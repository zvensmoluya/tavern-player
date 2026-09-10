package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.ContentRole
import io.github.zvensmoluya.tavernplayer.content.WorldBookDefinition
import io.github.zvensmoluya.tavernplayer.content.WorldBookEntryDefinition
import io.github.zvensmoluya.tavernplayer.content.WorldBookPosition
import io.github.zvensmoluya.tavernplayer.content.WorldBookSecondaryLogic
import kotlinx.serialization.json.*

/**
 * 对话内世界书写入。
 *
 * 会话快照是自己那份世界书内容的唯一所有者：写入只改当前对话，不触碰角色资产，也不建立第二份资产库。
 * 这里同时产出助手新旧两种条目形状——新版嵌套结构（`getWorldbook` / `replaceWorldbook` 系列）和
 * 旧版扁平结构（`getLorebookEntries` / `setLorebookEntries` 系列）——两者读写同一份定义。
 */
internal object BrowserWorldBook {
    private const val MAX_BOOKS = 64
    private const val MAX_ENTRIES_PER_BOOK = 2048
    private const val MAX_CONTENT_LENGTH = 256 * 1024
    private const val MAX_TOTAL_LENGTH = 8 * 1024 * 1024

    /** 新版嵌套结构的字段。`comment`、`player_entry_id`、`book` 由本宿主自己的读出补齐，回写时同样接受。 */
    private val nestedKeys = setOf(
        "uid", "name", "comment", "player_entry_id", "book", "enabled",
        "strategy", "position", "content", "probability", "recursion", "effect", "extra",
    )

    /** 旧版扁平结构相对新版多出的顶层字段。 */
    private val legacyKeys = setOf(
        "display_index", "type", "depth", "order", "keys", "logic", "filters", "scan_depth",
        "case_sensitive", "match_whole_words", "use_group_scoring", "automation_id", "exclude_recursion",
        "prevent_recursion", "delay_until_recursion", "group", "group_prioritized", "group_weight",
        "sticky", "cooldown", "delay",
    )

    private val positionNames = linkedMapOf(
        WorldBookPosition.BEFORE_CHARACTER to "before_character_definition",
        WorldBookPosition.AFTER_CHARACTER to "after_character_definition",
        WorldBookPosition.EXAMPLES_TOP to "before_example_messages",
        WorldBookPosition.EXAMPLES_BOTTOM to "after_example_messages",
        WorldBookPosition.AUTHOR_NOTE_TOP to "before_author_note",
        WorldBookPosition.AUTHOR_NOTE_BOTTOM to "after_author_note",
        WorldBookPosition.AT_DEPTH to "at_depth",
        WorldBookPosition.OUTLET to "outlet",
    )
    private val positionsByName = positionNames.entries.associate { (key, value) -> value to key }

    /** 旧版结构没有 outlet，且深度插入用位置与身份合并表达；按当前身份回写。 */
    private fun legacyPosition(entry: WorldBookEntryDefinition): String = when (entry.position) {
        WorldBookPosition.AT_DEPTH, WorldBookPosition.OUTLET -> "at_depth_as_" + entry.role.name.lowercase()
        else -> positionNames.getValue(entry.position)
    }

    private val logicNames = mapOf(
        WorldBookSecondaryLogic.AND_ANY to "and_any",
        WorldBookSecondaryLogic.AND_ALL to "and_all",
        WorldBookSecondaryLogic.NOT_ANY to "not_any",
        WorldBookSecondaryLogic.NOT_ALL to "not_all",
    )
    private val logicByName = logicNames.entries.associate { (key, value) -> value to key }

    // ---------------------------------------------------------------- 读出

    /** 对外 uid：作者新建的条目用自己的 uid，卡内条目沿用原 uid，两者都没有时退回列表位置。 */
    fun uid(entry: WorldBookEntryDefinition, index: Int): Int = entry.uid ?: entry.sourceId?.toIntOrNull() ?: index

    /** 新版嵌套结构。 */
    fun encodeEntry(bookId: String, entry: WorldBookEntryDefinition, index: Int, enabled: Boolean): JsonObject = buildJsonObject {
        put("uid", uid(entry, index)); put("player_entry_id", entry.id)
        put("name", entry.name.ifBlank { entry.comment }); put("comment", entry.comment)
        put("enabled", enabled)
        putJsonObject("strategy") {
            put("type", entry.strategyName()); put("keys", JsonArray(entry.keys.map(::JsonPrimitive)))
            putJsonObject("keys_secondary") {
                put("logic", logicNames.getValue(entry.effectiveSecondaryLogic))
                put("keys", JsonArray(entry.secondaryKeys.map(::JsonPrimitive)))
            }
            put("scan_depth", entry.scanDepth?.let(::JsonPrimitive) ?: JsonPrimitive("same_as_global"))
        }
        putJsonObject("position") {
            put("type", positionNames.getValue(entry.position)); put("role", entry.role.name.lowercase())
            put("depth", entry.depth); put("order", entry.insertionOrder)
        }
        put("probability", if (entry.useProbability) entry.probability else 100)
        putJsonObject("recursion") {
            put("prevent_incoming", entry.excludeRecursion); put("prevent_outgoing", entry.preventRecursion)
            put("delay_until", entry.delayUntilRecursionValue())
        }
        putJsonObject("effect") {
            put("sticky", entry.sticky.positiveOrNull()); put("cooldown", entry.cooldown.positiveOrNull())
            put("delay", entry.delay.positiveOrNull())
        }
        put("extra", entry.extensions)
        put("content", entry.content)
    }

    /** 旧版扁平结构。只声明该结构真实存在的字段，不虚构上游旧接口没有的概念。 */
    fun encodeLegacyEntry(entry: WorldBookEntryDefinition, index: Int, enabled: Boolean): JsonObject = buildJsonObject {
        put("uid", uid(entry, index)); put("display_index", index)
        put("comment", entry.comment.ifBlank { entry.name }); put("enabled", enabled)
        put("type", entry.strategyName()); put("position", legacyPosition(entry))
        put("depth", entry.depth); put("order", entry.insertionOrder)
        put("probability", if (entry.useProbability) entry.probability else 100)
        put("keys", JsonArray(entry.keys.map(::JsonPrimitive)))
        put("logic", logicNames.getValue(entry.effectiveSecondaryLogic))
        put("filters", JsonArray(emptyList()))
        put("scan_depth", entry.scanDepth?.let(::JsonPrimitive) ?: JsonPrimitive("same_as_global"))
        put("case_sensitive", entry.caseSensitive?.let(::JsonPrimitive) ?: JsonPrimitive("same_as_global"))
        put("match_whole_words", entry.matchWholeWords?.let(::JsonPrimitive) ?: JsonPrimitive("same_as_global"))
        put("use_group_scoring", JsonPrimitive(entry.useGroupScoring))
        put("automation_id", JsonNull)
        put("exclude_recursion", entry.excludeRecursion); put("prevent_recursion", entry.preventRecursion)
        put("delay_until_recursion", entry.delayUntilRecursionValue())
        put("content", entry.content)
        put("group", entry.group); put("group_prioritized", entry.groupOverride)
        put("group_weight", entry.groupWeight)
        put("sticky", entry.sticky.positiveOrNull()); put("cooldown", entry.cooldown.positiveOrNull())
        put("delay", entry.delay.positiveOrNull())
    }

    /** 旧接口的读取：只读，不改会话。 */
    fun readEntries(record: ConversationRecord, bookName: String): JsonArray {
        val book = book(record, bookName)
        val overrides = record.runtimeState.worldBookActivationOverrides
        return JsonArray(book.entries.mapIndexed { index, entry ->
            encodeLegacyEntry(entry, index, overrides.isEntryEnabled(book.id, entry.id, entry.enabled))
        })
    }

    // ---------------------------------------------------------------- 写入

    /** `replaceWorldbook` / `replaceLorebookEntries` / `updateWorldbookWith`：整体替换条目。 */
    fun replaceEntries(record: ConversationRecord, args: JsonObject): ConversationRecord {
        args.only("book", "entries")
        val book = book(record, args.string("book"))
        val values = args.array("entries")
        require(values.size <= MAX_ENTRIES_PER_BOOK) { "世界书条目数量超过限制" }
        val uids = UidAllocator(book.entries)
        val entries = values.mapIndexed { index, raw ->
            val patch = entryOf(raw)
            val requested = patch["uid"]?.jsonPrimitive?.intOrNull
            val existing = requested?.let { value -> entryAt(book.entries, value) }
            entryPatch(book.id, existing ?: newEntry(book.id, uids.reserve(requested)), patch)
        }
        require(entries.map { it.id }.distinct().size == entries.size) { "世界书条目 uid 不能重复" }
        validateSize(entries)
        return prune(record, record.character.worldBooks.map { if (it.id == book.id) it.copy(entries = entries) else it })
    }

    /** `setLorebookEntries`：按 uid 局部更新，未出现的条目保持不变。 */
    fun updateEntries(record: ConversationRecord, args: JsonObject): ConversationRecord {
        args.only("book", "entries")
        val book = book(record, args.string("book"))
        val values = args.array("entries")
        var entries = book.entries
        values.forEach { raw ->
            val patch = entryOf(raw)
            val requested = patch["uid"]?.jsonPrimitive?.intOrNull ?: error("缺少条目 uid")
            val index = uniqueIndex(entries, requested)
            val base = entries[index]
            entries = entries.toMutableList().also {
                it[index] = entryPatch(book.id, base, patch).copy(uid = base.uid)
            }
        }
        validateSize(entries)
        return prune(record, record.character.worldBooks.map { if (it.id == book.id) it.copy(entries = entries) else it })
    }

    /** `createWorldbookEntries` / `createLorebookEntries`：新增条目。 */
    fun createEntries(record: ConversationRecord, args: JsonObject): ConversationRecord {
        args.only("book", "entries")
        val book = book(record, args.string("book"))
        val values = args.array("entries")
        require(values.size <= 256) { "新增条目数量超过限制" }
        require(book.entries.size + values.size <= MAX_ENTRIES_PER_BOOK) { "世界书条目数量超过限制" }
        val uids = UidAllocator(book.entries)
        val created = values.map { raw ->
            val patch = entryOf(raw)
            val assigned = uids.reserve(patch["uid"]?.jsonPrimitive?.intOrNull)
            entryPatch(book.id, newEntry(book.id, assigned), patch)
        }
        require((book.entries + created).map { it.id }.distinct().size == book.entries.size + created.size) { "世界书条目 uid 不能重复" }
        validateSize(book.entries + created)
        return prune(record, record.character.worldBooks.map { if (it.id == book.id) it.copy(entries = book.entries + created) else it })
    }

    /** `deleteWorldbookEntries` / `deleteLorebookEntries`：按 uid 删除。 */
    fun deleteEntries(record: ConversationRecord, args: JsonObject): ConversationRecord {
        args.only("book", "uids")
        val book = book(record, args.string("book"))
        val uids = args.array("uids").map { (it as? JsonPrimitive)?.intOrNull ?: error("条目 uid 必须为整数") }.toSet()
        // uid 在书内不唯一时按 uid 删除会一次删掉多条，宁可明确失败也不静默多删。
        uids.forEach { value ->
            require(book.entries.indices.count { index -> uid(book.entries[index], index) == value } <= 1) {
                "世界书条目 uid 重复：$value"
            }
        }
        val entries = book.entries.filterIndexed { index, entry -> uid(entry, index) !in uids }
        return prune(record, record.character.worldBooks.map { if (it.id == book.id) it.copy(entries = entries) else it })
    }

    /** `createWorldbook` / `createOrReplaceWorldbook`：新建或替换整本书。 */
    fun createBook(record: ConversationRecord, args: JsonObject): ConversationRecord {
        args.only("name", "entries")
        val name = bookName(args.string("name"))
        val created = record.character.worldBooks.none { it.id == name }
        if (created) require(record.character.worldBooks.size < MAX_BOOKS) { "世界书数量超过限制" }
        val values = args["entries"]?.let { args.array("entries") }.orEmpty()
        val uids = UidAllocator(emptyList())
        val entries = values.map { raw ->
            val patch = entryOf(raw)
            entryPatch(name, newEntry(name, uids.reserve(patch["uid"]?.jsonPrimitive?.intOrNull)), patch)
        }
        require(entries.map { it.id }.distinct().size == entries.size) { "世界书条目 uid 不能重复" }
        validateSize(entries)
        val books = if (created) record.character.worldBooks + WorldBookDefinition(id = name, name = name, entries = entries)
            else record.character.worldBooks.map { if (it.id == name) it.copy(entries = entries) else it }
        // 新建的书先不参与编排；作者用 rebindCharWorldbooks 或 setWorldbookEnabled 显式启用。
        val overrides = record.runtimeState.worldBookActivationOverrides
        val runtime = if (created) record.runtimeState.copy(
            worldBookActivationOverrides = overrides.copy(books = overrides.books + (name to false)),
        ) else record.runtimeState
        return prune(record.copy(runtimeState = runtime), books)
    }

    /** `deleteWorldbook`：删除整本书及其激活覆盖和跨轮状态。 */
    fun deleteBook(record: ConversationRecord, args: JsonObject): ConversationRecord {
        args.only("name")
        val name = args.string("name")
        return prune(record, record.character.worldBooks.filterNot { it.id == name })
    }

    /** `rebindCharWorldbooks`：在这本书集合内确定参与编排的世界书及其顺序。 */
    fun rebind(record: ConversationRecord, args: JsonObject): ConversationRecord {
        args.only("primary", "additional")
        val primary = args["primary"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
        val additional = args["additional"]?.let { element ->
            (element as? JsonArray ?: error("additional 必须为数组")).map {
                (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content ?: error("世界书名称必须为字符串")
            }
        }.orEmpty()
        val requested = listOfNotNull(primary) + additional
        val known = record.character.worldBooks.map { it.id }
        requested.forEach { require(it in known) { "世界书不存在：$it" } }
        require(requested.distinct().size == requested.size) { "世界书名称不能重复" }
        val ordered = requested.mapNotNull { name -> record.character.worldBooks.find { it.id == name } } +
            record.character.worldBooks.filterNot { it.id in requested }
        val overrides = record.runtimeState.worldBookActivationOverrides
        val books = overrides.books.toMutableMap()
        known.forEach { books[it] = it in requested }
        return record.copy(
            character = record.character.copy(worldBooks = ordered),
            runtimeState = record.runtimeState.copy(worldBookActivationOverrides = overrides.copy(books = books.toMap())),
        )
    }

    // ---------------------------------------------------------------- 内部

    private fun book(record: ConversationRecord, name: String): WorldBookDefinition =
        requireNotNull(record.character.worldBooks.find { it.id == name }) { "世界书不存在：$name" }

    private fun entryAt(entries: List<WorldBookEntryDefinition>, value: Int): WorldBookEntryDefinition? =
        entries.filterIndexed { index, entry -> uid(entry, index) == value }.firstOrNull()

    /** 定位 uid 指定的条目；uid 在书内不唯一时明确失败，而不是命中任意一条。 */
    private fun uniqueIndex(entries: List<WorldBookEntryDefinition>, value: Int): Int {
        val matches = entries.indices.filter { index -> uid(entries[index], index) == value }
        require(matches.size <= 1) { "世界书条目 uid 重复：$value" }
        return requireNotNull(matches.singleOrNull()) { "世界书条目不存在：$value" }
    }

    private fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: error("$key 必须为数组")

    private fun entryOf(element: JsonElement): JsonObject = element as? JsonObject ?: error("世界书条目必须为对象")

    private fun bookName(name: String): String {
        require(name.isNotBlank() && name.length <= 256) { "世界书名称无效" }
        require(!name.startsWith("player:")) { "player: 前缀保留给 Player 内部世界书" }
        return name
    }

    private fun validateSize(entries: List<WorldBookEntryDefinition>) {
        require(entries.size <= MAX_ENTRIES_PER_BOOK) { "世界书条目数量超过限制" }
        entries.forEach { require(it.content.length <= MAX_CONTENT_LENGTH) { "世界书条目内容超过限制" } }
        require(entries.sumOf { it.content.length } <= MAX_TOTAL_LENGTH) { "世界书内容超过限制" }
    }

    private fun newEntry(bookId: String, uid: Int): WorldBookEntryDefinition =
        WorldBookEntryDefinition(id = "$bookId:entry:$uid", uid = uid, selective = true)

    /** 条目补丁：只改出现的字段，嵌套分组按子字段合并。新旧两种形状的字段名同时接受。 */
    private fun entryPatch(
        bookId: String,
        base: WorldBookEntryDefinition,
        patch: JsonObject,
    ): WorldBookEntryDefinition {
        require(patch.keys.all { it in nestedKeys || it in legacyKeys }) { "包含未支持的世界书条目字段" }
        // `extra` 先落盘：它整体替换扩展对象，之后的字段写入（例如清除卡内 `selectiveLogic`）必须能覆盖它。
        var entry = patch["extra"]?.let { raw ->
            base.copy(extensions = raw as? JsonObject ?: error("extra 必须为对象"))
        } ?: base
        patch.text("name")?.let { entry = entry.copy(name = it) }
        patch.text("comment")?.let { entry = entry.copy(comment = it) }
        patch.bool("enabled")?.let { entry = entry.copy(enabled = it) }
        patch.text("content")?.let {
            require(it.length <= MAX_CONTENT_LENGTH) { "世界书条目内容超过限制" }
            entry = entry.copy(content = it)
        }
        patch.int("probability")?.let { value ->
            val bounded = value.coerceIn(0, 100)
            entry = entry.copy(probability = bounded, useProbability = bounded < 100)
        }
        patch.timedEffect("sticky")?.let { entry = entry.copy(sticky = it) }
        patch.timedEffect("cooldown")?.let { entry = entry.copy(cooldown = it) }
        patch.timedEffect("delay")?.let { entry = entry.copy(delay = it) }
        patch.bool("exclude_recursion")?.let { entry = entry.copy(excludeRecursion = it) }
        patch.bool("prevent_recursion")?.let { entry = entry.copy(preventRecursion = it) }
        patch.bool("delay_until_recursion")?.let { entry = entry.copy(delayUntilRecursion = it) }
        patch.text("type")?.let { entry = entry.strategy(it) }
        patch.text("logic")?.let { entry = entry.secondaryLogic(it) }
        patch.text("position")?.let { entry = entry.position(it) }
        patch.int("depth")?.let { entry = entry.copy(depth = it) }
        patch.int("order")?.let { entry = entry.copy(insertionOrder = it) }
        patch.triState("case_sensitive")?.let { entry = entry.copy(caseSensitive = it) }
        patch.triState("match_whole_words")?.let { entry = entry.copy(matchWholeWords = it) }
        patch.bool("use_group_scoring")?.let { entry = entry.copy(useGroupScoring = it) }
        patch.bool("group_prioritized")?.let { entry = entry.copy(groupOverride = it) }
        patch.text("group")?.let { entry = entry.copy(group = it) }
        patch.int("group_weight")?.let { entry = entry.copy(groupWeight = it) }
        patch["scan_depth"]?.let { entry = entry.copy(scanDepth = scanDepth(it)) }
        patch["keys"]?.let { entry = entry.copy(keys = strings(it, "keys")) }
        patch["delay_until_recursion"]?.let { entry = entry.recursionDelay(it) }
        patch["filters"]?.let { value ->
            val filters = value as? JsonArray ?: error("filters 必须为数组")
            require(filters.isEmpty()) { "player-web-1 不支持世界书条目过滤器" }
        }
        patch["automation_id"]?.let { value -> require(value is JsonNull) { "player-web-1 不支持世界书自动化条目" } }

        patch["strategy"]?.let { raw ->
            val strategy = raw as? JsonObject ?: error("strategy 必须为对象")
            strategy.only("type", "keys", "keys_secondary", "scan_depth")
            strategy.text("type")?.let { entry = entry.strategy(it) }
            strategy["keys"]?.let { entry = entry.copy(keys = strings(it, "keys")) }
            strategy["scan_depth"]?.let { entry = entry.copy(scanDepth = scanDepth(it)) }
            strategy["keys_secondary"]?.let { raw ->
                val secondary = raw as? JsonObject ?: error("keys_secondary 必须为对象")
                secondary.only("logic", "keys")
                secondary.text("logic")?.let { entry = entry.secondaryLogic(it) }
                secondary["keys"]?.let { entry = entry.copy(secondaryKeys = strings(it, "keys")) }
            }
        }
        patch["position"]?.let { raw ->
            when (raw) {
                is JsonObject -> {
                    raw.only("type", "role", "depth", "order")
                    raw.text("type")?.let { name ->
                        entry = entry.copy(position = positionsByName[name] ?: error("无效的世界书位置：$name"))
                    }
                    raw.text("role")?.let { name ->
                        entry = entry.copy(role = when (name) {
                            "system" -> ContentRole.SYSTEM
                            "assistant" -> ContentRole.ASSISTANT
                            "user" -> ContentRole.USER
                            else -> error("无效的世界书条目身份：$name")
                        })
                    }
                    raw.int("depth")?.let { entry = entry.copy(depth = it) }
                    raw.int("order")?.let { entry = entry.copy(insertionOrder = it) }
                }
                is JsonPrimitive -> if (raw.isString) entry = entry.position(raw.content)
                else -> error("无效的世界书位置")
            }
        }
        patch["recursion"]?.let { raw ->
            val recursion = raw as? JsonObject ?: error("recursion 必须为对象")
            recursion.only("prevent_incoming", "prevent_outgoing", "delay_until")
            recursion.bool("prevent_incoming")?.let { entry = entry.copy(excludeRecursion = it) }
            recursion.bool("prevent_outgoing")?.let { entry = entry.copy(preventRecursion = it) }
            recursion["delay_until"]?.let { entry = entry.recursionDelay(it) }
        }
        patch["effect"]?.let { raw ->
            val effect = raw as? JsonObject ?: error("effect 必须为对象")
            effect.only("sticky", "cooldown", "delay")
            effect.timedEffect("sticky")?.let { entry = entry.copy(sticky = it) }
            effect.timedEffect("cooldown")?.let { entry = entry.copy(cooldown = it) }
            effect.timedEffect("delay")?.let { entry = entry.copy(delay = it) }
        }
        return entry
    }

    private fun WorldBookEntryDefinition.strategy(value: String): WorldBookEntryDefinition = when (value) {
        "constant" -> copy(constant = true, extensions = JsonObject(extensions - "vectorized"))
        // 不翻转 `selective`：它表示「次要关键字是否参与判定」，与激活策略不是同一个概念，
        // 原样回写 `type` 不应改变它。
        "selective" -> copy(constant = false, extensions = JsonObject(extensions - "vectorized"))
        "vectorized" -> copy(
            constant = false,
            extensions = JsonObject(extensions + ("vectorized" to JsonPrimitive(true))),
        )
        else -> error("无效的世界书激活策略：$value")
    }

    private fun WorldBookEntryDefinition.position(value: String): WorldBookEntryDefinition =
        when (value) {
            "at_depth_as_system", "at_depth_as_assistant", "at_depth_as_user" -> {
                val role = value.removePrefix("at_depth_as_")
                // outlet 在旧版结构里没有对应值；原样回写时保持 outlet，不静默改变注入位置。
                if (position == WorldBookPosition.OUTLET && this.role.name.lowercase() == role) this
                else copy(position = WorldBookPosition.AT_DEPTH, role = ContentRole.valueOf(role.uppercase()))
            }
            else -> copy(position = positionsByName[value] ?: error("无效的世界书位置：$value"))
        }

    /** `delay_until` 既接受级别数字，也接受旧版扁平结构的布尔值。 */
    private fun WorldBookEntryDefinition.recursionDelay(value: JsonElement): WorldBookEntryDefinition {
        val number = (value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        if (number == null) {
            val flag = (value as? JsonPrimitive)?.booleanOrNull ?: false
            return copy(delayUntilRecursion = flag, extensions = JsonObject(extensions - "delay_until_recursion"))
        }
        return copy(
            delayUntilRecursion = number > 0,
            extensions = if (number > 0) JsonObject(extensions + ("delay_until_recursion" to JsonPrimitive(number)))
            else JsonObject(extensions - "delay_until_recursion"),
        )
    }

    private fun scanDepth(value: JsonElement): Int? {
        if (value is JsonPrimitive && value.isString) {
            require(value.content == "same_as_global") { "无效的扫描深度：${value.content}" }
            return null
        }
        return (value as? JsonPrimitive)?.intOrNull ?: error("扫描深度必须为整数")
    }

    /**
     * 写入次要关键字逻辑。
     *
     * 导入时原始 `extensions.selectiveLogic` 会被原样保留，而 `effectiveSecondaryLogic` 优先读它；
     * 因此写入必须同时清掉该扩展，否则写进去的值不会生效。
     */
    private fun WorldBookEntryDefinition.secondaryLogic(value: String): WorldBookEntryDefinition = copy(
        secondaryLogic = logicByName[value] ?: error("无效的次要关键字逻辑：$value"),
        extensions = JsonObject(extensions - "selectiveLogic"),
    )

    private fun strings(value: JsonElement, key: String): List<String> {
        val array = value as? JsonArray ?: error("$key 必须为数组")
        return array.map { (it as? JsonPrimitive)?.takeIf { part -> part.isString }?.content ?: error("$key 必须为字符串数组") }
    }

    /**
     * 删除条目或整本书后，一并回收它们的激活覆盖与跨轮计时状态。
     *
     * 只回收这场对话自己管理的书：`player:` 命名空间等由其他控制器合成的世界书（例如对话记忆）
     * 不在 `character.worldBooks` 里，必须原样保留，否则一次普通的世界书写入会清掉它们的运行状态。
     */
    private fun prune(record: ConversationRecord, books: List<WorldBookDefinition>): ConversationRecord {
        val previous = record.character.worldBooks
        val managed = previous.map { it.id }.toSet() + books.map { it.id }
        val liveBooks = books.map { it.id }.toSet()
        val liveEntries = books.associate { it.id to it.entries.map { entry -> entry.id }.toSet() }
        fun keys(source: List<WorldBookDefinition>): Set<String> =
            source.flatMap { book -> book.entries.map { "${book.id}:${it.id}" } }.toSet()
        val liveKeys = keys(books)
        val previousKeys = keys(previous)
        val overrides = record.runtimeState.worldBookActivationOverrides
        val runtime = record.runtimeState.copy(
            worldBookActivationOverrides = WorldBookActivationOverrides(
                books = overrides.books.filterKeys { it !in managed || it in liveBooks },
                entries = overrides.entries.entries
                    .filter { (bookId, _) -> bookId !in managed || bookId in liveEntries }
                    .associate { (bookId, values) ->
                        bookId to if (bookId in managed) values.filterKeys { it in liveEntries[bookId].orEmpty() } else values
                    }
                    .filterValues { it.isNotEmpty() },
            ),
            worldBookEntries = record.runtimeState.worldBookEntries.filterKeys { it !in previousKeys || it in liveKeys },
        )
        return record.copy(character = record.character.copy(worldBooks = books), runtimeState = runtime)
    }

    private fun WorldBookEntryDefinition.strategyName(): String = when {
        constant -> "constant"
        extensions["vectorized"] == JsonPrimitive(true) -> "vectorized"
        else -> "selective"
    }

    /** 条目声明了延迟级别时按级别回写，否则回落到布尔值。 */
    private fun WorldBookEntryDefinition.delayUntilRecursionValue(): JsonElement =
        (extensions["delay_until_recursion"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }?.let(::JsonPrimitive)
            ?: if (delayUntilRecursion) JsonPrimitive(1) else JsonNull

    private fun Int.positiveOrNull(): JsonElement = takeIf { it > 0 }?.let(::JsonPrimitive) ?: JsonNull

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.bool(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull

    /**
     * `sticky` / `cooldown` / `delay` 的上游类型是 `number | null`：显式 null 表示清除，不是「未提供」。
     */
    private fun JsonObject.timedEffect(key: String): Int? {
        if (!containsKey(key)) return null
        val value = get(key)
        if (value is JsonNull) return 0
        return ((value as? JsonPrimitive)?.intOrNull ?: error("$key 必须为整数或 null")).coerceAtLeast(0)
    }

    /** 旧版三态字段：`"same_as_global"` 表示沿用全局设置，等价于不改动。 */
    private fun JsonObject.triState(key: String): Boolean? =
        (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    /**
     * 书内 uid 分配：优先接受作者指定且未被占用的值，否则取已用最大值之后的下一个。
     *
     * 卡内条目的内部 id 后缀可能与其 uid 不同（源数组中有元素被导入器跳过时会错位），
     * 因此两者都要占位，避免新建条目复用已存在的内部 id。
     */
    private class UidAllocator(existing: List<WorldBookEntryDefinition>) {
        private val used = existing.flatMapIndexed { index, entry ->
            listOfNotNull(uid(entry, index), entry.id.substringAfterLast(':').toIntOrNull())
        }.toMutableSet()
        private var next = (used.maxOrNull() ?: -1) + 1

        fun reserve(requested: Int?): Int {
            if (requested != null && requested >= 0 && used.add(requested)) return requested
            while (next in used) next++
            used.add(next)
            return next
        }
    }
}
