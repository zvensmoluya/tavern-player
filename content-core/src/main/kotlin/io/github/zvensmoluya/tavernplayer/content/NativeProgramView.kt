package io.github.zvensmoluya.tavernplayer.content

import kotlinx.serialization.json.JsonObject

/** Source packaging, not recognized gameplay. Only request is sent to the model. */
internal data class NativeProgramView(
    val request: JsonObject,
    val sources: List<NativeProgramSource>,
    val warnings: List<String>,
)
internal data class NativeProgramSource(
    val id: String, val path: String, val kind: String, val active: Boolean, val content: String,
    val bookId: String? = null, val entryId: String? = null, val regexId: String? = null,
)
