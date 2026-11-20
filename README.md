# JBrowser for Android TV

**Current source version: 1.5.0**

JBrowser is a remote-first Android browser built on Android System WebView. It is designed for TV screens, D-pad navigation and optional keyboard input.

## Version 1.5.0 highlights

- Website file-upload support via Android's document picker, including multi-select where requested by a website.
- User-initiated `target=_blank` links can open in a new tab; automatic JavaScript pop-ups remain blocked.
- Saved video-autoplay preference; choose whether playback may begin without a user gesture.
- Improved first-/third-party classification using registrable-domain comparison and a compact list of common multi-label public suffixes.
- `$badfilter` rule cancellation and `$all` resource-type handling in the built-in network filter subset.
- Small LRU cache for cosmetic CSS, reducing repeated selector assembly when revisiting sites.
- Browser-managed downloads now include the current page as a Referer header when available, alongside cookies and the page User-Agent.
- All 1.4 features remain: TV-first tab strip, scrollable toolbar, progress indicator, D-pad and keyboard navigation, site controls, downloads, history, bookmarks, private mode and fullscreen video controls.

## Build a single APK on GitHub

The repository includes `.github/workflows/build-apk.yml`. It builds a single debug-signed APK automatically.

1. Push this project to the `main` or `master` branch of your GitHub repository.
2. Open the repository's **Actions** tab and select **Build JBrowser Android TV APK**.
3. After the run completes, download the `JBrowser-1.5.0-Android-TV` artifact. The artifact ZIP contains one APK.
4. To create a GitHub Release, push a tag named `v1.5.0`; the workflow attaches `JBrowser-1.5.0-Android-TV.apk` to that release.

You do not need Android Studio on your own computer when using GitHub Actions.

### Important signing note

The current workflow makes a debug-signed test APK. GitHub-hosted runners do not retain the automatically generated debug key between separate runs. Therefore, an APK built by a later run might not install as an update over a previous build. For seamless in-place updates, configure a persistent release keystore and its passwords as GitHub Actions secrets, and use that same keystore for every release. Never commit a keystore or its passwords to a public repository.

## Build locally (optional)

Open the project in Android Studio with JDK 17 and Android SDK Platform 36 installed, then run **Build → Build Bundle(s) / APK(s) → Build APK(s)**. The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Compatibility and limitations

- Minimum Android version: Android 6.0 (API 23).
- Compile/target SDK: Android 16 / API 36.
- The browser uses the Android System WebView supplied by the TV or TV box. Update it using the device's supported system/app update path where available.
- File upload depends on the TV's installed document picker/file manager. Some TVs ship without a compatible picker.
- Supported video formats, maximum resolution, HDR and DRM depend on the device's hardware, WebView provider and streaming service. JBrowser cannot guarantee 4K/8K, HDR or protected playback on unsupported devices.
- The blocker implements a useful subset of common network and cosmetic filter syntax. It is not the full uBlock Origin browser-extension runtime; scriptlets, procedural cosmetics, redirects, CSP modifiers, generic URL regex filters and other advanced rules remain unsupported.
- First-/third-party classification uses a compact list of common multi-label public suffixes, not the complete Public Suffix List; unusual country-code domains may be classified imperfectly.
- The browser needs network access to browse and update filter lists.

## Project layout

- `app/src/main/java/com/jbrowser/tv/MainActivity.java` — browser interface, tabs, downloads and TV interactions.
- `app/src/main/java/com/jbrowser/tv/AdBlockEngine.java` — network/cosmetic filter parser and matcher.
- `app/src/main/java/com/jbrowser/tv/BookmarkStore.java` — bookmarks and protected private bookmarks.
- `app/src/main/AndroidManifest.xml` — Android TV launcher and browser declarations.
- `.github/workflows/build-apk.yml` — GitHub Actions APK build/release workflow.

See `docs/TV_TEST_CHECKLIST.md` before publishing a release.
