package io.github.zvensmoluya.tavernplayer.conversation.mvu

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import io.github.zvensmoluya.tavernplayer.conversation.ConversationRuntimeState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuickJsMvuAndroidTest {
    @Test fun dynamicHelperImportInitializesOnAndroid() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val bundle = instrumentation.targetContext.assets.open("mvu/runtime.js").bufferedReader().use { it.readText() }
        val original = MvuRuntimeContract.program(assets.open("mvu/state-card.json").bufferedReader().use { it.readText() })
        val prefix = """
            let registerMvuSchema;
            try { ({registerMvuSchema} = await import('https://cdn.jsdelivr.net/gh/StageDog/tavern_resource/dist/util/mvu_zod.js')); }
            catch (_) { ({registerMvuSchema} = await import('https://testingcf.jsdelivr.net/gh/StageDog/tavern_resource/dist/util/mvu_zod.js')); }
        """.trimIndent()
        val program = JsonObject(original + ("schemaScript" to JsonPrimitive(prefix + "\n" + original.getValue("schemaScript").jsonPrimitive.content)))
        val runtime = QuickJsMvuRuntime.create(bundle, program)
        try {
            val initial = runtime.initialize().messages.first()
            assertTrue((initial.state.data["stat_data"] as JsonObject).isNotEmpty())
            val next = runtime.update("A quiet moment passes.", initial.applyTo(ConversationRuntimeState())).messages.single()
            assertEquals(initial.state.data["stat_data"], next.state.data["stat_data"])
        } finally { runtime.close() }
    }

    @Test fun suppliedSchemaInitializesOnAndroid() = runBlocking {
        assumeTrue("Opt-in private regression fixture", InstrumentationRegistry.getArguments().getString("schemaRegression") == "1")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val program = MvuRuntimeContract.program(File(context.cacheDir, "mvu-schema-regression.json").readText())
        val bundle = context.assets.open("mvu/runtime.js").bufferedReader().use { it.readText() }
        val runtime = QuickJsMvuRuntime.create(bundle, program)
        try {
            val initialized = runtime.initialize()
            assertTrue(initialized.messages.isNotEmpty())
            val first = initialized.messages.first()
            assertTrue((first.state.data["stat_data"] as JsonObject).isNotEmpty())
            val updated = runtime.update("A quiet moment passes.", first.applyTo(ConversationRuntimeState()))
            assertEquals(first.state.data["stat_data"], updated.messages.single().state.data["stat_data"])
        } finally { runtime.close() }
    }

    @Test fun originalSampleRunsOnAndroidQuickJs() = runBlocking {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        assumeTrue("Optional audited C-04 fixture is not prepared", assets.list("mvu")?.contains("c04-program.json") == true)
        val bundle = assets.open("mvu/runtime.js").bufferedReader().use { it.readText() }
        val program = MvuRuntimeContract.program(assets.open("mvu/c04-program.json").bufferedReader().use { it.readText() })
        MvuRuntimeContract.verifyOriginalSample(bundle, program)
    }

    @Test fun upstreamRunsOnAndroidAndPersistsConversationCheckpoints() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        assumeTrue("Prepare the local MVU bundle first", assets.list("mvu")?.contains("runtime.js") == true)
        val bundle = assets.open("mvu/runtime.js").bufferedReader().use { it.readText() }
        val program = MvuRuntimeContract.program(assets.open("mvu/state-card.json").bufferedReader().use { it.readText() })
        val root = File(instrumentation.targetContext.cacheDir, "mvu-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            val metrics = MvuRuntimeContract.verify(bundle, program, root)
            Log.i("MvuQuickJs", "MVU_ANDROID_METRICS=$metrics")
            Unit
        } finally { root.deleteRecursively() }
    }
}
