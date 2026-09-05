package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.RegexPlacement
import io.github.zvensmoluya.tavernplayer.content.RegexDefinition
import io.github.zvensmoluya.tavernplayer.content.PresetGenerationTrigger
import io.github.zvensmoluya.tavernplayer.content.PresetNamesBehavior
import io.github.zvensmoluya.tavernplayer.content.WorldBookPosition
import java.time.Instant
import java.time.ZoneId

interface GenerationPlanner {
    fun compile(input: NormalGenerationInput): CompilationResult
}

data class AssistantOutputProjection(
    val storageText: String,
    val displayText: String,
    val storageReasoning: List<String>,
    val displayReasoning: List<String>,
    val runtimeState: ConversationRuntimeState,
    val diagnostics: List<CompilationDiagnostic>,
)

class PromptCompiler(
    private val macroEngine: MacroEngine = MacroEngine(),
    private val regexEngine: CharacterRegexEngine = CharacterRegexEngine(macroEngine),
    private val worldBookEngine: WorldBookEngine = WorldBookEngine(macroEngine, regexEngine),
    private val contextBudgeter: ContextBudgeter = ContextBudgeter(),
    private val statePromptProjector: ConversationStatePromptProjector = ConversationStatePromptProjector(),
) : GenerationPlanner {
    fun expandConversationText(
        text: String,
        character: CharacterSnapshot,
        persona: Persona,
        runtimeState: ConversationRuntimeState = ConversationRuntimeState(),
        history: List<ConversationMessage> = emptyList(),
        conversationId: String = "preview",
        generationId: String = "preview-0",
        modelId: String = "",
        evaluationInstant: Instant = Instant.now(),
        evaluationZoneId: ZoneId = ZoneId.systemDefault(),
    ): TextExpansionResult {
        val transaction = MacroTransaction(runtimeState.localVariables, generationId)
        val result = macroEngine.evaluate(
            text,
            MacroContext(
                character = character,
                persona = persona,
                history = history,
                inputText = text,
                modelId = modelId,
                conversationId = conversationId,
                generationId = generationId,
                lastGenerationType = runtimeState.lastGenerationType,
            legacyStateJson = LegacyStateReadProjection.project(character.nativeAdaptation, runtimeState.conversationState),
                now = evaluationInstant,
                zoneId = evaluationZoneId,
            ),
            transaction,
        )
        return TextExpansionResult.Success(
            text = result.text,
            runtimeState = runtimeState.copy(localVariables = transaction.snapshot()),
            diagnostics = result.diagnostics,
        )
    }

    fun projectUserInput(
        text: String,
        character: CharacterSnapshot,
        persona: Persona,
        preset: Preset,
        runtimeState: ConversationRuntimeState,
        history: List<ConversationMessage>,
        conversationId: String,
        generationId: String,
        modelId: String,
        evaluationInstant: Instant = Instant.now(),
        evaluationZoneId: ZoneId = ZoneId.systemDefault(),
    ): TextExpansionResult = projectConversationText(
        text = text,
        placement = RegexPlacement.USER_INPUT,
        projection = RegexProjection.STORAGE,
        character = character,
        persona = persona,
        preset = preset,
        runtimeState = runtimeState,
        history = history,
        conversationId = conversationId,
        generationId = generationId,
        modelId = modelId,
        depth = 0,
        evaluationInstant = evaluationInstant,
        evaluationZoneId = evaluationZoneId,
    )

    fun projectAssistantText(
        text: String,
        projection: RegexProjection,
        character: CharacterSnapshot,
        persona: Persona,
        preset: Preset,
        runtimeState: ConversationRuntimeState,
        history: List<ConversationMessage>,
        conversationId: String,
        generationId: String,
        modelId: String,
        depth: Int = 0,
        evaluationInstant: Instant = Instant.now(),
        evaluationZoneId: ZoneId = ZoneId.systemDefault(),
    ): TextExpansionResult = projectConversationText(
        text = text,
        placement = RegexPlacement.AI_OUTPUT,
        projection = projection,
        character = character,
        persona = persona,
        preset = preset,
        runtimeState = runtimeState,
        history = history,
        conversationId = conversationId,
        generationId = generationId,
        modelId = modelId,
        depth = depth,
        evaluationInstant = evaluationInstant,
        evaluationZoneId = evaluationZoneId,
    )

    fun projectReasoningText(
        text: String,
        projection: RegexProjection,
        character: CharacterSnapshot,
        persona: Persona,
        preset: Preset,
        runtimeState: ConversationRuntimeState,
        history: List<ConversationMessage>,
        conversationId: String,
        generationId: String,
        modelId: String,
        depth: Int = 0,
        evaluationInstant: Instant = Instant.now(),
        evaluationZoneId: ZoneId = ZoneId.systemDefault(),
    ): TextExpansionResult {
        if (projection == RegexProjection.DISPLAY && !preset.controlSettings.showThoughts) {
            return TextExpansionResult.Success("", runtimeState)
        }
        val source = if (projection == RegexProjection.DISPLAY) {
            projectConversationText(
                text = text,
                placement = RegexPlacement.REASONING,
                projection = RegexProjection.STORAGE,
                character = character,
                persona = persona,
                preset = preset,
                runtimeState = runtimeState,
                history = history,
                conversationId = conversationId,
                generationId = generationId,
                modelId = modelId,
                depth = depth,
                evaluationInstant = evaluationInstant,
                evaluationZoneId = evaluationZoneId,
            )
        } else null
        val sourceProjection = source as? TextExpansionResult.Success
        val displayed = projectConversationText(
            text = sourceProjection?.text ?: text,
            placement = RegexPlacement.REASONING,
            projection = projection,
            character = character,
            persona = persona,
            preset = preset,
            runtimeState = sourceProjection?.runtimeState ?: runtimeState,
            history = history,
            conversationId = conversationId,
            generationId = generationId,
            modelId = modelId,
            depth = depth,
            evaluationInstant = evaluationInstant,
            evaluationZoneId = evaluationZoneId,
        )
        return if (sourceProjection != null && displayed is TextExpansionResult.Success) {
            displayed.copy(diagnostics = sourceProjection.diagnostics + displayed.diagnostics)
        } else displayed
    }

    fun projectAssistantOutput(
        rawText: String,
        rawReasoning: List<String>,
        character: CharacterSnapshot,
        persona: Persona,
        preset: Preset,
        runtimeState: ConversationRuntimeState,
        history: List<ConversationMessage>,
        conversationId: String,
        generationId: String,
        modelId: String,
        depth: Int = 0,
        evaluationInstant: Instant = Instant.now(),
        evaluationZoneId: ZoneId = ZoneId.systemDefault(),
    ): AssistantOutputProjection {
        val baseContext = MacroContext(
            character = character,
            persona = persona,
            history = history,
            modelId = modelId,
            conversationId = conversationId,
            generationId = generationId,
            lastGenerationType = runtimeState.lastGenerationType,
            legacyStateJson = LegacyStateReadProjection.project(character.nativeAdaptation, runtimeState.conversationState),
            now = evaluationInstant,
            zoneId = evaluationZoneId,
        )
        val storageTransaction = MacroTransaction(runtimeState.localVariables, generationId)
        val storageDiagnostics = mutableListOf<CompilationDiagnostic>()
        // Provider reasoning and signatures are diagnostic source data. Persist the text verbatim;
        // display/prompt projections are derived later from the current Preset without destroying it.
        val storageReasoning = rawReasoning.toList()
        val storageText = applyProjection(
            rawText,
            RegexPlacement.AI_OUTPUT,
            RegexProjection.STORAGE,
            character,
            preset,
            baseContext,
            storageTransaction,
            conversationId,
            depth,
            storageDiagnostics,
        )
        val committedRuntime = runtimeState.copy(localVariables = storageTransaction.snapshot())
        val displayTransaction = MacroTransaction(committedRuntime.localVariables, generationId)
        val displayDiagnostics = mutableListOf<CompilationDiagnostic>()
        val displayReasoning = if (preset.controlSettings.showThoughts) rawReasoning.map { text ->
            val displaySource = applyProjection(
                text,
                RegexPlacement.REASONING,
                RegexProjection.STORAGE,
                character,
                preset,
                baseContext,
                displayTransaction,
                conversationId,
                depth,
                displayDiagnostics,
            )
            applyProjection(
                displaySource,
                RegexPlacement.REASONING,
                RegexProjection.DISPLAY,
                character,
                preset,
                baseContext,
                displayTransaction,
                conversationId,
                depth,
                displayDiagnostics,
            )
        } else emptyList()
        val displayText = applyProjection(
            storageText,
            RegexPlacement.AI_OUTPUT,
            RegexProjection.DISPLAY,
            character,
            preset,
            baseContext,
            displayTransaction,
            conversationId,
            depth,
            displayDiagnostics,
        )
        return AssistantOutputProjection(
            storageText = storageText,
            displayText = displayText,
            storageReasoning = storageReasoning,
            displayReasoning = displayReasoning,
            runtimeState = committedRuntime,
            diagnostics = (storageDiagnostics + displayDiagnostics).distinctBy { Triple(it.code, it.sourceId, it.message) },
        )
    }

    fun projectDisplayText(
        text: String,
        role: MessageRole,
        character: CharacterSnapshot,
        persona: Persona,
        preset: Preset,
        runtimeState: ConversationRuntimeState,
        history: List<ConversationMessage>,
        conversationId: String,
        generationId: String,
        modelId: String,
        depth: Int = 0,
        evaluationInstant: Instant = Instant.now(),
        evaluationZoneId: ZoneId = ZoneId.systemDefault(),
    ): TextExpansionResult = projectConversationText(
        text = text,
        placement = if (role == MessageRole.USER) RegexPlacement.USER_INPUT else RegexPlacement.AI_OUTPUT,
        projection = RegexProjection.DISPLAY,
        character = character,
        persona = persona,
        preset = preset,
        runtimeState = runtimeState,
        history = history,
        conversationId = conversationId,
        generationId = generationId,
        modelId = modelId,
        depth = depth,
        evaluationInstant = evaluationInstant,
        evaluationZoneId = evaluationZoneId,
    )

    override fun compile(input: NormalGenerationInput): CompilationResult {
        val diagnostics = mutableListOf<CompilationDiagnostic>()
        val trace = mutableListOf<CompilationTraceEntry>()
        val definitions = validatePreset(input.preset, diagnostics)
        if (diagnostics.hasErrors()) return CompilationResult.Failure(diagnostics, trace)

        val ordered = orderPrompts(
            input.preset,
            definitions,
            input.runtimeState.lastGenerationType,
            diagnostics,
            trace,
        )
        if (diagnostics.hasErrors()) return CompilationResult.Failure(diagnostics, trace)

        val contextLimit = contextBudgeter.contextLimit(input)
        val outputLimit = contextBudgeter.outputLimit(input)
        val transaction = MacroTransaction(input.runtimeState.localVariables, input.generationId)
        val baseContext = MacroContext(
            character = input.character,
            persona = input.persona,
            history = input.history,
            inputText = input.inputText,
            modelId = input.modelId,
            conversationId = input.conversationId,
            generationId = input.generationId,
            maxContextTokens = contextLimit,
            maxResponseTokens = outputLimit,
            firstIncludedMessageId = input.firstIncludedMessageId,
            firstDisplayedMessageId = input.firstDisplayedMessageId,
            lastSwipeId = input.lastSwipeId,
            currentSwipeId = input.currentSwipeId,
            allChatLastMessageId = input.allChatLastMessageId,
            lastGenerationType = input.runtimeState.lastGenerationType,
            legacyStateJson = LegacyStateReadProjection.project(input.character.nativeAdaptation, input.runtimeState.conversationState),
            now = input.evaluationInstant,
            zoneId = input.evaluationZoneId,
        )
        val regexRules = input.preset.regexScripts + input.character.regexScripts
        val projectedHistory = projectHistory(input, regexRules, baseContext, transaction, diagnostics, trace)
        if (diagnostics.hasErrors()) return CompilationResult.Failure(diagnostics, trace)

        val scanTransaction = transaction.fork()
        val characterScanText = listOf(
            input.character.description,
            input.character.personality,
            input.character.scenario,
            input.character.depthPrompt?.content.orEmpty(),
            input.character.creatorNotes,
        ).joinToString("\n") { macroEngine.evaluate(it, baseContext, scanTransaction).also { evaluation ->
            diagnostics += evaluation.diagnostics
        }.text }
        val activation = worldBookEngine.activate(
            books = input.character.worldBooks,
            characterText = characterScanText,
            projectedHistory = projectedHistory.map { message ->
                ConversationMessage(
                    id = message.origin.sourceIds.firstOrNull().orEmpty(),
                    role = message.role,
                    content = message.content,
                    authorName = message.authorName.orEmpty(),
                )
            },
            regexRules = regexRules,
            macroContext = baseContext,
            transaction = transaction,
            previousState = input.runtimeState.worldBookEntries,
            activationOverrides = input.runtimeState.worldBookActivationOverrides,
            turnIndex = input.runtimeState.generationIndex,
            inputBudgetTokens = (contextLimit - outputLimit).coerceAtLeast(0),
        )
        diagnostics += activation.diagnostics
        trace += activation.trace
        trace += CompilationTraceEntry(
            stage = "world-book-budget",
            sourceIds = activation.activatedEntryIds,
            decision = "used=${activation.usedBudgetTokens} budget=${activation.budgetTokens}",
        )
        val outlets = activation.injections.filter { it.position == WorldBookPosition.OUTLET }
            .filter { it.outletName.isNotBlank() }
            .groupBy(WorldBookInjection::outletName)
            .mapValues { (_, injections) -> injections.joinToString("\n") { it.content } }
        val macroContext = baseContext.copy(outlets = outlets)

        val resolved = mutableListOf<ResolvedPrompt>()
        val enabledIds = input.preset.promptOrder.asSequence()
            .filter { it.enabled }
            .map { it.identifier }
            .toSet()
        ordered.forEach { prompt ->
            when (prompt.identifier) {
                DIALOGUE_EXAMPLES_MARKER, CHAT_HISTORY_MARKER -> {
                    if (prompt.injectionPosition == InjectionPosition.ABSOLUTE) {
                        diagnostics += error("INVALID_COLLECTION_MARKER_POSITION", "${prompt.identifier} 必须是 relative marker", prompt.identifier)
                    } else {
                        resolved += ResolvedPrompt(prompt, null, prompt.identifier)
                    }
                }
                else -> resolvePrompt(
                    prompt,
                    enabledIds,
                    input,
                    activation.injections,
                    macroContext,
                    transaction,
                    diagnostics,
                    trace,
                )?.let(resolved::add)
            }
        }

        input.character.depthPrompt?.takeIf { it.content.isNotBlank() }?.let { depthPrompt ->
            val expanded = macroEngine.evaluate(depthPrompt.content, macroContext, transaction)
            diagnostics += expanded.diagnostics
            resolved += ResolvedPrompt(
                definition = PromptDefinition(
                    identifier = CHARACTER_DEPTH_SOURCE,
                    role = depthPrompt.role,
                    content = depthPrompt.content,
                    injectionPosition = InjectionPosition.ABSOLUTE,
                    injectionDepth = depthPrompt.depth,
                    injectionOrder = depthPrompt.order,
                ),
                content = expanded.text,
            )
        }
        activation.injections.filter { it.position in DEPTH_POSITIONS }.forEach { injection ->
            resolved += ResolvedPrompt(
                definition = PromptDefinition(
                    identifier = "world-depth-${injection.entryIds.joinToString("-")}",
                    role = injection.role.toContentRole(),
                    injectionPosition = InjectionPosition.ABSOLUTE,
                    injectionDepth = injection.depth,
                    injectionOrder = when (injection.position) {
                        WorldBookPosition.AUTHOR_NOTE_TOP -> Int.MIN_VALUE
                        WorldBookPosition.AUTHOR_NOTE_BOTTOM -> Int.MAX_VALUE
                        else -> 100
                    },
                ),
                content = injection.content,
                worldBookEntryIds = injection.entryIds,
            )
        }
        if (diagnostics.hasErrors()) return CompilationResult.Failure(diagnostics, trace)

        val examples = projectExamples(input, activation.injections, macroContext, transaction, diagnostics, trace)
        val absolute = resolved.filter { it.definition.injectionPosition == InjectionPosition.ABSOLUTE }
        val injectedHistory = injectAbsolute(projectedHistory, absolute, trace)
        val historyCollection = buildList {
            expand(input.preset.controlSettings.newChatPrompt, NEW_CHAT_SOURCE, macroContext, transaction, diagnostics)
                .takeIf(String::isNotBlank)
                ?.let { content -> add(PreparedMessage(MessageRole.SYSTEM, content, PromptOrigin("control", listOf(NEW_CHAT_SOURCE)))) }
            addAll(injectedHistory)
        }
        val relative = resolved.filter { it.definition.injectionPosition == InjectionPosition.RELATIVE }
        val compiled = buildList {
            relative.forEach { prompt ->
                when (prompt.collectionMarker) {
                    DIALOGUE_EXAMPLES_MARKER -> addAll(examples)
                    CHAT_HISTORY_MARKER -> addAll(historyCollection)
                    else -> prompt.content?.takeIf(String::isNotBlank)?.let { content ->
                        add(prompt.toPreparedMessage(content))
                    }
                }
            }
        }.toMutableList()
        statePromptProjector.project(
            input.runtimeState.conversationState,
            input.character.nativeAdaptation,
        )?.let { content ->
            val insertionIndex = compiled.indexOfFirst { it.role != MessageRole.SYSTEM }
                .takeIf { it >= 0 }
                ?: compiled.size
            compiled.add(
                insertionIndex,
                PreparedMessage(
                    role = MessageRole.SYSTEM,
                    content = content,
                    origin = PromptOrigin("conversation-state", listOf(CONVERSATION_STATE_SOURCE)),
                ),
            )
            trace += CompilationTraceEntry(
                stage = "conversation-state",
                sourceIds = listOf(CONVERSATION_STATE_SOURCE),
                decision = "projected ${input.runtimeState.conversationState.values.size} state values",
                role = MessageRole.SYSTEM,
                content = content,
            )
        }
        statePromptProjector.projectAdapterContract(input.character.nativeAdaptation)?.let { content ->
            val insertionIndex = compiled.indexOfFirst { it.role != MessageRole.SYSTEM }
                .takeIf { it >= 0 }
                ?: compiled.size
            compiled.add(
                insertionIndex,
                PreparedMessage(
                    role = MessageRole.SYSTEM,
                    content = content,
                    origin = PromptOrigin("assistant-state-contract", listOf(ASSISTANT_STATE_CONTRACT_SOURCE)),
                ),
            )
            trace += CompilationTraceEntry(
                stage = "assistant-state-contract",
                sourceIds = listOf(ASSISTANT_STATE_CONTRACT_SOURCE),
                decision = "projected fixed adapter reply contract",
                role = MessageRole.SYSTEM,
                content = content,
            )
        }
        statePromptProjector.projectAdapterCompletionReminder(input.character.nativeAdaptation)?.let { content ->
            compiled += PreparedMessage(
                role = MessageRole.SYSTEM,
                content = content,
                origin = PromptOrigin("assistant-state-reminder", listOf(ASSISTANT_STATE_REMINDER_SOURCE)),
            )
            trace += CompilationTraceEntry(
                stage = "assistant-state-reminder",
                sourceIds = listOf(ASSISTANT_STATE_REMINDER_SOURCE),
                decision = "appended fixed adapter completion reminder",
                role = MessageRole.SYSTEM,
                content = content,
            )
        }
        val preparedForTransport = applyNamesBehavior(compiled, input.preset.controlSettings.namesBehavior, trace)
            .let { messages ->
                if (input.preset.controlSettings.squashSystemMessages) squashSystemMessages(messages, trace) else messages
            }
        val prefill = expand(
            input.preset.controlSettings.assistantPrefill,
            ASSISTANT_PREFILL_SOURCE,
            macroContext,
            transaction,
            diagnostics,
        )
        if (diagnostics.hasErrors()) return CompilationResult.Failure(diagnostics, trace)

        val budget = contextBudgeter.budget(preparedForTransport, input)
        diagnostics += budget.diagnostics
        trace += budget.trace
        val firstIncludedMessageId = input.history.indexOfFirst { historyMessage ->
            budget.messages.any { message ->
                message.origin.stage == "chat-history" && historyMessage.id in message.origin.sourceIds
            }
        }.takeIf { it >= 0 }
        if (
            input.usesFirstIncludedMessageIdMacro() &&
            firstIncludedMessageId != input.firstIncludedMessageId &&
            input.chatRangeResolutionPass < MAX_CHAT_RANGE_RESOLUTION_PASSES
        ) {
            return compile(
                input.copy(
                    firstIncludedMessageId = firstIncludedMessageId,
                    chatRangeResolutionPass = input.chatRangeResolutionPass + 1,
                ),
            )
        }
        if (input.usesFirstIncludedMessageIdMacro() && firstIncludedMessageId != input.firstIncludedMessageId) {
            diagnostics += CompilationDiagnostic(
                DiagnosticSeverity.WARNING,
                "CHAT_RANGE_DID_NOT_STABILIZE",
                "firstIncludedMessageId 在 $MAX_CHAT_RANGE_RESOLUTION_PASSES 次预算后仍未稳定",
            )
        }
        trace += CompilationTraceEntry(
            stage = "chat-range",
            sourceIds = emptyList(),
            decision = "firstIncludedMessageId=${firstIncludedMessageId ?: ""}",
        )
        budget.failure?.let {
            diagnostics += it
            return CompilationResult.Failure(diagnostics, trace)
        }
        budget.messages.forEach { message ->
            trace += CompilationTraceEntry(
                stage = "final-message",
                sourceIds = message.origin.sourceIds,
                decision = "sent",
                role = message.role,
                content = message.content,
            )
        }
        val nextRuntime = input.runtimeState.copy(
            localVariables = transaction.snapshot(),
            worldBookEntries = activation.runtimeState,
            generationIndex = input.runtimeState.generationIndex + 1,
        )
        return CompilationResult.Success(
            GenerationPlan(
                messages = budget.messages,
                maxOutputTokens = budget.report.reservedOutputTokens,
                declaredContextTokens = budget.report.contextLimit,
                assistantPrefill = prefill,
                presetId = input.preset.id,
                presetName = input.preset.name,
                presetContentSha256 = input.preset.contentSha256,
                generationSettings = input.preset.generationSettings.copy(),
                diagnostics = diagnostics.distinctBy { Triple(it.code, it.sourceId, it.message) },
                trace = trace,
                runtimeState = nextRuntime,
                tokenAccounting = budget.report,
                activatedWorldBookEntries = activation.activatedEntryIds,
                nativeAdaptation = input.character.nativeAdaptation,
            ),
        )
    }

    private fun projectConversationText(
        text: String,
        placement: RegexPlacement,
        projection: RegexProjection,
        character: CharacterSnapshot,
        persona: Persona,
        preset: Preset,
        runtimeState: ConversationRuntimeState,
        history: List<ConversationMessage>,
        conversationId: String,
        generationId: String,
        modelId: String,
        depth: Int,
        evaluationInstant: Instant,
        evaluationZoneId: ZoneId,
    ): TextExpansionResult {
        val transaction = MacroTransaction(runtimeState.localVariables, generationId)
        val context = MacroContext(
            character = character,
            persona = persona,
            history = history,
            inputText = text,
            modelId = modelId,
            conversationId = conversationId,
            generationId = generationId,
            lastGenerationType = runtimeState.lastGenerationType,
            legacyStateJson = LegacyStateReadProjection.project(character.nativeAdaptation, runtimeState.conversationState),
            now = evaluationInstant,
            zoneId = evaluationZoneId,
        )
        val diagnostics = mutableListOf<CompilationDiagnostic>()
        val projected = applyProjection(
            text,
            placement,
            projection,
            character,
            preset,
            context,
            transaction,
            conversationId,
            depth,
            diagnostics,
        )
        return TextExpansionResult.Success(
            text = projected,
            runtimeState = runtimeState.copy(localVariables = transaction.snapshot()),
            diagnostics = diagnostics,
        )
    }

    private fun applyProjection(
        text: String,
        placement: RegexPlacement,
        projection: RegexProjection,
        character: CharacterSnapshot,
        preset: Preset,
        context: MacroContext,
        transaction: MacroTransaction,
        conversationId: String,
        depth: Int,
        diagnostics: MutableList<CompilationDiagnostic>,
    ): String {
        val textContext = context.copy(inputText = text)
        val nativeFormMarkers = if (placement == RegexPlacement.AI_OUTPUT && projection == RegexProjection.DISPLAY) {
            character.nativeAdaptation?.forms.orEmpty()
                .filter { form -> form.matchesMessage(text) }
                .map { it.marker }
                .filter(String::isNotEmpty)
                .toSet()
        } else {
            emptySet()
        }
        val characterRules = if (nativeFormMarkers.isEmpty()) {
            character.regexScripts
        } else {
            character.regexScripts.filterNot { rule ->
                nativeFormMarkers.any { marker ->
                    regexEngine.matchesWithoutReplacement(
                        text = marker,
                        rule = rule,
                        placement = placement,
                        projection = projection,
                        depth = depth,
                        context = textContext,
                        transaction = transaction,
                    )
                }
            }
        }
        val regexed = regexEngine.apply(
            text,
            preset.regexScripts + characterRules,
            placement,
            projection,
            depth,
            textContext,
            transaction,
            conversationId,
        )
        diagnostics += regexed.diagnostics
        val markerFreeText = nativeFormMarkers.fold(regexed.text) { current, marker -> current.replace(marker, "") }
        val expanded = macroEngine.evaluate(markerFreeText, textContext, transaction)
        diagnostics += expanded.diagnostics
        return expanded.text
    }

    private fun validatePreset(
        preset: Preset,
        diagnostics: MutableList<CompilationDiagnostic>,
    ): Map<String, PromptDefinition> {
        val definitions = linkedMapOf<String, PromptDefinition>()
        preset.prompts.forEach { prompt ->
            when {
                prompt.identifier.isBlank() -> diagnostics += error("EMPTY_PROMPT_IDENTIFIER", "Prompt identifier 不能为空")
                definitions.put(prompt.identifier, prompt) != null -> diagnostics += error(
                    "DUPLICATE_PROMPT_IDENTIFIER",
                    "重复 Prompt identifier：${prompt.identifier}",
                    prompt.identifier,
                )
            }
            if (prompt.injectionPosition == InjectionPosition.ABSOLUTE && prompt.injectionDepth < 0) {
                diagnostics += error("INVALID_INJECTION_DEPTH", "Absolute prompt depth 不能为负数", prompt.identifier)
            }
            if (prompt.marker && prompt.identifier !in SUPPORTED_MARKERS) {
                diagnostics += warning(
                    "UNSUPPORTED_MARKER_SKIPPED",
                    "不支持的 Prompt marker“${prompt.identifier}”已保留但跳过",
                    prompt.identifier,
                )
            }
        }
        return definitions
    }

    private fun orderPrompts(
        preset: Preset,
        definitions: Map<String, PromptDefinition>,
        lastGenerationType: String,
        diagnostics: MutableList<CompilationDiagnostic>,
        trace: MutableList<CompilationTraceEntry>,
    ): List<PromptDefinition> = buildList {
        preset.promptOrder.forEach { entry ->
            val prompt = definitions[entry.identifier]
            if (prompt == null) {
                diagnostics += warning(
                    "MISSING_PROMPT_DEFINITION_SKIPPED",
                    "Prompt order 引用缺失定义“${entry.identifier}”；已按 ST 行为跳过",
                    entry.identifier,
                )
                return@forEach
            }
            if (prompt.marker && prompt.identifier !in SUPPORTED_MARKERS) {
                trace += trace("prompt-order", prompt.identifier, "unsupported marker skipped")
                return@forEach
            }
            val trigger = when (lastGenerationType.lowercase()) {
                PresetGenerationTrigger.REGENERATE.wireValue -> PresetGenerationTrigger.REGENERATE
                else -> PresetGenerationTrigger.NORMAL
            }
            val triggered = trigger in prompt.triggers
            if (entry.enabled && triggered) {
                add(prompt)
                trace += trace("prompt-order", prompt.identifier, "included")
            } else if (prompt.identifier == MAIN_MARKER) {
                add(prompt.copy(content = ""))
                trace += trace("prompt-order", prompt.identifier, "disabled main placeholder")
            } else {
                trace += trace("prompt-order", prompt.identifier, "disabled or trigger did not match")
            }
        }
    }

    private fun resolvePrompt(
        prompt: PromptDefinition,
        enabledIds: Set<String>,
        input: NormalGenerationInput,
        world: List<WorldBookInjection>,
        context: MacroContext,
        transaction: MacroTransaction,
        diagnostics: MutableList<CompilationDiagnostic>,
        trace: MutableList<CompilationTraceEntry>,
    ): ResolvedPrompt? {
        val dynamic = when (prompt.identifier) {
            WORLD_INFO_BEFORE_MARKER -> formatWorldInfo(
                input.preset.controlSettings.worldInfoFormat,
                world.filter { it.position == WorldBookPosition.BEFORE_CHARACTER }.joinToString("\n") { it.content },
            )
            WORLD_INFO_AFTER_MARKER -> formatWorldInfo(
                input.preset.controlSettings.worldInfoFormat,
                world.filter { it.position == WorldBookPosition.AFTER_CHARACTER }.joinToString("\n") { it.content },
            )
            PERSONA_DESCRIPTION_MARKER -> input.persona.description
            CHAR_DESCRIPTION_MARKER -> input.character.description
            CHAR_PERSONALITY_MARKER -> formatTemplate(
                input.preset.controlSettings.personalityFormat,
                "{{personality}}",
                input.character.personality,
            )
            SCENARIO_MARKER -> formatTemplate(
                input.preset.controlSettings.scenarioFormat,
                "{{scenario}}",
                input.character.scenario,
            )
            else -> prompt.content
        }
        var candidate = dynamic
        var expansionContext = context
        if (
            prompt.identifier == MAIN_MARKER && prompt.identifier in enabledIds &&
            input.character.systemPrompt.isNotBlank() && !prompt.forbidOverrides
        ) {
            val original = expand(prompt.content, prompt.identifier, context, transaction, diagnostics)
            candidate = input.character.systemPrompt
            expansionContext = context.copy(original = original)
            trace += trace("character-override", prompt.identifier, "main overridden by Character Snapshot")
        } else if (
            prompt.identifier == JAILBREAK_MARKER && prompt.identifier in enabledIds &&
            input.character.postHistoryInstructions.isNotBlank() && !prompt.forbidOverrides
        ) {
            val original = expand(prompt.content, prompt.identifier, context, transaction, diagnostics)
            candidate = input.character.postHistoryInstructions
            expansionContext = context.copy(original = original)
            trace += trace("character-override", prompt.identifier, "post-history instruction applied")
        }
        val content = expand(candidate, prompt.identifier, expansionContext, transaction, diagnostics)
        val entryIds = when (prompt.identifier) {
            WORLD_INFO_BEFORE_MARKER -> world.filter { it.position == WorldBookPosition.BEFORE_CHARACTER }.flatMap(WorldBookInjection::entryIds)
            WORLD_INFO_AFTER_MARKER -> world.filter { it.position == WorldBookPosition.AFTER_CHARACTER }.flatMap(WorldBookInjection::entryIds)
            else -> emptyList()
        }
        return ResolvedPrompt(prompt, content, worldBookEntryIds = entryIds)
    }

    private fun projectHistory(
        input: NormalGenerationInput,
        rules: List<RegexDefinition>,
        context: MacroContext,
        transaction: MacroTransaction,
        diagnostics: MutableList<CompilationDiagnostic>,
        trace: MutableList<CompilationTraceEntry>,
    ): List<PreparedMessage> = buildList {
        input.history.forEachIndexed { index, message ->
            val depth = input.history.lastIndex - index
            val placement = if (message.role == MessageRole.ASSISTANT) RegexPlacement.AI_OUTPUT else RegexPlacement.USER_INPUT
            val projectedReasoning = message.reasoning.map { block ->
                val regexed = regexEngine.apply(
                    block.text,
                    rules,
                    RegexPlacement.REASONING,
                    RegexProjection.PROMPT,
                    depth,
                    context.copy(inputText = block.text),
                    transaction,
                    input.conversationId,
                )
                diagnostics += regexed.diagnostics
                val expanded = macroEngine.evaluate(regexed.text, context.copy(inputText = block.text), transaction)
                diagnostics += expanded.diagnostics
                block.copy(text = expanded.text)
            }
            val regexed = regexEngine.apply(
                message.content,
                rules,
                placement,
                RegexProjection.PROMPT,
                depth,
                context,
                transaction,
                input.conversationId,
            )
            diagnostics += regexed.diagnostics
            val expanded = macroEngine.evaluate(regexed.text, context, transaction)
            diagnostics += expanded.diagnostics
            add(
                PreparedMessage(
                    role = message.role,
                    content = expanded.text,
                    origin = PromptOrigin("chat-history", listOf(message.id)),
                    authorName = message.authorName,
                    reasoning = projectedReasoning,
                    adapterId = message.adapterId,
                ),
            )
            trace += trace("chat-history", message.id, "projected depth=$depth", message.role, expanded.text)
        }
    }

    private fun projectExamples(
        input: NormalGenerationInput,
        world: List<WorldBookInjection>,
        context: MacroContext,
        transaction: MacroTransaction,
        diagnostics: MutableList<CompilationDiagnostic>,
        trace: MutableList<CompilationTraceEntry>,
    ): List<PreparedMessage> = buildList {
        world.filter { it.position == WorldBookPosition.EXAMPLES_TOP }.forEach { injection ->
            add(PreparedMessage(injection.role, injection.content, PromptOrigin("world-book", injection.entryIds)))
        }
        val raw = expand(input.character.rawMessageExamples, "character-examples", context, transaction, diagnostics)
        parseDialogueExamples(raw, input.character.promptName, input.persona.name).forEachIndexed { exampleIndex, example ->
            expand(
                input.preset.controlSettings.newExampleChatPrompt,
                "$NEW_EXAMPLE_SOURCE-$exampleIndex",
                context,
                transaction,
                diagnostics,
            )
                .takeIf(String::isNotBlank)
                ?.let { add(PreparedMessage(MessageRole.SYSTEM, it, PromptOrigin("dialogue-example", listOf("$NEW_EXAMPLE_SOURCE-$exampleIndex")))) }
            example.messages.forEachIndexed { messageIndex, message ->
                val sourceId = "example-$exampleIndex-$messageIndex"
                val expanded = expand(message.content, sourceId, context, transaction, diagnostics)
                add(
                    PreparedMessage(
                        role = message.role,
                        content = expanded,
                        origin = PromptOrigin("dialogue-example", listOf(sourceId)),
                        authorName = if (message.role == MessageRole.ASSISTANT) {
                            input.character.promptName
                        } else {
                            input.persona.name
                        },
                    ),
                )
                trace += trace("dialogue-example", sourceId, "projected", message.role, expanded)
            }
        }
        world.filter { it.position == WorldBookPosition.EXAMPLES_BOTTOM }.forEach { injection ->
            add(PreparedMessage(injection.role, injection.content, PromptOrigin("world-book", injection.entryIds)))
        }
    }

    private fun applyNamesBehavior(
        messages: List<PreparedMessage>,
        behavior: PresetNamesBehavior,
        trace: MutableList<CompilationTraceEntry>,
    ): List<PreparedMessage> = messages.map { message ->
        val author = message.authorName?.trim().orEmpty()
        when {
            message.role == MessageRole.SYSTEM || author.isEmpty() -> message.copy(authorName = null)
            behavior == PresetNamesBehavior.CONTENT -> {
                trace += CompilationTraceEntry(
                    stage = "names-behavior",
                    sourceIds = message.origin.sourceIds,
                    decision = "author name prefixed into content",
                    role = message.role,
                )
                message.copy(content = "$author: ${message.content}", authorName = null)
            }
            behavior == PresetNamesBehavior.COMPLETION -> message
            else -> message.copy(authorName = null)
        }
    }

    private fun squashSystemMessages(
        messages: List<PreparedMessage>,
        trace: MutableList<CompilationTraceEntry>,
    ): List<PreparedMessage> = messages.fold(mutableListOf()) { result, message ->
        val previous = result.lastOrNull()
        if (previous?.role == MessageRole.SYSTEM && message.role == MessageRole.SYSTEM) {
            result[result.lastIndex] = previous.copy(
                content = listOf(previous.content, message.content).filter(String::isNotBlank).joinToString("\n\n"),
                origin = PromptOrigin(
                    stage = "system-squash",
                    sourceIds = previous.origin.sourceIds + message.origin.sourceIds,
                ),
            )
            trace += CompilationTraceEntry(
                stage = "system-squash",
                sourceIds = message.origin.sourceIds,
                decision = "merged with preceding system message",
                role = MessageRole.SYSTEM,
            )
        } else {
            result += message
        }
        result
    }

    private fun formatWorldInfo(template: String, content: String): String =
        content.takeIf(String::isNotBlank)?.let { template.replace("{0}", it) }.orEmpty()

    private fun formatTemplate(template: String, placeholder: String, content: String): String =
        content.takeIf(String::isNotBlank)?.let { template.replace(placeholder, it, ignoreCase = true) }.orEmpty()

    private fun injectAbsolute(
        chronologicalHistory: List<PreparedMessage>,
        prompts: List<ResolvedPrompt>,
        trace: MutableList<CompilationTraceEntry>,
    ): List<PreparedMessage> {
        if (prompts.isEmpty()) return chronologicalHistory
        val reverse = chronologicalHistory.reversed().toMutableList()
        var inserted = 0
        val maxDepth = prompts.maxOfOrNull { it.definition.injectionDepth } ?: return chronologicalHistory
        for (depth in 0..maxDepth) {
            val atDepth = prompts.filter { it.definition.injectionDepth == depth && !it.content.isNullOrBlank() }
            val messages = mutableListOf<PreparedMessage>()
            atDepth.groupBy { it.definition.injectionOrder }.toSortedMap(compareByDescending { it }).forEach { (order, values) ->
                MessageRole.entries.forEach { role ->
                    val matches = values.filter { it.definition.role.toMessageRole() == role }
                    val content = matches.mapNotNull { it.content?.trim() }.filter(String::isNotEmpty).joinToString("\n")
                    if (content.isNotEmpty()) {
                        val worldIds = matches.flatMap { it.worldBookEntryIds }
                        messages += PreparedMessage(
                            role,
                            content,
                            PromptOrigin(if (worldIds.isEmpty()) "depth-injection" else "world-book", worldIds.ifEmpty { matches.map { it.definition.identifier } }),
                        )
                        trace += CompilationTraceEntry(
                            "depth-injection",
                            matches.map { it.definition.identifier },
                            "depth=$depth order=$order",
                            role,
                            content,
                        )
                    }
                }
            }
            if (messages.isNotEmpty()) {
                val index = (depth + inserted).coerceAtMost(reverse.size)
                reverse.addAll(index, messages)
                inserted += messages.size
            }
        }
        return reverse.reversed()
    }

    private fun expand(
        text: String,
        sourceId: String,
        context: MacroContext,
        transaction: MacroTransaction,
        diagnostics: MutableList<CompilationDiagnostic>,
    ): String {
        val result = macroEngine.evaluate(text, context, transaction)
        diagnostics += result.diagnostics.map { it.copy(sourceId = it.sourceId ?: sourceId) }
        return result.text
    }

    private fun ResolvedPrompt.toPreparedMessage(content: String) = PreparedMessage(
        role = definition.role.toMessageRole(),
        content = content,
        origin = PromptOrigin(
            if (worldBookEntryIds.isEmpty()) "prompt" else "world-book",
            worldBookEntryIds.ifEmpty { listOf(definition.identifier) },
        ),
    )

    private fun error(code: String, message: String, sourceId: String? = null) = CompilationDiagnostic(
        DiagnosticSeverity.ERROR,
        code,
        message,
        sourceId,
    )

    private fun warning(code: String, message: String, sourceId: String? = null) = CompilationDiagnostic(
        DiagnosticSeverity.WARNING,
        code,
        message,
        sourceId,
    )

    private fun trace(
        stage: String,
        sourceId: String,
        decision: String,
        role: MessageRole? = null,
        content: String? = null,
    ) = CompilationTraceEntry(stage, listOf(sourceId), decision, role, content)

    private data class ResolvedPrompt(
        val definition: PromptDefinition,
        val content: String?,
        val collectionMarker: String? = null,
        val worldBookEntryIds: List<String> = emptyList(),
    )

    companion object {
        private const val MAIN_MARKER = "main"
        private const val WORLD_INFO_BEFORE_MARKER = "worldInfoBefore"
        private const val WORLD_INFO_AFTER_MARKER = "worldInfoAfter"
        private const val CHAR_DESCRIPTION_MARKER = "charDescription"
        private const val CHAR_PERSONALITY_MARKER = "charPersonality"
        private const val SCENARIO_MARKER = "scenario"
        private const val PERSONA_DESCRIPTION_MARKER = "personaDescription"
        private const val DIALOGUE_EXAMPLES_MARKER = "dialogueExamples"
        private const val CHAT_HISTORY_MARKER = "chatHistory"
        private const val JAILBREAK_MARKER = "jailbreak"
        private const val CHARACTER_DEPTH_SOURCE = "characterDepthPrompt"
        private const val NEW_CHAT_SOURCE = "newChatPrompt"
        private const val NEW_EXAMPLE_SOURCE = "newExampleChatPrompt"
        private const val ASSISTANT_PREFILL_SOURCE = "assistantPrefill"
        private const val CONVERSATION_STATE_SOURCE = "conversationState"
        private const val ASSISTANT_STATE_CONTRACT_SOURCE = "assistantStateContract"
        private const val ASSISTANT_STATE_REMINDER_SOURCE = "assistantStateReminder"
        private val SUPPORTED_MARKERS = setOf(
            WORLD_INFO_BEFORE_MARKER,
            WORLD_INFO_AFTER_MARKER,
            CHAR_DESCRIPTION_MARKER,
            CHAR_PERSONALITY_MARKER,
            SCENARIO_MARKER,
            PERSONA_DESCRIPTION_MARKER,
            DIALOGUE_EXAMPLES_MARKER,
            CHAT_HISTORY_MARKER,
        )
        private val DEPTH_POSITIONS = setOf(
            WorldBookPosition.AT_DEPTH,
            WorldBookPosition.AUTHOR_NOTE_TOP,
            WorldBookPosition.AUTHOR_NOTE_BOTTOM,
        )
        private const val MAX_CHAT_RANGE_RESOLUTION_PASSES = 3
    }
}

private fun NormalGenerationInput.usesFirstIncludedMessageIdMacro(): Boolean = sequence {
    yield(persona.description)
    yield(character.description)
    yield(character.personality)
    yield(character.scenario)
    yield(character.rawMessageExamples)
    yield(character.systemPrompt)
    yield(character.postHistoryInstructions)
    yield(character.creatorNotes)
    character.depthPrompt?.content?.let { yield(it) }
    for (book in character.worldBooks) {
        for (entry in book.entries) yield(entry.content)
    }
    for (regex in preset.regexScripts + character.regexScripts) {
        yield(regex.findRegex)
        yield(regex.replaceString)
        for (trim in regex.trimStrings) yield(trim)
    }
    for (prompt in preset.prompts) yield(prompt.content)
    for (message in history) {
        yield(message.content)
        for (reasoning in message.reasoning) yield(reasoning.text)
    }
    yield(preset.controlSettings.newChatPrompt)
    yield(preset.controlSettings.newExampleChatPrompt)
    yield(preset.controlSettings.assistantPrefill)
    yield(preset.controlSettings.worldInfoFormat)
    yield(preset.controlSettings.scenarioFormat)
    yield(preset.controlSettings.personalityFormat)
}.any { it.contains("{{firstIncludedMessageId", ignoreCase = true) }

private fun List<CompilationDiagnostic>.hasErrors(): Boolean = any { it.severity == DiagnosticSeverity.ERROR }
