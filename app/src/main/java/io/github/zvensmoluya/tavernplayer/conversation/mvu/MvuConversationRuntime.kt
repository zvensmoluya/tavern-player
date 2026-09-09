package io.github.zvensmoluya.tavernplayer.conversation.mvu

import io.github.zvensmoluya.tavernplayer.conversation.*
import io.github.zvensmoluya.tavernplayer.content.mvuProgram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Player owns the timeline. Each transaction gets a fresh JS instance, released on all exits. */
class MvuConversationRuntime(
    private val loadBundle: suspend () -> String = { error("此构建未提供 MVU 运行资源") },
) {
    /** Load and initialize the original program before offering an installable compilation result. */
    suspend fun validateProgram(character: CharacterSnapshot) {
        if (character.mvuProgram == null) return
        withRuntime(character) { runtime ->
            fun verify(evaluation: MvuEvaluation) {
                require(evaluation.diagnostics.none { it.level == "error" }) { "原 MVU 初始化报告错误" }
            }
            verify(runtime.initialize(listOf("Opening.")))
            val greetings = (listOf(character.firstMessage) + character.alternateFirstMessages).filter { it.isNotBlank() }
            if (greetings.isNotEmpty()) verify(runtime.initialize(greetings))
        }
    }

    suspend fun initialize(record: ConversationRecord): ConversationRecord {
        if (record.character.mvuProgram == null || record.runtimeState.mvuState != null) return record
        require(record.turns.size <= 1 && record.turns.all { turn ->
            turn.variants.all { it.openingSourceIndex != null }
        }) { "已有历史缺少 MVU 检查点，请新建对话" }
        return withRuntime(record.character) { runtime ->
            val base = runtime.initialize(listOf("Opening.")).messages.single()
            val opening = record.turns.singleOrNull()
            val variants = opening?.variants.orEmpty()
            val initialized = if (variants.isEmpty()) emptyList() else
                runtime.initialize(variants.map { it.message.sourceText }).messages
            require(initialized.size == variants.size)
            val next = variants.mapIndexed { index, variant ->
                variant.copy(
                    runtimeStateBefore = base.applyTo(variant.runtimeStateBefore ?: record.runtimeState),
                    projectionRuntimeStateBefore = base.applyTo(variant.projectionRuntimeStateBefore ?: record.runtimeState),
                    runtimeStateAfter = initialized[index].applyTo(variant.runtimeStateAfter ?: record.runtimeState),
                )
            }
            record.copy(
                turns = opening?.let { listOf(it.copy(variants = next)) }.orEmpty(),
                runtimeState = next.getOrNull(opening?.selectedVariantIndex ?: 0)?.runtimeStateAfter
                    ?: base.applyTo(record.runtimeState),
            )
        }
    }

    suspend fun validateCheckpoint(character: CharacterSnapshot, state: ConversationRuntimeState) {
        if (character.mvuProgram == null) return
        val checkpoint = requireNotNull(state.mvuState) { "缺少 MVU 检查点，请新建对话" }
        val bundle = withContext(Dispatchers.IO) { loadBundle() }
        require(checkpoint.bundleSha256 == sha256(bundle) && checkpoint.programSha256 == sha256(program(character).toString())) {
            "MVU 框架或卡程序已改变，当前检查点不能继续使用，请新建对话"
        }
    }

    suspend fun update(
        character: CharacterSnapshot,
        sourceText: String,
        previous: ConversationRuntimeState,
        opening: Boolean = false,
    ): MvuEvaluation? {
        if (character.mvuProgram == null) return null
        return withRuntime(character) { runtime ->
            if (opening) runtime.initialize(listOf(sourceText)) else runtime.update(sourceText, previous)
        }
    }

    private suspend fun <T> withRuntime(character: CharacterSnapshot, block: suspend (QuickJsMvuRuntime) -> T): T {
        val bundle = withContext(Dispatchers.IO) { loadBundle() }
        val runtime = QuickJsMvuRuntime.create(bundle, program(character))
        try { return block(runtime) } finally { runtime.close() }
    }

    private fun program(character: CharacterSnapshot): JsonObject {
        val schema = checkNotNull(character.mvuProgram)
        return buildJsonObject {
            put("schemaScript", schema.schemaScript)
            putJsonArray("entries") {
                character.worldBooks.forEach { book -> book.entries.forEach { entry ->
                    add(buildJsonObject {
                        put("comment", entry.comment.ifBlank { entry.name })
                        put("content", entry.content)
                        put("enabled", entry.enabled)
                    })
                } }
            }
        }
    }

    private fun sha256(value: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
