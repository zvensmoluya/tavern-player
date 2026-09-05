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
