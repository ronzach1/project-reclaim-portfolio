package com.projectreclaim.app.detection

import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/**
 * Transiently inspects only structural resource IDs in the active YouTube tree.
 * Node text, descriptions, titles, captions, and other user content are never read,
 * retained, or logged.
 */
class YouTubeShortsDetector(
    private val maxNodesToInspect: Int = MAX_NODES_TO_INSPECT,
) {
    fun detect(packageName: String?, root: AccessibilityNodeInfo?): YouTubeShortsDetection {
        val collection = if (
            packageName == YouTubeShortsClassifier.YOUTUBE_PACKAGE && root != null
        ) {
            collectSignals(packageName, root)
        } else {
            SignalCollection(
                signals = YouTubeShortsSignals(packageName = packageName),
                nodesInspected = 0,
                hitNodeLimit = false,
            )
        }

        return YouTubeShortsDetection(
            isActive = YouTubeShortsClassifier.isShortsActive(collection.signals),
            signals = collection.signals,
            nodesInspected = collection.nodesInspected,
            hitNodeLimit = collection.hitNodeLimit,
        )
    }

    @Suppress("DEPRECATION")
    private fun collectSignals(
        packageName: String,
        root: AccessibilityNodeInfo,
    ): SignalCollection {
        val matchedViewerResourceIds = mutableSetOf<String>()
        val pendingNodes = ArrayDeque<AccessibilityNodeInfo>()
        pendingNodes.add(root)
        var inspectedNodes = 0

        try {
            while (pendingNodes.isNotEmpty() && inspectedNodes < maxNodesToInspect) {
                val node = pendingNodes.removeFirst()
                try {
                    inspectedNodes += 1
                    if (node.isVisibleToUser) {
                        val resourceId = node.viewIdResourceName
                        if (resourceId in YouTubeShortsClassifier.STRONG_VIEWER_RESOURCE_IDS) {
                            matchedViewerResourceIds.add(resourceId)
                            // A positive result is monotonic; the outer finally recycles queued nodes.
                            break
                        }
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
            signals = YouTubeShortsSignals(
                packageName = packageName,
                matchedViewerResourceIds = matchedViewerResourceIds,
            ),
            nodesInspected = inspectedNodes,
            hitNodeLimit = inspectedNodes >= maxNodesToInspect,
        )
    }

    private companion object {
        const val MAX_NODES_TO_INSPECT = 400
    }

    private data class SignalCollection(
        val signals: YouTubeShortsSignals,
        val nodesInspected: Int,
        val hitNodeLimit: Boolean,
    )
}

data class YouTubeShortsDetection(
    val isActive: Boolean,
    val signals: YouTubeShortsSignals,
    val nodesInspected: Int,
    val hitNodeLimit: Boolean,
)
