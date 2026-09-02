package io.github.zvensmoluya.tavernplayer.presets

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zvensmoluya.tavernplayer.content.BuiltInPresets
import io.github.zvensmoluya.tavernplayer.content.PresetGenerationParameter
import io.github.zvensmoluya.tavernplayer.content.PresetGenerationSettings
import io.github.zvensmoluya.tavernplayer.content.PresetImporter
import java.io.File
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PresetRepositoryTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `import deduplicates content allocates names and persists complete source`() = runTest {
        val root = temporary.newFolder("preset-import")
        var importedId = 0
        val repository = PresetRepository(
            filesDir = root,
            importer = PresetImporter { "import-${importedId++}" },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        val firstBytes = presetJson(temperature = 0.7, secret = "do-not-store")
        val secondBytes = presetJson(temperature = 0.8, secret = "another-secret")

        val first = repository.importPreset(firstBytes, "Writer.json") as PresetLibraryImportResult.Saved
        val duplicate = repository.importPreset(firstBytes, "Copy.json") as PresetLibraryImportResult.Saved
        val second = repository.importPreset(secondBytes, "writer.JSON") as PresetLibraryImportResult.Saved

        assertFalse(first.duplicate)
        assertTrue(duplicate.duplicate)
        assertEquals(first.preset.id, duplicate.preset.id)
        assertEquals("Writer", first.preset.name)
        assertEquals("writer (2)", second.preset.name)
        assertEquals(3, repository.library.value.presets.size)
        val persisted = File(root, "tavern/presets/library.json").readText()
        assertTrue(persisted.contains("do-not-store"))
        assertTrue(persisted.contains("another-secret"))
        assertTrue(persisted.contains("api.example.com"))

        val restored = PresetRepository(
            filesDir = root,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        val exported = restored.exportPreset(second.preset.id).decodeToString()
        assertTrue(exported.contains("another-secret"))
        assertTrue(exported.contains("api.example.com"))
    }

    @Test
    fun `active preset survives restart and deleting it atomically falls back to default`() = runTest {
        val root = temporary.newFolder("preset-active")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = PresetRepository(root, ioDispatcher = dispatcher)
        val imported = repository.importPreset(
            presetJson(temperature = 0.4),
            "Focused.json",
        ) as PresetLibraryImportResult.Saved

        repository.save(
            imported.preset.copy(
                generationSettings = imported.preset.generationSettings
                    .withEnabled(PresetGenerationParameter.TEMPERATURE, false),
            ),
        )
        repository.activate(imported.preset.id)
        val restored = PresetRepository(root, ioDispatcher = dispatcher)
        assertEquals(imported.preset.id, restored.library.value.activePresetId)
        assertEquals(imported.preset.id, restored.captureActive().id)
        assertFalse(
            restored.captureActive().generationSettings.isEnabled(PresetGenerationParameter.TEMPERATURE),
        )

        restored.delete(imported.preset.id)
        assertEquals(BuiltInPresets.DEFAULT_ID, restored.library.value.activePresetId)
        assertEquals(BuiltInPresets.DEFAULT_ID, restored.activePreset.value.id)
        val restartedAfterDelete = PresetRepository(root, ioDispatcher = dispatcher)
        assertEquals(BuiltInPresets.DEFAULT_ID, restartedAfterDelete.captureActive().id)
    }

    @Test
    fun `built in preset is immutable but its copy can be explicitly edited`() = runTest {
        val root = temporary.newFolder("preset-copy")
        var copyId = 0
        val repository = PresetRepository(
            root,
            idFactory = { "copy-${copyId++}" },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val directEdit = runCatching {
            repository.save(BuiltInPresets.default.copy(name = "Changed"))
        }.exceptionOrNull()
        val directDelete = runCatching {
            repository.delete(BuiltInPresets.DEFAULT_ID)
        }.exceptionOrNull()
        assertTrue(directEdit is BuiltInPresetMutationException)
        assertTrue(directDelete is BuiltInPresetMutationException)

        val copied = repository.copy(BuiltInPresets.DEFAULT_ID)
        val edited = repository.save(
            copied.copy(
                name = "My Default",
                generationSettings = PresetGenerationSettings(maxOutputTokens = 2_048, temperature = 0.6),
            ),
        )
        assertFalse(edited.builtIn)
        assertNotEquals(BuiltInPresets.DEFAULT_ID, edited.id)
        assertEquals(2_048, edited.generationSettings.maxOutputTokens)
        assertNotEquals(copied.contentSha256, edited.contentSha256)

        val secondCopy = repository.copy(BuiltInPresets.DEFAULT_ID)
        val conflict = runCatching { repository.rename(secondCopy.id, "MY DEFAULT") }.exceptionOrNull()
        assertTrue(conflict is PresetNameConflictException)
    }

    private fun presetJson(temperature: Double, secret: String? = null): ByteArray = """
        {
          "temperature": $temperature,
          "openai_max_tokens": 512,
          "reverse_proxy": "https://api.example.com/v1",
          "proxy_password": ${secret?.let { "\"$it\"" } ?: "null"},
          "prompts": [
            {"identifier":"main","name":"Main","role":"system","content":"Reply naturally."},
            {"identifier":"chatHistory","name":"History","role":"system","marker":true}
          ],
          "prompt_order": [
            {"character_id":100001,"order":[
              {"identifier":"main","enabled":true},
              {"identifier":"chatHistory","enabled":true}
            ]}
          ]
        }
    """.trimIndent().encodeToByteArray()
}
