package io.github.zvensmoluya.tavernplayer.conversation

class PromptCompiler {
    fun expandConversationText(
        text: String,
        character: CharacterSnapshot,
        persona: Persona,
    ): TextExpansionResult = expand(text, character, persona, original = null)

    fun compile(input: NormalGenerationInput): CompilationResult {
        val diagnostics = mutableListOf<CompilationDiagnostic>()
        val trace = mutableListOf<CompilationTraceEntry>()
        val preset = input.preset

        if (preset.maxOutputTokens <= 0) {
            diagnostics += error("INVALID_MAX_OUTPUT_TOKENS", "maxOutputTokens must be positive", preset.id)
        }

        val definitions = linkedMapOf<String, PromptDefinition>()
        preset.prompts.forEach { prompt ->
            if (prompt.identifier.isBlank()) {
                diagnostics += error("EMPTY_PROMPT_IDENTIFIER", "Prompt identifier cannot be empty")
            } else if (definitions.put(prompt.identifier, prompt) != null) {
                diagnostics += error(
                    "DUPLICATE_PROMPT_IDENTIFIER",
                    "Duplicate prompt identifier: ${prompt.identifier}",
                    prompt.identifier,
                )
            }
            if (prompt.injectionPosition == InjectionPosition.ABSOLUTE && prompt.injectionDepth < 0) {
                diagnostics += error(
                    "INVALID_INJECTION_DEPTH",
                    "Absolute prompt depth cannot be negative",
                    prompt.identifier,
                )
            }
            if (prompt.marker && prompt.identifier !in supportedMarkers) {
                diagnostics += error(
                    "UNSUPPORTED_MARKER",
                    "Unsupported prompt marker: ${prompt.identifier}",
                    prompt.identifier,
                )
            }
        }

        val ordered = mutableListOf<PromptDefinition>()
        val enabledIds = mutableSetOf<String>()
        preset.promptOrder.forEach { entry ->
            val prompt = definitions[entry.identifier]
            if (prompt == null) {
                diagnostics += error(
                    "MISSING_PROMPT_DEFINITION",
                    "Prompt order references missing definition: ${entry.identifier}",
                    entry.identifier,
                )
                return@forEach
            }
            val triggered = prompt.injectionTriggers.isEmpty() || NORMAL_TRIGGER in prompt.injectionTriggers
            if (entry.enabled && triggered) {
                ordered += prompt
                enabledIds += prompt.identifier
                trace += trace("prompt-order", prompt.identifier, "included")
            } else if (prompt.identifier == MAIN_MARKER) {
                ordered += prompt.copy(content = "")
                trace += trace("prompt-order", prompt.identifier, "disabled main placeholder")
            } else {
                trace += trace("prompt-order", prompt.identifier, "disabled or trigger did not match")
            }
        }

        if (diagnostics.any { it.severity == DiagnosticSeverity.ERROR }) {
            return CompilationResult.Failure(diagnostics, trace)
        }

        val resolved = mutableListOf<ResolvedPrompt>()
        ordered.forEach { prompt ->
            when (prompt.identifier) {
                DIALOGUE_EXAMPLES_MARKER, CHAT_HISTORY_MARKER -> {
                    if (prompt.injectionPosition == InjectionPosition.ABSOLUTE) {
                        diagnostics += error(
                            "INVALID_COLLECTION_MARKER_POSITION",
                            "${prompt.identifier} must be a relative marker",
                            prompt.identifier,
                        )
                    } else {
                        resolved += ResolvedPrompt(prompt, content = null, collectionMarker = prompt.identifier)
                    }
                }

                else -> resolvePrompt(prompt, enabledIds, input, diagnostics, trace)?.let(resolved::add)
            }
        }

        input.character.depthPrompt?.takeIf { it.content.isNotBlank() }?.let { depthPrompt ->
            val expanded = expand(depthPrompt.content, input.character, input.persona, original = null)
            when (expanded) {
                is TextExpansionResult.Failure -> diagnostics += expanded.diagnostic.copy(sourceId = CHARACTER_DEPTH_SOURCE)
                is TextExpansionResult.Success -> resolved += ResolvedPrompt(
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
        }

        if (diagnostics.any { it.severity == DiagnosticSeverity.ERROR }) {
            return CompilationResult.Failure(diagnostics, trace)
        }

        val absolute = resolved.filter { it.definition.injectionPosition == InjectionPosition.ABSOLUTE }
        val projectedHistory = projectHistory(input, diagnostics, trace)
        if (diagnostics.any { it.severity == DiagnosticSeverity.ERROR }) {
            return CompilationResult.Failure(diagnostics, trace)
        }
        val injectedHistory = injectAbsolute(projectedHistory, absolute, trace)
        val historyCollection = buildList {
            expandOptional(
                text = preset.newChatPrompt,
                sourceId = NEW_CHAT_SOURCE,
                input = input,
                diagnostics = diagnostics,
            )?.takeIf(String::isNotBlank)?.let { content ->
                add(
                    PreparedMessage(
                        role = MessageRole.SYSTEM,
                        content = content,
                        origin = PromptOrigin("control", listOf(NEW_CHAT_SOURCE)),
                    ),
                )
            }
            addAll(injectedHistory)
        }
        val examples = projectExamples(input, diagnostics, trace)
        val relative = resolved.filter { it.definition.injectionPosition == InjectionPosition.RELATIVE }

        if (diagnostics.any { it.severity == DiagnosticSeverity.ERROR }) {
            return CompilationResult.Failure(diagnostics, trace)
        }

        val messages = buildList {
            relative.forEach { prompt ->
                when (prompt.collectionMarker) {
                    DIALOGUE_EXAMPLES_MARKER -> addAll(examples)
                    CHAT_HISTORY_MARKER -> addAll(historyCollection)
                    else -> prompt.content?.takeIf(String::isNotBlank)?.let { content ->
                        add(prompt.toPreparedMessage(content))
                    }
                }
            }
        }
        messages.forEach { message ->
            trace += CompilationTraceEntry(
                stage = "final-message",
                sourceIds = message.origin.sourceIds,
                decision = "sent",
                role = message.role,
                content = message.content,
            )
        }

        val prefill = expandOptional(
            text = preset.assistantPrefill,
            sourceId = ASSISTANT_PREFILL_SOURCE,
            input = input,
            diagnostics = diagnostics,
        ).orEmpty()
        if (diagnostics.any { it.severity == DiagnosticSeverity.ERROR }) {
            return CompilationResult.Failure(diagnostics, trace)
        }

        diagnostics += CompilationDiagnostic(
            severity = DiagnosticSeverity.WARNING,
            code = "CONTEXT_BUDGET_NOT_ENFORCED",
            message = "Token counting and context pruning are not implemented; all compiled content will be sent",
        )

        return CompilationResult.Success(
            GenerationPlan(
                messages = messages,
                maxOutputTokens = preset.maxOutputTokens,
                declaredContextTokens = preset.declaredContextTokens,
                assistantPrefill = prefill,
                presetId = preset.id,
                presetName = preset.name,
                diagnostics = diagnostics,
                trace = trace,
            ),
        )
    }

    private fun resolvePrompt(
        prompt: PromptDefinition,
        enabledIds: Set<String>,
        input: NormalGenerationInput,
        diagnostics: MutableList<CompilationDiagnostic>,
        trace: MutableList<CompilationTraceEntry>,
    ): ResolvedPrompt? {
        val dynamicContent = when (prompt.identifier) {
            WORLD_INFO_BEFORE_MARKER, WORLD_INFO_AFTER_MARKER, PERSONA_DESCRIPTION_MARKER -> ""
            CHAR_DESCRIPTION_MARKER -> input.character.description
            CHAR_PERSONALITY_MARKER -> input.character.personality
            SCENARIO_MARKER -> input.character.scenario
            else -> prompt.content
        }
        var original: String? = null
        var candidate = dynamicContent
        if (
            prompt.identifier == MAIN_MARKER &&
            prompt.identifier in enabledIds &&
            input.character.systemPrompt.isNotBlank() &&
            !prompt.forbidOverrides
        ) {
            original = expandOrRecord(prompt.content, prompt.identifier, input, diagnostics)?.text
            candidate = input.character.systemPrompt
            trace += trace("character-override", prompt.identifier, "main overridden by Character Snapshot")
        } else if (
            prompt.identifier == JAILBREAK_MARKER &&
            prompt.identifier in enabledIds &&
            input.character.postHistoryInstructions.isNotBlank() &&
            !prompt.forbidOverrides
        ) {
            original = expandOrRecord(prompt.content, prompt.identifier, input, diagnostics)?.text
            candidate = input.character.postHistoryInstructions
            trace += trace("character-override", prompt.identifier, "post-history instruction applied")
        }

        val expanded = expand(candidate, input.character, input.persona, original)
        return when (expanded) {
            is TextExpansionResult.Failure -> {
                diagnostics += expanded.diagnostic.copy(sourceId = prompt.identifier)
                null
            }
            is TextExpansionResult.Success -> ResolvedPrompt(prompt, expanded.text)
        }
    }

    private fun projectHistory(
        input: NormalGenerationInput,
        diagnostics: MutableList<CompilationDiagnostic>,
        trace: MutableList<CompilationTraceEntry>,
    ): List<PreparedMessage> = buildList {
        input.history.forEach { message ->
            val expanded = expand(message.content, input.character, input.persona, original = null)
            when (expanded) {
                is TextExpansionResult.Failure -> diagnostics += expanded.diagnostic.copy(sourceId = message.id)
                is TextExpansionResult.Success -> {
                    add(
                        PreparedMessage(
                            role = message.role,
                            content = expanded.text,
                            origin = PromptOrigin("chat-history", listOf(message.id)),
                            authorName = message.authorName,
                            reasoning = message.reasoning,
                            adapterId = message.adapterId,
                        ),
                    )
                    trace += trace("chat-history", message.id, "projected", message.role, expanded.text)
                }
            }
        }
    }

    private fun projectExamples(
        input: NormalGenerationInput,
        diagnostics: MutableList<CompilationDiagnostic>,
        trace: MutableList<CompilationTraceEntry>,
    ): List<PreparedMessage> = buildList {
        input.character.examples.forEachIndexed { exampleIndex, example ->
            val examplePrefix = expandOptional(
                text = input.preset.newExampleChatPrompt,
                sourceId = "$NEW_EXAMPLE_SOURCE-$exampleIndex",
                input = input,
                diagnostics = diagnostics,
            )
            examplePrefix?.takeIf(String::isNotBlank)?.let { content ->
                add(
                    PreparedMessage(
                        role = MessageRole.SYSTEM,
                        content = content,
                        origin = PromptOrigin("dialogue-example", listOf("$NEW_EXAMPLE_SOURCE-$exampleIndex")),
                    ),
                )
            }
            example.messages.forEachIndexed { messageIndex, message ->
                val sourceId = "example-$exampleIndex-$messageIndex"
                when (val expanded = expand(message.content, input.character, input.persona, original = null)) {
                    is TextExpansionResult.Failure -> diagnostics += expanded.diagnostic.copy(sourceId = sourceId)
                    is TextExpansionResult.Success -> {
                        add(
                            PreparedMessage(
                                role = message.role,
                                content = expanded.text,
                                origin = PromptOrigin("dialogue-example", listOf(sourceId)),
                            ),
                        )
                        trace += trace("dialogue-example", sourceId, "projected", message.role, expanded.text)
                    }
                }
            }
        }
    }

    private fun injectAbsolute(
        chronologicalHistory: List<PreparedMessage>,
        prompts: List<ResolvedPrompt>,
        trace: MutableList<CompilationTraceEntry>,
    ): List<PreparedMessage> {
        if (prompts.isEmpty()) return chronologicalHistory
        val reverseHistory = chronologicalHistory.reversed().toMutableList()
        var totalInserted = 0
        val maxDepth = prompts.maxOfOrNull { it.definition.injectionDepth } ?: return chronologicalHistory
        for (depth in 0..maxDepth) {
            val atDepth = prompts.filter {
                it.definition.injectionDepth == depth && !it.content.isNullOrBlank()
            }
            val roleMessages = mutableListOf<PreparedMessage>()
            atDepth.groupBy { it.definition.injectionOrder }
                .toSortedMap(compareByDescending { it })
                .forEach { (order, orderedPrompts) ->
                    MessageRole.entries.forEach { role ->
                        val matches = orderedPrompts.filter { it.definition.role == role }
                        val content = matches.mapNotNull { it.content?.trim() }
                            .filter(String::isNotEmpty)
                            .joinToString("\n")
                        if (content.isNotEmpty()) {
                            roleMessages += PreparedMessage(
                                role = role,
                                content = content,
                                origin = PromptOrigin("depth-injection", matches.map { it.definition.identifier }),
                            )
                            trace += CompilationTraceEntry(
                                stage = "depth-injection",
                                sourceIds = matches.map { it.definition.identifier },
                                decision = "depth=$depth order=$order",
                                role = role,
                                content = content,
                            )
                        }
                    }
                }
            if (roleMessages.isNotEmpty()) {
                val index = (depth + totalInserted).coerceAtMost(reverseHistory.size)
                reverseHistory.addAll(index, roleMessages)
                totalInserted += roleMessages.size
            }
        }
        return reverseHistory.reversed()
    }

    private fun expandOptional(
        text: String,
        sourceId: String,
        input: NormalGenerationInput,
        diagnostics: MutableList<CompilationDiagnostic>,
    ): String? = when (val result = expand(text, input.character, input.persona, original = null)) {
        is TextExpansionResult.Failure -> {
            diagnostics += result.diagnostic.copy(sourceId = sourceId)
            null
        }
        is TextExpansionResult.Success -> result.text
    }

    private fun expandOrRecord(
        text: String,
        sourceId: String,
        input: NormalGenerationInput,
        diagnostics: MutableList<CompilationDiagnostic>,
    ): TextExpansionResult.Success? = when (val result = expand(text, input.character, input.persona, original = null)) {
        is TextExpansionResult.Failure -> {
            diagnostics += result.diagnostic.copy(sourceId = sourceId)
            null
        }
        is TextExpansionResult.Success -> result
    }

    private fun expand(
        text: String,
        character: CharacterSnapshot,
        persona: Persona,
        original: String?,
    ): TextExpansionResult {
        var failure: CompilationDiagnostic? = null
        val expanded = macroPattern.replace(text) { match ->
            when (val macro = match.groupValues[1].trim().lowercase()) {
                "char" -> character.name
                "user" -> persona.name
                "original" -> original ?: run {
                    failure = error(
                        "ORIGINAL_MACRO_UNAVAILABLE",
                        "{{original}} is only available while applying an override",
                    )
                    match.value
                }
                else -> {
                    failure = error("UNSUPPORTED_MACRO", "Unsupported macro: {{$macro}}")
                    match.value
                }
            }
        }
        return failure?.let(TextExpansionResult::Failure) ?: TextExpansionResult.Success(expanded)
    }

    private fun ResolvedPrompt.toPreparedMessage(content: String) = PreparedMessage(
        role = definition.role,
        content = content,
        origin = PromptOrigin("prompt", listOf(definition.identifier)),
    )

    private fun error(code: String, message: String, sourceId: String? = null) = CompilationDiagnostic(
        severity = DiagnosticSeverity.ERROR,
        code = code,
        message = message,
        sourceId = sourceId,
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
    )

    companion object {
        private const val NORMAL_TRIGGER = "normal"
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

        private val supportedMarkers = setOf(
            WORLD_INFO_BEFORE_MARKER,
            WORLD_INFO_AFTER_MARKER,
            CHAR_DESCRIPTION_MARKER,
            CHAR_PERSONALITY_MARKER,
            SCENARIO_MARKER,
            PERSONA_DESCRIPTION_MARKER,
            DIALOGUE_EXAMPLES_MARKER,
            CHAT_HISTORY_MARKER,
        )
        private val macroPattern = Regex("\\{\\{([^{}]+)}}")
    }
}
