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

    @Test
    fun `rejects form actions that write state outside the message timeline`() {
        val original = openingFormArtifact()
        val artifact = original.copy(
            requiredCapabilities = listOf("ui.native", "state.write"),
            state = listOf(AdaptationStateDefinition("visits", AdaptationStateType.NUMBER, JsonPrimitive(0))),
            views = original.views.map { view ->
                view.copy(
                    submitActions = listOf(
                        AdaptationAction(AdaptationActionType.STATE_INCREMENT, target = "visits", value = "1"),
                    ),
                )
            },
        )

        val result = AdaptationValidator().validate(artifact)

        assertFalse(result.valid)
        assertTrue(result.issues.any { it.code == "FORM_STATE_WRITE_UNSUPPORTED" })
        assertTrue(result.issues.any { it.code == "UNSUPPORTED_CAPABILITY" })
    }

    @Test
    fun `rejects quoted and non-finite typed state values`() {
        val artifact = openingFormArtifact().copy(
            state = listOf(
                AdaptationStateDefinition("quoted-number", AdaptationStateType.NUMBER, JsonPrimitive("7")),
                AdaptationStateDefinition("quoted-boolean", AdaptationStateType.BOOLEAN, JsonPrimitive("true")),
                AdaptationStateDefinition("infinite-number", AdaptationStateType.NUMBER, JsonPrimitive(Double.POSITIVE_INFINITY)),
            ),
        )

        val result = AdaptationValidator().validate(artifact)

        assertFalse(result.valid)
        assertTrue(result.issues.count { it.code == "STATE_TYPE_MISMATCH" } == 3)
    }

    @Test
    fun `accepts bounded assistant message state ingestion`() {
        val artifact = openingFormArtifact().copy(
            requiredCapabilities = listOf("ui.native", "chat.setDraft", "state.ingest"),
            state = listOf(AdaptationStateDefinition("visits", AdaptationStateType.NUMBER, JsonPrimitive(1))),
            messageStateRules = listOf(
                AdaptationMessageStateRule(
                    dialect = AdaptationMessageStateDialect.UPDATE_VARIABLE_SET_V1,
                    mappings = listOf(AdaptationMessageStateMapping("game.visits", "visits")),
                ),
            ),
            views = openingFormArtifact().views.map { view ->
                view.copy(nodes = view.nodes + AdaptationUiNode("visits-text", AdaptationUiNodeType.TEXT, text = "{{state.visits}}"))
            },
        )

        val result = AdaptationValidator().validate(artifact)

        assertTrue(result.issues.joinToString { "${it.path}: ${it.message}" }, result.valid)
    }

    @Test
    fun `rejects duplicate unsafe and unknown message state mappings`() {
        val artifact = openingFormArtifact().copy(
            requiredCapabilities = listOf("ui.native", "chat.setDraft", "state.ingest"),
            state = listOf(AdaptationStateDefinition("visits", AdaptationStateType.NUMBER, JsonPrimitive(1))),
            messageStateRules = listOf(
                AdaptationMessageStateRule(
                    AdaptationMessageStateDialect.UPDATE_VARIABLE_SET_V1,
                    listOf(
                        AdaptationMessageStateMapping("game.visits", "visits"),
                        AdaptationMessageStateMapping("game.visits", "missing"),
                        AdaptationMessageStateMapping("bad path", "visits"),
                    ),
                ),
            ),
        )

        val result = AdaptationValidator().validate(artifact)

        assertFalse(result.valid)
        assertTrue(result.issues.any { it.code == "DUPLICATE_STATE_PATH" })
        assertTrue(result.issues.any { it.code == "UNKNOWN_STATE" })
        assertTrue(result.issues.any { it.code == "INVALID_STATE_PATH" })
        assertTrue(result.issues.any { it.code == "DUPLICATE_STATE_TARGET" })
    }

    @Test
    fun `binds model artifact protocol surface to program view observations`() {
        val artifact = openingFormArtifact().copy(
            requiredCapabilities = listOf("ui.native", "chat.setDraft", "state.ingest"),
            state = listOf(AdaptationStateDefinition("visits", AdaptationStateType.NUMBER, JsonPrimitive(1))),
            messageStateRules = listOf(
                AdaptationMessageStateRule(
                    AdaptationMessageStateDialect.UPDATE_VARIABLE_SET_V1,
                    listOf(AdaptationMessageStateMapping("invented.path", "visits")),
                ),
            ),
        ).let { value ->
            value.copy(views = value.views.map { it.copy(trigger = AdaptationViewTrigger(AdaptationTriggerType.MESSAGE_EXACT, "<INVENTED/>")) })
        }
        val programView = ProgramView(
            sourceSha256 = artifact.sourceSha256,
            programBlocks = listOf(
                ProgramBlock(
                    id = "markup-1",
                    kind = ProgramBlockKind.ACTIVE_MARKUP,
                    sourcePath = "data.extensions.regex_scripts[0].replaceString",
                    name = "Opening",
                    language = "html",
                    content = "<form></form>",
                    originalSha256 = "b".repeat(64),
                    triggerPattern = "<GAMESTART/>",
                ),
            ),
            stateProtocolHints = listOf(
                ProgramStateProtocolHint(
                    dialect = AdaptationMessageStateDialect.UPDATE_VARIABLE_SET_V1.name,
                    variableName = "stat_data",
                    values = listOf(ProgramStateValueHint("game.visits", AdaptationStateType.NUMBER, JsonPrimitive(0))),
                ),
            ),
        )

        val result = AdaptationValidator().validateAgainstProgramView(artifact, programView)

        assertFalse(result.valid)
        assertTrue(result.issues.any { it.code == "UNOBSERVED_TRIGGER" })
        assertTrue(result.issues.any { it.code == "UNOBSERVED_STATE_PATH" })
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
