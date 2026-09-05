package io.github.zvensmoluya.tavernplayer.content

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAdaptationValidatorTest {
    private val validator = NativeAdaptationValidator()

    @Test
    fun `accepts fixed Player views without capabilities actions or triggers`() {
        val result = validator.validate(fixture(), "a".repeat(64), setOf("asset-beach"))

        assertTrue(result.issues.toString(), result.valid)
    }

    @Test
    fun `rejects mismatched view data and draft references`() {
        val source = fixture()
        val invalid = source.copy(
            status = source.status?.copy(items = listOf(NativeStatusItem("inventory", "错误状态"))),
            collections = source.collections.map { it.copy(stateKey = "affection") },
            forms = source.forms.map { it.copy(draftTemplate = "{{state.affection}} {{form.missing}}") },
        )

        val result = validator.validate(invalid)

        assertFalse(result.valid)
        assertTrue(result.issues.any { it.code == "STATUS_REQUIRES_SCALAR" })
        assertTrue(result.issues.any { it.code == "COLLECTION_REQUIRES_COLLECTION_STATE" })
        assertTrue(result.issues.any { it.code == "UNSUPPORTED_DRAFT_REFERENCE" })
        assertTrue(result.issues.any { it.code == "UNKNOWN_FORM_FIELD" })
    }

    @Test
    fun `rejects form fields that cannot affect the sole draft outcome`() {
        val source = fixture()
        val invalid = source.copy(
            forms = source.forms.map { form ->
                form.copy(
                    fields = form.fields + NativeFormField("ignored", NativeFormFieldType.TEXT, "被忽略字段"),
                    draftTemplate = "固定文本",
                )
            },
        )

        val result = validator.validate(invalid)

        assertTrue(result.issues.any { it.code == "UNUSED_FORM_FIELD" })
    }

    @Test
    fun `rejects scene references outside local card assets`() {
        val result = validator.validate(fixture(), availableAssetIds = setOf("asset-forest"))

        assertTrue(result.issues.any { it.code == "UNKNOWN_ASSET" })
    }

    @Test
    fun `rejects collection values outside their declared record shape`() {
        val source = fixture()
        val invalid = source.copy(
            state = source.state.map { definition ->
                if (definition.key == "inventory") {
                    definition.copy(initialValue = JsonArray(listOf(JsonObject(mapOf("unknown" to JsonPrimitive("x"))))))
                } else definition
            },
        )

        val result = validator.validate(invalid)

        assertTrue(result.issues.any { it.code == "STATE_TYPE_MISMATCH" })
    }

    @Test
    fun `record values must contain their complete declared shape`() {
        val source = fixture()
        val invalid = source.copy(
            state = source.state.map { definition ->
                if (definition.key == "inventory") {
                    definition.copy(
                        initialValue = JsonArray(
                            listOf(JsonObject(mapOf("name" to JsonPrimitive("potion")))),
                        ),
                    )
                } else definition
            },
        )

        val result = validator.validate(invalid)

        assertTrue(result.issues.any { it.code == "STATE_TYPE_MISMATCH" })
    }

    @Test
    fun `rejects ambiguous form markers options and unusable status ranges`() {
        val source = fixture()
        val duplicatedForm = source.forms.single().copy(id = "duplicate-form")
        val invalid = source.copy(
            status = NativeStatusView(
                items = listOf(
                    NativeStatusItem("location", "Location", min = 0.0, max = 1.0),
                    NativeStatusItem("affection", "Affection", min = 0.0),
                ),
            ),
            forms = listOf(
                source.forms.single().copy(
                    fields = listOf(
                        NativeFormField(
                            "name",
                            NativeFormFieldType.SINGLE_SELECT,
                            "Name",
                            options = listOf(NativeFormOption("same"), NativeFormOption("same")),
                        ),
                    ),
                ),
                duplicatedForm,
            ),
        )

        val result = validator.validate(invalid)

        assertTrue(result.issues.any { it.code == "RANGE_REQUIRES_NUMBER" })
        assertTrue(result.issues.any { it.code == "INCOMPLETE_RANGE" })
        assertTrue(result.issues.any { it.code == "DUPLICATE_OPTION_VALUE" })
        assertTrue(result.issues.any { it.code == "DUPLICATE_FORM_MARKER" })
    }

    @Test
    fun `validates source path syntax for each legacy adapter dialect`() {
        val source = fixture()
        val valid = source.copy(
            assistantStateAdapters = listOf(
                AssistantStateAdapterDefinition(
                    LegacyStateDialect.UPDATE_VARIABLE_JSON_PATCH_V1,
                    listOf(AssistantStateMapping("/角色/好感度", "affection")),
                ),
            ),
        )
        val invalid = valid.copy(
            assistantStateAdapters = valid.assistantStateAdapters.map { adapter ->
                adapter.copy(mappings = listOf(AssistantStateMapping("角色/~broken", "affection")))
            },
        )

        assertTrue(validator.validate(valid).valid)
        assertTrue(validator.validate(invalid).issues.any { it.code == "INVALID_STATE_PATH" })
    }

    @Test
    fun `accepts only one assistant state dialect per adaptation`() {
        val source = fixture()
        val result = validator.validate(
            source.copy(
                assistantStateAdapters = listOf(
                    AssistantStateAdapterDefinition(
                        LegacyStateDialect.UPDATE_VARIABLE_SET_V1,
                        listOf(AssistantStateMapping("角色.好感度", "affection")),
                    ),
                    AssistantStateAdapterDefinition(
                        LegacyStateDialect.UPDATE_VARIABLE_JSON_PATCH_V1,
                        listOf(AssistantStateMapping("/角色/好感度", "affection")),
                    ),
                ),
            ),
        )

        assertTrue(result.issues.any { it.code == "TOO_MANY_STATE_ADAPTERS" })
    }

    private fun fixture() = NativeAdaptation(
        sourceSha256 = "a".repeat(64),
        state = listOf(
            ConversationStateDefinition("affection", type = ConversationStateValueType.NUMBER, initialValue = JsonPrimitive(0)),
            ConversationStateDefinition("location", type = ConversationStateValueType.STRING, initialValue = JsonPrimitive("beach")),
            ConversationStateDefinition(
                "inventory",
                type = ConversationStateValueType.COLLECTION,
                initialValue = JsonArray(emptyList()),
                fields = listOf(
                    ConversationStateFieldDefinition("name", type = ConversationStateScalarType.STRING),
                    ConversationStateFieldDefinition("quantity", type = ConversationStateScalarType.NUMBER),
                ),
            ),
        ),
        assistantStateAdapters = listOf(
            AssistantStateAdapterDefinition(
                LegacyStateDialect.UPDATE_VARIABLE_SET_V1,
                listOf(AssistantStateMapping("角色.好感度", "affection")),
            ),
        ),
        status = NativeStatusView(items = listOf(NativeStatusItem("affection", "好感度", 0.0, 100.0))),
        scenes = listOf(
            NativeSceneView(
                id = "current-scene",
                title = "当前场景",
                stateKey = "location",
                assets = listOf(NativeSceneAsset("beach", "asset-beach", "海滩")),
            ),
        ),
        collections = listOf(
            NativeCollectionView(
                id = "inventory",
                title = "背包",
                stateKey = "inventory",
                fields = listOf(NativeCollectionField("name", "物品"), NativeCollectionField("quantity", "数量")),
            ),
        ),
        forms = listOf(
            NativeFormView(
                id = "opening-form",
                title = "人物设定",
                marker = "<GAMESTART/>",
                fields = listOf(NativeFormField("name", NativeFormFieldType.TEXT, "名字", required = true)),
                draftTemplate = "{{user}}选择名字：{{form.name}}，准备与{{char}}开始。",
            ),
        ),
    )
}
