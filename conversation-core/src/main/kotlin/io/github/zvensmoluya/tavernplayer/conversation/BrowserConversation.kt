package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.BrowserProgramReader
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Bound by the trusted renderer when an execution frame is created, never chosen by card JS. */
@Serializable
data class BrowserActor(val id: String, val turnId: String? = null, val variantId: String? = null, val scriptId: String? = null)

object BrowserConversation {
    private val json = Json { encodeDefaults = true }

    fun revision(record: ConversationRecord): String = BrowserProgramReader.sha256(buildString {
        append(record.id); append(JsonPrimitive(record.draft)); append(json.encodeToString(record.runtimeState))
        record.turns.forEach { turn ->
            append(turn.id); append(turn.selectedVariantIndex)
            turn.variants.forEach { variant ->
                append(variant.id); append(json.encodeToString(variant.message)); append(variant.status.name)
                append(variant.browserVariables); append(variant.browserHidden)
                append(json.encodeToString(variant.browserHead))
            }
        }
    })

    fun authorize(record: ConversationRecord, actor: BrowserActor, revision: String) {
        require(record.executionMode == ConversationExecutionMode.BROWSER) { "当前会话未启用网页运行" }
        require(revision == revision(record)) { "会话已更新，请等待同步后重试" }
        if (actor.turnId != null) {
            val turn = record.turns.find { it.id == actor.turnId }
            require(turn != null && turn.selected.id == actor.variantId) { "页面所属候选已失效" }
        }
    }

    fun snapshot(record: ConversationRecord): JsonObject = buildJsonObject {
        put("conversationId", record.id); put("revision", revision(record)); put("draft", record.draft)
        put("characterName", record.character.promptName); put("userName", record.persona.name)
        put("chatVariables", record.runtimeState.browserChatVariables)
        put("scriptVariables", JsonObject(record.runtimeState.browserScriptVariables))
        put("mvu", record.runtimeState.mvuState?.data ?: JsonNull)
        putJsonArray("messages") {
            record.turns.forEachIndexed { index, turn -> add(buildJsonObject {
                val selected = turn.selected
                put("message_id", index); put("turnId", turn.id); put("variantId", selected.id)
                put("id", selected.message.id); put("role", turn.role.name.lowercase())
                put("name", selected.message.authorName)
                put("message", selected.message.content); put("sourceText", selected.message.sourceText)
                put("is_hidden", selected.browserHidden); put("swipe_id", turn.selectedVariantIndex)
                put("data", variables(selected)); put("extra", buildJsonObject {})
                putJsonArray("swipes") { turn.variants.forEach { add(it.message.content) } }
                putJsonArray("swipes_data") { turn.variants.forEach { add(variables(it)) } }
                putJsonArray("swipes_info") { turn.variants.forEach { add(buildJsonObject {}) } }
                put("status", selected.status.name)
            }) }
        }
        putJsonArray("worldbooks") {
            record.character.worldBooks.forEach { book -> add(buildJsonObject {
                put("name", book.id)
                put("enabled", record.runtimeState.worldBookActivationOverrides.books[book.id] ?: true)
                putJsonArray("entries") { book.entries.forEachIndexed { index, entry -> add(buildJsonObject {
                    put("uid", entry.sourceId?.toIntOrNull() ?: index); put("player_entry_id", entry.id)
                    put("name", entry.name.ifBlank { entry.comment }); put("comment", entry.comment)
                    putJsonObject("strategy") {
                        put("type", if (entry.constant) "constant" else if (entry.extensions["vectorized"] == JsonPrimitive(true)) "vectorized" else "selective")
                        put("keys", JsonArray(entry.keys.map(::JsonPrimitive)))
                        putJsonObject("keys_secondary") { put("logic", entry.effectiveSecondaryLogic.name.lowercase()); put("keys", JsonArray(entry.secondaryKeys.map(::JsonPrimitive))) }
                        put("scan_depth", entry.scanDepth?.let(::JsonPrimitive) ?: JsonPrimitive("same_as_global"))
                    }
                    putJsonObject("position") {
                        put("type", when (entry.position.name) {
                            "BEFORE_CHARACTER" -> "before_character_definition"; "AFTER_CHARACTER" -> "after_character_definition"
                            "AUTHOR_NOTE_TOP" -> "before_author_note"; "AUTHOR_NOTE_BOTTOM" -> "after_author_note"
                            "EXAMPLES_TOP" -> "before_example_messages"; "EXAMPLES_BOTTOM" -> "after_example_messages"
                            "AT_DEPTH" -> "at_depth"; else -> "outlet"
                        })
                        put("role", entry.role.name.lowercase()); put("depth", entry.depth); put("order", entry.insertionOrder)
                    }
                    put("probability", if (entry.useProbability) entry.probability else 100)
                    putJsonObject("recursion") {
                        put("prevent_incoming", entry.excludeRecursion); put("prevent_outgoing", entry.preventRecursion)
                        put("delay_until", (entry.extensions["delay_until_recursion"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }?.let(::JsonPrimitive)
                            ?: if (entry.delayUntilRecursion) JsonPrimitive(1) else JsonNull)
                    }
                    putJsonObject("effect") {
                        put("sticky", entry.sticky.takeIf { it > 0 }?.let(::JsonPrimitive) ?: JsonNull)
                        put("cooldown", entry.cooldown.takeIf { it > 0 }?.let(::JsonPrimitive) ?: JsonNull)
                        put("delay", entry.delay.takeIf { it > 0 }?.let(::JsonPrimitive) ?: JsonNull)
                    }
                    put("extra", entry.extensions)
                    put("content", entry.content)
                    put("enabled", record.runtimeState.worldBookActivationOverrides.entries[book.id]?.get(entry.id) ?: entry.enabled)
                }) } }
            }) }
        }
    }

    fun variables(variant: MessageVariant): JsonObject = variant.nativeHead()?.mvuState?.data ?: variant.browserVariables

    /** Computes an atomic proposal. Caller saves before publishing or acknowledging success. */
    fun apply(record: ConversationRecord, actor: BrowserActor, method: String, args: JsonObject): ConversationRecord = when (method) {
        "variables.replace" -> {
            args.only("type", "message_id", "data")
            val data = args.obj("data").also { require(it.toString().length <= 1024 * 1024) { "变量超过 1 MiB" } }
            when (args.string("type")) {
                "chat" -> withRuntime(record, record.runtimeState.copy(browserChatVariables = data))
                "script" -> {
                    val id = requireNotNull(actor.scriptId) { "当前页面没有脚本私有作用域" }
                    withRuntime(record, record.runtimeState.copy(browserScriptVariables = record.runtimeState.browserScriptVariables + (id to data)))
                }
                "message" -> {
                    val index = messageIndex(record, args["message_id"], actor)
                    replaceMessageVariables(record, index, data)
                }
                else -> error("未支持的变量作用域")
            }
        }
        "messages.set" -> setMessages(record, args)
        "mvu.replace" -> {
            args.only("data", "message_id")
            val index = messageIndex(record, args["message_id"], actor)
            require(record.turns[index].selected.nativeHead()?.mvuState != null) { "目标候选没有 MVU 状态" }
            replaceMessageVariables(record, index, args.obj("data"))
        }
        "worldbook.activation" -> {
            args.only("book", "entry", "enabled")
            val book = args.string("book")
            val enabled = args["enabled"]?.jsonPrimitive?.booleanOrNull ?: error("缺少启停值")
            val requestedEntry = args["entry"]?.jsonPrimitive?.contentOrNull
            val entry = requestedEntry?.let { requested ->
                record.character.worldBooks.find { it.id == book }?.entries?.withIndex()?.find {
                    it.value.id == requested || (it.value.sourceId?.toIntOrNull() ?: it.index).toString() == requested
                }?.value?.id ?: error("世界书条目不存在")
            }
            val intent = if (entry == null) WorldBookActivationIntent.SetBookEnabled(book, enabled)
                else WorldBookActivationIntent.SetEntryEnabled(book, entry, enabled)
            when (val result = WorldBookActivationController().apply(record.character.worldBooks, record.runtimeState, listOf(intent))) {
                is WorldBookActivationMutationResult.Applied -> withRuntime(record, result.runtimeState)
                is WorldBookActivationMutationResult.Rejected -> error("世界书或条目不存在")
            }
        }
        "draft.replace" -> {
            args.only("text")
            record.withDraft(args.string("text").also { require(it.length <= 262_144) { "草稿超过限制" } })
        }
        else -> error("未支持的宿主写入：$method")
    }

    private fun setMessages(original: ConversationRecord, args: JsonObject): ConversationRecord {
        args.only("messages", "refresh")
        require((args["refresh"]?.jsonPrimitive?.content ?: "affected") in setOf("none", "affected", "all")) { "无效刷新方式" }
        val updates = args["messages"] as? JsonArray ?: error("消息修改必须为数组")
        require(updates.size <= 256) { "消息修改数量超过限制" }
        var record = original
        updates.forEach { item ->
            val change = item as? JsonObject ?: error("消息修改必须为对象")
            change.only("message_id", "message", "swipe_id", "swipes", "data", "swipes_data", "is_hidden")
            val index = change["message_id"]?.jsonPrimitive?.intOrNull ?: error("缺少消息楼层")
            require(index in record.turns.indices) { "消息楼层不存在" }
            var turn = record.turns[index]
            require(turn.variants.none { it.status == PersistedMessageStatus.STREAMING }) { "不能改写正在生成的楼层" }
            val selected = change["swipe_id"]?.jsonPrimitive?.intOrNull ?: turn.selectedVariantIndex
            require(selected in turn.variants.indices) { "候选不存在" }
            change["swipes"]?.let { value ->
                val swipes = value as? JsonArray ?: error("候选必须为数组")
                require(swipes.size == turn.variants.size) { "首版仅替换已有候选，不增删候选" }
                turn = turn.copy(variants = turn.variants.mapIndexed { i, variant -> text(variant, swipes[i]) })
            }
            turn = turn.copy(selectedVariantIndex = selected)
            change["message"]?.let { value -> turn = turn.copy(variants = turn.variants.mapIndexed { i, v -> if (i == selected) text(v, value) else v }) }
            change["is_hidden"]?.let { value ->
                val hidden = value.jsonPrimitive.booleanOrNull ?: error("隐藏状态必须为布尔值")
                turn = turn.copy(variants = turn.variants.mapIndexed { i, v -> if (i == selected) v.copy(browserHidden = hidden) else v })
            }
            record = record.copy(turns = record.turns.mapIndexed { i, v -> if (i == index) turn else v },
                runtimeState = if (index == record.turns.lastIndex) turn.selected.nativeHead() ?: record.runtimeState else record.runtimeState)
            change["swipes_data"]?.let { value ->
                val list = value as? JsonArray ?: error("候选变量必须为数组")
                require(list.size == turn.variants.size) { "候选变量数量不匹配" }
                list.forEachIndexed { i, data -> record = replaceMessageVariables(record, index, data as? JsonObject ?: error("变量必须为对象"), i) }
            }
            change["data"]?.let { record = replaceMessageVariables(record, index, it as? JsonObject ?: error("变量必须为对象")) }
        }
        return record
    }

    private fun text(variant: MessageVariant, value: JsonElement): MessageVariant {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("正文必须为字符串")
        require(text.length <= 2 * 1024 * 1024) { "正文超过限制" }
        // A host message write is literal. It neither reruns storage macros nor truncates the future.
        return variant.copy(message = variant.message.copy(content = text, sourceText = text), edited = true)
    }

    private fun replaceMessageVariables(record: ConversationRecord, index: Int, data: JsonObject,
        candidate: Int = record.turns[index].selectedVariantIndex): ConversationRecord {
        require(data.toString().length <= 1024 * 1024) { "变量超过 1 MiB" }
        val turn = record.turns[index]
        val variant = turn.variants[candidate]
        val old = variant.nativeHead()
        val runtime = old?.mvuState?.let { old.copy(mvuState = it.withDirectReplacement(data)) }
        val next = variant.copy(browserVariables = data, browserHead = runtime ?: variant.browserHead)
        return record.copy(turns = record.turns.mapIndexed { i, t ->
            if (i != index) t else t.copy(variants = t.variants.mapIndexed { v, original -> if (v == candidate) next else original })
        }, runtimeState = if (index == record.turns.lastIndex && candidate == turn.selectedVariantIndex) runtime ?: record.runtimeState else record.runtimeState)
    }

    fun withRuntime(record: ConversationRecord, runtime: ConversationRuntimeState): ConversationRecord = record.copy(
        runtimeState = runtime,
        turns = record.turns.mapIndexed { index, turn -> if (index != record.turns.lastIndex) turn else
            turn.copy(variants = turn.variants.mapIndexed { i, variant ->
                if (i == turn.selectedVariantIndex) variant.copy(browserHead = runtime) else variant
            }) },
    )

    private fun messageIndex(record: ConversationRecord, value: JsonElement?, actor: BrowserActor): Int {
        if (value == null || value == JsonNull) return actor.turnId?.let { id -> record.turns.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 } ?: record.turns.lastIndex.also { require(it >= 0) { "尚无消息" } }
        val n = value.jsonPrimitive.intOrNull ?: error("消息楼层必须为整数")
        val index = if (n < 0) record.turns.size + n else n
        require(index in record.turns.indices) { "消息楼层不存在" }
        return index
    }
}

internal fun JsonObject.only(vararg keys: String) { require(this.keys.all { it in keys }) { "包含未支持的参数" } }
internal fun JsonObject.string(key: String): String = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("缺少字符串参数：$key")
internal fun JsonObject.obj(key: String): JsonObject = get(key) as? JsonObject ?: error("缺少对象参数：$key")
