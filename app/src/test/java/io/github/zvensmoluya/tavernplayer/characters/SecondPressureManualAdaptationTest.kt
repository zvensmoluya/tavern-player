package io.github.zvensmoluya.tavernplayer.characters

import io.github.zvensmoluya.tavernplayer.content.*
import io.github.zvensmoluya.tavernplayer.conversation.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class SecondPressureManualAdaptationTest {
    @Test fun `all derived relationship stages reach the prompt with source speaker context`() {
        val source = sequenceOf(File("source/real复杂压测卡.png"), File("../source/real复杂压测卡.png")).firstOrNull { it.isFile }
        assumeTrue("second source is required", source != null)
        val imported = (CharacterCardImporter().import(source!!.readBytes(), source.name) as CharacterImportResult.Ready).character
        val adaptation = javaClass.classLoader!!.getResourceAsStream("native-adaptation/second-pressure-manual.json")!!.bufferedReader().use {
            Json.decodeFromString<NativeAdaptation>(it.readText())
        }
        val character = imported.copy(nativeAdaptation = adaptation)
        val selection = adaptation.worldBookTextSelections.single()
        val entry = imported.worldBooks.single().entries.single { it.id == selection.entryId }
        val runtime = NativeAdaptationRuntime()
        var state = runtime.initialState(adaptation)
        val steps = listOf(0 to false, 29 to false, 30 to false, 84 to false, 85 to false, 100 to true, 50 to true, 0 to false)
        val expectedStages = listOf("暗流涌动", "暗流涌动", "两难挣扎", "两难挣扎", "心防动摇(锁中)", "清醒接纳", "两难挣扎", "暗流涌动")
        steps.zip(expectedStages).forEach { (step, expectedStage) ->
            val (affinity, reconciled) = step
            val update = "<UpdateVariable><JSONPatch>[" +
                "{\"op\":\"replace\",\"path\":\"/角色/云知意/好感度\",\"value\":$affinity}," +
                "{\"op\":\"replace\",\"path\":\"/角色/云知意/\$已触发破镜重圆事件\",\"value\":$reconciled}]</JSONPatch></UpdateVariable>"
            state = runtime.ingestAssistantMessage(adaptation, update, state).runtimeState
            assertEquals(JsonPrimitive(expectedStage), state.conversationState.values["relationship-stage"])
            val result = PromptCompiler().compile(NormalGenerationInput(
                character = character.snapshot(), persona = Persona("p", "旅人"),
                history = listOf(ConversationMessage("u", MessageRole.USER, "在院中品茶。", "旅人")),
                preset = BuiltInPresets.default, runtimeState = state, conversationId = "stages", generationId = "g",
                modelId = "test", modelContextTokens = 65536,
            ))
            assertTrue("$expectedStage: $result", result is CompilationResult.Success)
            val plan = (result as CompilationResult.Success).plan
            val text = plan.messages.joinToString("\n") { it.content }
            val selected = selection.cases.single { it.stateValue == expectedStage }
            val prefix = checkNotNull(selection.sourcePrefix).let { entry.content.substring(it.start, it.endExclusive) }
            val suffix = checkNotNull(selection.sourceSuffix).let { entry.content.substring(it.start, it.endExclusive) }
            val expected = prefix + entry.content.substring(selected.sourceStart, selected.sourceEndExclusive) + suffix
            assertTrue("Expected branch missing: $expectedStage", text.contains(expected))
            assertEquals(1, Regex("<cloud_attitude>").findAll(text).count())
            selection.cases.filter { it != selected }.forEach {
                assertFalse(text.contains(entry.content.substring(it.sourceStart, it.sourceEndExclusive)))
            }
            assertEquals(state.conversationState, plan.runtimeState.conversationState)
            assertTrue(plan.trace.any { it.stage == "native-world-book-text" && entry.id in it.sourceIds })
        }
        assertTrue(entry.content.contains("<%"))
    }

    @Test fun `second source fixture retains its state constraints progression and message panel`() {
        val source = sequenceOf(File("source/real复杂压测卡.png"), File("../source/real复杂压测卡.png")).firstOrNull { it.isFile }
        assumeTrue("held-back source is required", source != null)
        val character = (CharacterCardImporter().import(source!!.readBytes(), source.name) as CharacterImportResult.Ready).character
        val adaptation = javaClass.classLoader!!.getResourceAsStream("native-adaptation/second-pressure-manual.json")!!.bufferedReader().use {
            Json.decodeFromString<NativeAdaptation>(it.readText())
        }
        val validation = NativeAdaptationValidator().validate(adaptation, character.sourceSha256, worldBooks = character.worldBooks)
        assertTrue(validation.issues.toString(), validation.valid)
        assertEquals(9, adaptation.state.size)
        val runtime = NativeAdaptationRuntime()
        val initial = runtime.initialState(adaptation)
        val sourceUpdate = "<UpdateVariable><JSONPatch>[{\"op\":\"replace\",\"path\":\"/角色/云知意/好感度\",\"value\":85}]</JSONPatch></UpdateVariable>"
        val locked = runtime.ingestAssistantMessage(adaptation, sourceUpdate, initial)
        assertEquals(JsonPrimitive("心防动摇(锁中)"), locked.runtimeState.conversationState.values["relationship-stage"])
        val unlocked = runtime.ingestAssistantMessage(adaptation, "<UpdateVariable><JSONPatch>[{\"op\":\"replace\",\"path\":\"/角色/云知意/\$已触发破镜重圆事件\",\"value\":true}]</JSONPatch></UpdateVariable>", locked.runtimeState)
        assertEquals(JsonPrimitive("清醒接纳"), unlocked.runtimeState.conversationState.values["relationship-stage"])
        val panel = "正文。<suihan_panel><状态>雨</状态><日历简述>午时</日历简述><日历折叠>细雨</日历折叠><心境波澜>犹豫</心境波澜><袖中语>我该如何开口</袖中语></suihan_panel>"
        assertEquals("正文。", runtime.projectAssistantMessage(adaptation, panel).narrativeText)
        assertEquals(5, NativeMessagePanels.project(adaptation, panel).panels.single().fields.size)
    }
}
