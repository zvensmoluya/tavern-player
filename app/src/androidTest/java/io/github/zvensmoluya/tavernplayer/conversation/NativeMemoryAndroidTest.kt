package io.github.zvensmoluya.tavernplayer.conversation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zvensmoluya.tavernplayer.content.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeMemoryAndroidTest {
    /** Run in a fresh instrumentation process after force-stop; expected checkpoint is explicit test input. */
    @Test fun savedMemoryCheckpointSurvivesProcessRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expectedFile = java.io.File(context.cacheDir, "native-memory-checkpoint.json")
        assumeTrue("explicit live memory checkpoint is required", expectedFile.isFile)
        val expected = Json.decodeFromString<ConversationRecord>(expectedFile.readText())
        val restored = checkNotNull(ConversationRepository(context.filesDir, PromptCompiler()).get(expected.id))
        assertEquals(3, restored.runtimeState.memories.size)
        assertEquals(expected.runtimeState, restored.runtimeState)
        assertEquals(expected.turns, restored.turns)
        assertEquals(restored.runtimeState.memories, restored.turns.last().selected.runtimeStateAfter?.memories)
    }

    @Test fun fixedPlaceholdersWorkOnAndroidWithoutRecursiveExpansion() {
        val entry = WorldBookEntryDefinition("analysis", content = "评估{{user}}与{{char}}；保留{{setvar::x::1}}。", enabled = false)
        val book = WorldBookDefinition("source", entries = listOf(entry))
        val adaptation = NativeAdaptation(sourceSha256 = "a".repeat(64), memories = listOf(NativeMemoryDefinition(
            "relationship", "关系分析", NativeWorldBookReference(book.id, entry.id, NativeWorldBookTextSelectionValidator.sha256(entry.content)),
            firstReply = 2, everyReplies = 15)))
        val character = CharacterAsset("card", sourceSha256 = adaptation.sourceSha256, name = "向导", firstMessage = "出发。",
            worldBooks = listOf(book), nativeAdaptation = adaptation)
        val runtime = NativeAdaptationRuntime().initialState(adaptation)
        val plan = GenerationPlan(emptyList(), 8192, 32768, "", "preset", "Preset", diagnostics = emptyList(), trace = emptyList())
        val record = ConversationRecord(id = "android-memory", character = character.snapshot(), persona = Persona("p", "旅人{{char}}"),
            turns = listOf(MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ASSISTANT).mapIndexed { index, role ->
                ConversationTurn("turn-$index", role, listOf(MessageVariant("variant-$index", ConversationMessage("message-$index", role,
                    "普通旅行对话。", "旅人"), generationPlan = if (index == 2) plan else null, runtimeStateBefore = runtime, runtimeStateAfter = runtime)))
            }, runtimeState = runtime, createdAtEpochMillis = 0, updatedAtEpochMillis = 0)
        val request = checkNotNull(NativeMemoryController.prepare(record, "relationship"))
        val evidence = Json.parseToJsonElement(request.plan.messages.last().content).jsonObject
        assertEquals("评估旅人{{char}}与向导；保留{{setvar::x::1}}。", evidence.getValue("analysisSpecification").jsonPrimitive.content)
        assertNull(request.plan.nativeAdaptation)
    }
}
