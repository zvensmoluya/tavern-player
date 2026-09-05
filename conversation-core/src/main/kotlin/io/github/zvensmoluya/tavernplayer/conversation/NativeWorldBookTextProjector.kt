package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.NativeAdaptation
import io.github.zvensmoluya.tavernplayer.content.NativeSourceTextRange
import io.github.zvensmoluya.tavernplayer.content.NativeWorldBookTextSelectionValidator
import io.github.zvensmoluya.tavernplayer.content.WorldBookDefinition
import kotlinx.serialization.json.JsonPrimitive

data class NativeWorldBookTextProjection(
    val books: List<WorldBookDefinition>,
    val diagnostics: List<CompilationDiagnostic> = emptyList(),
    val trace: List<CompilationTraceEntry> = emptyList(),
)

/** 每次编译都从不可变原文和当前检查点选择正文，不产生状态写入或启停操作。 */
object NativeWorldBookTextProjector {
    fun project(
        books: List<WorldBookDefinition>,
        adaptation: NativeAdaptation?,
        sourceSha256: String,
        state: ConversationStateSnapshot,
    ): NativeWorldBookTextProjection {
        if (adaptation == null || adaptation.worldBookTextSelections.isEmpty()) return NativeWorldBookTextProjection(books)
        fun rejected(code: String, message: String, sourceId: String? = null) = NativeWorldBookTextProjection(
            books, listOf(CompilationDiagnostic(DiagnosticSeverity.ERROR, code, message, sourceId)),
        )
        if (adaptation.sourceSha256 != sourceSha256) {
            return rejected("SOURCE_HASH_MISMATCH", "原文选择与角色快照不匹配")
        }
        val issues = NativeWorldBookTextSelectionValidator.validate(adaptation, books)
        if (issues.isNotEmpty()) {
            val issue = issues.first()
            return rejected(issue.code, issue.message, issue.path)
        }
        val replacements = mutableMapOf<Pair<String, String>, String>()
        val trace = mutableListOf<CompilationTraceEntry>()
        adaptation.worldBookTextSelections.forEach { selection ->
            val value = (state.values[selection.stateKey] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val caseIndex = selection.cases.indexOfFirst { it.stateValue == value }
            if (caseIndex < 0) return rejected("INVALID_TEXT_SELECTION_STATE", "当前状态缺失或不在原文分支选项中", selection.stateKey)
            val case = selection.cases[caseIndex]
            val entry = books.single { it.id == selection.bookId }.entries.single { it.id == selection.entryId }
            val ranges = listOfNotNull(selection.sourcePrefix,
                NativeSourceTextRange(case.sourceStart, case.sourceEndExclusive), selection.sourceSuffix)
            replacements[selection.bookId to selection.entryId] = ranges.joinToString("") {
                entry.content.substring(it.start, it.endExclusive)
            }
            trace += CompilationTraceEntry(
                stage = "native-world-book-text",
                sourceIds = listOf(selection.bookId, selection.entryId, selection.stateKey),
                decision = "selected source case=$caseIndex ranges=${ranges.joinToString(",") { "${it.start}..${it.endExclusive}" }}; activation unchanged",
            )
        }
        return NativeWorldBookTextProjection(books.map { book ->
            book.copy(entries = book.entries.map { entry ->
                replacements[book.id to entry.id]?.let { entry.copy(content = it) } ?: entry
            })
        }, trace = trace)
    }
}
