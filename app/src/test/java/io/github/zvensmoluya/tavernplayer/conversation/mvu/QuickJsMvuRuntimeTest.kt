package io.github.zvensmoluya.tavernplayer.conversation.mvu

import com.dokar.quickjs.QuickJsInterruptedException
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QuickJsMvuRuntimeTest {
    @get:Rule val temporary = TemporaryFolder()
    private val assets get() = File(checkNotNull(System.getProperty("mvuProbeAssets")), "mvu")
    private fun bundle() = File(assets, "runtime.js").also {
        check(it.isFile) { "Run npm --prefix tools/mvu-probe run build before MVU tests" }
    }.readText()
    private fun program() = MvuRuntimeContract.program(File(assets, "state-card.json").readText())

    @Before fun requireLocalBundle() {
        assumeTrue("Optional MVU checks require npm --prefix tools/mvu-probe run build", File(assets, "runtime.js").isFile)
    }

    @Test fun upstreamRunsOnQuickJsAndPersistsRealConversationCheckpoints() = runBlocking {
        val metrics = MvuRuntimeContract.verify(bundle(), program(), temporary.newFolder())
        println("MVU_DESKTOP_METRICS=$metrics")
    }

    @Test fun lowercaseSchemaExportRunsAndUpdatesCheckpoints() = runBlocking {
        val original = program()
        val script = original["schemaScript"]!!.jsonPrimitive.content.replace(Regex("\\bSchema\\b"), "schema")
        val renamed = JsonObject(original + ("schemaScript" to JsonPrimitive(
            "// export const Schema is only an example in a comment.\n" + script,
        )))
        MvuRuntimeContract.verify(bundle(), renamed, temporary.newFolder())
        Unit
    }

    @Test fun runawayEventIsInterruptedAndRuntimeCannotBeReused() = runBlocking {
        val original = program()
        val looping = JsonObject(original + ("schemaScript" to JsonPrimitive(
            original["schemaScript"]!!.jsonPrimitive.content +
                ";eventOn('mag_variable_update_started', () => { while (true) {} });",
        )))
        val runtime = QuickJsMvuRuntime.create(bundle(), looping, evaluationTimeoutMillis = 100)
        try {
            try { runtime.initialize(); fail("Expected QuickJS interruption") }
            catch (_: QuickJsInterruptedException) { }
            catch (_: TimeoutCancellationException) { }
            try { runtime.initialize(); fail("Failed runtime must not be reused") }
            catch (_: IllegalStateException) { }
        } finally { runtime.close() }
    }

    @Test fun originalSampleRunsOnQuickJs() = runBlocking {
        val file = File(assets, "c04-program.json")
        assumeTrue("Optional audited C-04 fixture is not prepared", file.isFile)
        MvuRuntimeContract.verifyOriginalSample(bundle(), MvuRuntimeContract.program(file.readText()))
    }

    @Test fun unresolvedEventPromiseIsCancelled() = runBlocking {
        val original = program()
        val stalled = JsonObject(original + ("schemaScript" to JsonPrimitive(
            original["schemaScript"]!!.jsonPrimitive.content +
                ";eventOn('mag_variable_update_started', () => new Promise(() => {}));",
        )))
        val runtime = QuickJsMvuRuntime.create(bundle(), stalled, evaluationTimeoutMillis = 200)
        try {
            try { runtime.initialize(); fail("Expected event promise to time out") }
            catch (_: TimeoutCancellationException) { }
        } finally { runtime.close() }
    }
}
