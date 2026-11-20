# Download and install JBrowser 1.5.0

## Build the APK with GitHub Actions

1. Upload the contents of this project to the `jjudanss/JBrowser` repository (or push a commit to its `main` branch).
2. Open **Actions** in the GitHub repository.
3. Select **Build JBrowser Android TV APK**, then open the latest successful run.
4. Under **Artifacts**, download `JBrowser-1.5.0-Android-TV`.
5. Extract the downloaded ZIP. It contains one file: `JBrowser-1.5.0-Android-TV.apk`.

## Install on Android TV

1. Copy the APK to a USB drive, or download it directly using a TV browser.
2. Open the APK with the TV's File Manager.
3. When Android asks, allow that file manager to install unknown apps.
4. Return to the APK and choose **Install**.
5. Open **JBrowser** from the TV's Apps screen.

The exact names of the Android security prompts differ by TV brand and Android version.

## Create a Release download link

To attach the APK to a GitHub Release, create and push the tag `v1.5.0` after the source is on the default branch. The workflow creates a prerelease named **JBrowser v1.5.0 for Android TV** with `JBrowser-1.5.0-Android-TV.apk` attached.

## Signing and upgrades

This repository's default workflow creates a debug-signed APK for testing. The generated debug key is not persistent across GitHub runner jobs, so do not assume a new APK will upgrade an older APK in place. Before public distribution, configure a persistent signing keystore in GitHub Actions secrets and use the same key for all versions.
