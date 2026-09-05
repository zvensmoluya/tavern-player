package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.modelgateway.AuthScheme
import io.github.zvensmoluya.modelgateway.GatewayException
import io.github.zvensmoluya.modelgateway.ModelProtocol
import io.github.zvensmoluya.modelgateway.catalog.ModelCatalog
import io.github.zvensmoluya.tavernplayer.connections.ConnectionRepository
import io.github.zvensmoluya.tavernplayer.connections.ConnectionStateStore
import io.github.zvensmoluya.tavernplayer.connections.CredentialStore
import io.github.zvensmoluya.tavernplayer.connections.GatewayAppState
import io.github.zvensmoluya.tavernplayer.connections.ModelCache
import io.github.zvensmoluya.tavernplayer.connections.ModelTokenLimits
import io.github.zvensmoluya.tavernplayer.connections.StoredConnection
import io.github.zvensmoluya.tavernplayer.content.RegexDefinition
import io.github.zvensmoluya.tavernplayer.content.RegexPlacement
import io.github.zvensmoluya.tavernplayer.content.PresetAsset
import io.github.zvensmoluya.tavernplayer.content.AssistantStateAdapterDefinition
import io.github.zvensmoluya.tavernplayer.content.AssistantStateMapping
import io.github.zvensmoluya.tavernplayer.content.ConversationStateDefinition
import io.github.zvensmoluya.tavernplayer.content.ConversationStateValueType
import io.github.zvensmoluya.tavernplayer.content.LegacyStateDialect
import io.github.zvensmoluya.tavernplayer.content.NativeAdaptation
import io.github.zvensmoluya.tavernplayer.content.WorldBookDefinition
import io.github.zvensmoluya.tavernplayer.content.WorldBookEntryDefinition
import io.github.zvensmoluya.tavernplayer.presets.ActivePresetSource
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `completed stream appends user once and records response metadata and latest trace`() = runTest {
        val generator = FakeGenerator { connection, _ ->
            flow {
                emit(GenerationEvent.RequestPrepared(preview(connection)))
                emit(GenerationEvent.ReasoningDelta("思考"))
                emit(GenerationEvent.ReasoningSignature("signature"))
                emit(GenerationEvent.TextDelta("欢迎回来。"))
                emit(GenerationEvent.Usage(GenerationUsage(10, 4, 14)))
                emit(GenerationEvent.Finished("stop"))
            }
        }
        val viewModel = viewModel(generator)

        viewModel.updateInput("我想住一晚。")
        viewModel.send()

        val state = viewModel.uiState.value
        assertFalse(state.running)
        assertEquals(listOf(MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ASSISTANT), state.messages.map { it.message.role })
        assertEquals("欢迎回来。", state.messages.last().message.content)
        assertEquals("思考", state.messages.last().message.reasoning.single().text)
        assertEquals("signature", state.messages.last().message.reasoning.single().signature)
        assertEquals(ChatMessageStatus.COMPLETE, state.messages.last().status)
        assertEquals("stop", state.messages.last().metadata?.finishReason)
        assertEquals(14L, state.lastTrace?.usage?.totalTokens)
        assertNotNull(state.lastTrace?.providerPreview)
        assertTrue(state.regenerateAvailable)
    }

    @Test
    fun `completed assistant update dialect commits conversation state`() = runTest {
        val adaptation = dayAdaptation()
        val generator = FakeGenerator { _, _ ->
            flow {
                emit(GenerationEvent.TextDelta("正文\n<UpdateVariable>\n_.set('世界.日期', 1, 2);"))
                emit(GenerationEvent.TextDelta("\n</UpdateVariable>"))
                emit(GenerationEvent.Finished("stop"))
            }
        }
        val viewModel = viewModel(generator, DemoConversationContent.character.copy(nativeAdaptation = adaptation))

        viewModel.updateInput("继续")
        viewModel.send()

        assertEquals(2.0, (viewModel.uiState.value.conversationState["world-day"] as JsonPrimitive).double, 0.0)
        assertEquals("正文", viewModel.uiState.value.messages.last().message.content)
        assertTrue(viewModel.uiState.value.messages.last().message.sourceText.orEmpty().contains("<UpdateVariable>"))
    }

    @Test
    fun `separate state confirmation is persisted and applied without changing assistant prose`() = runTest {
        val adaptation = dayAdaptation()
        val confirmation = "<UpdateVariable>\n_.set('世界.日期', 1, 2);\n</UpdateVariable>"
        val generator = FakeGenerator { _, _ ->
            flow {
                emit(GenerationEvent.TextDelta("只有正文。"))
                emit(GenerationEvent.AssistantStateConfirmed(confirmation))
                emit(GenerationEvent.Finished("stop"))
            }
        }
        val viewModel = viewModel(generator, DemoConversationContent.character.copy(nativeAdaptation = adaptation))

        viewModel.updateInput("继续")
        viewModel.send()

        val message = viewModel.uiState.value.messages.last().message
        assertEquals("只有正文。", message.content)
        assertEquals("只有正文。", message.sourceText)
        assertEquals(confirmation, message.stateConfirmation)
        assertEquals(2.0, (viewModel.uiState.value.conversationState["world-day"] as JsonPrimitive).double, 0.0)
    }

    @Test
    fun `failed assistant response does not commit its conversation state patch`() = runTest {
        val adaptation = dayAdaptation()
        val generator = FakeGenerator { _, _ ->
            flow {
                emit(GenerationEvent.TextDelta("正文\n<UpdateVariable>\n_.set('世界.日期', 1, 2);\n</UpdateVariable>"))
                error("stream failed")
            }
        }
        val viewModel = viewModel(generator, DemoConversationContent.character.copy(nativeAdaptation = adaptation))

        viewModel.updateInput("继续")
        viewModel.send()

        assertEquals(1.0, (viewModel.uiState.value.conversationState.getValue("world-day") as JsonPrimitive).double, 0.0)
        assertEquals(ChatMessageStatus.ERROR, viewModel.uiState.value.messages.last().status)
    }

    @Test
    fun `swipe restores conversation state and the selected snapshot reaches the next prompt`() = runTest {
        val adaptation = dayAdaptation()
        val plans = mutableListOf<GenerationPlan>()
        val generator = FakeGenerator { _, plan ->
            plans += plan
            val nextDay = plans.size + 1
            flow {
                emit(GenerationEvent.TextDelta("回复$nextDay\n<UpdateVariable>\n_.set('世界.日期', 1, $nextDay);\n</UpdateVariable>"))
                emit(GenerationEvent.Finished("stop"))
            }
        }
        val viewModel = viewModel(generator, DemoConversationContent.character.copy(nativeAdaptation = adaptation))

        viewModel.updateInput("继续")
        viewModel.send()
        assertEquals(1, plans.size)
        assertTrue(plans[0].projectedConversationState().contains("\"world-day\":1"))
        assertEquals(2.0, (viewModel.uiState.value.conversationState.getValue("world-day") as JsonPrimitive).double, 0.0)

        viewModel.regenerate()
        assertEquals(2, plans.size)
        assertTrue(plans[1].projectedConversationState().contains("\"world-day\":1"))
        assertEquals(3.0, (viewModel.uiState.value.conversationState.getValue("world-day") as JsonPrimitive).double, 0.0)

        viewModel.previousVariant()
        assertEquals(2.0, (viewModel.uiState.value.conversationState.getValue("world-day") as JsonPrimitive).double, 0.0)
        viewModel.nextVariant()
        assertEquals(3.0, (viewModel.uiState.value.conversationState.getValue("world-day") as JsonPrimitive).double, 0.0)
        viewModel.previousVariant()

        viewModel.updateInput("下一轮")
        viewModel.send()
        assertEquals(3, plans.size)
        assertTrue(plans[2].projectedConversationState().contains("\"world-day\":2"))
    }

    @Test
    fun `world book overrides replay from the assistant checkpoint and follow the selected swipe`() = runTest {
        val directory = Files.createTempDirectory("tavern-chat-world-book-swipe").toFile()
        try {
            val compiler = PromptCompiler()
            var repositoryId = 0
            val conversations = ConversationRepository(
                directory,
                compiler,
                idFactory = { "world-book-repository-${repositoryId++}" },
                ioDispatcher = mainDispatcherRule.dispatcher,
            )
            val character = DemoConversationContent.character.copy(
                firstMessage = "opening",
                alternateFirstMessages = emptyList(),
                worldBooks = listOf(
                    WorldBookDefinition(
                        id = "book",
                        entries = listOf(
                            WorldBookEntryDefinition(id = "entry", content = "constant lore", constant = true),
                        ),
                    ),
                ),
            )
            val created = conversations.create(character, DemoConversationContent.persona, DemoConversationContent.preset)
            val enabled = created.runtimeState.copy(
                worldBookActivationOverrides = WorldBookActivationOverrides(books = mapOf("book" to true)),
            )
            val disabled = enabled.copy(
                worldBookActivationOverrides = WorldBookActivationOverrides(books = mapOf("book" to false)),
            )
            val userTurn = ConversationTurn(
                id = "seed-user-turn",
                role = MessageRole.USER,
                variants = listOf(
                    MessageVariant(
                        id = "seed-user-variant",
                        message = ConversationMessage("seed-user", MessageRole.USER, "choose", "Traveler"),
                        runtimeStateBefore = enabled,
                        projectionRuntimeStateBefore = enabled,
                        runtimeStateAfter = enabled,
                    ),
                ),
            )
            val assistantTurn = ConversationTurn(
                id = "seed-assistant-turn",
                role = MessageRole.ASSISTANT,
                variants = listOf(
                    MessageVariant(
                        id = "disabled-variant",
                        message = ConversationMessage("disabled-message", MessageRole.ASSISTANT, "disabled", character.promptName),
                        runtimeStateBefore = enabled,
                        projectionRuntimeStateBefore = enabled,
                        runtimeStateAfter = disabled,
                    ),
                    MessageVariant(
                        id = "enabled-variant",
                        message = ConversationMessage("enabled-message", MessageRole.ASSISTANT, "enabled", character.promptName),
                        runtimeStateBefore = enabled,
                        projectionRuntimeStateBefore = enabled,
                        runtimeStateAfter = enabled,
                    ),
                ),
            )
            val seeded = conversations.save(
                created.copy(
                    turns = created.turns + userTurn + assistantTurn,
                    runtimeState = disabled,
                ),
            )
            val plans = mutableListOf<GenerationPlan>()
            val generator = FakeGenerator { _, plan ->
                plans += plan
                flow {
                    emit(GenerationEvent.TextDelta("response"))
                    emit(GenerationEvent.Finished("stop"))
                }
            }
            var messageId = 0
            val viewModel = ChatViewModel(
                repository = repository(),
                compiler = compiler,
                generator = generator,
                conversationRepository = conversations,
                presetSource = FixedPresetSource(),
                characterAsset = character,
                idGenerator = { "world-book-message-${messageId++}" },
                projectionDispatcher = mainDispatcherRule.dispatcher,
            )
            viewModel.loadConversation(seeded.id)

            viewModel.regenerate()

            assertEquals(listOf("entry"), plans.single().activatedWorldBookEntries)

            viewModel.previousVariant()
            viewModel.previousVariant()
            viewModel.updateInput("continue from disabled candidate")
            viewModel.send()

            assertTrue(plans.last().activatedWorldBookEntries.isEmpty())
            assertEquals(
                false,
                conversations.get(seeded.id)?.runtimeState?.worldBookActivationOverrides?.books?.get("book"),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `history restart discards future world book overrides and restores its checkpoint`() = runTest {
        val directory = Files.createTempDirectory("tavern-chat-world-book-history").toFile()
        try {
            val compiler = PromptCompiler()
            var repositoryId = 0
            val conversations = ConversationRepository(
                directory,
                compiler,
                idFactory = { "world-book-history-repository-${repositoryId++}" },
                ioDispatcher = mainDispatcherRule.dispatcher,
            )
            val character = DemoConversationContent.character.copy(
                firstMessage = "opening",
                alternateFirstMessages = emptyList(),
                worldBooks = listOf(
                    WorldBookDefinition(
                        id = "book",
                        entries = listOf(
                            WorldBookEntryDefinition(id = "entry", content = "constant lore", constant = true),
                        ),
                    ),
                ),
            )
            val created = conversations.create(character, DemoConversationContent.persona, DemoConversationContent.preset)
            val disabled = created.runtimeState.copy(
                worldBookActivationOverrides = WorldBookActivationOverrides(books = mapOf("book" to false)),
            )
            val enabled = disabled.copy(
                worldBookActivationOverrides = WorldBookActivationOverrides(books = mapOf("book" to true)),
            )
            val firstUser = ConversationTurn(
                id = "first-user-turn",
                role = MessageRole.USER,
                variants = listOf(
                    MessageVariant(
                        id = "first-user-variant",
                        message = ConversationMessage("first-user", MessageRole.USER, "first", "Traveler"),
                        runtimeStateBefore = disabled,
                        projectionRuntimeStateBefore = disabled,
                        runtimeStateAfter = disabled,
                    ),
                ),
            )
            val firstAssistant = ConversationTurn(
                id = "first-assistant-turn",
                role = MessageRole.ASSISTANT,
                variants = listOf(
                    MessageVariant(
                        id = "first-assistant-variant",
                        message = ConversationMessage("first-assistant", MessageRole.ASSISTANT, "first reply", character.promptName),
                        runtimeStateBefore = disabled,
                        projectionRuntimeStateBefore = disabled,
                        runtimeStateAfter = disabled,
                    ),
                ),
            )
            val laterUser = ConversationTurn(
                id = "later-user-turn",
                role = MessageRole.USER,
                variants = listOf(
                    MessageVariant(
                        id = "later-user-variant",
                        message = ConversationMessage("later-user", MessageRole.USER, "later", "Traveler"),
                        runtimeStateBefore = disabled,
                        projectionRuntimeStateBefore = disabled,
                        runtimeStateAfter = enabled,
                    ),
                ),
            )
            val laterAssistant = ConversationTurn(
                id = "later-assistant-turn",
                role = MessageRole.ASSISTANT,
                variants = listOf(
                    MessageVariant(
                        id = "later-assistant-variant",
                        message = ConversationMessage("later-assistant", MessageRole.ASSISTANT, "later reply", character.promptName),
                        runtimeStateBefore = enabled,
                        projectionRuntimeStateBefore = enabled,
                        runtimeStateAfter = enabled,
                    ),
                ),
            )
            val seeded = conversations.save(
                created.copy(
                    turns = created.turns + firstUser + firstAssistant + laterUser + laterAssistant,
                    runtimeState = enabled,
                ),
            )
            val plans = mutableListOf<GenerationPlan>()
            val generator = FakeGenerator { _, plan ->
                plans += plan
                flow {
                    emit(GenerationEvent.TextDelta("replacement reply"))
                    emit(GenerationEvent.Finished("stop"))
                }
            }
            var messageId = 0
            val viewModel = ChatViewModel(
                repository = repository(),
                compiler = compiler,
                generator = generator,
                conversationRepository = conversations,
                presetSource = FixedPresetSource(),
                characterAsset = character,
                idGenerator = { "world-book-history-message-${messageId++}" },
                projectionDispatcher = mainDispatcherRule.dispatcher,
            )
            viewModel.loadConversation(seeded.id)

            viewModel.editMessage("first-user", "changed first", MessageEditMode.RESTART)

            assertEquals(3, viewModel.uiState.value.messages.size)
            assertTrue(plans.single().activatedWorldBookEntries.isEmpty())
            assertEquals(
                false,
                conversations.get(seeded.id)?.runtimeState?.worldBookActivationOverrides?.books?.get("book"),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `opening greetings are swipe variants but cannot be regenerated`() = runTest {
        val character = DemoConversationContent.character.copy(
            alternateFirstMessages = listOf("备用开场，{{user}}。"),
        )
        val viewModel = viewModel(FakeGenerator { _, _ -> flow { } }, character)

        assertEquals(2, viewModel.uiState.value.messages.single().variantCount)
        assertTrue(viewModel.uiState.value.variantNavigationAvailable)
        assertFalse(viewModel.uiState.value.regenerateAvailable)

        viewModel.nextVariant()

        assertEquals("备用开场，旅人。", viewModel.uiState.value.messages.single().message.content)
        assertEquals(1, viewModel.uiState.value.messages.single().variantIndex)
    }

    @Test
    fun `regenerate adds an assistant variant and switching it has no generation side effect`() = runTest {
        var response = 0
        val generator = FakeGenerator { _, _ ->
            flow {
                response += 1
                emit(GenerationEvent.TextDelta("回复$response"))
                emit(GenerationEvent.Finished("stop"))
            }
        }
        val viewModel = viewModel(generator)

        viewModel.updateInput("继续")
        viewModel.send()
        viewModel.regenerate()

        assertEquals(2, generator.calls)
        assertEquals(2, viewModel.uiState.value.messages.last().variantCount)
        assertEquals("回复2", viewModel.uiState.value.messages.last().message.content)

        viewModel.previousVariant()

        assertEquals(2, generator.calls)
        assertEquals("回复1", viewModel.uiState.value.messages.last().message.content)
    }

    @Test
    fun `regenerate replays from turn runtime and swipe restores each cached candidate state`() = runTest {
        val generator = FakeGenerator { _, _ ->
            flow {
                emit(GenerationEvent.TextDelta("{{incvar::answer}}"))
                emit(GenerationEvent.Finished("stop"))
            }
        }
        val viewModel = viewModel(generator)

        viewModel.updateInput("继续")
        viewModel.send()
        assertEquals("1", viewModel.uiState.value.messages.last().message.content)

        viewModel.regenerate()
        assertEquals("1", viewModel.uiState.value.messages.last().message.content)

        viewModel.previousVariant()
        viewModel.regenerate()
        assertEquals("1", viewModel.uiState.value.messages.last().message.content)
        assertEquals(3, generator.calls)
    }

    @Test
    fun `editing a user message truncates the future restores runtime and regenerates`() = runTest {
        var response = 0
        val generator = FakeGenerator { _, _ ->
            flow {
                response += 1
                val text = when (response) {
                    1 -> "{{setvar::answer::old}}one\n<UpdateVariable>\n_.set('世界.日期', 1, 2);\n</UpdateVariable>"
                    2 -> "later\n<UpdateVariable>\n_.set('世界.日期', 2, 3);\n</UpdateVariable>"
                    else -> "{{getvar::route}}/{{getvar::answer}}"
                }
                emit(GenerationEvent.TextDelta(text))
                emit(GenerationEvent.Finished("stop"))
            }
        }
        val viewModel = viewModel(generator, DemoConversationContent.character.copy(nativeAdaptation = dayAdaptation()))

        viewModel.updateInput("{{setvar::route::old}}first")
        viewModel.send()
        val firstUserId = viewModel.uiState.value.messages[1].message.id
        viewModel.updateInput("{{setvar::route::later}}second")
        viewModel.send()
        assertEquals(3.0, (viewModel.uiState.value.conversationState.getValue("world-day") as JsonPrimitive).double, 0.0)

        viewModel.editMessage(firstUserId, "{{setvar::route::new}}changed", MessageEditMode.RESTART)

        val state = viewModel.uiState.value
        assertEquals(3, state.messages.size)
        assertEquals("changed", state.messages[1].message.content)
        assertEquals("{{setvar::route::new}}changed", state.messages[1].message.sourceText)
        assertTrue(state.messages[1].edited)
        assertEquals("new/", state.messages.last().message.content)
        assertEquals(1.0, (state.conversationState.getValue("world-day") as JsonPrimitive).double, 0.0)
        assertEquals(3, generator.calls)
    }

    @Test
    fun `text-only edit preserves the future and current runtime state`() = runTest {
        var response = 0
        val generator = FakeGenerator { _, _ ->
            flow {
                response += 1
                val text = when (response) {
                    1 -> "first answer"
                    2 -> "later answer"
                    else -> "{{getvar::route}}"
                }
                emit(GenerationEvent.TextDelta(text))
                emit(GenerationEvent.Finished("stop"))
            }
        }
        val viewModel = viewModel(generator)

        viewModel.updateInput("{{setvar::route::old}}first")
        viewModel.send()
        val firstUserId = viewModel.uiState.value.messages[1].message.id
        viewModel.updateInput("{{setvar::route::later}}second")
        viewModel.send()
        val oldFutureIds = viewModel.uiState.value.messages.drop(2).map { it.message.id }

        viewModel.editMessage(firstUserId, "{{setvar::route::corrected}}changed", MessageEditMode.TEXT_ONLY)

        val edited = viewModel.uiState.value
        assertEquals(5, edited.messages.size)
        assertEquals("changed", edited.messages[1].message.content)
        assertTrue(edited.messages[1].edited)
        assertEquals(oldFutureIds, edited.messages.drop(2).map { it.message.id })

        viewModel.updateInput("check")
        viewModel.send()
        assertEquals("later", viewModel.uiState.value.messages.last().message.content)
    }

    @Test
    fun `editing an assistant message makes it the new fact and restores its projected state`() = runTest {
        var response = 0
        val generator = FakeGenerator { _, _ ->
            flow {
                response += 1
                val text = when (response) {
                    1 -> "{{setvar::mood::old}}original"
                    2 -> "later"
                    else -> "{{getvar::mood}}"
                }
                emit(GenerationEvent.TextDelta(text))
                emit(GenerationEvent.Finished("stop"))
            }
        }
        val viewModel = viewModel(generator)

        viewModel.updateInput("first")
        viewModel.send()
        val firstAssistantId = viewModel.uiState.value.messages[2].message.id
        viewModel.updateInput("second")
        viewModel.send()

        viewModel.editMessage(firstAssistantId, "{{setvar::mood::manual}}修正", MessageEditMode.RESTART)

        val edited = viewModel.uiState.value
        assertEquals(3, edited.messages.size)
        assertEquals("修正", edited.messages.last().message.content)
        assertEquals("{{setvar::mood::manual}}修正", edited.messages.last().message.sourceText)
        assertTrue(edited.messages.last().edited)
        assertTrue(edited.messages.last().message.reasoning.isEmpty())
        assertEquals(null, edited.lastTrace)

        viewModel.updateInput("continue")
        viewModel.send()
        assertEquals("manual", viewModel.uiState.value.messages.last().message.content)
    }

    @Test
    fun `reasoning preserves raw storage and uses a safe display projection`() = runTest {
        val character = DemoConversationContent.character.copy(
            regexScripts = listOf(
                RegexDefinition(
                    id = "reasoning-storage",
                    name = "Reasoning storage",
                    findRegex = "secret",
                    replaceString = "stored",
                    placements = setOf(RegexPlacement.REASONING),
                ),
                RegexDefinition(
                    id = "reasoning-display",
                    name = "Reasoning display",
                    findRegex = "stored",
                    replaceString = "shown",
                    placements = setOf(RegexPlacement.REASONING),
                    markdownOnly = true,
                ),
            ),
        )
        val generator = FakeGenerator { _, _ ->
            flow {
                emit(GenerationEvent.ReasoningDelta("secret"))
                emit(GenerationEvent.ReasoningSignature("opaque"))
                emit(GenerationEvent.TextDelta("正文"))
                emit(GenerationEvent.Finished("stop"))
            }
        }
        val viewModel = viewModel(generator, character)

        viewModel.updateInput("开始")
        viewModel.send()

        val assistant = viewModel.uiState.value.messages.last()
        assertEquals("secret", assistant.message.reasoning.single().text)
        assertEquals("opaque", assistant.message.reasoning.single().signature)
        assertEquals(listOf("shown"), assistant.displayReasoning)
    }

    @Test
    fun `reasoning-only empty response does not commit assistant output mutations`() = runTest {
        val character = DemoConversationContent.character.copy(
            regexScripts = listOf(
                RegexDefinition(
                    id = "reasoning-state",
                    name = "Reasoning state",
                    findRegex = "thought",
                    replaceString = "{{setvar::reasoning-side-effect::yes}}thought",
                    placements = setOf(RegexPlacement.REASONING),
                ),
            ),
        )
        var attempt = 0
        val generator = FakeGenerator { _, _ ->
            flow {
                attempt += 1
                if (attempt == 1) {
                    emit(GenerationEvent.ReasoningDelta("thought"))
                } else {
                    emit(GenerationEvent.TextDelta("{{getvar::reasoning-side-effect}}"))
                }
                emit(GenerationEvent.Finished("stop"))
            }
        }
        val viewModel = viewModel(generator, character)

        viewModel.updateInput("开始")
        viewModel.send()

        assertTrue(viewModel.uiState.value.retryAvailable)
        viewModel.retry()

        assertEquals(2, generator.calls)
        assertTrue(viewModel.uiState.value.retryAvailable)
        assertEquals(listOf(MessageRole.ASSISTANT, MessageRole.USER), viewModel.uiState.value.messages.map { it.message.role })
    }

    @Test
    fun `prompt runtime mutations are not committed when request preparation never succeeds`() = runTest {
        val directory = Files.createTempDirectory("tavern-chat-runtime").toFile()
        try {
            val compiler = PromptCompiler()
            var repositoryId = 0
            val conversations = ConversationRepository(
                directory,
                compiler,
                idFactory = { "repository-${repositoryId++}" },
                ioDispatcher = mainDispatcherRule.dispatcher,
            )
            val character = DemoConversationContent.character.copy(
                description = "{{setvar::planned::yes}}${DemoConversationContent.character.description}",
            )
            val created = conversations.create(character, DemoConversationContent.persona, DemoConversationContent.preset)
            var messageId = 0
            val viewModel = ChatViewModel(
                repository = repository(),
                compiler = compiler,
                generator = FakeGenerator { _, _ -> flow { throw IOException("before request") } },
                conversationRepository = conversations,
                presetSource = FixedPresetSource(),
                characterAsset = character,
                idGenerator = { "runtime-${messageId++}" },
                projectionDispatcher = mainDispatcherRule.dispatcher,
            )
            viewModel.loadConversation(created.id)

            viewModel.updateInput("开始")
            viewModel.send()

            val persisted = conversations.get(created.id)!!
            val userVariant = persisted.turns.last().selected
            assertEquals(null, persisted.runtimeState.localVariables["planned"])
            assertEquals("开始", userVariant.message.sourceText)
            assertNotNull(userVariant.runtimeStateBefore)
            assertNotNull(userVariant.runtimeStateAfter)
            assertTrue(viewModel.uiState.value.toString(), viewModel.uiState.value.retryAvailable)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `failure before text keeps user and retry does not duplicate it`() = runTest {
        var fail = true
        val generator = FakeGenerator { connection, _ ->
            flow {
                if (fail) throw GatewayException.Network(IOException("offline"))
                emit(GenerationEvent.RequestPrepared(preview(connection)))
                emit(GenerationEvent.TextDelta("现在可以了。"))
                emit(GenerationEvent.Finished("stop"))
            }
        }
        val viewModel = viewModel(generator)

        viewModel.updateInput("你好")
        viewModel.send()

        assertEquals(listOf(MessageRole.ASSISTANT, MessageRole.USER), viewModel.uiState.value.messages.map { it.message.role })
        assertTrue(viewModel.uiState.value.retryAvailable)

        fail = false
        viewModel.retry()

        val state = viewModel.uiState.value
        assertEquals(1, state.messages.count { it.message.role == MessageRole.USER })
        assertEquals("现在可以了。", state.messages.last().message.content)
        assertFalse(state.retryAvailable)
    }

    @Test
    fun `cancellation keeps partial assistant and reset clears trace`() = runTest {
        val generator = FakeGenerator { connection, _ ->
            flow {
                emit(GenerationEvent.RequestPrepared(preview(connection)))
                emit(GenerationEvent.TextDelta("半句回复"))
                awaitCancellation()
            }
        }
        val viewModel = viewModel(generator)

        viewModel.updateInput("开始")
        viewModel.send()
        assertTrue(viewModel.uiState.value.running)

        viewModel.cancel()

        assertFalse(viewModel.uiState.value.running)
        assertEquals(ChatMessageStatus.CANCELLED, viewModel.uiState.value.messages.last().status)
        assertEquals("半句回复", viewModel.uiState.value.messages.last().message.content)
        assertNotNull(viewModel.uiState.value.lastTrace)

        viewModel.resetConversation()
        assertEquals(1, viewModel.uiState.value.messages.size)
        assertEquals(null, viewModel.uiState.value.lastTrace)
    }

    @Test
    fun `global variable macro remains literal and is never downgraded to local state`() = runTest {
        val generator = FakeGenerator { connection, _ ->
            flow {
                emit(GenerationEvent.RequestPrepared(preview(connection)))
                emit(GenerationEvent.TextDelta("收到。"))
                emit(GenerationEvent.Finished("stop"))
            }
        }
        val viewModel = viewModel(generator)

        viewModel.updateInput("{{getglobalvar::mood}}")
        viewModel.send()

        assertEquals("", viewModel.uiState.value.input)
        assertEquals("{{getglobalvar::mood}}", viewModel.uiState.value.messages[1].message.content)
        assertTrue(viewModel.uiState.value.lastTrace?.compileDiagnostics.orEmpty().any { it.code == "UNSUPPORTED_MACRO" })
        assertEquals(1, generator.calls)
    }

    @Test
    fun `generation captures one preset while active changes affect display and the next request`() = runTest {
        val presetA = DemoConversationContent.preset.copy(
            id = "preset-a",
            name = "Preset A",
            contentSha256 = "fingerprint-a",
            builtIn = false,
            generationSettings = DemoConversationContent.preset.generationSettings.copy(maxOutputTokens = 111),
        )
        val presetB = presetA.copy(
            id = "preset-b",
            name = "Preset B",
            contentSha256 = "fingerprint-b",
            generationSettings = presetA.generationSettings.copy(maxOutputTokens = 222),
            regexScripts = listOf(
                RegexDefinition(
                    id = "display-b",
                    name = "Display B",
                    findRegex = "alpha",
                    replaceString = "beta",
                    placements = setOf(RegexPlacement.AI_OUTPUT),
                    markdownOnly = true,
                ),
            ),
        )
        val presetSource = FixedPresetSource(presetA)
        val releaseFirst = CompletableDeferred<Unit>()
        val plans = mutableListOf<GenerationPlan>()
        val generator = FakeGenerator { _, plan ->
            plans += plan
            flow {
                if (plans.size == 1) releaseFirst.await()
                emit(GenerationEvent.TextDelta("alpha"))
                emit(GenerationEvent.Finished("stop"))
            }
        }
        var id = 0
        val viewModel = ChatViewModel(
            repository = repository(),
            compiler = PromptCompiler(),
            generator = generator,
            presetSource = presetSource,
            idGenerator = { "capture-${id++}" },
            projectionDispatcher = mainDispatcherRule.dispatcher,
        )

        viewModel.updateInput("first")
        viewModel.send()
        assertTrue(viewModel.uiState.value.running)
        assertEquals("preset-a", plans.single().presetId)
        assertEquals(111, plans.single().maxOutputTokens)

        presetSource.set(presetB)
        assertEquals("preset-b", viewModel.uiState.value.activePresetId)
        assertEquals("preset-a", plans.single().presetId)
        releaseFirst.complete(Unit)

        val firstAssistant = viewModel.uiState.value.messages.last()
        assertEquals("alpha", firstAssistant.message.content)
        assertEquals("beta", firstAssistant.displayContent)
        assertEquals("preset-a", firstAssistant.metadata?.presetId)
        assertEquals("fingerprint-a", firstAssistant.metadata?.presetContentSha256)

        viewModel.updateInput("second")
        viewModel.send()
        assertEquals(2, plans.size)
        assertEquals("preset-b", plans.last().presetId)
        assertEquals(222, plans.last().maxOutputTokens)
        assertEquals("preset-b", viewModel.uiState.value.messages.last().metadata?.presetId)
    }

    @Test
    fun `generation uses selected model token limit overrides`() = runTest {
        val connection = connection().copy(
            modelTokenLimitOverrides = mapOf(
                "model" to ModelTokenLimits(contextTokens = 128_000, outputTokens = 32_000),
            ),
        )
        val preset = DemoConversationContent.preset.copy(
            generationSettings = DemoConversationContent.preset.generationSettings.copy(
                maxContextTokens = 2_000_000,
                maxOutputTokens = 65_535,
            ),
        )
        var captured: GenerationPlan? = null
        val generator = FakeGenerator { _, plan ->
            captured = plan
            flow {
                emit(GenerationEvent.TextDelta("完成"))
                emit(GenerationEvent.Finished("stop"))
            }
        }
        val viewModel = ChatViewModel(
            repository = repository(connection),
            compiler = PromptCompiler(),
            generator = generator,
            presetSource = FixedPresetSource(preset),
            projectionDispatcher = mainDispatcherRule.dispatcher,
        )

        viewModel.updateInput("开始")
        viewModel.send()

        assertEquals(128_000, captured?.tokenAccounting?.contextLimit)
        assertEquals(32_000, captured?.maxOutputTokens)
        assertTrue(captured?.diagnostics.orEmpty().none { it.code.endsWith("_FALLBACK") })
    }

    private fun viewModel(
        generator: FakeGenerator,
        character: CharacterAsset = DemoConversationContent.character,
    ): ChatViewModel {
        var id = 0
        return ChatViewModel(
            repository = repository(),
            compiler = PromptCompiler(),
            generator = generator,
            presetSource = FixedPresetSource(),
            characterAsset = character,
            idGenerator = { "message-${id++}" },
            projectionDispatcher = mainDispatcherRule.dispatcher,
        )
    }

    private fun dayAdaptation() = NativeAdaptation(
        sourceSha256 = "a".repeat(64),
        state = listOf(ConversationStateDefinition("world-day", type = ConversationStateValueType.NUMBER, initialValue = JsonPrimitive(1))),
        assistantStateAdapters = listOf(
            AssistantStateAdapterDefinition(
                LegacyStateDialect.UPDATE_VARIABLE_SET_V1,
                listOf(AssistantStateMapping("世界.日期", "world-day")),
            ),
        ),
    )

    private class FixedPresetSource(initial: PresetAsset = DemoConversationContent.preset) : ActivePresetSource {
        private val state = MutableStateFlow(initial)
        override val activePreset = state

        fun set(preset: PresetAsset) {
            state.value = preset
        }
    }

    private fun repository(connection: StoredConnection = connection()): ConnectionRepository {
        return ConnectionRepository(
            stateStore = FakeStateStore(GatewayAppState(connections = listOf(connection), recentConnectionId = connection.id)),
            credentialStore = FakeCredentialStore(),
            catalogLoader = { ModelCatalog(emptyList(), truncated = false) },
        )
    }

    private fun connection() = StoredConnection(
        id = "connection",
        name = "Test",
        templateId = "test",
        protocol = ModelProtocol.OPENAI_CHAT_COMPLETIONS,
        apiAddress = "https://example.com/v1",
        streamEndpoint = "https://example.com/v1/chat/completions",
        catalogEndpoint = null,
        authScheme = AuthScheme.NONE,
        credentialRef = null,
        credentialMask = null,
        approvedOrigins = emptySet(),
        selectedModel = "model",
        modelCache = ModelCache(),
    )

    private fun preview(connection: StoredConnection) = ProviderRequestPreview(
        protocol = connection.protocol,
        model = connection.selectedModel,
        systemInstruction = null,
        messages = listOf(ProviderPreviewMessage("user", "hello")),
        maxOutputTokens = 512,
        store = false,
        usesHostedState = false,
        assistantPrefillApplied = false,
    )
}

private fun GenerationPlan.projectedConversationState(): String =
    messages.single { it.origin.sourceIds == listOf("conversationState") }.content

private class FakeGenerator(
    private val block: (StoredConnection, GenerationPlan) -> Flow<GenerationEvent>,
) : ConversationGenerator {
    var calls: Int = 0
        private set

    override fun stream(connection: StoredConnection, plan: GenerationPlan): Flow<GenerationEvent> {
        calls += 1
        return block(connection, plan)
    }
}

private class FakeStateStore(initial: GatewayAppState) : ConnectionStateStore {
    private val mutable = MutableStateFlow(initial)
    override val state: Flow<GatewayAppState> = mutable

    override suspend fun update(transform: (GatewayAppState) -> GatewayAppState) {
        mutable.value = transform(mutable.value)
    }
}

private class FakeCredentialStore : CredentialStore {
    override suspend fun put(credentialId: String, secret: String) = Unit
    override suspend fun getOrNull(credentialId: String): String? = null
    override suspend fun delete(credentialId: String) = Unit
    override suspend fun contains(credentialId: String): Boolean = false
}
