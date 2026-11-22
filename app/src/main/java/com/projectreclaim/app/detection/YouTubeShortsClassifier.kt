package com.projectreclaim.app.detection

/**
 * Privacy-preserving structural summary of the active YouTube accessibility tree.
 * Only exact, known viewer resource IDs are retained.
 */
data class YouTubeShortsSignals(
    val packageName: String?,
    val matchedViewerResourceIds: Set<String> = emptySet(),
)

/** Pure decision logic for the observed YouTube Shorts viewer structure. */
object YouTubeShortsClassifier {
    const val YOUTUBE_PACKAGE = "com.google.android.youtube"

    internal val STRONG_VIEWER_RESOURCE_IDS = setOf(
        "$YOUTUBE_PACKAGE:id/reel_watch_fragment_root",
        "$YOUTUBE_PACKAGE:id/reel_recycler",
        "$YOUTUBE_PACKAGE:id/reel_player_page_container",
        "$YOUTUBE_PACKAGE:id/reel_watch_player",
    )

    fun isShortsActive(signals: YouTubeShortsSignals): Boolean =
        signals.packageName == YOUTUBE_PACKAGE &&
            signals.matchedViewerResourceIds.any(STRONG_VIEWER_RESOURCE_IDS::contains)
}
