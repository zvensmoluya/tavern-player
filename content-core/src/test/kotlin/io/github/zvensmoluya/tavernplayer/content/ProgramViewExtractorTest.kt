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
            firstMessage = "private opening\n<GAMESTART/>",
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
        assertEquals("MESSAGE_CONTAINS", view.programBlocks.first().triggerMatchMode)
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

    @Test
    fun `reduces update variable books to primitive state hints`() {
        val card = CharacterAsset(
            id = "b".repeat(64),
            sourceSha256 = "b".repeat(64),
            name = "State fixture",
            worldBooks = listOf(
                WorldBookDefinition(
                    id = "book",
                    entries = listOf(
                        WorldBookEntryDefinition(
                            id = "rule",
                            name = "变量更新规则",
                            content = """
                                {{get_message_variable::stat_data}}
                                <UpdateVariable>
                                _.set('${'$'}{path_of_changed_variable}', 0, 1);
                                _.set('世界.日期', 1, 2);
                                </UpdateVariable>
                            """.trimIndent(),
                            enabled = true,
                        ),
                        WorldBookEntryDefinition(
                            id = "init",
                            comment = "[InitVar]初始化",
                            content = """{"世界":{"日期":[1,"PRIVATE DESCRIPTION"],"地点":["家","PRIVATE PLACE"]},"角色":{"好感度":[50,"PRIVATE RELATION"]}}""",
                            enabled = false,
                        ),
                    ),
                ),
            ),
        )

        val view = ProgramViewExtractor().extract(card)

        val hint = view.stateProtocolHints.single()
        assertEquals("UPDATE_VARIABLE_SET_V1", hint.dialect)
        assertEquals("stat_data", hint.variableName)
        assertEquals(setOf("世界.日期", "世界.地点", "角色.好感度"), hint.values.map { it.path }.toSet())
        assertEquals(JsonPrimitive(1), hint.values.single { it.path == "世界.日期" }.initialValue)
        assertFalse(hint.values.joinToString().contains("PRIVATE"))
        assertEquals(listOf("stat_data"), view.referencedVariables)
        assertTrue("state.read" in view.observedCapabilities)
        assertTrue("state.write" in view.observedCapabilities)
    }
}
