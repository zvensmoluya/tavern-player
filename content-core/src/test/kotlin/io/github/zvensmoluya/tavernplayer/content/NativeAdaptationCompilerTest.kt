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
        assertTrue(all.contains("promptOnly"))
        assertTrue(all.contains("原始"))
    }

    @Test fun ejsRoundTripPreservesCodeAndEveryTextSpan() {
        val view = NativeProgramExtractor().extract(card(), emptySet())
        val source = view.sources.single { it.kind == "EJS_TEMPLATE" }
        val wire = view.request["sources"]!!.jsonArray.map { it.jsonObject }.single { it["id"] == JsonPrimitive(source.id) }["content"]!!.jsonPrimitive.content
        assertTrue(wire.contains("const n = getvar('stat_data.score'); if (n > 0)"))
        assertFalse(wire.contains("正值原文"))
        val reconstructed = Regex("\\[\\[LOCAL_TEXT:([^]]+)]]").replace(wire) { match ->
            val ref = view.texts.getValue(match.groupValues[1])
            source.content.substring(ref.range.start, ref.range.endExclusive)
        }
        assertEquals(branch, reconstructed)
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

    private fun branchDraft(): NativeCompilationDraft {
        val view = NativeProgramExtractor().extract(card(), emptySet())
        val src = view.sources.single { it.kind == "EJS_TEMPLATE" }
        val refs = view.texts.filterValues { it.sourceId == src.id }.keys.toList()
        return NativeCompilationDraft("数值分段",
            state = listOf(
                ConversationStateDefinition("score", type = ConversationStateValueType.NUMBER, initialValue = JsonPrimitive(0), numberRange = NativeNumberRange(0.0, 100.0)),
                ConversationStateDefinition("stage", type = ConversationStateValueType.STRING, initialValue = JsonPrimitive("zero"), allowedStrings = listOf("zero", "positive")),
            ),
            progressions = listOf(NativeCompilationProgression("score", "stage", listOf(
                NativeCompilationLevel(0.0, "zero"), NativeCompilationLevel(0.0, "positive", exclusive = true)))),
            worldBookTextSelections = listOf(NativeCompilationTextSelection(src.id, "stage",
                listOf(NativeCompilationTextCase("positive", refs[1]), NativeCompilationTextCase("zero", refs[2])), refs.first(), refs.last())),
        )
    }

    @Test fun modelSemanticMappingBeyondOldGrammarPreservesFractionalBoundary() {
        val result = compiler.complete(card(), Json.encodeToString(branchDraft()), emptySet()) as NativeCompilationResult.Ready
        val selection = result.adaptation.worldBookTextSelections.single()
        assertEquals("共同 😀 说明\n", branch.substring(selection.sourcePrefix!!.start, selection.sourcePrefix.endExclusive))
        assertEquals("\n共同规则", branch.substring(selection.sourceSuffix!!.start, selection.sourceSuffix.endExclusive))
        assertEquals(NativeWorldBookTextSelectionValidator.sha256(branch), selection.sourceContentSha256)
        val levels = result.adaptation.progressions.single().levels
        assertEquals("zero", levels.last { 0.0 >= it.minValue }.label)
        assertEquals("positive", levels.last { 0.5 >= it.minValue }.label)
        assertEquals(Double.MIN_VALUE, levels.last().minValue, 0.0)
    }

    @Test fun droppedCommonRulesAndCrossSourceRefsReject() {
        val draft = branchDraft()
        val selection = draft.worldBookTextSelections.single()
        listOf(selection.copy(suffixRef = null), selection.copy(prefixRef = "unknown.text0")).forEach {
            assertTrue(compiler.complete(card(), Json.encodeToString(draft.copy(worldBookTextSelections = listOf(it))), emptySet()) is NativeCompilationResult.Rejected)
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

    @Test fun budgetFailsWithoutTruncatingSource() {
        assertThrows(IllegalArgumentException::class.java) { compiler.prepare(card("x".repeat(300_000)), emptySet()) }
        assertTrue(NativeCompilationInstructions.text.contains("NativeCompilationForm"))
        assertTrue(NativeCompilationInstructions.text.contains("UPDATE_VARIABLE_JSON_PATCH_V1"))
    }
}
