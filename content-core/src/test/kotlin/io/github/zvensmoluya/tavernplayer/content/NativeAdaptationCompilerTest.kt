package io.github.zvensmoluya.tavernplayer.content

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NativeAdaptationCompilerTest {
    private val compiler = NativeAdaptationCompiler()
    private val tick = 96.toChar()
    private val interpolation = "$" + "{document.querySelector('#who').value || '访客'}"
    private val html = "<input id='who'><button>继续</button><script>function run(){const draft = " +
        tick + "原始 😀 开头\\n称呼：" + interpolation + "\\n原始结尾" + tick + "; document.querySelector('#send_textarea').value = draft;}</script>"
    private val branch = "共同 😀 说明\n<% const n = getvar('stat_data.score'); if (n > 0) { %>正值原文" +
        "<% } else { %>零值原文<% } %>\n共同规则"
    private fun card(code: String = "const limit = 2; // important comment\nfor (const x of values) { process(x / limit, 'literal'); }"): CharacterAsset {
        val entries = listOf(
            WorldBookEntryDefinition("init", comment = "[initvar]", enabled = false, content = "score: 0"),
            WorldBookEntryDefinition("branch", sourceId = "999", content = branch),
            WorldBookEntryDefinition("rules", comment = "变量更新规则", content = "完成任务时由模型判断分数变化。"),
            WorldBookEntryDefinition("story", comment = "背景", content = "PRIVATE_BACKGROUND".repeat(2000)),
        )
        return CharacterAsset(id = "a".repeat(64), name = "Sample",
            worldBooks = listOf(WorldBookDefinition("book", entries = entries)),
            regexScripts = listOf(RegexDefinition("r", "Input", "<START/>", html, markdownOnly = true)),
            rawCard = buildJsonObject { put("data", buildJsonObject {
                put("description", "PRIVATE_DESCRIPTION".repeat(1000)); put("first_mes", "PRIVATE_OPENING")
                put("extensions", buildJsonObject {
                    put("tavern_helper", buildJsonObject { put("scripts", buildJsonArray {
                        add(buildJsonObject { put("content", code); put("enabled", true) })
                    }) })
                    put("unknown", buildJsonObject { put("api_key", "PRIVATE_UNKNOWN_SECRET") })
                })
            }) },
        )
    }

    @Test fun completeSourceWithoutGameplayCandidates() {
        val code = "import 'https://example.test/module.js';\n// semantic comment\nconst t = " + tick + "visible $" + "{x / 2}" + tick + ";\nwhile (ready()) { doSomething('meaningful string'); }"
        val source = card(code).copy(nativeAdaptation = NativeAdaptation(sourceSha256 = "a".repeat(64), report = NativeCompatibilityReport(summary = "PRIVATE_MANUAL")))
        val request = Json.parseToJsonElement(compiler.prepare(source, emptySet())).jsonObject
        val scripts = request["sources"]!!.jsonArray.map { it.jsonObject }.filter { it["kind"] == JsonPrimitive("SCRIPT") }
        assertEquals(code, scripts.single()["content"]!!.jsonPrimitive.content)
        val all = request.toString()
        assertFalse(all.contains("PRIVATE_"))
        assertFalse(all.contains("NUMERIC_BRANCH"))
        assertFalse(all.contains("DRAFT_FORM"))
        assertTrue(all.contains("完成任务时由模型判断"))
        assertTrue(all.contains("markdownOnly"))
        assertTrue(all.contains("原始"))
    }

    @Test fun ejsProjectionKeepsEveryCodeSpanAndRetainsCompleteLocalSource() {
        val view = NativeProgramExtractor().extract(card(), emptySet())
        val source = view.sources.single { it.kind == "EJS_TEMPLATE" }
        val wire = view.request["sources"]!!.jsonArray.map { it.jsonObject }.single { it["id"] == JsonPrimitive(source.id) }["content"]!!.jsonPrimitive.content
        assertTrue(wire.contains("const n = getvar('stat_data.score'); if (n > 0)"))
        assertFalse(wire.contains("正值原文"))
        val tags = Regex("<%[\\s\\S]*?%>")
        assertEquals(tags.findAll(branch).map { it.value }.toList(), tags.findAll(wire).map { it.value }.toList())
        assertEquals(branch, source.content)
        assertFalse(view.request.containsKey("textReferences"))
    }

    @Test fun importerRawDoesNotUploadASecondProgramCopy() {
        val source = card().let { it.copy(regexScripts = it.regexScripts.map { rule -> rule.copy(raw = buildJsonObject {
            put("replaceString", html); put("futureOption", "keep")
        }) }) }
        val view = NativeProgramExtractor().extract(source, emptySet())
        val entry = view.request["sources"]!!.jsonArray.map { it.jsonObject }.single { it["id"] == JsonPrimitive("regex0") }
        assertEquals(html, entry["content"]!!.jsonPrimitive.content)
        assertFalse(entry["metadata"].toString().contains("replaceString"))
        assertTrue(entry["metadata"].toString().contains("futureOption"))
    }

    private fun formDraft() = NativeCompilationDraft("原生输入", forms = listOf(NativeCompilationForm(
        "input", "称呼", "regex0", "<START/>",
        fields = listOf(NativeFormField("who", NativeFormFieldType.TEXT, "称呼", emptyText = "访客")),
        draft = NativeCompilationTemplate("const draft = " + tick, tick + ";", mapOf(interpolation to "who")),
    )))

    @Test fun nonJqueryFormAndOriginalLiteralAssembly() {
        val result = compiler.complete(card(), Json.encodeToString(formDraft()), emptySet()) as NativeCompilationResult.Ready
        val form = result.adaptation.forms.single()
        assertEquals("原始 😀 开头\n称呼：{{form.who}}\n原始结尾", form.draftTemplate)
        assertEquals(listOf("r"), form.replacedDisplayRegexIds)
        assertNull(form.setup)
        assertEquals("访客", form.fields.single().emptyText)
    }

    @Test fun invalidAnchorsAndUnboundInterpolationsReject() {
        val draft = formDraft()
        fun attempt(template: NativeCompilationTemplate, source: CharacterAsset = card()) =
            compiler.complete(source, Json.encodeToString(draft.copy(forms = listOf(draft.forms.single().copy(draft = template)))), emptySet())
        val template = draft.forms.single().draft
        assertTrue(attempt(template.copy(after = "missing" + tick)) is NativeCompilationResult.Rejected)
        assertTrue(attempt(template.copy(bindings = mapOf(("$" + "{other}") to "who"))) is NativeCompilationResult.Rejected)
        val duplicate = card().copy(regexScripts = card().regexScripts.map { it.copy(replaceString = html + html) })
        assertTrue(attempt(template, duplicate) is NativeCompilationResult.Rejected)
    }

    @Test fun obsoleteBranchCompilationIsRejected() {
        for (field in listOf("progressions", "worldBookTextSelections")) {
            val response = """{"summary":"obsolete", "$field":[]}"""
            assertTrue(compiler.complete(card(), response, emptySet()) is NativeCompilationResult.Rejected)
            assertFalse(NativeCompilationInstructions.text.contains("$field?:"))
        }
    }

    @Test fun disabledCodeCannotSupplyActiveForm() {
        val source = card().copy(regexScripts = card().regexScripts.map { it.copy(disabled = true) })
        val view = NativeProgramExtractor().extract(source, emptySet())
        assertEquals(html, view.sources.single { it.regexId != null }.content)
        assertFalse(view.sources.single { it.regexId != null }.active)
        assertTrue(compiler.complete(source, Json.encodeToString(formDraft()), emptySet()) is NativeCompilationResult.Rejected)
    }

    @Test fun embeddedProgramLocationIsNotRejected() {
        val original = card()
        val data = original.rawCard["data"]!!.jsonObject
        val mixed = "Context <script>if (check()) unknownApi('x');</script>"
        val source = original.copy(rawCard = buildJsonObject { put("data", JsonObject(data + ("description" to JsonPrimitive(mixed)))) })
        assertTrue(NativeProgramExtractor().extract(source, emptySet()).sources.any { it.content == mixed })
    }

    @Test fun identityPlaceholdersDoNotUploadWholeNarrative() {
        val original = card()
        val data = original.rawCard["data"]!!.jsonObject
        val text = "PRIVATE_STORY {{user}} meets {{char}}"
        fun withDescription(value: String) = original.copy(rawCard = buildJsonObject {
            put("data", JsonObject(data + ("description" to JsonPrimitive(value))))
        })
        assertFalse(compiler.prepare(withDescription(text), emptySet()).contains("PRIVATE_STORY"))
        val program = "{{format_message_variable::stat_data}}"
        assertTrue(NativeProgramExtractor().extract(withDescription(program), emptySet()).sources.any { it.content == program })
    }

    @Test fun invalidTargetsAndUnknownRuntimePowersReject() {
        val draft = NativeCompilationDraft("错误", assessments = listOf(NativeCompilationAssessment("script0", NativeCompilationDisposition.RESTORED, "映射", listOf("/forms/9"))))
        assertTrue(compiler.complete(card(), Json.encodeToString(draft), emptySet()) is NativeCompilationResult.Rejected)
        assertTrue(compiler.complete(card(), """{"summary":"no","executeJs":"evil()"}""", emptySet()) is NativeCompilationResult.Rejected)
        assertTrue(compiler.complete(card(), """{"summary":"no","memories":[]}""", emptySet()) is NativeCompilationResult.Rejected)
    }

    @Test fun unbackedClaimsAndMissingAssessmentsRemainUncertain() {
        val draft = NativeCompilationDraft("部分", assessments = listOf(NativeCompilationAssessment("script0", NativeCompilationDisposition.RESTORED, "模型声明")))
        val ready = compiler.complete(card(), Json.encodeToString(draft), emptySet()) as NativeCompilationResult.Ready
        assertEquals(NativeCompatibilityStatus.PARTIAL, ready.adaptation.report.status)
        assertTrue(ready.adaptation.report.restoredBehaviors.isEmpty())
        assertTrue(ready.adaptation.report.warnings.contains("模型声明"))
        assertTrue(ready.adaptation.report.warnings.any { "未评估" in it })
    }

    @Test fun metadataOnlyWorldBookIsAValidAssessmentSource() {
        val draft = NativeCompilationDraft("保留背景", assessments = listOf(NativeCompilationAssessment(
            "book0.entry3", NativeCompilationDisposition.UNCERTAIN, "仅有元数据，未审计正文")))
        assertTrue(compiler.complete(card(), Json.encodeToString(draft), emptySet()) is NativeCompilationResult.Ready)
    }

    @Test fun mvuSelectsExactEnabledSourceAndRejectsCompetingWriters() {
        val source = card()
        val script = NativeProgramExtractor().extract(source, emptySet()).sources.single { it.id == "script0" }
        val draft = NativeCompilationDraft("保留变量程序", mvu = NativeCompilationMvu(script.id))
        val ready = compiler.complete(source, Json.encodeToString(draft), emptySet()) as NativeCompilationResult.Ready
        assertEquals(script.content, ready.adaptation.mvu!!.schemaScript)
        assertTrue(compiler.complete(source, Json.encodeToString(draft.copy(mvu = NativeCompilationMvu("regex0"))), emptySet()) is NativeCompilationResult.Rejected)
        assertTrue(compiler.complete(source, Json.encodeToString(draft.copy(mvu = NativeCompilationMvu("missing"))), emptySet()) is NativeCompilationResult.Rejected)
        val conflict = ready.adaptation.copy(assistantStateAdapters = listOf(AssistantStateAdapterDefinition(
            LegacyStateDialect.UPDATE_VARIABLE_JSON_PATCH_V1, listOf(AssistantStateMapping("/days", "days")))))
        assertTrue(NativeAdaptationValidator().validate(conflict).issues.any { it.code == "CONFLICTING_MVU_WRITER" })
    }

    @Test fun ejsSelectsFullOriginalSourceAndRejectsConflictingOrChangedReferences() {
        val original = card()
        val src = NativeProgramExtractor().extract(original, emptySet()).sources.single { it.kind == "EJS_TEMPLATE" }
        val draft = NativeCompilationDraft("执行原模板", ejsSourceIds = listOf(src.id))
        val ready = compiler.complete(original, Json.encodeToString(draft), emptySet()) as NativeCompilationResult.Ready
        val ref = ready.adaptation.ejsTemplates.single()
        assertEquals(NativeWorldBookTextSelectionValidator.sha256(branch), ref.sourceContentSha256)
        assertEquals("branch", ref.entryId)
        assertEquals(ready.adaptation, Json.decodeFromString<NativeAdaptation>(Json.encodeToString(ready.adaptation)))
        assertTrue(compiler.complete(original, Json.encodeToString(draft.copy(ejsSourceIds = listOf("script0"))), emptySet()) is NativeCompilationResult.Rejected)
        assertTrue(compiler.complete(original, Json.encodeToString(draft.copy(ejsSourceIds = listOf(src.id, src.id))), emptySet()) is NativeCompilationResult.Rejected)
        val conflict = ready.adaptation.copy(worldBookTextSelections = listOf(NativeWorldBookTextSelection(
            ref.bookId, ref.entryId, "mode", ref.sourceContentSha256, emptyList())))
        assertTrue(NativeEjsValidator.validate(conflict, original.worldBooks).isNotEmpty())
        val changed = original.worldBooks.map { b -> b.copy(entries = b.entries.map { it.copy(content = it.content + "changed") }) }
        assertTrue(NativeEjsValidator.validate(ready.adaptation, changed).isNotEmpty())
    }

    @Test fun budgetFailsWithoutTruncatingSource() {
        assertThrows(IllegalArgumentException::class.java) { compiler.prepare(card("x".repeat(300_000)), emptySet()) }
        assertTrue(NativeCompilationInstructions.text.contains("NativeCompilationForm"))
        assertTrue(NativeCompilationInstructions.text.contains("UPDATE_VARIABLE_JSON_PATCH_V1"))
    }
}
