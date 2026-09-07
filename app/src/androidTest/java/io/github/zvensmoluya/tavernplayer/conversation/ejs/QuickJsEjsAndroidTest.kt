package io.github.zvensmoluya.tavernplayer.conversation.ejs

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test

class QuickJsEjsAndroidTest {
    @Test fun promptTemplatesExecuteOnAndroidJni() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bundle = context.assets.open("ejs/runtime.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
        EjsRuntimeContract.verify(bundle)
    }
}
