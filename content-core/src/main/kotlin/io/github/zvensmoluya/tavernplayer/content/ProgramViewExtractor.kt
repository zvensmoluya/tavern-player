package io.github.zvensmoluya.tavernplayer.content

import java.net.URI
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

class ProgramViewExtractor {
    fun extract(character: CharacterAsset): ProgramView {
        val sanitizer = ProgramSanitizer()
        val blocks = mutableListOf<ProgramBlock>()

        character.regexScripts.forEachIndexed { index, regex ->
            if (ACTIVE_MARKUP.containsMatchIn(regex.replaceString)) {
                blocks += ProgramBlock(
                    id = "markup-${blocks.size + 1}",
                    kind = ProgramBlockKind.ACTIVE_MARKUP,
                    sourcePath = "data.extensions.regex_scripts[$index].replaceString",
                    name = sanitizer.sanitize(regex.name),
                    language = "html",
                    content = sanitizer.sanitize(regex.replaceString),
                    originalSha256 = regex.replaceString.sha256(),
                    enabled = !regex.disabled,
                    triggerPattern = sanitizer.sanitize(regex.findRegex),
                    triggerMatchMode = observedTriggerMatchMode(
                        regex.findRegex,
                        listOf(character.firstMessage) + character.alternateFirstMessages,
                    ),
                    placements = regex.placements.map(RegexPlacement::wireValue).sorted(),
                )
            }
        }

        collectScriptObjects(character.extensions).forEach { script ->
            blocks += ProgramBlock(
                id = "script-${blocks.size + 1}",
                kind = ProgramBlockKind.SCRIPT,
                sourcePath = script.path,
                name = sanitizer.sanitize(script.name),
                language = "javascript",
                content = sanitizer.sanitize(script.content),
                originalSha256 = script.content.sha256(),
                enabled = script.enabled,
            )
        }

        val stateProtocolHints = extractStateProtocolHints(character, sanitizer)
        val programText = blocks.joinToString("\n") { it.content }
        val observedCapabilities = detectCapabilities(programText).toMutableSet()
        val referencedVariables = detectVariables(programText).toMutableSet()
        if (stateProtocolHints.isNotEmpty()) {
            observedCapabilities += setOf("state.read", "state.write")
            referencedVariables += stateProtocolHints.map(ProgramStateProtocolHint::variableName).filter(String::isNotBlank)
        }
        val omitted = listOf(
            "description" to character.description,
            "personality" to character.personality,
            "scenario" to character.scenario,
            "firstMessage" to character.firstMessage,
            "messageExamples" to character.rawMessageExamples,
            "systemPrompt" to character.systemPrompt,
            "postHistoryInstructions" to character.postHistoryInstructions,
            "creatorNotes" to character.creatorNotes,
        ).mapNotNull { (field, content) ->
            content.takeIf(String::isNotBlank)?.let { OmittedContent(field, it.length, it.sha256()) }
        }

        return ProgramView(
            sourceSha256 = character.sourceSha256,
            programBlocks = blocks,
            worldBookHandles = character.worldBooks.flatMapIndexed { bookIndex, book ->
                book.entries.map { entry ->
                    WorldBookHandle(
                        handle = "worldbook:$bookIndex:${entry.id}",
                        name = sanitizer.sanitize(entry.name.ifBlank { entry.comment }),
                        enabled = entry.enabled,
                        contentChars = entry.content.length,
                        contentSha256 = entry.content.sha256(),
                    )
                }
            },
            stateProtocolHints = stateProtocolHints,
            dependencies = sanitizer.dependencies(),
            observedCapabilities = observedCapabilities.sorted(),
            referencedVariables = referencedVariables.sorted(),
            omittedContent = omitted,
            redactions = sanitizer.redactions(),
        )
    }

    private fun observedTriggerMatchMode(pattern: String, messages: List<String>): String? {
        val literal = pattern.trim()
        if (literal.isEmpty() || literal.any { it in "\\.^$|?*+()[]{}" }) return null
        if (messages.any { it.trim() == literal }) return AdaptationTriggerType.MESSAGE_EXACT.name
        if (messages.any { it.contains(literal) }) return AdaptationTriggerType.MESSAGE_CONTAINS.name
        return null
    }

    private fun extractStateProtocolHints(
        character: CharacterAsset,
        sanitizer: ProgramSanitizer,
    ): List<ProgramStateProtocolHint> {
        val entries = character.worldBooks.flatMap { it.entries }
        val ruleEntries = entries.filter { entry ->
            entry.enabled && UPDATE_VARIABLE_BLOCK.containsMatchIn(entry.content) && UPDATE_SET_CALL.containsMatchIn(entry.content)
        }
        if (ruleEntries.isEmpty()) return emptyList()
        val variableName = ruleEntries.firstNotNullOfOrNull { entry ->
            MESSAGE_VARIABLE_MACRO.find(entry.content)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)
        }.orEmpty()
        val updatePaths = ruleEntries.flatMap { entry ->
            UPDATE_SET_PATH.findAll(entry.content).map { it.groupValues[1] }.filter(::validStatePath).toList()
        }.toSet()
        val values = linkedMapOf<String, ProgramStateValueHint>()
        entries.filter { entry ->
            entry.name.contains("initvar", ignoreCase = true) || entry.comment.contains("initvar", ignoreCase = true)
        }.forEach { entry ->
            val root = runCatching { Json.parseToJsonElement(entry.content) }.getOrNull() ?: return@forEach
            collectStateValueHints(root, emptyList(), sanitizer, values)
        }
        updatePaths.filterNot(values::containsKey).forEach { path ->
            values[path] = ProgramStateValueHint(path, AdaptationStateType.STRING, JsonPrimitive(""))
        }
        if (values.isEmpty()) return emptyList()
        return listOf(
            ProgramStateProtocolHint(
                dialect = AdaptationMessageStateDialect.UPDATE_VARIABLE_SET_V1.name,
                variableName = sanitizer.sanitize(variableName),
                values = values.values.sortedBy(ProgramStateValueHint::path),
            ),
        )
    }

    private fun collectStateValueHints(
        element: JsonElement,
        path: List<String>,
        sanitizer: ProgramSanitizer,
        target: MutableMap<String, ProgramStateValueHint>,
    ) {
        if (element is JsonObject) {
            element.entries.sortedBy { it.key }.forEach { (key, child) ->
                collectStateValueHints(child, path + key, sanitizer, target)
            }
            return
        }
        val primitive = when (element) {
            is JsonArray -> element.firstOrNull() as? JsonPrimitive
            is JsonPrimitive -> element
        } ?: return
        val joined = path.joinToString(".")
        if (!validStatePath(joined)) return
        val hint = when {
            primitive.isString -> ProgramStateValueHint(joined, AdaptationStateType.STRING, JsonPrimitive(sanitizer.sanitize(primitive.content)))
            primitive.booleanOrNull != null -> ProgramStateValueHint(joined, AdaptationStateType.BOOLEAN, primitive)
            primitive.doubleOrNull?.isFinite() == true -> ProgramStateValueHint(joined, AdaptationStateType.NUMBER, primitive)
            else -> null
        }
        if (hint != null) target[joined] = hint
    }

    private fun validStatePath(value: String): Boolean = value.length <= 256 && STATE_PATH.matches(value)

    private data class ScriptObject(
        val path: String,
        val name: String,
        val content: String,
        val enabled: Boolean,
    )

    private fun collectScriptObjects(root: JsonObject): List<ScriptObject> {
        val scripts = mutableListOf<ScriptObject>()

        fun visit(element: JsonElement, path: String) {
            when (element) {
                is JsonArray -> element.forEachIndexed { index, child -> visit(child, "$path[$index]") }
                is JsonObject -> {
                    val content = (element["content"] as? JsonPrimitive)?.contentOrNull
                    val looksLikeScript = path.contains("script", ignoreCase = true) ||
                        (element["type"] as? JsonPrimitive)?.contentOrNull.equals("script", ignoreCase = true)
                    if (looksLikeScript && !content.isNullOrBlank()) {
                        val name = (element["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        val enabled = (element["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true
                        scripts += ScriptObject("$path.content", name, content, enabled)
                    }
                    element.forEach { (key, child) -> visit(child, "$path.$key") }
                }
                else -> Unit
            }
        }

        visit(root, "data.extensions")
        return scripts.distinctBy { it.path }
    }

    private fun detectCapabilities(text: String): List<String> = CAPABILITIES
        .filterValues { patterns -> patterns.any { it.containsMatchIn(text) } }
        .keys
        .sorted()

    private fun detectVariables(text: String): List<String> = buildSet {
        FUNCTION_VARIABLE.findAll(text).forEach { match -> add(match.groupValues[2]) }
        MACRO_VARIABLE.findAll(text).forEach { match -> add(match.groupValues[1].trim()) }
    }.filter(String::isNotBlank).sorted()

    private class ProgramSanitizer {
        private val dependencyByUrl = linkedMapOf<String, ProgramDependency>()
        private val redactionCounts = linkedMapOf<String, Int>()

        fun sanitize(source: String): String {
            var value = DATA_URI.replace(source) { match ->
                count("inline-data")
                "data:<redacted>;bytes~${match.value.length}"
            }
            value = URL.replace(value) { match ->
                val raw = match.value.trimEnd('.', ',', ';')
                val locator = sanitizeUrl(raw)
                val dependency = dependencyByUrl.getOrPut(raw) {
                    ProgramDependency(
                        id = "dependency-${dependencyByUrl.size + 1}",
                        kind = "remote-url",
                        locator = locator,
                    )
                }
                "dependency://${dependency.id}"
            }
            value = BEARER_SECRET.replace(value) {
                count("credential")
                "Bearer <redacted-secret>"
            }
            value = SECRET_ASSIGNMENT.replace(value) { match ->
                count("credential")
                "${match.groupValues[1]}<redacted-secret>"
            }
            value = WINDOWS_PATH.replace(value) {
                count("local-path")
                "<redacted-local-path>"
            }
            value = HOME_PATH.replace(value) {
                count("local-path")
                "<redacted-local-path>"
            }
            return value
        }

        fun dependencies(): List<ProgramDependency> = dependencyByUrl.values.toList()

        fun redactions(): List<ProgramRedaction> = redactionCounts
            .map { (kind, count) -> ProgramRedaction(kind, count) }

        private fun count(kind: String) {
            redactionCounts[kind] = (redactionCounts[kind] ?: 0) + 1
        }

        private fun sanitizeUrl(raw: String): String = runCatching {
            val uri = URI(raw)
            URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toASCIIString()
        }.getOrDefault("<redacted-url>")
    }

    companion object {
        private val ACTIVE_MARKUP = Regex("<(?:html|style|script|form|button|input|select|textarea)\\b", RegexOption.IGNORE_CASE)
        private val URL = Regex("https?://[^\\s'\"<>`)]+", RegexOption.IGNORE_CASE)
        private val DATA_URI = Regex("data:[^\\s'\"<>]+", RegexOption.IGNORE_CASE)
        private val BEARER_SECRET = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{8,}")
        private val SECRET_ASSIGNMENT = Regex(
            "(?i)(['\"]?(?:api[_-]?key|access[_-]?token|auth[_-]?token|password|secret)['\"]?\\s*[:=]\\s*['\"]?)[^'\"\\s,;}]{6,}",
        )
        private val WINDOWS_PATH = Regex("(?i)\\b[A-Z]:\\\\+(?:Users|Documents and Settings)\\\\+[^\\s'\"<>]+")
        private val HOME_PATH = Regex("/(?:home|Users)/[^\\s'\"<>]+")
        private val FUNCTION_VARIABLE = Regex(
            "(?i)\\b(getvar|setvar|addvar|incvar|decvar|getglobalvar|setglobalvar|get_message_variable|set_message_variable)\\s*\\(\\s*['\"]([^'\"]+)['\"]",
        )
        private val MACRO_VARIABLE = Regex(
            "(?i)\\{\\{\\s*(?:getvar|getglobalvar|get_message_variable)::([^}]+)\\}\\}",
        )
        private val UPDATE_VARIABLE_BLOCK = Regex("<UpdateVariable>[\\s\\S]*?</UpdateVariable>")
        private val UPDATE_SET_CALL = Regex("(?m)^\\s*_\\.set\\s*\\(")
        private val UPDATE_SET_PATH = Regex("(?m)^\\s*_\\.set\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*,")
        private val MESSAGE_VARIABLE_MACRO = Regex("(?i)\\{\\{\\s*get_message_variable::([^},]+)")
        private val STATE_PATH = Regex("[^.\\s'\"(){}\\[\\]\$]+(?:\\.[^.\\s'\"(){}\\[\\]\$]+){0,15}")
        private val CAPABILITIES = linkedMapOf(
            "chat.read" to listOf(Regex("\\bgetChatMessages\\b"), Regex("\\bgetCurrentMessageId\\b")),
            "chat.write" to listOf(Regex("\\bsetChatMessage\\b"), Regex("#send_textarea"), Regex("\\bcreateChatMessages\\b")),
            "event.subscribe" to listOf(Regex("\\beventOn\\b"), Regex("addEventListener\\s*\\(")),
            "event.emit" to listOf(Regex("\\beventEmit\\b")),
            "generation.request" to listOf(Regex("\\bgenerate\\s*\\(")),
            "state.read" to listOf(Regex("(?i)\\b(?:getvar|getglobalvar|get_message_variable)\\b")),
            "state.write" to listOf(Regex("(?i)\\b(?:setvar|addvar|incvar|decvar|setglobalvar|set_message_variable)\\b")),
            "slash.execute" to listOf(Regex("\\btriggerSlash\\b")),
            "worldbook.control" to listOf(Regex("(?i)\\b(?:worldbook|lorebook)\\b")),
        )
    }
}

internal fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
