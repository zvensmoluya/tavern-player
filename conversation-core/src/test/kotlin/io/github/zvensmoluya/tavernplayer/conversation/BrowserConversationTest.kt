package io.github.zvensmoluya.tavernplayer.conversation

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BrowserConversationTest {
    private fun record(): ConversationRecord = ConversationRecord(
        id = "conversation", character = CharacterAsset(id = "card", name = "Actor").snapshot(), persona = Persona("p", "User"),
        turns = listOf(ConversationTurn("turn", MessageRole.ASSISTANT, listOf(
            MessageVariant("a", ConversationMessage("m0", MessageRole.ASSISTANT, "Opening", "Actor"), runtimeStateAfter = ConversationRuntimeState()),
            MessageVariant("b", ConversationMessage("m1", MessageRole.ASSISTANT, "Alternative", "Actor"), runtimeStateAfter = ConversationRuntimeState()),
        ))), createdAtEpochMillis = 1, updatedAtEpochMillis = 1, executionMode = ConversationExecutionMode.BROWSER,
    )
    private fun args(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test fun `source writes are literal and preserve the following history`() {
        val initial = record().let { it.copy(turns = it.turns + ConversationTurn("later", MessageRole.USER,
            listOf(MessageVariant("c", ConversationMessage("m2", MessageRole.USER, "Later", "User"))))) }
        val next = BrowserConversation.apply(initial, BrowserActor("page", "turn", "a"), "messages.set",
            args("""{"messages":[{"message_id":0,"message":"{{setvar::x::1}}<body>Original</body>"}]}"""))
        assertEquals(2, next.turns.size)
        assertEquals("{{setvar::x::1}}<body>Original</body>", next.turns[0].selected.message.content)
        assertTrue(next.runtimeState.localVariables.isEmpty())
    }

    @Test fun `candidate changes invalidate the previous page and restore its own state`() {
        val original = record()
        val next = BrowserConversation.apply(original, BrowserActor("page"), "messages.set", args("""{"messages":[{"message_id":0,"swipe_id":1}]}"""))
        assertEquals("Alternative", next.turns.single().selected.message.content)
        assertThrows(IllegalArgumentException::class.java) {
            BrowserConversation.authorize(next, BrowserActor("page", "turn", "a"), BrowserConversation.revision(next))
        }
    }

    @Test fun `chat state follows the candidate checkpoint and survives serialization`() {
        val updated = BrowserConversation.apply(record(), BrowserActor("script", scriptId = "s"), "variables.replace",
            args("""{"type":"chat","data":{"score":3}}"""))
        assertEquals(JsonPrimitive(3), updated.turns.single().selected.nativeHead()!!.browserChatVariables["score"])
        val recovered = Json.decodeFromString<ConversationRecord>(Json.encodeToString(updated))
        assertEquals(updated, recovered)
        val other = BrowserConversation.apply(recovered, BrowserActor("page"), "messages.set", args("""{"messages":[{"message_id":0,"swipe_id":1}]}"""))
        assertTrue(other.runtimeState.browserChatVariables.isEmpty())
    }

    @Test fun `unsupported mutation fails atomically and old records keep native execution`() {
        val original = record()
        assertThrows(IllegalArgumentException::class.java) { BrowserConversation.apply(original, BrowserActor("page"), "messages.set",
            args("""{"messages":[{"message_id":0,"message":"Changed"},{"message_id":9,"message":"Invalid"}]}""")) }
        assertEquals("Opening", original.turns.single().selected.message.content)
        val encoded = Json.encodeToJsonElement(original).jsonObject
        val old = Json.decodeFromJsonElement<ConversationRecord>(JsonObject(encoded - "executionMode"))
        assertEquals(ConversationExecutionMode.LEGACY_NATIVE, old.executionMode)
    }

    @Test fun `stale revisions and unsupported global scope are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { BrowserConversation.authorize(record(), BrowserActor("page"), "stale") }
        assertThrows(IllegalStateException::class.java) { BrowserConversation.apply(record(), BrowserActor("page"), "variables.replace",
            args("""{"type":"global","data":{}}""")) }
    }
}
