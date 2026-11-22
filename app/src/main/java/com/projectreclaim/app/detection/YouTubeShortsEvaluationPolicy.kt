package com.projectreclaim.app.detection

enum class YouTubeShortsEvaluationAction {
    CLEAR_IMMEDIATELY,
    FAST_REEVALUATE,
    DEBOUNCED_REEVALUATE,
    IGNORE,
}

/** Pure event policy; the service composes this with the Instagram policy. */
object YouTubeShortsEvaluationPolicy {
    fun actionFor(
        eventType: ReelsAccessibilityEventType,
        packageName: String?,
    ): YouTubeShortsEvaluationAction = when {
        eventType == ReelsAccessibilityEventType.WINDOW_STATE_CHANGED &&
            packageName != null &&
            packageName != YouTubeShortsClassifier.YOUTUBE_PACKAGE ->
            YouTubeShortsEvaluationAction.CLEAR_IMMEDIATELY

        eventType == ReelsAccessibilityEventType.WINDOW_STATE_CHANGED ||
            eventType == ReelsAccessibilityEventType.WINDOWS_CHANGED ->
            YouTubeShortsEvaluationAction.FAST_REEVALUATE

        eventType == ReelsAccessibilityEventType.WINDOW_CONTENT_CHANGED &&
            (packageName == null || packageName == YouTubeShortsClassifier.YOUTUBE_PACKAGE) ->
            YouTubeShortsEvaluationAction.DEBOUNCED_REEVALUATE

        else -> YouTubeShortsEvaluationAction.IGNORE
    }
}
