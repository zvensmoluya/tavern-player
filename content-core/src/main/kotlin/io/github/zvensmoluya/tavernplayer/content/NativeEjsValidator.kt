package io.github.zvensmoluya.tavernplayer.content

/** Executable source stays in the immutable world book; only its identity is installed. */
object NativeEjsValidator {
    fun validate(adaptation: NativeAdaptation, books: List<WorldBookDefinition>?): List<NativeAdaptationValidationIssue> {
        val issues = mutableListOf<NativeAdaptationValidationIssue>()
        fun issue(message: String) { issues += NativeAdaptationValidationIssue("ejsTemplates", "INVALID_EJS_TEMPLATE", message) }
        val refs = adaptation.ejsTemplates
        if (refs.size > 32 || refs.distinctBy { it.bookId to it.entryId }.size != refs.size) issue("EJS 引用重复或超过 32 项")
        refs.forEach { ref ->
            if (!Regex("[a-f0-9]{64}").matches(ref.sourceContentSha256)) issue("EJS 来源哈希无效")
            if (adaptation.worldBookTextSelections.any { it.bookId == ref.bookId && it.entryId == ref.entryId })
                issue("同一条目不能同时执行 EJS 和旧原文分支选择")
            if (books != null) {
                val entry = books.singleOrNull { it.id == ref.bookId }?.entries?.singleOrNull { it.id == ref.entryId }
                if (entry == null || "<%" !in entry.content || entry.content.length > 256_000 ||
                    NativeWorldBookTextSelectionValidator.sha256(entry.content) != ref.sourceContentSha256)
                    issue("EJS 来源不存在、已改变或超过限制")
                if (entry != null && Regex("<%[\\s\\S]*?%>").findAll(entry.content).any { "{{" in it.value })
                    issue("EJS 代码内部的 Macro 暂不支持；不能动态拼接可执行代码")
            }
        }
        return issues
    }
}
