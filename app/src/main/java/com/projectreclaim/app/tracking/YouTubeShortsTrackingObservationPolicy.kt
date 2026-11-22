package com.projectreclaim.app.tracking

enum class YouTubeShortsTrackingEvidence {
    YOUTUBE_ACCESSIBILITY_ROOT,
    UNAVAILABLE_ROOT,
    KNOWN_NON_YOUTUBE,
}

/** Decides whether YouTube Shorts evidence is authoritative enough to persist. */
object YouTubeShortsTrackingObservationPolicy {
    fun persistenceState(
        evidence: YouTubeShortsTrackingEvidence,
        detectorResult: Boolean,
    ): Boolean? = when (evidence) {
        YouTubeShortsTrackingEvidence.YOUTUBE_ACCESSIBILITY_ROOT -> detectorResult
        YouTubeShortsTrackingEvidence.UNAVAILABLE_ROOT -> null
        YouTubeShortsTrackingEvidence.KNOWN_NON_YOUTUBE -> false
    }
}
