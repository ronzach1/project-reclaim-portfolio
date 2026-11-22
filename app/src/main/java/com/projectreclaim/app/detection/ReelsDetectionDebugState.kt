package com.projectreclaim.app.detection

import java.util.concurrent.CopyOnWriteArraySet

/** Process-local, non-persistent observable state for the A3 spike only. */
object ReelsDetectionDebugState {
    private val observers = CopyOnWriteArraySet<(Boolean) -> Unit>()

    @Volatile
    var isInstagramReelsActive: Boolean = false
        private set

    fun observe(observer: (Boolean) -> Unit): () -> Unit {
        observers.add(observer)
        observer(isInstagramReelsActive)
        return { observers.remove(observer) }
    }

    internal fun update(isActive: Boolean) {
        if (isInstagramReelsActive == isActive) return
        isInstagramReelsActive = isActive
        observers.forEach { it(isActive) }
    }

    internal fun reset() = update(false)
}
