package com.jbrowser.tv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebResourceRequest;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Lightweight Android-WebView filter engine.
 *
 * It intentionally implements the high-value, portable subset of ABP/uBlock filter
 * syntax: hosts entries, domain-anchored network filters, common resource-type and
 * domain-scope options, important rules, basic exceptions, and ordinary CSS cosmetic
 * selectors. uBO scriptlets, procedural cosmetics, redirects, CSP modifiers and some
 * option combinations still require the full extension engine and are not interpreted.
 */
public final class AdBlockEngine {
    public interface UpdateCallback {
        void onFinished(boolean success, int ruleCount, String message);
    }

    private static final String FILTER_FILE = "jbrowser-filters.txt";
    private static final String[] FILTER_URLS = new String[] {
            "https://ublockorigin.github.io/uAssets/filters/filters.min.txt",
            "https://ublockorigin.github.io/uAssets/filters/quick-fixes.min.txt",
            "https://ublockorigin.github.io/uAssets/filters/privacy.min.txt",
            "https://ublockorigin.github.io/uAssets/filters/unbreak.min.txt",
            "https://ublockorigin.github.io/uAssets/thirdparties/easylist.txt",
            "https://ublockorigin.github.io/uAssets/thirdparties/easyprivacy.txt",
            "https://ublockorigin.github.io/uAssets/thirdparties/easylist-cookies.txt",
            "https://ublockorigin.github.io/uAssets/filters/annoyances-cookies.txt",
            "https://ublockorigin.github.io/uAssets/filters/annoyances.txt",
            "https://ublockorigin.github.io/uAssets/filters/annoyances-others.txt",
            "https://ublockorigin.github.io/uAssets/thirdparties/easylist-newsletters.txt",
            "https://ublockorigin.github.io/uAssets/filters/badware.min.txt",
            "https://malware-filter.gitlab.io/malware-filter/urlhaus-filter-hosts.txt"
    };

    private static final int MAX_FILTER_BYTES_PER_LIST = 6 * 1024 * 1024;
    private static final int MAX_COSMETIC_RULES = 9000;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "JBrowser-FilterWorker");
        t.setDaemon(true);
        return t;
    });

    private final Context appContext;
    private final AtomicReference<Snapshot> active = new AtomicReference<>(Snapshot.empty());
    // Cosmetic CSS is reused across reloads. Keep the cache small because selectors can be large.
    private final Map<String, String> cosmeticCssCache = Collections.synchronizedMap(
            new LinkedHashMap<String, String>(32, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > 24;
                }
            });
    private final AtomicInteger blockedCount = new AtomicInteger(0);
    private final AtomicReference<String> topLevelUrl = new AtomicReference<>("");
    private volatile boolean enabled = true;

    public AdBlockEngine(Context context) {
        appContext = context.getApplicationContext();
        loadCachedFilters();
    }

    public void setEnabled(boolean value) { enabled = value; }
    public boolean isEnabled() { return enabled; }
    public int getBlockedCount() { return blockedCount.get(); }
    public int getRuleCount() { return active.get().ruleCount; }
    public void resetBlockedCount() { blockedCount.set(0); }
    public void setTopLevelUrl(String url) { topLevelUrl.set(url == null ? "" : url); }

    /** Per-site controls are keyed by the normalized host, not by a full URL. */
    public boolean isSiteNetworkEnabled(String siteUrl) {
        String host = hostOf(siteUrl);
        return host.isEmpty() || appContext.getSharedPreferences("jbrowser", Context.MODE_PRIVATE)
                .getBoolean("site_network_" + host, true);
    }

    public boolean isSiteCosmeticEnabled(String siteUrl) {
        String host = hostOf(siteUrl);
        return host.isEmpty() || appContext.getSharedPreferences("jbrowser", Context.MODE_PRIVATE)
                .getBoolean("site_cosmetic_" + host, true);
    }

    public void setSiteNetworkEnabled(String siteUrl, boolean value) {
        String host = hostOf(siteUrl);
        if (!host.isEmpty()) appContext.getSharedPreferences("jbrowser", Context.MODE_PRIVATE)
                .edit().putBoolean("site_network_" + host, value).apply();
    }

    public void setSiteCosmeticEnabled(String siteUrl, boolean value) {
        String host = hostOf(siteUrl);
        if (!host.isEmpty()) {
            appContext.getSharedPreferences("jbrowser", Context.MODE_PRIVATE)
                    .edit().putBoolean("site_cosmetic_" + host, value).apply();
            clearCosmeticCache();
        }
    }

    public void resetSiteSettings(String siteUrl) {
        String host = hostOf(siteUrl);
        if (!host.isEmpty()) {
            appContext.getSharedPreferences("jbrowser", Context.MODE_PRIVATE)
                    .edit().remove("site_network_" + host).remove("site_cosmetic_" + host).apply();
            clearCosmeticCache();
        }
    }

    private void clearCosmeticCache() {
        synchronized (cosmeticCssCache) { cosmeticCssCache.clear(); }
    }

    public boolean shouldBlock(WebResourceRequest request) {
        return shouldBlock(request, topLevelUrl.get());
    }

    /** Pass the owning tab's top-level URL so requests from multiple tabs stay isolated. */
    public boolean shouldBlock(WebResourceRequest request, String pageUrl) {
        if (!enabled || request == null || request.getUrl() == null) return false;
        String url = request.getUrl().toString();
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return false;
        if (request.isForMainFrame()) return false;

        String pageHost = hostOf(pageUrl);
        if (!isSiteNetworkEnabled(pageUrl)) return false;
        String resourceType = resourceType(request);
        boolean thirdParty = !pageHost.isEmpty() && !sameSite(pageHost, hostOf(url));
        boolean blocked = active.get().matches(url, pageHost, resourceType, thirdParty);
        if (blocked) blockedCount.incrementAndGet();
        return blocked;
    }

    public String cosmeticCssFor(String pageUrl) {
        return cosmeticCssFor(pageUrl, true, true);
    }

    public String cosmeticCssFor(String pageUrl, boolean includeCookieBanners) {
        return cosmeticCssFor(pageUrl, true, includeCookieBanners);
    }

    /** Returns filter CSS and optional cookie-banner CSS, with independent site and global switches. */
    public String cosmeticCssFor(String pageUrl, boolean applySiteCosmetics, boolean includeCookieBanners) {
        if (!enabled) return "";
        boolean useCosmetics = applySiteCosmetics && isSiteCosmeticEnabled(pageUrl);
        Snapshot s = active.get();
        String pageHost = hostOf(pageUrl);
        String cacheKey = pageHost + "|" + useCosmetics + "|" + includeCookieBanners;
        String cachedCss = cosmeticCssCache.get(cacheKey);
        if (cachedCss != null) return cachedCss;
        Set<String> selectors = new LinkedHashSet<>();
        if (useCosmetics) {
            for (CosmeticRule rule : s.globalCosmetics) {
                if (rule.matches(pageHost)) selectors.add(rule.selector);
            }
            // Look up only the page host and its parents. Each rule retains its own
            // negative-domain scope, so one excluded rule cannot cancel an unrelated rule.
            String candidate = pageHost;
            while (!candidate.isEmpty()) {
                List<CosmeticRule> domainRules = s.domainCosmetics.get(candidate);
                if (domainRules != null) {
                    for (CosmeticRule rule : domainRules) {
                        if (rule.matches(pageHost)) selectors.add(rule.selector);
                    }
                }
                int dot = candidate.indexOf('.');
                if (dot < 0) break;
                candidate = candidate.substring(dot + 1);
            }
            // Apply exceptions after collecting all matching rules.
            for (CosmeticExceptionRule exception : s.globalCosmeticExceptions) {
                if (exception.matches(pageHost)) selectors.remove(exception.selector);
            }
            candidate = pageHost;
            while (!candidate.isEmpty()) {
                List<CosmeticExceptionRule> exceptions = s.domainCosmeticExceptions.get(candidate);
                if (exceptions != null) {
                    for (CosmeticExceptionRule exception : exceptions) {
                        if (exception.matches(pageHost)) selectors.remove(exception.selector);
                    }
                }
                int dot = candidate.indexOf('.');
                if (dot < 0) break;
                candidate = candidate.substring(dot + 1);
            }
        }

        StringBuilder out = new StringBuilder(Math.min(64 * 1024, selectors.size() * 80));
        if (useCosmetics) appendSelectors(out, selectors);
        if (includeCookieBanners) {
            // Conservative, well-known consent widgets only. Avoid generic [role=dialog]
            // rules because they can hide sign-in dialogs, menus and video controls.
            out.append("\n#onetrust-banner-sdk,#onetrust-consent-sdk,.qc-cmp2-container,"
                    + ".fc-consent-root,.fc-dialog-container,#didomi-host,"
                    + ".cookie-consent-banner,.cookie-banner:not([data-essential]),"
                    + "[id^='sp_message_container_'],.cc-window,#CybotCookiebotDialog,"
                    + "#cookie-law-info-bar,.cky-consent-container,.osano-cm-window,"
                    + ".iubenda-cs-container,#CookiebotDialog{display:none!important;visibility:hidden!important;}");
        }
        if (useCosmetics) {
            // A deliberately narrow built-in baseline for obvious ad slots. This complements
            // downloaded lists without hiding arbitrary elements named merely "ad".
            out.append("\nins.adsbygoogle,iframe[id^='google_ads_iframe_'],"
                    + "div[id^='google_ads_iframe_'],[data-ad-slot],[data-ad-client],"
                    + "[aria-label='Advertisement'],[aria-label='advertisement']{"
                    + "display:none!important;visibility:hidden!important;}");
        }
        String css = out.toString();
        cosmeticCssCache.put(cacheKey, css);
        return css;
    }

    private static void appendSelectors(StringBuilder out, Iterable<String> selectors) {
        if (selectors == null) return;
        for (String selector : selectors) {
            if (selector == null || selector.isEmpty()) continue;
            // Each selector gets its own rule so an unsupported selector cannot invalidate
            // the rest of a filter list. Modern WebView CSS supports :has(), :is() and :where().
            out.append(selector).append("{display:none!important;visibility:hidden!important;}");
            if (out.length() >= 160 * 1024) break; // Bound injected CSS and the per-host cache on low-memory TVs.
        }
    }

    public void updateFilters(UpdateCallback callback) {
        EXECUTOR.execute(() -> {
            boolean success = false;
            String message;
            int downloaded = 0;
            File target = new File(appContext.getFilesDir(), FILTER_FILE);
            File temp = new File(appContext.getFilesDir(), FILTER_FILE + ".tmp");
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(temp), StandardCharsets.UTF_8))) {
                writer.write("! JBrowser downloaded filter bundle\n");
                for (String filterUrl : FILTER_URLS) {
                    HttpURLConnection connection = null;
                    try {
                        connection = (HttpURLConnection) new URL(filterUrl).openConnection();
                        connection.setConnectTimeout(9000);
                        connection.setReadTimeout(12000);
                        connection.setRequestProperty("User-Agent", "JBrowser/1.5.0 (Android TV; filter updater)");
                        connection.setRequestProperty("Accept", "text/plain,*/*;q=0.8");
                        connection.setInstanceFollowRedirects(true);
                        int status = connection.getResponseCode();
                        if (status < 200 || status >= 300) continue;
                        try (InputStream input = connection.getInputStream()) {
                            byte[] buffer = new byte[8192];
                            int read;
                            int total = 0;
                            StringBuilder chunk = new StringBuilder();
                            while ((read = input.read(buffer)) != -1 && total < MAX_FILTER_BYTES_PER_LIST) {
                                int accepted = Math.min(read, MAX_FILTER_BYTES_PER_LIST - total);
                                chunk.append(new String(buffer, 0, accepted, StandardCharsets.UTF_8));
                                total += accepted;
                            }
                            writer.write("\n! Source: ");
                            writer.write(filterUrl);
                            writer.write('\n');
                            writer.write(chunk.toString());
                            writer.write('\n');
                            downloaded += total;
                        }
                    } catch (Exception ignored) {
                        // Keep working with whatever valid sources were downloaded.
                    } finally {
                        if (connection != null) connection.disconnect();
                    }
                }
                writer.flush();
                if (downloaded > 0) {
                    if (target.exists() && !target.delete()) throw new IllegalStateException("Cannot replace filter cache");
                    if (!temp.renameTo(target)) throw new IllegalStateException("Cannot save filter cache");
                    Snapshot next = parseFile(target);
                    active.set(next);
                    clearCosmeticCache();
                    appContext.getSharedPreferences("jbrowser", Context.MODE_PRIVATE)
                            .edit().putLong("filters_updated_at", System.currentTimeMillis()).apply();
                    success = next.ruleCount > 0;
                    message = success ? "Protection lists updated" : "Downloaded lists contained no compatible rules";
                } else {
                    message = "Could not download filter lists; keeping existing protection";
                }
            } catch (Exception e) {
                message = "Filter update failed; keeping existing protection";
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
            final boolean finalSuccess = success;
            final String finalMessage = message;
            final int finalCount = active.get().ruleCount;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (callback != null) callback.onFinished(finalSuccess, finalCount, finalMessage);
            });
        });
    }

    public void loadCachedFilters() {
        EXECUTOR.execute(() -> {
            File file = new File(appContext.getFilesDir(), FILTER_FILE);
            if (file.exists() && file.length() > 0) {
                try {
                    active.set(parseFile(file));
                    clearCosmeticCache();
                } catch (Exception ignored) { }
            }
        });
    }

    public long getLastUpdateTime() {
        return appContext.getSharedPreferences("jbrowser", Context.MODE_PRIVATE)
                .getLong("filters_updated_at", 0L);
    }

    private Snapshot parseFile(File file) throws Exception {
        Set<String> blockedHosts = new HashSet<>();
        Set<String> allowedHosts = new HashSet<>();
        Map<String, List<NetworkRule>> byHost = new HashMap<>();
        Map<String, List<CosmeticRule>> domainCss = new HashMap<>();
        Map<String, List<CosmeticExceptionRule>> domainCosmeticExceptions = new HashMap<>();
        List<CosmeticExceptionRule> globalCosmeticExceptions = new ArrayList<>();
        List<CosmeticRule> globalCss = new ArrayList<>();
        int networkRules = 0;
        int cosmeticRules = 0;
        // ABP/uBO $badfilter lines disable an otherwise identical network rule.
        Set<String> disabledNetworkRules = collectBadfilterRules(file);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8), 64 * 1024)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("!") || line.startsWith("[") || line.startsWith("# ")) continue;
                if (disabledNetworkRules.contains(canonicalNetworkRule(line))) continue;

                // Hosts-format lists are also accepted (0.0.0.0 ads.example.com).
                if (line.startsWith("0.0.0.0 ") || line.startsWith("127.0.0.1 ")) {
                    String[] hostsLine = line.split("\\s+");
                    for (int i = 1; i < hostsLine.length; i++) {
                        String h = hostsLine[i].trim().toLowerCase(Locale.ROOT);
                        if (isValidHost(h) && !h.equals("localhost")) blockedHosts.add(h);
                    }
                    continue;
                }

                int regularCosmeticAt = line.indexOf("##");
                int exceptionCosmeticAt = line.indexOf("#@#");
                boolean cosmeticException = exceptionCosmeticAt >= 0
                        && (regularCosmeticAt < 0 || exceptionCosmeticAt <= regularCosmeticAt);
                int cosmeticAt = cosmeticException ? exceptionCosmeticAt : regularCosmeticAt;
                if (cosmeticAt >= 0) {
                    int delimiterLength = cosmeticException ? 3 : 2;
                    String domains = line.substring(0, cosmeticAt).trim();
                    String selector = line.substring(cosmeticAt + delimiterLength).trim();
                    if (isSupportedCosmeticSelector(selector) && cosmeticRules < MAX_COSMETIC_RULES) {
                        List<String> includedDomains = new ArrayList<>();
                        List<String> excludedDomains = new ArrayList<>();
                        if (!domains.isEmpty()) {
                            for (String rawDomain : domains.split(",")) {
                                String domain = rawDomain.trim().toLowerCase(Locale.ROOT);
                                if (domain.startsWith("~")) {
                                    String excluded = domain.substring(1);
                                    if (isValidHost(excluded)) excludedDomains.add(excluded);
                                } else if (isValidHost(domain)) {
                                    includedDomains.add(domain);
                                }
                            }
                        }

                        if (!domains.isEmpty() && includedDomains.isEmpty() && excludedDomains.isEmpty()) {
                            continue; // Do not turn malformed scoped rules into global rules.
                        }
                        Set<String> excluded = new HashSet<>(excludedDomains);
                        if (cosmeticException) {
                            CosmeticExceptionRule rule = new CosmeticExceptionRule(excluded, selector);
                            if (includedDomains.isEmpty()) globalCosmeticExceptions.add(rule);
                            else {
                                for (String domain : includedDomains) {
                                    domainCosmeticExceptions.computeIfAbsent(domain, k -> new ArrayList<>()).add(rule);
                                }
                            }
                        } else {
                            CosmeticRule rule = new CosmeticRule(excluded, selector);
                            if (includedDomains.isEmpty()) globalCss.add(rule);
                            else {
                                for (String domain : includedDomains) {
                                    domainCss.computeIfAbsent(domain, k -> new ArrayList<>()).add(rule);
                                }
                            }
                        }
                        cosmeticRules++;
                    }
                    continue;
                }

                boolean exception = line.startsWith("@@");
                if (exception) line = line.substring(2);
                String patternPart = line;
                String optionPart = "";
                int dollar = line.lastIndexOf('$');
                if (dollar > 0) {
                    patternPart = line.substring(0, dollar);
                    optionPart = line.substring(dollar + 1);
                }
                if (!optionPart.isEmpty() && hasUnsupportedOptions(optionPart)) continue;

                String host = extractAnchoredHost(patternPart);
                if (host != null) {
                    String path = extractPathPattern(patternPart, host);
                    if (path.isEmpty() && optionPart.isEmpty()) {
                        if (exception) allowedHosts.add(host); else blockedHosts.add(host);
                    } else {
                        try {
                            NetworkRule rule = new NetworkRule(host,
                                    path.isEmpty() ? Pattern.compile("^.*") : wildcardToRegex(path, optionPart),
                                    optionPart, exception);
                            byHost.computeIfAbsent(host, k -> new ArrayList<>()).add(rule);
                            networkRules++;
                        } catch (PatternSyntaxException ignored) { }
                    }
                } else if (patternPart.startsWith("/")) {
                    // URL-fragment-style generic filters are rare and can be expensive;
                    // ignore them rather than add an O(all-rules) check to every request.
                }
            }
        }
        int ruleCount = networkRules + blockedHosts.size() + allowedHosts.size();
        return new Snapshot(blockedHosts, allowedHosts, byHost, globalCss, domainCss,
                globalCosmeticExceptions, domainCosmeticExceptions, ruleCount);
    }

    private static Set<String> collectBadfilterRules(File file) throws Exception {
        Set<String> disabled = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8), 32 * 1024)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String signature = badfilterTarget(line.trim());
                if (signature != null) disabled.add(signature);
            }
        }
        return disabled;
    }

    /** Returns the corresponding ordinary rule when this line carries $badfilter. */
    private static String badfilterTarget(String line) {
        if (line == null || line.isEmpty() || line.contains("##") || line.contains("#@#")) return null;
        int dollar = line.lastIndexOf('$');
        if (dollar <= 0) return null;
        String[] options = line.substring(dollar + 1).split(",");
        ArrayList<String> kept = new ArrayList<>();
        boolean found = false;
        for (String option : options) {
            String trimmed = option.trim();
            if (trimmed.equalsIgnoreCase("badfilter")) found = true;
            else if (!trimmed.isEmpty()) kept.add(trimmed);
        }
        if (!found) return null;
        return canonicalNetworkRule(line);
    }

    /** Normalizes a rule so a $badfilter line can suppress its matching regular rule. */
    private static String canonicalNetworkRule(String line) {
        if (line == null) return "";
        int dollar = line.lastIndexOf('$');
        if (dollar <= 0) return line.trim();
        String[] options = line.substring(dollar + 1).split(",");
        ArrayList<String> kept = new ArrayList<>();
        for (String option : options) {
            String trimmed = option.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.equals("badfilter") && !trimmed.isEmpty()) kept.add(trimmed);
        }
        Collections.sort(kept);
        return line.substring(0, dollar).trim() + (kept.isEmpty() ? "" : "$" + String.join(",", kept));
    }

    private static boolean isSupportedCosmeticSelector(String selector) {
        if (selector == null || selector.isEmpty() || selector.length() > 420) return false;
        if (selector.startsWith("+js(") || selector.contains(":has-text(")
                || selector.contains(":matches-css(") || selector.contains(":xpath(")
                || selector.contains(":upward(") || selector.contains(":style(")
                || selector.contains(":remove(") || selector.contains(":watch-attr(")) return false;
        // Allow common CSS syntax including modern :has/:is/:where and attribute selectors,
        // while excluding braces, semicolons and other declaration-injection characters.
        return selector.matches("[A-Za-z0-9_#.:,>+~*\\[\\]\\-='%\"()\\s]+")
                && !selector.contains("{ ") && !selector.contains("}") && !selector.contains(";");
    }

    private static String extractAnchoredHost(String pattern) {
        if (pattern == null || !pattern.startsWith("||")) return null;
        int end = pattern.length();
        for (char c : new char[] {'^', '/', '*', '|', '?', ':'}) {
            int at = pattern.indexOf(c, 2);
            if (at >= 0 && at < end) end = at;
        }
        String host = pattern.substring(2, end).toLowerCase(Locale.ROOT);
        if (!isValidHost(host)) return null;
        return host;
    }

    private static String extractPathPattern(String original, String host) {
        int start = 2 + host.length();
        if (start >= original.length()) return "";
        String rest = original.substring(start);
        if (rest.equals("^") || rest.equals("^|") || rest.equals("|")) return "";
        if (rest.startsWith("^/")) return rest.substring(1);
        if (rest.startsWith("/") || rest.startsWith("^")) return rest;
        return "";
    }

    private static boolean hasUnsupportedOptions(String options) {
        for (String raw : options.split(",")) {
            String option = raw.trim().toLowerCase(Locale.ROOT);
            if (option.isEmpty() || option.equals("third-party") || option.equals("~third-party")
                    || option.equals("important") || option.equals("match-case")
                    || option.equals("badfilter")) continue;
            if (option.startsWith("domain=")) {
                String value = option.substring("domain=".length());
                if (value.isEmpty()) return true;
                boolean validScope = false;
                for (String rawDomain : value.split("\\|")) {
                    String domain = rawDomain.startsWith("~") ? rawDomain.substring(1) : rawDomain;
                    if (isValidHost(domain)) validScope = true;
                    else return true;
                }
                if (!validScope) return true;
                continue;
            }
            if (option.startsWith("redirect") || option.startsWith("csp")
                    || option.startsWith("removeparam") || option.startsWith("badfilter")
                    || option.startsWith("method=") || option.startsWith("to=")) return true;
            String type = option.startsWith("~") ? option.substring(1) : option;
            if (normalizeFilterType(type) == null) return true;
        }
        return false;
    }

    private static String normalizeFilterType(String type) {
        if (type == null) return null;
        switch (type.toLowerCase(Locale.ROOT)) {
            case "all": return "all";
            case "script": return "script";
            case "image": return "image";
            case "stylesheet": return "stylesheet";
            case "xmlhttprequest":
            case "xhr": return "xmlhttprequest";
            case "subdocument":
            case "frame": return "subdocument";
            case "font": return "font";
            case "media": return "media";
            case "ping": return "ping";
            case "other": return "other";
            case "object":
            case "object-subrequest": return "object";
            case "document": return "document";
            default: return null;
        }
    }

    private static Pattern wildcardToRegex(String path, String options) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '*') regex.append(".*");
            else if (c == '^') regex.append("(?:[^a-zA-Z0-9_.%-]|$)");
            else if ("\\.[]{}()+-?$|".indexOf(c) >= 0) regex.append('\\').append(c);
            else regex.append(c);
        }
        boolean matchCase = false;
        for (String raw : (options == null ? "" : options).split(",")) {
            if (raw.trim().equalsIgnoreCase("match-case")) { matchCase = true; break; }
        }
        return Pattern.compile(regex.toString(), matchCase ? 0 : Pattern.CASE_INSENSITIVE);
    }

    private static String resourceType(WebResourceRequest request) {
        if (request.isForMainFrame()) return "document";
        Map<String, String> h = request.getRequestHeaders();
        if (h != null) {
            String dest = headerIgnoreCase(h, "Sec-Fetch-Dest");
            if (dest != null) {
                String normalized = dest.toLowerCase(Locale.ROOT);
                if (normalized.equals("style")) return "stylesheet";
                if (normalized.equals("iframe")) return "subdocument";
                if (normalized.equals("empty")) return "xmlhttprequest";
                if (normalized.equals("video") || normalized.equals("audio")) return "media";
                if (normalized.equals("object") || normalized.equals("embed")) return "object";
                if (normalized.equals("document")) return "document";
                return normalized;
            }
            String accept = headerIgnoreCase(h, "Accept");
            if (accept != null) {
                String a = accept.toLowerCase(Locale.ROOT);
                if (a.startsWith("image/")) return "image";
                if (a.contains("text/css")) return "stylesheet";
                if (a.contains("javascript") || a.contains("ecmascript")) return "script";
                if (a.contains("font/")) return "font";
                if (a.contains("video/") || a.contains("audio/")) return "media";
                if (a.contains("application/json")) return "xmlhttprequest";
                if (a.contains("text/html")) return "subdocument";
                if (a.contains("application/x-shockwave-flash") || a.contains("application/pdf")) return "object";
            }
        }
        return "other";
    }

    private static String headerIgnoreCase(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        }
        return null;
    }

    static String hostOf(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (Exception e) { return ""; }
    }

    private static boolean isValidHost(String host) {
        return host != null && host.length() > 2 && host.length() < 254
                && host.matches("[a-z0-9.-]+") && host.contains(".")
                && !host.startsWith(".") && !host.endsWith(".");
    }

    private static boolean domainMatches(String host, String ruleHost) {
        return host.equals(ruleHost) || host.endsWith("." + ruleHost);
    }

    private static final Set<String> MULTI_LABEL_PUBLIC_SUFFIXES = new HashSet<>(java.util.Arrays.asList(
            "co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "net.au", "org.au",
            "co.nz", "org.nz", "co.jp", "or.jp", "ne.jp", "com.br", "com.cn",
            "net.cn", "org.cn", "com.hk", "com.sg", "com.my", "com.ph", "com.tw",
            "co.in", "firm.in", "net.in", "org.in", "com.mx", "com.ar", "com.co",
            "com.pe", "com.tr", "com.ua", "co.za"));

    private static boolean sameSite(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return true;
        return registrableDomain(a).equals(registrableDomain(b));
    }

    /** Best-effort registrable-domain comparison for first/third-party filter options.
     * This is intentionally a compact common-suffix table, not a complete Public Suffix List.
     */
    private static String registrableDomain(String host) {
        if (host == null || host.isEmpty() || host.indexOf(':') >= 0 || host.matches("\\d{1,3}(?:\\.\\d{1,3}){3}")) {
            return host == null ? "" : host;
        }
        String[] labels = host.toLowerCase(Locale.ROOT).split("\\.");
        if (labels.length <= 2) return host.toLowerCase(Locale.ROOT);
        String suffix2 = labels[labels.length - 2] + "." + labels[labels.length - 1];
        int start = MULTI_LABEL_PUBLIC_SUFFIXES.contains(suffix2) ? labels.length - 3 : labels.length - 2;
        return String.join(".", java.util.Arrays.copyOfRange(labels, Math.max(0, start), labels.length));
    }

    private void unused() { /* Keeps the class easy to extend without static state. */ }

    private static final class NetworkRule {
        final String host;
        final Pattern pathPattern;
        final boolean thirdPartyOnly;
        final boolean firstPartyOnly;
        final boolean exception;
        final boolean important;
        final Set<String> types;
        final Set<String> excludedTypes;
        final Set<String> includedDomains;
        final Set<String> excludedDomains;

        NetworkRule(String host, Pattern pathPattern, String options, boolean exception) {
            this.host = host;
            this.pathPattern = pathPattern;
            this.exception = exception;
            boolean t = false, f = false, priority = false;
            Set<String> parsedTypes = new HashSet<>();
            Set<String> parsedExcludedTypes = new HashSet<>();
            Set<String> parsedIncludedDomains = new HashSet<>();
            Set<String> parsedExcludedDomains = new HashSet<>();
            if (options != null && !options.isEmpty()) {
                for (String raw : options.split(",")) {
                    String option = raw.trim().toLowerCase(Locale.ROOT);
                    if (option.equals("third-party")) t = true;
                    else if (option.equals("~third-party")) f = true;
                    else if (option.equals("important")) priority = true;
                    else if (option.startsWith("domain=")) {
                        for (String rawDomain : option.substring("domain=".length()).split("\\|")) {
                            if (rawDomain.startsWith("~")) parsedExcludedDomains.add(rawDomain.substring(1));
                            else parsedIncludedDomains.add(rawDomain);
                        }
                    } else {
                        boolean negative = option.startsWith("~");
                        String normalized = normalizeFilterType(negative ? option.substring(1) : option);
                        if (normalized != null) {
                            if (negative) parsedExcludedTypes.add(normalized);
                            else parsedTypes.add(normalized);
                        }
                    }
                }
            }
            thirdPartyOnly = t;
            firstPartyOnly = f;
            important = priority;
            types = parsedTypes;
            excludedTypes = parsedExcludedTypes;
            includedDomains = parsedIncludedDomains;
            excludedDomains = parsedExcludedDomains;
        }

        boolean matches(String url, String pageHost, String resourceType, boolean thirdParty) {
            String hostNow = hostOf(url);
            if (!domainMatches(hostNow, host)) return false;
            if (thirdPartyOnly && !thirdParty) return false;
            if (firstPartyOnly && thirdParty) return false;
            if (!types.isEmpty() && !types.contains("all") && !types.contains(resourceType)) return false;
            if (excludedTypes.contains("all") || excludedTypes.contains(resourceType)) return false;
            if (!includedDomains.isEmpty()) {
                boolean included = false;
                for (String domain : includedDomains) {
                    if (domainMatches(pageHost, domain)) { included = true; break; }
                }
                if (!included) return false;
            }
            for (String domain : excludedDomains) {
                if (domainMatches(pageHost, domain)) return false;
            }
            int index = url.toLowerCase(Locale.ROOT).indexOf(host);
            String tail = index >= 0 ? url.substring(index + host.length()) : url;
            return pathPattern.matcher(tail).find();
        }
    }

    private static class CosmeticRuleBase {
        final Set<String> excludedDomains;
        final String selector;

        CosmeticRuleBase(Set<String> excluded, String selector) {
            excludedDomains = Collections.unmodifiableSet(new HashSet<>(excluded));
            this.selector = selector;
        }

        boolean matches(String pageHost) {
            for (String domain : excludedDomains) {
                if (domainMatches(pageHost, domain)) return false;
            }
            return true;
        }
    }

    private static final class CosmeticRule extends CosmeticRuleBase {
        CosmeticRule(Set<String> excluded, String selector) { super(excluded, selector); }
    }

    private static final class CosmeticExceptionRule extends CosmeticRuleBase {
        CosmeticExceptionRule(Set<String> excluded, String selector) { super(excluded, selector); }
    }

    private static final class Snapshot {
        final Set<String> blockedHosts;
        final Set<String> allowedHosts;
        final Map<String, List<NetworkRule>> byHost;
        final List<CosmeticRule> globalCosmetics;
        final Map<String, List<CosmeticRule>> domainCosmetics;
        final List<CosmeticExceptionRule> globalCosmeticExceptions;
        final Map<String, List<CosmeticExceptionRule>> domainCosmeticExceptions;
        final int ruleCount;

        Snapshot(Set<String> blocked, Set<String> allowed, Map<String, List<NetworkRule>> rules,
                 List<CosmeticRule> globalCss, Map<String, List<CosmeticRule>> domainCss,
                 List<CosmeticExceptionRule> globalCssExceptions,
                 Map<String, List<CosmeticExceptionRule>> domainCssExceptions,
                 int count) {
            blockedHosts = Collections.unmodifiableSet(blocked);
            allowedHosts = Collections.unmodifiableSet(allowed);
            Map<String, List<NetworkRule>> immutableRules = new HashMap<>();
            for (Map.Entry<String, List<NetworkRule>> e : rules.entrySet())
                immutableRules.put(e.getKey(), Collections.unmodifiableList(e.getValue()));
            byHost = Collections.unmodifiableMap(immutableRules);
            globalCosmetics = Collections.unmodifiableList(globalCss);
            Map<String, List<CosmeticRule>> immutableCss = new HashMap<>();
            for (Map.Entry<String, List<CosmeticRule>> e : domainCss.entrySet())
                immutableCss.put(e.getKey(), Collections.unmodifiableList(new ArrayList<>(e.getValue())));
            domainCosmetics = Collections.unmodifiableMap(immutableCss);
            globalCosmeticExceptions = Collections.unmodifiableList(new ArrayList<>(globalCssExceptions));
            Map<String, List<CosmeticExceptionRule>> immutableExceptions = new HashMap<>();
            for (Map.Entry<String, List<CosmeticExceptionRule>> e : domainCssExceptions.entrySet())
                immutableExceptions.put(e.getKey(), Collections.unmodifiableList(new ArrayList<>(e.getValue())));
            domainCosmeticExceptions = Collections.unmodifiableMap(immutableExceptions);
            ruleCount = count;
        }

        static Snapshot empty() {
            return new Snapshot(new HashSet<>(), new HashSet<>(), new HashMap<>(),
                    new ArrayList<>(), new HashMap<>(), new ArrayList<>(), new HashMap<>(), 0);
        }

        boolean matches(String url, String pageHost, String resourceType, boolean thirdParty) {
            String host = hostOf(url);
            if (host.isEmpty()) return false;

            // $important blocking rules take precedence over allow/exception rules.
            if (matchesNetworkRules(url, host, pageHost, resourceType, thirdParty, false, true)) return true;
            if (matchesDomainSet(allowedHosts, host)) return false;

            // A matching exception exempts the request from ordinary blocking rules.
            if (matchesNetworkRules(url, host, pageHost, resourceType, thirdParty, true, false)) return false;
            if (matchesDomainSet(blockedHosts, host)) return true;

            return matchesNetworkRules(url, host, pageHost, resourceType, thirdParty, false, false);
        }

        private boolean matchesNetworkRules(String url, String host, String pageHost,
                                            String resourceType, boolean thirdParty,
                                            boolean exceptionsOnly, boolean importantOnly) {
            String candidate = host;
            while (candidate.contains(".")) {
                List<NetworkRule> rules = byHost.get(candidate);
                if (rules != null) {
                    for (NetworkRule rule : rules) {
                        if (exceptionsOnly != rule.exception) continue;
                        if (importantOnly != rule.important) continue;
                        if (rule.matches(url, pageHost, resourceType, thirdParty)) return true;
                    }
                }
                int dot = candidate.indexOf('.');
                if (dot < 0 || dot == candidate.length() - 1) break;
                candidate = candidate.substring(dot + 1);
            }
            return false;
        }

        private static boolean matchesDomainSet(Set<String> domains, String host) {
            String candidate = host;
            while (candidate.contains(".")) {
                if (domains.contains(candidate)) return true;
                int dot = candidate.indexOf('.');
                candidate = candidate.substring(dot + 1);
            }
            return domains.contains(candidate);
        }
    }
}
