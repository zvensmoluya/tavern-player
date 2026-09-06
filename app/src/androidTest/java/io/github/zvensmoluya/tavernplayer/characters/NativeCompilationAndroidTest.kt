package io.github.zvensmoluya.tavernplayer.characters

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zvensmoluya.tavernplayer.content.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on Android's ICU regex engine, which host JVM/Robolectric tests do not reproduce. */
@RunWith(AndroidJUnit4::class)
class NativeCompilationAndroidTest {
    @Test fun realCardPreparationRunsOnAndroidWithoutNetwork() {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("doctor-card.png").use { it.readBytes() }
        val imported = CharacterCardImporter().import(bytes, "sample-c03.png") as CharacterImportResult.Ready
        val wire = Json.parseToJsonElement(NativeAdaptationCompiler().prepare(imported.character, emptySet())).jsonObject
        val replacements = wire.getValue("sources").jsonArray.map { it.jsonObject }.filter { it["kind"] == JsonPrimitive("REGEX_REPLACEMENT") }
        assertEquals(imported.character.regexScripts.size, replacements.size)
        assertEquals(imported.character.regexScripts.first().replaceString, replacements.first().getValue("content").jsonPrimitive.content)
    }

    @Test fun identityMacrosAndProgramMacrosUseAndroidCompatiblePatterns() {
        val source = CharacterAsset(id = "a".repeat(64), name = "Sample", rawCard = buildJsonObject {
            put("data", buildJsonObject {
                put("description", "LOCAL_ONLY {{user}} meets {{char}}")
                put("first_mes", "{{format_message_variable::stat_data}}")
            })
        }, worldBooks = listOf(WorldBookDefinition("book", entries = listOf(
            WorldBookEntryDefinition("branch", content = "Prefix<% if (ready()) { %>Body<% } %>Suffix"),
        ))))
        val compiler = NativeAdaptationCompiler()
        val request = compiler.prepare(source, emptySet())
        assertFalse(request.contains("LOCAL_ONLY"))
        assertTrue(request.contains("format_message_variable::stat_data"))
        assertTrue(request.contains("[[LOCAL_TEXT:"))
        assertTrue(compiler.complete(source, Json.encodeToString(NativeCompilationDraft("仅验证本地流程")), emptySet()) is NativeCompilationResult.Ready)
    }
}
