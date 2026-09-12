package io.github.zvensmoluya.tavernplayer.content

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class PresetImporterTest {
    private val importer = PresetImporter { "preset-id" }

    @Test
    fun builtInDefaultUsesNeutralStSkeletonAndNoContextCap() {
        val preset = BuiltInPresets.default

        assertTrue(preset.builtIn)
        assertEquals("默认", preset.name)
        assertNull(preset.generationSettings.maxContextTokens)
        assertEquals(32_768, preset.generationSettings.maxOutputTokens)
        assertTrue(preset.generationSettings.isEnabled(PresetGenerationParameter.OUTPUT_LIMIT))
        assertFalse(preset.generationSettings.isEnabled(PresetGenerationParameter.TEMPERATURE))
        assertFalse(preset.generationSettings.isEnabled(PresetGenerationParameter.TOP_P))
        assertEquals(
            "Write {{char}}'s next reply in a fictional chat between {{char}} and {{user}}.",
            preset.prompts.single { it.identifier == "main" }.content,
        )
        assertFalse(preset.promptOrder.single { it.identifier == "enhanceDefinitions" }.enabled)
        assertTrue(preset.promptOrder.any { it.identifier == "personaDescription" })
    }

    @Test
    fun importsGlobalOrderDefinitionsPlacementTriggersAndPresetRegex() {
        val result = importer.import(complexPreset(), "Writer.json") as PresetImportResult.Ready
        val preset = result.preset

        assertEquals("Writer", preset.name)
        assertEquals(listOf("main", "absolute"), preset.promptOrder.map { it.identifier })
        assertTrue(preset.prompts.any { it.identifier == "unused" })
        assertFalse(preset.promptOrder.any { it.identifier == "unused" })
        assertFalse(preset.promptOrder.any { it.identifier == "missing" })
        val absolute = preset.prompts.single { it.identifier == "absolute" }
        assertEquals(ContentRole.USER, absolute.role)
        assertEquals(PresetInjectionPosition.ABSOLUTE, absolute.injectionPosition)
        assertEquals(2, absolute.injectionDepth)
        assertEquals(setOf(PresetGenerationTrigger.REGENERATE), absolute.triggers)
        assertEquals(setOf("continue"), absolute.unknownTriggers)
        assertEquals(1, preset.regexScripts.size)
        assertTrue(preset.generationSettings.isEnabled(PresetGenerationParameter.TEMPERATURE))
        assertTrue(preset.generationSettings.isEnabled(PresetGenerationParameter.TOP_P))
        assertFalse(preset.generationSettings.isEnabled(PresetGenerationParameter.SEED))
        assertEquals(setOf(RegexPlacement.AI_OUTPUT, RegexPlacement.REASONING), preset.regexScripts.single().placements)
        assertTrue(result.diagnostics.any { it.code == "MISSING_PROMPT_DEFINITION_REFERENCE" })
        assertTrue(result.diagnostics.any { it.code == "UNSUPPORTED_PROMPT_TRIGGER" })
    }

    @Test
    fun fallsBackToLegacyGlobalOrderBucket() {
        val result = importer.import(
            """
                {
                  "prompts":[{"identifier":"main","name":"Main","role":"system","content":"Hello"}],
                  "prompt_order":[{"character_id":100000,"order":[{"identifier":"main","enabled":true}]}]
                }
            """.trimIndent().encodeToByteArray(),
        ) as PresetImportResult.Ready

        assertEquals(listOf("main"), result.preset.promptOrder.map { it.identifier })
        assertTrue(result.diagnostics.any { it.code == "LEGACY_PROMPT_ORDER_FALLBACK" })
    }

    @Test
    fun migratesLegacyPromptFieldsIntoDefaultDefinitionPool() {
        val result = importer.import(
            """
                {
                  "main_prompt":"Legacy main",
                  "nsfw_prompt":"Legacy auxiliary",
                  "jailbreak_prompt":"Legacy post-history",
                  "temperature":0.7
                }
            """.trimIndent().encodeToByteArray(),
            "Legacy.json",
        ) as PresetImportResult.Ready

        assertEquals("Legacy main", result.preset.prompts.single { it.identifier == "main" }.content)
        assertEquals("Legacy auxiliary", result.preset.prompts.single { it.identifier == "nsfw" }.content)
        assertEquals("Legacy post-history", result.preset.prompts.single { it.identifier == "jailbreak" }.content)
        assertEquals(0.7, result.preset.generationSettings.temperature)
        assertTrue(result.preset.generationSettings.isEnabled(PresetGenerationParameter.TEMPERATURE))
        assertFalse(result.preset.generationSettings.isEnabled(PresetGenerationParameter.OUTPUT_LIMIT))
        assertTrue(result.diagnostics.any { it.code == "LEGACY_PROMPTS_MIGRATED" })
    }

    @Test
    fun preservesCompleteSourceAcrossEditAndExportWithoutExecutingExtensions() {
        val source = """
            {
              "chat_completion_source":"custom",
              "custom_model":"community-model",
              "custom_url":"https://private.invalid/v1",
              "proxy_password":"secret-value",
              "custom_include_headers":"Authorization: Bearer secret-value",
              "prompts":[{"identifier":"main","content":"safe","role":"system"}],
              "prompt_order":[{"character_id":100001,"order":[{"identifier":"main","enabled":true}]}],
              "tavern_helper":{"script":"do-not-run()","future":true},
              "extensions":{
                "future":{"kept":42,"api_key":"nested-secret"},
                "helper_script":{"code":"still-preserved()"}
              }
            }
        """.trimIndent().encodeToByteArray()

        val result = importer.import(source) as PresetImportResult.Ready
        val edited = result.preset.copy(
            prompts = result.preset.prompts.map { prompt ->
                if (prompt.identifier == "main") prompt.copy(content = "edited") else prompt
            },
        )
        val exported = PresetExporter.export(edited).decodeToString()
        val json = Json.parseToJsonElement(exported).jsonObject

        assertEquals("custom", json["chat_completion_source"]!!.jsonPrimitive.content)
        assertEquals("community-model", json["custom_model"]!!.jsonPrimitive.content)
        assertEquals("https://private.invalid/v1", json["custom_url"]!!.jsonPrimitive.content)
        assertEquals("secret-value", json["proxy_password"]!!.jsonPrimitive.content)
        assertEquals(
            "Authorization: Bearer secret-value",
            json["custom_include_headers"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "nested-secret",
            json["extensions"]!!.jsonObject["future"]!!.jsonObject["api_key"]!!.jsonPrimitive.content,
        )
        assertEquals(42, json["extensions"]!!.jsonObject["future"]!!.jsonObject["kept"]!!.jsonPrimitive.content.toInt())
        assertEquals("do-not-run()", json["tavern_helper"]!!.jsonObject["script"]!!.jsonPrimitive.content)
        assertEquals(
            "edited",
            json["prompts"]!!.jsonArray.single().jsonObject["content"]!!.jsonPrimitive.content,
        )
        assertTrue(result.diagnostics.any { it.code == "THIRD_PARTY_SCRIPT_PRESERVED" })
    }

    @Test
    fun emptyTavernHelperContainersDoNotClaimThirdPartyRuntimeDependency() {
        val source = """
            {
              "prompts":[{"identifier":"main","content":"safe","role":"system"}],
              "prompt_order":[{"character_id":100001,"order":[{"identifier":"main","enabled":true}]}],
              "extensions":{"tavern_helper":{"scripts":[],"variables":{}}}
            }
        """.trimIndent().encodeToByteArray()

        val result = importer.import(source) as PresetImportResult.Ready

        assertTrue(result.preset.source["extensions"]!!.jsonObject.containsKey("tavern_helper"))
        assertFalse(result.diagnostics.any { it.code == "THIRD_PARTY_SCRIPT_PRESERVED" })
    }

    @Test
    fun exporterMergesEditsAndCanBeImportedAgainWithoutLosingExtensions() {
        val imported = importer.import(complexPreset(), "Round Trip.json") as PresetImportResult.Ready
        val edited = imported.preset.copy(
            prompts = imported.preset.prompts.map {
                if (it.identifier == "main") it.copy(content = "Edited main") else it
            },
            promptOrder = imported.preset.promptOrder.reversed().mapIndexed { index, entry ->
                if (index == 0) entry.copy(enabled = false) else entry
            },
            generationSettings = imported.preset.generationSettings.copy(
                maxOutputTokens = 777,
                seed = 123,
            ).withEnabled(PresetGenerationParameter.SEED, true),
            controlSettings = imported.preset.controlSettings.copy(
                assistantPrefill = "Prefill",
                showThoughts = false,
            ),
            regexScripts = imported.preset.regexScripts.map { it.copy(disabled = true) },
        )

        val exported = PresetExporter.export(edited)
        val reimported = importer.import(exported, "Again.json") as PresetImportResult.Ready
        val root = Json.parseToJsonElement(exported.decodeToString()).jsonObject

        assertEquals("Edited main", reimported.preset.prompts.single { it.identifier == "main" }.content)
        assertEquals(listOf("absolute", "main"), reimported.preset.promptOrder.map { it.identifier })
        assertFalse(reimported.preset.promptOrder.first().enabled)
        assertEquals(777, reimported.preset.generationSettings.maxOutputTokens)
        assertEquals(123, reimported.preset.generationSettings.seed)
        assertEquals("Prefill", reimported.preset.controlSettings.assistantPrefill)
        assertFalse(reimported.preset.controlSettings.showThoughts)
        assertTrue(reimported.preset.regexScripts.single().disabled)
        assertEquals("kept", root["future_root"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertFalse(root["stream_openai"]!!.jsonPrimitive.boolean)
        assertEquals(3, root["n"]!!.jsonPrimitive.content.toInt())
        assertTrue(root["prompt_order"]!!.jsonArray.hasGlobalOrder(100001))
    }

    @Test
    fun disabledRequestParametersKeepTheirValueButDisappearFromExportAndRestoreAsDisabled() {
        val imported = importer.import(complexPreset(), "Switches.json") as PresetImportResult.Ready
        val disabled = imported.preset.copy(
            generationSettings = imported.preset.generationSettings
                .withEnabled(PresetGenerationParameter.TEMPERATURE, false),
        )

        assertEquals(0.8, disabled.generationSettings.temperature)
        val exported = PresetExporter.export(disabled)
        val root = Json.parseToJsonElement(exported.decodeToString()).jsonObject
        val reimported = importer.import(exported, "Switches.json") as PresetImportResult.Ready

        assertFalse(root.containsKey("temperature"))
        assertFalse(reimported.preset.generationSettings.isEnabled(PresetGenerationParameter.TEMPERATURE))
        assertTrue(reimported.preset.generationSettings.isEnabled(PresetGenerationParameter.TOP_P))
    }

    @Test
    fun rejectsRandomJsonAndOversizedInput() {
        val random = importer.import("""{"temperature":1}""".encodeToByteArray())
        val oversized = importer.import(ByteArray(PresetImporter.MAX_SOURCE_BYTES + 1) { 'x'.code.toByte() })

        assertTrue(random is PresetImportResult.Rejected)
        assertTrue(oversized is PresetImportResult.Rejected)
        assertEquals("UNRECOGNIZED_CHAT_COMPLETION_PRESET", random.diagnostics.single().code)
        assertEquals("SOURCE_TOO_LARGE", oversized.diagnostics.single().code)
    }

    @Test
    fun importsAndReexportsPinnedLocalStDefaultWhenConfigured() {
        val configured = System.getProperty("stDefaultPreset")?.takeIf(String::isNotBlank)
        val file = configured?.let(::File)
        assumeTrue("local ST Default.json not configured", file?.isFile == true)

        val imported = importer.import(file!!.readBytes(), file.name) as PresetImportResult.Ready
        val exported = PresetExporter.export(imported.preset)
        val reimported = importer.import(exported, file.name) as PresetImportResult.Ready

        assertTrue(imported.preset.prompts.any { it.identifier == "main" })
        assertTrue(imported.preset.promptOrder.any { it.identifier == "chatHistory" })
        assertTrue(imported.preset.promptOrder.any { it.identifier == "personaDescription" })
        val originalRoot = Json.parseToJsonElement(file.readText()).jsonObject
        val exportedRoot = Json.parseToJsonElement(exported.decodeToString()).jsonObject
        originalRoot["chat_completion_source"]?.let { source ->
            assertEquals(source, exportedRoot["chat_completion_source"])
        }
        assertEquals(imported.preset.contentSha256, reimported.preset.contentSha256)
    }

    private fun complexPreset(): ByteArray = """
        {
          "temperature":0.8,
          "top_p":0.9,
          "top_a":0.2,
          "openai_max_context":8192,
          "openai_max_tokens":512,
          "stream_openai":false,
          "n":3,
          "prompts":[
            {"identifier":"main","name":"Main","role":"system","content":"Main content","future_prompt_key":"kept"},
            {"identifier":"absolute","name":"Depth","role":"user","content":"Depth content","injection_position":1,"injection_depth":2,"injection_order":7,"injection_trigger":["regenerate","continue"]},
            {"identifier":"unused","name":"Unused","role":"assistant","content":"Keep me"}
          ],
          "prompt_order":[
            {"character_id":100000,"order":[{"identifier":"unused","enabled":true}]},
            {"character_id":100001,"future_bucket":"kept","order":[
              {"identifier":"main","enabled":true},
              {"identifier":"missing","enabled":true},
              {"identifier":"absolute","enabled":true}
            ]}
          ],
          "extensions":{"regex_scripts":[{
            "id":"regex-1","scriptName":"Display","findRegex":"secret","replaceString":"shown",
            "placement":[2,6],"disabled":false,"future_regex":"kept"
          }]},
          "future_root":{"value":"kept"}
        }
    """.trimIndent().encodeToByteArray()
}

private fun JsonArray.hasGlobalOrder(id: Int): Boolean = any { element ->
    val bucket = element as? JsonObject ?: return@any false
    bucket["character_id"]?.jsonPrimitive?.content?.toIntOrNull() == id
}
