package io.github.zvensmoluya.tavernplayer.content

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NativeHelperContainerTest {
    @Test fun localC07ScriptsReachCompilerWithoutChangingTheCard() {
        val configured = System.getenv("TAVERN_HELPER_AUDIT_CARD")
        org.junit.Assume.assumeTrue(!configured.isNullOrBlank())
        val bytes = java.io.File(configured!!).readBytes()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("0166ea69a6bdfa0e7559cc98e877d3d5b6b106bc12e1c712a45ba96585f359f0", hash)
        val asset = (CharacterCardImporter().import(bytes) as CharacterImportResult.Ready).character
        val rawBefore = asset.rawCard.toString()
        val view = NativeProgramExtractor().extract(asset, emptySet())
        val scripts = view.sources.filter { it.kind == "SCRIPT" }
        assertEquals(2, scripts.size)
        assertTrue(scripts.all { it.active })
        assertEquals("/data/extensions/tavern_helper/0/1/0/content", scripts[0].path)
        assertTrue(scripts[0].content.contains("registerMvuSchema"))
        assertTrue(view.request.toString().contains("registerMvuSchema"))
        assertTrue(view.sources.any { it.active && it.content.contains("{{format_message_variable::stat_data}}") })
        assertTrue(NativeAdaptationCompiler().select(asset,
            """{"runtime":"MVU","schemaSourceId":"${scripts[0].id}"}""", emptySet()) is NativeCompilationSelectionResult.Ready)
        assertEquals(rawBefore, asset.rawCard.toString())
    }

    private fun card(helper: String) = CharacterAsset(
        id = "a".repeat(64), name = "Sample",
        rawCard = Json.parseToJsonElement("""{"data":{"extensions":{"tavern_helper":$helper}}}""").jsonObject,
    )

    @Test fun objectAndEntryArrayPreserveScriptsMetadataAndSourcePointers() {
        val scripts = """[{"enabled":true,"content":"registerMvuSchema(schema);"},{"enabled":false,"scripts":[{"enabled":true,"content":"disabled();"}]}]"""
        val variants = listOf(
            """{"scripts":$scripts,"variables":{"score":3}}""" to "/scripts",
            """[["variables",{"score":3}],["scripts",$scripts]]""" to "/1/1",
        )
        for ((helper, suffix) in variants) {
            val asset = card(helper)
            val view = NativeProgramExtractor().extract(asset, emptySet())
            val sources = view.sources.filter { it.kind == "SCRIPT" }
            assertEquals(listOf(true, false), sources.map { it.active })
            assertEquals("/data/extensions/tavern_helper$suffix/0/content", sources[0].path)
            assertEquals("/data/extensions/tavern_helper$suffix/1/scripts/0/content", sources[1].path)
            assertEquals("registerMvuSchema(schema);", sources[0].content)
            assertEquals("""{"variables":{"score":3}}""", view.sources.single { it.id == "helper-metadata" }.content)
            val compiler = NativeAdaptationCompiler()
            assertTrue(compiler.prepare(asset, emptySet()).contains("registerMvuSchema(schema);"))
            assertTrue(compiler.select(asset, """{"runtime":"MVU","schemaSourceId":"script0"}""", emptySet()) is NativeCompilationSelectionResult.Ready)
            assertTrue(compiler.select(asset, """{"runtime":"MVU","schemaSourceId":"script1"}""", emptySet()) is NativeCompilationSelectionResult.Rejected)
        }
    }

    @Test fun malformedOrAmbiguousContainersFailBeforeModelPreparation() {
        for (helper in listOf("null", "42", "[[1,[]]]", "[[\"scripts\"]]", "[[\"scripts\",[],0]]",
            "[[\"scripts\",[]],[\"scripts\",[]]]", "{\"scripts\":{}}")) {
            try {
                NativeAdaptationCompiler().prepare(card(helper), emptySet())
                fail("Accepted $helper")
            } catch (error: IllegalArgumentException) {
                assertTrue(error.message.orEmpty().contains("/data/extensions/tavern_helper"))
            }
        }
    }
}
