package io.github.zvensmoluya.tavernplayer.content

object NativeMemoryValidator {
    fun validate(adaptation: NativeAdaptation, books: List<WorldBookDefinition>?): List<NativeAdaptationValidationIssue> {
        val issues = mutableListOf<NativeAdaptationValidationIssue>()
        fun issue(path: String, message: String) { issues += NativeAdaptationValidationIssue(path, "INVALID_MEMORY_DEFINITION", message) }
        if (adaptation.memories.size > 4) issue("memories", "对话记忆最多四项")
        val ids = mutableSetOf<String>()
        adaptation.memories.forEachIndexed { index, memory ->
            val path = "memories[$index]"
            if (!memory.id.matches(Regex("[a-z][a-z0-9-]{0,63}")) || !ids.add(memory.id)) issue(path, "记忆标识必须有效且唯一")
            if (memory.title.isBlank() || memory.title.length > 96) issue(path, "记忆标题必须非空且不超过 96 字符")
            if (memory.everyReplies !in 2..64 || memory.firstReply !in 2..memory.everyReplies) issue(path, "刷新间隔为 2 到 64 个助手回复，首次位置必须在该周期内且不能为开场")
            if (memory.references.size > 8 || memory.references.distinct().size != memory.references.size) issue(path, "参考资料最多八份且不能重复")
            (listOf(memory.instruction) + memory.references).forEach { ref ->
                val entry = books?.singleOrNull { it.id == ref.bookId }?.entries?.singleOrNull { it.id == ref.entryId }
                if (entry == null || NativeWorldBookTextSelectionValidator.sha256(entry.content) != ref.sourceContentSha256 ||
                    entry.content.isBlank() || entry.content.length > 32768 || "<%" in entry.content || "%>" in entry.content) {
                    issue(path, "记忆要求和资料必须引用唯一、哈希匹配、非空且不超过 32768 字符的静态世界书正文")
                }
            }
        }
        return issues
    }
}
