package io.github.zvensmoluya.tavernplayer.conversation

/**
 * 会话内世界书编辑的对外入口。
 *
 * 玩家在界面上能做的三件事——书级参与方式、条目启停、正文改写与恢复——都走这里；
 * 作者程序通过浏览器桥到达的是同一份实现（[BrowserWorldBook]），两者共用一套留痕与收口逻辑。
 * 所有结果都落在 [ConversationRecord.worldBookState] 上：只影响这场对话，不改角色资产，也不随消息候选回退。
 */
object ConversationWorldBookEditor {
    /**
     * 设置书级三态。三种取值互斥，转换按需要组合意图：
     * `DISABLED` 会清掉"必定生效"，`FORCED` 会同时解除书级停用。
     */
    fun setBookMode(record: ConversationRecord, bookId: String, mode: WorldBookBookMode): ConversationRecord {
        val intents = when (mode) {
            WorldBookBookMode.DISABLED -> listOf(WorldBookActivationIntent.SetBookEnabled(bookId, false))
            WorldBookBookMode.AUTO -> listOf(
                WorldBookActivationIntent.SetBookEnabled(bookId, true),
                WorldBookActivationIntent.SetBookForceEnabled(bookId, false),
            )
            WorldBookBookMode.FORCED -> listOf(WorldBookActivationIntent.SetBookForceEnabled(bookId, true))
        }
        return applyIntents(record, intents)
    }

    fun setEntryEnabled(
        record: ConversationRecord,
        bookId: String,
        entryId: String,
        enabled: Boolean,
    ): ConversationRecord =
        applyIntents(record, listOf(WorldBookActivationIntent.SetEntryEnabled(bookId, entryId, enabled)))

    /** 改写条目正文；改写前的原文会被记下，用于标明"已改过"并支持恢复。 */
    fun setEntryContent(
        record: ConversationRecord,
        bookId: String,
        entryId: String,
        content: String,
    ): ConversationRecord = BrowserWorldBook.setEntryContent(record, bookId, entryId, content)

    /** 恢复被改写的正文；[entryId] 为空时恢复这本书的全部已改写条目。启停与"必定生效"不受影响。 */
    fun restoreContent(
        record: ConversationRecord,
        bookId: String,
        entryId: String? = null,
    ): ConversationRecord = BrowserWorldBook.restoreContent(
        record,
        buildRestoreArgs(bookId, entryId),
    )

    /** 清除这场对话的全部世界书意图，回到原卡默认。 */
    fun reset(record: ConversationRecord): ConversationRecord =
        record.copy(worldBookState = ConversationWorldBookState())

    private fun applyIntents(
        record: ConversationRecord,
        intents: List<WorldBookActivationIntent>,
    ): ConversationRecord = when (
        val result = WorldBookActivationController().apply(record.character.worldBooks, record.worldBookState, intents)
    ) {
        is WorldBookActivationMutationResult.Applied -> record.copy(worldBookState = result.state)
        is WorldBookActivationMutationResult.Rejected -> error("世界书或条目不存在")
    }

    private fun buildRestoreArgs(bookId: String, entryId: String?): kotlinx.serialization.json.JsonObject =
        kotlinx.serialization.json.buildJsonObject {
            put("book", kotlinx.serialization.json.JsonPrimitive(bookId))
            entryId?.let { put("entry", kotlinx.serialization.json.JsonPrimitive(it)) }
        }
}
