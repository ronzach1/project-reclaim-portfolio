package com.projectreclaim.app.tracking

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OrderedTrackingStateCoordinatorTest {
    @Test
    fun `first false is reconciled even when debug state may default to false`() = runBlocking {
        val reconciled = mutableListOf<Boolean>()
        val coordinator = coordinator { isActive, _ -> reconciled += isActive }

        assertNotNull(coordinator.observe(isActive = false, timestampMillis = 1L))
        coordinator.awaitIdle()

        assertEquals(listOf(false), reconciled)
    }

    @Test
    fun `repeated false is suppressed`() = runBlocking {
        val reconciled = mutableListOf<Boolean>()
        val coordinator = coordinator { isActive, _ -> reconciled += isActive }

        coordinator.observe(isActive = false, timestampMillis = 1L)
        assertNull(coordinator.observe(isActive = false, timestampMillis = 2L))
        coordinator.awaitIdle()

        assertEquals(listOf(false), reconciled)
    }

    @Test
    fun `first true is reconciled`() = runBlocking {
        val reconciled = mutableListOf<Boolean>()
        val coordinator = coordinator { isActive, _ -> reconciled += isActive }

        assertNotNull(coordinator.observe(isActive = true, timestampMillis = 1L))
        coordinator.awaitIdle()

        assertEquals(listOf(true), reconciled)
    }

    @Test
    fun `repeated true remains ordered as an active confirmation`() = runBlocking {
        val reconciled = mutableListOf<Boolean>()
        val coordinator = coordinator { isActive, _ -> reconciled += isActive }

        coordinator.observe(isActive = true, timestampMillis = 1L)
        assertNotNull(coordinator.observe(isActive = true, timestampMillis = 2L))
        coordinator.awaitIdle()

        assertEquals(listOf(true, true), reconciled)
    }

    @Test
    fun `first successful true arms heartbeat`() = runBlocking {
        val scheduler = ManualHeartbeatScheduler()
        val coordinator = heartbeatCoordinator(
            scheduler = scheduler,
            nowMillis = { 5_100L },
        ) { _, _ -> }

        coordinator.observe(isActive = true, timestampMillis = 100L)
        coordinator.awaitIdle()

        assertEquals(1, scheduler.pendingCount)
        assertEquals(ACTIVE_SESSION_HEARTBEAT_INTERVAL_MILLIS, scheduler.tasks.single().delayMillis)
    }

    @Test
    fun `heartbeat produces ordered true with current timestamp`() = runBlocking {
        val scheduler = ManualHeartbeatScheduler()
        var currentTimeMillis = 5_100L
        val reconciled = mutableListOf<Pair<Boolean, Long>>()
        val coordinator = heartbeatCoordinator(
            scheduler = scheduler,
            nowMillis = { currentTimeMillis },
        ) { isActive, timestampMillis -> reconciled += isActive to timestampMillis }

        coordinator.observe(isActive = true, timestampMillis = 100L)
        coordinator.awaitIdle()
        scheduler.fireNext()
        coordinator.awaitIdle()

        assertEquals(listOf(true to 100L, true to 5_100L), reconciled)
        assertEquals(1, scheduler.pendingCount)
    }

    @Test
    fun `successful heartbeats continue while active`() = runBlocking {
        val scheduler = ManualHeartbeatScheduler()
        var currentTimeMillis = 5_100L
        val reconciled = mutableListOf<Pair<Boolean, Long>>()
        val coordinator = heartbeatCoordinator(
            scheduler = scheduler,
            nowMillis = { currentTimeMillis },
        ) { isActive, timestampMillis -> reconciled += isActive to timestampMillis }

        coordinator.observe(isActive = true, timestampMillis = 100L)
        coordinator.awaitIdle()
        scheduler.fireNext()
        coordinator.awaitIdle()
        currentTimeMillis = 10_100L
        scheduler.fireNext()
        coordinator.awaitIdle()

        assertEquals(
            listOf(true to 100L, true to 5_100L, true to 10_100L),
            reconciled,
        )
        assertEquals(1, scheduler.pendingCount)
    }

    @Test
    fun `false before heartbeat fires prevents enqueue`() = runBlocking {
        val scheduler = ManualHeartbeatScheduler()
        val reconciled = mutableListOf<Pair<Boolean, Long>>()
        val coordinator = heartbeatCoordinator(
            scheduler = scheduler,
            nowMillis = { 5_100L },
        ) { isActive, timestampMillis -> reconciled += isActive to timestampMillis }

        coordinator.observe(isActive = true, timestampMillis = 100L)
        coordinator.awaitIdle()
        val staleTask = scheduler.tasks.single()

        coordinator.observe(isActive = false, timestampMillis = 200L)
        assertEquals(0, scheduler.pendingCount)
        scheduler.fire(staleTask, evenIfCancelled = true)
        coordinator.awaitIdle()

        assertEquals(listOf(true to 100L, false to 200L), reconciled)
    }

    @Test
    fun `heartbeat enqueued before false remains ordered before false`() = runBlocking {
        val scheduler = ManualHeartbeatScheduler()
        val reconciled = mutableListOf<Pair<Boolean, Long>>()
        val coordinator = heartbeatCoordinator(
            scheduler = scheduler,
            nowMillis = { 5_100L },
        ) { isActive, timestampMillis -> reconciled += isActive to timestampMillis }

        coordinator.observe(isActive = true, timestampMillis = 100L)
        coordinator.awaitIdle()
        scheduler.fireNext()
        coordinator.observe(isActive = false, timestampMillis = 5_200L)
        coordinator.awaitIdle()

        assertEquals(
            listOf(true to 100L, true to 5_100L, false to 5_200L),
            reconciled,
        )
        assertEquals(0, scheduler.pendingCount)
    }

    @Test
    fun `stale heartbeat from prior active generation cannot affect new period`() = runBlocking {
        val scheduler = ManualHeartbeatScheduler()
        var currentTimeMillis = 5_300L
        val reconciled = mutableListOf<Pair<Boolean, Long>>()
        val coordinator = heartbeatCoordinator(
            scheduler = scheduler,
            nowMillis = { currentTimeMillis },
        ) { isActive, timestampMillis -> reconciled += isActive to timestampMillis }

        coordinator.observe(isActive = true, timestampMillis = 100L)
        coordinator.awaitIdle()
        val staleTask = scheduler.tasks.single()
        coordinator.observe(isActive = false, timestampMillis = 200L)
        coordinator.observe(isActive = true, timestampMillis = 300L)
        coordinator.awaitIdle()

        scheduler.fire(staleTask, evenIfCancelled = true)
        coordinator.awaitIdle()
        assertEquals(
            listOf(true to 100L, false to 200L, true to 300L),
            reconciled,
        )

        scheduler.fireNext()
        coordinator.awaitIdle()
        assertEquals(
            listOf(true to 100L, false to 200L, true to 300L, true to 5_300L),
            reconciled,
        )
    }

    @Test
    fun `shutdown prevents future heartbeat`() = runBlocking {
        val scheduler = ManualHeartbeatScheduler()
        val reconciled = mutableListOf<Pair<Boolean, Long>>()
        val coordinator = heartbeatCoordinator(
            scheduler = scheduler,
            nowMillis = { 5_100L },
        ) { isActive, timestampMillis -> reconciled += isActive to timestampMillis }

        coordinator.observe(isActive = true, timestampMillis = 100L)
        coordinator.awaitIdle()
        val staleTask = scheduler.tasks.single()
        coordinator.shutdown(timestampMillis = 200L)
        scheduler.fire(staleTask, evenIfCancelled = true)
        coordinator.awaitIdle()

        assertEquals(listOf(true to 100L, false to 200L), reconciled)
        assertEquals(0, scheduler.pendingCount)
    }

    @Test
    fun `failed real true does not arm heartbeat`() = runBlocking {
        val scheduler = ManualHeartbeatScheduler()
        val coordinator = heartbeatCoordinator(
            scheduler = scheduler,
            nowMillis = { 5_100L },
        ) { _, _ -> error("test failure") }

        coordinator.observe(isActive = true, timestampMillis = 100L)
        coordinator.awaitIdle()

        assertEquals(0, scheduler.pendingCount)
        assertEquals(0, scheduler.tasks.size)
    }

    @Test
    fun `failed heartbeat does not retry itself`() = runBlocking {
        val scheduler = ManualHeartbeatScheduler()
        var attempts = 0
        val coordinator = heartbeatCoordinator(
            scheduler = scheduler,
            nowMillis = { 5_100L },
        ) { _, _ ->
            attempts += 1
            if (attempts == 2) error("heartbeat failure")
        }

        coordinator.observe(isActive = true, timestampMillis = 100L)
        coordinator.awaitIdle()
        scheduler.fireNext()
        coordinator.awaitIdle()

        assertEquals(2, attempts)
        assertEquals(0, scheduler.pendingCount)

        coordinator.observe(isActive = true, timestampMillis = 6_000L)
        coordinator.awaitIdle()
        assertEquals(3, attempts)
        assertEquals(1, scheduler.pendingCount)
    }

    @Test
    fun `repeated real true keeps only one heartbeat loop`() = runBlocking {
        val scheduler = ManualHeartbeatScheduler()
        val reconciled = mutableListOf<Pair<Boolean, Long>>()
        val coordinator = heartbeatCoordinator(
            scheduler = scheduler,
            nowMillis = { 5_200L },
        ) { isActive, timestampMillis -> reconciled += isActive to timestampMillis }

        coordinator.observe(isActive = true, timestampMillis = 100L)
        coordinator.awaitIdle()
        val firstTask = scheduler.tasks.single()
        coordinator.observe(isActive = true, timestampMillis = 200L)
        coordinator.awaitIdle()

        assertEquals(1, scheduler.pendingCount)
        scheduler.fire(firstTask, evenIfCancelled = true)
        coordinator.awaitIdle()
        assertEquals(listOf(true to 100L, true to 200L), reconciled)

        scheduler.fireNext()
        coordinator.awaitIdle()
        assertEquals(listOf(true to 100L, true to 200L, true to 5_200L), reconciled)
        assertEquals(1, scheduler.pendingCount)
    }

    @Test
    fun `startup recovery finishes before first reconciliation`() = runBlocking {
        val recoveryMayFinish = CompletableDeferred<Unit>()
        val operations = mutableListOf<String>()
        val coordinator = OrderedTrackingStateCoordinator(
            scope = this,
            startupAction = {
                operations += "recovery-start"
                recoveryMayFinish.await()
                operations += "recovery-finish"
            },
            reconcile = { isActive, _ -> operations += "reconcile-$isActive" },
        )

        coordinator.observe(isActive = true, timestampMillis = 1L)
        yield()
        assertEquals(listOf("recovery-start"), operations)

        recoveryMayFinish.complete(Unit)
        coordinator.awaitIdle()
        assertEquals(
            listOf("recovery-start", "recovery-finish", "reconcile-true"),
            operations,
        )
    }

    @Test
    fun `startup recovery runs without a detector observation`() = runBlocking {
        val operations = mutableListOf<String>()
        val coordinator = OrderedTrackingStateCoordinator(
            scope = this,
            startupAction = { operations += "recovered" },
            reconcile = { _, _ -> operations += "unexpected-reconcile" },
        )

        coordinator.start()
        coordinator.awaitIdle()

        assertEquals(listOf("recovered"), operations)
    }

    @Test
    fun `true then false stays ordered while true reconciliation suspends`() = runBlocking {
        val trueMayFinish = CompletableDeferred<Unit>()
        val reconciled = mutableListOf<Boolean>()
        val coordinator = coordinator { isActive, _ ->
            reconciled += isActive
            if (isActive) trueMayFinish.await()
        }

        coordinator.observe(isActive = true, timestampMillis = 1L)
        coordinator.observe(isActive = false, timestampMillis = 2L)
        yield()
        assertEquals(listOf(true), reconciled)

        trueMayFinish.complete(Unit)
        coordinator.awaitIdle()
        assertEquals(listOf(true, false), reconciled)
    }

    @Test
    fun `false then true stays ordered while false reconciliation suspends`() = runBlocking {
        val falseMayFinish = CompletableDeferred<Unit>()
        val reconciled = mutableListOf<Boolean>()
        val coordinator = coordinator { isActive, _ ->
            reconciled += isActive
            if (!isActive) falseMayFinish.await()
        }

        coordinator.observe(isActive = false, timestampMillis = 1L)
        coordinator.observe(isActive = true, timestampMillis = 2L)
        yield()
        assertEquals(listOf(false), reconciled)

        falseMayFinish.complete(Unit)
        coordinator.awaitIdle()
        assertEquals(listOf(false, true), reconciled)
    }

    @Test
    fun `failure allows later observation of same state to retry`() = runBlocking {
        var attempts = 0
        val reconciled = mutableListOf<Boolean>()
        val coordinator = coordinator { isActive, _ ->
            attempts += 1
            if (attempts == 1) error("test failure")
            reconciled += isActive
        }

        coordinator.observe(isActive = true, timestampMillis = 1L)
        coordinator.awaitIdle()
        assertNotNull(coordinator.observe(isActive = true, timestampMillis = 2L))
        coordinator.awaitIdle()

        assertEquals(2, attempts)
        assertEquals(listOf(true), reconciled)
    }

    @Test
    fun `older failure does not invalidate a newer matching state`() = runBlocking {
        var firstTrue = true
        val reconciled = mutableListOf<Boolean>()
        val coordinator = coordinator { isActive, _ ->
            if (isActive && firstTrue) {
                firstTrue = false
                error("test failure")
            }
            reconciled += isActive
        }

        coordinator.observe(isActive = true, timestampMillis = 1L)
        coordinator.observe(isActive = false, timestampMillis = 2L)
        coordinator.observe(isActive = true, timestampMillis = 3L)
        coordinator.awaitIdle()

        assertEquals(listOf(false, true), reconciled)
        assertNotNull(coordinator.observe(isActive = true, timestampMillis = 4L))
        coordinator.awaitIdle()
        assertEquals(listOf(false, true, true), reconciled)
    }

    @Test
    fun `shutdown inactive is ordered after pending active work`() = runBlocking {
        val activeMayFinish = CompletableDeferred<Unit>()
        val reconciled = mutableListOf<Boolean>()
        val coordinator = coordinator { isActive, _ ->
            reconciled += isActive
            if (isActive) activeMayFinish.await()
        }

        coordinator.observe(isActive = true, timestampMillis = 1L)
        val shutdownJob = coordinator.shutdown(timestampMillis = 2L)
        yield()
        assertEquals(listOf(true), reconciled)

        activeMayFinish.complete(Unit)
        shutdownJob?.join()
        assertEquals(listOf(true, false), reconciled)
        assertNull(coordinator.observe(isActive = true, timestampMillis = 3L))
    }

    private fun CoroutineScope.coordinator(
        reconcile: suspend (isActive: Boolean, timestampMillis: Long) -> Unit,
    ) = OrderedTrackingStateCoordinator(
        scope = this,
        reconcile = reconcile,
    )

    private fun CoroutineScope.heartbeatCoordinator(
        scheduler: ManualHeartbeatScheduler,
        nowMillis: () -> Long,
        reconcile: suspend (isActive: Boolean, timestampMillis: Long) -> Unit,
    ) = OrderedTrackingStateCoordinator(
        scope = this,
        reconcile = reconcile,
        heartbeatIntervalMillis = ACTIVE_SESSION_HEARTBEAT_INTERVAL_MILLIS,
        nowMillis = nowMillis,
        heartbeatScheduler = scheduler::schedule,
    )

    private class ManualHeartbeatScheduler {
        val tasks = mutableListOf<ScheduledTask>()

        val pendingCount: Int
            get() = tasks.count { !it.cancelled && !it.fired }

        fun schedule(delayMillis: Long, action: () -> Unit): () -> Unit {
            val task = ScheduledTask(delayMillis = delayMillis, action = action)
            tasks += task
            return { task.cancelled = true }
        }

        fun fireNext() {
            fire(tasks.first { !it.cancelled && !it.fired })
        }

        fun fire(task: ScheduledTask, evenIfCancelled: Boolean = false) {
            check(!task.fired)
            check(evenIfCancelled || !task.cancelled)
            task.fired = true
            task.action()
        }

        data class ScheduledTask(
            val delayMillis: Long,
            val action: () -> Unit,
            var cancelled: Boolean = false,
            var fired: Boolean = false,
        )
    }
}
