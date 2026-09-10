package io.github.zvensmoluya.tavernplayer.conversation

import io.github.zvensmoluya.tavernplayer.content.CharacterAsset
import org.junit.Assert.*
import org.junit.Test
import org.yaml.snakeyaml.Yaml

class MessageVariableFormatterTest {
    private val macro = "{{format_message_variable::stat_data}}"
    private fun expand(text: String, json: String) = MacroEngine().evaluate(text,
        MacroContext(CharacterAsset(id = "sample", name = "Sample").snapshot(), Persona("p", "旅人"), legacyStateJson = json), MacroTransaction())

    @Test fun formattedReadUsesYamlWhileGetKeepsJsonAndBothOmitPrivateFields() {
        val json = """{"score":3,"world":{"place":"酒馆","${'$'}meta":1},"items":[{"name":"钥匙","${'$'}private":true}],"${'$'}schema":{}}"""
        val expected = "score: 3\nworld:\n  place: 酒馆\nitems:\n  - name: 钥匙"
        assertEquals(expected, expand(macro, json).text)
        assertEquals("""{"score":3,"world":{"place":"酒馆"},"items":[{"name":"钥匙"}]}""",
            expand("{{get_message_variable::stat_data}}", json).text)
        assertTrue(expand(macro, json).diagnostics.isEmpty())
    }

    @Test fun eachFormattedMacroIndentsContinuationFromItsExpandedLinePrefix() {
        val json = """{"a":1,"b":2}"""
        assertEquals("  a: 1\n  b: 2", expand("  $macro", json).text)
        assertEquals("a: 1\nb: 2 / a: 1\n            b: 2", expand("$macro / $macro", json).text)
        assertEquals("旅人: a: 1\n    b: 2", expand("{{user}}: $macro", json).text)
        assertEquals("a: 1\nb: 2\na: 1\nb: 2", expand("$macro\n$macro", json).text)
    }

    @Test fun yamlPreservesScalarTypesSpecialStringsAndMultilineContent() {
        val json = """{"numeric":"001","boolean":"true","nil":"null","empty":"","colon":"a: b","hash":"#tag","text":"first\nsecond\n","unicode":"旅人 😀","control":"\u0001","number":123456789012345678901234567890,"flag":true,"none":null,"list":[],"map":{}}"""
        val rendered = MessageVariableFormatter.format(json, yaml = true)
        val parsed = Yaml().load<Map<String, Any?>>(rendered)
        assertEquals("001", parsed["numeric"])
        assertEquals("true", parsed["boolean"])
        assertEquals("null", parsed["nil"])
        assertEquals("", parsed["empty"])
        assertEquals("a: b", parsed["colon"])
        assertEquals("#tag", parsed["hash"])
        assertEquals("first\nsecond\n", parsed["text"])
        assertTrue(rendered.contains("text: |\n"))
        assertEquals("旅人 😀", parsed["unicode"])
        assertEquals("\u0001", parsed["control"])
        assertEquals("123456789012345678901234567890", parsed["number"].toString())
        assertEquals(true, parsed["flag"])
        assertNull(parsed["none"])
        assertEquals(emptyList<Any>(), parsed["list"])
        assertEquals(emptyMap<String, Any>(), parsed["map"])
    }

    @Test fun rootStringsAndEmptyContainersFollowTheVariableReadContract() {
        for (yaml in listOf(false, true)) {
            assertEquals("a\nb", MessageVariableFormatter.format("\"a\\nb\"", yaml))
            assertEquals("null", MessageVariableFormatter.format("null", yaml))
            assertEquals("{}", MessageVariableFormatter.format("{}", yaml))
            assertEquals("[]", MessageVariableFormatter.format("[]", yaml))
        }
    }

    @Test fun nestedPathsReadObjectsArraysAndScalarsWithoutLeakingPrivateFields() {
        val state = """{"world":{"time":"Day 2","${'$'}secret":9},"items":[{"name":"key"}],"world.time":"literal"}"""
        assertEquals("literal", expand("{{get_message_variable::stat_data.world.time}}", state).text)
        assertEquals("key", expand("{{format_message_variable::stat_data.items[0].name}}", state).text)
        assertEquals("time: Day 2", expand("{{format_message_variable::stat_data.world}}", state).text)
        assertEquals("null", expand("{{get_message_variable::stat_data.world.${'$'}secret}}", state).text)
        assertEquals("null", expand("{{format_message_variable::stat_data.missing}}", state).text)
    }
}
