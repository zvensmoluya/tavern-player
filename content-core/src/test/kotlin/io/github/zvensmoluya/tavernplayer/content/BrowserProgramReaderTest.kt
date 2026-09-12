package io.github.zvensmoluya.tavernplayer.content

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BrowserProgramReaderTest {
    @Test fun legacyCharacterScriptsKeepTheirOriginalPathsDefaultsAndData() {
        val raw = Json.parseToJsonElement("""{"data":{"extensions":{
            "TavernHelper_scripts":[
                {"type":"script","value":{"id":"loader","enabled":true,"content":"  void 0;\n","data":{"seed":3},"buttons":[{"name":"Reset","visible":false}]}},
                {"type":"folder","value":[{"id":"off","content":"void 1;"},{"id":"on","enabled":true,"content":"void 2;"}]},
                {"id":"bare","enabled":true,"content":"void 3;"}
            ],
            "TavernHelper_characterScriptVariables":{"initial":7}
        }}}""").jsonObject
        val program = BrowserProgramReader.character(CharacterAsset("legacy", name = "Actor", rawCard = raw))
        assertEquals(listOf(true, false, true, true), program.sources.map { it.enabled })
        assertEquals(listOf("loader", "off", "on", "bare"), program.sources.map { it.declaredId })
        assertEquals("/data/extensions/TavernHelper_scripts/0/value/content", program.sources[0].pointer)
        assertEquals("/data/extensions/TavernHelper_scripts/1/value/1/content", program.sources[2].pointer)
        assertEquals("  void 0;\n", program.sources[0].content)
        assertEquals(BrowserProgramReader.sha256(program.sources[0].content), program.sources[0].sha256)
        assertEquals(JsonPrimitive(3), program.sources[0].data["seed"])
        assertEquals(listOf(BrowserScriptButton("Reset", false)), program.sources[0].buttons)
        assertEquals(JsonPrimitive(7), program.variables["initial"])
        assertEquals(program, Json.decodeFromString<BrowserProgram>(Json.encodeToString(program)))
    }

    @Test fun modernCharacterContainerWinsEvenWhenEmptyAndLegacyIsMalformed() {
        val raw = Json.parseToJsonElement("""{"extensions":{
            "tavern_helper":{},"TavernHelper_scripts":"invalid",
            "TavernHelper_characterScriptVariables":{"stale":1}
        }}""").jsonObject
        val program = BrowserProgramReader.character(CharacterAsset("mixed", name = "Actor", rawCard = raw))
        assertTrue(program.sources.isEmpty())
        assertTrue(program.variables.isEmpty())
        val legacyOnly = buildJsonObject { putJsonObject("extensions") { put("TavernHelper_scripts", "invalid") } }
        assertThrows(IllegalArgumentException::class.java) {
            BrowserProgramReader.character(CharacterAsset("invalid", name = "Actor", rawCard = legacyOnly))
        }
    }

    @Test fun capturesCharacterAndScriptVariableDefaultsWithoutChangingSource() {
        val raw = Json.parseToJsonElement("""{"extensions":{"tavern_helper":[
            ["variables",{"seed":7}],
            ["scripts",[{"id":"s","content":"void 0;","data":{"counter":2}}]]
        ]}}""").jsonObject
        val program = BrowserProgramReader.preset(raw)
        assertEquals(JsonPrimitive(7), program.variables["seed"])
        assertEquals(JsonPrimitive(2), program.sources.single().data["counter"])
        assertEquals("void 0;", program.sources.single().content)
        assertEquals(program, Json.decodeFromString<BrowserProgram>(Json.encodeToString(program)))
    }
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
    @Test fun authorRuntimeNeedsEnabledScriptsOrEjsTemplates() {
        assertFalse(BrowserProgramReader.character(CharacterAsset("plain", name = "纯文字样本")).hasAuthorRuntime)
        val disabled = BrowserProgramReader.character(CharacterAsset("off", name = "停用脚本",
            rawCard = buildJsonObject { putJsonObject("data") { putJsonObject("extensions") {
                putJsonArray("tavern_helper") { add(buildJsonArray { add("scripts"); add(buildJsonArray {
                    add(buildJsonObject { put("enabled", false); put("content", "void 0;") }) }) }) }
            } } }))
        assertFalse(disabled.hasAuthorRuntime)
        val viaScript = BrowserProgramReader.preset(buildJsonObject { putJsonObject("extensions") {
            putJsonArray("tavern_helper") { add(buildJsonArray { add("scripts"); add(buildJsonArray {
                add(buildJsonObject { put("content", "void 0;") }) }) }) }
        } })
        assertTrue(viaScript.hasAuthorRuntime)
        val viaEjs = CharacterAsset("ejs", name = "模板样本", worldBooks = listOf(WorldBookDefinition("book",
            entries = listOf(WorldBookEntryDefinition("entry", content = "血量：<%= 1 %>")))))
        assertTrue(BrowserProgramReader.character(viaEjs).hasAuthorRuntime)
    }
}
