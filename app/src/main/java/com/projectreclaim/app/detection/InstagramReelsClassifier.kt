package com.projectreclaim.app.detection

/**
 * Privacy-preserving summary of the live accessibility tree. No node text or user
 * content is retained in this object.
 */
data class InstagramReelsSignals(
    val packageName: String?,
    val hasKnownViewerResourceId: Boolean = false,
    val hasSelectedReelsDestination: Boolean = false,
    val hasScrollablePagingContainer: Boolean = false,
    val actionControls: Set<ReelsActionControl> = emptySet(),
)

enum class ReelsActionControl {
    LIKE,
    COMMENT,
    SHARE,
}

/** Pure decision logic so the conservative heuristic can be unit tested and changed easily. */
object InstagramReelsClassifier {
    const val INSTAGRAM_PACKAGE = "com.instagram.android"

    fun isReelsActive(signals: InstagramReelsSignals): Boolean {
        if (signals.packageName != INSTAGRAM_PACKAGE) return false

        val knownViewerWithCorroboration =
            signals.hasKnownViewerResourceId &&
                (signals.hasScrollablePagingContainer || signals.actionControls.size >= 2)

        val selectedDestinationWithFullViewerShape =
            signals.hasSelectedReelsDestination &&
                signals.hasScrollablePagingContainer &&
                signals.actionControls.containsAll(ReelsActionControl.entries)

        return knownViewerWithCorroboration || selectedDestinationWithFullViewerShape
    }
}
