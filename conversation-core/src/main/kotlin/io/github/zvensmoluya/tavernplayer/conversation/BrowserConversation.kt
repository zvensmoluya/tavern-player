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

    /** The session assigns commit revisions; typing has its own independent sequence. */
    fun revision(record: ConversationRecord): String = "${record.id}:${record.commitRevision}:${record.draftSeq}"

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
        put("characterVariables", record.character.browserProgram?.variables ?: buildJsonObject {})
        put("characterRegexes", JsonArray(record.character.regexScripts.map(BrowserRegex::encode)))
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
                put("data", variables(selected)); put("extra", selected.browserExtra)
                putJsonArray("swipes") { turn.variants.forEach { add(it.message.content) } }
                putJsonArray("swipes_data") { turn.variants.forEach { add(variables(it)) } }
                putJsonArray("swipes_info") { turn.variants.forEach { add(it.browserExtra) } }
                put("status", selected.status.name)
            }) }
        }
        putJsonArray("worldbooks") {
            record.character.worldBooks.forEach { book ->
                val overrides = record.worldBookState.activation
                add(buildJsonObject {
                    put("id", book.id); put("name", book.id)
                    put("enabled", overrides.isBookEnabled(book.id))
                    put("forced", book.id in record.worldBookState.forcedBooks)
                    putJsonArray("entries") { book.entries.forEachIndexed { index, entry ->
                        add(BrowserWorldBook.encodeEntry(book.id, entry, index,
                            overrides.isEntryEnabled(book.id, entry.id, entry.enabled),
                            edited = entry.id in record.worldBookState.editedContent))
                    } }
                })
            }
        }
    }

    fun variables(variant: MessageVariant): JsonObject = if (variant.browserOwnVariables) variant.browserVariables
        else variant.nativeHead()?.mvuState?.data ?: variant.browserVariables

    /** Computes an atomic proposal. Caller saves before publishing or acknowledging success. */
    fun apply(record: ConversationRecord, actor: BrowserActor, method: String, args: JsonObject): ConversationRecord = when (method) {
        "variables.replace" -> {
            args.only("type", "message_id", "data")
            val data = args.obj("data").also { require(it.toString().length <= 1024 * 1024) { "变量超过 1 MiB" } }
            when (args.string("type")) {
                "chat" -> withRuntime(record, record.runtimeState.copy(browserChatVariables = data))
                "character" -> record.copy(character = record.character.copy(browserProgram =
                    (record.character.browserProgram ?: io.github.zvensmoluya.tavernplayer.content.BrowserProgram()).copy(variables = data)))
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
        "regex.replace" -> BrowserRegex.replace(record, args)
        "messages.set" -> setMessages(record, args)
        "messages.create", "messages.delete", "messages.rotate" -> restructureMessages(record, method, args)
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
                    it.value.id == requested || BrowserWorldBook.uid(it.value, it.index).toString() == requested
                }?.value?.id ?: error("世界书条目不存在")
            }
            val intent = if (entry == null) WorldBookActivationIntent.SetBookEnabled(book, enabled)
                else WorldBookActivationIntent.SetEntryEnabled(book, entry, enabled)
            when (val result = WorldBookActivationController().apply(record.character.worldBooks, record.worldBookState, listOf(intent))) {
                // 提交落在会话级的 worldBookState 上：切候选或回溯不回退玩家与脚本的启用意图。
                is WorldBookActivationMutationResult.Applied -> record.copy(worldBookState = result.state)
                is WorldBookActivationMutationResult.Rejected -> error("世界书或条目不存在")
            }
        }
        "worldbook.books.force" -> {
            args.only("book", "forced")
            val book = args.string("book")
            val forced = args["forced"]?.jsonPrimitive?.booleanOrNull ?: error("缺少必定生效值")
            val intent = WorldBookActivationIntent.SetBookForceEnabled(book, forced)
            when (val result = WorldBookActivationController().apply(record.character.worldBooks, record.worldBookState, listOf(intent))) {
                is WorldBookActivationMutationResult.Applied -> record.copy(worldBookState = result.state)
                is WorldBookActivationMutationResult.Rejected -> error("世界书不存在")
            }
        }
        // 世界书内容写入：只改当前对话自己那份快照，角色资产保持不变。
        "worldbook.entries.replace" -> BrowserWorldBook.replaceEntries(record, args)
        "worldbook.entries.update" -> BrowserWorldBook.updateEntries(record, args)
        "worldbook.entries.create" -> BrowserWorldBook.createEntries(record, args)
        "worldbook.entries.delete" -> BrowserWorldBook.deleteEntries(record, args)
        "worldbook.entries.restore" -> BrowserWorldBook.restoreContent(record, args)
        "worldbook.books.create" -> BrowserWorldBook.createBook(record, args)
        "worldbook.books.delete" -> BrowserWorldBook.deleteBook(record, args)
        "worldbook.books.rebind" -> BrowserWorldBook.rebind(record, args)
        "draft.replace" -> {
            args.only("text")
            record.withDraft(args.string("text").also { require(it.length <= 262_144) { "草稿超过限制" } })
        }
        else -> error("未支持的宿主写入：$method")
    }

    /** 只读的旧版世界书条目列表；调用方不保存会话。 */
    fun readWorldBookEntries(record: ConversationRecord, args: JsonObject): JsonElement {
        args.only("book")
        return BrowserWorldBook.readEntries(record, args.string("book"))
    }

    private fun restructureMessages(record: ConversationRecord, method: String, args: JsonObject): ConversationRecord {
        require((args["refresh"]?.jsonPrimitive?.content ?: "affected") in setOf("none", "affected", "all")) { "无效刷新方式" }
        require(record.turns.none { turn -> turn.variants.any { it.status == PersistedMessageStatus.STREAMING } }) { "不能移动正在生成的消息" }
        require(args["refresh"] != JsonPrimitive("none")) { "消息结构变化需要刷新显示" }
        val size = record.turns.size
        fun integer(key: String): Int = (args[key] as? JsonPrimitive)?.intOrNull ?: error("消息位置必须为整数")
        fun normalized(value: Int): Int = if (value < 0) size + value else value
        val turns = record.turns.toMutableList()
        when (method) {
            "messages.create" -> {
                args.only("messages", "insert_before", "refresh")
                val items = args["messages"] as? JsonArray ?: error("消息必须为数组")
                require(items.size <= 256) { "消息数量超过限制" }
                val position = if (args["insert_before"] == null || args["insert_before"] == JsonPrimitive("end")) size
                    else normalized(integer("insert_before").coerceIn(-size, size))
                val created = items.map { raw ->
                    val item = raw as? JsonObject ?: error("消息必须为对象")
                    item.only("name", "role", "is_hidden", "message", "data", "extra")
                    val role = when (item.string("role")) {
                        "system" -> MessageRole.SYSTEM; "user" -> MessageRole.USER; "assistant" -> MessageRole.ASSISTANT
                        else -> error("无效消息角色")
                    }
                    val name = if (item.containsKey("name")) item.string("name") else when (role) {
                        MessageRole.SYSTEM -> "system"; MessageRole.USER -> record.persona.name; MessageRole.ASSISTANT -> record.character.promptName
                    }
                    val content = item.string("message").also { require(it.length <= 2 * 1024 * 1024) { "正文超过限制" } }
                    val data = if (item.containsKey("data")) item.obj("data") else buildJsonObject {}
                    val extra = if (item.containsKey("extra")) item.obj("extra") else buildJsonObject {}
                    require(data.toString().length <= 1024 * 1024 && extra.toString().length <= 1024 * 1024) { "消息数据超过限制" }
                    val hidden = if (item.containsKey("is_hidden")) item["is_hidden"]?.jsonPrimitive?.booleanOrNull ?: error("隐藏状态必须为布尔值") else false
                    val id = java.util.UUID.randomUUID().toString()
                    ConversationTurn(id, role, listOf(MessageVariant(id, ConversationMessage(id, role, content, name),
                        browserVariables = data, browserOwnVariables = true, browserExtra = extra, browserHidden = hidden)))
                }
                turns.addAll(position, created)
            }
            "messages.delete" -> {
                args.only("message_ids", "refresh")
                val ids = args["message_ids"] as? JsonArray ?: error("消息楼层必须为数组")
                val removed = ids.map { (it as? JsonPrimitive)?.intOrNull ?: error("消息楼层必须为整数") }
                    .filter { it >= -size && it < size }.map(::normalized).toSet()
                val removedIds = removed.map { record.turns[it].id }.toSet()
                turns.removeAll { it.id in removedIds }
            }
            "messages.rotate" -> {
                args.only("begin", "middle", "end", "refresh")
                val begin = normalized(integer("begin")).coerceIn(0, size)
                val end = normalized(integer("end")).coerceIn(0, size)
                require(begin <= end) { "移动范围起点超过终点" }
                val middle = normalized(integer("middle")).coerceIn(begin, end)
                val rotated = turns.subList(middle, end).toList() + turns.subList(begin, middle).toList()
                rotated.forEachIndexed { index, turn -> turns[begin + index] = turn }
            }
        }
        // History edits preserve live state and never re-run MVU.
        return withRuntime(record.copy(turns = turns), record.runtimeState)
    }

    private fun setMessages(original: ConversationRecord, args: JsonObject): ConversationRecord {
        args.only("messages", "refresh")
        require((args["refresh"]?.jsonPrimitive?.content ?: "affected") in setOf("none", "affected", "all")) { "无效刷新方式" }
        val updates = args["messages"] as? JsonArray ?: error("消息修改必须为数组")
        require(updates.size <= 256) { "消息修改数量超过限制" }
        var record = original
        val merged = linkedMapOf<Int, JsonObject>()
        updates.forEach { item ->
            val change = item as? JsonObject ?: error("消息修改必须为对象")
            change.only("message_id", "name", "role", "message", "swipe_id", "swipes", "data", "extra", "swipes_data", "swipes_info", "is_hidden")
            val requested = change["message_id"]?.jsonPrimitive?.intOrNull ?: error("缺少消息楼层")
            val index = if (requested < 0) original.turns.size + requested else requested
            if (index in original.turns.indices) merged[index] = JsonObject(merged[index].orEmpty() + change)
        }
        merged.toSortedMap().forEach { (index, change) ->
            var turn = record.turns[index]
            require(turn.variants.none { it.status == PersistedMessageStatus.STREAMING }) { "不能改写正在生成的楼层" }
            val role = change["role"]?.let { when ((it as? JsonPrimitive)?.content) {
                "user" -> MessageRole.USER; "assistant" -> MessageRole.ASSISTANT; "system" -> MessageRole.SYSTEM
                else -> error("无效消息角色")
            } } ?: turn.role
            val name = if (change.containsKey("name")) change.string("name") else turn.selected.message.authorName
            val hidden = if (change.containsKey("is_hidden")) change["is_hidden"]?.jsonPrimitive?.booleanOrNull ?: error("隐藏状态必须为布尔值") else turn.selected.browserHidden
            turn = turn.copy(role = role, variants = turn.variants.map { it.copy(
                message = it.message.copy(role = role, authorName = name), browserHidden = hidden) })
            if (change.containsKey("message") || change.containsKey("data")) {
                val selected = turn.selectedVariantIndex
                turn = turn.copy(variants = turn.variants.mapIndexed { i, old ->
                    if (i != selected) old else {
                        var next = change["message"]?.let { text(old, it) } ?: old
                        change["extra"]?.let { next = next.copy(browserExtra = it as? JsonObject ?: error("extra 必须为对象")) }
                        next
                    }
                })
            } else if (listOf("swipe_id", "swipes", "swipes_data", "swipes_info").any(change::containsKey)) {
                fun array(key: String): JsonArray? = if (change.containsKey(key)) change[key] as? JsonArray ?: error("候选字段必须为数组") else null
                val texts = array("swipes"); val data = array("swipes_data"); val info = array("swipes_info")
                val count = listOfNotNull(texts?.size, data?.size, info?.size).maxOrNull() ?: turn.variants.size
                require(count in 1..256) { "候选数量必须在 1 至 256 之间" }
                val selected = (if (change.containsKey("swipe_id")) change["swipe_id"]?.jsonPrimitive?.intOrNull ?: error("候选编号必须为整数")
                    else turn.selectedVariantIndex).coerceIn(0, count - 1)
                val oldTurn = turn
                turn = turn.copy(selectedVariantIndex = selected, variants = List(count) { i ->
                    val existing = oldTurn.variants.getOrNull(i)
                    val id = java.util.UUID.randomUUID().toString()
                    val base = existing ?: MessageVariant(id, ConversationMessage(id, role, "", name), browserHidden = hidden, browserOwnVariables = true)
                    val content = texts?.getOrNull(i) ?: if (texts == null && existing != null) JsonPrimitive(existing.message.content) else JsonPrimitive("")
                    val extra = (if (info == null) existing?.browserExtra else info.getOrNull(i)) ?: buildJsonObject {}
                    val variables = (if (data == null) existing?.let(::variables) else data.getOrNull(i)) ?: buildJsonObject {}
                    require(extra is JsonObject && variables is JsonObject) { "候选数据必须为对象" }
                    val head = base.nativeHead()?.let { old -> old.mvuState?.let { old.copy(mvuState = it.withDirectReplacement(variables)) } }
                    text(base, content).copy(browserVariables = variables, browserExtra = extra, browserHead = head ?: base.browserHead)
                })
            }
            turn.variants.forEach { require(it.browserExtra.toString().length <= 1024 * 1024 && variables(it).toString().length <= 1024 * 1024) { "消息数据超过限制" } }
            record = record.copy(turns = record.turns.mapIndexed { i, value -> if (i == index) turn else value },
                runtimeState = if (index == record.turns.lastIndex) turn.selected.nativeHead() ?: record.runtimeState else record.runtimeState)
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
