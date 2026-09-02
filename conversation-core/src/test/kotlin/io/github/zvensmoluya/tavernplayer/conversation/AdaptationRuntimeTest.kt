package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.AdaptationAction
import io.github.zvensmoluya.tavernplayer.content.AdaptationActionType
import io.github.zvensmoluya.tavernplayer.content.AdaptationArtifact
import io.github.zvensmoluya.tavernplayer.content.AdaptationCompiler
import io.github.zvensmoluya.tavernplayer.content.AdaptationFormField
import io.github.zvensmoluya.tavernplayer.content.AdaptationFormFieldType
import io.github.zvensmoluya.tavernplayer.content.AdaptationFormOption
import io.github.zvensmoluya.tavernplayer.content.AdaptationMessageStateDialect
import io.github.zvensmoluya.tavernplayer.content.AdaptationMessageStateMapping
import io.github.zvensmoluya.tavernplayer.content.AdaptationMessageStateRule
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

    @Test
    fun `assistant update dialect ingests only complete whitelisted scalar operations`() {
        val base = artifact()
        val stateful = base.copy(
            requiredCapabilities = base.requiredCapabilities + "state.ingest",
            state = listOf(
                AdaptationStateDefinition("world-day", AdaptationStateType.NUMBER, JsonPrimitive(1)),
                AdaptationStateDefinition("world-location", AdaptationStateType.STRING, JsonPrimitive("家")),
            ),
            messageStateRules = listOf(
                AdaptationMessageStateRule(
                    AdaptationMessageStateDialect.UPDATE_VARIABLE_SET_V1,
                    listOf(
                        AdaptationMessageStateMapping("世界.日期", "world-day"),
                        AdaptationMessageStateMapping("世界.地点", "world-location"),
                    ),
                ),
            ),
        )
        val initial = AdaptationRuntime().initialState(stateful)
        val source = """
            正文中的 _.set('世界.日期', 1, 99); 不应执行。
            <UpdateVariable>
              <Analysis>ignored</Analysis>
              _.set('世界.日期', 1, 2); // 时间流逝
              _.set('世界.地点', '家', '客厅, 东侧');
              _.set('世界.日期', previousValue, 7);
              _.set('世界.日期', 2, fetch('bad'));
              _.set('世界.日期', 2, '9');
              _.set('世界.地点', '家', 99);
              _.set('未知.字段', 0, 7);
            </UpdateVariable>
            <UpdateVariable>
              _.set('世界.日期', 2, 3);
        """.trimIndent()

        val result = AdaptationRuntime().ingestAssistantMessage(stateful, source, initial)

        assertEquals(2, result.appliedUpdates)
        assertEquals(2.0, (result.runtimeState.adaptationState["world-day"] as JsonPrimitive).double, 0.0)
        assertEquals("客厅, 东侧", (result.runtimeState.adaptationState["world-location"] as JsonPrimitive).content)
        assertEquals(1.0, (initial.adaptationState["world-day"] as JsonPrimitive).double, 0.0)
    }

    @Test
    fun `native display template resolves state and identities`() {
        val rendered = renderAdaptationText(
            "{{user}}：{{state.world-day}} / {{char}}",
            mapOf("world-day" to JsonPrimitive(3)),
            userName = "Traveler",
            characterName = "Mara",
        )

        assertEquals("Traveler：3 / Mara", rendered)
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
