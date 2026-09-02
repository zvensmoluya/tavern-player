package io.github.zvensmoluya.tavernplayer.content

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgramViewExtractorTest {
    @Test
    fun `extracts only executable content and redacts dependencies and secrets`() {
        val markup = """
            <html><script>
            const api_key = "secret-value";
            fetch("https://example.test/widget.js?token=private#frag");
            const image = "data:image/png;base64,AAAA";
            const path = "C:\\Users\\someone\\private.txt";
            getvar("affection");
            </script></html>
        """.trimIndent()
        val helper = JsonObject(
            mapOf(
                "scripts" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("script"),
                                "name" to JsonPrimitive("state updater"),
                                "enabled" to JsonPrimitive(true),
                                "content" to JsonPrimitive("set_message_variable('mood', 'calm')"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val card = CharacterAsset(
            id = "a".repeat(64),
            sourceSha256 = "a".repeat(64),
            name = "Fixture",
            description = "private narrative",
            firstMessage = "private opening",
            regexScripts = listOf(
                RegexDefinition(
                    id = "opening",
                    name = "Opening form",
                    findRegex = "<GAMESTART/>",
                    replaceString = markup,
                    placements = setOf(RegexPlacement.AI_OUTPUT),
                ),
            ),
            worldBooks = listOf(
                WorldBookDefinition(
                    id = "book",
                    entries = listOf(
                        WorldBookEntryDefinition(
                            id = "27",
                            name = "Night mode",
                            content = "private world book prose",
                            enabled = true,
                        ),
                    ),
                ),
            ),
            extensions = JsonObject(mapOf("tavern_helper" to helper)),
        )

        val view = ProgramViewExtractor().extract(card)
        val serializedProgram = view.programBlocks.joinToString("\n") { it.content }

        assertEquals(2, view.programBlocks.size)
        assertEquals(setOf(ProgramBlockKind.ACTIVE_MARKUP, ProgramBlockKind.SCRIPT), view.programBlocks.map { it.kind }.toSet())
        assertFalse(serializedProgram.contains("secret-value"))
        assertFalse(serializedProgram.contains("token=private"))
        assertFalse(serializedProgram.contains("private narrative"))
        assertFalse(serializedProgram.contains("private world book prose"))
        assertTrue(serializedProgram.contains("dependency://dependency-1"))
        assertTrue(serializedProgram.contains("<redacted-local-path>"))
        assertEquals("https://example.test/widget.js", view.dependencies.single().locator)
        assertEquals(listOf("affection", "mood"), view.referencedVariables)
        assertTrue("state.read" in view.observedCapabilities)
        assertTrue("state.write" in view.observedCapabilities)
        assertEquals(24, view.worldBookHandles.single().contentChars)
        assertEquals(setOf("description", "firstMessage"), view.omittedContent.map { it.field }.toSet())
    }
}
