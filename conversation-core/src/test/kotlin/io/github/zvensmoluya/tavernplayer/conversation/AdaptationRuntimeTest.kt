package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.AdaptationAction
import io.github.zvensmoluya.tavernplayer.content.AdaptationActionType
import io.github.zvensmoluya.tavernplayer.content.AdaptationArtifact
import io.github.zvensmoluya.tavernplayer.content.AdaptationCompiler
import io.github.zvensmoluya.tavernplayer.content.AdaptationFormField
import io.github.zvensmoluya.tavernplayer.content.AdaptationFormFieldType
import io.github.zvensmoluya.tavernplayer.content.AdaptationFormOption
import io.github.zvensmoluya.tavernplayer.content.AdaptationStateDefinition
import io.github.zvensmoluya.tavernplayer.content.AdaptationStateType
import io.github.zvensmoluya.tavernplayer.content.AdaptationStatus
import io.github.zvensmoluya.tavernplayer.content.AdaptationTriggerType
import io.github.zvensmoluya.tavernplayer.content.AdaptationUiNode
import io.github.zvensmoluya.tavernplayer.content.AdaptationUiNodeType
import io.github.zvensmoluya.tavernplayer.content.AdaptationView
import io.github.zvensmoluya.tavernplayer.content.AdaptationViewPlacement
import io.github.zvensmoluya.tavernplayer.content.AdaptationViewTrigger
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptationRuntimeTest {
    @Test
    fun `form submission applies state transaction and returns draft effect`() {
        val runtime = AdaptationRuntime()
        val artifact = artifact()
        val initial = runtime.initialState(artifact)

        val result = runtime.execute(
            artifact,
            AdaptationFormSubmission(
                viewId = "opening-form",
                values = mapOf("name" to listOf("Mira"), "reasons" to listOf("family", "chat")),
            ),
            initial,
        ) as AdaptationExecutionResult.Success

        assertEquals(1.0, (result.runtimeState.adaptationState["submissions"] as JsonPrimitive).double, 0.0)
        assertEquals("Name: Mira\nReasons: family、chat", result.effects.single().value)
        assertEquals(0.0, (initial.adaptationState["submissions"] as JsonPrimitive).double, 0.0)
    }

    @Test
    fun `invalid form fails without returning a mutated state`() {
        val runtime = AdaptationRuntime()
        val artifact = artifact()
        val initial = runtime.initialState(artifact)

        val result = runtime.execute(
            artifact,
            AdaptationFormSubmission("opening-form", mapOf("name" to listOf(""), "reasons" to listOf("invented"))),
            initial,
        )

        assertTrue(result is AdaptationExecutionResult.Failure)
        assertEquals(0.0, (initial.adaptationState["submissions"] as JsonPrimitive).double, 0.0)
    }

    @Test
    fun `message matching uses immutable source text markers`() {
        val view = artifact().views.single()

        assertTrue(view.matchesMessage("  <GAMESTART/>  "))
    }

    @Test
    fun `draft template resolves conversation identities`() {
        val original = artifact()
        val artifact = original.copy(
            views = listOf(
                original.views.single().copy(
                    submitActions = listOf(
                        AdaptationAction(
                            AdaptationActionType.CHAT_SET_DRAFT,
                            template = "{{user}} meets {{char}} as {{form.name}}",
                        ),
                    ),
                ),
            ),
        )

        val result = AdaptationRuntime().execute(
            artifact,
            AdaptationFormSubmission("opening-form", mapOf("name" to listOf("Mira"))),
            AdaptationRuntime().initialState(artifact),
            userName = "Traveler",
            characterName = "Mara",
        ) as AdaptationExecutionResult.Success

        assertEquals("Traveler meets Mara as Mira", result.effects.single().value)
    }

    private fun artifact() = AdaptationArtifact(
        sourceSha256 = "a".repeat(64),
        compiler = AdaptationCompiler("fixture", "1"),
        status = AdaptationStatus.FULL,
        requiredCapabilities = listOf("ui.native", "chat.setDraft", "state.write"),
        state = listOf(AdaptationStateDefinition("submissions", AdaptationStateType.NUMBER, JsonPrimitive(0))),
        views = listOf(
            AdaptationView(
                id = "opening-form",
                placement = AdaptationViewPlacement.MESSAGE_REPLACEMENT,
                trigger = AdaptationViewTrigger(AdaptationTriggerType.MESSAGE_EXACT, "<GAMESTART/>"),
                nodes = listOf(
                    AdaptationUiNode(
                        id = "form",
                        type = AdaptationUiNodeType.FORM,
                        fields = listOf(
                            AdaptationFormField("name", AdaptationFormFieldType.TEXT, "Name", required = true),
                            AdaptationFormField(
                                "reasons",
                                AdaptationFormFieldType.MULTI_SELECT,
                                "Reasons",
                                options = listOf(
                                    AdaptationFormOption("family"),
                                    AdaptationFormOption("chat"),
                                ),
                            ),
                        ),
                    ),
                ),
                submitActions = listOf(
                    AdaptationAction(AdaptationActionType.STATE_INCREMENT, target = "submissions", value = "1"),
                    AdaptationAction(
                        AdaptationActionType.CHAT_SET_DRAFT,
                        template = "Name: {{form.name}}\nReasons: {{form.reasons}}",
                    ),
                ),
            ),
        ),
    )
}
