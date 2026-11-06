package com.wodcapture.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Feedback mail goes to the owner and carries the version and device (REQ-AG05). */
class FeedbackTest {
    @Test
    fun subjectNamesTheVersion() {
        assertEquals("WOD Capture feedback (v0.8.6)", Feedback.subject("0.8.6"))
    }

    @Test
    fun bodyLeavesRoomThenListsContext() {
        val body = Feedback.body("0.8.6", "14", "Google Pixel 8")
        assertTrue(body.startsWith("\n\n"))
        assertTrue(body.contains("WOD Capture 0.8.6 · Android 14 · Google Pixel 8"))
    }

    @Test
    fun mailtoIsAddressedAndPercentEncoded() {
        val uri = Feedback.mailto("WOD Capture feedback (v0.8.6)", "a b\n&c")
        assertTrue(uri.startsWith("mailto:cyturel@gmail.com?subject="))
        assertTrue(uri.contains("subject=WOD%20Capture%20feedback%20%28v0.8.6%29"))
        assertTrue(uri.endsWith("&body=a%20b%0A%26c"))
        assertFalse(uri.contains("+"))
    }
}
