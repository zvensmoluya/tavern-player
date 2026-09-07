package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.*
import kotlinx.serialization.json.*

/** Retains references to one immutable checkpoint; no projected state is persisted. */
class ConversationStateReader(adaptation: NativeAdaptation?, private val checkpoint: ConversationRuntimeState) : NativeStateReader {
    private val bindings = adaptation?.stateBindings.orEmpty().associateBy { it.key }
    private val mvuEnabled = adaptation?.mvu != null

    override fun get(key: String): JsonElement? {
        val binding = bindings[key] ?: return checkpoint.conversationState.values[key]
        val root = when (binding.source) {
            NativeStateSource.PLAYER -> JsonObject(checkpoint.conversationState.values)
            NativeStateSource.MVU -> if (mvuEnabled) checkpoint.mvuState?.data else null
        }
        return NativeStatePath.read(root, binding.path)?.takeIf { NativeStatePath.matches(it, binding.type) }
    }
}
