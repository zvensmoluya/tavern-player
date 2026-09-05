package io.github.zvensmoluya.tavernplayer.content

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class CharacterCardImporterTest {
    private val importer = CharacterCardImporter { "asset-id" }

    @Test
    fun importsV3JsonAndPreservesUnknownData() {
        val bytes = """
            {
              "spec":"chara_card_v3",
              "spec_version":"3.1",
              "data":{
                "name":"Aster",
                "nickname":"Ash",
                "description":"{{char}} is here",
                "first_mes":"Hello",
                "alternate_greetings":["Hi"],
                "group_only_greetings":[],
                "mes_example":"<START>\n{{user}}: Test\n{{char}}: Answer",
                "extensions":{"future":{"kept":true}},
                "unknown_future_field":{"value":42}
              }
            }
        """.trimIndent().encodeToByteArray()

        val result = importer.import(bytes) as CharacterImportResult.Ready

        assertEquals(CharacterCardGeneration.V3, result.character.cardGeneration)
        assertEquals("Aster", result.character.name)
        assertEquals("Ash", result.character.promptName)
        assertNotNull(result.character.rawCard["data"])
        assertTrue(result.diagnostics.any { it.code == "FUTURE_OR_NONSTANDARD_V3" })
        assertArrayEquals(bytes, result.sourceBytes)
    }

    @Test
    fun pngUsesCcv3BeforeChara() {
        val v2 = """{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"Old"}}"""
        val v3 = """{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"New","group_only_greetings":[]}}"""
        val png = png(
            "chara" to Base64.getEncoder().encodeToString(v2.encodeToByteArray()),
            "ccv3" to Base64.getEncoder().encodeToString(v3.encodeToByteArray()),
        )

        val result = importer.import(png) as CharacterImportResult.Ready

        assertEquals("New", result.character.name)
        assertEquals(CharacterSourceFormat.PNG, result.character.sourceFormat)
        assertTrue(result.diagnostics.any { it.code == "CCV3_PRECEDENCE" })
    }

    @Test
    fun pngFallsBackToCharaAndAcceptsApngAncillaryChunks() {
        val v2 = """{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"Fallback"}}"""
        val png = pngWithChunks(
            "acTL" to byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0),
            "tEXt" to ("chara".toByteArray(StandardCharsets.ISO_8859_1) + byteArrayOf(0) +
                Base64.getEncoder().encodeToString(v2.encodeToByteArray()).toByteArray(StandardCharsets.ISO_8859_1)),
        )

        val result = importer.import(png) as CharacterImportResult.Ready

        assertEquals("Fallback", result.character.name)
        assertEquals(CharacterCardGeneration.V2, result.character.cardGeneration)
        assertTrue(result.character.assets.any { it.type == "icon" && it.name == "main" && it.uri == "ccdefault:" })
        assertTrue(result.character.assets.single().id.startsWith("asset-"))
    }

    @Test
    fun v2DataIsAuthoritativeAndMissingOptionalFieldsUseDefaultsWithWarning() {
        val result = importer.import(
            """{"name":"Wrong","spec":"chara_card_v2","spec_version":"2.0","data":{"name":"Right"}}"""
                .encodeToByteArray(),
        ) as CharacterImportResult.Ready

        assertEquals("Right", result.character.name)
        assertEquals("", result.character.firstMessage)
        assertTrue(result.diagnostics.any { it.code == "MISSING_OPTIONAL_FIELDS_DEFAULTED" })
    }

    @Test
    fun rejectsBrokenBase64InvalidUtf8AndMissingName() {
        val base64 = importer.import(png("chara" to "not base64!")) as CharacterImportResult.Rejected
        val utf8 = importer.import(byteArrayOf('{'.code.toByte(), '"'.code.toByte(), 0xC3.toByte(), 0x28, '}'.code.toByte()))
            as CharacterImportResult.Rejected
        val name = importer.import("""{"spec":"chara_card_v3","spec_version":"3.0","data":{}}""".encodeToByteArray())
            as CharacterImportResult.Rejected

        assertTrue(base64.diagnostics.any { it.code == "INVALID_BASE64" })
        assertTrue(utf8.diagnostics.any { it.code == "INVALID_UTF8" })
        assertTrue(name.diagnostics.any { it.code == "MISSING_CHARACTER_NAME" })
    }

    @Test
    fun enforcesSourceAndJsonMetadataLimits() {
        val oversizedSource = importer.import(ByteArray(CharacterCardImporter.MAX_SOURCE_BYTES + 1))
            as CharacterImportResult.Rejected
        val oversizedJson = ByteArray(CharacterCardImporter.MAX_METADATA_BYTES + 1) { ' '.code.toByte() }.also {
            it[0] = '{'.code.toByte()
        }
        val oversizedMetadata = importer.import(oversizedJson) as CharacterImportResult.Rejected

        assertTrue(oversizedSource.diagnostics.any { it.code == "SOURCE_TOO_LARGE" })
        assertTrue(oversizedMetadata.diagnostics.any { it.code == "METADATA_TOO_LARGE" })
    }

    @Test
    fun rejectsPngWithInvalidCrc() {
        val json = """{"name":"Broken"}"""
        val png = png("chara" to Base64.getEncoder().encodeToString(json.encodeToByteArray()))
        png[png.lastIndex - 1] = (png[png.lastIndex - 1].toInt() xor 1).toByte()

        val result = importer.import(png) as CharacterImportResult.Rejected

        assertTrue(result.diagnostics.any { it.code == "PNG_CRC_MISMATCH" })
    }

    @Test
    fun rejectsPngWithOutOfBoundsChunk() {
        val json = """{"name":"Truncated"}"""
        val complete = png("chara" to Base64.getEncoder().encodeToString(json.encodeToByteArray()))
        val truncated = complete.copyOf(complete.size - 3)

        val result = importer.import(truncated) as CharacterImportResult.Rejected

        assertTrue(result.diagnostics.any { it.code == "TRUNCATED_PNG" })
    }

    @Test
    fun importsLegacyPygmalionShapeAsV1() {
        val result = importer.import(
            """{"char_name":"Mira","char_persona":"Quiet","char_greeting":"Welcome","example_dialogue":"Example","world_scenario":"Rain"}"""
                .encodeToByteArray(),
        ) as CharacterImportResult.Ready

        assertEquals(CharacterCardGeneration.V1, result.character.cardGeneration)
        assertEquals("Mira", result.character.name)
        assertEquals("Quiet", result.character.description)
        assertEquals("Welcome", result.character.firstMessage)
        assertEquals("Rain", result.character.scenario)
    }

    @Test
    fun importsLocalCommunityCardWhenAvailable() {
        val configured = System.getProperty("communityCard")?.takeIf(String::isNotBlank)
        val file = configured?.let(::File) ?: File("../source/少女.png")
        assumeTrue("community card not available", file.isFile)

        val result = importer.import(file.readBytes(), file.name) as CharacterImportResult.Ready

        assertEquals("奴隶社会与三位少女", result.character.name)
        assertEquals(18, result.character.worldBooks.single().entries.size)
        assertEquals(5, result.character.regexScripts.size)
        assertTrue(result.diagnostics.any { it.code == "THIRD_PARTY_SCRIPT_PRESERVED" })
        assertTrue(result.diagnostics.any { it.code == "UNSUPPORTED_DYNAMIC_MACRO" })
        assertTrue(result.diagnostics.any { it.code == "ACTIVE_MARKUP_DOWNGRADED" })
    }

    private fun png(vararg textChunks: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        textChunks.forEach { (key, value) ->
            chunk(
                output,
                "tEXt",
                key.toByteArray(StandardCharsets.ISO_8859_1) + byteArrayOf(0) +
                    value.toByteArray(StandardCharsets.ISO_8859_1),
            )
        }
        chunk(output, "IEND", byteArrayOf())
        return output.toByteArray()
    }

    private fun pngWithChunks(vararg chunks: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        chunks.forEach { (type, value) -> chunk(output, type, value) }
        chunk(output, "IEND", byteArrayOf())
        return output.toByteArray()
    }

    private fun chunk(output: ByteArrayOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(StandardCharsets.US_ASCII)
        DataOutputStream(output).writeInt(data.size)
        output.write(typeBytes)
        output.write(data)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }.value
        DataOutputStream(output).writeInt(crc.toInt())
    }
}
