package io.github.zvensmoluya.tavernplayer.content

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Assemble model-authored native behavior. Source discovery never preselects supported JS patterns. */
class NativeAdaptationCompiler {
    private val json = Json { encodeDefaults = true }
    fun prepare(character: CharacterAsset, availableAssetIds: Set<String>): String =
        NativeProgramExtractor().extract(character, availableAssetIds).request.toString()

    fun complete(character: CharacterAsset, response: String, availableAssetIds: Set<String>): NativeCompilationResult {
        fun reject(code: String, message: String) = NativeCompilationResult.Rejected(
            listOf(NativeAdaptationValidationIssue("response", code, message)))
        if (response.length > MAX_OUTPUT_CHARS) return reject("COMPILER_OUTPUT_TOO_LARGE", "模型适配结果超过大小限制")
        val draft = try {
            val text = response.trim().let {
                if (it.startsWith("```json\n") && it.endsWith("\n```")) it.removePrefix("```json\n").removeSuffix("\n```") else it
            }
            json.decodeFromString<NativeCompilationDraft>(text)
        } catch (_: IllegalArgumentException) {
            return reject("COMPILER_INVALID_JSON", "模型未返回符合约定的完整适配 JSON")
        }
        return try {
            val view = NativeProgramExtractor().extract(character, availableAssetIds)
            require(draft.summary.isNotBlank() && draft.summary.length <= 1024) { "需要简短的适配摘要" }
            fun source(id: String) = view.sources.singleOrNull { it.id == id } ?: error("未知程序来源：$id")
            draft.script?.modules?.forEach { module ->
                module.sourceIds.forEach { id -> require(source(id).active) { "生成模块不能激活停用来源" } }
            }
            val forms = draft.forms.map { form ->
                val src = source(form.sourceId)
                require(src.active && src.regexId != null) { "本轮表单必须来自启用的显示正则" }
                val rule = character.regexScripts.single { it.id == src.regexId }
                require(rule.markdownOnly && !rule.promptOnly) { "不能用原生表单接管提示词或持久化正则" }
                require(form.marker.isNotBlank()) { "表单必须保留原触发标记" }
                val template = assembleTemplate(src.content, form.draft, form.fields.map { it.id }.toSet())
                NativeFormView(form.id, form.title, form.marker, form.description, form.fields, template,
                    submitLabel = form.submitLabel, replacedDisplayRegexIds = listOf(src.regexId))
            }
            require(draft.mvu == null || (draft.state.isEmpty() && draft.assistantStateAdapters.isEmpty() && draft.playerChoices.isEmpty())) {
                "MVU 卡不能复制业务状态或创建旧写入器；自定义操作必须使用声明的 JS 宿主"
            }
            var adaptation = NativeAdaptation(sourceSha256 = character.sourceSha256, state = draft.state,
                assistantStateAdapters = draft.assistantStateAdapters, status = draft.status, collections = draft.collections,
                forms = forms, messagePanels = draft.messagePanels, stateBindings = draft.stateBindings,
                playerChoices = draft.playerChoices,
                script = draft.script,
                ejsTemplates = draft.ejsSourceIds.map { id ->
                    val src = source(id)
                    require(src.active && src.bookId != null && src.entryId != null && "<%" in src.content) { "EJS 必须引用启用的世界书模板" }
                    NativeWorldBookReference(src.bookId, src.entryId, NativeWorldBookTextSelectionValidator.sha256(src.content))
                },
                mvu = draft.mvu?.let {
                    val src = source(it.schemaSourceId)
                    require(src.active && src.kind == "SCRIPT") { "MVU Schema 必须引用启用的原卡脚本" }
                    NativeMvuProgram(src.content)
                })
            require(draft.assessments.size <= 512 && draft.assessments.map { it.sourceId }.distinct().size == draft.assessments.size) { "来源评估重复或过多" }
            val tree = json.encodeToJsonElement(draft)
            val targetRoots = setOf("state", "stateBindings", "assistantStateAdapters", "status", "collections", "forms",
                "messagePanels", "playerChoices", "mvu", "ejsSourceIds", "script")
            draft.assessments.forEach {
                source(it.sourceId)
                require(it.reason.isNotBlank() && it.reason.length <= 1024) { "评估必须简短且说明具体影响" }
                require(it.targets.size <= 32 && it.targets.all { target -> target.removePrefix("/").substringBefore("/") in targetRoots && resolve(tree, target) != null }) { "评估指向不存在的适配结果" }
            }
            val evidence = buildList {
                view.sources.filter { it.active && it.kind !in setOf("STATIC_WORLD_BOOK", "EXTENSION_METADATA") || draft.assessments.any { assessment -> assessment.sourceId == it.id } }.forEach { src ->
                    val assessment = draft.assessments.singleOrNull { it.sourceId == src.id }
                    val claim = assessment?.disposition ?: NativeCompilationDisposition.UNCERTAIN
                    val disposition = if (claim == NativeCompilationDisposition.RESTORED && assessment?.targets.isNullOrEmpty())
                        NativeCompilationDisposition.UNCERTAIN else claim
                    add(NativeCompilationEvidence(src.path, src.kind,
                        if (!src.active) NativeCompilationDisposition.UNCERTAIN else disposition,
                        assessment?.reason ?: "此来源未评估；保留原件，不声明行为已经迁移"))
                }
                view.warnings.forEach { add(NativeCompilationEvidence("/", "输入覆盖边界", NativeCompilationDisposition.UNCERTAIN, it)) }
                if (adaptation.collections.any { collection -> adaptation.stateBindings.none { it.key == collection.stateKey && it.source == NativeStateSource.MVU } }) add(NativeCompilationEvidence("/", "动态集合",
                    NativeCompilationDisposition.UNSUPPORTED, "集合保留初始快照；当前适配器不支持动态物品增删或集合字段更新"))
            }
            adaptation = adaptation.copy(report = NativeCompatibilityReport(
                // Installation/type checks are not a proof of source program equivalence.
                status = NativeCompatibilityStatus.PARTIAL, summary = draft.summary,
                restoredBehaviors = evidence.filter { it.disposition == NativeCompilationDisposition.RESTORED }.map { it.impact }.distinct(),
                degradedPresentation = evidence.filter { it.disposition == NativeCompilationDisposition.PRESENTATION_ONLY }.map { it.impact }.distinct(),
                unsupportedBehaviors = evidence.filter { it.disposition == NativeCompilationDisposition.UNSUPPORTED }.map { it.impact }.distinct(),
                warnings = listOf("已验证引用、结构和安装约束；程序含义由模型判断，未证明整卡行为等价。远程依赖未执行或核对精确版本。") +
                    evidence.filter { it.disposition == NativeCompilationDisposition.UNCERTAIN && it.impact != "此来源未评估；保留原件，不声明行为已经迁移" }.map { it.impact }.distinct() +
                    listOfNotNull(evidence.count { it.impact == "此来源未评估；保留原件，不声明行为已经迁移" }.takeIf { it > 0 }
                        ?.let { "有 $it 个启用来源未评估；不能据此判断其行为已迁移。" }),
            ), compilationEvidence = evidence)
            val validation = NativeAdaptationValidator().validate(adaptation, expectedSourceSha256 = character.sourceSha256,
                availableAssetIds = availableAssetIds, worldBooks = character.worldBooks,
                openingCount = 1 + character.alternateFirstMessages.size, regexScripts = character.regexScripts)
            if (validation.valid) NativeCompilationResult.Ready(adaptation, evidence) else NativeCompilationResult.Rejected(validation.issues)
        } catch (error: IllegalArgumentException) {
            reject("COMPILER_INVALID_DECISION", error.message ?: "适配结果未通过本地组装")
        } catch (error: IllegalStateException) {
            reject("COMPILER_INVALID_DECISION", error.message ?: "适配引用不一致")
        }
    }

    /** Exact source slicing and interpolation replacement, not a gameplay recognizer or JS evaluator. */
    private fun assembleTemplate(source: String, template: NativeCompilationTemplate, fieldIds: Set<String>): String {
        val tick = 96.toChar()
        require(template.after.isNotBlank() && template.before.isNotBlank() && template.after.last() == tick && template.before.first() == tick) {
            "草稿必须引用原 JS 模板字符串，锚点包含两侧反引号"
        }
        val anchor = source.indexOf(template.after)
        require(anchor >= 0 && source.indexOf(template.after, anchor + 1) < 0) { "草稿起始锚点必须存在且唯一" }
        val start = anchor + template.after.length
        val end = source.indexOf(template.before, start)
        require(end > start) { "草稿结束锚点不存在" }
        var literal = source.substring(start, end)
        require(template.bindings.values.toSet() == fieldIds) { "草稿必须覆盖全部表单字段" }
        template.bindings.forEach { (expression, field) ->
            require(expression.startsWith("$" + "{") && expression.endsWith("}") && expression in literal) { "插值引用必须精确对应原模板" }
            literal = literal.replace(expression, "{{form.$field}}")
        }
        require("$" + "{" !in literal && tick !in literal) { "模板仍含未绑定插值或跨越字符串边界" }
        return decodeLiteral(literal)
    }

    private fun decodeLiteral(body: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i++]
            if (c != '\\') { out.append(c); continue }
            require(i < body.length) { "未闭合字符串转义" }
            when (val escape = body[i++]) {
                'n' -> out.append('\n'); 'r' -> out.append('\r'); 't' -> out.append('\t')
                '\\', '\'', '"', '$' -> out.append(escape)
                '\n' -> Unit
                '\r' -> if (body.getOrNull(i) == '\n') i++
                'u' -> { require(i + 4 <= body.length); out.append(body.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                'x' -> { require(i + 2 <= body.length); out.append(body.substring(i, i + 2).toInt(16).toChar()); i += 2 }
                else -> { require(escape.code == 96) { "未支持的原文字符串转义" }; out.append(escape) }
            }
        }
        return out.toString()
    }

    private fun resolve(root: JsonElement, pointer: String): JsonElement? {
        if (!pointer.startsWith('/') || pointer == "/") return null
        var current = root
        for (token in pointer.drop(1).split('/')) {
            val key = token.replace("~1", "/").replace("~0", "~")
            current = when (val node = current) {
                is JsonObject -> node[key]
                is JsonArray -> key.toIntOrNull()?.let { node.getOrNull(it) }
                else -> null
            } ?: return null
        }
        return current.takeUnless { it is JsonNull }
    }

    companion object {
        const val MAX_INPUT_CHARS = 256_000
        const val MAX_OUTPUT_CHARS = 131_072
    }
}
