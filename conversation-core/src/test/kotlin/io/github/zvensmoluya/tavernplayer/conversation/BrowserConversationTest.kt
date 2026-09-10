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
        assertThrows(IllegalStateException::class.java) { BrowserConversation.apply(original, BrowserActor("page"), "messages.set",
            args("""{"messages":[{"message_id":0,"message":"Changed"},{"message_id":0,"extra":false,"message":"Invalid"}]}""")) }
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

    @Test fun `structural edits preserve identities metadata and live state across recovery`() {
        val actor = BrowserActor("script", scriptId = "s")
        val initial = BrowserConversation.apply(record(), actor, "variables.replace", args("""{"type":"chat","data":{"score":9}}"""))
        val appended = BrowserConversation.apply(initial, actor, "messages.create", args("""{"messages":[
            {"role":"user","message":"choice","data":{"pick":1},"extra":{"tag":"sample"}},
            {"role":"assistant","message":"result"}]}"""))
        assertEquals(listOf("Opening", "choice", "result"), appended.turns.map { it.selected.message.content })
        assertEquals("User", appended.turns[1].selected.message.authorName)
        assertEquals(JsonPrimitive("sample"), appended.turns[1].selected.browserExtra["tag"])
        val moved = BrowserConversation.apply(appended, actor, "messages.rotate", args("""{"begin":0,"middle":1,"end":3}"""))
        assertEquals(listOf("choice", "result", "Opening"), moved.turns.map { it.selected.message.content })
        assertEquals(initial.turns[0].id, moved.turns.last().id)
        val removed = BrowserConversation.apply(moved, actor, "messages.delete", args("""{"message_ids":[-1,2,999]}"""))
        assertEquals(2, removed.turns.size)
        assertEquals(JsonPrimitive(9), removed.runtimeState.browserChatVariables["score"])
        assertThrows(IllegalArgumentException::class.java) {
            BrowserConversation.authorize(removed, BrowserActor("page", "turn", "a"), BrowserConversation.revision(removed))
        }
        assertEquals(removed, Json.decodeFromString<ConversationRecord>(Json.encodeToString(removed)))
        val empty = BrowserConversation.apply(removed, actor, "messages.delete", args("""{"message_ids":[0,1]}"""))
        assertTrue(empty.turns.isEmpty())
        assertEquals(initial.runtimeState, empty.runtimeState)
    }

    @Test fun `create validates the whole batch and supports negative insertion`() {
        val actor = BrowserActor("script")
        val original = record()
        assertThrows(IllegalStateException::class.java) {
            BrowserConversation.apply(original, actor, "messages.create", args("""{"messages":[{"role":"user","message":"valid"},{"role":"user","message":42}]}"""))
        }
        assertEquals(1, original.turns.size)
        val next = BrowserConversation.apply(original, actor, "messages.create", args("""{"insert_before":-1,"messages":[{"role":"system","message":"prefix","is_hidden":true}]}"""))
        assertEquals("prefix", next.turns.first().selected.message.content)
        assertTrue(next.turns.first().selected.browserHidden)
        assertEquals(original.turns.first().id, next.turns.last().id)
    }

    @Test fun `message updates merge negative indices and resize candidate data`() {
        val next = BrowserConversation.apply(record(), BrowserActor("script"), "messages.set", args("""{"messages":[
            {"message_id":-1,"name":"Narrator","role":"system"},
            {"message_id":0,"swipes":["one","two","three"],"swipes_data":[{"score":1}],"swipes_info":[{"tag":true}],"swipe_id":9}
        ]}"""))
        assertEquals(3, next.turns.single().variants.size)
        assertEquals(2, next.turns.single().selectedVariantIndex)
        assertEquals("three", next.turns.single().selected.message.content)
        assertEquals(MessageRole.SYSTEM, next.turns.single().role)
        assertEquals("Narrator", next.turns.single().selected.message.authorName)
        assertEquals(JsonPrimitive(true), next.turns.single().variants.first().browserExtra["tag"])
        assertEquals(JsonPrimitive(1), BrowserConversation.variables(next.turns.single().variants.first())["score"])
        assertTrue(BrowserConversation.variables(next.turns.single().selected).isEmpty())
    }

    @Test fun `new messages retain explicit variables beside a live MVU checkpoint`() {
        val initial = record().copy(runtimeState = ConversationRuntimeState(mvuState = MvuStateSnapshot("b", "p",
            args("""{"schema":{},"stat_data":{"score":8}}"""))))
        val next = BrowserConversation.apply(initial, BrowserActor("script"), "messages.create",
            args("""{"messages":[{"role":"user","message":"choice","data":{"choice":2}}]}"""))
        assertEquals(JsonPrimitive(2), BrowserConversation.variables(next.turns.last().selected)["choice"])
        assertEquals(initial.runtimeState.mvuState, next.runtimeState.mvuState)
        val changed = BrowserConversation.apply(next, BrowserActor("script"), "variables.replace", args("""{"type":"chat","data":{"saved":true}}"""))
        assertEquals(JsonPrimitive(2), BrowserConversation.variables(changed.turns.last().selected)["choice"])
    }

    @Test fun `regex replacement changes display but preserves prompt and original message`() {
        val initial = record().copy(character = record().character.copy(regexScripts = listOf(
            io.github.zvensmoluya.tavernplayer.content.RegexDefinition("r", "sample", "Opening", "Before", disabled = true,
                placements = setOf(io.github.zvensmoluya.tavernplayer.content.RegexPlacement.AI_OUTPUT), markdownOnly = true))))
        val wire = BrowserRegex.encode(initial.character.regexScripts.single())
        val changed = JsonObject(wire + mapOf("enabled" to JsonPrimitive(true), "replace_string" to JsonPrimitive("After")))
        val next = BrowserConversation.apply(initial, BrowserActor("script"), "regex.replace", buildJsonObject { put("regexes", JsonArray(listOf(changed))) })
        val engine = CharacterRegexEngine(executionStrategy = ImmediateRegexExecutionStrategy)
        fun project(projection: RegexProjection) = engine.apply("Opening", next.character.regexScripts,
            io.github.zvensmoluya.tavernplayer.content.RegexPlacement.AI_OUTPUT, projection,
            context = MacroContext(character = next.character, persona = next.persona, conversationId = next.id), transaction = MacroTransaction()).text
        assertEquals("After", project(RegexProjection.DISPLAY))
        assertEquals("Opening", project(RegexProjection.PROMPT))
        assertEquals("Opening", next.turns.single().selected.message.content)
        assertNotEquals(BrowserConversation.revision(initial), BrowserConversation.revision(next))
        val recovered = Json.decodeFromString<ConversationRecord>(Json.encodeToString(next))
        assertEquals(next, recovered)
        assertEquals(next, BrowserConversation.apply(recovered, BrowserActor("script"), "regex.replace", buildJsonObject { put("regexes", JsonArray(listOf(changed))) }))
    }

    @Test fun `character variables persist in the captured character independently of chat checkpoints`() {
        val original = record()
        val next = BrowserConversation.apply(original, BrowserActor("script"), "variables.replace", args("""{"type":"character","data":{"seed":2}}"""))
        assertTrue(next.runtimeState.browserChatVariables.isEmpty())
        val switched = BrowserConversation.apply(next, BrowserActor("script"), "messages.set", args("""{"messages":[{"message_id":0,"swipe_id":1}]}"""))
        val restored = Json.decodeFromString<ConversationRecord>(Json.encodeToString(switched))
        assertEquals(JsonPrimitive(2), restored.character.browserProgram!!.variables["seed"])
        assertNotEquals(BrowserConversation.revision(original), BrowserConversation.revision(next))
    }
}
