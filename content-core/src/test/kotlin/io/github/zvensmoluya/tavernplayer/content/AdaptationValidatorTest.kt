package io.github.zvensmoluya.tavernplayer.content

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptationValidatorTest {
    @Test
    fun `accepts a bounded native opening form`() {
        val artifact = openingFormArtifact()

        val result = AdaptationValidator().validate(artifact, artifact.sourceSha256)

        assertTrue(result.issues.joinToString { "${it.path}: ${it.message}" }, result.valid)
    }

    @Test
    fun `rejects source mismatch unknown powers and external io`() {
        val artifact = openingFormArtifact().copy(
            requiredCapabilities = listOf("ui.native", "chat.setDraft", "network.fetch"),
            views = openingFormArtifact().views.map { view ->
                view.copy(
                    submitActions = listOf(
                        AdaptationAction(
                            type = AdaptationActionType.CHAT_SET_DRAFT,
                            template = "send {{form.name}} to https://example.test",
                        ),
                    ),
                )
            },
        )

        val result = AdaptationValidator().validate(artifact, "b".repeat(64))

        assertFalse(result.valid)
        assertTrue(result.issues.any { it.code == "SOURCE_HASH_MISMATCH" })
        assertTrue(result.issues.any { it.code == "UNSUPPORTED_CAPABILITY" })
        assertTrue(result.issues.any { it.code == "EXTERNAL_IO_FORBIDDEN" })
    }

    @Test
    fun `rejects invalid state bindings and field references`() {
        val artifact = openingFormArtifact().copy(
            state = listOf(AdaptationStateDefinition("visits", AdaptationStateType.NUMBER, JsonPrimitive("not a number"))),
            views = openingFormArtifact().views.map { view ->
                view.copy(
                    nodes = view.nodes + AdaptationUiNode(
                        id = "missing-status",
                        type = AdaptationUiNodeType.STATUS,
                        stateKey = "missing",
                    ),
                    submitActions = listOf(
                        AdaptationAction(AdaptationActionType.CHAT_SET_DRAFT, template = "{{form.unknown}}"),
                    ),
                )
            },
        )

        val result = AdaptationValidator().validate(artifact)

        assertFalse(result.valid)
        assertTrue(result.issues.any { it.code == "STATE_TYPE_MISMATCH" })
        assertTrue(result.issues.any { it.code == "UNKNOWN_STATE" })
        assertTrue(result.issues.any { it.code == "UNKNOWN_TEMPLATE_REFERENCE" })
    }

    private fun openingFormArtifact() = AdaptationArtifact(
        sourceSha256 = "a".repeat(64),
        compiler = AdaptationCompiler("fixture", "1"),
        status = AdaptationStatus.FULL,
        requiredCapabilities = listOf("ui.native", "chat.setDraft"),
        views = listOf(
            AdaptationView(
                id = "opening-form",
                title = "Appointment",
                placement = AdaptationViewPlacement.MESSAGE_REPLACEMENT,
                trigger = AdaptationViewTrigger(AdaptationTriggerType.MESSAGE_EXACT, "<GAMESTART/>"),
                nodes = listOf(
                    AdaptationUiNode(
                        id = "form",
                        type = AdaptationUiNodeType.FORM,
                        fields = listOf(
                            AdaptationFormField("name", AdaptationFormFieldType.TEXT, "Name", required = true),
                            AdaptationFormField(
                                "reason",
                                AdaptationFormFieldType.MULTI_SELECT,
                                "Reason",
                                options = listOf(AdaptationFormOption("chat", "Chat")),
                            ),
                        ),
                    ),
                ),
                submitLabel = "Confirm",
                submitActions = listOf(
                    AdaptationAction(
                        type = AdaptationActionType.CHAT_SET_DRAFT,
                        template = "Name: {{form.name}}\nReason: {{form.reason}}",
                    ),
                ),
            ),
        ),
    )
}
