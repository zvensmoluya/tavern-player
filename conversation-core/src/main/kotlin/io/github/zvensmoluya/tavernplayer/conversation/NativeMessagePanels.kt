package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.NativeAdaptation

data class NativeMessagePanelContent(val id: String, val title: String, val fields: List<Pair<String, String>>)
data class NativeMessagePanelsProjection(val narrative: String, val panels: List<NativeMessagePanelContent>)

object NativeMessagePanels {
    fun project(adaptation: NativeAdaptation?, source: String, streaming: Boolean = false): NativeMessagePanelsProjection {
        var narrative = source
        val panels = mutableListOf<NativeMessagePanelContent>()
        adaptation?.messagePanels.orEmpty().forEach { panel ->
            val open = "<${panel.sourceTag}>"
            val close = "</${panel.sourceTag}>"
            val start = narrative.indexOf(open, ignoreCase = true)
            if (start < 0) return@forEach
            val body = narrative.singleTaggedContent(open, close)
            if (body == null || body.length > 16_384) {
                if (streaming && !narrative.contains(close, ignoreCase = true)) narrative = narrative.substring(0, start).trimEnd()
                return@forEach
            }
            var remainder: String = body
            val fields = panel.fields.map { field ->
                val fieldOpen = "<${field.tag}>"
                val fieldClose = "</${field.tag}>"
                val content = body.singleTaggedContent(fieldOpen, fieldClose) ?: return@forEach
                val fieldStart = remainder.indexOf(fieldOpen, ignoreCase = true)
                val fieldEnd = remainder.indexOf(fieldClose, fieldStart, ignoreCase = true)
                if (fieldStart < 0 || fieldEnd < 0 || content.length > 8192) return@forEach
                remainder = remainder.removeRange(fieldStart, fieldEnd + fieldClose.length)
                field.label to content.trim()
            }
            // 不静默丢弃未映射的资料；出现新标签或额外正文时保留原文供排查。
            if (remainder.isNotBlank()) return@forEach
            val end = narrative.indexOf(close, start, ignoreCase = true) + close.length
            narrative = (narrative.substring(0, start).trimEnd() + "\n\n" + narrative.substring(end).trimStart()).trim()
            panels += NativeMessagePanelContent(panel.id, panel.title, fields)
        }
        return NativeMessagePanelsProjection(narrative, panels)
    }
}
