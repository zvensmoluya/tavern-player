package io.github.zvensmoluya.tavernplayer.conversation.script

import io.github.zvensmoluya.tavernplayer.content.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class QuickJsNativeRuntimeTest {
    private val empty = JsonObject(emptyMap())
    private fun program(code: String, capabilities: Set<NativeScriptCapability> = emptySet()) = NativeScriptProgram(
        modules = listOf(NativeScriptModule("main", code, listOf("fixture"), "Synthetic behavior test")),
        surfaces = listOf(NativeSurfaceEntry("shop", "main", "present", NativeSurfaceType.COLLECTION)),
        handlers = listOf(NativeHandlerEntry("buy", "main", "buy")), capabilities = capabilities,
    )
    private val code = """
        export function present(c) {
          return {surface:'collection',title:'Shop',items:Object.entries(c.state.items).map(([key,v]) =>
            ({key,title:v.name,description:v.description || '',status:v.owned?'Owned':'Available',
              actions:v.owned?[]:[{id:'buy',label:'Buy',handler:'buy',args:{itemId:key}}]}))};
        }
        export async function buy(c,args) {
          const current = await c.variables.read();
          if (current.items[args.itemId].owned) return;
          const text = await c.generation.text({prompt:args.itemId});
          current.items[args.itemId].owned = true;
          current.items[args.itemId].description = text;
          await c.program.replace(current);
        }
    """.trimIndent()

    @Test fun modulesAndDynamicSurfaceRunOnActualQuickJsWithAsyncHost() = runBlocking {
        val p = program(code, setOf(NativeScriptCapability.VARIABLES_READ, NativeScriptCapability.GENERATE_TEXT, NativeScriptCapability.PROGRAM_STATE_REPLACE))
        var state = Json.parseToJsonElement("""{"items":{"a":{"name":"A","owned":false},"b":{"name":"B","owned":true}}}""").jsonObject
        val runtime = QuickJsNativeRuntime()
        runtime.validate(p)
        val first = runtime.present(p, buildJsonObject { put("state", state) }, "r0").single()
        assertEquals(2, first.data.items.size)
        assertTrue(first.data.items[1].actions.isEmpty())
        val calls = mutableListOf<String>()
        runtime.invoke(p, "buy", empty, buildJsonObject { put("itemId", "a") }, emptyMap()) { method, value ->
            calls += method
            when (method) {
                "variables.read" -> state
                "generation.text" -> { delay(20); JsonPrimitive("Generated description") }
                "program.replace" -> { state = value.jsonObject; JsonNull }
                else -> error(method)
            }
        }
        assertEquals(listOf("variables.read", "generation.text", "program.replace"), calls)
        val after = runtime.present(p, buildJsonObject { put("state", state) }, "r1").single()
        assertTrue(after.data.items.all { it.actions.isEmpty() })
        assertEquals("Generated description", after.data.items.first().description)
    }

    @Test fun moduleImportsResolveLocallyAndUnknownDependenciesFailBeforeEffects() = runBlocking {
        val p = program("import {title} from 'helpers'; export function present(){return {surface:'collection',title};} export function buy() {}")
            .let { it.copy(modules = it.modules + NativeScriptModule("helpers", "export const title='Local';", listOf("fixture"), "Helper")) }
        assertEquals("Local", QuickJsNativeRuntime().present(p, empty, "r").single().data.title)
        expectFailure { QuickJsNativeRuntime().validate(p.copy(modules = p.modules.take(1))) }
        expectFailure { QuickJsNativeRuntime().validate(program("export function present() {}")) }
    }

    @Test fun runtimeFormsAcceptStringOptionsAndRejectFixedFormFields() = runBlocking {
        fun form(field: String) = program("export function present(){return {surface:'form',title:'Input',fields:[$field]};} export function buy() {}")
            .copy(surfaces = listOf(NativeSurfaceEntry("input", "main", "present", NativeSurfaceType.FORM)))
        val runtime = QuickJsNativeRuntime()
        val valid = runtime.present(form("{id:'mode',label:'Mode',value:'A',required:true,options:['A','B']}"), empty, "r").single().data.fields.single()
        assertEquals("A", valid.value)
        assertEquals(listOf("A", "B"), valid.options)
        for (invalid in listOf("type:'TEXT'", "initialValues:['A']", "placeholder:'hint'", "options:[{value:'A',label:'A'}]", "value:1"))
            expectFailure { runtime.present(form("{id:'mode',label:'Mode',$invalid}"), empty, "invalid") }
    }

    @Test fun noTopLevelOrProjectionEffectsAndNoComponentTreeEscape() = runBlocking {
        val runtime = QuickJsNativeRuntime()
        for (body in listOf(
            "__nativeCall('draft.replace','\"bad\"'); export function present(){} export function buy(){}",
            "export function present(c){c.state.x=2;} export function buy(){}",
            "export function present(){return {surface:'collection',title:'x',children:[{type:'row'}]};} export function buy(){}",
            "export function present(){return {surface:'collection',title:'x',actions:[{id:'x',label:'x',handler:'missing'}]};} export function buy(){}",
        )) expectFailure { runtime.present(program(body), buildJsonObject { put("state", empty) }, "r") }
        var calls = 0
        expectFailure { runtime.invoke(program("export function present(){} export async function buy(c){await c.draft.replace('bad');}"),
            "buy", empty, empty, emptyMap()) { _, _ -> calls++; JsonNull } }
        assertEquals(0, calls)
    }

    @Test fun failedOrCancelledGenerationCannotReachLaterWriteAndNextCallIsFresh() = runBlocking {
        val p = program(code, NativeScriptCapability.entries.toSet())
        val state = Json.parseToJsonElement("""{"items":{"a":{"name":"A","owned":false}}}""")
        val runtime = QuickJsNativeRuntime(timeoutMillis = 200, actionTimeoutMillis = 3000)
        var writes = 0
        val waiting = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            runtime.invoke(p, "buy", empty, buildJsonObject { put("itemId", "a") }, emptyMap()) { method, _ ->
                when (method) {
                    "variables.read" -> state
                    "generation.text" -> { waiting.complete(Unit); awaitCancellation() }
                    else -> { writes++; JsonNull }
                }
            }
        }
        withTimeout(5000) { waiting.await(); job.cancelAndJoin() }
        assertEquals(0, writes)
        expectFailure { runtime.invoke(p, "buy", empty, buildJsonObject { put("itemId", "a") }, emptyMap()) { method, _ ->
            if (method == "variables.read") state else error("Network failed")
        } }
        expectFailure { runtime.present(program("export function present(){while(true){}} export function buy(){}"), empty, "r") }
        assertEquals(1, runtime.present(p, buildJsonObject { put("state", state) }, "r").size)
    }

    private suspend fun expectFailure(block: suspend () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: Exception) { }
    }
}
