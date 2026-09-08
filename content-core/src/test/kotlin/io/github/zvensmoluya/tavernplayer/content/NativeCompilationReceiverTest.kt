package io.github.zvensmoluya.tavernplayer.content

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NativeCompilationReceiverTest {
    private val program = NativeScriptProgram(
        modules = listOf(NativeScriptModule("main", "export function present(c) { return {surface:'status',title:'{ok}',items:[]}; }",
            listOf("script0"), "保留显示")),
        surfaces = listOf(NativeSurfaceEntry("status", "main", "present", NativeSurfaceType.STATUS)),
    )
    private val draft = NativeCompilationDraft("显示", script = program)
    private fun rejected(text: String) = assertThrows(NativeCompilationInputFailure::class.java) {
        NativeCompilationReceiver.receive(text)
    }.issues

    @Test fun envelopesPreserveExactExecutableContents() {
        val raw = Json.encodeToString(draft)
        listOf(raw, "\uFEFF$raw", "```\r\n$raw\r\n```", "```JSON\n$raw\n```", "结果如下：\n$raw\n以上为结果。", "$raw\n以上为结果。").forEach {
            assertEquals(draft, NativeCompilationReceiver.receive(it).draft)
        }
        assertTrue(NativeCompilationReceiver.receive("```json\n$raw\n```").notes.isNotEmpty())
    }

    @Test fun ambiguousOrTruncatedOutputIsNeverRepairedOrSelected() {
        val raw = Json.encodeToString(draft)
        listOf("结果：$raw\n$raw", "$raw\n$raw", "[$raw]", "```json\n$raw", raw.dropLast(1),
            "结果：" + raw.dropLast(1), "解释 []：$raw").forEach { assertTrue(rejected(it).isNotEmpty()) }
    }

    @Test fun advisorySummaryCanDefaultOrShortenWithoutChangingProgram() {
        val tree = Json.encodeToJsonElement(draft).jsonObject
        listOf(null, JsonNull, JsonPrimitive("  "), JsonPrimitive("说明".repeat(1000))).forEach { summary ->
            val fields = tree.toMutableMap().apply { remove("summary"); if (summary != null) put("summary", summary) }
            val result = NativeCompilationReceiver.receive(JsonObject(fields).toString())
            assertEquals(program, result.draft.script)
            assertTrue(result.draft.summary.length in 1..1024)
            assertTrue(result.notes.any { it.sourcePath == "/summary" })
        }
        assertEquals("/summary", rejected("""{"summary":42}""").single().path)
    }

    @Test fun executableErrorsHavePrecisePathsWithoutEchoingPayloads() {
        val issues = rejected("""{"script":{"version":"1","modules":[{"id":"main","code":"PRIVATE_CODE","sourceIds":[7],"transformation":"ok","execute":"PRIVATE_CODE"}],"surfaces":[{"id":"s","module":"main","surface":"wrong"}]}}""")
        assertTrue(issues.any { it.path == "/script/version" && it.code == "COMPILER_FIELD_TYPE" })
        assertTrue(issues.any { it.path == "/script/modules/0/sourceIds/0" && it.code == "COMPILER_FIELD_TYPE" })
        assertTrue(issues.any { it.path == "/script/modules/0/execute" && it.code == "COMPILER_UNKNOWN_FIELD" })
        assertTrue(issues.any { it.path == "/script/surfaces/0/export" && it.code == "COMPILER_MISSING_FIELD" })
        assertTrue(issues.any { it.path == "/script/surfaces/0/surface" && it.code == "COMPILER_ENUM_VALUE" })
        assertFalse(issues.toString().contains("PRIVATE_CODE"))
        assertEquals("COMPILER_UNKNOWN_FIELD", rejected("""{"executeJs":"evil()"}""").single().code)
    }
}
