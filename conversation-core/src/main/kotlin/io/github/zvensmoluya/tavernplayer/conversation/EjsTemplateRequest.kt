package io.github.zvensmoluya.tavernplayer.conversation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class EjsHistoryMessage(val role: String, val content: String)

@Serializable
data class EjsTemplateRequest(
    val sourceId: String,
    val template: String,
    val variables: JsonObject,
    val history: List<EjsHistoryMessage>,
)

/** Suspends preparation at the app boundary without introducing a JS dependency into core. */
class EjsRenderRequired(val request: EjsTemplateRequest) : RuntimeException(null, null, false, false)
