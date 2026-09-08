package io.github.zvensmoluya.tavernplayer.content

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Executable semantic projections. Surface contracts, never layout nodes, cross the UI boundary. */
@Serializable
data class NativeScriptProgram(
    val version: Int = 1,
    val modules: List<NativeScriptModule>,
    val surfaces: List<NativeSurfaceEntry>,
    val handlers: List<NativeHandlerEntry> = emptyList(),
    val capabilities: Set<NativeScriptCapability> = emptySet(),
    val initialState: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class NativeScriptModule(
    val id: String,
    val code: String,
    val sourceIds: List<String>,
    val transformation: String,
)

@Serializable
data class NativeSurfaceEntry(val id: String, val module: String, val export: String, val surface: NativeSurfaceType)

@Serializable
data class NativeHandlerEntry(val id: String, val module: String, val export: String)

@Serializable
enum class NativeScriptCapability { VARIABLES_READ, MVU_REPLACE, PROGRAM_STATE_REPLACE, DRAFT_REPLACE, GENERATE_TEXT }

@Serializable
enum class NativeSurfaceType {
    @SerialName("status") STATUS,
    @SerialName("collection") COLLECTION,
    @SerialName("form") FORM,
    @SerialName("scene") SCENE,
    @SerialName("action_group") ACTION_GROUP,
}

@Serializable
data class NativeSurfaceData(
    val surface: NativeSurfaceType,
    val title: String,
    val description: String = "",
    val items: List<NativeSurfaceItem> = emptyList(),
    val fields: List<NativeSurfaceField> = emptyList(),
    val actions: List<NativeSurfaceAction> = emptyList(),
    val emptyLabel: String = "暂无内容",
)

@Serializable
data class NativeSurfaceItem(
    val key: String, val title: String, val description: String = "", val status: String = "",
    val actions: List<NativeSurfaceAction> = emptyList(),
)

@Serializable
data class NativeSurfaceField(
    val id: String, val label: String, val value: String = "", val required: Boolean = false,
    /** Empty options means free text; otherwise select one exact string. */
    val options: List<String> = emptyList(),
)

@Serializable
data class NativeSurfaceAction(
    val id: String, val label: String, val handler: String,
    val args: JsonObject = JsonObject(emptyMap()), val enabled: Boolean = true,
)

object NativeScriptValidator {
    private val id = Regex("[a-z][a-z0-9]*(?:[._-][a-z0-9]+){0,15}")
    private val exportName = Regex("[A-Za-z_$][A-Za-z0-9_$]{0,127}")

    fun validate(program: NativeScriptProgram?, hasMvu: Boolean): List<NativeAdaptationValidationIssue> {
        if (program == null) return emptyList()
        return try {
            require(program.version == 1) { "不支持的 JS 契约版本" }
            require(program.modules.size in 1..32 && program.surfaces.size in 1..32 && program.handlers.size <= 128)
            require(program.modules.sumOf { it.code.length } <= 131_072)
            fun ids(values: List<String>) = values.all { id.matches(it) } && values.distinct().size == values.size
            require(ids(program.modules.map { it.id }) && ids(program.surfaces.map { it.id }) && ids(program.handlers.map { it.id })) { "JS 入口 ID 无效或重复" }
            program.modules.forEach {
                require(it.code.isNotBlank() && it.sourceIds.isNotEmpty() && it.sourceIds.size <= 128 &&
                    it.transformation.isNotBlank() && it.transformation.length <= 2048) { "模块必须关联来源并解释转换" }
            }
            val modules = program.modules.map { it.id }.toSet()
            require(program.surfaces.all { it.module in modules && exportName.matches(it.export) } &&
                program.handlers.all { it.module in modules && exportName.matches(it.export) }) { "JS 入口引用无效" }
            require(hasMvu || NativeScriptCapability.MVU_REPLACE !in program.capabilities) { "MVU 写入需要 MVU 程序" }
            require(program.initialState.toString().length <= 65_536) { "程序初始状态过大" }
            emptyList()
        } catch (error: IllegalArgumentException) {
            listOf(NativeAdaptationValidationIssue("script", "INVALID_SCRIPT_PROGRAM", error.message ?: "JS 产物超出契约限制"))
        }
    }

    fun validateSurface(data: NativeSurfaceData, entry: NativeSurfaceEntry, program: NativeScriptProgram) {
        require(data.surface == entry.surface) { "Surface 类型与入口声明不一致" }
        require(data.title.length <= 256 && data.description.length <= 16_384 && data.emptyLabel.length <= 256)
        require(data.items.size <= 512 && data.fields.size <= 32 && data.actions.size <= 32)
        require(data.items.map { it.key }.distinct().size == data.items.size)
        require(data.fields.map { it.id }.distinct().size == data.fields.size)
        require(data.surface == NativeSurfaceType.FORM || data.fields.isEmpty()) { "仅 Form 提供输入字段" }
        require(data.surface != NativeSurfaceType.FORM || data.items.isEmpty())
        require(data.surface != NativeSurfaceType.ACTION_GROUP || data.items.isEmpty())
        val handlers = program.handlers.map { it.id }.toSet()
        fun actions(values: List<NativeSurfaceAction>) {
            require(values.size <= 32 && values.map { it.id }.distinct().size == values.size)
            values.forEach { require(it.id.isNotBlank() && it.id.length <= 256 && it.label.length in 1..256 &&
                it.handler in handlers && it.args.toString().length <= 16_384) { "无效的 Surface 操作" } }
        }
        actions(data.actions)
        data.items.forEach {
            require(it.key.isNotBlank() && it.key.length <= 256 && it.title.length <= 1024 &&
                it.description.length <= 16_384 && it.status.length <= 1024)
            actions(it.actions)
        }
        data.fields.forEach {
            require(id.matches(it.id) && it.label.length <= 256 && it.value.length <= 16_384 &&
                it.options.size <= 128 && it.options.distinct().size == it.options.size && it.options.all { option -> option.length <= 1024 })
            require(it.options.isEmpty() || it.value.isEmpty() || it.value in it.options)
        }
    }
}
