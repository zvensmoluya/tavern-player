package io.github.zvensmoluya.tavernplayer.content

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BrowserProgramReaderTest {
    @Test fun originalArrayContainerRetainsDisabledFoldersAndUntrimmedSource() {
        val source = "  const text = '<not-a-model-prompt>';\n"
        val raw = buildJsonObject { putJsonObject("data") { putJsonObject("extensions") {
            putJsonArray("tavern_helper") { add(buildJsonArray {
                add("scripts"); add(buildJsonArray { add(buildJsonObject {
                    put("enabled", false); putJsonArray("scripts") { add(buildJsonObject { put("enabled", true); put("content", source) }) }
                }) })
            }) }
        } } }
        val program = BrowserProgramReader.character(CharacterAsset("sample", name = "Actor", rawCard = raw))
        val script = program.sources.single()
        assertEquals(source, script.content)
        assertFalse(script.enabled)
        assertEquals("/data/extensions/tavern_helper/0/1/0/scripts/0/content", script.pointer)
        assertEquals(BrowserProgramReader.sha256(source), script.sha256)
    }
    @Test fun unknownHelperContainerIsRejectedInsteadOfPretendingToRun() {
        assertThrows(IllegalStateException::class.java) {
            BrowserProgramReader.preset(buildJsonObject { putJsonObject("extensions") { put("tavern_helper", "unsupported") } })
        }
    }
}
