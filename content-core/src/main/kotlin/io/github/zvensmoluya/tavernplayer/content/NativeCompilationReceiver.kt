package io.github.zvensmoluya.tavernplayer.content

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.json.*

internal class NativeCompilationInputFailure(val issues: List<NativeAdaptationValidationIssue>) : IllegalArgumentException()

internal data class NativeReceivedDraft(val draft: NativeCompilationDraft, val notes: List<NativeCompilationEvidence>)

/** Normalize the transport envelope, then validate the executable schema without guessing repairs. */
internal object NativeCompilationReceiver {
    private val json = Json

    fun receive(response: String, selection: NativeCompilationSelection? = null): NativeReceivedDraft {
        val notes = mutableListOf<NativeCompilationEvidence>()
        fun note(path: String, message: String) {
            notes += NativeCompilationEvidence(path, "输入规范化", NativeCompilationDisposition.UNCERTAIN, message)
        }
        var text = response.trim().removePrefix("\uFEFF").trim()
        if (text != response.trim()) note("response", "已移除响应开头的文本标记")
        if (text.startsWith("```")) {
            val fence = Regex("\\A```(?:json)?[ \\t]*\\r?\\n([\\s\\S]*?)\\r?\\n```[ \\t]*\\z", RegexOption.IGNORE_CASE).matchEntire(text)
                ?: fail("response", "COMPILER_RESPONSE_WRAPPER", "代码围栏不完整或包含多个结果")
            text = fence.groupValues[1].trim()
            note("response", "已移除 JSON 代码围栏")
        } else if (!text.startsWith('[')) {
            // Never search inside broken JSON, arrays or code fences for a plausible nested draft.
            val start = text.indexOf('{')
            if (start < 0 || text.substring(0, start).contains("```")) fail("response", "COMPILER_INVALID_JSON", "响应中没有单一完整 JSON 对象")
            val end = objectEnd(text, start)
            val suffix = text.substring(end)
            if (text.substring(0, start).any { it in "{}[]" } || suffix.any { it in "{}[]`" })
                fail("response", "COMPILER_AMBIGUOUS_RESPONSE", "响应含多个结果或有歧义的包装，未选择其中一个")
            if (start != 0 || suffix.isNotBlank()) note("response", "已提取说明文字中的单一 JSON 对象；未改动对象内容")
            text = text.substring(start, end)
        }
        val parsed = try { json.parseToJsonElement(text) }
        catch (_: IllegalArgumentException) { fail("response", "COMPILER_INVALID_JSON", "JSON 语法不完整或无效；未自动补全") }
        val tree = (parsed as? JsonObject)?.toMutableMap()
            ?: fail("response", "COMPILER_EXPECTED_OBJECT", "适配结果顶层必须为对象")
        tree.remove("version")?.let { version ->
            if (version !is JsonPrimitive || !version.isString || version.content != NativeCompilationInstructions.VERSION)
                fail("/version", "COMPILER_VERSION_MISMATCH", "返回的编译版本与当前契约不一致")
            note("/version", "已识别并移除模型回显的编译版本元数据；未修改执行内容")
        }
        val summary = tree["summary"]
        if (summary == null || summary == JsonNull || summary is JsonPrimitive && summary.isString && summary.content.isBlank()) {
            tree["summary"] = JsonPrimitive("原生适配结果；行为完整性待验证")
            note("/summary", "摘要缺失或为空，已使用本地说明")
        } else if (summary is JsonPrimitive && summary.isString) {
            val value = summary.content.trim().take(1024)
            if (value != summary.content) note("/summary", "已整理摘要空白或过长说明；未改动执行内容")
            tree["summary"] = JsonPrimitive(value)
        }
        val normalized = JsonObject(tree)
        selection?.let {
            val forbidden = if (it.runtime == NativeStateSource.MVU) setOf("state", "assistantStateAdapters", "playerChoices") else setOf("mvu")
            forbidden.firstOrNull { key -> key in tree }?.let { key ->
                fail("/$key", "COMPILER_BRANCH_FIELD", "所选状态分支未声明此字段；空值或空数组也不能跨分支")
            }
        }
        val issues = mutableListOf<NativeAdaptationValidationIssue>()
        validate(normalized, NativeCompilationDraft.serializer().descriptor, "", issues, 0)
        if (issues.isNotEmpty()) throw NativeCompilationInputFailure(issues)
        val draft = try { json.decodeFromJsonElement<NativeCompilationDraft>(normalized) }
        catch (_: IllegalArgumentException) { fail("response", "COMPILER_SCHEMA_DECODE", "字段值无法按适配契约解码；未自动转换执行数据") }
        selection?.let {
            if (it.runtime == NativeStateSource.MVU && (draft.mvu == null || draft.mvu.schemaSourceId != it.schemaSourceId))
                fail("/mvu", "COMPILER_BRANCH_SOURCE", "编译结果必须保留本轮选择的 MVU Schema")
            if (draft.stateBindings.any { binding -> binding.source != it.runtime })
                fail("/stateBindings", "COMPILER_BRANCH_SOURCE", "只读绑定必须使用本轮选择的状态来源")
            if (it.runtime == NativeStateSource.PLAYER && NativeScriptCapability.MVU_REPLACE in draft.script?.capabilities.orEmpty())
                fail("/script/capabilities", "COMPILER_BRANCH_CAPABILITY", "普通卡契约未提供 MVU 写入接口")
        }
        return NativeReceivedDraft(draft, notes)
    }

    private fun objectEnd(text: String, start: Int): Int {
        var depth = 0
        var quoted = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> if (--depth == 0) return i + 1
            }
        }
        fail("response", "COMPILER_INVALID_JSON", "JSON 对象未完整结束；未自动补全")
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun validate(value: JsonElement, descriptor: SerialDescriptor, path: String,
                         issues: MutableList<NativeAdaptationValidationIssue>, depth: Int) {
        if (issues.size >= 32) return
        fun issue(code: String, message: String) { issues += NativeAdaptationValidationIssue(path.ifEmpty { "response" }, code, message) }
        if (depth > 64) { issue("COMPILER_SCHEMA_DEPTH", "字段嵌套过深"); return }
        if (value == JsonNull) {
            if (!descriptor.isNullable && descriptor.serialName != "kotlinx.serialization.json.JsonElement")
                issue("COMPILER_FIELD_TYPE", "字段不接受 null")
            return
        }
        if (descriptor.serialName.startsWith("kotlinx.serialization.json.")) {
            if (descriptor.serialName.removeSuffix("?") == "kotlinx.serialization.json.JsonObject" && value !is JsonObject)
                issue("COMPILER_FIELD_TYPE", "字段必须为 JSON 对象")
            return
        }
        fun nested(v: JsonElement, d: SerialDescriptor, key: String) = validate(v, d,
            "$path/${key.replace("~", "~0").replace("/", "~1")}", issues, depth + 1)
        when (descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> {
                if (value !is JsonObject) { issue("COMPILER_FIELD_TYPE", "字段必须为对象"); return }
                value.forEach { (key, v) ->
                    val index = descriptor.getElementIndex(key)
                    if (index < 0) {
                        if (issues.size < 32) issues += NativeAdaptationValidationIssue("$path/${key.replace("~", "~0").replace("/", "~1")}",
                            "COMPILER_UNKNOWN_FIELD", "契约未声明此字段；未静默丢弃")
                    } else nested(v, descriptor.getElementDescriptor(index), key)
                }
                for (i in 0 until descriptor.elementsCount) if (issues.size < 32 && !descriptor.isElementOptional(i) && descriptor.getElementName(i) !in value)
                    issues += NativeAdaptationValidationIssue("$path/${descriptor.getElementName(i)}", "COMPILER_MISSING_FIELD", "缺少必需字段")
            }
            StructureKind.LIST -> if (value is JsonArray) value.forEachIndexed { i, v -> nested(v, descriptor.getElementDescriptor(0), i.toString()) }
                else issue("COMPILER_FIELD_TYPE", "字段必须为数组")
            StructureKind.MAP -> if (value is JsonObject) value.forEach { (k, v) -> nested(v, descriptor.getElementDescriptor(1), k) }
                else issue("COMPILER_FIELD_TYPE", "字段必须为映射对象")
            SerialKind.ENUM -> if (value !is JsonPrimitive || !value.isString || descriptor.getElementIndex(value.content) < 0)
                issue("COMPILER_ENUM_VALUE", "枚举值无效；允许值：" + (0 until descriptor.elementsCount).joinToString(", ") { descriptor.getElementName(it) })
            PrimitiveKind.STRING, PrimitiveKind.CHAR -> if (value !is JsonPrimitive || !value.isString) issue("COMPILER_FIELD_TYPE", "字段必须为字符串")
            PrimitiveKind.BOOLEAN -> if (value !is JsonPrimitive || value.isString || value.booleanOrNull == null) issue("COMPILER_FIELD_TYPE", "字段必须为布尔值")
            PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.SHORT, PrimitiveKind.BYTE -> {
                val n = (value as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
                val valid = n != null && when (descriptor.kind) {
                    PrimitiveKind.INT -> n in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
                    PrimitiveKind.SHORT -> n in Short.MIN_VALUE.toLong()..Short.MAX_VALUE.toLong()
                    PrimitiveKind.BYTE -> n in Byte.MIN_VALUE.toLong()..Byte.MAX_VALUE.toLong()
                    else -> true
                }
                if (!valid) issue("COMPILER_FIELD_TYPE", "字段必须为范围内整数")
            }
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> if (value !is JsonPrimitive || value.isString || value.doubleOrNull?.isFinite() != true)
                issue("COMPILER_FIELD_TYPE", "字段必须为有限数值")
            else -> Unit
        }
    }

    private fun fail(path: String, code: String, message: String): Nothing =
        throw NativeCompilationInputFailure(listOf(NativeAdaptationValidationIssue(path, code, message)))
}
