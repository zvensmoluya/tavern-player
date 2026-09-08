package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.NativeAdaptationValidator
import io.github.zvensmoluya.tavernplayer.content.NativeFormFieldType
import io.github.zvensmoluya.tavernplayer.content.NativeSetupPayload
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

sealed interface NativeSetupResult {
    data class Committed(val record: ConversationRecord) : NativeSetupResult
    data class Rejected(val message: String) : NativeSetupResult
}

/** 一次性开局事务。返回完整的新记录，调用方持久化成功后才发布状态和草稿。 */
class NativeSetupController(private val forms: NativeAdaptationRuntime = NativeAdaptationRuntime()) {
    fun commit(record: ConversationRecord, submission: NativeFormSubmission): NativeSetupResult {
        fun reject(message: String) = NativeSetupResult.Rejected(message)
        if (record.runtimeState.setupCommit != null) return reject("开局设定已经保存；重新开始对话后可重新填写")
        if (record.turns.any { it.role == MessageRole.USER }) return reject("对话已经开始，不能再次初始化开局")
        val adaptation = record.character.nativeAdaptation ?: return reject("没有可用的原生开局")
        val form = adaptation.forms.firstOrNull { it.id == submission.formId } ?: return reject("找不到开局表单")
        val setup = form.setup ?: return reject("这是一张普通表单")
        val opening = record.turns.singleOrNull()?.takeIf { it.role == MessageRole.ASSISTANT }
            ?: return reject("仅在对话的开场阶段设定")
        if (!form.matchesMessage(opening.selected.message.sourceText, opening.selected.openingSourceIndex)) return reject("当前开场没有这张表单")
        val validation = NativeAdaptationValidator().validate(adaptation, worldBooks = record.character.worldBooks,
            openingCount = 1 + record.character.alternateFirstMessages.size, regexScripts = record.character.regexScripts)
        if (!validation.valid) return reject(validation.issues.first().message)
        val targetIndex = setup.openingIndex?.let { target ->
            opening.variants.indices.singleOrNull { opening.variants[it].openingSourceIndex == target }
                ?: return reject("开局目标不存在或没有可用正文")
        } ?: opening.selectedVariantIndex
        val targetState = opening.variants[targetIndex].nativeHead() ?: record.runtimeState
        val draft = when (val result = forms.submitForm(adaptation, submission, record.persona.name, record.character.promptName)) {
            is NativeFormSubmissionResult.Draft -> result.text
            is NativeFormSubmissionResult.Rejected -> return reject(result.message)
        }
        if (record.draft.isNotBlank() && record.draft != draft) return reject("输入框已有内容，请先保存或清空后再提交开局设定")
        val payloads = mutableListOf(setup.values)
        val direct = linkedMapOf<String, JsonElement>()
        setup.stateFields.forEach { (key, fieldId) ->
            val field = form.fields.first { it.id == fieldId }
            val value = submission.values[fieldId]?.singleOrNull()?.takeIf(String::isNotBlank)
                ?: return reject("请填写${field.label}，它是开局状态的一部分")
            direct[key] = when (field.type) {
                NativeFormFieldType.NUMBER -> JsonPrimitive(value.toDouble())
                NativeFormFieldType.TOGGLE -> JsonPrimitive(value.toBooleanStrict())
                else -> JsonPrimitive(value)
            }
        }
        payloads += NativeSetupPayload(stateValues = direct)
        form.fields.forEach { field ->
            field.options.firstOrNull { it.value == submission.values[field.id]?.singleOrNull() }?.setup?.let(payloads::add)
        }
        val stateValues = linkedMapOf<String, JsonElement>()
        val targets = mutableSetOf<Pair<String, String?>>()
        val intents = mutableListOf<WorldBookActivationIntent>()
        payloads.forEach { payload ->
            payload.stateValues.forEach { (key, value) ->
                val allowed = adaptation.state.first { it.key == key }.allowedStrings
                if (allowed.isNotEmpty() && (value as? JsonPrimitive)?.content !in allowed) return reject("开局状态不在允许选项中：$key")
                if (stateValues.put(key, value) != null) return reject("开局选择重复写入同一个状态：$key")
            }
            payload.worldBookOverrides.forEach { override ->
                val entryId = override.entryId
                if (!targets.add(override.bookId to override.entryId)) return reject("开局选择包含重复的世界书设置")
                intents += if (entryId == null) {
                    WorldBookActivationIntent.SetBookEnabled(override.bookId, override.enabled)
                } else WorldBookActivationIntent.SetEntryEnabled(override.bookId, entryId, override.enabled)
            }
        }
        val activation = WorldBookActivationController().apply(record.character.worldBooks, targetState, intents)
        if (activation is WorldBookActivationMutationResult.Rejected) return reject("开局引用的世界书或条目不存在")
        val overrides = (activation as WorldBookActivationMutationResult.Applied).runtimeState.worldBookActivationOverrides
        fun ConversationRuntimeState.initialized() = copy(
            conversationState = NativeStateRules.apply(adaptation, conversationState.applying(ConversationStatePatch(stateValues))),
            worldBookActivationOverrides = overrides,
            setupCommit = ConversationSetupCommit(form.id),
        )
        return NativeSetupResult.Committed(record.copy(
            draft = draft,
            runtimeState = targetState.initialized(),
            turns = record.turns.map { turn ->
                turn.copy(selectedVariantIndex = targetIndex, variants = turn.variants.map { variant ->
                    variant.copy(
                        runtimeStateBefore = (variant.runtimeStateBefore ?: record.runtimeState).initialized(),
                        projectionRuntimeStateBefore = (variant.projectionRuntimeStateBefore ?: record.runtimeState).initialized(),
                        runtimeStateAfter = (variant.runtimeStateAfter ?: record.runtimeState).initialized(),
                        nativeOperations = variant.nativeOperations.map { operation -> operation.copy(commits = operation.commits.map { commit -> commit.copy(runtime = commit.runtime.initialized()) }) },
                    )
                })
            },
        ))
    }
}
