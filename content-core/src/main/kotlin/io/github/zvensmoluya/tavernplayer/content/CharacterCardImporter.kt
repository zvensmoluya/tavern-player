package io.github.zvensmoluya.tavernplayer.content

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.zip.CRC32
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class CharacterCardImporter(
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    fun import(sourceBytes: ByteArray, sourceName: String = ""): CharacterImportResult {
        val diagnostics = mutableListOf<CompatibilityDiagnostic>()
        if (sourceBytes.isEmpty()) return rejected("EMPTY_SOURCE", "角色卡文件为空")
        if (sourceBytes.size > MAX_SOURCE_BYTES) {
            return rejected("SOURCE_TOO_LARGE", "角色卡超过 32 MiB 导入上限")
        }

        val format: CharacterSourceFormat
        val jsonText: String
        try {
            if (sourceBytes.startsWith(PNG_SIGNATURE)) {
                format = CharacterSourceFormat.PNG
                jsonText = decodePng(sourceBytes, diagnostics)
            } else {
                format = CharacterSourceFormat.JSON
                jsonText = decodeUtf8(sourceBytes).removePrefix("\uFEFF")
                if (!jsonText.trimStart().startsWith('{')) {
                    return rejected("UNSUPPORTED_FORMAT", "仅支持 PNG/APNG 或 JSON Character Card")
                }
                if (sourceBytes.size > MAX_METADATA_BYTES) {
                    return rejected("METADATA_TOO_LARGE", "JSON 角色卡超过 8 MiB metadata 上限")
                }
            }
        } catch (error: ImportFailure) {
            return CharacterImportResult.Rejected(
                diagnostics + CompatibilityDiagnostic(
                    CompatibilitySeverity.ERROR,
                    error.code,
                    error.message ?: "角色卡解码失败",
                    sourceName.takeIf(String::isNotBlank),
                ),
            )
        } catch (error: CharacterEncodingException) {
            return rejected("INVALID_UTF8", "角色卡 metadata 不是有效 UTF-8")
        } catch (error: Exception) {
            return rejected("MALFORMED_SOURCE", error.message ?: "角色卡解码失败")
        }

        val raw = try {
            JSON.parseToJsonElement(jsonText) as? JsonObject
                ?: return rejected("INVALID_CARD_ROOT", "角色卡 JSON 根节点必须是对象")
        } catch (error: Exception) {
            return rejected("MALFORMED_JSON", "角色卡 JSON 无法解析：${error.message.orEmpty()}")
        }

        return try {
            val sourceSha256 = sourceBytes.sha256()
            val asset = normalize(raw, format, sourceSha256, diagnostics)
            val finalDiagnostics = diagnostics.distinctBy { Triple(it.code, it.message, it.source) }
            CharacterImportResult.Ready(
                character = asset.copy(diagnostics = finalDiagnostics),
                sourceBytes = sourceBytes.copyOf(),
                diagnostics = finalDiagnostics,
            )
        } catch (error: ImportFailure) {
            CharacterImportResult.Rejected(
                diagnostics + CompatibilityDiagnostic(
                    CompatibilitySeverity.ERROR,
                    error.code,
                    error.message ?: "角色卡规范化失败",
                    sourceName.takeIf(String::isNotBlank),
                ),
            )
        }
    }

    private fun normalize(
        raw: JsonObject,
        format: CharacterSourceFormat,
        sourceSha256: String,
        diagnostics: MutableList<CompatibilityDiagnostic>,
    ): CharacterAsset {
        val spec = raw.string("spec", diagnostics)
        val specVersion = raw.string("spec_version", diagnostics)
        val generation = when (spec) {
            "chara_card_v3" -> CharacterCardGeneration.V3
            "chara_card_v2" -> CharacterCardGeneration.V2
            null, "" -> CharacterCardGeneration.V1
            else -> throw ImportFailure("UNSUPPORTED_CARD_SPEC", "不支持的 Character Card spec：$spec")
        }
        val data = when (generation) {
            CharacterCardGeneration.V1 -> raw
            CharacterCardGeneration.V2, CharacterCardGeneration.V3 -> raw["data"] as? JsonObject
                ?: throw ImportFailure("MISSING_CARD_DATA", "V2/V3 角色卡缺少 data 对象")
        }

        if (generation != CharacterCardGeneration.V1) {
            val defaulted = OPTIONAL_CARD_FIELDS.filterNot(data::containsKey)
            if (defaulted.isNotEmpty()) {
                diagnostics.warning(
                    "MISSING_OPTIONAL_FIELDS_DEFAULTED",
                    "缺失可选字段已按 Character Card 默认值补齐：${defaulted.joinToString()}",
                    "data",
                )
            }
        }

        if (generation == CharacterCardGeneration.V3) {
            val version = specVersion?.toDoubleOrNull()
            if (version == null) {
                diagnostics.warning("INVALID_SPEC_VERSION", "无法识别 V3 spec_version，已按兼容模式导入")
            } else if (version != 3.0) {
                diagnostics.warning(
                    "FUTURE_OR_NONSTANDARD_V3",
                    "卡片 spec_version=$specVersion，当前按 CCv3 兼容子集导入并保留未知字段",
                )
            }
        }

        val legacyName = if (generation == CharacterCardGeneration.V1) {
            data.string("name", diagnostics) ?: data.string("char_name", diagnostics)
        } else {
            data.string("name", diagnostics)
        }
        val name = legacyName?.trim().orEmpty()
        if (name.isEmpty()) throw ImportFailure("MISSING_CHARACTER_NAME", "角色卡缺少可用的 name")

        val extensions = data.objectOrEmpty("extensions", diagnostics)
        val worldBooks = buildList {
            (data["character_book"] as? JsonObject)?.let { book ->
                add(parseWorldBook(book, sourceSha256, diagnostics))
            }
        }
        val externalWorld = extensions.string("world", diagnostics)?.trim().orEmpty()
        if (externalWorld.isNotEmpty() && worldBooks.none { it.name == externalWorld }) {
            diagnostics.warning(
                "UNRESOLVED_EXTERNAL_WORLD_BOOK",
                "卡片引用外部 World Book“$externalWorld”，Tavern Player 不解析 ST global lorebook",
                "data.extensions.world",
            )
        }

        val regexScripts = parseRegexScripts(extensions["regex_scripts"], diagnostics)
        val depthPrompt = (extensions["depth_prompt"] as? JsonObject)?.let { depth ->
            val content = depth.string("prompt", diagnostics).orEmpty()
            content.takeIf(String::isNotBlank)?.let {
                CharacterDepthPrompt(
                    content = it,
                    role = depth.role("role", ContentRole.SYSTEM),
                    depth = depth.int("depth", diagnostics) ?: 4,
                    order = depth.int("order", diagnostics) ?: 100,
                )
            }
        }

        val description = if (generation == CharacterCardGeneration.V1 && data["char_name"] != null) {
            data.string("char_persona", diagnostics).orEmpty()
        } else {
            data.string("description", diagnostics).orEmpty()
        }
        val firstMessage = if (generation == CharacterCardGeneration.V1 && data["char_name"] != null) {
            data.string("char_greeting", diagnostics).orEmpty()
        } else {
            data.string("first_mes", diagnostics).orEmpty()
        }
        val rawExamples = if (generation == CharacterCardGeneration.V1 && data["char_name"] != null) {
            data.string("example_dialogue", diagnostics).orEmpty()
        } else {
            data.string("mes_example", diagnostics).orEmpty()
        }
        val scenario = if (generation == CharacterCardGeneration.V1 && data["char_name"] != null) {
            data.string("world_scenario", diagnostics).orEmpty()
        } else {
            data.string("scenario", diagnostics).orEmpty()
        }

        detectOpaqueCapabilities(extensions, regexScripts, worldBooks, diagnostics)

        return CharacterAsset(
            id = idFactory(),
            sourceSha256 = sourceSha256,
            sourceFormat = format,
            cardGeneration = generation,
            spec = spec,
            specVersion = specVersion,
            name = name,
            nickname = data.string("nickname", diagnostics)?.takeIf(String::isNotBlank),
            description = description,
            personality = data.string("personality", diagnostics).orEmpty(),
            scenario = scenario,
            firstMessage = firstMessage,
            alternateFirstMessages = data.stringList("alternate_greetings", diagnostics),
            groupOnlyGreetings = data.stringList("group_only_greetings", diagnostics),
            rawMessageExamples = rawExamples,
            systemPrompt = data.string("system_prompt", diagnostics).orEmpty(),
            postHistoryInstructions = data.string("post_history_instructions", diagnostics).orEmpty(),
            creatorNotes = data.string("creator_notes", diagnostics)
                ?: data.string("creatorcomment", diagnostics).orEmpty(),
            creatorNotesMultilingual = data.stringMap("creator_notes_multilingual", diagnostics),
            tags = data.stringList("tags", diagnostics),
            creator = data.string("creator", diagnostics).orEmpty(),
            characterVersion = data.string("character_version", diagnostics).orEmpty(),
            sourceLinks = data.stringList("source", diagnostics),
            creationDateEpochSeconds = data.long("creation_date", diagnostics),
            modificationDateEpochSeconds = data.long("modification_date", diagnostics),
            depthPrompt = depthPrompt,
            worldBooks = worldBooks,
            regexScripts = regexScripts,
            assets = buildList {
                if (format == CharacterSourceFormat.PNG) {
                    add(assetReference("icon", "ccdefault:", "main", "png", 0))
                }
                addAll(parseAssets(data["assets"], diagnostics, if (format == CharacterSourceFormat.PNG) 1 else 0))
            },
            extensions = extensions,
            rawCard = raw,
        )
    }

    private fun decodePng(
        bytes: ByteArray,
        diagnostics: MutableList<CompatibilityDiagnostic>,
    ): String {
        var position = PNG_SIGNATURE.size
        val ccv3 = mutableListOf<String>()
        val chara = mutableListOf<String>()
        var ended = false
        while (position < bytes.size) {
            if (bytes.size - position < 12) throw ImportFailure("TRUNCATED_PNG", "PNG chunk header 不完整")
            val length = bytes.readUInt32(position)
            if (length > Int.MAX_VALUE.toLong()) throw ImportFailure("PNG_CHUNK_TOO_LARGE", "PNG chunk 长度超出范围")
            val intLength = length.toInt()
            val dataStart = position + 8
            val dataEnd = dataStart.toLong() + intLength
            val chunkEnd = dataEnd + 4
            if (dataEnd > bytes.size || chunkEnd > bytes.size) {
                throw ImportFailure("TRUNCATED_PNG", "PNG chunk 数据越界")
            }
            val typeBytes = bytes.copyOfRange(position + 4, position + 8)
            val type = String(typeBytes, StandardCharsets.US_ASCII)
            val data = bytes.copyOfRange(dataStart, dataEnd.toInt())
            val expectedCrc = bytes.readUInt32(dataEnd.toInt())
            val crc = CRC32().apply {
                update(typeBytes)
                update(data)
            }.value
            if (crc != expectedCrc) throw ImportFailure("PNG_CRC_MISMATCH", "PNG $type chunk CRC 校验失败")

            if (type == "tEXt") {
                val separator = data.indexOf(0)
                if (separator <= 0) throw ImportFailure("INVALID_TEXT_CHUNK", "PNG tEXt chunk 缺少 keyword 分隔符")
                val keyword = String(data, 0, separator, StandardCharsets.ISO_8859_1)
                if (keyword == "ccv3" || keyword == "chara") {
                    val encoded = String(
                        data,
                        separator + 1,
                        data.size - separator - 1,
                        StandardCharsets.ISO_8859_1,
                    )
                    if (encoded.length > MAX_BASE64_METADATA_CHARS) {
                        throw ImportFailure("METADATA_TOO_LARGE", "PNG 角色卡 metadata 超过 8 MiB 上限")
                    }
                    val decoded = try {
                        Base64.getDecoder().decode(encoded)
                    } catch (error: IllegalArgumentException) {
                        throw ImportFailure("INVALID_BASE64", "PNG $keyword metadata 不是有效 Base64", error)
                    }
                    if (decoded.size > MAX_METADATA_BYTES) {
                        throw ImportFailure("METADATA_TOO_LARGE", "PNG 角色卡 metadata 超过 8 MiB 上限")
                    }
                    val text = decodeUtf8(decoded)
                    if (keyword == "ccv3") ccv3 += text else chara += text
                }
            }
            position = chunkEnd.toInt()
            if (type == "IEND") {
                ended = true
                break
            }
        }
        if (!ended) throw ImportFailure("MISSING_PNG_IEND", "PNG 缺少 IEND chunk")
        if (ccv3.size > 1 || chara.size > 1) {
            diagnostics.warning("DUPLICATE_CARD_CHUNK", "PNG 含重复 Character Card metadata，采用第一个")
        }
        if (ccv3.isNotEmpty() && chara.isNotEmpty()) {
            diagnostics.warning("CCV3_PRECEDENCE", "PNG 同时包含 ccv3 与 chara，已按规范采用 ccv3")
        }
        return ccv3.firstOrNull() ?: chara.firstOrNull()
        ?: throw ImportFailure("MISSING_CARD_METADATA", "PNG 中没有 ccv3 或 chara tEXt metadata")
    }

    private fun parseWorldBook(
        raw: JsonObject,
        sourceSha256: String,
        diagnostics: MutableList<CompatibilityDiagnostic>,
    ): WorldBookDefinition {
        val bookName = raw.string("name", diagnostics).orEmpty()
        val bookId = "book-${sourceSha256.take(16)}"
        val entries = (raw["entries"] as? JsonArray)?.mapIndexedNotNull { index, element ->
            val entry = element as? JsonObject
            if (entry == null) {
                diagnostics.warning("INVALID_WORLD_BOOK_ENTRY", "World Book 第 $index 项不是对象，已跳过")
                return@mapIndexedNotNull null
            }
            parseWorldBookEntry(entry, bookId, index, diagnostics)
        }.orEmpty()
        if (raw["entries"] != null && raw["entries"] !is JsonArray) {
            diagnostics.warning("INVALID_WORLD_BOOK_ENTRIES", "character_book.entries 不是数组，已按空列表处理")
        }
        return WorldBookDefinition(
            id = bookId,
            name = bookName,
            description = raw.string("description", diagnostics).orEmpty(),
            scanDepth = raw.int("scan_depth", diagnostics),
            tokenBudget = raw.int("token_budget", diagnostics),
            recursiveScanning = raw.boolean("recursive_scanning", diagnostics),
            entries = entries,
            extensions = raw.objectOrEmpty("extensions", diagnostics),
        )
    }

    private fun parseWorldBookEntry(
        raw: JsonObject,
        bookId: String,
        index: Int,
        diagnostics: MutableList<CompatibilityDiagnostic>,
    ): WorldBookEntryDefinition {
        val extensions = raw.objectOrEmpty("extensions", diagnostics)
        val sourceId = raw.primitiveText("id")
        val positionValue = extensions.int("position", diagnostics)
        val position = when (positionValue) {
            0 -> WorldBookPosition.BEFORE_CHARACTER
            1 -> WorldBookPosition.AFTER_CHARACTER
            2 -> WorldBookPosition.AUTHOR_NOTE_TOP
            3 -> WorldBookPosition.AUTHOR_NOTE_BOTTOM
            4 -> WorldBookPosition.AT_DEPTH
            5 -> WorldBookPosition.EXAMPLES_TOP
            6 -> WorldBookPosition.EXAMPLES_BOTTOM
            7 -> WorldBookPosition.OUTLET
            null -> when (raw.string("position", diagnostics)) {
                "after_char" -> WorldBookPosition.AFTER_CHARACTER
                else -> WorldBookPosition.BEFORE_CHARACTER
            }
            else -> {
                diagnostics.warning("UNSUPPORTED_WORLD_BOOK_POSITION", "World Book entry $index 使用未知 position=$positionValue，按 before 处理")
                WorldBookPosition.BEFORE_CHARACTER
            }
        }
        val probability = (extensions.int("probability", diagnostics) ?: 100).coerceIn(0, 100)
        return WorldBookEntryDefinition(
            id = "$bookId:entry:${sourceId ?: index}",
            sourceId = sourceId,
            name = raw.string("name", diagnostics).orEmpty(),
            comment = raw.string("comment", diagnostics).orEmpty(),
            keys = raw.stringList("keys", diagnostics),
            secondaryKeys = raw.stringList("secondary_keys", diagnostics),
            content = raw.string("content", diagnostics).orEmpty(),
            enabled = raw.boolean("enabled", diagnostics) ?: true,
            constant = raw.boolean("constant", diagnostics) ?: false,
            selective = raw.boolean("selective", diagnostics) ?: false,
            secondaryLogic = WorldBookSecondaryLogic.fromSourceValue(extensions.int("selectiveLogic", diagnostics) ?: 0),
            insertionOrder = raw.int("insertion_order", diagnostics) ?: 100,
            priority = raw.int("priority", diagnostics),
            position = position,
            depth = extensions.int("depth", diagnostics) ?: 4,
            role = extensions.role("role", ContentRole.SYSTEM),
            outletName = extensions.string("outlet_name", diagnostics).orEmpty(),
            useRegex = raw.boolean("use_regex", diagnostics) ?: false,
            caseSensitive = raw.boolean("case_sensitive", diagnostics)
                ?: extensions.boolean("case_sensitive", diagnostics),
            matchWholeWords = extensions.boolean("match_whole_words", diagnostics),
            probability = probability,
            useProbability = extensions.boolean("useProbability", diagnostics) ?: true,
            group = extensions.string("group", diagnostics).orEmpty(),
            groupOverride = extensions.boolean("group_override", diagnostics) ?: false,
            groupWeight = extensions.int("group_weight", diagnostics) ?: 100,
            useGroupScoring = extensions.boolean("use_group_scoring", diagnostics) ?: false,
            excludeRecursion = extensions.boolean("exclude_recursion", diagnostics) ?: false,
            preventRecursion = extensions.boolean("prevent_recursion", diagnostics) ?: false,
            delayUntilRecursion = extensions.boolean("delay_until_recursion", diagnostics) ?: false,
            scanDepth = extensions.int("scan_depth", diagnostics),
            sticky = extensions.int("sticky", diagnostics)?.coerceAtLeast(0) ?: 0,
            cooldown = extensions.int("cooldown", diagnostics)?.coerceAtLeast(0) ?: 0,
            delay = extensions.int("delay", diagnostics)?.coerceAtLeast(0) ?: 0,
            extensions = extensions,
        )
    }

    private fun parseRegexScripts(
        element: JsonElement?,
        diagnostics: MutableList<CompatibilityDiagnostic>,
    ): List<RegexDefinition> {
        if (element == null || element is JsonNull) return emptyList()
        val array = element as? JsonArray ?: run {
            diagnostics.warning("INVALID_REGEX_SCRIPTS", "extensions.regex_scripts 不是数组，已忽略")
            return emptyList()
        }
        return array.mapIndexedNotNull { index, value ->
            val raw = value as? JsonObject
            if (raw == null) {
                diagnostics.warning("INVALID_REGEX_SCRIPT", "Regex 第 $index 项不是对象，已跳过")
                return@mapIndexedNotNull null
            }
            val placements = (raw["placement"] as? JsonArray).orEmpty().mapNotNull { item ->
                val wire = (item as? JsonPrimitive)?.intOrNull
                RegexPlacement.entries.firstOrNull { it.wireValue == wire }.also {
                    if (it == null) diagnostics.warning(
                        "UNSUPPORTED_REGEX_PLACEMENT",
                        "Regex 第 $index 项含未知 placement=$wire，已保留原始数据但不执行该 placement",
                    )
                }
            }.toSet()
            if (RegexPlacement.SLASH_COMMAND in placements) {
                diagnostics.warning(
                    "UNSUPPORTED_SLASH_REGEX",
                    "Regex“${raw.string("scriptName", diagnostics).orEmpty()}”包含 Slash Command placement；当前产品不执行 slash/STscript",
                )
            }
            RegexDefinition(
                id = raw.primitiveText("id") ?: "regex-$index",
                name = raw.string("scriptName", diagnostics).orEmpty().ifBlank { "Regex ${index + 1}" },
                findRegex = raw.string("findRegex", diagnostics).orEmpty(),
                replaceString = raw.string("replaceString", diagnostics).orEmpty(),
                trimStrings = raw.stringList("trimStrings", diagnostics),
                placements = placements,
                disabled = raw.boolean("disabled", diagnostics) ?: false,
                markdownOnly = raw.boolean("markdownOnly", diagnostics) ?: false,
                promptOnly = raw.boolean("promptOnly", diagnostics) ?: false,
                runOnEdit = raw.boolean("runOnEdit", diagnostics) ?: false,
                substitutionMode = when (raw.int("substituteRegex", diagnostics) ?: 0) {
                    1 -> RegexSubstitutionMode.RAW
                    2 -> RegexSubstitutionMode.ESCAPED
                    else -> RegexSubstitutionMode.NONE
                },
                minDepth = raw.int("minDepth", diagnostics),
                maxDepth = raw.int("maxDepth", diagnostics),
                raw = raw,
            )
        }
    }

    private fun parseAssets(
        element: JsonElement?,
        diagnostics: MutableList<CompatibilityDiagnostic>,
        indexOffset: Int,
    ): List<CharacterAssetReference> {
        if (element == null || element is JsonNull) return emptyList()
        val array = element as? JsonArray ?: run {
            diagnostics.warning("INVALID_ASSETS", "data.assets 不是数组，已保留原始 JSON 但不使用")
            return emptyList()
        }
        return array.mapIndexedNotNull { index, item ->
            val raw = item as? JsonObject ?: return@mapIndexedNotNull null
            val type = raw.string("type", diagnostics)
            val uri = raw.string("uri", diagnostics)
            val name = raw.string("name", diagnostics)
            val ext = raw.string("ext", diagnostics)
            if (type == null || uri == null || name == null || ext == null) {
                diagnostics.warning("INVALID_ASSET", "V3 asset 第 $index 项缺少必要字段，已保留但不使用")
                null
            } else {
                if (uri.startsWith("http://") || uri.startsWith("https://") || uri.startsWith("embeded://")) {
                    diagnostics.warning("REMOTE_OR_PACKAGED_ASSET_IGNORED", "资产 $name 使用 $uri；当前版本保留 URI 但不抓取")
                }
                if (uri.startsWith("data:") && !SAFE_INLINE_IMAGE.matches(uri.substringBefore(','))) {
                    diagnostics.warning(
                        "UNSUPPORTED_INLINE_ASSET",
                        "资产 $name 不是受支持的内联 PNG/JPEG/WebP data image；URI 已保留但不会解码",
                    )
                }
                assetReference(type, uri, name, ext, index + indexOffset)
            }
        }
    }

    private fun assetReference(
        type: String,
        uri: String,
        name: String,
        extension: String,
        index: Int,
    ): CharacterAssetReference {
        val identitySource = listOf(index.toString(), type, name, extension, uri).joinToString("\u0000")
        return CharacterAssetReference(
            id = "asset-${identitySource.encodeToByteArray().sha256().take(24)}",
            type = type,
            uri = uri,
            name = name,
            extension = extension,
        )
    }

    private fun detectOpaqueCapabilities(
        extensions: JsonObject,
        regexScripts: List<RegexDefinition>,
        worldBooks: List<WorldBookDefinition>,
        diagnostics: MutableList<CompatibilityDiagnostic>,
    ) {
        val scriptKeys = extensions.keys.filter {
            val lower = it.lowercase()
            lower.contains("script") && lower != "regex_scripts"
        }
        if (scriptKeys.any { extensions[it].hasMeaningfulValue() }) {
            diagnostics.warning(
                "THIRD_PARTY_SCRIPT_PRESERVED",
                "检测到第三方脚本扩展（${scriptKeys.joinToString()}）；数据会保留，但不会执行或联网加载",
            )
        }
        val searchable = buildList {
            addAll(extensions.allStrings())
            regexScripts.forEach { add(it.findRegex); add(it.replaceString) }
            worldBooks.flatMap(WorldBookDefinition::entries).forEach { add(it.content) }
        }
        if (searchable.any { UNSUPPORTED_DYNAMIC_MACRO.containsMatchIn(it) }) {
            diagnostics.warning(
                "UNSUPPORTED_DYNAMIC_MACRO",
                "卡片使用第三方动态 Macro（例如 get_message_variable）；将保持原文，不会作为内置变量执行",
            )
        }
        if (regexScripts.any { RICH_ACTIVE_MARKUP.containsMatchIn(it.replaceString) }) {
            diagnostics.warning(
                "ACTIVE_MARKUP_DOWNGRADED",
                "角色 Regex 会生成 HTML/CSS/JS；聊天仅进行安全文本/Markdown 展示",
            )
        }
        val unsupportedEntry = worldBooks.flatMap(WorldBookDefinition::entries).any { entry ->
            entry.extensions.booleanValue("vectorized") == true ||
                !entry.extensions.stringValue("automation_id").isNullOrBlank()
        }
        if (unsupportedEntry) {
            diagnostics.warning(
                "UNSUPPORTED_WORLD_BOOK_EXTENSION",
                "World Book 含 vectorized 或 automation_id 字段；已保留但不参与激活",
            )
        }
    }

    private fun rejected(code: String, message: String) = CharacterImportResult.Rejected(
        listOf(CompatibilityDiagnostic(CompatibilitySeverity.ERROR, code, message)),
    )

    private class ImportFailure(
        val code: String,
        message: String,
        cause: Throwable? = null,
    ) : IllegalArgumentException(message, cause)

    companion object {
        const val MAX_SOURCE_BYTES: Int = 32 * 1024 * 1024
        const val MAX_METADATA_BYTES: Int = 8 * 1024 * 1024
        private const val MAX_BASE64_METADATA_CHARS: Int = (MAX_METADATA_BYTES * 4 / 3) + 8
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        private val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = false
        }
        private val UNSUPPORTED_DYNAMIC_MACRO = Regex(
            "\\{\\{\\s*(?:get_message_variable|set_message_variable|[a-z_]*globalvar)",
            RegexOption.IGNORE_CASE,
        )
        private val RICH_ACTIVE_MARKUP = Regex("<(?:html|style|script)\\b", RegexOption.IGNORE_CASE)
        private val SAFE_INLINE_IMAGE = Regex("(?i)data:image/(?:png|jpeg|webp);base64")
        private val OPTIONAL_CARD_FIELDS = listOf(
            "nickname", "personality", "scenario", "first_mes", "mes_example", "creator_notes",
            "system_prompt", "post_history_instructions", "alternate_greetings", "tags", "creator",
            "character_version", "extensions",
        )
    }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

private fun ByteArray.readUInt32(offset: Int): Long =
    ByteBuffer.wrap(this, offset, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFF_FFFFL

private fun decodeUtf8(bytes: ByteArray): String = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (error: Exception) {
    throw CharacterEncodingException(error)
}

private class CharacterEncodingException(cause: Throwable) : IllegalArgumentException("metadata 不是有效 UTF-8", cause)

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { byte -> "%02x".format(byte) }

private fun MutableList<CompatibilityDiagnostic>.warning(code: String, message: String, source: String? = null) {
    add(CompatibilityDiagnostic(CompatibilitySeverity.WARNING, code, message, source))
}

private fun JsonObject.string(
    name: String,
    diagnostics: MutableList<CompatibilityDiagnostic>,
): String? {
    val value = this[name] ?: return null
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive
    val text = primitive?.takeIf { it.isString }?.contentOrNull
    if (text == null) diagnostics.warning("INVALID_FIELD_TYPE", "$name 应为字符串，已使用默认值", name)
    return text
}

private fun JsonObject.int(
    name: String,
    diagnostics: MutableList<CompatibilityDiagnostic>,
): Int? {
    val value = this[name] ?: return null
    if (value is JsonNull) return null
    val result = (value as? JsonPrimitive)?.intOrNull
    if (result == null) diagnostics.warning("INVALID_FIELD_TYPE", "$name 应为整数，已使用默认值", name)
    return result
}

private fun JsonObject.long(
    name: String,
    diagnostics: MutableList<CompatibilityDiagnostic>,
): Long? {
    val value = this[name] ?: return null
    if (value is JsonNull) return null
    val result = (value as? JsonPrimitive)?.longOrNull
    if (result == null) diagnostics.warning("INVALID_FIELD_TYPE", "$name 应为整数，已使用默认值", name)
    return result
}

private fun JsonObject.boolean(
    name: String,
    diagnostics: MutableList<CompatibilityDiagnostic>,
): Boolean? {
    val value = this[name] ?: return null
    if (value is JsonNull) return null
    val result = (value as? JsonPrimitive)?.booleanOrNull
    if (result == null) diagnostics.warning("INVALID_FIELD_TYPE", "$name 应为布尔值，已使用默认值", name)
    return result
}

private fun JsonObject.stringList(
    name: String,
    diagnostics: MutableList<CompatibilityDiagnostic>,
): List<String> {
    val value = this[name] ?: return emptyList()
    if (value is JsonNull) return emptyList()
    if (name == "tags" && value is JsonPrimitive && value.isString) {
        return value.content.split(',').map(String::trim).filter(String::isNotEmpty)
    }
    val array = value as? JsonArray
    if (array == null) {
        diagnostics.warning("INVALID_FIELD_TYPE", "$name 应为字符串数组，已使用空列表", name)
        return emptyList()
    }
    return array.mapIndexedNotNull { index, element ->
        val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if (text == null) diagnostics.warning("INVALID_FIELD_TYPE", "$name[$index] 不是字符串，已跳过", "$name[$index]")
        text
    }
}

private fun JsonObject.stringMap(
    name: String,
    diagnostics: MutableList<CompatibilityDiagnostic>,
): Map<String, String> {
    val value = this[name] ?: return emptyMap()
    if (value is JsonNull) return emptyMap()
    val obj = value as? JsonObject
    if (obj == null) {
        diagnostics.warning("INVALID_FIELD_TYPE", "$name 应为对象，已使用空对象", name)
        return emptyMap()
    }
    return obj.mapNotNull { (key, element) ->
        val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if (text == null) diagnostics.warning("INVALID_FIELD_TYPE", "$name.$key 不是字符串，已跳过", "$name.$key")
        text?.let { key to it }
    }.toMap()
}

private fun JsonObject.objectOrEmpty(
    name: String,
    diagnostics: MutableList<CompatibilityDiagnostic>,
): JsonObject {
    val value = this[name] ?: return JsonObject(emptyMap())
    if (value is JsonNull) return JsonObject(emptyMap())
    return value as? JsonObject ?: run {
        diagnostics.warning("INVALID_FIELD_TYPE", "$name 应为对象，已使用空对象", name)
        JsonObject(emptyMap())
    }
}

private fun JsonObject.primitiveText(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.role(name: String, default: ContentRole): ContentRole {
    val primitive = this[name] as? JsonPrimitive ?: return default
    primitive.intOrNull?.let { value ->
        return when (value) {
            1 -> ContentRole.USER
            2 -> ContentRole.ASSISTANT
            else -> ContentRole.SYSTEM
        }
    }
    return when (primitive.contentOrNull?.lowercase()) {
        "user" -> ContentRole.USER
        "assistant", "char", "model" -> ContentRole.ASSISTANT
        else -> default
    }
}

private fun JsonElement?.hasMeaningfulValue(): Boolean = when (this) {
    null, JsonNull -> false
    is JsonArray -> isNotEmpty()
    is JsonObject -> isNotEmpty()
    is JsonPrimitive -> contentOrNull?.isNotBlank() == true
}

private fun JsonElement.allStrings(): List<String> = when (this) {
    is JsonPrimitive -> contentOrNull?.let(::listOf).orEmpty()
    is JsonArray -> flatMap(JsonElement::allStrings)
    is JsonObject -> values.flatMap(JsonElement::allStrings)
}

private fun JsonObject.booleanValue(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.stringValue(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull
