package com.jbrowser.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.database.Cursor;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.PermissionRequest;
import android.webkit.SafeBrowsingResponse;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.URLUtil;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONTokener;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/** JBrowser's TV-first browser shell. All controls are focusable with a TV remote. */
public class MainActivity extends Activity {
    private static final int MAX_TABS = 5;
    private static final int BG = Color.rgb(8, 13, 24);
    private static final int PANEL = Color.rgb(17, 29, 46);
    private static final int PANEL_2 = Color.rgb(24, 40, 60);
    private static final int ACCENT = Color.rgb(73, 213, 197);
    private static final int TEXT = Color.rgb(243, 248, 255);
    private static final int MUTED = Color.rgb(153, 174, 194);

    private final ArrayList<Tab> tabs = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout pageRoot;
    private EditText addressBar;
    private TextView statusText;
    private TextView tabCountButton;
    private TextView adBlockButton;
    private View moreMenuButton;
    private HorizontalScrollView toolbarScroll;
    private HorizontalScrollView tabStripScroll;
    private LinearLayout tabStrip;
    private ProgressBar pageProgress;
    private FrameLayout webContainer;
    private View homeView;
    private View customView;
    private FrameLayout fullscreenRoot;
    private LinearLayout fullscreenControlBar;
    private TextView fullscreenPlayButton;
    private TextView fullscreenVideoTitle;
    private TextView fullscreenTimeText;
    private TextView fullscreenSpeedButton;
    private SeekBar fullscreenSeekBar;
    private boolean videoSeekInProgress;
    private double currentVideoDurationSeconds;
    private double currentVideoPositionSeconds;
    private float playbackSpeed = 1.0f;
    private float defaultPlaybackSpeed = 1.0f;
    private static final int MAX_HISTORY_ENTRIES = 200;
    private static final int REQUEST_WRITE_DOWNLOAD_PERMISSION = 1301;
    private static final int REQUEST_FILE_CHOOSER = 1302;
    private ValueCallback<Uri[]> pendingFileChooserCallback;
    private String pendingDownloadUrl;
    private String pendingDownloadUserAgent;
    private String pendingDownloadDisposition;
    private String pendingDownloadMimeType;
    private Runnable videoStatusPoller;
    private String searchEngineName = "Google";
    private String searchEngineTemplate = "https://www.google.com/search?q=%s";
    private WebChromeClient.CustomViewCallback customViewCallback;
    private AdBlockEngine adBlocker;
    private BookmarkStore bookmarkStore;
    private int activeIndex = -1;
    private boolean desktopMode = false;
    private boolean cookieCleaner = true;
    private boolean thirdPartyCookies = true;
    private boolean allowMediaAutoplay = true;
    private boolean privateMode;

    private static final class RecentSite {
        final String title;
        final String url;
        RecentSite(String title, String url) { this.title = title; this.url = url; }
    }

    private static final class DownloadEntry {
        final long id;
        final String title;
        final String mimeType;
        final int status;
        final long downloaded;
        final long total;
        DownloadEntry(long id, String title, String mimeType, int status, long downloaded, long total) {
            this.id = id; this.title = title; this.mimeType = mimeType;
            this.status = status; this.downloaded = downloaded; this.total = total;
        }
    }

    private static final class Tab {
        WebView webView;
        String url = "";
        String title = "New tab";
        boolean isHome = true;
        boolean isLoading = false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        privateMode = this instanceof PrivateActivity;
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        applyImmersiveMode();

        copyBundledFiltersIfNeeded();
        adBlocker = new AdBlockEngine(this);
        bookmarkStore = new BookmarkStore(this);
        android.content.SharedPreferences prefs = getSharedPreferences("jbrowser", MODE_PRIVATE);
        adBlocker.setEnabled(prefs.getBoolean("adblock_enabled", true));
        desktopMode = prefs.getBoolean("desktop_mode", false);
        cookieCleaner = prefs.getBoolean("cookie_cleaner", true);
        thirdPartyCookies = prefs.getBoolean("third_party_cookies", true);
        allowMediaAutoplay = prefs.getBoolean("media_autoplay_allowed", true);
        defaultPlaybackSpeed = prefs.getFloat("default_playback_speed", 1.0f);
        if (!isSupportedPlaybackSpeed(defaultPlaybackSpeed)) defaultPlaybackSpeed = 1.0f;
        searchEngineName = prefs.getString("search_engine_name", "Google");
        searchEngineTemplate = prefs.getString("search_engine_template", "https://www.google.com/search?q=%s");
        if (!isValidSearchTemplate(searchEngineTemplate)) {
            searchEngineName = "Google";
            searchEngineTemplate = "https://www.google.com/search?q=%s";
        }

        if (privateMode) clearPrivateSessionData();
        buildInterface();
        Tab first = createTab();
        activateTab(tabs.indexOf(first));

        Uri launchUri = getIntent() == null ? null : getIntent().getData();
        if (launchUri != null) navigate(launchUri.toString());

        long lastUpdate = adBlocker.getLastUpdateTime();
        if (!privateMode && (lastUpdate == 0L || System.currentTimeMillis() - lastUpdate > 7L * 24L * 60L * 60L * 1000L)) {
            status("Updating protection lists in the background…");
            adBlocker.updateFilters((success, count, message) -> {
                status(message + " • " + count + " rules");
                updateToolbarState();
            });
        } else {
            updateToolbarState();
        }
    }

    private void applyImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void copyBundledFiltersIfNeeded() {
        File target = new File(getFilesDir(), "jbrowser-filters.txt");
        if (target.exists() && target.length() > 0) return;
        try (InputStream in = getAssets().open("default-domains.txt");
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        } catch (Exception ignored) { }
    }

    private void buildInterface() {
        pageRoot = new LinearLayout(this);
        pageRoot.setOrientation(LinearLayout.VERTICAL);
        pageRoot.setBackgroundColor(BG);
        pageRoot.setPadding(dp(22), dp(12), dp(22), dp(8));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = text("JBrowser", 25, TEXT, true);
        brand.setLetterSpacing(-0.025f);
        brand.setPadding(0, 0, dp(18), 0);
        top.addView(brand, new LinearLayout.LayoutParams(dp(142), dp(52)));

        addressBar = new EditText(this);
        addressBar.setSingleLine(true);
        addressBar.setTextSize(17);
        addressBar.setTextColor(TEXT);
        addressBar.setHintTextColor(MUTED);
        addressBar.setHint("Search or enter website address");
        addressBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        addressBar.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        addressBar.setSelectAllOnFocus(true);
        addressBar.setPadding(dp(16), 0, dp(16), 0);
        addressBar.setBackground(round(PANEL_2, 13, Color.rgb(50, 75, 96), 1));
        addressBar.setOnFocusChangeListener((v, focused) ->
                v.setBackground(round(focused ? PANEL : PANEL_2, 13,
                        focused ? ACCENT : Color.rgb(50, 75, 96), focused ? 2 : 1)));
        addressBar.setLayoutParams(new LinearLayout.LayoutParams(0, dp(48), 1f));
        addressBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                navigate(addressBar.getText().toString());
                hideKeyboard();
                return true;
            }
            return false;
        });
        top.addView(addressBar);
        TextView go = control("GO", ACCENT, BG, 64);
        go.setOnClickListener(v -> { navigate(addressBar.getText().toString()); hideKeyboard(); });
        LinearLayout.LayoutParams goLp = new LinearLayout.LayoutParams(dp(64), dp(48));
        goLp.leftMargin = dp(9);
        top.addView(go, goLp);
        pageRoot.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(6), 0, dp(8));
        addAction(actions, "‹", "Back", this::goBack);
        addAction(actions, "›", "Forward", this::goForward);
        addAction(actions, "↻", "Reload", this::reload);
        addAction(actions, "⌂", "Home", this::goHome);
        addAction(actions, "+", "New tab", this::newTab);
        tabCountButton = addAction(actions, "TABS 1", "Switch tabs", this::showTabsDialog);
        addAction(actions, "☆", "Save bookmark", this::promptAddBookmark);
        addAction(actions, "★", "Bookmarks", this::showBookmarks);
        adBlockButton = addAction(actions, "BLOCK ON", "Toggle ad and tracker blocking", this::toggleAdBlock);
        TextView more = addAction(actions, "⋮", "More settings", () -> showMoreMenu(actions));
        more.setTextSize(24);
        moreMenuButton = more;
        toolbarScroll = new HorizontalScrollView(this);
        toolbarScroll.setHorizontalScrollBarEnabled(false);
        toolbarScroll.setFillViewport(false);
        toolbarScroll.setClipToPadding(false);
        toolbarScroll.addView(actions, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pageRoot.addView(toolbarScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));

        tabStripScroll = new HorizontalScrollView(this);
        tabStripScroll.setHorizontalScrollBarEnabled(false);
        tabStripScroll.setFillViewport(false);
        tabStripScroll.setClipToPadding(false);
        tabStrip = new LinearLayout(this);
        tabStrip.setOrientation(LinearLayout.HORIZONTAL);
        tabStrip.setGravity(Gravity.CENTER_VERTICAL);
        tabStrip.setPadding(0, dp(2), 0, dp(5));
        tabStripScroll.addView(tabStrip, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pageRoot.addView(tabStripScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        pageProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pageProgress.setMax(100);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            pageProgress.setProgressTintList(ColorStateList.valueOf(ACCENT));
            pageProgress.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(35, 48, 65)));
        }
        pageProgress.setVisibility(View.GONE);
        pageRoot.addView(pageProgress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        webContainer = new FrameLayout(this);
        webContainer.setBackground(round(Color.rgb(11, 19, 31), 14, Color.rgb(32, 47, 66), 1));
        LinearLayout.LayoutParams contentLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        contentLp.bottomMargin = dp(7);
        pageRoot.addView(webContainer, contentLp);

        statusText = text(privateMode ? "Private browsing • Nothing is saved to normal history" : "Ready • Ad and tracker protection enabled", 12, MUTED, false);
        statusText.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        statusText.setPadding(dp(5), 0, dp(5), 0);
        pageRoot.addView(statusText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));
        setContentView(pageRoot);
        addressBar.requestFocus();
    }

    private TextView addAction(LinearLayout row, String label, String description, Runnable action) {
        TextView button = control(label, TEXT, TEXT, 58);
        button.setContentDescription(description);
        button.setTextSize(label.length() > 5 ? 12 : 21);
        button.setOnClickListener(v -> action.run());
        int width = label.equals("BLOCK ON") ? 92 : (label.startsWith("TABS") ? 76 : 58);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(width), dp(43));
        lp.rightMargin = dp(6);
        row.addView(button, lp);
        return button;
    }

    private TextView control(String label, int foreground, int ignored, int widthDp) {
        TextView view = text(label, 15, foreground, true);
        view.setGravity(Gravity.CENTER);
        view.setFocusable(true);
        view.setClickable(true);
        view.setMinWidth(dp(widthDp));
        view.setBackground(round(PANEL, 10, Color.TRANSPARENT, 0));
        view.setOnFocusChangeListener((v, focused) -> {
            v.setBackground(round(focused ? ACCENT : PANEL, 10, focused ? ACCENT : Color.TRANSPARENT, focused ? 1 : 0));
            if (v instanceof TextView) ((TextView) v).setTextColor(focused ? BG : foreground);
            v.setScaleX(focused ? 1.035f : 1f);
            v.setScaleY(focused ? 1.035f : 1f);
        });
        return view;
    }

    private TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable round(int color, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) d.setStroke(dp(strokeDp), strokeColor);
        return d;
    }

    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    private View buildHomePage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(34), dp(24), dp(34), dp(28));

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setBackground(round(PANEL, 20, Color.rgb(34, 58, 78), 1));
        hero.setPadding(dp(24), dp(18), dp(24), dp(18));

        LinearLayout heroCopy = new LinearLayout(this);
        heroCopy.setOrientation(LinearLayout.VERTICAL);
        TextView headline = text(privateMode ? "Private browsing" : "Your web. Your big screen.", 30, TEXT, true);
        heroCopy.addView(headline);
        TextView subtitle = text(privateMode
                ? "This private window is separate from your normal browser session."
                : "Search with " + searchEngineName + " or jump straight to a favorite site.", 15, MUTED, false);
        LinearLayout.LayoutParams subtitleLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleLp.topMargin = dp(7);
        heroCopy.addView(subtitle, subtitleLp);
        hero.addView(heroCopy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout heroActions = new LinearLayout(this);
        heroActions.setGravity(Gravity.CENTER_VERTICAL);
        TextView engineButton = control(searchEngineName.toUpperCase(Locale.ROOT), TEXT, TEXT, 126);
        engineButton.setTextSize(13);
        engineButton.setOnClickListener(v -> showSearchEngineDialog());
        heroActions.addView(engineButton, new LinearLayout.LayoutParams(dp(126), dp(48)));
        TextView tabsButton = control("TABS " + tabs.size(), TEXT, TEXT, 86);
        tabsButton.setTextSize(13);
        LinearLayout.LayoutParams tabsLp = new LinearLayout.LayoutParams(dp(86), dp(48));
        tabsLp.leftMargin = dp(8);
        heroActions.addView(tabsButton, tabsLp);
        tabsButton.setOnClickListener(v -> showTabsDialog());
        hero.addView(heroActions);
        panel.addView(hero, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(112)));

        addHomeShortcutSectionTitle(panel);
        List<RecentSite> shortcuts = readHomeShortcuts();
        if (shortcuts.isEmpty()) {
            TextView emptyShortcuts = text("No shortcuts yet. Choose MANAGE to add your favorite websites.", 14, MUTED, false);
            emptyShortcuts.setPadding(dp(4), dp(10), 0, dp(10));
            panel.addView(emptyShortcuts);
        } else {
            for (int start = 0; start < shortcuts.size(); start += 4) {
                LinearLayout shortcutRow = new LinearLayout(this);
                shortcutRow.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
                int end = Math.min(start + 4, shortcuts.size());
                for (int i = start; i < end; i++) {
                    RecentSite shortcut = shortcuts.get(i);
                    addHomeTile(shortcutRow, shortcut.title, shortcut.url);
                }
                panel.addView(shortcutRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76)));
            }
        }

        if (!privateMode) {
            addHomeSectionTitle(panel, "RECENTLY VISITED", "Pick up where you left off");
            List<RecentSite> recents = readRecentSites();
            if (recents.isEmpty()) {
                TextView empty = text("Your recent websites will appear here after you browse.", 14, MUTED, false);
                empty.setPadding(dp(4), dp(8), 0, dp(10));
                panel.addView(empty);
            } else {
                HorizontalScrollView recentScroll = new HorizontalScrollView(this);
                recentScroll.setHorizontalScrollBarEnabled(false);
                LinearLayout recentRow = new LinearLayout(this);
                recentRow.setGravity(Gravity.CENTER_VERTICAL);
                for (RecentSite recent : recents) {
                    addHomeTile(recentRow, shorten(recent.title, 17), recent.url);
                }
                recentScroll.addView(recentRow);
                panel.addView(recentScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76)));
            }
        }

        TextView tip = text("TV TIP   •   Use the remote arrows to move between buttons. Press OK to open a site, and BACK to return here.", 13, MUTED, false);
        tip.setGravity(Gravity.CENTER_VERTICAL);
        tip.setPadding(dp(4), dp(8), dp(4), dp(4));
        panel.addView(tip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        scroll.addView(panel);
        return scroll;
    }

    private void addHomeSectionTitle(LinearLayout parent, String title, String subtitle) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(dp(3), dp(15), 0, dp(5));
        TextView heading = text(title, 14, ACCENT, true);
        heading.setLetterSpacing(0.08f);
        section.addView(heading);
        TextView detail = text(subtitle, 12, MUTED, false);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailLp.topMargin = dp(3);
        section.addView(detail, detailLp);
        parent.addView(section);
    }

    private void addHomeTile(LinearLayout row, String label, String url) {
        TextView tile = control(label, TEXT, TEXT, 142);
        tile.setTextSize(16);
        tile.setMaxLines(1);
        tile.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tile.setBackground(round(PANEL_2, 13, Color.rgb(50, 75, 96), 1));
        tile.setOnClickListener(v -> navigate(url));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(142), dp(58));
        lp.rightMargin = dp(11);
        row.addView(tile, lp);
    }

    private List<RecentSite> readHistorySites() {
        ArrayList<RecentSite> result = new ArrayList<>();
        try {
            String raw = getSharedPreferences("jbrowser", MODE_PRIVATE).getString("recent_sites", "[]");
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length() && result.size() < MAX_HISTORY_ENTRIES; i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String url = item.optString("url", "");
                String title = item.optString("title", url);
                if (isHttpUrl(url)) result.add(new RecentSite(title, url));
            }
        } catch (Exception ignored) { }
        return result;
    }

    private List<RecentSite> readRecentSites() {
        ArrayList<RecentSite> recent = new ArrayList<>();
        for (RecentSite site : readHistorySites()) {
            if (recent.size() >= 8) break;
            recent.add(site);
        }
        return recent;
    }

    private void recordRecentSite(String title, String url) {
        if (privateMode || !isHttpUrl(url)) return;
        try {
            List<RecentSite> old = readHistorySites();
            JSONArray updated = new JSONArray();
            updated.put(new JSONObject().put("title", (title == null || title.trim().isEmpty()) ? url : title).put("url", url));
            for (RecentSite site : old) {
                if (!site.url.equalsIgnoreCase(url) && updated.length() < MAX_HISTORY_ENTRIES) {
                    updated.put(new JSONObject().put("title", site.title).put("url", site.url));
                }
            }
            getSharedPreferences("jbrowser", MODE_PRIVATE).edit().putString("recent_sites", updated.toString()).apply();
        } catch (Exception ignored) { }
    }

    private boolean isHttpUrl(String value) {
        if (value == null) return false;
        try {
            Uri uri = Uri.parse(value.trim());
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null && !uri.getHost().trim().isEmpty();
        } catch (Exception ignored) { return false; }
    }

    private List<RecentSite> defaultHomeShortcuts() {
        ArrayList<RecentSite> defaults = new ArrayList<>();
        defaults.add(new RecentSite("YouTube", "https://www.youtube.com/tv"));
        defaults.add(new RecentSite("Google", "https://www.google.com"));
        defaults.add(new RecentSite("Twitch", "https://www.twitch.tv"));
        defaults.add(new RecentSite("Wikipedia", "https://www.wikipedia.org"));
        defaults.add(new RecentSite("Netflix", "https://www.netflix.com"));
        defaults.add(new RecentSite("Prime Video", "https://www.primevideo.com"));
        defaults.add(new RecentSite("Disney+", "https://www.disneyplus.com"));
        defaults.add(new RecentSite("Crunchyroll", "https://www.crunchyroll.com"));
        return defaults;
    }

    private List<RecentSite> readHomeShortcuts() {
        ArrayList<RecentSite> result = new ArrayList<>();
        String raw = getSharedPreferences("jbrowser", MODE_PRIVATE).getString("home_shortcuts", null);
        if (raw == null) return defaultHomeShortcuts();
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length() && result.size() < 24; i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String title = item.optString("title", "Website").trim();
                String url = item.optString("url", "").trim();
                if (!title.isEmpty() && isHttpUrl(url)) result.add(new RecentSite(title, url));
            }
        } catch (Exception ignored) { }
        return result;
    }

    private void saveHomeShortcuts(List<RecentSite> shortcuts) {
        JSONArray array = new JSONArray();
        try {
            for (RecentSite shortcut : shortcuts) {
                if (shortcut != null && !shortcut.title.trim().isEmpty() && isHttpUrl(shortcut.url)) {
                    array.put(new JSONObject().put("title", shortcut.title.trim()).put("url", shortcut.url.trim()));
                }
            }
            getSharedPreferences("jbrowser", MODE_PRIVATE).edit().putString("home_shortcuts", array.toString()).apply();
            Tab tab = activeTab();
            if (tab != null && tab.isHome) showHomeForActiveTab();
        } catch (Exception e) {
            Toast.makeText(this, "Could not save homepage shortcuts.", Toast.LENGTH_SHORT).show();
        }
    }

    private void addHomeShortcutSectionTitle(LinearLayout parent) {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(3), dp(15), 0, dp(5));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView heading = text("QUICK ACCESS", 14, ACCENT, true);
        heading.setLetterSpacing(0.08f);
        copy.addView(heading);
        TextView detail = text("Your favorite websites • customize this row", 12, MUTED, false);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailLp.topMargin = dp(3);
        copy.addView(detail, detailLp);
        header.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView manage = control("MANAGE", TEXT, TEXT, 92);
        manage.setTextSize(12);
        manage.setOnClickListener(v -> showHomeShortcutManager());
        header.addView(manage, new LinearLayout.LayoutParams(dp(92), dp(40)));
        parent.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)));
    }

    private void showHomeShortcutManager() {
        List<RecentSite> shortcuts = readHomeShortcuts();
        ArrayList<String> labels = new ArrayList<>();
        for (RecentSite item : shortcuts) labels.add(item.title + "\n" + shorten(item.url, 56));
        String[] items = labels.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle("Homepage shortcuts")
                .setItems(items, (dialog, which) -> showHomeShortcutActions(which))
                .setNeutralButton("Add shortcut", (dialog, which) -> showHomeShortcutEditor(-1))
                .setPositiveButton("Reset defaults", (dialog, which) -> saveHomeShortcuts(defaultHomeShortcuts()))
                .setNegativeButton("Done", null).show();
    }

    private void showHomeShortcutActions(int index) {
        List<RecentSite> shortcuts = readHomeShortcuts();
        if (index < 0 || index >= shortcuts.size()) return;
        RecentSite selected = shortcuts.get(index);
        new AlertDialog.Builder(this).setTitle(selected.title)
                .setItems(new String[] {"Open website", "Edit shortcut", "Remove shortcut"}, (dialog, which) -> {
                    if (which == 0) navigate(selected.url);
                    else if (which == 1) showHomeShortcutEditor(index);
                    else {
                        ArrayList<RecentSite> updated = new ArrayList<>(readHomeShortcuts());
                        if (index < updated.size()) updated.remove(index);
                        saveHomeShortcuts(updated);
                        Toast.makeText(this, "Shortcut removed", Toast.LENGTH_SHORT).show();
                    }
                }).setNegativeButton("Back", (dialog, which) -> showHomeShortcutManager()).show();
    }

    private void showHomeShortcutEditor(int index) {
        List<RecentSite> current = readHomeShortcuts();
        boolean editing = index >= 0 && index < current.size();
        RecentSite existing = editing ? current.get(index) : null;
        LinearLayout box = dialogBox();
        EditText titleInput = new EditText(this);
        titleInput.setSingleLine(true);
        titleInput.setTextSize(17);
        titleInput.setTextColor(TEXT);
        titleInput.setHintTextColor(MUTED);
        titleInput.setHint("Shortcut name");
        titleInput.setPadding(dp(12), dp(8), dp(12), dp(8));
        titleInput.setBackground(round(PANEL_2, 10, Color.rgb(50, 75, 96), 1));
        EditText urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setTextSize(15);
        urlInput.setTextColor(TEXT);
        urlInput.setHintTextColor(MUTED);
        urlInput.setHint("https://example.com");
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setPadding(dp(12), dp(8), dp(12), dp(8));
        urlInput.setBackground(round(PANEL_2, 10, Color.rgb(50, 75, 96), 1));
        if (editing) { titleInput.setText(existing.title); urlInput.setText(existing.url); }
        box.addView(titleInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        LinearLayout.LayoutParams urlLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        urlLp.topMargin = dp(10);
        box.addView(urlInput, urlLp);
        AlertDialog editor = new AlertDialog.Builder(this).setTitle(editing ? "Edit shortcut" : "Add homepage shortcut")
                .setView(box).setPositiveButton("Save", null).setNegativeButton("Cancel", null).create();
        editor.setOnShowListener(d -> editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String title = titleInput.getText().toString().trim();
            String url = urlInput.getText().toString().trim();
            if (title.isEmpty()) { titleInput.setError("Enter a name"); return; }
            if (!isHttpUrl(url)) { urlInput.setError("Enter a valid HTTP or HTTPS address"); return; }
            ArrayList<RecentSite> updated = new ArrayList<>(readHomeShortcuts());
            if (editing && index < updated.size()) updated.set(index, new RecentSite(title, url));
            else if (updated.size() < 24) updated.add(new RecentSite(title, url));
            else { Toast.makeText(this, "You can save up to 24 shortcuts.", Toast.LENGTH_SHORT).show(); return; }
            saveHomeShortcuts(updated);
            editor.dismiss();
        }));
        editor.show();
    }

    private void showHistoryDialog() {
        if (privateMode) {
            Toast.makeText(this, "History is not saved in private browsing.", Toast.LENGTH_SHORT).show();
            return;
        }
        List<RecentSite> history = readHistorySites();
        if (history.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Browsing history")
                    .setMessage("Websites you visit will appear here.")
                    .setPositiveButton("Done", null).show();
            return;
        }
        String[] labels = new String[history.size()];
        for (int i = 0; i < history.size(); i++) labels[i] = history.get(i).title + "\n" + shorten(history.get(i).url, 70);
        new AlertDialog.Builder(this).setTitle("Browsing history • " + history.size())
                .setItems(labels, (dialog, which) -> {
                    RecentSite selected = history.get(which);
                    new AlertDialog.Builder(this).setTitle(selected.title)
                            .setItems(new String[] {"Open website", "Remove from history"}, (d, action) -> {
                                if (action == 0) navigate(selected.url);
                                else {
                                    removeHistoryEntry(selected.url);
                                    Toast.makeText(this, "Removed from history", Toast.LENGTH_SHORT).show();
                                    showHistoryDialog();
                                }
                            }).setNegativeButton("Back", (d, w) -> showHistoryDialog()).show();
                })
                .setNeutralButton("Clear history", (dialog, which) -> new AlertDialog.Builder(this)
                        .setTitle("Clear browsing history?")
                        .setMessage("This removes the saved list of visited websites. It does not clear cookies or sign you out.")
                        .setPositiveButton("Clear", (d, w) -> clearHistoryEntries())
                        .setNegativeButton("Cancel", null).show())
                .setPositiveButton("Done", null).show();
    }

    private void removeHistoryEntry(String url) {
        ArrayList<RecentSite> updated = new ArrayList<>();
        for (RecentSite item : readHistorySites()) if (!item.url.equalsIgnoreCase(url)) updated.add(item);
        saveHistoryEntries(updated);
    }

    private void clearHistoryEntries() {
        getSharedPreferences("jbrowser", MODE_PRIVATE).edit().remove("recent_sites").apply();
        Tab tab = activeTab();
        if (tab != null && tab.isHome) showHomeForActiveTab();
        Toast.makeText(this, "Browsing history cleared", Toast.LENGTH_SHORT).show();
    }

    private void saveHistoryEntries(List<RecentSite> history) {
        JSONArray array = new JSONArray();
        try {
            for (RecentSite item : history) {
                if (item != null && isHttpUrl(item.url)) array.put(new JSONObject().put("title", item.title).put("url", item.url));
            }
            getSharedPreferences("jbrowser", MODE_PRIVATE).edit().putString("recent_sites", array.toString()).apply();
            Tab tab = activeTab();
            if (tab != null && tab.isHome) showHomeForActiveTab();
        } catch (Exception ignored) { }
    }

    private Tab createTab() {
        if (tabs.size() >= MAX_TABS) {
            Toast.makeText(this, "Close a tab before opening another. Maximum " + MAX_TABS + ".", Toast.LENGTH_SHORT).show();
            return tabs.isEmpty() ? null : tabs.get(activeIndex < 0 ? 0 : activeIndex);
        }
        Tab tab = new Tab();
        tab.webView = createWebView(tab);
        tabs.add(tab);
        webContainer.addView(tab.webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tab.webView.setVisibility(View.GONE);
        return tab;
    }

    private WebView createWebView(Tab tab) {
        WebView web = new WebView(this);
        web.setBackgroundColor(Color.rgb(11, 19, 31));
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(!allowMediaAutoplay);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setBlockNetworkImage(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Avoid keeping hidden tabs rasterized; this is friendlier to low-memory TV boxes.
            settings.setOffscreenPreRaster(false);
        }
        // Allow user-initiated target=_blank links to open in a browser tab. Automatic
        // JavaScript windows remain disabled and onCreateWindow rejects non-gestures.
        settings.setSupportMultipleWindows(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccess(false);
        settings.setGeolocationEnabled(false);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setTextZoom(100);
        settings.setDefaultFontSize(16);
        settings.setDefaultFixedFontSize(13);
        settings.setLoadsImagesAutomatically(true);
        if (desktopMode) settings.setUserAgentString(desktopUserAgent());
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, thirdPartyCookies);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request == null || request.getUrl() == null) return true;
                String scheme = request.getUrl().getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) return false;
                if ("mailto".equalsIgnoreCase(scheme) || "tel".equalsIgnoreCase(scheme)) {
                    openExternal(request.getUrl().toString());
                }
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (adBlocker.shouldBlock(request, tab.url)) {
                    return new WebResourceResponse("text/plain", "UTF-8", 200, "Blocked by JBrowser",
                            java.util.Collections.singletonMap("Access-Control-Allow-Origin", "*"),
                            new ByteArrayInputStream(new byte[0]));
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                tab.url = url;
                tab.isHome = false;
                tab.isLoading = true;
                if (tab == activeTab()) {
                    updatePageProgress(5);
                    addressBar.setText(url);
                    status("Loading " + shorten(url, 72));
                    updateToolbarState();
                }
                if (tab == activeTab()) adBlocker.setTopLevelUrl(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                tab.url = url;
                tab.isHome = false;
                tab.isLoading = false;
                if (tab == activeTab()) adBlocker.setTopLevelUrl(url);
                recordRecentSite(tab.title, url);
                injectPageEnhancements(view, url);
                if (tab == activeTab()) {
                    updatePageProgress(100);
                    addressBar.setText(url);
                    status((privateMode ? "Private • " : "") + "Loaded • " + adBlocker.getBlockedCount()
                            + " requests blocked • " + adBlocker.getRuleCount() + " filter rules");
                    updateToolbarState();
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame() && tab == activeTab()) {
                    updatePageProgress(100);
                    status("Page error: " + (error == null ? "Unable to load page" : error.getDescription()));
                }
            }

            @Override
            public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler, SslError error) {
                // Never let a user bypass a certificate error from a TV remote.
                handler.cancel();
                if (tab == activeTab()) status("Secure connection blocked: certificate error");
            }

            @Override
            @android.annotation.TargetApi(Build.VERSION_CODES.O_MR1)
            public void onSafeBrowsingHit(WebView view, WebResourceRequest request, int threatType, SafeBrowsingResponse response) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) response.backToSafety(true);
            }

            @Override
            @android.annotation.TargetApi(Build.VERSION_CODES.O)
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                int position = tabs.indexOf(tab);
                webContainer.removeView(view);
                try { view.destroy(); } catch (Exception ignored) { }
                tab.webView = createWebView(tab);
                webContainer.addView(tab.webView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                tab.webView.setVisibility(position == activeIndex ? View.VISIBLE : View.GONE);
                tab.isHome = true;
                tab.url = "";
                if (position == activeIndex) showHomeForActiveTab();
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int progress) {
                if (tab == activeTab()) {
                    updatePageProgress(progress);
                    if (progress < 100) status("Loading page… " + progress + "%");
                }
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                if (title != null && !title.trim().isEmpty()) tab.title = title;
                if (!tab.isHome) recordRecentSite(tab.title, tab.url);
                updateToolbarState();
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                pageRoot.setVisibility(View.GONE);

                // Keep fullscreen HTML5 video in the WebView's native fullscreen surface,
                // then add lightweight TV-remote controls above it.
                fullscreenRoot = new FrameLayout(MainActivity.this);
                fullscreenRoot.setBackgroundColor(Color.BLACK);
                if (view.getParent() instanceof ViewGroup) {
                    ((ViewGroup) view.getParent()).removeView(view);
                }
                fullscreenRoot.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                buildFullscreenPlaybackControls();
                setContentView(fullscreenRoot);
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
                if (fullscreenPlayButton != null) fullscreenPlayButton.requestFocus();
            }

            @Override
            public void onHideCustomView() { exitCustomView(); }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> filePathCallback,
                                             WebChromeClient.FileChooserParams fileChooserParams) {
                if (pendingFileChooserCallback != null) {
                    pendingFileChooserCallback.onReceiveValue(null);
                    pendingFileChooserCallback = null;
                }
                pendingFileChooserCallback = filePathCallback;
                try {
                    Intent picker = fileChooserParams.createIntent();
                    if (fileChooserParams.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                        picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    }
                    startActivityForResult(Intent.createChooser(picker, "Choose a file"), REQUEST_FILE_CHOOSER);
                    return true;
                } catch (ActivityNotFoundException | SecurityException error) {
                    pendingFileChooserCallback = null;
                    filePathCallback.onReceiveValue(null);
                    Toast.makeText(MainActivity.this, "No file picker is available on this device.", Toast.LENGTH_SHORT).show();
                    return true;
                }
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                // Permit only explicit user-initiated new-window links; reject unsolicited pop-ups.
                if (!isUserGesture || resultMsg == null
                        || !(resultMsg.obj instanceof WebView.WebViewTransport)) return false;
                if (tabs.size() >= MAX_TABS) {
                    Toast.makeText(MainActivity.this, "Close a tab before opening another.", Toast.LENGTH_SHORT).show();
                    return false;
                }
                Tab popupTab = createTab();
                if (popupTab == null) return false;
                int popupIndex = tabs.indexOf(popupTab);
                activateTab(popupIndex);
                // New-window content replaces the new tab's home screen when it starts loading.
                popupTab.isHome = false;
                removeHomeView();
                popupTab.webView.setVisibility(View.VISIBLE);
                popupTab.webView.requestFocus();
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popupTab.webView);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.deny();
            }
        });

        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> enqueueDownload(url, userAgent, contentDisposition, mimeType));
        web.setOnKeyListener((v, keyCode, event) -> {
            // Let DPAD_UP scroll the page normally. Only move into browser chrome
            // when the web page is already at its top edge.
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && keyCode == KeyEvent.KEYCODE_DPAD_UP
                    && web.getScrollY() <= dp(2)) {
                if (addressBar != null) addressBar.requestFocus();
                return true;
            }
            return false;
        });
        return web;
    }

    private void injectPageEnhancements(WebView web, String url) {
        String css = adBlocker.cosmeticCssFor(url, true, cookieCleaner);
        try {
            String script = "(function(){try{window.__jbrowserCosmeticCss=" + JSONObject.quote(css) + ";"
                    + "function installCss(){try{var root=document.head||document.documentElement;if(!root)return;"
                    + "var s=document.getElementById('jbrowser-cosmetics');"
                    + "if(!s){s=document.createElement('style');s.id='jbrowser-cosmetics';root.appendChild(s);}"
                    + "s.textContent=window.__jbrowserCosmeticCss||'';}catch(e){}}installCss();"
                    + "if(!window.__jbrowserCosmeticObserver&&window.MutationObserver){"
                    + "window.__jbrowserCosmeticObserver=new MutationObserver(function(){"
                    + "if(window.__jbrowserCosmeticCss&&!document.getElementById('jbrowser-cosmetics'))installCss();});"
                    + "try{if(document.documentElement)window.__jbrowserCosmeticObserver.observe(document.documentElement,{childList:true});"
                    + "if(document.head)window.__jbrowserCosmeticObserver.observe(document.head,{childList:true});}catch(e){}}"
                    + "if(!window.__jbrowserPopupGuard){window.__jbrowserPopupGuard=true;"
                    + "try{Object.defineProperty(window,'open',{configurable:false,writable:false,value:function(){return null;}});}"
                    + "catch(e){window.open=function(){return null;};}}"
                    + "function prepareVideo(v){try{v.playsInline=true;v.setAttribute('playsinline','');v.disablePictureInPicture=false;"
                    + "if(!v.dataset.jbrowserSpeedApplied){v.playbackRate=" + defaultPlaybackSpeed + ";v.dataset.jbrowserSpeedApplied='1';}}catch(e){}}"
                    + "function prepareVideos(root){try{if(root&&root.tagName==='VIDEO')prepareVideo(root);"
                    + "if(root&&root.querySelectorAll)root.querySelectorAll('video').forEach(prepareVideo);}catch(e){}}"
                    + "prepareVideos(document);if(!window.__jbrowserVideoObserver&&window.MutationObserver){"
                    + "window.__jbrowserVideoObserver=new MutationObserver(function(records){records.forEach(function(r){"
                    + "r.addedNodes.forEach(function(n){if(n&&n.nodeType===1)prepareVideos(n);});});});"
                    + "try{window.__jbrowserVideoObserver.observe(document.documentElement,{childList:true,subtree:true});}catch(e){}}"
                    + "document.addEventListener('loadedmetadata',function(e){if(e.target&&e.target.tagName==='VIDEO')prepareVideo(e.target);},true);"
                    + "}catch(e){}})();";
            web.evaluateJavascript(script, null);
        } catch (Exception ignored) { }
    }

    private void buildFullscreenPlaybackControls() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22), dp(12), dp(22), dp(12));
        panel.setBackground(round(Color.argb(226, 8, 13, 24), 16,
                Color.argb(150, 90, 110, 130), 1));
        panel.setElevation(dp(10));
        fullscreenControlBar = panel;

        fullscreenVideoTitle = text("Now playing", 18, TEXT, true);
        fullscreenVideoTitle.setMaxLines(1);
        fullscreenVideoTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        panel.addView(fullscreenVideoTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));

        LinearLayout timelineRow = new LinearLayout(this);
        timelineRow.setGravity(Gravity.CENTER_VERTICAL);
        fullscreenTimeText = text("00:00  /  --:--", 12, MUTED, true);
        timelineRow.addView(fullscreenTimeText);
        fullscreenSeekBar = new SeekBar(this);
        fullscreenSeekBar.setMax(1000);
        fullscreenSeekBar.setFocusable(true);
        fullscreenSeekBar.setFocusableInTouchMode(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            fullscreenSeekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            fullscreenSeekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            fullscreenSeekBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(75, 91, 108)));
        }
        LinearLayout.LayoutParams seekLp = new LinearLayout.LayoutParams(0, dp(34), 1f);
        seekLp.leftMargin = dp(18);
        timelineRow.addView(fullscreenSeekBar, seekLp);
        panel.addView(timelineRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));

        fullscreenSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && currentVideoDurationSeconds > 0) {
                    double target = currentVideoDurationSeconds * progress / 1000.0;
                    fullscreenTimeText.setText(formatVideoTime(target) + "  /  " + formatVideoTime(currentVideoDurationSeconds));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { videoSeekInProgress = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                videoSeekInProgress = false;
                seekVideoToPercent(seekBar.getProgress());
            }
        });

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        TextView exit = fullscreenControl("EXIT", 70, this::exitCustomView);
        TextView rewind30 = fullscreenControl("−30s", 68, () -> seekVideoBy(-30));
        TextView rewind10 = fullscreenControl("−10s", 68, () -> seekVideoBy(-10));
        fullscreenPlayButton = fullscreenControl("PAUSE", 104, this::toggleVideoPlayback);
        TextView forward10 = fullscreenControl("+10s", 68, () -> seekVideoBy(10));
        TextView forward30 = fullscreenControl("+30s", 68, () -> seekVideoBy(30));
        fullscreenSpeedButton = fullscreenControl("1.0×", 74, this::showPlaybackSpeedDialog);
        controls.addView(exit);
        addPlayerGap(controls);
        controls.addView(rewind30);
        addPlayerGap(controls);
        controls.addView(rewind10);
        addPlayerGap(controls);
        controls.addView(fullscreenPlayButton);
        addPlayerGap(controls);
        controls.addView(forward10);
        addPlayerGap(controls);
        controls.addView(forward30);
        addPlayerGap(controls);
        controls.addView(fullscreenSpeedButton);
        HorizontalScrollView controlsScroll = new HorizontalScrollView(this);
        controlsScroll.setHorizontalScrollBarEnabled(false);
        controlsScroll.setFillViewport(false);
        controlsScroll.addView(controls, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams controlsLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        controlsLp.topMargin = dp(4);
        panel.addView(controlsScroll, controlsLp);

        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        panelLp.leftMargin = dp(44);
        panelLp.rightMargin = dp(44);
        panelLp.bottomMargin = dp(26);
        fullscreenRoot.addView(panel, panelLp);

        fullscreenVideoTitle.setText(activeTab() == null ? "Now playing" : activeTab().title);
        startVideoUiPolling();
    }

    private void addPlayerGap(LinearLayout row) {
        View gap = new View(this);
        row.addView(gap, new LinearLayout.LayoutParams(dp(7), 1));
    }

    private TextView fullscreenControl(String label, int widthDp, Runnable action) {
        TextView button = control(label, TEXT, TEXT, widthDp);
        button.setTextSize(13);
        button.setBackground(round(PANEL_2, 10, Color.rgb(65, 90, 112), 1));
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(widthDp), dp(42));
        button.setLayoutParams(lp);
        return button;
    }

    private void startVideoUiPolling() {
        stopVideoUiPolling();
        videoStatusPoller = new Runnable() {
            @Override public void run() {
                if (customView == null) return;
                pollVideoStatus();
                handler.postDelayed(this, 1000L);
            }
        };
        handler.post(videoStatusPoller);
    }

    private void stopVideoUiPolling() {
        if (videoStatusPoller != null) handler.removeCallbacks(videoStatusPoller);
        videoStatusPoller = null;
    }

    private void pollVideoStatus() {
        Tab tab = activeTab();
        if (tab == null || tab.isHome || fullscreenTimeText == null) return;
        String js = "(function(){var a=[].slice.call(document.querySelectorAll('video'));"
                + "if(!a.length)return JSON.stringify({none:true});"
                + "a.sort(function(x,y){return (y.clientWidth*y.clientHeight)-(x.clientWidth*x.clientHeight);});"
                + "var v=a[0];return JSON.stringify({t:Number(v.currentTime)||0,d:(isFinite(v.duration)?v.duration:0),p:!!v.paused,r:Number(v.playbackRate)||1,title:(document.title||'Now playing')});})()";
        tab.webView.evaluateJavascript(js, result -> {
            try {
                Object decoded = new JSONTokener(result == null ? "null" : result).nextValue();
                JSONObject snapshot = decoded instanceof String
                        ? new JSONObject((String) decoded)
                        : (decoded instanceof JSONObject ? (JSONObject) decoded : null);
                if (snapshot == null || customView == null || fullscreenVideoTitle == null) return;
                if (snapshot.optBoolean("none", false)) {
                    fullscreenVideoTitle.setText("Video controls unavailable on this page");
                    return;
                }
                currentVideoPositionSeconds = Math.max(0, snapshot.optDouble("t", 0));
                currentVideoDurationSeconds = Math.max(0, snapshot.optDouble("d", 0));
                if (fullscreenVideoTitle != null) {
                    String title = snapshot.optString("title", "Now playing");
                    fullscreenVideoTitle.setText(title.isEmpty() ? "Now playing" : title);
                }
                if (fullscreenTimeText != null) {
                    fullscreenTimeText.setText(formatVideoTime(currentVideoPositionSeconds) + "  /  "
                            + (currentVideoDurationSeconds > 0 ? formatVideoTime(currentVideoDurationSeconds) : "LIVE / --:--"));
                }
                if (fullscreenSeekBar != null && !videoSeekInProgress) {
                    int progress = currentVideoDurationSeconds > 0
                            ? (int) Math.max(0, Math.min(1000, currentVideoPositionSeconds * 1000.0 / currentVideoDurationSeconds)) : 0;
                    fullscreenSeekBar.setProgress(progress);
                    fullscreenSeekBar.setEnabled(currentVideoDurationSeconds > 0);
                }
                if (fullscreenPlayButton != null) fullscreenPlayButton.setText(snapshot.optBoolean("p", false) ? "PLAY" : "PAUSE");
                playbackSpeed = (float) snapshot.optDouble("r", playbackSpeed);
                if (fullscreenSpeedButton != null) fullscreenSpeedButton.setText(String.format(Locale.US, "%.2g×", playbackSpeed));
            } catch (Exception ignored) { }
        });
    }

    private String formatVideoTime(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0) seconds = 0;
        long total = (long) seconds;
        long hours = total / 3600;
        long minutes = (total % 3600) / 60;
        long secs = total % 60;
        return hours > 0 ? String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
                : String.format(Locale.US, "%02d:%02d", minutes, secs);
    }

    private void seekVideoToPercent(int progress) {
        Tab tab = activeTab();
        if (tab == null || tab.isHome || currentVideoDurationSeconds <= 0) return;
        double ratio = Math.max(0, Math.min(1000, progress)) / 1000.0;
        String js = "(function(){var a=[].slice.call(document.querySelectorAll('video'));if(!a.length)return;"
                + "a.sort(function(x,y){return (y.clientWidth*y.clientHeight)-(x.clientWidth*x.clientHeight);});"
                + "var v=a[0];if(isFinite(v.duration))v.currentTime=Math.max(0,Math.min(v.duration,v.duration*" + ratio + "));})()";
        tab.webView.evaluateJavascript(js, null);
    }

    private boolean isSupportedPlaybackSpeed(float rate) {
        float[] values = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f};
        for (float value : values) if (Math.abs(value - rate) < 0.001f) return true;
        return false;
    }

    private void showDefaultPlaybackSpeedDialog() {
        String[] labels = {"0.5×", "0.75×", "1.0× (Normal)", "1.25×", "1.5×", "1.75×", "2.0×"};
        float[] rates = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f};
        int selected = 2;
        for (int i = 0; i < rates.length; i++) if (Math.abs(defaultPlaybackSpeed - rates[i]) < 0.001f) selected = i;
        new AlertDialog.Builder(this).setTitle("Default video playback speed")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    defaultPlaybackSpeed = rates[which];
                    getSharedPreferences("jbrowser", MODE_PRIVATE).edit()
                            .putFloat("default_playback_speed", defaultPlaybackSpeed).apply();
                    dialog.dismiss();
                    Toast.makeText(this, "Default playback speed: " + labels[which], Toast.LENGTH_SHORT).show();
                    Tab tab = activeTab();
                    if (tab != null && !tab.isHome) applyDefaultPlaybackSpeed(tab.webView);
                }).setNegativeButton("Cancel", null).show();
    }

    private void applyDefaultPlaybackSpeed(WebView web) {
        if (web == null) return;
        String js = "(function(){document.querySelectorAll('video').forEach(function(v){"
                + "try{v.playbackRate=" + defaultPlaybackSpeed + ";v.dataset.jbrowserSpeedApplied='1';}catch(e){}});})()";
        web.evaluateJavascript(js, null);
    }

    private void showPlaybackSpeedDialog() {
        String[] labels = {"0.5×", "0.75×", "1.0× (Normal)", "1.25×", "1.5×", "1.75×", "2.0×"};
        float[] rates = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f};
        new AlertDialog.Builder(this).setTitle("Playback speed")
                .setItems(labels, (dialog, which) -> setVideoPlaybackRate(rates[which]))
                .setNegativeButton("Cancel", null).show();
    }

    private void setVideoPlaybackRate(float rate) {
        Tab tab = activeTab();
        if (tab == null || tab.isHome) return;
        String js = "(function(){var a=[].slice.call(document.querySelectorAll('video'));if(!a.length)return;"
                + "a.sort(function(x,y){return (y.clientWidth*y.clientHeight)-(x.clientWidth*x.clientHeight);});"
                + "a[0].playbackRate=" + rate + ";})()";
        tab.webView.evaluateJavascript(js, null);
        playbackSpeed = rate;
        if (fullscreenSpeedButton != null) fullscreenSpeedButton.setText(String.format(Locale.US, "%.2g×", rate));
    }

    private boolean isFullscreenControlFocused() {
        if (fullscreenControlBar == null) return false;
        View focused = fullscreenRoot == null ? null : fullscreenRoot.findFocus();
        while (focused != null) {
            if (focused == fullscreenControlBar) return true;
            if (!(focused.getParent() instanceof View)) break;
            focused = (View) focused.getParent();
        }
        return false;
    }

    private void toggleVideoPlayback() {
        Tab tab = activeTab();
        if (tab == null || tab.isHome) return;
        String js = "(function(){var a=[].slice.call(document.querySelectorAll('video'));"
                + "if(!a.length)return 'none';a.sort(function(x,y){"
                + "var sx=(x.paused?0:100000000)+(x.clientWidth*x.clientHeight);"
                + "var sy=(y.paused?0:100000000)+(y.clientWidth*y.clientHeight);return sy-sx;});"
                + "var v=a[0];if(v.paused){var p=v.play();if(p&&p.catch)p.catch(function(){});return 'playing';}"
                + "v.pause();return 'paused';})()";
        tab.webView.evaluateJavascript(js, result -> {
            if ("\"none\"".equals(result)) forwardMediaKeyToFullscreen(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            if (fullscreenPlayButton != null) {
                fullscreenPlayButton.setText("\"playing\"".equals(result) ? "PAUSE" : "PLAY");
            }
            pollVideoStatus();
        });
    }

    private void seekVideoBy(int seconds) {
        Tab tab = activeTab();
        if (tab == null || tab.isHome) return;
        String js = "(function(){var a=[].slice.call(document.querySelectorAll('video'));"
                + "if(!a.length)return 'none'; a.sort(function(x,y){return (y.clientWidth*y.clientHeight)-(x.clientWidth*x.clientHeight);});"
                + "var v=a[0],d=Number.isFinite(v.duration)?v.duration:1e12;"
                + "v.currentTime=Math.max(0,Math.min(d,v.currentTime+(" + seconds + ")));return 'ok';})()";
        tab.webView.evaluateJavascript(js, result -> {
            if ("\"none\"".equals(result) && customView != null) {
                forwardMediaKeyToFullscreen(seconds < 0 ? KeyEvent.KEYCODE_MEDIA_REWIND : KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);
            }
        });
    }

    private void setVideoPlaying(boolean playing) {
        Tab tab = activeTab();
        if (tab == null || tab.isHome) return;
        String js = "(function(){var a=[].slice.call(document.querySelectorAll('video'));if(!a.length)return 'none';"
                + "a.sort(function(x,y){return (y.clientWidth*y.clientHeight)-(x.clientWidth*x.clientHeight);});var v=a[0];"
                + (playing ? "if(v.paused){var p=v.play();if(p&&p.catch)p.catch(function(){});}" : "v.pause();")
                + "return 'ok';})()";
        tab.webView.evaluateJavascript(js, result -> {
            if ("\"none\"".equals(result) && customView != null) {
                forwardMediaKeyToFullscreen(playing ? KeyEvent.KEYCODE_MEDIA_PLAY : KeyEvent.KEYCODE_MEDIA_PAUSE);
            }
        });
        if (fullscreenPlayButton != null) fullscreenPlayButton.setText(playing ? "PAUSE" : "PLAY");
        if (customView != null) handler.postDelayed(this::pollVideoStatus, 250L);
    }

    private void forwardMediaKeyToFullscreen(int keyCode) {
        if (customView == null) return;
        long now = SystemClock.uptimeMillis();
        customView.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        customView.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
    }

    private void activateTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        activeIndex = index;
        Tab active = tabs.get(activeIndex);
        for (int i = 0; i < tabs.size(); i++) {
            tabs.get(i).webView.setVisibility(i == activeIndex && !active.isHome ? View.VISIBLE : View.GONE);
        }
        if (active.isHome) showHomeForActiveTab();
        else {
            removeHomeView();
            addressBar.setText(active.url);
            adBlocker.setTopLevelUrl(active.url);
            active.webView.requestFocus();
        }
        updateToolbarState();
    }

    private void showHomeForActiveTab() {
        Tab tab = activeTab();
        if (tab == null) return;
        tab.isHome = true;
        tab.url = "";
        tab.webView.setVisibility(View.GONE);
        removeHomeView();
        homeView = buildHomePage();
        webContainer.addView(homeView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addressBar.setText("");
        addressBar.requestFocus();
        updatePageProgress(100);
        status(privateMode ? "Private browsing • No normal history is saved" : "Ready • JBrowser protection is active");
        updateToolbarState();
    }

    private void removeHomeView() {
        if (homeView != null) {
            webContainer.removeView(homeView);
            homeView = null;
        }
    }

    private Tab activeTab() {
        if (activeIndex < 0 || activeIndex >= tabs.size()) return null;
        return tabs.get(activeIndex);
    }

    private void newTab() {
        Tab tab = createTab();
        if (tab != null) activateTab(tabs.indexOf(tab));
    }

    private void navigate(String input) {
        if (input == null || input.trim().isEmpty()) return;
        String value = input.trim();
        String url;
        if (value.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {
            url = value;
        } else if (value.matches("(?i)^([a-z0-9-]+\\.)+[a-z]{2,}(:[0-9]{1,5})?(/.*)?$")
                || value.matches("^\\d{1,3}(\\.\\d{1,3}){3}(:[0-9]{1,5})?(/.*)?$")) {
            url = "https://" + value;
        } else if (value.startsWith("localhost") || value.startsWith("192.168.") || value.startsWith("10.")) {
            url = "http://" + value;
        } else {
            url = searchEngineTemplate.replace("%s", Uri.encode(value));
        }
        Tab tab = activeTab();
        if (tab == null) { newTab(); tab = activeTab(); }
        if (tab == null) return;
        removeHomeView();
        tab.isHome = false;
        tab.url = url;
        tab.webView.setVisibility(View.VISIBLE);
        tab.webView.requestFocus();
        addressBar.setText(url);
        try {
            tab.webView.loadUrl(url);
            adBlocker.setTopLevelUrl(url);
            status("Opening " + shorten(url, 80));
        } catch (Exception e) {
            status("Could not open this address");
        }
    }

    private String desktopUserAgent() {
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
    }

    private void goBack() {
        Tab tab = activeTab();
        if (tab != null && !tab.isHome && tab.webView.canGoBack()) tab.webView.goBack();
        else goHome();
    }

    private void goForward() {
        Tab tab = activeTab();
        if (tab != null && !tab.isHome && tab.webView.canGoForward()) tab.webView.goForward();
    }

    private void reload() {
        Tab tab = activeTab();
        if (tab != null && !tab.isHome) tab.webView.reload();
        else if (tab != null) addressBar.requestFocus();
    }

    private void goHome() {
        Tab tab = activeTab();
        if (tab == null) return;
        tab.webView.stopLoading();
        tab.isHome = true;
        showHomeForActiveTab();
        updateToolbarState();
    }

    private void showTabsDialog() {
        if (tabs.isEmpty()) return;
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        final AlertDialog[] dialogRef = new AlertDialog[1];

        for (int i = 0; i < tabs.size(); i++) {
            final int index = i;
            Tab tab = tabs.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(5), dp(4), dp(5), dp(4));
            row.setBackground(round(i == activeIndex ? Color.rgb(27, 58, 69) : PANEL, 12,
                    i == activeIndex ? ACCENT : Color.rgb(44, 62, 82), 1));

            String tabTitle = (tab.title == null || tab.title.trim().isEmpty()) ? "New tab" : tab.title;
            String detail = tab.isHome ? "Start page" : shorten(tab.url, 52);
            TextView open = text((i == activeIndex ? "●  " : "   ") + tabTitle + "\n" + detail,
                    15, TEXT, i == activeIndex);
            open.setGravity(Gravity.CENTER_VERTICAL);
            open.setPadding(dp(12), dp(5), dp(8), dp(5));
            open.setFocusable(true);
            open.setClickable(true);
            open.setBackground(round(Color.TRANSPARENT, 9, Color.TRANSPARENT, 0));
            open.setOnFocusChangeListener((v, focused) -> {
                v.setBackground(round(focused ? ACCENT : Color.TRANSPARENT, 9,
                        focused ? ACCENT : Color.TRANSPARENT, focused ? 1 : 0));
                ((TextView) v).setTextColor(focused ? BG : TEXT);
            });
            open.setOnClickListener(v -> {
                activateTab(index);
                if (dialogRef[0] != null) dialogRef[0].dismiss();
            });
            row.addView(open, new LinearLayout.LayoutParams(0, dp(62), 1f));

            TextView close = control("CLOSE", TEXT, TEXT, 78);
            close.setTextSize(12);
            close.setOnClickListener(v -> {
                if (dialogRef[0] != null) dialogRef[0].dismiss();
                closeTabAt(index);
                showTabsDialog();
            });
            row.addView(close, new LinearLayout.LayoutParams(dp(78), dp(46)));
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(74));
            rowLp.bottomMargin = dp(7);
            list.addView(row, rowLp);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(list);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Your tabs  •  " + tabs.size() + "/" + MAX_TABS)
                .setView(scroll)
                .setNeutralButton("+ New tab", (d, w) -> newTab())
                .setPositiveButton("Done", null)
                .create();
        dialogRef[0] = dialog;
        dialog.show();
        if (list.getChildCount() > 0) {
            View firstRow = list.getChildAt(0);
            if (firstRow instanceof ViewGroup && ((ViewGroup) firstRow).getChildCount() > 0) {
                ((ViewGroup) firstRow).getChildAt(0).requestFocus();
            }
        }
    }

    private void closeTabAt(int index) {
        if (index < 0 || index >= tabs.size()) return;
        if (tabs.size() == 1) {
            Tab only = tabs.get(0);
            if (!only.isHome) {
                only.webView.stopLoading();
                only.webView.clearHistory();
                only.isHome = true;
                only.url = "";
                showHomeForActiveTab();
            }
            return;
        }
        Tab closing = tabs.get(index);
        webContainer.removeView(closing.webView);
        closing.webView.stopLoading();
        closing.webView.destroy();
        tabs.remove(index);
        if (index < activeIndex) activeIndex--;
        else if (index == activeIndex) activeIndex = Math.min(index, tabs.size() - 1);
        activateTab(activeIndex);
    }

    private void closeActiveTab() {
        closeTabAt(activeIndex);
    }

    private void promptAddBookmark() {
        if (privateMode) {
            Toast.makeText(this, "Bookmarks are disabled in private windows.", Toast.LENGTH_SHORT).show();
            return;
        }
        Tab tab = activeTab();
        if (tab == null || tab.isHome || tab.url.isEmpty()) {
            Toast.makeText(this, "Open a page before bookmarking it.", Toast.LENGTH_SHORT).show();
            return;
        }
        EditText titleInput = new EditText(this);
        titleInput.setSingleLine(true);
        titleInput.setText(tab.title);
        titleInput.setTextColor(TEXT);
        titleInput.setSelectAllOnFocus(true);
        LinearLayout box = dialogBox();
        box.addView(text("Bookmark name", 13, MUTED, false));
        box.addView(titleInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        new AlertDialog.Builder(this).setTitle("Save bookmark")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> chooseBookmarkType(titleInput.getText().toString(), tab.url))
                .setNegativeButton("Cancel", null).show();
    }

    private void chooseBookmarkType(String title, String url) {
        new AlertDialog.Builder(this).setTitle("Save bookmark as")
                .setItems(new String[] {"Regular bookmark", "Private bookmark (PIN protected)"}, (d, which) -> {
                    if (which == 0) saveBookmark(title, url, false);
                    else ensurePrivatePinAndSave(title, url);
                }).show();
    }

    private void ensurePrivatePinAndSave(String title, String url) {
        if (bookmarkStore.hasPrivatePin()) {
            promptPin("Enter your private bookmark PIN", pin -> {
                if (bookmarkStore.verifyPin(pin)) saveBookmark(title, url, true);
                else Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show();
            });
        } else {
            LinearLayout box = dialogBox();
            EditText pin = new EditText(this);
            pin.setSingleLine(true);
            pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            pin.setHint("4–8 digit PIN");
            pin.setTextColor(TEXT);
            EditText confirm = new EditText(this);
            confirm.setSingleLine(true);
            confirm.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            confirm.setHint("Confirm PIN");
            confirm.setTextColor(TEXT);
            box.addView(pin);
            box.addView(confirm);
            new AlertDialog.Builder(this).setTitle("Create private bookmark PIN").setView(box)
                    .setPositiveButton("Create", (d, w) -> {
                        String a = pin.getText().toString();
                        if (!a.matches("[0-9]{4,8}") || !a.equals(confirm.getText().toString())) {
                            Toast.makeText(this, "PINs must match and contain 4–8 digits.", Toast.LENGTH_LONG).show();
                        } else if (bookmarkStore.setPrivatePin(a)) saveBookmark(title, url, true);
                        else Toast.makeText(this, "Could not create the private PIN.", Toast.LENGTH_SHORT).show();
                    }).setNegativeButton("Cancel", null).show();
        }
    }

    private interface PinCallback { void onPin(String pin); }

    private void promptPin(String title, PinCallback callback) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint("PIN");
        input.setTextColor(TEXT);
        new AlertDialog.Builder(this).setTitle(title).setView(input)
                .setPositiveButton("Unlock", (d, w) -> callback.onPin(input.getText().toString()))
                .setNegativeButton("Cancel", null).show();
    }

    private void saveBookmark(String title, String url, boolean isPrivate) {
        try {
            bookmarkStore.add(title, url, isPrivate);
            Toast.makeText(this, isPrivate ? "Saved to encrypted private bookmarks" : "Bookmark saved", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Could not save bookmark.", Toast.LENGTH_SHORT).show();
        }
    }

    private void showBookmarks() {
        if (privateMode) {
            Toast.makeText(this, "Bookmarks are unavailable in private windows.", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Bookmarks")
                .setItems(new String[] {"Regular bookmarks", "Private bookmarks (PIN)"}, (d, which) -> {
                    if (which == 0) displayBookmarkList(false);
                    else openPrivateBookmarks();
                }).show();
    }

    private void openPrivateBookmarks() {
        if (!bookmarkStore.hasPrivatePin()) {
            Toast.makeText(this, "No private bookmarks have been created yet.", Toast.LENGTH_SHORT).show();
            return;
        }
        promptPin("Unlock private bookmarks", pin -> {
            if (bookmarkStore.verifyPin(pin)) displayBookmarkList(true);
            else Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show();
        });
    }

    private void displayBookmarkList(boolean privateList) {
        List<BookmarkStore.Bookmark> list = privateList
                ? bookmarkStore.getPrivateBookmarks() : bookmarkStore.getPublicBookmarks();
        if (list.isEmpty()) {
            Toast.makeText(this, "No bookmarks saved yet.", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] titles = new String[list.size()];
        for (int i = 0; i < list.size(); i++) titles[i] = list.get(i).title + "\n" + shorten(list.get(i).url, 60);
        new AlertDialog.Builder(this).setTitle(privateList ? "Private bookmarks" : "Bookmarks")
                .setItems(titles, (d, which) -> navigate(list.get(which).url))
                .setNegativeButton("Close", null)
                .setNeutralButton("Manage", (d, w) -> manageBookmarkList(privateList, list)).show();
    }

    private void manageBookmarkList(boolean privateList, List<BookmarkStore.Bookmark> list) {
        String[] titles = new String[list.size()];
        for (int i = 0; i < list.size(); i++) titles[i] = "Remove: " + list.get(i).title;
        new AlertDialog.Builder(this).setTitle("Remove bookmark")
                .setItems(titles, (d, which) -> {
                    try {
                        bookmarkStore.remove(list.get(which), privateList);
                        Toast.makeText(this, "Bookmark removed", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) { Toast.makeText(this, "Could not remove bookmark", Toast.LENGTH_SHORT).show(); }
                }).setNegativeButton("Cancel", null).show();
    }

    private void toggleAdBlock() {
        boolean enabled = !adBlocker.isEnabled();
        adBlocker.setEnabled(enabled);
        getSharedPreferences("jbrowser", MODE_PRIVATE).edit().putBoolean("adblock_enabled", enabled).apply();
        updateToolbarState();
        Tab tab = activeTab();
        if (tab != null && !tab.isHome) injectPageEnhancements(tab.webView, tab.url);
        status(enabled ? "Ad and tracker protection enabled" : "Ad and tracker protection paused");
    }

    private void updateToolbarState() {
        if (tabCountButton != null) tabCountButton.setText("TABS " + tabs.size());
        if (adBlockButton != null) {
            boolean enabled = adBlocker != null && adBlocker.isEnabled();
            adBlockButton.setText(enabled ? "BLOCK ON" : "BLOCK OFF");
            adBlockButton.setTextColor(enabled ? ACCENT : MUTED);
        }
        renderTabStrip();
    }

    private void renderTabStrip() {
        if (tabStrip == null) return;
        tabStrip.removeAllViews();
        for (int i = 0; i < tabs.size(); i++) {
            final int index = i;
            Tab tab = tabs.get(i);
            boolean selected = i == activeIndex;
            LinearLayout chip = new LinearLayout(this);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setPadding(dp(10), 0, dp(5), 0);
            chip.setBackground(round(selected ? Color.rgb(25, 57, 67) : PANEL,
                    9, selected ? ACCENT : Color.rgb(43, 60, 79), 1));
            chip.setFocusable(true);
            chip.setClickable(true);
            chip.setOnFocusChangeListener((v, focused) -> v.setBackground(round(
                    focused ? ACCENT : (index == activeIndex ? Color.rgb(25, 57, 67) : PANEL),
                    9, focused ? ACCENT : (index == activeIndex ? ACCENT : Color.rgb(43, 60, 79)), 1)));

            String title = tab.isHome ? "New tab" : (tab.title == null || tab.title.trim().isEmpty()
                    ? shorten(tab.url, 22) : shorten(tab.title, 22));
            TextView label = text((selected ? "●  " : "") + title, 13,
                    selected ? ACCENT : TEXT, selected);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            label.setGravity(Gravity.CENTER_VERTICAL);
            chip.addView(label, new LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.MATCH_PARENT));
            TextView close = text("×", 20, MUTED, true);
            close.setGravity(Gravity.CENTER);
            close.setFocusable(true);
            close.setClickable(true);
            close.setContentDescription("Close " + title);
            close.setBackground(round(Color.TRANSPARENT, 7, Color.TRANSPARENT, 0));
            close.setOnFocusChangeListener((v, focused) -> {
                v.setBackground(round(focused ? Color.rgb(8, 35, 42) : Color.TRANSPARENT,
                        7, focused ? BG : Color.TRANSPARENT, focused ? 1 : 0));
                ((TextView) v).setTextColor(focused ? BG : MUTED);
            });
            close.setOnClickListener(v -> closeTabAt(index));
            chip.addView(close, new LinearLayout.LayoutParams(dp(30), dp(32)));
            chip.setOnClickListener(v -> activateTab(index));
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(dp(198), dp(36));
            chipLp.rightMargin = dp(7);
            tabStrip.addView(chip, chipLp);
        }
        TextView add = control("+ NEW", TEXT, TEXT, 76);
        add.setTextSize(12);
        add.setOnClickListener(v -> newTab());
        tabStrip.addView(add, new LinearLayout.LayoutParams(dp(82), dp(36)));
        if (activeIndex >= 0 && activeIndex < tabs.size()) {
            tabStripScroll.post(() -> {
                View activeChip = tabStrip.getChildAt(activeIndex);
                if (activeChip == null) return;
                int left = activeChip.getLeft();
                int right = activeChip.getRight();
                int visibleLeft = tabStripScroll.getScrollX();
                int visibleRight = visibleLeft + tabStripScroll.getWidth();
                if (right > visibleRight) tabStripScroll.smoothScrollTo(right - tabStripScroll.getWidth(), 0);
                else if (left < visibleLeft) tabStripScroll.smoothScrollTo(left, 0);
            });
        }
    }

    private void updatePageProgress(int progress) {
        if (pageProgress == null) return;
        if (progress >= 100) {
            pageProgress.setProgress(100);
            pageProgress.setVisibility(View.GONE);
        } else {
            pageProgress.setVisibility(View.VISIBLE);
            pageProgress.setProgress(Math.max(0, progress));
        }
    }

    private void showMoreMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Settings dashboard");
        menu.getMenu().add("Downloads");
        menu.getMenu().add("Browsing history");
        menu.getMenu().add("Manage homepage shortcuts");
        menu.getMenu().add("New private window");
        menu.getMenu().add("Private bookmarks (PIN)");
        menu.getMenu().add("Site protection settings");
        menu.getMenu().add("Update ad-block filters");
        menu.getMenu().add("About JBrowser");
        menu.setOnMenuItemClickListener(item -> {
            String title = item.getTitle().toString();
            if (title.equals("Settings dashboard")) showSettingsDashboard();
            else if (title.equals("Downloads")) showDownloadsDialog();
            else if (title.equals("Browsing history")) showHistoryDialog();
            else if (title.equals("Manage homepage shortcuts")) showHomeShortcutManager();
            else if (title.startsWith("New private")) launchPrivateWindow();
            else if (title.startsWith("Private bookmarks")) openPrivateBookmarks();
            else if (title.equals("Site protection settings")) showSiteProtectionDialog();
            else if (title.startsWith("Update ad-block")) updateFiltersNow();
            else if (title.equals("About JBrowser")) showAbout();
            return true;
        });
        menu.show();
    }

    private void showSettingsDashboard() {
        String[] categories = {"Privacy & protection", "Appearance & homepage", "Playback", "General & data"};
        new AlertDialog.Builder(this).setTitle("JBrowser settings")
                .setItems(categories, (dialog, which) -> showSettingsCategory(which))
                .setNegativeButton("Done", null).show();
    }

    private void showSettingsCategory(int category) {
        String title;
        String[] items;
        if (category == 0) {
            title = "Privacy & protection";
            items = new String[] {
                    "Ad and tracker blocking: " + (adBlocker.isEnabled() ? "ON" : "OFF"),
                    "Site-specific protection settings",
                    "Cookie-banner cleaner: " + (cookieCleaner ? "ON" : "OFF"),
                    "Third-party cookies: " + (thirdPartyCookies ? "ALLOWED" : "BLOCKED"),
                    "Update protection filter lists",
                    "Open a private window",
                    "Clear browsing data"
            };
        } else if (category == 1) {
            title = "Appearance & homepage";
            items = new String[] {
                    "Customize homepage shortcuts",
                    "Choose search engine",
                    desktopMode ? "Request mobile websites" : "Request desktop websites",
                    "Change website text size"
            };
        } else if (category == 2) {
            title = "Playback";
            items = new String[] {
                    "Default video speed: " + String.format(Locale.US, "%.2g×", defaultPlaybackSpeed),
                    "Allow video autoplay: " + (allowMediaAutoplay ? "ON" : "OFF"),
                    "Set current video to normal speed (1.0×)",
                    "Change current video speed"
            };
        } else {
            title = "General & data";
            items = new String[] {
                    "Download manager",
                    "Browsing history",
                    "Bookmarks",
                    "Manage tabs",
                    "About JBrowser"
            };
        }
        new AlertDialog.Builder(this).setTitle(title).setItems(items, (dialog, which) -> {
            if (category == 0) {
                if (which == 0) toggleAdBlock();
                else if (which == 1) showSiteProtectionDialog();
                else if (which == 2) toggleCookieCleaner();
                else if (which == 3) toggleThirdPartyCookies();
                else if (which == 4) updateFiltersNow();
                else if (which == 5) launchPrivateWindow();
                else if (which == 6) confirmClearData();
            } else if (category == 1) {
                if (which == 0) showHomeShortcutManager();
                else if (which == 1) showSearchEngineDialog();
                else if (which == 2) toggleDesktopMode();
                else if (which == 3) showZoomDialog();
            } else if (category == 2) {
                if (which == 0) showDefaultPlaybackSpeedDialog();
                else if (which == 1) toggleMediaAutoplay();
                else if (which == 2) setVideoPlaybackRate(1.0f);
                else if (which == 3) showPlaybackSpeedDialog();
            } else {
                if (which == 0) showDownloadsDialog();
                else if (which == 1) showHistoryDialog();
                else if (which == 2) showBookmarks();
                else if (which == 3) showTabsDialog();
                else if (which == 4) showAbout();
            }
        }).setNegativeButton("Back", (dialog, which) -> showSettingsDashboard()).show();
    }

    private void showSearchEngineDialog() {
        String[] names = {"Google", "Bing", "DuckDuckGo", "Yahoo", "Brave Search", "Custom search URL…"};
        String[] templates = {
                "https://www.google.com/search?q=%s",
                "https://www.bing.com/search?q=%s",
                "https://duckduckgo.com/?q=%s",
                "https://search.yahoo.com/search?p=%s",
                "https://search.brave.com/search?q=%s"
        };
        int selected = -1;
        for (int i = 0; i < names.length - 1; i++) {
            if (names[i].equalsIgnoreCase(searchEngineName)) { selected = i; break; }
        }
        new AlertDialog.Builder(this)
                .setTitle("Choose search engine")
                .setSingleChoiceItems(names, selected, (dialog, which) -> {
                    if (which < templates.length) {
                        saveSearchEngine(names[which], templates[which]);
                        dialog.dismiss();
                    } else {
                        dialog.dismiss();
                        promptCustomSearchEngine();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptCustomSearchEngine() {
        LinearLayout box = dialogBox();
        EditText nameInput = new EditText(this);
        nameInput.setSingleLine(true);
        nameInput.setTextSize(17);
        nameInput.setTextColor(TEXT);
        nameInput.setHintTextColor(MUTED);
        nameInput.setHint("Search provider name");
        nameInput.setText(searchEngineName.equals("Google") ? "Custom" : searchEngineName);
        nameInput.setBackground(round(PANEL_2, 10, Color.rgb(50, 75, 96), 1));
        nameInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        EditText templateInput = new EditText(this);
        templateInput.setSingleLine(true);
        templateInput.setTextSize(15);
        templateInput.setTextColor(TEXT);
        templateInput.setHintTextColor(MUTED);
        templateInput.setHint("https://example.com/search?q=%s");
        templateInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        templateInput.setText(searchEngineTemplate);
        templateInput.setBackground(round(PANEL_2, 10, Color.rgb(50, 75, 96), 1));
        templateInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.addView(nameInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        LinearLayout.LayoutParams templateLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        templateLp.topMargin = dp(10);
        box.addView(templateInput, templateLp);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Custom search provider")
                .setMessage("Use %s where the encoded search terms should be inserted. HTTPS is required.")
                .setView(box)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = nameInput.getText().toString().trim();
            String template = templateInput.getText().toString().trim();
            if (name.isEmpty()) name = "Custom";
            if (!isValidSearchTemplate(template)) {
                templateInput.setError("Enter a valid HTTPS URL containing %s");
                return;
            }
            saveSearchEngine(name, template);
            dialog.dismiss();
        }));
        dialog.show();
    }

    private boolean isValidSearchTemplate(String template) {
        if (template == null || !template.contains("%s")) return false;
        try {
            Uri uri = Uri.parse(template.replace("%s", "jbrowser-test"));
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && !uri.getHost().trim().isEmpty();
        } catch (Exception ignored) { return false; }
    }

    private void saveSearchEngine(String name, String template) {
        if (!isValidSearchTemplate(template)) return;
        searchEngineName = name;
        searchEngineTemplate = template;
        getSharedPreferences("jbrowser", MODE_PRIVATE).edit()
                .putString("search_engine_name", name)
                .putString("search_engine_template", template)
                .apply();
        status("Search engine set to " + name);
        Tab tab = activeTab();
        if (tab != null && tab.isHome) showHomeForActiveTab();
        Toast.makeText(this, "Search engine: " + name, Toast.LENGTH_SHORT).show();
    }

    private void showSiteProtectionDialog() {
        Tab tab = activeTab();
        if (tab == null || tab.isHome || tab.url.isEmpty()) {
            Toast.makeText(this, "Open a website to change its protection settings.", Toast.LENGTH_SHORT).show();
            return;
        }
        String host = AdBlockEngine.hostOf(tab.url);
        if (host.isEmpty()) {
            Toast.makeText(this, "This page does not have a supported website address.", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean[] checked = new boolean[] {
                adBlocker.isSiteNetworkEnabled(tab.url),
                adBlocker.isSiteCosmeticEnabled(tab.url)
        };
        String[] options = new String[] {
                "Block ads and trackers on this site",
                "Apply cosmetic ad filters on this site"
        };
        new AlertDialog.Builder(this)
                .setTitle("Protection: " + host)
                .setMessage("These settings affect only this hostname. The main BLOCK switch must also be ON. The global cookie-banner cleaner has its own switch in More settings.")
                .setMultiChoiceItems(options, checked, (dialog, which, isChecked) -> {
                    if (which == 0) adBlocker.setSiteNetworkEnabled(tab.url, isChecked);
                    else adBlocker.setSiteCosmeticEnabled(tab.url, isChecked);
                    injectPageEnhancements(tab.webView, tab.url);
                    status("Site protection updated for " + host);
                })
                .setNeutralButton("Reset site", (dialog, which) -> {
                    adBlocker.resetSiteSettings(tab.url);
                    injectPageEnhancements(tab.webView, tab.url);
                    status("Protection settings reset for " + host);
                    Toast.makeText(this, "Site settings reset", Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton("Done", null)
                .show();
    }

    private void launchPrivateWindow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Toast.makeText(this, "Private windows require Android 9 or newer on this build.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Intent intent = new Intent(this, PrivateActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open private window.", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateFiltersNow() {
        status("Downloading ad and tracker filter lists…");
        adBlocker.updateFilters((success, count, message) -> {
            Tab tab = activeTab();
            if (tab != null && !tab.isHome) injectPageEnhancements(tab.webView, tab.url);
            status(message + " • " + count + " rules");
            Toast.makeText(this, message + " (" + count + " rules)", Toast.LENGTH_LONG).show();
        });
    }

    private void toggleDesktopMode() {
        desktopMode = !desktopMode;
        getSharedPreferences("jbrowser", MODE_PRIVATE).edit().putBoolean("desktop_mode", desktopMode).apply();
        for (Tab tab : tabs) {
            WebSettings settings = tab.webView.getSettings();
            settings.setUserAgentString(desktopMode ? desktopUserAgent() : WebSettings.getDefaultUserAgent(this));
            if (!tab.isHome) tab.webView.reload();
        }
        status(desktopMode ? "Desktop websites requested" : "Mobile websites requested");
    }

    private void toggleMediaAutoplay() {
        allowMediaAutoplay = !allowMediaAutoplay;
        getSharedPreferences("jbrowser", MODE_PRIVATE).edit()
                .putBoolean("media_autoplay_allowed", allowMediaAutoplay).apply();
        for (Tab tab : tabs) {
            if (tab.webView != null) {
                tab.webView.getSettings().setMediaPlaybackRequiresUserGesture(!allowMediaAutoplay);
            }
        }
        status(allowMediaAutoplay ? "Video autoplay allowed" : "Video autoplay requires user interaction");
        Toast.makeText(this, allowMediaAutoplay
                ? "Video autoplay enabled" : "Video autoplay disabled", Toast.LENGTH_SHORT).show();
    }

    private void toggleThirdPartyCookies() {
        thirdPartyCookies = !thirdPartyCookies;
        getSharedPreferences("jbrowser", MODE_PRIVATE).edit().putBoolean("third_party_cookies", thirdPartyCookies).apply();
        for (Tab tab : tabs) CookieManager.getInstance().setAcceptThirdPartyCookies(tab.webView, thirdPartyCookies);
        status(thirdPartyCookies ? "Third-party cookies allowed for site compatibility" : "Third-party cookies blocked");
    }

    private void toggleCookieCleaner() {
        cookieCleaner = !cookieCleaner;
        getSharedPreferences("jbrowser", MODE_PRIVATE).edit().putBoolean("cookie_cleaner", cookieCleaner).apply();
        Tab tab = activeTab();
        if (tab != null && !tab.isHome) injectPageEnhancements(tab.webView, tab.url);
        status(cookieCleaner ? "Cookie-banner cleaner enabled" : "Cookie-banner cleaner disabled");
    }

    private void confirmClearData() {
        new AlertDialog.Builder(this).setTitle("Clear browsing data?")
                .setMessage("This clears cookies, cached files, saved site data and page history for the normal browser. It may sign you out of websites.")
                .setPositiveButton("Clear data", (d, w) -> clearBrowsingData())
                .setNegativeButton("Cancel", null).show();
    }

    private void clearBrowsingData() {
        for (Tab tab : tabs) {
            tab.webView.clearCache(true);
            tab.webView.clearHistory();
            tab.webView.clearFormData();
        }
        CookieManager.getInstance().removeAllCookies(value -> CookieManager.getInstance().flush());
        WebStorage.getInstance().deleteAllData();
        getSharedPreferences("jbrowser", MODE_PRIVATE).edit().remove("recent_sites").apply();
        Tab active = activeTab();
        if (active != null && active.isHome) showHomeForActiveTab();
        status("Browsing data cleared");
        Toast.makeText(this, "Browsing data cleared", Toast.LENGTH_SHORT).show();
    }

    private void showZoomDialog() {
        String[] zooms = {"75%", "90%", "100%", "110%", "125%", "150%", "175%", "200%"};
        new AlertDialog.Builder(this).setTitle("Text size")
                .setItems(zooms, (d, which) -> {
                    int[] values = {75, 90, 100, 110, 125, 150, 175, 200};
                    Tab tab = activeTab();
                    if (tab != null) tab.webView.getSettings().setTextZoom(values[which]);
                }).show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this).setTitle("JBrowser for Android TV")
                .setMessage("Version 1.5.0\n\nA TV-first browser built around Android System WebView. Version 1.5 adds website file uploads, user-initiated links opening in new tabs, a saved video-autoplay preference, improved first-party classification, badfilter rule cancellation, and cached cosmetic styles for faster repeat page loads. It also includes a download manager, editable homepage shortcuts, history management, private browsing, and fullscreen video controls.\n\nFilter compatibility remains a practical subset of uBlock Origin / Adblock Plus syntax, not the full browser-extension engine. Video quality, codecs, HDR and DRM support depend on your TV, WebView and the streaming service.")
                .setPositiveButton("OK", null).show();
    }

    private LinearLayout dialogBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(8), dp(22), dp(8));
        return box;
    }

    private void enqueueDownload(String url, String userAgent, String contentDisposition, String mimeType) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingDownloadUrl = url;
            pendingDownloadUserAgent = userAgent;
            pendingDownloadDisposition = contentDisposition;
            pendingDownloadMimeType = mimeType;
            requestPermissions(new String[] {android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_WRITE_DOWNLOAD_PERMISSION);
            return;
        }
        enqueueDownloadNow(url, userAgent, contentDisposition, mimeType);
    }

    private void enqueueDownloadNow(String url, String userAgent, String contentDisposition, String mimeType) {
        try {
            Uri downloadUri = Uri.parse(url == null ? "" : url.trim());
            String scheme = downloadUri.getScheme();
            if (downloadUri.getHost() == null || !("http".equalsIgnoreCase(scheme)
                    || "https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("Only HTTP and HTTPS downloads are supported");
            }
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (manager == null) throw new IllegalStateException("Download service unavailable");
            DownloadManager.Request request = new DownloadManager.Request(downloadUri);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(false);
            if (mimeType != null && !mimeType.isEmpty()) request.setMimeType(mimeType);
            if (userAgent != null && !userAgent.isEmpty()) request.addRequestHeader("User-Agent", userAgent);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null && !cookie.isEmpty()) request.addRequestHeader("Cookie", cookie);
            Tab active = activeTab();
            if (active != null && isHttpUrl(active.url)) request.addRequestHeader("Referer", active.url);
            String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
            fileName = sanitizeDownloadFileName(fileName);
            fileName = makeDownloadNameUnique(manager, fileName);
            request.setTitle(fileName);
            request.setDescription("Downloading with JBrowser");
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            manager.enqueue(request);
            Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Download could not be started: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String sanitizeDownloadFileName(String rawName) {
        String name = rawName == null ? "download" : rawName.trim();
        name = name.replaceAll("[\\x00-\\x1F\\\\/:*?\"<>|]", "_");
        while (name.startsWith(".")) name = name.substring(1);
        if (name.isEmpty()) name = "download";
        if (name.length() > 120) {
            int dot = name.lastIndexOf('.');
            String extension = dot > 0 && name.length() - dot <= 12 ? name.substring(dot) : "";
            name = name.substring(0, Math.max(1, 120 - extension.length())) + extension;
        }
        return name;
    }

    private String makeDownloadNameUnique(DownloadManager manager, String originalName) {
        HashSet<String> existing = new HashSet<>();
        Cursor cursor = null;
        try {
            cursor = manager.query(new DownloadManager.Query());
            if (cursor != null) {
                int titleColumn = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE);
                while (cursor.moveToNext()) {
                    if (titleColumn >= 0) {
                        String title = cursor.getString(titleColumn);
                        if (title != null) existing.add(title.toLowerCase(Locale.ROOT));
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        if (!existing.contains(originalName.toLowerCase(Locale.ROOT))) return originalName;
        int dot = originalName.lastIndexOf('.');
        String base = dot > 0 ? originalName.substring(0, dot) : originalName;
        String extension = dot > 0 ? originalName.substring(dot) : "";
        for (int suffix = 2; suffix < 1000; suffix++) {
            String candidate = base + " (" + suffix + ")" + extension;
            if (!existing.contains(candidate.toLowerCase(Locale.ROOT))) return candidate;
        }
        return base + " (" + System.currentTimeMillis() + ")" + extension;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_FILE_CHOOSER) {
            ValueCallback<Uri[]> callback = pendingFileChooserCallback;
            pendingFileChooserCallback = null;
            if (callback != null) {
                Uri[] results = null;
                if (resultCode == RESULT_OK && data != null) {
                    ClipData clipData = data.getClipData();
                    if (clipData != null && clipData.getItemCount() > 0) {
                        results = new Uri[clipData.getItemCount()];
                        for (int i = 0; i < clipData.getItemCount(); i++) {
                            results[i] = clipData.getItemAt(i).getUri();
                        }
                    } else if (data.getData() != null) {
                        results = new Uri[] {data.getData()};
                    }
                }
                callback.onReceiveValue(results);
            }
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_WRITE_DOWNLOAD_PERMISSION) {
            String url = pendingDownloadUrl;
            String agent = pendingDownloadUserAgent;
            String disposition = pendingDownloadDisposition;
            String mime = pendingDownloadMimeType;
            pendingDownloadUrl = pendingDownloadUserAgent = pendingDownloadDisposition = pendingDownloadMimeType = null;
            if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
                    && url != null) {
                enqueueDownloadNow(url, agent, disposition, mime);
            } else {
                Toast.makeText(this, "Storage permission is needed to save downloads on this Android version.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void showDownloadsDialog() {
        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (manager == null) {
            Toast.makeText(this, "Download service is unavailable on this device.", Toast.LENGTH_SHORT).show();
            return;
        }
        ArrayList<DownloadEntry> entries = new ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = manager.query(new DownloadManager.Query());
            if (cursor != null) {
                int idCol = cursor.getColumnIndex(DownloadManager.COLUMN_ID);
                int titleCol = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE);
                int mimeCol = cursor.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE);
                int statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                int downloadedCol = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                int totalCol = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                while (cursor.moveToNext() && entries.size() < 60) {
                    long id = idCol >= 0 ? cursor.getLong(idCol) : -1L;
                    String name = titleCol >= 0 ? cursor.getString(titleCol) : "Download";
                    String mime = mimeCol >= 0 ? cursor.getString(mimeCol) : "application/octet-stream";
                    int status = statusCol >= 0 ? cursor.getInt(statusCol) : DownloadManager.STATUS_FAILED;
                    long downloaded = downloadedCol >= 0 ? cursor.getLong(downloadedCol) : 0L;
                    long total = totalCol >= 0 ? cursor.getLong(totalCol) : -1L;
                    entries.add(new DownloadEntry(id, name == null ? "Download" : name, mime, status, downloaded, total));
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, "Could not read downloads.", Toast.LENGTH_SHORT).show();
        } finally {
            if (cursor != null) cursor.close();
        }
        if (entries.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Downloads")
                    .setMessage("Your downloads will appear here. Files are saved in the device's Downloads folder.")
                    .setPositiveButton("Done", null).show();
            return;
        }
        String[] labels = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            DownloadEntry entry = entries.get(i);
            labels[i] = entry.title + "\n" + downloadStatusLabel(entry) + " • "
                    + formatBytes(entry.downloaded) + (entry.total > 0 ? " / " + formatBytes(entry.total) : "");
        }
        new AlertDialog.Builder(this).setTitle("Downloads • " + entries.size())
                .setItems(labels, (dialog, which) -> showDownloadActions(entries.get(which)))
                .setNeutralButton("Refresh", (dialog, which) -> showDownloadsDialog())
                .setPositiveButton("Done", null).show();
    }

    private String downloadStatusLabel(DownloadEntry entry) {
        if (entry.status == DownloadManager.STATUS_SUCCESSFUL) return "Completed";
        if (entry.status == DownloadManager.STATUS_RUNNING) return "Downloading";
        if (entry.status == DownloadManager.STATUS_PENDING) return "Waiting";
        if (entry.status == DownloadManager.STATUS_PAUSED) return "Paused";
        return "Failed / stopped";
    }

    private String formatBytes(long bytes) {
        if (bytes < 0) return "Unknown size";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private void showDownloadActions(DownloadEntry entry) {
        boolean completed = entry.status == DownloadManager.STATUS_SUCCESSFUL;
        String[] actions = completed ? new String[] {"Open file", "Remove download"}
                : new String[] {"Cancel download", "Remove download"};
        new AlertDialog.Builder(this).setTitle(entry.title)
                .setMessage(downloadStatusLabel(entry))
                .setItems(actions, (dialog, which) -> {
                    if (completed && which == 0) openDownloadedFile(entry);
                    else {
                        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                        if (manager != null) manager.remove(entry.id);
                        Toast.makeText(this, completed ? "Download removed" : "Download cancelled", Toast.LENGTH_SHORT).show();
                        showDownloadsDialog();
                    }
                }).setNegativeButton("Back", (dialog, which) -> showDownloadsDialog()).show();
    }

    private void openDownloadedFile(DownloadEntry entry) {
        try {
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            Uri uri = manager == null ? null : manager.getUriForDownloadedFile(entry.id);
            if (uri == null) {
                Toast.makeText(this, "The downloaded file is not available.", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, (entry.mimeType == null || entry.mimeType.trim().isEmpty()) ? "*/*" : entry.mimeType);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No installed app can open this file type.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Could not open the downloaded file.", Toast.LENGTH_SHORT).show();
        }
    }

    private void openExternal(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (ActivityNotFoundException ignored) { Toast.makeText(this, "No app can open this link.", Toast.LENGTH_SHORT).show(); }
    }

    private void exitCustomView() {
        if (customView == null) return;
        stopVideoUiPolling();
        if (customView.getParent() instanceof ViewGroup) ((ViewGroup) customView.getParent()).removeView(customView);
        customView = null;
        fullscreenRoot = null;
        fullscreenControlBar = null;
        fullscreenPlayButton = null;
        fullscreenVideoTitle = null;
        fullscreenTimeText = null;
        fullscreenSpeedButton = null;
        fullscreenSeekBar = null;
        videoSeekInProgress = false;
        currentVideoDurationSeconds = 0;
        currentVideoPositionSeconds = 0;
        setContentView(pageRoot);
        pageRoot.setVisibility(View.VISIBLE);
        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }
        applyImmersiveMode();
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(addressBar.getWindowToken(), 0);
        } catch (Exception ignored) { }
        Tab tab = activeTab();
        if (tab != null && !tab.isHome) tab.webView.requestFocus();
    }

    private void status(String value) {
        if (statusText != null) statusText.setText(value);
    }

    private String shorten(String value, int max) {
        if (value == null) return "";
        return value.length() > max ? value.substring(0, max - 1) + "…" : value;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            int key = event.getKeyCode();
            if (event.isCtrlPressed()) {
                if (key == KeyEvent.KEYCODE_L) { addressBar.requestFocus(); addressBar.selectAll(); return true; }
                if (key == KeyEvent.KEYCODE_T) { newTab(); return true; }
                if (key == KeyEvent.KEYCODE_W) { closeActiveTab(); return true; }
                if (key == KeyEvent.KEYCODE_R) { reload(); return true; }
                if (key == KeyEvent.KEYCODE_H) { showHistoryDialog(); return true; }
                if (key == KeyEvent.KEYCODE_J) { showDownloadsDialog(); return true; }
                if (key == KeyEvent.KEYCODE_TAB && tabs.size() > 1) {
                    int direction = event.isShiftPressed() ? -1 : 1;
                    activateTab((activeIndex + direction + tabs.size()) % tabs.size());
                    return true;
                }
            }
            if (key == KeyEvent.KEYCODE_F5) { reload(); return true; }
            if (key == KeyEvent.KEYCODE_MENU && moreMenuButton != null) {
                showMoreMenu(moreMenuButton);
                return true;
            }
            if (key == KeyEvent.KEYCODE_BACK) {
                onBackPressed();
                return true;
            }

            // Hardware media keys work both in fullscreen and ordinary page playback.
            if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                toggleVideoPlayback();
                return true;
            }
            if (key == KeyEvent.KEYCODE_MEDIA_PLAY) {
                setVideoPlaying(true);
                return true;
            }
            if (key == KeyEvent.KEYCODE_MEDIA_PAUSE || key == KeyEvent.KEYCODE_MEDIA_STOP) {
                setVideoPlaying(false);
                return true;
            }
            if (key == KeyEvent.KEYCODE_MEDIA_REWIND) {
                seekVideoBy(-10);
                return true;
            }
            if (key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                seekVideoBy(30);
                return true;
            }

            // When focus is on the fullscreen toolbar, keep normal D-pad focus movement.
            // When the video surface has focus, left/right seek and center toggles playback.
            if (fullscreenRoot != null && !isFullscreenControlFocused()) {
                if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
                    seekVideoBy(-10);
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    seekVideoBy(10);
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
                    toggleVideoPlayback();
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (customView != null) { exitCustomView(); return; }
        Tab tab = activeTab();
        if (tab != null && !tab.isHome && tab.webView.canGoBack()) {
            tab.webView.goBack();
            return;
        }
        if (tab != null && !tab.isHome) {
            goHome();
            return;
        }
        if (tabs.size() > 1) {
            closeActiveTab();
            return;
        }
        if (privateMode) finish();
        else super.onBackPressed();
    }

    private void clearPrivateSessionData() {
        try {
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
            WebStorage.getInstance().deleteAllData();
        } catch (Exception ignored) { }
    }

    @Override
    protected void onPause() {
        super.onPause();
        for (Tab tab : tabs) tab.webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersiveMode();
        for (Tab tab : tabs) tab.webView.onResume();
    }

    @Override
    protected void onDestroy() {
        if (pendingFileChooserCallback != null) {
            pendingFileChooserCallback.onReceiveValue(null);
            pendingFileChooserCallback = null;
        }
        for (Tab tab : tabs) {
            try {
                tab.webView.stopLoading();
                if (privateMode && isFinishing()) {
                    tab.webView.clearCache(true);
                    tab.webView.clearHistory();
                    tab.webView.clearFormData();
                }
                tab.webView.destroy();
            } catch (Exception ignored) { }
        }
        tabs.clear();
        if (privateMode && isFinishing()) clearPrivateSessionData();
        super.onDestroy();
    }

}
