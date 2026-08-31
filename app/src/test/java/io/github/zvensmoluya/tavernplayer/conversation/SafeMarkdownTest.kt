package io.github.zvensmoluya.tavernplayer.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeMarkdownTest {
    @Test
    fun `active blocks and html tags are removed while text and entities remain`() {
        val source = """
            <style>.secret { display:none }</style>
            <script>fetch('https://bad.example')</script>
            <div>Hello &amp; &lt;Traveler&gt;<br><b>safe</b></div>
        """.trimIndent()

        val result = sanitizeCardText(source)

        assertFalse(result.contains("fetch"))
        assertFalse(result.contains("display:none"))
        assertFalse(result.contains("<div>"))
        assertTrue(result.contains("Hello & <Traveler>"))
        assertTrue(result.contains("safe"))
    }

    @Test
    fun `unclosed active block consumes the unsafe tail`() {
        assertEquals("before", sanitizeCardText("before<script>while(true){}"))
    }
}
