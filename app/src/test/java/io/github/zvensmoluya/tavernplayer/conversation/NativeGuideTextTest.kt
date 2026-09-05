package io.github.zvensmoluya.tavernplayer.conversation

import org.junit.Assert.*
import org.junit.Test

class NativeGuideTextTest {
    @Test fun `guide keeps readable text and expands only fixed identities once`() {
        val source = "<div>给 {{user}}</div><div>{{char}}：领取地图。 &amp; <b>查看路线</b></div> {{setvar::route::north}}"
        val text = sanitizeCardText(nativeGuideIdentityText(source, "{{char}}", "向导"))
        assertTrue(text.contains("给 {{char}}"))
        assertTrue(text.contains("向导：领取地图。 & 查看路线"))
        assertTrue(text.contains("{{setvar::route::north}}"))
        assertFalse(text.contains("<div>"))
        assertFalse(sanitizeCardText("<script>alert(1)</script><img src='https://example.invalid/map.png'>地图").contains("alert"))
    }
}
