package io.github.zvensmoluya.tavernplayer.connections

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreCredentialStoreTest {
    @Test
    fun writeReadOverwriteDeleteAndCorruption() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = AndroidKeystoreCredentialStore(context)
        val id = "instrumented_${System.nanoTime()}"
        try {
            store.put(id, "first-secret")
            assertEquals("first-secret", store.getOrNull(id))
            assertTrue(store.contains(id))

            store.put(id, "second-secret")
            assertEquals("second-secret", store.getOrNull(id))

            val file = File(context.noBackupFilesDir, "model_gateway/credentials/$id.bin")
            file.writeBytes(byteArrayOf(1, 2, 3))
            val corrupted = runCatching { store.getOrNull(id) }.exceptionOrNull()
            assertTrue(corrupted is CredentialUnavailableException)

            store.delete(id)
            assertFalse(store.contains(id))
            assertNull(store.getOrNull(id))
        } finally {
            store.delete(id)
        }
    }
}
