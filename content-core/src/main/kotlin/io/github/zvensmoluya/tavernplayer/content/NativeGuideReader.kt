package io.github.zvensmoluya.tavernplayer.content

data class NativeGuideContent(val title: String, val sections: List<NativeGuideSectionContent>)
data class NativeGuideSectionContent(val id: String, val title: String, val text: String)
data class NativeGuideReading(val content: NativeGuideContent? = null, val error: String? = null)

/** Source references produce display text only. Neither Regex nor card macros are evaluated. */
object NativeGuideReader {
    fun validate(guide: NativeGuideView?, rules: List<RegexDefinition>?): List<NativeAdaptationValidationIssue> {
        if (guide == null) return emptyList()
        val issues = mutableListOf<NativeAdaptationValidationIssue>()
        fun issue(path: String, message: String) { issues += NativeAdaptationValidationIssue(path, "INVALID_GUIDE", message) }
        if (guide.title.isBlank() || guide.title.length > 96) issue("guide.title", "说明标题须为 1 到 96 字符")
        if (guide.sections.size !in 1..16) issue("guide.sections", "说明须包含 1 到 16 节")
        val source = rules?.singleOrNull { it.id == guide.sourceRegexId }
        if (source == null || !source.markdownOnly || source.promptOnly) {
            issue("guide.sourceRegexId", "说明必须引用唯一存在的原卡显示规则")
        }
        if (!HASH.matches(guide.sourceContentSha256) || source != null &&
            guide.sourceContentSha256 != NativeWorldBookTextSelectionValidator.sha256(source.replaceString)) {
            issue("guide.sourceContentSha256", "说明原文已变化，不能使用旧引用")
        }
        val ids = mutableSetOf<String>()
        var total = 0L
        guide.sections.forEachIndexed { index, section ->
            val path = "guide.sections[$index]"
            if (!ID.matches(section.id) || !ids.add(section.id)) issue(path, "说明节 ID 必须有效且唯一")
            if (section.title.isBlank() || section.title.length > 96) issue(path, "小节标题须为 1 到 96 字符")
            if (section.excerpts.size !in 1..8) issue(path, "每节须包含 1 到 8 段原文")
            section.excerpts.forEach { range ->
                val length = range.endExclusive.toLong() - range.start
                total += length.coerceAtLeast(0)
                val text = source?.replaceString
                if (range.start < 0 || length !in 1..8192 || text == null || range.endExclusive > text.length ||
                    !text.boundary(range.start) || !text.boundary(range.endExclusive)) {
                    issue(path, "原文区间无效、过长或截断字符")
                } else {
                    val excerpt = text.substring(range.start, range.endExclusive)
                    if (excerpt.isBlank() || ACTIVE_MARKUP.containsMatchIn(excerpt) || "<%" in excerpt || "%>" in excerpt) {
                        issue(path, "说明只能选取非空静态正文，不包含脚本、样式或 EJS")
                    }
                }
            }
        }
        if (total > 32768) issue("guide.sections", "说明正文总长超过 32768 个 UTF-16 单元")
        return issues
    }

    fun read(adaptation: NativeAdaptation?, rules: List<RegexDefinition>, expectedSourceSha256: String): NativeGuideReading {
        val guide = adaptation?.guide ?: return NativeGuideReading()
        val issues = validate(guide, rules)
        if (adaptation.sourceSha256 != expectedSourceSha256 || issues.isNotEmpty()) {
            return NativeGuideReading(error = "玩法说明的来源无法核对，请重新安装适配。")
        }
        val source = rules.single { it.id == guide.sourceRegexId }.replaceString
        return NativeGuideReading(NativeGuideContent(guide.title, guide.sections.map { section ->
            NativeGuideSectionContent(section.id, section.title,
                section.excerpts.joinToString("\n\n") { source.substring(it.start, it.endExclusive).trim() })
        }))
    }

    private fun String.boundary(index: Int): Boolean = index in 0..length &&
        (index == 0 || index == length || !this[index - 1].isHighSurrogate() || !this[index].isLowSurrogate())
    private val ID = Regex("[a-z][a-z0-9-]{0,63}")
    private val HASH = Regex("[a-f0-9]{64}")
    private val ACTIVE_MARKUP = Regex("<\\s*/?\\s*(script|style|iframe|object|embed)\\b", RegexOption.IGNORE_CASE)
}
