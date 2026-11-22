package com.projectreclaim.app.detection

import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/**
 * Transiently inspects the active accessibility tree and reduces it to non-content signals.
 * The tree, labels, captions, and other user content are never retained or logged.
 */
class InstagramReelsDetector(
    private val maxNodesToInspect: Int = MAX_NODES_TO_INSPECT,
) {
    fun detect(packageName: String?, root: AccessibilityNodeInfo?): InstagramReelsDetection {
        val collection = if (
            packageName == InstagramReelsClassifier.INSTAGRAM_PACKAGE && root != null
        ) {
            collectSignals(packageName, root)
        } else {
            SignalCollection(
                signals = InstagramReelsSignals(packageName = packageName),
                nodesInspected = 0,
                hitNodeLimit = false,
            )
        }

        return InstagramReelsDetection(
            isActive = InstagramReelsClassifier.isReelsActive(collection.signals),
            signals = collection.signals,
            nodesInspected = collection.nodesInspected,
            hitNodeLimit = collection.hitNodeLimit,
        )
    }

    fun isReelsActive(packageName: String?, root: AccessibilityNodeInfo?): Boolean {
        return detect(packageName, root).isActive
    }

    @Suppress("DEPRECATION")
    private fun collectSignals(
        packageName: String,
        root: AccessibilityNodeInfo,
    ): SignalCollection {
        var hasKnownViewerResourceId = false
        var hasSelectedReelsDestination = false
        var hasScrollablePagingContainer = false
        val actionControls = mutableSetOf<ReelsActionControl>()
        val pendingNodes = ArrayDeque<AccessibilityNodeInfo>()
        pendingNodes.add(root)
        var inspectedNodes = 0

        fun currentSignals() = InstagramReelsSignals(
            packageName = packageName,
            hasKnownViewerResourceId = hasKnownViewerResourceId,
            hasSelectedReelsDestination = hasSelectedReelsDestination,
            hasScrollablePagingContainer = hasScrollablePagingContainer,
            actionControls = actionControls,
        )

        try {
            while (pendingNodes.isNotEmpty() && inspectedNodes < maxNodesToInspect) {
                val node = pendingNodes.removeFirst()
                try {
                    inspectedNodes += 1
                    var signalsChanged = false
                    if (node.isVisibleToUser) {
                        val isSelectedDestinationCandidate =
                            !hasSelectedReelsDestination && node.isSelected
                        val resourceId = if (
                            !hasKnownViewerResourceId || isSelectedDestinationCandidate
                        ) {
                            node.viewIdResourceName?.substringAfterLast('/')
                        } else {
                            null
                        }

                        if (!hasKnownViewerResourceId && resourceId in KNOWN_VIEWER_RESOURCE_IDS) {
                            hasKnownViewerResourceId = true
                            signalsChanged = true
                        }
                        if (
                            isSelectedDestinationCandidate &&
                            (resourceId in REELS_DESTINATION_RESOURCE_IDS || isReelsLabel(node.contentDescription))
                        ) {
                            hasSelectedReelsDestination = true
                            signalsChanged = true
                        }
                        if (
                            !hasScrollablePagingContainer &&
                            node.isScrollable &&
                            node.className?.toString().orEmpty().isPagingContainerClass()
                        ) {
                            hasScrollablePagingContainer = true
                            signalsChanged = true
                        }
                        if (
                            actionControls.size < ReelsActionControl.entries.size &&
                            node.isClickable
                        ) {
                            matchingAction(node.contentDescription)?.let { action ->
                                signalsChanged = actionControls.add(action) || signalsChanged
                            }
                        }
                    }

                    if (
                        signalsChanged &&
                        InstagramReelsClassifier.isReelsActive(currentSignals())
                    ) {
                        // A positive result is monotonic; the outer finally recycles queued nodes.
                        break
                    }

                    for (childIndex in 0 until node.childCount) {
                        node.getChild(childIndex)?.let(pendingNodes::addLast)
                    }
                } finally {
                    if (node !== root) node.recycle()
                }
            }
        } finally {
            while (pendingNodes.isNotEmpty()) {
                val node = pendingNodes.removeFirst()
                if (node !== root) node.recycle()
            }
        }

        return SignalCollection(
            signals = currentSignals(),
            nodesInspected = inspectedNodes,
            hitNodeLimit = inspectedNodes >= maxNodesToInspect,
        )
    }

    private fun String.isPagingContainerClass(): Boolean =
        contains("ViewPager", ignoreCase = true) ||
            contains("RecyclerView", ignoreCase = true)

    private fun isReelsLabel(description: CharSequence?): Boolean =
        description?.toString()?.trim()?.equals("Reels", ignoreCase = true) == true

    private fun matchingAction(description: CharSequence?): ReelsActionControl? {
        val label = description?.toString()?.trim()?.lowercase() ?: return null
        return when {
            label.isControlLabel("like") -> ReelsActionControl.LIKE
            label.isControlLabel("comment") -> ReelsActionControl.COMMENT
            label.isControlLabel("share") -> ReelsActionControl.SHARE
            else -> null
        }
    }

    private fun String.isControlLabel(control: String): Boolean =
        this == control || startsWith("$control,") || startsWith("$control button")

    private companion object {
        const val MAX_NODES_TO_INSPECT = 400

        // These identifiers are intentionally isolated here because Instagram can change them.
        val KNOWN_VIEWER_RESOURCE_IDS = setOf(
            "clips_viewer_view_pager",
            "clips_viewer_container",
            "clips_viewer_fragment_container",
            "reels_viewer",
            "reel_viewer",
        )

        val REELS_DESTINATION_RESOURCE_IDS = setOf(
            "clips_tab",
            "clips_tab_icon",
            "reels_tab",
        )
    }

    private data class SignalCollection(
        val signals: InstagramReelsSignals,
        val nodesInspected: Int,
        val hitNodeLimit: Boolean,
    )
}

data class InstagramReelsDetection(
    val isActive: Boolean,
    val signals: InstagramReelsSignals,
    val nodesInspected: Int,
    val hitNodeLimit: Boolean,
)
