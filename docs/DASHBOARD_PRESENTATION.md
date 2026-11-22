# Dashboard presentation checks

The dashboard remains a single scrolling Compose screen. `MainActivity` retains its existing data reads, permission decisions, and resume refresh behavior; `Dashboard.kt` renders the supplied states.

## Presentation choices

- A fixed, restrained green palette provides consistent light/dark presentation across devices. System dark mode remains supported.
- Today's short-form duration retains seconds and the existing total/completeness rules. A paused detector is explicitly partial, including when the saved value is zero.
- The chart shows the seven completed days supplied after today's entry, oldest first. Two series with a shared maximum scale represent saved Instagram Reels and YouTube Shorts sessions. It never substitutes zero for unavailable TikTok history or displays a historical combined short-form total.
- True zero has no bar. Date range, scale, named series, exact screen-reader descriptions, and expandable daily values make the chart interpretable without relying on color alone.
- The existing completed-day average is displayed unchanged. No trend, target, comparison, or new average is calculated.
- Whole-app social usage and its breakdown remain accessible lower on the screen. Daily chart values and whole-app details expand in place; there is no navigation.
- Missing setup appears near the top with the existing privacy explanation and settings callbacks. Fully enabled setup becomes a compact footer.
- Values stack beneath labels below 260dp of card content width or above 1.3x font scale. No duration or warning is ellipsized. Buttons retain at least 48dp height.

## Fixtures and rendering

`app/src/debug/java/com/projectreclaim/app/DashboardPreviews.kt` contains populated, empty, partial, unavailable, missing-permission, loading, dark, and 320dp/200% font previews. Fixtures are confined to the debug source set, referenced only by previews and rendering tests, and never supplied to the runtime activity.

`DashboardRenderingTest` is explicitly emulator-only. It checks exceptional-state text, historical source descriptions, and expandable details, and captures PNGs in the emulator app's external files directory, `dashboard-screenshots/`.

Build the APKs with `gradlew.bat assembleDebug assembleDebugAndroidTest`. Install each with `adb -s emulator-5554 install -r <apk>`, then run only the rendering class with:

```powershell
adb -s emulator-5554 shell am instrument -w -e class com.projectreclaim.app.DashboardRenderingTest com.projectreclaim.app.test/androidx.test.runner.AndroidJUnitRunner
```

For the narrow/large-text check, set emulator size to `840x2100` at density `420` (320dp wide), set `font_scale` to `2.0`, and run `DashboardRenderingTest#narrowLargeTextRemainsScrollable`. Reset `wm size` and `font_scale` afterward. Never run these fixture tests on the personal Pixel.

Routine verification remains `.\verify.ps1` (JVM tests and debug APK; no connected instrumentation). Focused JVM coverage includes `CompletedHistoryDaysTest` and the existing presentation/readiness/state tests.

Android test dependencies use Espresso 3.7.0 and AndroidX JUnit 1.3.0. The previous Espresso 3.5.1 failed on the API 37 emulator before rendering (`InputManager.getInstance` reflection); the stable update uses the public system-service API. See [AndroidX Test release notes](https://developer.android.com/jetpack/androidx/releases/test#espresso-3.7.0). These are test-only dependencies.
