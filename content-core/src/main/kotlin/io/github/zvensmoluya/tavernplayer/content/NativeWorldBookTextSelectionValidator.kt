package io.github.zvensmoluya.tavernplayer.content

import java.security.MessageDigest

/** 校验来源与有限选项；不解析 EJS，也不声称证明人工挑选分支的语义正确性。 */
object NativeWorldBookTextSelectionValidator {
    fun validate(adaptation: NativeAdaptation, worldBooks: List<WorldBookDefinition>?): List<NativeAdaptationValidationIssue> {
        val issues = mutableListOf<NativeAdaptationValidationIssue>()
        fun issue(path: String, code: String, message: String) {
            issues += NativeAdaptationValidationIssue(path, code, message)
        }
        val selections = adaptation.worldBookTextSelections
        if (selections.size > 8) issue("worldBookTextSelections", "TOO_MANY_TEXT_SELECTIONS", "世界书原文分支超过 8 项")
        val targets = mutableSetOf<Pair<String, String>>()
        selections.forEachIndexed { index, selection ->
            val path = "worldBookTextSelections[$index]"
            if (!targets.add(selection.bookId to selection.entryId)) {
                issue(path, "DUPLICATE_TEXT_SELECTION", "一个世界书条目只能声明一份原文选择")
            }
            val definition = adaptation.state.singleOrNull { it.key == selection.stateKey }
            val expected = definition?.allowedStrings.orEmpty()
            if (definition?.type != ConversationStateValueType.STRING || expected.isEmpty() || expected.size > 32) {
                issue(path, "TEXT_SELECTION_REQUIRES_ENUM", "世界书原文选择只接受 1 到 32 个固定选项的字符串状态")
            }
            val values = selection.cases.map { it.stateValue }
            if (values.isEmpty() || values.size > 32 || values.distinct().size != values.size || values.toSet() != expected.toSet()) {
                issue(path, "INCOMPLETE_TEXT_SELECTION", "原文分支必须唯一且完整覆盖状态选项")
            }
            val entry = worldBooks?.singleOrNull { it.id == selection.bookId }?.entries?.singleOrNull { it.id == selection.entryId }
            if (entry == null) {
                issue(path, "UNKNOWN_TEXT_SELECTION_SOURCE", "必须提供存在且唯一的世界书条目以校验原文选择")
                return@forEachIndexed
            }
            if (selection.sourceContentSha256 != sha256(entry.content)) {
                issue(path, "TEXT_SELECTION_SOURCE_MISMATCH", "世界书原文已变化，不能使用旧的分支引用")
            }
            selection.cases.forEachIndexed { caseIndex, case ->
                val casePath = "$path.cases[$caseIndex]"
                val start = case.sourceStart
                val end = case.sourceEndExclusive
                if (start < 0 || end <= start || end > entry.content.length || end.toLong() - start > 8192 ||
                    !entry.content.isCharacterBoundary(start) || !entry.content.isCharacterBoundary(end)) {
                    issue(casePath, "INVALID_TEXT_SELECTION_RANGE", "原文区间必须有效、不截断字符且不超过 8192 个 UTF-16 单元")
                } else {
                    val text = entry.content.substring(start, end)
                    if (text.isBlank() || "<%" in text || "%>" in text) {
                        issue(casePath, "NON_LITERAL_TEXT_SELECTION", "原文分支必须是非空正文，不能包含未处理的 EJS 标记")
                    }
                }
            }
        }
        return issues
    }

    fun sha256(content: String): String = MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun String.isCharacterBoundary(index: Int): Boolean = index in 0..length &&
        (index == 0 || index == length || !this[index - 1].isHighSurrogate() || !this[index].isLowSurrogate())
}
