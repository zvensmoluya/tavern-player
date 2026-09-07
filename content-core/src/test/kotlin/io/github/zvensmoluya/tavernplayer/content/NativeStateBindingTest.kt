package io.github.zvensmoluya.tavernplayer.content

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NativeStateBindingTest {
    private val binding = NativeStateBinding("score", NativeStateSource.MVU, "/stat_data/数值", ConversationStateValueType.NUMBER)
    private val adaptation = NativeAdaptation(sourceSha256 = "a".repeat(64), mvu = NativeMvuProgram("schema"),
        stateBindings = listOf(binding), status = NativeStatusView(items = listOf(NativeStatusItem("score", "值", 0.0, 100.0))))

    @Test fun validatesReadTypesWithoutRequiringCopiedDefinitionsOrInitialValues() {
        val validator = NativeAdaptationValidator()
        val restored = Json.decodeFromString<NativeAdaptation>(Json.encodeToString(adaptation))
        assertTrue(validator.validate(restored).issues.toString(), validator.validate(restored).valid)
        val bag = restored.copy(stateBindings = listOf(binding.copy(type = ConversationStateValueType.RECORD)), status = null,
            collections = listOf(NativeCollectionView("bag", "Bag", "score", shape = NativeCollectionShape.OBJECT,
                fields = listOf(NativeCollectionField("name", "Name", entryKey = true), NativeCollectionField("quantity", "Quantity", path = "/数量")))))
        assertTrue(validator.validate(bag).issues.toString(), validator.validate(bag).valid)
        assertFalse(validator.validate(bag.copy(collections = bag.collections.map { it.copy(shape = NativeCollectionShape.ARRAY) })).valid)
        assertFalse(validator.validate(restored.copy(stateBindings = listOf(binding.copy(type = ConversationStateValueType.STRING)))).valid)
    }

    @Test fun rejectsAliasesThatShadowStateMissingHostsAndInvalidPaths() {
        val state = ConversationStateDefinition("score", type = ConversationStateValueType.NUMBER, initialValue = JsonPrimitive(3))
        for (bad in listOf(adaptation.copy(mvu = null), adaptation.copy(stateBindings = listOf(binding, binding)),
            adaptation.copy(state = listOf(state)), adaptation.copy(stateBindings = listOf(binding.copy(path = "stat_data.score"))),
            adaptation.copy(stateBindings = listOf(binding.copy(source = NativeStateSource.PLAYER))))) {
            assertFalse(NativeAdaptationValidator().validate(bad).valid)
        }
        val player = adaptation.copy(mvu = null, state = listOf(state.copy(key = "actual")),
            stateBindings = listOf(binding.copy(source = NativeStateSource.PLAYER, path = "/actual")))
        assertTrue(NativeAdaptationValidator().validate(player).valid)
    }

    @Test fun compilerPreservesPathsAndRejectsDuplicatedMvuBusinessState() {
        val card = CharacterAsset(id = "a".repeat(64), name = "Sample", rawCard = buildJsonObject {
            putJsonObject("data") { putJsonObject("extensions") { putJsonObject("tavern_helper") {
                putJsonArray("scripts") { add(buildJsonObject { put("content", "schema"); put("enabled", true) }) }
            } } }
        })
        val draft = NativeCompilationDraft("只读绑定", mvu = NativeCompilationMvu("script0"),
            stateBindings = listOf(binding), status = adaptation.status)
        val compiler = NativeAdaptationCompiler()
        val result = compiler.complete(card, Json.encodeToString(draft), emptySet()) as NativeCompilationResult.Ready
        assertEquals(listOf(binding), result.adaptation.stateBindings)
        assertTrue(result.adaptation.state.isEmpty())
        assertTrue(result.adaptation.progressions.isEmpty())
        val copied = draft.copy(state = listOf(ConversationStateDefinition("copy", type = ConversationStateValueType.NUMBER, initialValue = JsonPrimitive(0))))
        assertTrue(compiler.complete(card, Json.encodeToString(copied), emptySet()) is NativeCompilationResult.Rejected)
    }
}
