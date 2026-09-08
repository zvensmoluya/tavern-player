package io.github.zvensmoluya.tavernplayer.content

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

class NativeAdaptationValidator {
    fun validate(
        adaptation: NativeAdaptation,
        expectedSourceSha256: String? = null,
        availableAssetIds: Set<String>? = null,
        worldBooks: List<WorldBookDefinition>? = null,
        openingCount: Int? = null,
        regexScripts: List<RegexDefinition>? = null,
    ): NativeAdaptationValidationResult {
        val issues = mutableListOf<NativeAdaptationValidationIssue>()
        issues += NativeScriptValidator.validate(adaptation.script, adaptation.mvu != null)
        issues += NativeWorldBookTextSelectionValidator.validate(adaptation, worldBooks)
        issues += NativePlayerChoiceValidator.validate(adaptation)
        issues += NativeGuideReader.validate(adaptation.guide, regexScripts)
        issues += NativeStatusDisplay.validate(adaptation)
        issues += NativeMemoryValidator.validate(adaptation, worldBooks)
        issues += NativeEjsValidator.validate(adaptation, worldBooks)

        fun issue(path: String, code: String, message: String) {
            issues += NativeAdaptationValidationIssue(path, code, message)
        }

        adaptation.mvu?.let { program ->
            if (program.schemaScript.isBlank() || program.schemaScript.length > 256_000) {
                issue("mvu.schemaScript", "INVALID_MVU_PROGRAM", "MVU Schema 不能为空或超过 256000 字符")
            }
            if (adaptation.assistantStateAdapters.isNotEmpty()) {
                issue("assistantStateAdapters", "CONFLICTING_MVU_WRITER", "MVU 与旧消息变量适配器不能同时处理变量更新")
            }
        }

        if (adaptation.schemaVersion != NATIVE_ADAPTATION_SCHEMA_VERSION) {
            issue("schemaVersion", "UNSUPPORTED_SCHEMA", "仅支持 Native Adaptation v$NATIVE_ADAPTATION_SCHEMA_VERSION")
        }
        if (!SHA256.matches(adaptation.sourceSha256)) {
            issue("sourceSha256", "INVALID_SOURCE_HASH", "sourceSha256 必须是小写 SHA-256")
        }
        if (expectedSourceSha256 != null && adaptation.sourceSha256 != expectedSourceSha256) {
            issue("sourceSha256", "SOURCE_HASH_MISMATCH", "Native 内容与角色卡原件不匹配")
        }
        if (adaptation.state.size > MAX_STATE_VALUES) {
            issue("state", "TOO_MANY_STATE_VALUES", "Conversation State 顶层值超过 $MAX_STATE_VALUES")
        }

        val definitions = linkedMapOf<String, ConversationStateDefinition>()
        adaptation.state.forEachIndexed { index, definition ->
            val path = "state[$index]"
            validateId("$path.key", definition.key, issues)
            validateText("$path.label", definition.label, MAX_LABEL_CHARS, issues)
            validateText("$path.description", definition.description, MAX_DESCRIPTION_CHARS, issues)
            if (definitions.put(definition.key, definition) != null) {
                issue("$path.key", "DUPLICATE_STATE_KEY", "Conversation State key 重复")
            }
            if (!definition.initialValue.matches(definition.type, definition.fields)) {
                issue("$path.initialValue", "STATE_TYPE_MISMATCH", "初始值与状态类型或字段定义不匹配")
            }
            val fieldKeys = mutableSetOf<String>()
            definition.numberRange?.let { range ->
                val initial = (definition.initialValue as? JsonPrimitive)?.doubleOrNull
                if (definition.type != ConversationStateValueType.NUMBER || !range.min.isFinite() || !range.max.isFinite() ||
                    range.min >= range.max || initial == null || initial !in range.min..range.max) {
                    issue("$path.numberRange", "INVALID_STATE_RANGE", "数值范围必须有限、有序且包含初始数值")
                }
            }
            if (definition.allowedStrings.isNotEmpty() && (definition.type != ConversationStateValueType.STRING ||
                definition.allowedStrings.size > 128 || definition.allowedStrings.distinct().size != definition.allowedStrings.size ||
                (definition.initialValue as? JsonPrimitive)?.content !in definition.allowedStrings)) {
                issue("$path.allowedStrings", "INVALID_STATE_OPTIONS", "状态选项只适用于字符串，必须唯一并包含初始值")
            }
            definition.allowedStrings.forEach { validateText("$path.allowedStrings", it, MAX_INPUT_CHARS, issues) }
            if (definition.type in SCALAR_TYPES && definition.fields.isNotEmpty()) {
                issue("$path.fields", "UNEXPECTED_FIELDS", "标量状态不能定义 Record 字段")
            }
            if (definition.type in STRUCTURED_TYPES && definition.fields.size > MAX_RECORD_FIELDS) {
                issue("$path.fields", "TOO_MANY_FIELDS", "Record 字段超过 $MAX_RECORD_FIELDS")
            }
            definition.fields.forEachIndexed { fieldIndex, field ->
                val fieldPath = "$path.fields[$fieldIndex]"
                validateId("$fieldPath.key", field.key, issues)
                validateText("$fieldPath.label", field.label, MAX_LABEL_CHARS, issues)
                validateText("$fieldPath.description", field.description, MAX_DESCRIPTION_CHARS, issues)
                if (!fieldKeys.add(field.key)) issue("$fieldPath.key", "DUPLICATE_FIELD_KEY", "Record 字段 key 重复")
            }
        }

        if (adaptation.assistantStateAdapters.size > MAX_STATE_ADAPTERS) {
            issue("assistantStateAdapters", "TOO_MANY_STATE_ADAPTERS", "消息状态 Adapter 超过 $MAX_STATE_ADAPTERS")
        }
        val stageTargets = adaptation.progressions.mapTo(mutableSetOf()) { it.stageStateKey }
        val adapterDialects = mutableSetOf<LegacyStateDialect>()
        adaptation.assistantStateAdapters.forEachIndexed { adapterIndex, adapter ->
            val path = "assistantStateAdapters[$adapterIndex]"
            if (!adapterDialects.add(adapter.dialect)) {
                issue("$path.dialect", "DUPLICATE_ADAPTER_DIALECT", "同一种消息状态方言只能声明一个 Adapter")
            }
            if (adapter.mappings.isEmpty()) issue("$path.mappings", "EMPTY_MAPPINGS", "消息状态 Adapter 必须包含映射")
            if (adapter.mappings.size > MAX_STATE_MAPPINGS) {
                issue("$path.mappings", "TOO_MANY_STATE_MAPPINGS", "消息状态映射超过 $MAX_STATE_MAPPINGS")
            }
            val sources = mutableSetOf<String>()
            val targets = mutableSetOf<String>()
            adapter.mappings.forEachIndexed { mappingIndex, mapping ->
                val mappingPath = "$path.mappings[$mappingIndex]"
                val validSourcePath = when (adapter.dialect) {
                    LegacyStateDialect.UPDATE_VARIABLE_SET_V1 ->
                        mapping.sourcePath.length <= MAX_STATE_PATH_CHARS && STATE_PATH.matches(mapping.sourcePath)
                    LegacyStateDialect.UPDATE_VARIABLE_JSON_PATCH_V1 -> isValidJsonPointer(mapping.sourcePath)
                }
                if (!validSourcePath) {
                    issue("$mappingPath.sourcePath", "INVALID_STATE_PATH", "旧协议状态路径无效")
                }
                if (!sources.add(mapping.sourcePath)) {
                    issue("$mappingPath.sourcePath", "DUPLICATE_STATE_PATH", "同一 Adapter 中的来源路径重复")
                }
                val target = definitions[mapping.targetStateKey]
                if (mapping.writable && mapping.targetStateKey in stageTargets) {
                    issue(mappingPath, "DERIVED_STATE_IS_READ_ONLY", "阶段由 Player 判定，不能由模型直接写入")
                }
                if (target == null) {
                    issue("$mappingPath.targetStateKey", "UNKNOWN_STATE", "消息状态映射引用了未知状态")
                } else if (target.type !in SCALAR_TYPES) {
                    issue("$mappingPath.targetStateKey", "STRUCTURED_STATE_INGEST_UNSUPPORTED", "当前旧协议 Adapter 只能写入标量状态")
                }
                if (!targets.add(mapping.targetStateKey)) {
                    issue("$mappingPath.targetStateKey", "DUPLICATE_STATE_TARGET", "同一 Adapter 中的目标状态重复")
                }
            }
        }

        val bindingKeys = mutableSetOf<String>()
        if (adaptation.stateBindings.size > MAX_STATE_VALUES) issue("stateBindings", "TOO_MANY_BINDINGS", "状态绑定过多")
        adaptation.stateBindings.forEachIndexed { index, binding ->
            val path = "stateBindings[$index]"
            validateId("$path.key", binding.key, issues)
            if (!bindingKeys.add(binding.key) || binding.key in definitions) issue(path, "DUPLICATE_STATE_BINDING", "绑定不能重复或覆盖 Player 状态")
            if (!NativeStatePath.valid(binding.path)) issue(path, "INVALID_BINDING_PATH", "绑定需要有界 RFC 6901 路径")
            when (binding.source) {
                NativeStateSource.MVU -> if (adaptation.mvu == null) issue(path, "MISSING_STATE_SOURCE", "MVU 绑定需要启用 MVU 程序")
                NativeStateSource.PLAYER -> {
                    val initial = NativeStatePath.read(JsonObject(definitions.mapValues { it.value.initialValue }), binding.path)
                    if (!NativeStatePath.matches(initial, binding.type)) issue(path, "INVALID_PLAYER_BINDING", "Player 路径不存在或类型不匹配")
                }
            }
        }
        val readTypes = definitions.mapValues { it.value.type } + adaptation.stateBindings.associate { it.key to it.type }

        adaptation.status?.let { status ->
            validateText("status.title", status.title, MAX_LABEL_CHARS, issues)
            if (status.items.isEmpty()) issue("status.items", "EMPTY_STATUS", "Status View 必须包含至少一个状态项")
            if (status.items.size > MAX_STATUS_ITEMS) issue("status.items", "TOO_MANY_STATUS_ITEMS", "Status View 状态项过多")
            val keys = mutableSetOf<String>()
            status.items.forEachIndexed { index, item ->
                val path = "status.items[$index]"
                val type = readTypes[item.stateKey]
                if (type == null) issue("$path.stateKey", "UNKNOWN_STATE", "Status View 引用了未知状态")
                else if (type !in SCALAR_TYPES) issue("$path.stateKey", "STATUS_REQUIRES_SCALAR", "Status View 只能展示标量状态")
                validateText("$path.label", item.label, MAX_LABEL_CHARS, issues)
                if (!keys.add(item.stateKey)) issue("$path.stateKey", "DUPLICATE_STATUS_STATE", "Status View 重复展示同一状态")
                if ((item.min == null) != (item.max == null)) {
                    issue(path, "INCOMPLETE_RANGE", "Status 数值范围必须同时声明 min 与 max")
                } else if (item.min != null && item.max != null) {
                    if (type != ConversationStateValueType.NUMBER) {
                        issue(path, "RANGE_REQUIRES_NUMBER", "Status 数值范围只能用于 Number 状态")
                    }
                    if (!item.min.isFinite() || !item.max.isFinite() || item.min >= item.max) {
                        issue(path, "INVALID_RANGE", "Status 数值范围必须是有限值且 min 小于 max")
                    }
                }
            }
        }

        if (adaptation.collections.size > MAX_COLLECTION_VIEWS) {
            issue("collections", "TOO_MANY_COLLECTIONS", "Collection View 数量超过 $MAX_COLLECTION_VIEWS")
        }
        val viewIds = mutableSetOf<String>()
        if (adaptation.progressions.size > 16) issue("progressions", "TOO_MANY_PROGRESSIONS", "阶段表过多")
        val progressionTargets = mutableSetOf<String>()
        adaptation.progressions.forEachIndexed { index, progression ->
            val path = "progressions[$index]"
            val source = definitions[progression.valueStateKey]
            val target = definitions[progression.stageStateKey]
            if (source?.type != ConversationStateValueType.NUMBER || source.numberRange == null || target?.type != ConversationStateValueType.STRING ||
                !progressionTargets.add(progression.stageStateKey)) {
                issue(path, "INVALID_PROGRESSION_STATE", "阶段表必须从数值映射到唯一的字符串状态")
            }
            if (progression.levels.isEmpty() || progression.levels.size > 32 ||
                progression.levels.any { !it.minValue.isFinite() } ||
                progression.levels.zipWithNext().any { (left, right) -> left.minValue >= right.minValue }) {
                issue(path, "INVALID_PROGRESSION_LEVELS", "阶段必须按有限阈值严格递增排列")
            }
            progression.levels.forEachIndexed { levelIndex, level ->
                validateText("$path.levels[$levelIndex].label", level.label, MAX_LABEL_CHARS, issues)
                validateText("$path.levels[$levelIndex].lockedLabel", level.lockedLabel, MAX_LABEL_CHARS, issues)
                if (level.label.isBlank() || (level.unlockStateKey != null &&
                    (definitions[level.unlockStateKey]?.type != ConversationStateValueType.BOOLEAN || level.lockedLabel.isBlank()))) {
                    issue(path, "INVALID_PROGRESSION_UNLOCK", "解锁阶段只可引用布尔事件标记，并提供已锁定与解锁文案")
                }
                if (target?.allowedStrings?.isNotEmpty() == true &&
                    (level.label !in target.allowedStrings || (level.unlockStateKey != null && level.lockedLabel !in target.allowedStrings))) {
                    issue(path, "INVALID_PROGRESSION_LABEL", "阶段输出必须属于目标状态声明的选项")
                }
            }
            val lowest = progression.levels.firstOrNull()?.minValue
            val initial = (source?.initialValue as? JsonPrimitive)?.doubleOrNull
            if (lowest != null && (initial == null || initial < lowest || source.numberRange?.min?.let { it < lowest } == true)) {
                issue(path, "INCOMPLETE_PROGRESSION_RANGE", "阶段表必须覆盖初始值与声明范围")
            }
        }
        if (adaptation.messagePanels.size > 8) issue("messagePanels", "TOO_MANY_MESSAGE_PANELS", "消息资料面板过多")
        val panelTags = mutableSetOf<String>()
        val panelTagPattern = Regex("[\\p{L}][\\p{L}\\p{N}_-]{0,31}")
        adaptation.messagePanels.forEachIndexed { index, panel ->
            val path = "messagePanels[$index]"
            validateViewId(path, panel.id, viewIds, issues)
            validateText("$path.title", panel.title, MAX_LABEL_CHARS, issues)
            if (!panelTagPattern.matches(panel.sourceTag) || !panelTags.add(panel.sourceTag.lowercase())) issue(path, "INVALID_PANEL_TAG", "资料面板标签无效或重复")
            if (panel.fields.isEmpty() || panel.fields.size > 16) issue(path, "INVALID_PANEL_FIELDS", "资料面板需要 1 到 16 个字段")
            val tags = mutableSetOf<String>()
            panel.fields.forEach { field ->
                if (!panelTagPattern.matches(field.tag) || !tags.add(field.tag.lowercase()) || field.tag.equals(panel.sourceTag, ignoreCase = true)) issue(path, "INVALID_PANEL_FIELD_TAG", "资料字段标签无效或重复")
                validateText("$path.fields.label", field.label, MAX_LABEL_CHARS, issues)
            }
        }
        if (adaptation.scenes.size > MAX_SCENE_VIEWS) {
            issue("scenes", "TOO_MANY_SCENES", "Scene View 数量超过 $MAX_SCENE_VIEWS")
        }
        adaptation.scenes.forEachIndexed { index, view ->
            val path = "scenes[$index]"
            validateViewId(path, view.id, viewIds, issues)
            validateText("$path.title", view.title, MAX_LABEL_CHARS, issues)
            validateText("$path.emptyLabel", view.emptyLabel, MAX_LABEL_CHARS, issues)
            val type = readTypes[view.stateKey]
            if (type == null) issue("$path.stateKey", "UNKNOWN_STATE", "Scene View 引用了未知状态")
            else if (type !in SCALAR_TYPES) {
                issue("$path.stateKey", "SCENE_REQUIRES_SCALAR", "Scene View 必须引用标量状态")
            }
            if (view.assets.isEmpty()) issue("$path.assets", "EMPTY_SCENE_ASSETS", "Scene View 必须包含状态到资产的映射")
            if (view.assets.size > MAX_SCENE_ASSETS) issue("$path.assets", "TOO_MANY_SCENE_ASSETS", "Scene 资产映射过多")
            val stateValues = mutableSetOf<String>()
            view.assets.forEachIndexed { assetIndex, asset ->
                val assetPath = "$path.assets[$assetIndex]"
                validateText("$assetPath.stateValue", asset.stateValue, MAX_LABEL_CHARS, issues)
                validateText("$assetPath.contentDescription", asset.contentDescription, MAX_LABEL_CHARS, issues)
                if (!stateValues.add(asset.stateValue)) {
                    issue("$assetPath.stateValue", "DUPLICATE_SCENE_VALUE", "Scene View 的状态值重复")
                }
                if (!ID.matches(asset.assetId)) issue("$assetPath.assetId", "INVALID_ASSET_ID", "Scene View 资产标识符格式无效")
                if (availableAssetIds != null && asset.assetId !in availableAssetIds) {
                    issue("$assetPath.assetId", "UNKNOWN_ASSET", "Scene View 引用了不可用的本地静态资产")
                }
            }
        }
        adaptation.collections.forEachIndexed { index, view ->
            val path = "collections[$index]"
            validateViewId(path, view.id, viewIds, issues)
            validateText("$path.title", view.title, MAX_LABEL_CHARS, issues)
            validateText("$path.emptyLabel", view.emptyLabel, MAX_LABEL_CHARS, issues)
            val definition = definitions[view.stateKey]
            val type = readTypes[view.stateKey]
            if (type == null) issue("$path.stateKey", "UNKNOWN_STATE", "Collection View 引用了未知状态")
            else if (type != (if (view.shape == NativeCollectionShape.ARRAY) ConversationStateValueType.COLLECTION else ConversationStateValueType.RECORD)) {
                issue("$path.stateKey", "COLLECTION_REQUIRES_COLLECTION_STATE", "Collection View 必须引用 Collection 状态")
            }
            val schemaFields = definition?.fields?.mapTo(mutableSetOf()) { it.key }.orEmpty()
            if (view.fields.isEmpty() || view.fields.size > MAX_RECORD_FIELDS) issue(path, "INVALID_COLLECTION_FIELDS", "集合需要有限的展示字段")
            val fields = mutableSetOf<String>()
            view.fields.forEachIndexed { fieldIndex, field ->
                val fieldPath = "$path.fields[$fieldIndex]"
                if (field.entryKey && (view.shape != NativeCollectionShape.OBJECT || field.path != null)) issue(fieldPath, "INVALID_ENTRY_KEY", "物品名只适用于对象集合且不能同时绑定字段路径")
                if (field.path != null && !NativeStatePath.valid(field.path)) issue(fieldPath, "INVALID_BINDING_PATH", "集合字段路径无效")
                if (field.key.isBlank() || field.key.length > 128) issue(fieldPath, "INVALID_COLLECTION_FIELD", "集合字段标识无效")
                if (view.stateKey !in bindingKeys && !field.entryKey && field.path == null && field.key !in schemaFields) issue("$fieldPath.key", "UNKNOWN_RECORD_FIELD", "Collection View 引用了未知 Record 字段")
                if (!fields.add(field.key)) issue("$fieldPath.key", "DUPLICATE_COLLECTION_FIELD", "Collection View 字段重复")
                validateText("$fieldPath.label", field.label, MAX_LABEL_CHARS, issues)
            }
        }

        if (adaptation.forms.size > MAX_FORMS) issue("forms", "TOO_MANY_FORMS", "Form View 数量超过 $MAX_FORMS")
        val formMarkers = mutableSetOf<String>()
        val formOpenings = mutableSetOf<Int>()
        adaptation.forms.forEachIndexed { index, form ->
            val path = "forms[$index]"
            validateViewId(path, form.id, viewIds, issues)
            validateText("$path.title", form.title, MAX_LABEL_CHARS, issues)
            validateText("$path.description", form.description, MAX_DESCRIPTION_CHARS, issues)
            validateText("$path.marker", form.marker, MAX_MARKER_CHARS, issues)
            if (form.replacedDisplayRegexIds.size > 16 || form.replacedDisplayRegexIds.distinct().size != form.replacedDisplayRegexIds.size ||
                form.replacedDisplayRegexIds.any { id ->
                    val sourceRule = regexScripts?.singleOrNull { it.id == id }
                    sourceRule == null || !sourceRule.markdownOnly || sourceRule.promptOnly
                }) {
                issue("$path.replacedDisplayRegexIds", "INVALID_REPLACED_DISPLAY_REGEX", "表单只能接管原卡中唯一存在、仅用于显示的 Regex")
            }
            if (form.openingIndices.isEmpty()) {
                if (form.marker.isBlank()) issue("$path.marker", "EMPTY_MARKER", "Form 必须拥有固定消息 marker 或开场来源引用")
                else if (!formMarkers.add(form.marker)) issue("$path.marker", "DUPLICATE_FORM_MARKER", "Form 消息 marker 重复")
            } else {
                if (form.marker.isNotEmpty() || form.setup == null || form.openingIndices.size > 32 ||
                    form.openingIndices.any { it !in 0..255 || (openingCount != null && it >= openingCount) || !formOpenings.add(it) }) {
                    issue("$path.openingIndices", "INVALID_FORM_OPENINGS", "开场表单只允许唯一、有效的来源引用，不能同时指定 marker")
                }
            }
            if (form.fields.isEmpty()) issue("$path.fields", "EMPTY_FORM", "Form View 必须包含字段")
            if (form.fields.size > MAX_FORM_FIELDS) issue("$path.fields", "TOO_MANY_FORM_FIELDS", "Form 字段过多")
            val fieldIds = mutableSetOf<String>()
            form.fields.forEachIndexed { fieldIndex, field ->
                val fieldPath = "$path.fields[$fieldIndex]"
                validateId("$fieldPath.id", field.id, issues)
                if (!fieldIds.add(field.id)) issue("$fieldPath.id", "DUPLICATE_FIELD_ID", "Form 字段 id 重复")
                validateText("$fieldPath.label", field.label, MAX_LABEL_CHARS, issues)
                validateText("$fieldPath.placeholder", field.placeholder, MAX_LABEL_CHARS, issues)
                validateText("$fieldPath.emptyText", field.emptyText, MAX_INPUT_CHARS, issues)
                if (field.initialValues.size > MAX_FORM_OPTIONS) {
                    issue("$fieldPath.initialValues", "TOO_MANY_INITIAL_VALUES", "Form 默认值过多")
                }
                field.initialValues.forEachIndexed { valueIndex, value ->
                    validateText("$fieldPath.initialValues[$valueIndex]", value, MAX_INPUT_CHARS, issues)
                }
                val choice = field.type == NativeFormFieldType.SINGLE_SELECT || field.type == NativeFormFieldType.MULTI_SELECT
                if (choice && field.options.isEmpty()) issue("$fieldPath.options", "MISSING_OPTIONS", "选择字段必须包含选项")
                if (!choice && field.options.isNotEmpty()) issue("$fieldPath.options", "UNEXPECTED_OPTIONS", "非选择字段不能包含选项")
                if (field.options.size > MAX_FORM_OPTIONS) issue("$fieldPath.options", "TOO_MANY_OPTIONS", "选项过多")
                val optionValues = mutableSetOf<String>()
                field.options.forEachIndexed { optionIndex, option ->
                    validateText("$fieldPath.options[$optionIndex].value", option.value, MAX_LABEL_CHARS, issues)
                    validateText("$fieldPath.options[$optionIndex].label", option.label, MAX_LABEL_CHARS, issues)
                    if (!optionValues.add(option.value)) {
                        issue("$fieldPath.options[$optionIndex].value", "DUPLICATE_OPTION_VALUE", "同一字段的选项值重复")
                    }
                }
                if (field.type != NativeFormFieldType.MULTI_SELECT && field.initialValues.size > 1) {
                    issue("$fieldPath.initialValues", "MULTIPLE_VALUES_FOR_SINGLE_FIELD", "只有多选字段允许多个默认值")
                }
                if (choice && field.initialValues.any { it !in optionValues }) {
                    issue("$fieldPath.initialValues", "UNKNOWN_INITIAL_OPTION", "选择字段的默认值不在选项中")
                }
                if (field.type == NativeFormFieldType.TOGGLE && field.initialValues.any { it != "true" && it != "false" }) {
                    issue("$fieldPath.initialValues", "INVALID_TOGGLE_INITIAL_VALUE", "开关默认值只能是 true 或 false")
                }
            }
            form.setup?.let { setup ->
                setup.openingIndex?.let { target ->
                    if (target !in 0..255 || (openingCount != null && target >= openingCount)) {
                        issue("$path.setup.openingIndex", "INVALID_SETUP_OPENING", "开局目标必须引用已有开场")
                    }
                }
                validateSetupPayload("$path.setup.values", setup.values, definitions, worldBooks, issues)
                if (setup.stateFields.values.distinct().size != setup.stateFields.size) {
                    issue(path, "DUPLICATE_SETUP_FIELD", "开局字段只允许一对一复制")
                }
                val claimedState = (setup.values.stateValues.keys + setup.stateFields.keys).toMutableSet()
                val claimedBooks = setup.values.worldBookOverrides.mapTo(mutableSetOf()) { it.bookId to it.entryId }
                form.fields.forEach { field ->
                    val options = field.options.mapNotNull { it.setup }
                    val optionStates = options.flatMap { it.stateValues.keys }.toSet()
                    val optionBooks = options.flatMap { it.worldBookOverrides }.map { it.bookId to it.entryId }.toSet()
                    if (optionStates.any { it in claimedState } || optionBooks.any { it in claimedBooks }) {
                        issue(path, "CONFLICTING_SETUP_OPTION", "不同开局字段或常量不能写入同一目标")
                    }
                    claimedState += optionStates
                    claimedBooks += optionBooks
                }
                setup.stateFields.forEach { (stateKey, fieldId) ->
                    if (stateKey in stageTargets) issue(path, "DERIVED_STATE_IS_READ_ONLY", "开局不能直接写入派生阶段")
                    val definition = definitions[stateKey]
                    val field = form.fields.firstOrNull { it.id == fieldId }
                    val expected = when (field?.type) {
                        NativeFormFieldType.TEXT, NativeFormFieldType.MULTILINE_TEXT, NativeFormFieldType.SINGLE_SELECT -> ConversationStateValueType.STRING
                        NativeFormFieldType.NUMBER -> ConversationStateValueType.NUMBER
                        NativeFormFieldType.TOGGLE -> ConversationStateValueType.BOOLEAN
                        else -> null
                    }
                    if (definition == null || expected == null || definition.type != expected) {
                        issue("$path.setup.stateFields.$stateKey", "INVALID_SETUP_FIELD", "开局字段必须一对一复制到同类型标量状态")
                    }
                    if (stateKey in setup.values.stateValues) {
                        issue("$path.setup.stateFields.$stateKey", "CONFLICTING_SETUP_TARGET", "开局常量与字段不能写入同一个状态")
                    }
                }
            }
            form.fields.forEachIndexed { fieldIndex, field ->
                field.options.forEachIndexed { optionIndex, option ->
                    option.setup?.let { payload ->
                        val optionPath = "$path.fields[$fieldIndex].options[$optionIndex].setup"
                        if (form.setup == null || field.type != NativeFormFieldType.SINGLE_SELECT) {
                            issue(optionPath, "INVALID_OPTION_SETUP", "只有开局表单的单选项可携带初始化常量")
                        }
                        validateSetupPayload(optionPath, payload, definitions, worldBooks, issues)
                    }
                }
            }
            validateText("$path.draftTemplate", form.draftTemplate, MAX_DRAFT_TEMPLATE_CHARS, issues)
            val setupPayloads = listOfNotNull(form.setup?.values) + form.fields.flatMap { field -> field.options.mapNotNull { it.setup } }
            if (setupPayloads.any { payload -> payload.stateValues.keys.any { it in stageTargets } }) issue(path, "DERIVED_STATE_IS_READ_ONLY", "开局不能直接写入派生阶段")
            if (form.draftTemplate.isBlank()) {
                issue("$path.draftTemplate", "EMPTY_DRAFT_TEMPLATE", "Form 必须把填写结果投影为聊天草稿")
            }
            val referencedFieldIds = FORM_REFERENCE.findAll(form.draftTemplate).map { match ->
                match.groupValues[1]
            }.toSet()
            referencedFieldIds.forEach { fieldId ->
                if (fieldId !in fieldIds) {
                    issue("$path.draftTemplate", "UNKNOWN_FORM_FIELD", "Draft 投影引用了未知字段 $fieldId")
                }
            }
            fieldIds.filterNot(referencedFieldIds::contains).forEach { fieldId ->
                issue(
                    "$path.draftTemplate",
                    "UNUSED_FORM_FIELD",
                    "Form 字段 $fieldId 没有进入聊天草稿；当前 Form 不允许收集后丢弃输入",
                )
            }
            ANY_REFERENCE.findAll(form.draftTemplate).forEach { match ->
                val reference = match.groupValues[1].trim()
                if (reference != "user" && reference != "char" && !FORM_REFERENCE.matches(match.value)) {
                    issue("$path.draftTemplate", "UNSUPPORTED_DRAFT_REFERENCE", "Draft 投影只能引用 Form 字段和对话身份")
                }
            }
        }

        return NativeAdaptationValidationResult(issues.distinct())
    }

    private fun validateSetupPayload(
        path: String,
        payload: NativeSetupPayload,
        definitions: Map<String, ConversationStateDefinition>,
        worldBooks: List<WorldBookDefinition>?,
        issues: MutableList<NativeAdaptationValidationIssue>,
    ) {
        payload.stateValues.forEach { (key, value) ->
            val definition = definitions[key]
            if (definition == null || !value.matches(definition.type, definition.fields) || value.toString().length > MAX_INPUT_CHARS) {
                issues += NativeAdaptationValidationIssue("$path.stateValues.$key", "INVALID_SETUP_VALUE", "开局常量必须匹配已声明的状态类型与大小限制")
            }
            if (definition?.allowedStrings?.isNotEmpty() == true && (value as? JsonPrimitive)?.content !in definition.allowedStrings) {
                issues += NativeAdaptationValidationIssue("$path.stateValues.$key", "INVALID_SETUP_OPTION", "开局常量不在声明选项中")
            }
        }
        if (payload.worldBookOverrides.size > 128) {
            issues += NativeAdaptationValidationIssue(path, "TOO_MANY_SETUP_OVERRIDES", "开局世界书设置过多")
        }
        val targets = mutableSetOf<Pair<String, String?>>()
        payload.worldBookOverrides.forEachIndexed { index, override ->
            val book = worldBooks?.firstOrNull { it.id == override.bookId }
            if (override.bookId.isBlank() || override.entryId?.isBlank() == true ||
                (worldBooks != null && (book == null || (override.entryId != null && book.entries.none { it.id == override.entryId })))) {
                issues += NativeAdaptationValidationIssue("$path.worldBookOverrides[$index]", "UNKNOWN_SETUP_WORLD_BOOK", "开局设置只能引用当前角色快照内的世界书或条目")
            }
            if (!targets.add(override.bookId to override.entryId)) {
                issues += NativeAdaptationValidationIssue("$path.worldBookOverrides[$index]", "CONFLICTING_SETUP_TARGET", "开局世界书目标重复")
            }
        }
    }

    private fun validateViewId(
        path: String,
        id: String,
        ids: MutableSet<String>,
        issues: MutableList<NativeAdaptationValidationIssue>,
    ) {
        validateId("$path.id", id, issues)
        if (!ids.add(id)) issues += NativeAdaptationValidationIssue("$path.id", "DUPLICATE_VIEW_ID", "Native View id 重复")
    }

    private fun validateId(path: String, value: String, issues: MutableList<NativeAdaptationValidationIssue>) {
        if (!ID.matches(value)) issues += NativeAdaptationValidationIssue(path, "INVALID_ID", "标识符格式无效")
    }

    private fun isValidJsonPointer(value: String): Boolean {
        if (value.length !in 2..MAX_STATE_PATH_CHARS || !value.startsWith('/')) return false
        val segments = value.substring(1).split('/')
        if (segments.isEmpty() || segments.size > MAX_STATE_PATH_SEGMENTS || segments.any(String::isEmpty)) return false
        return segments.all { segment ->
            var index = 0
            while (index < segment.length) {
                val char = segment[index]
                if (char.code < 0x20) return@all false
                if (char == '~') {
                    if (index + 1 >= segment.length || segment[index + 1] !in "01") return@all false
                    index += 1
                }
                index += 1
            }
            true
        }
    }

    private fun validateText(
        path: String,
        value: String,
        limit: Int,
        issues: MutableList<NativeAdaptationValidationIssue>,
    ) {
        if (value.length > limit) issues += NativeAdaptationValidationIssue(path, "TEXT_TOO_LONG", "文本超过 $limit 字符")
    }

    private fun JsonElement.matches(
        type: ConversationStateValueType,
        fields: List<ConversationStateFieldDefinition>,
    ): Boolean = when (type) {
        ConversationStateValueType.STRING -> (this as? JsonPrimitive)?.isString == true
        ConversationStateValueType.NUMBER -> (this as? JsonPrimitive)
            ?.takeUnless(JsonPrimitive::isString)
            ?.takeIf { it.booleanOrNull == null }
            ?.doubleOrNull
            ?.isFinite() == true
        ConversationStateValueType.BOOLEAN -> (this as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.booleanOrNull != null
        ConversationStateValueType.RECORD -> (this as? JsonObject)?.matches(fields) == true
        ConversationStateValueType.COLLECTION -> (this as? JsonArray)?.all { (it as? JsonObject)?.matches(fields) == true } == true
    }

    private fun JsonObject.matches(fields: List<ConversationStateFieldDefinition>): Boolean {
        val byKey = fields.associateBy(ConversationStateFieldDefinition::key)
        if (keys != byKey.keys) return false
        return entries.all { (key, value) -> value.matches(byKey.getValue(key).type) }
    }

    private fun JsonElement.matches(type: ConversationStateScalarType): Boolean = when (type) {
        ConversationStateScalarType.STRING -> (this as? JsonPrimitive)?.isString == true
        ConversationStateScalarType.NUMBER -> (this as? JsonPrimitive)
            ?.takeUnless(JsonPrimitive::isString)
            ?.takeIf { it.booleanOrNull == null }
            ?.doubleOrNull
            ?.isFinite() == true
        ConversationStateScalarType.BOOLEAN -> (this as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.booleanOrNull != null
    }

    private companion object {
        val SCALAR_TYPES = setOf(
            ConversationStateValueType.STRING,
            ConversationStateValueType.NUMBER,
            ConversationStateValueType.BOOLEAN,
        )
        val STRUCTURED_TYPES = setOf(ConversationStateValueType.RECORD, ConversationStateValueType.COLLECTION)
        const val MAX_STATE_VALUES = 512
        const val MAX_RECORD_FIELDS = 64
        const val MAX_STATE_ADAPTERS = 1
        const val MAX_STATE_MAPPINGS = 512
        const val MAX_STATUS_ITEMS = 32
        const val MAX_SCENE_VIEWS = 8
        const val MAX_SCENE_ASSETS = 128
        const val MAX_COLLECTION_VIEWS = 16
        const val MAX_FORMS = 16
        const val MAX_FORM_FIELDS = 32
        const val MAX_FORM_OPTIONS = 64
        const val MAX_LABEL_CHARS = 256
        const val MAX_DESCRIPTION_CHARS = 4_096
        const val MAX_INPUT_CHARS = 8_192
        const val MAX_MARKER_CHARS = 1_024
        const val MAX_DRAFT_TEMPLATE_CHARS = 16_384
        const val MAX_STATE_PATH_CHARS = 1_024
        const val MAX_STATE_PATH_SEGMENTS = 16
        val SHA256 = Regex("[0-9a-f]{64}")
        val ID = Regex("[a-z][a-z0-9]*(?:[._-][a-z0-9]+){0,15}")
        val STATE_PATH = Regex("[^.\\s'\"(){}\\[\\]$]+(?:\\.[^.\\s'\"(){}\\[\\]$]+){0,15}")
        val FORM_REFERENCE = Regex("\\{\\{form\\.([a-z][a-z0-9]*(?:[._-][a-z0-9]+){0,15})\\}\\}")
        val ANY_REFERENCE = Regex("\\{\\{([^{}]+)\\}\\}")
    }
}
