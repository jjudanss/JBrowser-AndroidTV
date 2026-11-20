# JBrowser filter sources and compatibility

JBrowser downloads and combines the configured uAssets, EasyList and EasyPrivacy sources in `AdBlockEngine.java`. The list bundle is parsed locally and cached in the app's private files directory. If an update fails, the previously cached rules remain active.

## Supported useful subset

- Hosts entries (`0.0.0.0` / `127.0.0.1`).
- Domain-anchored network filters (`||host.example^`, basic wildcard path patterns).
- Basic allow exceptions (`@@`).
- Resource-type options: script, image, stylesheet, xmlhttprequest/xhr, subdocument/frame, font, media, ping, other, object/object-subrequest, document and `all`.
- Positive and negative resource types (for example `$script,~image`).
- First-/third-party options using best-effort registrable-domain comparison and common compound public suffixes.
- `$domain=` page-domain include/exclude scope.
- `$important`, `$match-case` and `$badfilter` cancellation of a corresponding network filter.
- Common CSS cosmetic rules, domain-scoped cosmetic rules, negative-domain scope and cosmetic exceptions.

## Important limitations

This is not the full uBlock Origin extension engine. It does not execute uBO scriptlets or procedural cosmetic operators, and does not implement redirect/redirect-rule, CSP, removeparam, arbitrary generic URL regular expressions, popup filtering or all option combinations. Such filters are skipped rather than guessed. WebView does not expose the full extension APIs available to desktop Chromium extensions.

The registrable-domain comparison uses a compact table of common compound suffixes, not the complete Public Suffix List. First-/third-party matching may therefore be imperfect for uncommon public suffixes. The built-in cosmetic cleaner is intentionally conservative. Some websites may need site-specific blocking paused to avoid breaking page layouts or video players.
