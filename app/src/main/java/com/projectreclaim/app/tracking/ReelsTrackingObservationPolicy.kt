package com.projectreclaim.app.tracking

enum class ReelsTrackingEvidence {
    ACCESSIBILITY_ROOT,
    UNAVAILABLE_ROOT,
    KNOWN_NON_INSTAGRAM,
}

/** Decides whether an A3 result is authoritative enough to update durable tracking. */
object ReelsTrackingObservationPolicy {
    fun persistenceState(
        evidence: ReelsTrackingEvidence,
        detectorResult: Boolean,
    ): Boolean? = when (evidence) {
        ReelsTrackingEvidence.ACCESSIBILITY_ROOT -> detectorResult
        ReelsTrackingEvidence.UNAVAILABLE_ROOT -> null
        ReelsTrackingEvidence.KNOWN_NON_INSTAGRAM -> false
    }
}
