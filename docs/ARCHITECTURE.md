# Current architecture

Project Reclaim is a native Android prototype built with Kotlin, Compose, Android accessibility APIs, UsageStats, coroutines, and Room.

## Short-form tracking flow

```text
Accessibility service
  -> Instagram Reels and YouTube Shorts detector/classifier policies
  -> platform-specific tracking-observation policies
  -> independent ordered tracking coordinators
  -> platform-scoped durable usage-session trackers
  -> repository and Room
  -> scoped local-day aggregation/read
  -> Compose UI
```

`ShortFormDetectionAccessibilityService` inspects the active accessibility window for privacy-preserving structural signals. `InstagramReelsClassifier` and `YouTubeShortsClassifier` make pure platform-specific decisions, while event policies decide when to reevaluate. Separate observation policies decide whether each detector's evidence is authoritative enough to affect persistence. Instagram Reels and YouTube Shorts each have an independent ordered coordinator and `UsageSessionTracker`, scoped to their own platform/type pair, so their durable transitions cannot suppress or cancel each other.

Instagram transition detection includes a bounded root-readiness retry burst after an eligible initial false or unavailable-root evaluation. Its four immutable deadlines are anchored to the initial failure at +0.4, +1.4, +3.4, and +7.9 seconds. A separate callback reads the active root and runs only the Instagram detector; normal content events neither restart nor extend the burst, and delayed callbacks skip already-expired subsequent opportunities instead of shifting the schedule or running catch-up scans. A true result, a known non-Instagram root, a newer eligible transition generation, lifecycle shutdown, Handler post failure, or exhaustion invalidates the burst. This is bounded transition recovery, not permanent polling. Classifier and persistence-authority semantics are unchanged: unavailable roots remain non-authoritative, while rooted false and true results remain authoritative.

Ordinary content detection retains its 500-millisecond trailing debounce. While Instagram detection is inactive, the first explicit Instagram content event also starts one immutable starvation-watchdog deadline at +1 second. A quiet-gap content evaluation, window transition, or lifecycle boundary invalidates that generation; otherwise the watchdog may authorize one Instagram-only evaluation and then remains consumed for the rest of the continuous content burst. A watchdog failure can start the existing bounded Instagram retry burst only when no retry generation is already active. Repeated content events cannot move or re-arm the watchdog, so this is one-shot starvation recovery rather than periodic polling; YouTube scheduling is unchanged.

Room stores `UsageSessionEntity` rows with platform, usage type, start/end timestamps, detector version, and a nullable last-authoritatively-confirmed-active timestamp. `UsageSessionRepository` exposes small tracking and history boundaries. Schema v3 installs SQLite insert and update triggers that atomically permit at most one open row per platform/type across writers and processes. The v2-to-v3 migration first conservatively closes every inherited open row at its last confirmation, falling back to and never closing before its start. Trigger definitions are behavioral database infrastructure and are therefore tested directly because Room's exported schema JSON does not represent them.

When a new accessibility-service instance connects, each platform coordinator first orders a platform-scoped recovery before detector reconciliation. Recovery closes every inherited open row at its last confirmed timestamp, falling back to its start for a legacy row and never closing before the start. A subsequent authoritative active detection creates a new row for the new writer instance. After recovery, normal reconciliation reads every scoped open row and fails explicitly if the database invariant is unexpectedly violated; it never silently chooses the newest or oldest row. While the accepted state remains active, the coordinator orders a persistence-only heartbeat every five seconds through the same tracker path; the heartbeat does not rescan the accessibility tree. Normal live open sessions still aggregate through the queried range end, while the heartbeat bounds expected crash undercount after initial detection. It cannot credit time before the first authoritative positive detection.

`TodayTrackedUsageReader` queries a platform/type-scoped overlap and `UsageSessionDurationCalculator` clips each session to the current local-day range before summing it. `MainActivity` reads the Instagram/Reels and YouTube/Shorts totals asynchronously when it resumes and presents them in Compose. It combines those tracked durations with available TikTok foreground time for the primary short-form total, while withholding the combined total if any source is unavailable. The data layer exposes eight local days of independently numeric Instagram Reels and YouTube Shorts history from these persisted sessions, and Compose displays this history when Usage Access is available.

## Separate broad usage path

`UsageStatsReader` is a separate path for broad foreground time of supported social apps: Instagram, TikTok, Facebook, and X. It uses Android UsageStats events and does not identify short-form content. `ForegroundUsageCalculator` tracks resumed activity counts separately from paused activities awaiting a later stopped event: paused events close active usage, while stopped events close a matching active lifecycle first and otherwise consume a paused lifecycle. UsageEvents lacks Activity-instance identity, so when a same-class STOPPED cannot be uniquely associated, Project Reclaim favors preventing stale foreground over exact same-class concurrent reconstruction. It requires Usage Access, unlike local Room reads of tracked sessions. The Compose UI presents total TikTok foreground time as a primary MVP short-form approximation when Usage Access is available.

For the previous-seven-days comparison, `FullMeasurementSetupStore` records the first observed time when both Usage Access and the Project Reclaim accessibility service are enabled. The setup calendar day is partial; only later completed local calendar days are eligible. `UsageStatsReader` separately queries Android's daily aggregate UsageStats over the resulting shared half-open range, capped to the most recent seven eligible days and ending at today's local midnight. `PreviousSevenDaysUsageReader` combines aggregate TikTok foreground time with Room-backed Reels and Shorts duration over that same range, while the social-app average sums aggregate Instagram, TikTok, Facebook, and X foreground time. Both totals divide by the actual eligible day count (one through seven); today is excluded. A successful empty aggregate is a genuine zero, while a null or inaccessible aggregate source remains unavailable. Android may expand `queryUsageStats()` begin and end values to whole aggregation intervals; calendar-aligned ranges are suitable for approximate reporting but are not accounting-grade exactness. If Accessibility is later disabled, this alpha does not reconstruct gaps in its historical Reels and Shorts coverage.

TikTok's current-day short-form proxy comes from the available UsageStats lifecycle events. Completed historical TikTok values remain unavailable until durable TikTok history exists, so completed historical combined short-form values are also unavailable. This prevents Android's loss of older retained events from appearing as false zero usage; persisted Instagram Reels and YouTube Shorts values remain independently available for all eight days.

## Time and session invariants

- Today and the previous seven days come from `CalendarDayUsageTime`, using the supplied clock's zone and `atStartOfDay` for DST-safe calendar boundaries; `TodayUsageTime` preserves the existing today-only API.
- Ranges are half-open: `[startMillis, endMillis)`.
- Overlap queries are scoped by `platform` and `usageType`; open sessions overlap through the read range end.
- Aggregation clamps sessions to the requested range and never contributes a negative duration.
- SQLite insert and update triggers enforce at most one open session per platform/type pair, and tracking closes timestamps no earlier than their session start.
- A current writer advances confirmation only for its still-open row and never moves the timestamp backward; an ordered five-second heartbeat keeps that confirmation fresh while its accepted state remains active.
- Startup recovery is ordered ahead of normal persistence for each platform and closes all prior-instance open rows without crediting unknown post-crash time.

## Intentional current limitations

- Today's tracked totals refresh when the activity resumes; they do not tick continuously while open.
- There is no backend, account system, social feature, or cross-device sync.

This document describes current code, not planned architecture.
