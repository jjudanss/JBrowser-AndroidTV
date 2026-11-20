# Changelog

## 1.5.0

- Added Android WebView file-upload support through the device's document picker, including multi-select where the page requests it.
- Added new-tab support for user-initiated `target=_blank` links while continuing to reject automatic pop-ups.
- Added a saved video-autoplay preference; it can be changed in Settings → Playback and applies to current and newly created tabs.
- Added best-effort registrable-domain comparison for first-/third-party filter options, including a compact table of common multi-label public suffixes.
- Added `$badfilter` cancellation for matching network rules and `$all` resource-type support.
- Added a bounded, 24-entry cosmetic CSS cache to reduce repeated selector collection and CSS assembly for revisited hosts.
- Added the current page URL as the Referer header on browser-managed downloads when available, improving compatibility with authenticated and hotlink-protected file hosts.
- Updated version metadata, About text, GitHub Actions artifact/release names, documentation and TV test plan.

## 1.4.0

- Added a desktop-inspired, horizontally scrollable toolbar and visible five-tab strip with per-tab close buttons.
- Added a slim page-load progress bar and stronger address-bar focus treatment.
- Improved remote navigation: page scrolling is no longer intercepted by DPAD_UP except at the top edge.
- Added optional keyboard shortcuts for address bar, tabs, reload, history and downloads.
- Hardened download URL validation and filename sanitization; tracked duplicate filenames receive a numbered suffix.
- Expanded filter support for `$domain=`, `$important`, `$match-case`, negative resource types, and common aliases (`xhr`, `frame`, `object`).
- Changed filter matching order so important blocking rules override ordinary exceptions.
- Updated the About screen, version metadata, build workflow, release notes and installation guide.

## 1.3.0

- Added download manager, history management, editable homepage shortcuts, categorized settings and a saved default video playback speed.
- Added support for up to 24 homepage shortcuts.
- Kept a five-tab limit to reduce memory pressure on lower-powered TV devices.
