package com.projectreclaim.app.detection

enum class ReelsAccessibilityEventType {
    WINDOW_STATE_CHANGED,
    WINDOWS_CHANGED,
    WINDOW_CONTENT_CHANGED,
    VIEW_SCROLLED,
    OTHER,
}

enum class ReelsEvaluationAction {
    CLEAR_IMMEDIATELY,
    FAST_REEVALUATE,
    DEBOUNCED_REEVALUATE,
    IGNORE,
}

/** Pure event policy; Handler timing and Android event mapping remain in the service. */
object ReelsEvaluationPolicy {
    fun actionFor(
        eventType: ReelsAccessibilityEventType,
        packageName: String?,
    ): ReelsEvaluationAction = when {
        eventType == ReelsAccessibilityEventType.WINDOW_STATE_CHANGED &&
            packageName != null &&
            packageName != InstagramReelsClassifier.INSTAGRAM_PACKAGE ->
            ReelsEvaluationAction.CLEAR_IMMEDIATELY

        eventType == ReelsAccessibilityEventType.WINDOW_STATE_CHANGED ||
            eventType == ReelsAccessibilityEventType.WINDOWS_CHANGED ->
            ReelsEvaluationAction.FAST_REEVALUATE

        eventType == ReelsAccessibilityEventType.WINDOW_CONTENT_CHANGED &&
            (packageName == null || packageName == InstagramReelsClassifier.INSTAGRAM_PACKAGE) ->
            ReelsEvaluationAction.DEBOUNCED_REEVALUATE

        else -> ReelsEvaluationAction.IGNORE
    }
}
