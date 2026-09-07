package io.github.zvensmoluya.tavernplayer.conversation.mvu

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuickJsMvuAndroidTest {
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
