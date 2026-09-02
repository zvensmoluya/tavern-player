package io.github.zvensmoluya.tavernplayer.content

import java.net.URI
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

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

        val programText = blocks.joinToString("\n") { it.content }
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
                        name = sanitizer.sanitize(entry.name),
                        enabled = entry.enabled,
                        contentChars = entry.content.length,
                        contentSha256 = entry.content.sha256(),
                    )
                }
            },
            dependencies = sanitizer.dependencies(),
            observedCapabilities = detectCapabilities(programText),
            referencedVariables = detectVariables(programText),
            omittedContent = omitted,
            redactions = sanitizer.redactions(),
        )
    }

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
            "(?i)\\{\\{\\s*(?:getvar|getglobalvar|get_message_variable)::([^}]+)}}",
        )
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
