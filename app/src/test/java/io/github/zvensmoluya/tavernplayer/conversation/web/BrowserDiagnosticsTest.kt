package io.github.zvensmoluya.tavernplayer.conversation.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDiagnosticsTest {
    @Test fun onlyBrowserResizeNotificationsAreRecoverable() {
        assertTrue(isResizeObserverNotification("ResizeObserver loop completed with undelivered notifications."))
        assertTrue(isResizeObserverNotification("ResizeObserver loop limit exceeded"))
        assertFalse(isResizeObserverNotification("ResizeObserver is not defined"))
        assertFalse(isResizeObserverNotification("Uncaught Error: ResizeObserver loop limit exceeded"))
        assertFalse(isResizeObserverNotification("Failed to load resource"))
    }
}
