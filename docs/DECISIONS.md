# Durable decisions

This is a lightweight log of durable decisions, not a live task tracker or a full ADR system.

## Native Android and Compose prototype

**Decision:** Build the prototype as a native Android app using Kotlin and Compose.

**Why:** Android provides the accessibility and UsageStats APIs needed for the initial measurement work.

**Consequence:** Current implementation and validation are Android-specific.

## Local-first Phase A

**Decision:** Keep Phase A local-first.

**Why:** Reliable measurement is the first learning goal, before accounts or social systems.

**Consequence:** There is currently no backend, account, sync, or social feature.

## Durable Room usage sessions

**Decision:** Persist tracked short-form sessions in Room.

**Why:** Today's tracked usage must survive process/UI boundaries and be read independently of the detector.

**Consequence:** Session persistence has a small DAO/repository boundary and needs data-integrity care.

## Separate detector, tracker, storage, and UI responsibilities

**Decision:** Keep detection/classification, ordered tracking, persistence, aggregation, and UI as distinct responsibilities.

**Why:** The risky accessibility/lifecycle behavior can be tested and changed without coupling it to display code.

**Consequence:** Small boundaries are preferred over broad application architecture layers.

## Short-form measurement is separate from broad UsageStats

**Decision:** Treat identified short-form sessions separately from broad Android app foreground time.

**Why:** UsageStats can measure app time but cannot reliably distinguish Reels from other Instagram activity.

**Consequence:** Tracked Reels duration is local Room data and does not depend on Usage Access.

## TikTok foreground time as MVP short-form usage

**Decision:** Intentionally treat total TikTok foreground time as short-form consumption for the MVP.

**Why:** TikTok is a short-form-focused initial target, and the existing UsageStats path can measure its foreground time without a new detector.

**Consequence:** This is an approximation; revisit it only if user testing shows meaningful non-feed TikTok usage.

## Half-open local time ranges

**Decision:** Use `[start, end)` semantics for time ranges and overlap queries.

**Why:** This gives exact boundary behavior and avoids double-counting adjacent sessions.

**Consequence:** Aggregation clips sessions, including open sessions, to the queried range.

## Conservative recovery of open tracked sessions

**Decision:** Unknown time after a tracking-process failure is not credited as usage. A new accessibility-service instance closes every prior-instance open row at its last authoritative active confirmation, or at its start when migrating a legacy row without a confirmation.

**Why:** An orphaned open Room row otherwise aggregates through every later query end and can create unbounded phantom Reels or Shorts usage.

**Consequence:** The Room schema keeps a nullable last-confirmed timestamp, startup recovery is ordered before normal detector persistence per platform, and a current active detection starts a new session after recovery. While an authoritative session remains active, its coordinator persists liveness every five seconds without rescanning the accessibility tree, bounding expected crash undercount after detection while preserving the conservative recovery boundary. The heartbeat does not solve time before the first authoritative positive detection. Healthy live sessions remain open and continue to aggregate through now.

## Database-enforced single open session

**Decision:** Enforce at most one open usage session per platform and usage type with SQLite insert and update triggers.

**Why:** In-memory tracker ordering cannot protect against another writer or process, and selecting one row from duplicate opens would hide persistent corruption.

**Consequence:** The v2-to-v3 migration conservatively closes every inherited open row at its last confirmation or start fallback before installing the triggers. Fresh databases install the same trigger SQL at creation. Normal reconciliation reads all scoped opens and fails explicitly if more than one is ever observed. Room's exported schema JSON does not represent triggers, so migration and fresh-database tests verify their behavior directly.

## Product foundation over enterprise architecture

**Decision:** Build small real-product foundations rather than enterprise infrastructure.

**Why:** The prototype needs validated behavior and learning before scale-oriented complexity.

**Consequence:** Do not add layers, services, or platform support without a concrete product need.

## Completed-day seven-day averages

**Decision:** Compare today with averages calculated from up to seven completed local calendar days after full measurement setup, excluding the partial setup day and today. Historical social-app and TikTok totals use Android's aggregate UsageStats, while Reels and Shorts use persisted tracked sessions over the same range.

**Why:** Including an incomplete current day distorts the comparison, and aggregate UsageStats are more appropriate for a seven-day total than reconstructing older raw lifecycle events.

**Consequence:** A SharedPreferences timestamp records the first observed point where Usage Access and Accessibility are both enabled. Before seven eligible days, both totals divide by the actual day count; after that they divide by seven. Short-form remains Reels plus Shorts plus whole-app TikTok, social apps remains Instagram plus TikTok plus Facebook, and no daily-summary persistence is added. Android aggregate UsageStats are calendar-aligned but not accounting-grade exact because Android may expand query boundaries to aggregation intervals; later Accessibility gaps are not reconstructed in this alpha.
