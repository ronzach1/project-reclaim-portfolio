# Project Reclaim

Project Reclaim is a Kotlin and Jetpack Compose Android prototype for measuring time spent on short-form video and selected social apps. The dashboard shows today’s usage and compares it with up to seven completed days.

## What the app accesses and stores

- **Accessibility** inspects structural signals in Instagram and YouTube windows to detect Reels and Shorts. The app records the detected platform, session type and timestamps in an on-device Room database. It does not save screen contents.
- **Usage Access** reads Android’s foreground usage totals and events for selected social apps. TikTok time includes the whole app. A local preference records when both permissions were first observed enabled, so the dashboard can identify complete measurement days.
- **Sharing** previews a Today or Week summary. If you choose to share it, the app generates a PNG in its private cache and opens the Android Sharesheet with temporary read access to that image. A receiving app can keep its own copy.
- **Backups** are disabled for the app. The backup and device-transfer rules also explicitly exclude its databases and preferences.

The seven-day comparison excludes today and the partial setup day, then divides by the actual number of eligible days. Android may round historical Usage Access queries to its aggregation boundaries. Periods when Accessibility was off leave gaps in Reels and Shorts history.

## Build a debug APK

Open the repository in Android Studio with the Android 37 SDK installed. The Gradle wrapper uses a Java 25 toolchain. On Windows, run `./verify.ps1` from the repository root to run the JVM tests and build the debug APK. From another platform, run `./gradlew test assembleDebug`.

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The debug build uses Android’s standard debug signing and requires no personal credentials. Grant Usage Access and Accessibility through the dashboard prompts to try measurement.

See [Architecture](docs/ARCHITECTURE.md) for the tracking and storage design.
