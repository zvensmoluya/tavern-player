package io.github.zvensmoluya.tavernplayer.conversation.script

import com.dokar.quickjs.QuickJs
import io.github.zvensmoluya.tavernplayer.content.*
import io.github.zvensmoluya.tavernplayer.conversation.nativeHash
import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test

/** Optional local C-04 differential check. This is a hand-authored projection, NOT a model result. */
class NativeSurfaceCommunityTest {
    @Test fun originalInventoryLoopAndNativeCollectionAgreeOnDynamicRowsAndFallbacks() = runBlocking {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val hash = "fa7e8ec564887780b331d0da29f7966f58f3688d49e6faf587d2d80ae9aecefe"
        val source = File(root, "source").walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("png", "json") }
            .firstOrNull { file -> java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) } == hash }
        assumeNotNull(source)
        val card = (CharacterCardImporter().import(source!!.readBytes(), "sample-c04.png") as CharacterImportResult.Ready).character
        val html = card.regexScripts.single { "const inventory = data[" in it.replaceString }.replaceString
        val loop = html.substring(html.indexOf("const inventory = data["), html.indexOf("const wardrobe = data["))
        val program = NativeScriptProgram(modules = listOf(NativeScriptModule("inventory", """
            export function present(c) {
              const inventory = c.state.stat_data['物品栏'] || {};
              return {surface:'collection',title:'背包',items:Object.entries(inventory).map(([key,v]) =>
                ({key,title:key,description:v['描述'] || '暂无描述',status:'x'+(v['数量'] || 1)}))};
            }
        """.trimIndent(), listOf("regex1"), "Manual differential fixture: retain source inventory loop semantics")),
            surfaces = listOf(NativeSurfaceEntry("inventory", "inventory", "present", NativeSurfaceType.COLLECTION)))
        val cases = listOf("{}", """{"甲":{"数量":0,"描述":""},"乙":{"数量":2,"描述":"中性说明"}}""", """{"乙":{"数量":3,"描述":"更新说明"}}""")
        val js = QuickJs.create(Dispatchers.Default)
        try {
            js.evaluationTimeoutMillis = 2_000
            js.memoryLimit = 32L * 1024 * 1024
            js.evaluate<Any?>("""
                globalThis.container = {innerHTML:''};
                globalThis.document = {getElementById:()=>container};
                globalThis.original = function(data) { $loop return container.innerHTML; };
            """.trimIndent())
            cases.forEachIndexed { index, inventory ->
                val data = buildJsonObject { put("物品栏", Json.parseToJsonElement(inventory)) }
                val original = js.evaluate<String>("original(JSON.parse(${JsonPrimitive(data.toString())}))")
                val projected = QuickJsNativeRuntime().present(program,
                    buildJsonObject { put("state", buildJsonObject { put("stat_data", data) }) }, "case-$index").single().data
                assertEquals(data.getValue("物品栏").jsonObject.size, projected.items.size)
                projected.items.forEach { item ->
                    assertTrue(original.contains(">${item.title}</span>"))
                    assertTrue(original.contains(">${item.status}</span>"))
                    assertTrue(original.contains(">${item.description}</div>"))
                }
            }
            val report = File(root, "app/build/native-surface-community-audit.json")
            report.writeText(buildJsonObject {
                put("sample", "C-04"); put("sourceSha256", hash); put("sourceLoopSha256", nativeHash(loop))
                put("mode", "MANUAL_DIFFERENTIAL"); put("cases", cases.size)
                put("scope", "Inventory rows, source zero/empty fallback, add/remove/change; no model or full browser execution")
            }.toString())
        } finally { js.close() }
    }
}
