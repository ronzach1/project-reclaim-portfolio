package com.projectreclaim.app.detection

import java.util.concurrent.CopyOnWriteArraySet

/** Process-local, non-persistent YouTube Shorts state for debug display only. */
object YouTubeShortsDetectionDebugState {
    private val observers = CopyOnWriteArraySet<(Boolean) -> Unit>()

    @Volatile
    var isYouTubeShortsActive: Boolean = false
        private set

    fun observe(observer: (Boolean) -> Unit): () -> Unit {
        observers.add(observer)
        observer(isYouTubeShortsActive)
        return { observers.remove(observer) }
    }

    internal fun update(isActive: Boolean) {
        if (isYouTubeShortsActive == isActive) return
        isYouTubeShortsActive = isActive
        observers.forEach { it(isActive) }
    }

    internal fun reset() = update(false)
}
