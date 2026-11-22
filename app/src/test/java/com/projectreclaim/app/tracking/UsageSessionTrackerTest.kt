package com.projectreclaim.app.tracking

import com.projectreclaim.app.data.local.UsageSessionEntity
import com.projectreclaim.app.data.repository.UsageSessionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageSessionTrackerTest {
    @Test
    fun `new session initializes last confirmed timestamp at start`() = runBlocking {
        val store = FakeUsageSessionStore()
        val tracker = tracker(store)

        tracker.reconcile(isActive = true, timestampMillis = 200)

        val session = store.openSession(INSTAGRAM, REELS)
        assertEquals(1, store.startCalls)
        assertEquals(200L, session?.startTimeMillis)
        assertEquals(200L, session?.lastConfirmedActiveTimeMillis)
        assertEquals(A3_INSTAGRAM_REELS_DETECTOR_VERSION, session?.detectorVersion)
    }

    @Test
    fun `authoritative active confirmation advances timestamp without duplicate`() = runBlocking {
        val store = FakeUsageSessionStore()
        val tracker = tracker(store)

        tracker.reconcile(isActive = true, timestampMillis = 100)
        tracker.reconcile(isActive = true, timestampMillis = 200)

        assertEquals(1, store.startCalls)
        assertEquals(1, store.openSessions(INSTAGRAM, REELS).size)
        assertEquals(200L, store.openSession(INSTAGRAM, REELS)?.lastConfirmedActiveTimeMillis)
    }

    @Test
    fun `older active confirmation cannot move timestamp backwards`() = runBlocking {
        val store = FakeUsageSessionStore()
        val tracker = tracker(store)

        tracker.reconcile(isActive = true, timestampMillis = 200)
        tracker.reconcile(isActive = true, timestampMillis = 150)

        assertEquals(200L, store.openSession(INSTAGRAM, REELS)?.lastConfirmedActiveTimeMillis)
    }

    @Test
    fun `confirmation cannot mutate a closed row`() = runBlocking {
        val store = FakeUsageSessionStore()
        val tracker = tracker(store)

        tracker.reconcile(isActive = true, timestampMillis = 100)
        val closedId = store.openSession(INSTAGRAM, REELS)!!.id
        tracker.reconcile(isActive = false, timestampMillis = 150)

        assertEquals(false, store.advanceLastConfirmedActiveTime(closedId, 200))
        assertEquals(100L, store.sessions.single().lastConfirmedActiveTimeMillis)
        assertEquals(150L, store.sessions.single().endTimeMillis)
    }

    @Test
    fun `stale recovery closes at last confirmed timestamp not current time`() = runBlocking {
        val store = FakeUsageSessionStore().apply {
            addOpenSession(INSTAGRAM, REELS, 50, 80)
        }

        tracker(store).recoverStaleOpenSessions()

        assertEquals(80L, store.sessions.single().endTimeMillis)
    }

    @Test
    fun `stale recovery closes at latest committed coordinator heartbeat`() = runBlocking {
        val store = FakeUsageSessionStore()
        val scheduler = SinglePendingHeartbeatScheduler()
        var currentTimeMillis = 5_100L
        val liveTracker = tracker(store)
        val liveCoordinator = OrderedTrackingStateCoordinator(
            scope = this,
            reconcile = liveTracker::reconcile,
            heartbeatIntervalMillis = ACTIVE_SESSION_HEARTBEAT_INTERVAL_MILLIS,
            nowMillis = { currentTimeMillis },
            heartbeatScheduler = scheduler::schedule,
        )

        liveCoordinator.observe(isActive = true, timestampMillis = 100L)
        liveCoordinator.awaitIdle()
        scheduler.fire()
        liveCoordinator.awaitIdle()
        currentTimeMillis = 10_100L
        scheduler.fire()
        liveCoordinator.awaitIdle()

        assertEquals(
            10_100L,
            store.openSession(INSTAGRAM, REELS)?.lastConfirmedActiveTimeMillis,
        )

        val recoveredTracker = tracker(store)
        val recoveredCoordinator = OrderedTrackingStateCoordinator(
            scope = this,
            startupAction = recoveredTracker::recoverStaleOpenSessions,
            reconcile = recoveredTracker::reconcile,
        )
        recoveredCoordinator.start()
        recoveredCoordinator.awaitIdle()

        assertEquals(10_100L, store.sessions.single().endTimeMillis)
        assertEquals(emptyList<UsageSessionEntity>(), store.openSessions(INSTAGRAM, REELS))
    }

    @Test
    fun `legacy stale recovery closes at session start`() = runBlocking {
        val store = FakeUsageSessionStore().apply {
            addOpenSession(INSTAGRAM, REELS, 50, null)
        }

        tracker(store).recoverStaleOpenSessions()

        assertEquals(50L, store.sessions.single().endTimeMillis)
    }

    @Test
    fun `stale recovery defensively clamps confirmation to session start`() = runBlocking {
        val store = FakeUsageSessionStore().apply {
            addOpenSession(INSTAGRAM, REELS, 100, 80)
        }

        tracker(store).recoverStaleOpenSessions()

        assertEquals(100L, store.sessions.single().endTimeMillis)
    }

    @Test
    fun `stale recovery closes every matching open row`() = runBlocking {
        val store = FakeUsageSessionStore().apply {
            addOpenSession(INSTAGRAM, REELS, 10, 20)
            addOpenSession(INSTAGRAM, REELS, 30, 40)
            addOpenSession(INSTAGRAM, REELS, 50, null)
        }

        tracker(store).recoverStaleOpenSessions()

        assertEquals(3, store.finishCalls)
        assertEquals(emptyList<UsageSessionEntity>(), store.openSessions(INSTAGRAM, REELS))
        assertEquals(listOf(20L, 40L, 50L), store.sessions.map { it.endTimeMillis })
    }

    @Test
    fun `instagram recovery does not touch youtube or other usage types`() = runBlocking {
        val store = FakeUsageSessionStore().apply {
            addOpenSession(INSTAGRAM, REELS, 10, 20)
            addOpenSession(YOUTUBE, SHORTS, 30, 40)
            addOpenSession(INSTAGRAM, "stories", 50, 60)
        }

        tracker(store).recoverStaleOpenSessions()

        assertEquals(20L, store.sessions[0].endTimeMillis)
        assertNull(store.sessions[1].endTimeMillis)
        assertNull(store.sessions[2].endTimeMillis)
    }

    @Test
    fun `youtube recovery does not touch instagram`() = runBlocking {
        val store = FakeUsageSessionStore().apply {
            addOpenSession(INSTAGRAM, REELS, 10, 20)
            addOpenSession(YOUTUBE, SHORTS, 30, 40)
        }

        youTubeTracker(store).recoverStaleOpenSessions()

        assertNull(store.sessions[0].endTimeMillis)
        assertEquals(40L, store.sessions[1].endTimeMillis)
    }

    @Test
    fun `active after recovery starts new session instead of adopting stale row`() = runBlocking {
        val store = FakeUsageSessionStore().apply {
            addOpenSession(INSTAGRAM, REELS, 50, 80)
        }
        val tracker = tracker(store)

        tracker.recoverStaleOpenSessions()
        tracker.reconcile(isActive = true, timestampMillis = 200)

        assertEquals(2, store.sessions.size)
        assertEquals(80L, store.sessions[0].endTimeMillis)
        assertEquals(200L, store.sessions[1].startTimeMillis)
        assertEquals(200L, store.sessions[1].lastConfirmedActiveTimeMillis)
        assertNull(store.sessions[1].endTimeMillis)
    }

    @Test
    fun `startup recovery closes stale row even without a detector observation`() = runBlocking {
        val store = FakeUsageSessionStore().apply {
            addOpenSession(INSTAGRAM, REELS, 50, 80)
        }
        val tracker = tracker(store)
        val coordinator = OrderedTrackingStateCoordinator(
            scope = this,
            startupAction = tracker::recoverStaleOpenSessions,
            reconcile = tracker::reconcile,
        )

        // A missing accessibility root produces no authoritative observation. Starting the
        // coordinator must nevertheless recover the row inherited from the previous instance.
        coordinator.start()
        coordinator.awaitIdle()

        assertEquals(80L, store.sessions.single().endTimeMillis)
        assertEquals(emptyList<UsageSessionEntity>(), store.openSessions(INSTAGRAM, REELS))
    }

    @Test
    fun `false then true creates one open session`() = runBlocking {
        val store = FakeUsageSessionStore()
        val tracker = tracker(store)

        tracker.reconcile(isActive = false, timestampMillis = 100)
        tracker.reconcile(isActive = true, timestampMillis = 200)

        assertEquals(1, store.startCalls)
        assertEquals(200L, store.openSession(INSTAGRAM, REELS)?.startTimeMillis)
    }

    @Test
    fun `true then false closes existing session`() = runBlocking {
        val store = FakeUsageSessionStore()
        val tracker = tracker(store)

        tracker.reconcile(isActive = true, timestampMillis = 100)
        tracker.reconcile(isActive = false, timestampMillis = 250)

        assertEquals(1, store.finishCalls)
        assertEquals(250L, store.sessions.single().endTimeMillis)
    }

    @Test
    fun `repeated false is a no op after recovery`() = runBlocking {
        val store = FakeUsageSessionStore()
        val tracker = tracker(store)

        tracker.reconcile(isActive = false, timestampMillis = 100)
        tracker.reconcile(isActive = false, timestampMillis = 200)

        assertEquals(0, store.startCalls)
        assertEquals(0, store.finishCalls)
    }

    @Test
    fun `end before start is clamped to start`() = runBlocking {
        val store = FakeUsageSessionStore()
        val tracker = tracker(store)
        tracker.reconcile(isActive = true, timestampMillis = 200)

        tracker.reconcile(isActive = false, timestampMillis = 100)

        assertEquals(200L, store.sessions.single().endTimeMillis)
    }

    @Test
    fun `concurrent true reconciliations create one session and keep newest confirmation`() =
        runBlocking {
            val store = FakeUsageSessionStore()
            val tracker = tracker(store)

            coroutineScope {
                (1L..20L).map { timestamp ->
                    async(Dispatchers.Default) {
                        tracker.reconcile(isActive = true, timestampMillis = timestamp)
                    }
                }.awaitAll()
            }

            assertEquals(1, store.startCalls)
            assertEquals(1, store.openSessions(INSTAGRAM, REELS).size)
            assertEquals(20L, store.openSession(INSTAGRAM, REELS)?.lastConfirmedActiveTimeMillis)
        }

    @Test
    fun `unexpected duplicate opens after recovery fail instead of selecting one`() = runBlocking {
        val store = FakeUsageSessionStore()
        val tracker = tracker(store)
        tracker.reconcile(isActive = false, timestampMillis = 50)
        store.addOpenSession(INSTAGRAM, REELS, 100, 110)
        store.addOpenSession(INSTAGRAM, REELS, 120, 130)

        val failure = runCatching {
            tracker.reconcile(isActive = true, timestampMillis = 200)
        }

        assertEquals(true, failure.isFailure)
        assertEquals(
            true,
            failure.exceptionOrNull()?.message?.contains("found 2 open rows"),
        )
        assertEquals(0, store.startCalls)
        assertEquals(2, store.openSessions(INSTAGRAM, REELS).size)
    }

    @Test
    fun `insert conflict propagates and later observation can reconcile winner`() = runBlocking {
        val store = FakeUsageSessionStore().apply {
            startThrowsRemaining = 1
        }
        val tracker = tracker(store)

        val conflict = runCatching {
            tracker.reconcile(isActive = true, timestampMillis = 100)
        }

        assertEquals(true, conflict.isFailure)
        assertEquals(1, store.startCalls)
        assertEquals(emptyList<UsageSessionEntity>(), store.sessions)

        store.addOpenSession(INSTAGRAM, REELS, 105, 105)
        tracker.reconcile(isActive = true, timestampMillis = 200)

        assertEquals(1, store.startCalls)
        assertEquals(200L, store.openSession(INSTAGRAM, REELS)?.lastConfirmedActiveTimeMillis)
    }

    @Test
    fun `failed stale finish is retried before current state can be reconciled`() = runBlocking {
        val store = FakeUsageSessionStore().apply {
            addOpenSession(INSTAGRAM, REELS, 100, 120)
            finishReturnsFalseWithoutClosing = true
        }
        val tracker = tracker(store)

        val firstFailure = runCatching {
            tracker.reconcile(isActive = true, timestampMillis = 200)
        }
        assertEquals(true, firstFailure.isFailure)
        assertEquals(0, store.startCalls)
        assertEquals(1, store.openSessions(INSTAGRAM, REELS).size)

        store.finishReturnsFalseWithoutClosing = false
        tracker.reconcile(isActive = true, timestampMillis = 300)

        assertEquals(1, store.startCalls)
        assertEquals(2, store.finishCalls)
        assertEquals(1, store.openSessions(INSTAGRAM, REELS).size)
        assertEquals(300L, store.openSession(INSTAGRAM, REELS)?.startTimeMillis)
    }

    @Test
    fun `failed live finish is conservatively recovered before retry`() = runBlocking {
        val store = FakeUsageSessionStore()
        val tracker = tracker(store)
        tracker.reconcile(isActive = true, timestampMillis = 100)
        tracker.reconcile(isActive = true, timestampMillis = 180)
        store.finishReturnsFalseWithoutClosing = true

        val firstFailure = runCatching {
            tracker.reconcile(isActive = false, timestampMillis = 200)
        }
        assertEquals(true, firstFailure.isFailure)
        assertEquals(1, store.openSessions(INSTAGRAM, REELS).size)
        assertNull(store.sessions.single().endTimeMillis)
        assertEquals(180L, store.sessions.single().lastConfirmedActiveTimeMillis)

        store.finishReturnsFalseWithoutClosing = false
        tracker.reconcile(isActive = false, timestampMillis = 250)

        assertEquals(0, store.openSessions(INSTAGRAM, REELS).size)
        assertEquals(180L, store.sessions.single().endTimeMillis)
        assertEquals(1, store.startCalls)
    }

    @Test
    fun `queued true recovers failed live close before starting a new session`() = runBlocking {
        val store = FakeUsageSessionStore()
        val tracker = tracker(store)
        val coordinator = OrderedTrackingStateCoordinator(
            scope = this,
            reconcile = tracker::reconcile,
        )

        coordinator.observe(isActive = true, timestampMillis = 100)
        coordinator.observe(isActive = true, timestampMillis = 180)
        coordinator.awaitIdle()

        val failedFinishStarted = CompletableDeferred<Unit>()
        val failedFinishMayReturn = CompletableDeferred<Unit>()
        store.finishStarted = failedFinishStarted
        store.finishMayProceed = failedFinishMayReturn
        store.finishFalseWithoutClosingRemaining = 1

        coordinator.observe(isActive = false, timestampMillis = 200)
        failedFinishStarted.await()
        coordinator.observe(isActive = true, timestampMillis = 300)
        failedFinishMayReturn.complete(Unit)
        coordinator.awaitIdle()

        assertEquals(2, store.sessions.size)
        assertEquals(100L, store.sessions[0].startTimeMillis)
        assertEquals(180L, store.sessions[0].lastConfirmedActiveTimeMillis)
        assertEquals(180L, store.sessions[0].endTimeMillis)
        assertEquals(300L, store.sessions[1].startTimeMillis)
        assertEquals(300L, store.sessions[1].lastConfirmedActiveTimeMillis)
        assertNull(store.sessions[1].endTimeMillis)
    }

    @Test
    fun `true after live close exception recovers before starting a new session`() = runBlocking {
        val store = FakeUsageSessionStore()
        val tracker = tracker(store)
        tracker.reconcile(isActive = true, timestampMillis = 100)
        tracker.reconcile(isActive = true, timestampMillis = 180)
        store.finishThrowsWithoutClosingRemaining = 1

        val closeFailure = runCatching {
            tracker.reconcile(isActive = false, timestampMillis = 200)
        }
        assertEquals(true, closeFailure.isFailure)
        assertEquals(1, store.openSessions(INSTAGRAM, REELS).size)
        assertNull(store.sessions.single().endTimeMillis)
        assertEquals(180L, store.sessions.single().lastConfirmedActiveTimeMillis)

        tracker.reconcile(isActive = true, timestampMillis = 300)

        assertEquals(2, store.sessions.size)
        assertEquals(180L, store.sessions[0].endTimeMillis)
        assertEquals(300L, store.sessions[1].startTimeMillis)
        assertNull(store.sessions[1].endTimeMillis)
    }

    @Test
    fun `already closed false result remains benign and does not dirty recovery`() = runBlocking {
        val store = FakeUsageSessionStore()
        val tracker = tracker(store)
        tracker.reconcile(isActive = true, timestampMillis = 100)
        store.closeAsAnotherActorThenReturnFalse = true

        tracker.reconcile(isActive = false, timestampMillis = 200)
        assertEquals(0, store.openSessions(INSTAGRAM, REELS).size)
        assertEquals(200L, store.sessions.single().endTimeMillis)
        val openSessionReadsBeforeTrue = store.getOpenSessionsCalls
        tracker.reconcile(isActive = true, timestampMillis = 300)

        assertEquals(openSessionReadsBeforeTrue + 1, store.getOpenSessionsCalls)
        assertEquals(2, store.sessions.size)
        assertEquals(200L, store.sessions[0].endTimeMillis)
        assertEquals(300L, store.sessions[1].startTimeMillis)
    }

    private fun tracker(store: UsageSessionStore) = UsageSessionTracker(
        store = store,
        platform = INSTAGRAM,
        usageType = REELS,
        detectorVersion = A3_INSTAGRAM_REELS_DETECTOR_VERSION,
    )

    private fun youTubeTracker(store: UsageSessionStore) = UsageSessionTracker(
        store = store,
        platform = YOUTUBE,
        usageType = SHORTS,
        detectorVersion = A7B_YOUTUBE_SHORTS_DETECTOR_VERSION,
    )

    private class SinglePendingHeartbeatScheduler {
        private var pendingAction: (() -> Unit)? = null

        fun schedule(@Suppress("UNUSED_PARAMETER") delayMillis: Long, action: () -> Unit): () -> Unit {
            check(pendingAction == null)
            pendingAction = action
            return {
                if (pendingAction === action) {
                    pendingAction = null
                }
            }
        }

        fun fire() {
            val action = checkNotNull(pendingAction)
            pendingAction = null
            action()
        }
    }

    private class FakeUsageSessionStore : UsageSessionStore {
        val sessions = mutableListOf<UsageSessionEntity>()
        var startCalls = 0
        var startThrowsRemaining = 0
        var finishCalls = 0
        var finishReturnsFalseWithoutClosing = false
        var finishFalseWithoutClosingRemaining = 0
        var finishThrowsWithoutClosingRemaining = 0
        var closeAsAnotherActorThenReturnFalse = false
        var finishStarted: CompletableDeferred<Unit>? = null
        var finishMayProceed: CompletableDeferred<Unit>? = null
        var getOpenSessionsCalls = 0
        private var nextId = 1L

        override suspend fun startSession(
            platform: String,
            usageType: String,
            startTimeMillis: Long,
            detectorVersion: String?,
        ): Long {
            startCalls += 1
            if (startThrowsRemaining > 0) {
                startThrowsRemaining -= 1
                error("test insert conflict")
            }
            val session = UsageSessionEntity(
                id = nextId++,
                platform = platform,
                usageType = usageType,
                startTimeMillis = startTimeMillis,
                detectorVersion = detectorVersion,
                lastConfirmedActiveTimeMillis = startTimeMillis,
            )
            sessions += session
            return session.id
        }

        override suspend fun finishSession(sessionId: Long, endTimeMillis: Long): Boolean {
            finishCalls += 1
            finishStarted?.complete(Unit)
            finishMayProceed?.await()
            if (finishThrowsWithoutClosingRemaining > 0) {
                finishThrowsWithoutClosingRemaining -= 1
                error("test finish failure")
            }
            if (finishFalseWithoutClosingRemaining > 0) {
                finishFalseWithoutClosingRemaining -= 1
                return false
            }
            if (finishReturnsFalseWithoutClosing) return false
            if (closeAsAnotherActorThenReturnFalse) {
                closeAsAnotherActorThenReturnFalse = false
                val index = sessions.indexOfFirst {
                    it.id == sessionId && it.endTimeMillis == null
                }
                if (index != -1) {
                    sessions[index] = sessions[index].copy(endTimeMillis = endTimeMillis)
                }
                return false
            }
            val index = sessions.indexOfFirst { it.id == sessionId && it.endTimeMillis == null }
            if (index == -1) return false
            sessions[index] = sessions[index].copy(endTimeMillis = endTimeMillis)
            return true
        }

        override suspend fun advanceLastConfirmedActiveTime(
            sessionId: Long,
            timestampMillis: Long,
        ): Boolean {
            val index = sessions.indexOfFirst { it.id == sessionId && it.endTimeMillis == null }
            if (index == -1) return false
            val current = sessions[index].lastConfirmedActiveTimeMillis
            if (current != null && current >= timestampMillis) return false
            sessions[index] = sessions[index].copy(
                lastConfirmedActiveTimeMillis = timestampMillis,
            )
            return true
        }

        override suspend fun getOpenSessions(
            platform: String,
            usageType: String,
        ): List<UsageSessionEntity> {
            getOpenSessionsCalls += 1
            return openSessions(platform, usageType)
        }

        fun addOpenSession(
            platform: String,
            usageType: String,
            startTimeMillis: Long,
            lastConfirmedActiveTimeMillis: Long?,
        ) {
            sessions += UsageSessionEntity(
                id = nextId++,
                platform = platform,
                usageType = usageType,
                startTimeMillis = startTimeMillis,
                lastConfirmedActiveTimeMillis = lastConfirmedActiveTimeMillis,
            )
        }

        fun openSession(platform: String, usageType: String): UsageSessionEntity? =
            openSessions(platform, usageType).maxByOrNull { it.startTimeMillis }

        fun openSessions(platform: String, usageType: String): List<UsageSessionEntity> =
            sessions.filter {
                it.platform == platform && it.usageType == usageType && it.endTimeMillis == null
            }
    }

    private companion object {
        const val INSTAGRAM = "instagram"
        const val REELS = "reels"
        const val YOUTUBE = "youtube"
        const val SHORTS = "shorts"
    }
}
