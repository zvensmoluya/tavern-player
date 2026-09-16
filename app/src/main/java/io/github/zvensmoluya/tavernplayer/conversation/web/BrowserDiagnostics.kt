package io.github.zvensmoluya.tavernplayer.conversation.web

/** Browser layout notifications do not imply that the author program failed. */
internal fun isResizeObserverNotification(message: String): Boolean =
    message == "ResizeObserver loop completed with undelivered notifications." ||
        message == "ResizeObserver loop limit exceeded"
