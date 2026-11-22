package com.projectreclaim.app.detection

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.projectreclaim.app.BuildConfig
import com.projectreclaim.app.data.local.ProjectReclaimDatabase
import com.projectreclaim.app.data.repository.UsageSessionRepository
import com.projectreclaim.app.tracking.A3_INSTAGRAM_REELS_DETECTOR_VERSION
import com.projectreclaim.app.tracking.A7B_YOUTUBE_SHORTS_DETECTOR_VERSION
import com.projectreclaim.app.tracking.ACTIVE_SESSION_HEARTBEAT_INTERVAL_MILLIS
import com.projectreclaim.app.tracking.OrderedTrackingStateCoordinator
import com.projectreclaim.app.tracking.ReelsTrackingEvidence
import com.projectreclaim.app.tracking.ReelsTrackingObservationPolicy
import com.projectreclaim.app.tracking.UsageSessionTracker
import com.projectreclaim.app.tracking.YouTubeShortsTrackingEvidence
import com.projectreclaim.app.tracking.YouTubeShortsTrackingObservationPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class ShortFormDetectionAccessibilityService : AccessibilityService() {
    private val instagramReelsDetector = InstagramReelsDetector()
    private val youTubeShortsDetector = YouTubeShortsDetector()
    private val instagramReelsRetryController = InstagramReelsRetryController()
    private val instagramContentWatchdogController =
        InstagramContentStarvationWatchdogController()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val instagramTrackingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val youTubeTrackingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val instagramUsageSessionTracker: UsageSessionTracker by lazy(LazyThreadSafetyMode.NONE) {
        val database = ProjectReclaimDatabase.getInstance(applicationContext)
        UsageSessionTracker(
            store = UsageSessionRepository(database.usageSessionDao()),
            platform = INSTAGRAM_PLATFORM,
            usageType = REELS_USAGE_TYPE,
            detectorVersion = A3_INSTAGRAM_REELS_DETECTOR_VERSION,
        )
    }
    private val youTubeUsageSessionTracker: UsageSessionTracker by lazy(LazyThreadSafetyMode.NONE) {
        val database = ProjectReclaimDatabase.getInstance(applicationContext)
        UsageSessionTracker(
            store = UsageSessionRepository(database.usageSessionDao()),
            platform = YOUTUBE_PLATFORM,
            usageType = SHORTS_USAGE_TYPE,
            detectorVersion = A7B_YOUTUBE_SHORTS_DETECTOR_VERSION,
        )
    }
    private val instagramTrackingCoordinator: OrderedTrackingStateCoordinator by lazy(
        LazyThreadSafetyMode.NONE,
    ) {
        OrderedTrackingStateCoordinator(
            scope = instagramTrackingScope,
            reconcile = instagramUsageSessionTracker::reconcile,
            startupAction = instagramUsageSessionTracker::recoverStaleOpenSessions,
            onReconciled = { isActive ->
                if (BuildConfig.DEBUG) Log.d(LOG_TAG, "Instagram Reels tracking reconciled state=$isActive")
            },
            onFailure = { _, error ->
                Log.e(
                    LOG_TAG,
                    "Instagram Reels tracking reconciliation failed " +
                        "(${error.javaClass.simpleName})",
                )
            },
            onStartupFailure = { error ->
                Log.e(
                    LOG_TAG,
                    "Instagram Reels stale-session recovery failed " +
                        "(${error.javaClass.simpleName})",
                )
            },
            heartbeatIntervalMillis = ACTIVE_SESSION_HEARTBEAT_INTERVAL_MILLIS,
        )
    }
    private val youTubeTrackingCoordinator: OrderedTrackingStateCoordinator by lazy(
        LazyThreadSafetyMode.NONE,
    ) {
        OrderedTrackingStateCoordinator(
            scope = youTubeTrackingScope,
            reconcile = youTubeUsageSessionTracker::reconcile,
            startupAction = youTubeUsageSessionTracker::recoverStaleOpenSessions,
            onReconciled = { isActive ->
                if (BuildConfig.DEBUG) Log.d(LOG_TAG, "YouTube Shorts tracking reconciled state=$isActive")
            },
            onFailure = { _, error ->
                Log.e(
                    LOG_TAG,
                    "YouTube Shorts tracking reconciliation failed " +
                        "(${error.javaClass.simpleName})",
                )
            },
            onStartupFailure = { error ->
                Log.e(
                    LOG_TAG,
                    "YouTube Shorts stale-session recovery failed " +
                        "(${error.javaClass.simpleName})",
                )
            },
            heartbeatIntervalMillis = ACTIVE_SESSION_HEARTBEAT_INTERVAL_MILLIS,
        )
    }
    private val reevaluateRunnable = Runnable {
        val reevaluationContext = pendingReevaluationContext
        pendingReevaluationContext = null
        pendingReevaluation = null
        if (reevaluationContext != null) {
            reevaluationContext.instagramContentBurstGeneration?.let { generation ->
                if (instagramContentWatchdogController.onTrailingContentEvaluationStarted(
                        generation,
                    )
                ) {
                    removePendingInstagramContentWatchdogCallback(generation)
                }
            }
            reevaluateActiveWindow(reevaluationContext)
        }
    }
    private var lastLoggedSignals: InstagramReelsSignals? = null
    private var pendingReevaluationContext: ReevaluationContext? = null
    private var pendingReevaluation: PendingReevaluation? = null
    private var pendingInstagramRetry: PendingInstagramRetry? = null
    private var pendingInstagramContentWatchdog: PendingInstagramContentWatchdog? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        cancelPendingReevaluation()
        cancelInstagramContentWatchdogForLifecycle()
        cancelInstagramRetries()
        publishInstagramDebugState(false)
        publishYouTubeDebugState(false)
        instagramTrackingCoordinator.start()
        youTubeTrackingCoordinator.start()
        scheduleFastReevaluation(
            ReevaluationContext(
                packageNameHint = null,
                instagramOrigin = InstagramReelsEvaluationOrigin.SERVICE_CONNECTED,
                hasInstagramTransitionHint = false,
            ),
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventPackageName = event.packageName?.toString()
        val eventType = event.eventType.toPolicyEventType()
        val reelsAction = ReelsEvaluationPolicy.actionFor(eventType, eventPackageName)
        val youTubeAction = YouTubeShortsEvaluationPolicy.actionFor(eventType, eventPackageName)

        if (eventType.isWindowTransition()) {
            resetInstagramContentWatchdogForWindowTransition()
            cancelInstagramRetries()
        }
        val instagramContentDecision =
            if (isInstagramContentStarvationWatchdogEvent(eventType, eventPackageName)) {
                instagramContentWatchdogController.onInstagramContentEvent(
                    nowElapsedMillis = SystemClock.elapsedRealtime(),
                    detectorActive = ReelsDetectionDebugState.isInstagramReelsActive,
                )
            } else {
                null
            }
        instagramContentDecision?.newWatchdogSchedule?.let(
            ::postInstagramContentWatchdog,
        )
        if (reelsAction == ReelsEvaluationAction.CLEAR_IMMEDIATELY) {
            cancelInstagramRetries()
            lastLoggedSignals = null
            publishInstagramDetectorState(
                detectorResult = false,
                evidence = ReelsTrackingEvidence.KNOWN_NON_INSTAGRAM,
            )
        }
        if (youTubeAction == YouTubeShortsEvaluationAction.CLEAR_IMMEDIATELY) {
            publishYouTubeDebugState(false)
            publishYouTubePersistenceState(
                detectorResult = false,
                evidence = YouTubeShortsTrackingEvidence.KNOWN_NON_YOUTUBE,
            )
        }

        when {
            reelsAction == ReelsEvaluationAction.FAST_REEVALUATE ||
                youTubeAction == YouTubeShortsEvaluationAction.FAST_REEVALUATE ->
                scheduleFastReevaluation(eventPackageName, eventType)

            reelsAction == ReelsEvaluationAction.DEBOUNCED_REEVALUATE ||
                youTubeAction == YouTubeShortsEvaluationAction.DEBOUNCED_REEVALUATE ->
                scheduleDebouncedReevaluation(
                    packageNameHint = eventPackageName,
                    instagramContentBurstGeneration = instagramContentDecision?.generation,
                )

            reelsAction == ReelsEvaluationAction.CLEAR_IMMEDIATELY ||
                youTubeAction == YouTubeShortsEvaluationAction.CLEAR_IMMEDIATELY ->
                cancelPendingReevaluation()
        }
    }

    private fun scheduleFastReevaluation(
        packageNameHint: String?,
        eventType: ReelsAccessibilityEventType,
    ) {
        scheduleFastReevaluation(
            ReevaluationContext(
                packageNameHint = packageNameHint,
                instagramOrigin = when (eventType) {
                    ReelsAccessibilityEventType.WINDOW_STATE_CHANGED ->
                        InstagramReelsEvaluationOrigin.WINDOW_STATE_CHANGED
                    ReelsAccessibilityEventType.WINDOWS_CHANGED ->
                        InstagramReelsEvaluationOrigin.WINDOWS_CHANGED
                    else -> InstagramReelsEvaluationOrigin.OTHER
                },
                hasInstagramTransitionHint =
                    packageNameHint == InstagramReelsClassifier.INSTAGRAM_PACKAGE,
            ),
        )
    }

    private fun scheduleFastReevaluation(context: ReevaluationContext) {
        pendingReevaluationContext = context
        if (pendingReevaluation == PendingReevaluation.FAST) return
        replacePendingReevaluation(PendingReevaluation.FAST, TRANSITION_DELAY_MILLIS)
    }

    private fun scheduleDebouncedReevaluation(
        packageNameHint: String?,
        instagramContentBurstGeneration: Long?,
    ) {
        if (pendingReevaluation == PendingReevaluation.FAST) {
            pendingReevaluationContext = pendingReevaluationContext?.copy(
                packageNameHint = packageNameHint,
            )
            return
        }
        pendingReevaluationContext = ReevaluationContext(
            packageNameHint = packageNameHint,
            instagramOrigin = InstagramReelsEvaluationOrigin.CONTENT_CHANGED,
            hasInstagramTransitionHint = false,
            instagramContentBurstGeneration = instagramContentBurstGeneration,
        )
        replacePendingReevaluation(PendingReevaluation.CONTENT, CONTENT_DEBOUNCE_MILLIS)
    }

    private fun replacePendingReevaluation(
        reevaluation: PendingReevaluation,
        delayMillis: Long,
    ) {
        mainHandler.removeCallbacks(reevaluateRunnable)
        pendingReevaluation = reevaluation
        if (!mainHandler.postDelayed(reevaluateRunnable, delayMillis)) {
            pendingReevaluationContext = null
            pendingReevaluation = null
        }
    }

    private fun reevaluateActiveWindow(context: ReevaluationContext) {
        @Suppress("DEPRECATION")
        val root = rootInActiveWindow
        try {
            val accessibilityRootPackageName = root?.packageName?.toString()
            val detectionPackageName = accessibilityRootPackageName ?: context.packageNameHint
            val startedAtNanos = SystemClock.elapsedRealtimeNanos()
            val instagramDetection = instagramReelsDetector.detect(detectionPackageName, root)
            val youTubeDetection = youTubeShortsDetector.detect(detectionPackageName, root)
            val durationMillis =
                (SystemClock.elapsedRealtimeNanos() - startedAtNanos) / NANOS_PER_MILLISECOND
            context.instagramContentBurstGeneration?.let { generation ->
                instagramContentWatchdogController.onTrailingContentEvaluationFinished(
                    generation = generation,
                    detectorActive = instagramDetection.isActive,
                )
            }
            logEvaluation(instagramDetection, youTubeDetection, durationMillis)
            if (detectionPackageName == InstagramReelsClassifier.INSTAGRAM_PACKAGE) {
                logSignalsIfChanged(instagramDetection)
            } else {
                lastLoggedSignals = null
            }
            publishInstagramDetectorState(
                detectorResult = instagramDetection.isActive,
                evidence = if (root == null) {
                    ReelsTrackingEvidence.UNAVAILABLE_ROOT
                } else {
                    ReelsTrackingEvidence.ACCESSIBILITY_ROOT
                },
            )
            updateInstagramRetriesAfterNormalEvaluation(
                context = context,
                accessibilityRootPackageName = accessibilityRootPackageName,
                rootAvailable = root != null,
                detectorResult = instagramDetection.isActive,
            )
            if (root != null) {
                publishYouTubeDebugState(youTubeDetection.isActive)
            }
            publishYouTubePersistenceState(
                detectorResult = youTubeDetection.isActive,
                evidence = when {
                    root == null || accessibilityRootPackageName == null ->
                        YouTubeShortsTrackingEvidence.UNAVAILABLE_ROOT
                    accessibilityRootPackageName == YouTubeShortsClassifier.YOUTUBE_PACKAGE ->
                        YouTubeShortsTrackingEvidence.YOUTUBE_ACCESSIBILITY_ROOT
                    else -> YouTubeShortsTrackingEvidence.KNOWN_NON_YOUTUBE
                },
            )
        } finally {
            @Suppress("DEPRECATION")
            root?.recycle()
        }
    }

    override fun onInterrupt() {
        cancelPendingReevaluation()
        cancelInstagramContentWatchdogForLifecycle()
        cancelInstagramRetries()
        publishInstagramDebugState(false)
        publishYouTubeDebugState(false)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        cancelPendingReevaluation()
        cancelInstagramContentWatchdogForLifecycle()
        cancelInstagramRetries()
        publishInstagramObservation(false)
        publishYouTubeObservation(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cancelPendingReevaluation()
        cancelInstagramContentWatchdogForLifecycle()
        cancelInstagramRetries()
        publishInstagramDebugState(false)
        publishYouTubeDebugState(false)
        val shutdownTimestampMillis = System.currentTimeMillis()
        shutDownTracking(
            coordinator = instagramTrackingCoordinator,
            scope = instagramTrackingScope,
            timestampMillis = shutdownTimestampMillis,
        )
        shutDownTracking(
            coordinator = youTubeTrackingCoordinator,
            scope = youTubeTrackingScope,
            timestampMillis = shutdownTimestampMillis,
        )
        super.onDestroy()
    }

    private fun cancelPendingReevaluation() {
        mainHandler.removeCallbacks(reevaluateRunnable)
        pendingReevaluationContext = null
        pendingReevaluation = null
    }

    private fun postInstagramContentWatchdog(
        schedule: InstagramContentStarvationWatchdogSchedule,
    ) {
        removePendingInstagramContentWatchdogCallback()
        lateinit var runnable: Runnable
        runnable = Runnable {
            if (pendingInstagramContentWatchdog?.runnable === runnable) {
                pendingInstagramContentWatchdog = null
            }
            executeInstagramContentWatchdog(schedule)
        }
        pendingInstagramContentWatchdog = PendingInstagramContentWatchdog(schedule, runnable)
        val delayMillis =
            (schedule.deadlineElapsedMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        if (!mainHandler.postDelayed(runnable, delayMillis)) {
            if (pendingInstagramContentWatchdog?.runnable === runnable) {
                pendingInstagramContentWatchdog = null
            }
            instagramContentWatchdogController.onWatchdogPostFailure(schedule.generation)
        }
    }

    private fun executeInstagramContentWatchdog(
        schedule: InstagramContentStarvationWatchdogSchedule,
    ) {
        if (!instagramContentWatchdogController.beginWatchdog(
                generation = schedule.generation,
                deadlineElapsedMillis = schedule.deadlineElapsedMillis,
                nowElapsedMillis = SystemClock.elapsedRealtime(),
            )
        ) {
            return
        }

        @Suppress("DEPRECATION")
        val root = rootInActiveWindow
        try {
            val accessibilityRootPackageName = root?.packageName?.toString()
            val startedAtNanos = SystemClock.elapsedRealtimeNanos()
            val detection = instagramReelsDetector.detect(accessibilityRootPackageName, root)
            val durationMillis =
                (SystemClock.elapsedRealtimeNanos() - startedAtNanos) / NANOS_PER_MILLISECOND
            logInstagramContentWatchdogEvaluation(detection, durationMillis)
            if (accessibilityRootPackageName == InstagramReelsClassifier.INSTAGRAM_PACKAGE) {
                logSignalsIfChanged(detection)
            } else {
                lastLoggedSignals = null
            }
            val evidence = when {
                root == null -> ReelsTrackingEvidence.UNAVAILABLE_ROOT
                accessibilityRootPackageName != InstagramReelsClassifier.INSTAGRAM_PACKAGE &&
                    accessibilityRootPackageName != null ->
                    ReelsTrackingEvidence.KNOWN_NON_INSTAGRAM
                else -> ReelsTrackingEvidence.ACCESSIBILITY_ROOT
            }
            publishInstagramDetectorState(
                detectorResult = detection.isActive,
                evidence = evidence,
            )
            updateInstagramRetriesAfterContentWatchdogEvaluation(
                accessibilityRootPackageName = accessibilityRootPackageName,
                rootAvailable = root != null,
                detectorResult = detection.isActive,
            )
        } catch (error: RuntimeException) {
            logDetectionError("Instagram content watchdog evaluation failed", error)
        } finally {
            @Suppress("DEPRECATION")
            root?.recycle()
        }
    }

    private fun updateInstagramRetriesAfterContentWatchdogEvaluation(
        accessibilityRootPackageName: String?,
        rootAvailable: Boolean,
        detectorResult: Boolean,
    ) {
        val schedule = instagramReelsRetryController.onContentStarvationWatchdogEvaluation(
            hasInstagramPackageHint = true,
            result = instagramRetryResult(
                accessibilityRootPackageName = accessibilityRootPackageName,
                rootAvailable = rootAvailable,
                detectorResult = detectorResult,
            ),
            nowElapsedMillis = SystemClock.elapsedRealtime(),
        )
        if (detectorResult ||
            rootAvailable &&
            accessibilityRootPackageName != null &&
            accessibilityRootPackageName != InstagramReelsClassifier.INSTAGRAM_PACKAGE
        ) {
            removePendingInstagramRetryCallback()
        } else if (schedule != null) {
            replacePendingInstagramRetry(schedule)
        }
    }

    private fun consumePendingInstagramContentWatchdog() {
        val pending = pendingInstagramContentWatchdog ?: return
        if (instagramContentWatchdogController.consume(pending.schedule.generation)) {
            removePendingInstagramContentWatchdogCallback(pending.schedule.generation)
        }
    }

    private fun resetInstagramContentWatchdogForWindowTransition() {
        removePendingInstagramContentWatchdogCallback()
        instagramContentWatchdogController.onWindowTransition()
    }

    private fun cancelInstagramContentWatchdogForLifecycle() {
        removePendingInstagramContentWatchdogCallback()
        instagramContentWatchdogController.onLifecycleCancel()
    }

    private fun removePendingInstagramContentWatchdogCallback(generation: Long? = null) {
        val pending = pendingInstagramContentWatchdog ?: return
        if (generation != null && pending.schedule.generation != generation) return
        mainHandler.removeCallbacks(pending.runnable)
        pendingInstagramContentWatchdog = null
    }

    private fun updateInstagramRetriesAfterNormalEvaluation(
        context: ReevaluationContext,
        accessibilityRootPackageName: String?,
        rootAvailable: Boolean,
        detectorResult: Boolean,
    ) {
        val schedule = instagramReelsRetryController.onNormalEvaluation(
            origin = context.instagramOrigin,
            hasInstagramPackageHint = context.hasInstagramTransitionHint,
            result = instagramRetryResult(
                accessibilityRootPackageName = accessibilityRootPackageName,
                rootAvailable = rootAvailable,
                detectorResult = detectorResult,
            ),
            nowElapsedMillis = SystemClock.elapsedRealtime(),
        )
        if (detectorResult ||
            rootAvailable &&
            accessibilityRootPackageName != null &&
            accessibilityRootPackageName != InstagramReelsClassifier.INSTAGRAM_PACKAGE
        ) {
            removePendingInstagramRetryCallback()
        } else if (schedule != null) {
            replacePendingInstagramRetry(schedule)
        }
    }

    private fun replacePendingInstagramRetry(schedule: InstagramReelsRetrySchedule) {
        pendingInstagramRetry?.let { mainHandler.removeCallbacks(it.runnable) }
        lateinit var runnable: Runnable
        runnable = Runnable {
            if (pendingInstagramRetry?.runnable === runnable) pendingInstagramRetry = null
            executeInstagramRetry(schedule, runnable)
        }
        pendingInstagramRetry = PendingInstagramRetry(schedule, runnable)
        val delayMillis =
            (schedule.deadlineElapsedMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        if (!mainHandler.postDelayed(runnable, delayMillis)) {
            if (pendingInstagramRetry?.runnable === runnable) pendingInstagramRetry = null
            instagramReelsRetryController.abandon(schedule.generation)
        }
    }

    private fun executeInstagramRetry(
        schedule: InstagramReelsRetrySchedule,
        runnable: Runnable,
    ) {
        if (!instagramReelsRetryController.beginRetry(
                retryGeneration = schedule.generation,
                deadlineElapsedMillis = schedule.deadlineElapsedMillis,
                nowElapsedMillis = SystemClock.elapsedRealtime(),
            )
        ) {
            return
        }

        @Suppress("DEPRECATION")
        val root = rootInActiveWindow
        try {
            val accessibilityRootPackageName = root?.packageName?.toString()
            val startedAtNanos = SystemClock.elapsedRealtimeNanos()
            val detection = instagramReelsDetector.detect(accessibilityRootPackageName, root)
            val durationMillis =
                (SystemClock.elapsedRealtimeNanos() - startedAtNanos) / NANOS_PER_MILLISECOND
            logInstagramRetryEvaluation(detection, durationMillis)
            if (accessibilityRootPackageName == InstagramReelsClassifier.INSTAGRAM_PACKAGE) {
                logSignalsIfChanged(detection)
            } else {
                lastLoggedSignals = null
            }
            publishInstagramDetectorState(
                detectorResult = detection.isActive,
                evidence = when {
                    root == null -> ReelsTrackingEvidence.UNAVAILABLE_ROOT
                    accessibilityRootPackageName != InstagramReelsClassifier.INSTAGRAM_PACKAGE &&
                        accessibilityRootPackageName != null ->
                        ReelsTrackingEvidence.KNOWN_NON_INSTAGRAM
                    else -> ReelsTrackingEvidence.ACCESSIBILITY_ROOT
                },
            )
            val nextSchedule = instagramReelsRetryController.onRetryEvaluation(
                retryGeneration = schedule.generation,
                result = instagramRetryResult(
                    accessibilityRootPackageName = accessibilityRootPackageName,
                    rootAvailable = root != null,
                    detectorResult = detection.isActive,
                ),
                nowElapsedMillis = SystemClock.elapsedRealtime(),
            )
            if (nextSchedule != null) replacePendingInstagramRetry(nextSchedule)
        } catch (error: RuntimeException) {
            instagramReelsRetryController.abandon(schedule.generation)
            logDetectionError("Instagram retry evaluation failed", error)
        } finally {
            if (pendingInstagramRetry?.runnable === runnable) pendingInstagramRetry = null
            @Suppress("DEPRECATION")
            root?.recycle()
        }
    }

    private fun cancelInstagramRetries() {
        removePendingInstagramRetryCallback()
        instagramReelsRetryController.cancel()
    }

    private fun removePendingInstagramRetryCallback() {
        pendingInstagramRetry?.let { mainHandler.removeCallbacks(it.runnable) }
        pendingInstagramRetry = null
    }

    private fun instagramRetryResult(
        accessibilityRootPackageName: String?,
        rootAvailable: Boolean,
        detectorResult: Boolean,
    ) = InstagramReelsEvaluationResult(
        rootEvidence = when {
            !rootAvailable -> InstagramReelsRootEvidence.UNAVAILABLE
            accessibilityRootPackageName == InstagramReelsClassifier.INSTAGRAM_PACKAGE ->
                InstagramReelsRootEvidence.INSTAGRAM
            accessibilityRootPackageName != null ->
                InstagramReelsRootEvidence.KNOWN_NON_INSTAGRAM
            else -> InstagramReelsRootEvidence.UNKNOWN_PACKAGE
        },
        detectorResult = detectorResult,
    )

    private fun publishInstagramObservation(isActive: Boolean) {
        publishInstagramDebugState(isActive)
        observeInstagramTrackingState(isActive)
    }

    private fun publishInstagramDetectorState(
        detectorResult: Boolean,
        evidence: ReelsTrackingEvidence,
    ) {
        val wasActive = ReelsDetectionDebugState.isInstagramReelsActive
        if (detectorResult || evidence == ReelsTrackingEvidence.KNOWN_NON_INSTAGRAM) {
            consumePendingInstagramContentWatchdog()
        }
        publishInstagramDebugState(detectorResult)
        val persistenceState =
            ReelsTrackingObservationPolicy.persistenceState(evidence, detectorResult)
        if (wasActive && persistenceState == false) {
            removePendingInstagramContentWatchdogCallback()
            instagramContentWatchdogController.onAuthoritativeInactiveTransition()
        }
        persistenceState?.let(::observeInstagramTrackingState)
    }

    private fun publishYouTubeObservation(isActive: Boolean) {
        publishYouTubeDebugState(isActive)
        observeYouTubeTrackingState(isActive)
    }

    private fun publishYouTubePersistenceState(
        detectorResult: Boolean,
        evidence: YouTubeShortsTrackingEvidence,
    ) {
        YouTubeShortsTrackingObservationPolicy.persistenceState(evidence, detectorResult)
            ?.let(::observeYouTubeTrackingState)
    }

    private fun observeInstagramTrackingState(isActive: Boolean) {
        instagramTrackingCoordinator.observe(isActive, System.currentTimeMillis())
    }

    private fun observeYouTubeTrackingState(isActive: Boolean) {
        youTubeTrackingCoordinator.observe(isActive, System.currentTimeMillis())
    }

    private fun publishInstagramDebugState(isActive: Boolean) {
        if (ReelsDetectionDebugState.isInstagramReelsActive == isActive) return
        ReelsDetectionDebugState.update(isActive)
        // Intentionally logs only the boolean transition, never node text or user content.
        if (BuildConfig.DEBUG) Log.d(LOG_TAG, "INSTAGRAM_REELS_ACTIVE=$isActive")
    }

    private fun publishYouTubeDebugState(isActive: Boolean) {
        if (YouTubeShortsDetectionDebugState.isYouTubeShortsActive == isActive) return
        YouTubeShortsDetectionDebugState.update(isActive)
        // Intentionally logs only the boolean transition, never node text or user content.
        if (BuildConfig.DEBUG) Log.d(LOG_TAG, "YOUTUBE_SHORTS_ACTIVE=$isActive")
    }

    private fun shutDownTracking(
        coordinator: OrderedTrackingStateCoordinator,
        scope: CoroutineScope,
        timestampMillis: Long,
    ) {
        val finalTrackingJob = coordinator.shutdown(timestampMillis)
        if (finalTrackingJob == null) {
            scope.cancel()
        } else {
            finalTrackingJob.invokeOnCompletion { scope.cancel() }
        }
    }

    private fun logSignalsIfChanged(detection: InstagramReelsDetection) {
        if (!BuildConfig.DEBUG) return
        val signals = detection.signals
        if (signals == lastLoggedSignals) return
        lastLoggedSignals = signals
        Log.d(
            LOG_TAG,
            "Instagram package detected; " +
                "viewerId=${signals.hasKnownViewerResourceId}, " +
                "selectedReels=${signals.hasSelectedReelsDestination}, " +
                "paging=${signals.hasScrollablePagingContainer}, " +
                "actionCount=${signals.actionControls.size}, " +
                "result=${detection.isActive}",
        )
    }

    private fun logEvaluation(
        instagramDetection: InstagramReelsDetection,
        youTubeDetection: YouTubeShortsDetection,
        durationMillis: Long,
    ) {
        if (!BuildConfig.DEBUG) return
        Log.d(
            LOG_TAG,
            "Short-form evaluation: " +
                "instagram=${instagramDetection.isActive}, " +
                "instagramNodes=${instagramDetection.nodesInspected}, " +
                "instagramHitLimit=${instagramDetection.hitNodeLimit}, " +
                "youtube=${youTubeDetection.isActive}, " +
                "youtubeNodes=${youTubeDetection.nodesInspected}, " +
                "youtubeHitLimit=${youTubeDetection.hitNodeLimit}, " +
                "durationMs=$durationMillis",
        )
    }

    private fun logInstagramRetryEvaluation(
        detection: InstagramReelsDetection,
        durationMillis: Long,
    ) {
        if (!BuildConfig.DEBUG) return
        Log.d(
            LOG_TAG,
            "Instagram retry evaluation: " +
                "active=${detection.isActive}, " +
                "nodes=${detection.nodesInspected}, " +
                "hitLimit=${detection.hitNodeLimit}, " +
                "durationMs=$durationMillis",
        )
    }

    private fun logInstagramContentWatchdogEvaluation(
        detection: InstagramReelsDetection,
        durationMillis: Long,
    ) {
        if (!BuildConfig.DEBUG) return
        Log.d(
            LOG_TAG,
            "Instagram content watchdog evaluation: " +
                "active=${detection.isActive}, " +
                "nodes=${detection.nodesInspected}, " +
                "hitLimit=${detection.hitNodeLimit}, " +
                "durationMs=$durationMillis",
        )
    }

    private fun logDetectionError(message: String, error: Exception) {
        if (BuildConfig.DEBUG) {
            Log.e(LOG_TAG, message, error)
        } else {
            Log.e(LOG_TAG, "$message (${error.javaClass.simpleName})")
        }
    }

    private companion object {
        const val LOG_TAG = "ReclaimReels"
        const val TRANSITION_DELAY_MILLIS = 125L
        const val CONTENT_DEBOUNCE_MILLIS = 500L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val INSTAGRAM_PLATFORM = "instagram"
        const val REELS_USAGE_TYPE = "reels"
        const val YOUTUBE_PLATFORM = "youtube"
        const val SHORTS_USAGE_TYPE = "shorts"
    }

    private enum class PendingReevaluation {
        FAST,
        CONTENT,
    }

    private data class ReevaluationContext(
        val packageNameHint: String?,
        val instagramOrigin: InstagramReelsEvaluationOrigin,
        val hasInstagramTransitionHint: Boolean,
        val instagramContentBurstGeneration: Long? = null,
    )

    private data class PendingInstagramRetry(
        val schedule: InstagramReelsRetrySchedule,
        val runnable: Runnable,
    )

    private data class PendingInstagramContentWatchdog(
        val schedule: InstagramContentStarvationWatchdogSchedule,
        val runnable: Runnable,
    )
}

private fun Int.toPolicyEventType(): ReelsAccessibilityEventType = when (this) {
    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
        ReelsAccessibilityEventType.WINDOW_STATE_CHANGED
    AccessibilityEvent.TYPE_WINDOWS_CHANGED ->
        ReelsAccessibilityEventType.WINDOWS_CHANGED
    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
        ReelsAccessibilityEventType.WINDOW_CONTENT_CHANGED
    AccessibilityEvent.TYPE_VIEW_SCROLLED ->
        ReelsAccessibilityEventType.VIEW_SCROLLED
    else -> ReelsAccessibilityEventType.OTHER
}

private fun ReelsAccessibilityEventType.isWindowTransition(): Boolean =
    this == ReelsAccessibilityEventType.WINDOW_STATE_CHANGED ||
        this == ReelsAccessibilityEventType.WINDOWS_CHANGED
