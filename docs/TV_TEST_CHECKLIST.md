# Android TV testing checklist — JBrowser 1.5.0

## Installation and launch
- [ ] Install APK on Android TV / TV box running Android 6.0 or newer.
- [ ] Confirm JBrowser appears in the TV launcher.
- [ ] Launch in landscape mode and confirm no screen clipping at 720p and 1080p.

## Browser chrome and navigation
- [ ] Navigate address bar and GO using a D-pad remote.
- [ ] Use toolbar actions when they are off-screen horizontally.
- [ ] Switch tabs using the visible tab strip and the tab manager.
- [ ] Close one tab from its tab chip; verify the active tab remains correct.
- [ ] Confirm DPAD_UP scrolls a page and only moves to the address bar when already at page top.
- [ ] Test Ctrl+L/T/W/R/H/J and Ctrl+Tab with a USB/Bluetooth keyboard.
- [ ] Confirm the progress bar appears while pages load and disappears after completion/error.

## Website compatibility
- [ ] Test sign-in pages, search forms and pages using JavaScript and DOM storage.
- [ ] Click a user-initiated `target=_blank` link and confirm it opens in a new tab.
- [ ] Confirm non-user-initiated pop-ups remain blocked.
- [ ] Open a page with an HTML `<input type="file">` and select one file from the TV document picker.
- [ ] Test multi-file selection on a picker that supports it.
- [ ] Cancel the picker and confirm the web page remains usable.
- [ ] Test a TV without a document picker and confirm JBrowser reports the limitation without crashing.

## Privacy and content blocking
- [ ] Confirm ad/tracker blocking works and can be paused for one site.
- [ ] Test `$badfilter` with `||ads.example^$script` and `||ads.example^$script,badfilter`; the matching rule should be disabled.
- [ ] Test `$badfilter` when options appear in a different order.
- [ ] Test `$all`, `$domain=`, `$important`, `$match-case`, `~image` and first-/third-party rules with controlled test pages.
- [ ] Test same-site requests across subdomains (for example `www.example.co.uk` and `static.example.co.uk`).
- [ ] Verify private mode does not add normal history or retain its private session after exit.

## Downloads
- [ ] Download a file over HTTPS and open it from the Downloads folder.
- [ ] Test an authenticated download that requires cookies and a Referer header.
- [ ] Test a URL with query parameters and a disposition filename.
- [ ] Download a duplicate filename and confirm the numeric suffix.
- [ ] Confirm non-HTTP(S) URLs are rejected.
- [ ] Test cancel, remove, refresh and completion states.

## Playback
- [ ] Toggle video autoplay in Settings → Playback and confirm new/current tabs apply the preference.
- [ ] Test HTML5 video, fullscreen, play/pause, seek and speed controls.
- [ ] Test remote media keys and a low-memory device.
- [ ] Verify DRM, HDR and high-resolution behavior only against services and hardware that officially support them.

## Release readiness
- [ ] Run the GitHub Actions build and install the generated APK.
- [ ] For update testing, use the same persistent signing key for consecutive builds.
- [ ] Test at least one low-end TV box and one Android TV / Google TV device.
