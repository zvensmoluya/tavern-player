package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BrowserWorldBookTest {
    private val hash = "a".repeat(64)
    private val actor = BrowserActor("script", scriptId = "s")

    private fun args(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun asset() = CharacterAsset(
        id = "card", sourceSha256 = hash, name = "Guide", firstMessage = "Go.",
        worldBooks = listOf(WorldBookDefinition(id = "notes", name = "Field Notes", entries = listOf(
            WorldBookEntryDefinition(id = "notes:entry:0", sourceId = "0", keys = listOf("lighthouse"),
                content = "The lighthouse is dark.", selective = true),
        ))),
    )

    private fun record(asset: CharacterAsset = asset()) = ConversationRecord(
        id = "conversation", character = asset.snapshot(), persona = Persona("p", "Traveler"),
        turns = listOf(ConversationTurn("turn", MessageRole.USER, listOf(
            MessageVariant("u", ConversationMessage("m0", MessageRole.USER, "I wait.", "Traveler"))))),
        createdAtEpochMillis = 1, updatedAtEpochMillis = 1, executionMode = ConversationExecutionMode.BROWSER,
    )

    /** 用真实 PromptCompiler 检查写入是否真的进入下一轮编排，而不是只看接口返回成功。 */
    private fun compile(
        record: ConversationRecord,
        userText: String,
        renderer: (EjsTemplateRequest) -> String = { error("本用例不应执行 EJS 模板") },
    ): CompilationResult.Success {
        val history = record.turns.map {
            ConversationMessage(it.selected.message.id, it.role, it.selected.message.content, it.selected.message.authorName)
        } + ConversationMessage("u-last", MessageRole.USER, userText, record.persona.name)
        return PromptCompiler().compile(NormalGenerationInput(
            character = record.character, persona = record.persona, history = history,
            preset = BuiltInPresets.default, runtimeState = record.runtimeState,
            worldBookState = record.worldBookState,
            modelContextTokens = 32768, generationId = "world-book-test", ejsRenderer = renderer,
        )) as CompilationResult.Success
    }

    private fun legacy(record: ConversationRecord): JsonArray =
        BrowserConversation.readWorldBookEntries(record, args("""{"book":"notes"}""")).jsonArray

    @Test fun `entry writes stay inside the conversation and reach the next prompt`() {
        val asset = asset()
        val original = record(asset)
        val written = BrowserConversation.apply(original, actor, "worldbook.entries.create",
            args("""{"book":"notes","entries":[{"name":"Harbor","keys":["harbor"],"content":"HARBOR-FACT"}]}"""))

        assertEquals(2, written.character.worldBooks.single().entries.size)
        // 角色资产原件与已经建立的另一份会话快照都不受影响。
        assertEquals(1, asset.worldBooks.single().entries.size)
        assertEquals(1, legacy(original).size)
        assertEquals(1, asset.snapshot().worldBooks.single().entries.size)

        // 关键字未命中时不注入；命中后按条目内容进入下一轮提示词。
        assertFalse(compile(written, "I wait by the fire.").plan.messages.any { "HARBOR-FACT" in it.content })
        assertTrue(compile(written, "I walk to the harbor.").plan.messages.any { "HARBOR-FACT" in it.content })
        assertEquals(1, legacy(original).size)
    }

    @Test fun `legacy flat entries map both directions onto the same definition`() {
        val original = record()
        val flat = legacy(original).single().jsonObject
        assertEquals(0, flat["uid"]!!.jsonPrimitive.int)
        assertEquals("selective", flat["type"]!!.jsonPrimitive.content)
        assertEquals("before_character_definition", flat["position"]!!.jsonPrimitive.content)
        assertEquals("The lighthouse is dark.", flat["content"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, flat["sticky"])

        val updated = BrowserConversation.apply(original, actor, "worldbook.entries.update", args("""{"book":"notes","entries":[
            {"uid":0,"content":"Rewritten","position":"at_depth_as_user","depth":2,"sticky":4,"keys":["tide"],
             "exclude_recursion":true,"delay_until_recursion":2}]}"""))
        val entry = updated.character.worldBooks.single().entries.single()
        assertEquals("Rewritten", entry.content)
        assertEquals(WorldBookPosition.AT_DEPTH, entry.position)
        assertEquals(ContentRole.USER, entry.role)
        assertEquals(2, entry.depth)
        assertEquals(4, entry.sticky)
        assertEquals(listOf("tide"), entry.keys)
        assertTrue(entry.excludeRecursion)
        assertEquals(2, entry.extensions["delay_until_recursion"]!!.jsonPrimitive.int)
        // 卡内条目的源 uid 与内部身份保持不变，只有内容与配置被改写。
        assertEquals("0", entry.sourceId)
        assertEquals("notes:entry:0", entry.id)
        // 原件未被改写。
        assertEquals("The lighthouse is dark.", original.character.worldBooks.single().entries.single().content)

        // 改回扁平视图后仍然是同一份定义。
        val reread = legacy(updated).single().jsonObject
        assertEquals("Rewritten", reread["content"]!!.jsonPrimitive.content)
        assertEquals("at_depth_as_user", reread["position"]!!.jsonPrimitive.content)
        assertEquals(2, reread["delay_until_recursion"]!!.jsonPrimitive.int)
        assertThrows(IllegalArgumentException::class.java) {
            BrowserConversation.apply(original, actor, "worldbook.entries.update",
                args("""{"book":"notes","entries":[{"uid":9,"content":"x"}]}"""))
        }
    }

    @Test fun `new entries take fresh uids and deletion reclaims their bookkeeping`() {
        val created = BrowserConversation.apply(record(), actor, "worldbook.entries.create",
            args("""{"book":"notes","entries":[{},{"name":"third"}]}"""))
        val entries = created.character.worldBooks.single().entries
        assertEquals(listOf(0, 1, 2), entries.mapIndexed { index, entry -> BrowserWorldBook.uid(entry, index) })
        assertEquals(3, entries.map { it.id }.distinct().size)

        val target = entries.last()
        val tracked = created.copy(
            // 玩家/脚本的启停意图属于会话级状态，剧情派生的跨轮计时留在 runtimeState。
            worldBookState = created.worldBookState.copy(
                activation = WorldBookActivationOverrides(entries = mapOf("notes" to mapOf(target.id to false))),
            ),
            runtimeState = created.runtimeState.copy(
                worldBookEntries = mapOf("notes:${target.id}" to WorldBookEntryRuntimeState(stickyRemaining = 3)),
            ),
        )
        val removed = BrowserConversation.apply(tracked, actor, "worldbook.entries.delete", args("""{"book":"notes","uids":[2]}"""))
        assertEquals(2, removed.character.worldBooks.single().entries.size)
        assertTrue(removed.worldBookState.activation.entries.isEmpty())
        assertTrue(removed.runtimeState.worldBookEntries.isEmpty())
        assertEquals(listOf(0, 1), legacy(removed).jsonArray.map { it.jsonObject.getValue("uid").jsonPrimitive.int })
    }

    @Test fun `a created book stays out of the prompt until it is rebound`() {
        val created = BrowserConversation.apply(record(), actor, "worldbook.books.create",
            args("""{"name":"Scratch","entries":[{"content":"SCRATCH-FACT","strategy":{"type":"constant"}}]}"""))
        assertEquals(2, created.character.worldBooks.size)
        assertFalse(created.worldBookState.activation.isBookEnabled("Scratch"))
        assertFalse(compile(created, "anything").plan.messages.any { "SCRATCH-FACT" in it.content })

        val rebound = BrowserConversation.apply(created, actor, "worldbook.books.rebind", args("""{"primary":"Scratch","additional":[]}"""))
        assertTrue(rebound.worldBookState.activation.isBookEnabled("Scratch"))
        assertFalse(rebound.worldBookState.activation.isBookEnabled("notes"))
        assertEquals("Scratch", rebound.character.worldBooks.first().id)
        assertTrue(compile(rebound, "anything").plan.messages.any { "SCRATCH-FACT" in it.content })

        val deleted = BrowserConversation.apply(rebound, actor, "worldbook.books.delete", args("""{"name":"Scratch"}"""))
        assertEquals(listOf("notes"), deleted.character.worldBooks.map { it.id })
        // 删除只回收这本书自己的覆盖；被 rebind 关掉的另一本书保持关闭。
        assertEquals(setOf("notes"), deleted.worldBookState.activation.books.keys)
        assertFalse(deleted.worldBookState.activation.isBookEnabled("notes"))
        assertThrows(IllegalArgumentException::class.java) {
            BrowserConversation.apply(deleted, actor, "worldbook.books.rebind", args("""{"primary":"Missing","additional":[]}"""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BrowserConversation.apply(deleted, actor, "worldbook.books.create", args("""{"name":"player:conversation-memory"}"""))
        }
    }

    @Test fun `a template whose source changed is skipped with a diagnostic instead of stopping the conversation`() {
        val entry = WorldBookEntryDefinition(id = "notes:entry:0", sourceId = "0", constant = true,
            content = "before <% if (true) { %>X<% } %>")
        val asset = CharacterAsset(id = "card", sourceSha256 = hash, name = "Guide", firstMessage = "Go.",
            worldBooks = listOf(WorldBookDefinition(id = "notes", entries = listOf(entry))))
        val template = NativeWorldBookReference("notes", entry.id, BrowserProgramReader.sha256(entry.content))
        val base = record(asset).let {
            it.copy(character = it.character.copy(browserProgram = BrowserProgram(ejsTemplates = listOf(template))))
        }
        assertTrue(compile(base, "anything", renderer = { "RENDERED" }).plan.messages.any { "RENDERED" in it.content })

        val changed = base.copy(character = base.character.copy(worldBooks = listOf(WorldBookDefinition(
            id = "notes", entries = listOf(entry.copy(content = "after <% if (true) { %>X<% } %>"))))))
        val success = PromptCompiler().compile(NormalGenerationInput(
            character = changed.character, persona = changed.persona,
            history = listOf(ConversationMessage("u", MessageRole.USER, "anything", "Traveler")),
            preset = BuiltInPresets.default, runtimeState = changed.runtimeState,
            modelContextTokens = 32768, generationId = "stale-template", ejsRenderer = { error("失效模板不得执行") },
        )) as CompilationResult.Success
        assertTrue(success.plan.diagnostics.any { it.code == "STALE_EJS_TEMPLATE" })
        // 既不执行模板，也不把模板源码注入提示词。
        assertFalse(success.plan.messages.any { "<%" in it.content })
        assertTrue(success.plan.diagnostics.any { it.code == "STALE_EJS_TEMPLATE" && it.sourceId == "notes:entry:0" })
    }

    @Test fun `a write leaves state owned by other world book controllers alone`() {
        val original = record()
        val memory = "player:conversation-memory:summary"
        val tracked = original.copy(
            worldBookState = original.worldBookState.copy(
                activation = WorldBookActivationOverrides(
                    books = mapOf("player:conversation-memory" to true),
                    entries = mapOf("player:conversation-memory" to mapOf(memory to false)),
                ),
            ),
            runtimeState = original.runtimeState.copy(
                worldBookEntries = mapOf("player:conversation-memory:$memory" to WorldBookEntryRuntimeState(stickyRemaining = 2)),
            ),
        )
        val written = BrowserConversation.apply(tracked, actor, "worldbook.entries.create",
            args("""{"book":"notes","entries":[{"name":"Harbor","content":"HARBOR"}]}"""))
        assertEquals(2, written.character.worldBooks.single().entries.size)
        // 对话记忆是编译期合成的世界书，不属于角色书集合，它的启停与跨轮状态必须原样保留。
        assertEquals(mapOf("player:conversation-memory" to true), written.worldBookState.activation.books)
        assertEquals(mapOf("player:conversation-memory" to mapOf(memory to false)), written.worldBookState.activation.entries)
        assertEquals(WorldBookEntryRuntimeState(stickyRemaining = 2),
            written.runtimeState.worldBookEntries["player:conversation-memory:$memory"])
    }

    @Test fun `entry level activation addresses the uid the snapshot exposes`() {
        val created = BrowserConversation.apply(record(), actor, "worldbook.entries.create",
            args("""{"book":"notes","entries":[{"name":"Second","content":"SECOND"}]}"""))
        // 删掉卡内那条之后，剩下的条目 uid 与它的列表下标不再相同。
        val removed = BrowserConversation.apply(created, actor, "worldbook.entries.delete", args("""{"book":"notes","uids":[0]}"""))
        val remaining = removed.character.worldBooks.single().entries.single()
        assertEquals(1, BrowserWorldBook.uid(remaining, 0))
        val disabled = BrowserConversation.apply(removed, actor, "worldbook.activation",
            args("""{"book":"notes","entry":"1","enabled":false}"""))
        assertEquals(false, disabled.worldBookState.activation.entries.getValue("notes").getValue(remaining.id))
        assertFalse(disabled.worldBookState.activation.isEntryEnabled("notes", remaining.id, true))
        assertThrows(IllegalStateException::class.java) {
            BrowserConversation.apply(removed, actor, "worldbook.activation", args("""{"book":"notes","entry":"9","enabled":false}"""))
        }
    }

    @Test fun `writing secondary logic overrides the imported source option`() {
        val entry = WorldBookEntryDefinition(id = "notes:entry:0", sourceId = "0", keys = listOf("a"),
            secondaryKeys = listOf("b"), selective = true, extensions = buildJsonObject { put("selectiveLogic", 1) })
        val original = record().let {
            it.copy(character = it.character.copy(worldBooks = listOf(WorldBookDefinition(id = "notes", entries = listOf(entry)))))
        }
        assertEquals(WorldBookSecondaryLogic.NOT_ALL, original.character.worldBooks.single().entries.single().effectiveSecondaryLogic)

        val updated = BrowserConversation.apply(original, actor, "worldbook.entries.update",
            args("""{"book":"notes","entries":[{"uid":0,"logic":"and_all"}]}"""))
        val target = updated.character.worldBooks.single().entries.single()
        assertEquals(WorldBookSecondaryLogic.AND_ALL, target.secondaryLogic)
        // 原始扩展会压过定义字段，写入必须让它失效。
        assertEquals(WorldBookSecondaryLogic.AND_ALL, target.effectiveSecondaryLogic)
        assertEquals(WorldBookSecondaryLogic.AND_ALL,
            updated.character.worldBooks.single().entries.single().effectiveSecondaryLogic)
    }

    @Test fun `reading and writing a legacy entry back does not change its meaning`() {
        val outlet = WorldBookEntryDefinition(id = "notes:entry:7", sourceId = "7", position = WorldBookPosition.OUTLET,
            role = ContentRole.USER, outletName = "panel", content = "panel text")
        val partial = WorldBookEntryDefinition(id = "notes:entry:8", sourceId = "8", keys = listOf("k"),
            secondaryKeys = listOf("s"), selective = false, content = "x")
        val original = record().let {
            it.copy(character = it.character.copy(worldBooks = listOf(
                WorldBookDefinition(id = "notes", entries = listOf(outlet, partial)))))
        }
        val returned = BrowserConversation.apply(original, actor, "worldbook.entries.replace",
            args("""{"book":"notes","entries":${legacy(original)}}"""))
        val entries = returned.character.worldBooks.single().entries
        // outlet 在旧版结构里没有对应值，原样回写不得把它降级成深度插入。
        assertEquals(WorldBookPosition.OUTLET, entries[0].position)
        assertEquals("panel", entries[0].outletName)
        assertEquals(ContentRole.USER, entries[0].role)
        // `selective` 与激活策略不是同一个概念，原样回写不得翻转它。
        assertFalse(entries[1].selective)
        assertFalse(entries[1].constant)
        assertNull(entries[1].caseSensitive)
        assertEquals(WorldBookSecondaryLogic.AND_ANY, entries[1].effectiveSecondaryLogic)
        assertEquals(listOf("k"), entries[1].keys)
        assertEquals(listOf("s"), entries[1].secondaryKeys)
    }

    @Test fun `an ambiguous uid is rejected instead of affecting several entries`() {
        // 卡内第一个条目没有数字 id（uid 退化为下标 0），第二个条目的数字 id 也是 0。
        val first = WorldBookEntryDefinition(id = "notes:entry:0", name = "first", content = "first")
        val second = WorldBookEntryDefinition(id = "notes:entry:1", sourceId = "0", name = "second", content = "second")
        val original = record().let {
            it.copy(character = it.character.copy(worldBooks = listOf(
                WorldBookDefinition(id = "notes", entries = listOf(first, second)))))
        }
        assertEquals(0, BrowserWorldBook.uid(first, 0))
        assertEquals(0, BrowserWorldBook.uid(second, 1))
        assertThrows(IllegalArgumentException::class.java) {
            BrowserConversation.apply(original, actor, "worldbook.entries.delete", args("""{"book":"notes","uids":[0]}"""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BrowserConversation.apply(original, actor, "worldbook.entries.update",
                args("""{"book":"notes","entries":[{"uid":0,"content":"x"}]}"""))
        }
        assertEquals(2, original.character.worldBooks.single().entries.size)
    }

    @Test fun `new entries never reuse an existing internal id`() {
        // 现存条目的内部 id 后缀是 1，但它的对外 uid 是 0。
        val existing = WorldBookEntryDefinition(id = "notes:entry:1", name = "existing", content = "existing")
        val original = record().let {
            it.copy(character = it.character.copy(worldBooks = listOf(WorldBookDefinition(id = "notes", entries = listOf(existing)))))
        }
        val created = BrowserConversation.apply(original, actor, "worldbook.entries.create",
            args("""{"book":"notes","entries":[{"name":"fresh","content":"fresh"}]}"""))
        val entries = created.character.worldBooks.single().entries
        assertEquals(2, entries.map { it.id }.distinct().size)
        assertEquals(setOf(0, 2), entries.mapIndexed { index, entry -> BrowserWorldBook.uid(entry, index) }.toSet())
    }

    @Test fun `a read modify write round trip lets new secondary logic win over the carried extra`() {
        val entry = WorldBookEntryDefinition(id = "notes:entry:0", sourceId = "0", keys = listOf("a"),
            secondaryKeys = listOf("b"), selective = true,
            extensions = buildJsonObject { put("selectiveLogic", 1); put("custom", "kept") })
        val original = record().let {
            it.copy(character = it.character.copy(worldBooks = listOf(WorldBookDefinition(id = "notes", entries = listOf(entry)))))
        }
        // 助手侧「读取 → 改次要关键字逻辑 → 原样回写」会带上 extra，extra 里仍是被读出的旧 selectiveLogic。
        val patch = buildJsonObject {
            put("uid", 0)
            put("extra", buildJsonObject { put("selectiveLogic", 1); put("custom", "kept") })
            putJsonObject("strategy") {
                put("type", "selective")
                putJsonObject("keys_secondary") {
                    put("logic", "and_all")
                    put("keys", JsonArray(listOf(JsonPrimitive("b"))))
                }
            }
        }
        val updated = BrowserConversation.apply(original, actor, "worldbook.entries.update",
            args(buildJsonObject {
                put("book", "notes")
                put("entries", JsonArray(listOf(patch)))
            }.toString()))
        val target = updated.character.worldBooks.single().entries.single()
        assertEquals(WorldBookSecondaryLogic.AND_ALL, target.secondaryLogic)
        assertEquals(WorldBookSecondaryLogic.AND_ALL, target.effectiveSecondaryLogic)
        // 其余扩展字段仍原样保留。
        assertEquals("kept", target.extensions.getValue("custom").jsonPrimitive.content)
        assertNull(target.extensions["selectiveLogic"])
    }

    @Test fun `an explicit null clears a timed effect and absent fields stay untouched`() {
        val entry = WorldBookEntryDefinition(id = "notes:entry:0", sourceId = "0", constant = true, content = "x",
            sticky = 5, cooldown = 3, delay = 2)
        val original = record().let {
            it.copy(character = it.character.copy(worldBooks = listOf(WorldBookDefinition(id = "notes", entries = listOf(entry)))))
        }
        val cleared = BrowserConversation.apply(original, actor, "worldbook.entries.update",
            args("""{"book":"notes","entries":[{"uid":0,"sticky":null,"effect":{"cooldown":null,"delay":null}}]}"""))
        val target = cleared.character.worldBooks.single().entries.single()
        assertEquals(0, target.sticky)
        assertEquals(0, target.cooldown)
        assertEquals(0, target.delay)
        // 回读也必须反映清除结果。
        val flat = legacy(cleared).single().jsonObject
        assertEquals(JsonNull, flat["sticky"])
        assertEquals(JsonNull, flat["cooldown"])

        val untouched = BrowserConversation.apply(original, actor, "worldbook.entries.update",
            args("""{"book":"notes","entries":[{"uid":0,"content":"y"}]}"""))
        assertEquals(5, untouched.character.worldBooks.single().entries.single().sticky)
    }

    @Test fun `a template rewritten as plain text takes effect in the prompt`() {
        val entry = WorldBookEntryDefinition(id = "notes:entry:0", sourceId = "0", constant = true,
            content = "before <% if (true) { %>X<% } %>")
        val asset = CharacterAsset(id = "card", sourceSha256 = hash, name = "Guide", firstMessage = "Go.",
            worldBooks = listOf(WorldBookDefinition(id = "notes", entries = listOf(entry))))
        val template = NativeWorldBookReference("notes", entry.id, BrowserProgramReader.sha256(entry.content))
        val base = record(asset).let {
            it.copy(character = it.character.copy(browserProgram = BrowserProgram(ejsTemplates = listOf(template))))
        }
        val rewritten = base.copy(character = base.character.copy(worldBooks = listOf(WorldBookDefinition(
            id = "notes", entries = listOf(entry.copy(content = "PLAIN-REWRITE"))))))
        // renderer 抛错即证明没有把改写后的内容当模板执行。
        val success = compile(rewritten, "anything", renderer = { error("改写后的普通文本不得触发模板执行") })
        assertTrue(success.plan.messages.any { "PLAIN-REWRITE" in it.content })
        assertTrue(success.plan.diagnostics.any { it.code == "STALE_EJS_TEMPLATE" })
    }

    @Test fun `writes survive a save and reload and unsupported fields are rejected`() {
        val original = record()
        val written = BrowserConversation.apply(original, actor, "worldbook.entries.replace", args("""{"book":"notes","entries":[
            {"uid":0,"content":"Rewritten","strategy":{"type":"constant"}},{"name":"Second","content":"SECOND"}]}"""))
        val recovered = Json.decodeFromString<ConversationRecord>(Json.encodeToString(written))
        assertEquals(written.character.worldBooks, recovered.character.worldBooks)
        assertEquals(2, recovered.character.worldBooks.single().entries.size)
        assertEquals("Rewritten", recovered.character.worldBooks.single().entries.first().content)
        assertEquals("Second", recovered.character.worldBooks.single().entries.last().name)
        // 同一角色的另一场对话仍然只有原始条目。
        assertEquals(1, record().character.worldBooks.single().entries.size)
        assertEquals(1, legacy(record()).size)

        listOf(
            """{"book":"notes","entries":[{"content":"x","unknown_field":1}]}""",
            """{"book":"notes","entries":[{"content":"x","filters":["unsupported"]}]}""",
            """{"book":"notes","entries":[{"content":"x","automation_id":"auto"}]}""",
        ).forEach { payload ->
            assertThrows(IllegalArgumentException::class.java) {
                BrowserConversation.apply(original, actor, "worldbook.entries.replace", args(payload))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            BrowserConversation.apply(original, actor, "worldbook.entries.update", args("""{"book":"missing","entries":[{"uid":0}]}"""))
        }
    }

    // ---------------------------------------------------------------- 会话级世界书意图

    private fun snapshotBook(record: ConversationRecord): JsonObject =
        BrowserConversation.snapshot(record).getValue("worldbooks").jsonArray.single().jsonObject

    private fun snapshotEntry(record: ConversationRecord, index: Int = 0): JsonObject =
        snapshotBook(record).getValue("entries").jsonArray[index].jsonObject

    /** 带两个候选的记录：每个候选各自携带不同的跨轮计时，用来对比两类状态。 */
    private fun swipeable(entry: WorldBookEntryDefinition, asset: CharacterAsset = asset()): ConversationRecord {
        val base = record(asset)
        val turn = base.turns.single()
        return base.copy(
            turns = listOf(turn.copy(variants = listOf(
                MessageVariant("first", turn.selected.message, browserHead = head(entry, 1)),
                MessageVariant("second", turn.selected.message.copy(id = "m1"), browserHead = head(entry, 9)),
            ), selectedVariantIndex = 0)),
            runtimeState = head(entry, 1),
        )
    }

    /** 候选级的剧情派生状态：与 sticky 计时一起随候选回退。 */
    private fun head(entry: WorldBookEntryDefinition, sticky: Int) = ConversationRuntimeState(
        worldBookEntries = mapOf("notes:${entry.id}" to WorldBookEntryRuntimeState(stickyRemaining = sticky)),
    )

    @Test fun `switching candidates keeps the player's world book intent while runtime state follows the swipe`() {
        val asset = asset()
        val entry = asset.worldBooks.single().entries.single()
        val seeded = swipeable(entry, asset)
        val disabled = BrowserConversation.apply(seeded, actor, "worldbook.activation",
            args("""{"book":"notes","entry":"0","enabled":false}"""))

        assertEquals(false, disabled.worldBookState.activation.entries["notes"]?.get(entry.id))
        // 启停意图不再随消息楼层写入，最后楼层的候选头保持原样。
        assertEquals(seeded.turns.single().variants.map { it.browserHead }, disabled.turns.single().variants.map { it.browserHead })

        val switched = BrowserConversation.apply(disabled, actor, "messages.set",
            args("""{"messages":[{"message_id":0,"swipe_id":1}]}"""))

        assertEquals(1, switched.turns.single().selectedVariantIndex)
        // 玩家开关是会话级的：切候选不回退。
        assertFalse(switched.worldBookState.activation.isEntryEnabled("notes", entry.id, true))
        assertEquals(false, switched.worldBookState.activation.entries["notes"]?.get(entry.id))
        // 剧情派生的跨轮状态跟随候选回退。
        assertEquals(head(entry, 9), switched.runtimeState)
        assertNotEquals(head(entry, 9), disabled.runtimeState)

        val back = BrowserConversation.apply(switched, actor, "messages.set",
            args("""{"messages":[{"message_id":0,"swipe_id":0}]}"""))

        assertFalse(back.worldBookState.activation.isEntryEnabled("notes", entry.id, true))
        assertEquals(head(entry, 1), back.runtimeState)
    }

    @Test fun `a forced book injects its enabled entries without keys and skips probability`() {
        val original = record()
        val configured = BrowserConversation.apply(original, actor, "worldbook.entries.create", args("""{"book":"notes","entries":[
            {"name":"Guaranteed","keys":["ember"],"content":"PROBABILITY-NEEDLE","probability":0},
            {"name":"AlmostNever","keys":["ember"],"content":"COINFLIP-NEEDLE","probability":1},
            {"name":"OffByAuthor","keys":["ember"],"content":"DISABLED-NEEDLE","enabled":false}]}"""))
        // 强制前：关键字不命中的条目、概率失败的条目、被停用的条目都不进入。
        val before = compile(configured, "I wait by the ember.").plan.messages.joinToString("\n") { it.content }
        assertFalse(before.contains("The lighthouse is dark."))
        assertFalse(before.contains("PROBABILITY-NEEDLE"))
        assertFalse(before.contains("COINFLIP-NEEDLE"))
        assertFalse(before.contains("DISABLED-NEEDLE"))
        assertFalse(snapshotBook(configured).getValue("forced").jsonPrimitive.boolean)

        val forced = BrowserConversation.apply(configured, actor, "worldbook.books.force",
            args("""{"book":"notes","forced":true}"""))

        assertEquals(setOf("notes"), forced.worldBookState.forcedBooks)
        assertTrue(snapshotBook(forced).getValue("forced").jsonPrimitive.boolean)
        val after = compile(forced, "I wait by the ember.").plan.messages.joinToString("\n") { it.content }
        // 关键字不命中也被激活；概率被跳过。
        assertTrue(after.contains("The lighthouse is dark."))
        assertTrue(after.contains("PROBABILITY-NEEDLE"))
        assertTrue(after.contains("COINFLIP-NEEDLE"))
        // 强制不覆盖条目级停用。
        assertFalse(after.contains("DISABLED-NEEDLE"))

        // 书级停用与必定生效是同一个三态的两端。
        val disabled = BrowserConversation.apply(forced, actor, "worldbook.activation",
            args("""{"book":"notes","enabled":false}"""))
        assertTrue(disabled.worldBookState.forcedBooks.isEmpty())
        assertFalse(disabled.worldBookState.activation.isBookEnabled("notes"))
        assertFalse(compile(disabled, "I wait by the ember.").plan.messages.any { "PROBABILITY-NEEDLE" in it.content })

        val reforced = BrowserConversation.apply(disabled, actor, "worldbook.books.force",
            args("""{"book":"notes","forced":true}"""))
        assertTrue(reforced.worldBookState.activation.isBookEnabled("notes"))
        assertEquals(setOf("notes"), reforced.worldBookState.forcedBooks)
        assertTrue(compile(reforced, "I wait by the ember.").plan.messages.any { "PROBABILITY-NEEDLE" in it.content })
    }

    @Test fun `rewritten content keeps its original for restore and marks the entry as edited`() {
        val asset = asset()
        val entry = asset.worldBooks.single().entries.single()
        val original = record(asset)
        val rewritten = BrowserConversation.apply(original, actor, "worldbook.entries.update",
            args("""{"book":"notes","entries":[{"uid":0,"content":"REWRITTEN-ONE"}]}"""))

        assertEquals("REWRITTEN-ONE", rewritten.character.worldBooks.single().entries.single().content)
        // 留痕记录的是改写前的原文，而不是改写后的内容。
        assertEquals("The lighthouse is dark.", rewritten.worldBookState.editedContent[entry.id])
        assertTrue(snapshotEntry(rewritten).getValue("edited").jsonPrimitive.boolean)
        assertEquals("The lighthouse is dark.", original.character.worldBooks.single().entries.single().content)

        val rewrittenAgain = BrowserConversation.apply(rewritten, actor, "worldbook.entries.update",
            args("""{"book":"notes","entries":[{"uid":0,"content":"REWRITTEN-TWO"}]}"""))

        assertEquals("The lighthouse is dark.", rewrittenAgain.worldBookState.editedContent[entry.id])

        val configured = BrowserConversation.apply(rewrittenAgain, actor, "worldbook.activation",
            args("""{"book":"notes","entry":"0","enabled":false}"""))
        val armed = BrowserConversation.apply(configured, actor, "worldbook.books.force",
            args("""{"book":"notes","forced":true}"""))
        val restored = BrowserConversation.apply(armed, actor, "worldbook.entries.restore", args("""{"book":"notes"}"""))

        assertEquals("The lighthouse is dark.", restored.character.worldBooks.single().entries.single().content)
        assertFalse(restored.worldBookState.editedContent.containsKey(entry.id))
        assertFalse(snapshotEntry(restored).getValue("edited").jsonPrimitive.boolean)
        // 恢复正文不动启停与必定生效。
        assertEquals(false, restored.worldBookState.activation.entries["notes"]?.get(entry.id))
        assertEquals(setOf("notes"), restored.worldBookState.forcedBooks)

        // 手工改回原文同样让留痕消失。
        val revertedByHand = BrowserConversation.apply(rewrittenAgain, actor, "worldbook.entries.update",
            args("""{"book":"notes","entries":[{"uid":0,"content":"The lighthouse is dark."}]}"""))

        assertEquals("The lighthouse is dark.", revertedByHand.character.worldBooks.single().entries.single().content)
        assertFalse(revertedByHand.worldBookState.editedContent.containsKey(entry.id))
        assertFalse(snapshotEntry(revertedByHand).getValue("edited").jsonPrimitive.boolean)
    }
}
