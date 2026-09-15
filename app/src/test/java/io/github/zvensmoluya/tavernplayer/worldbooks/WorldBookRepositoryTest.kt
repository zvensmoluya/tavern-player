package io.github.zvensmoluya.tavernplayer.worldbooks

import io.github.zvensmoluya.tavernplayer.conversation.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorldBookRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = """{"unknown":3,"entries":{"0":{"uid":0,"key":["gate"],"content":"original","disable":true}}}""".toByteArray()
    @Test fun `import does not enable and saved capture stays immutable across edits and restart`() = runTest {
        val repository = WorldBookRepository(temporary.root)
        val asset = repository.import(bytes, "sample.json")
        assertTrue(repository.capture().books.isEmpty())
        repository.setEnabled(asset.book.id, true)
        repository.setMode(asset.book.id, asset.book.entries.single().id, WorldBookEntryMode.FORCED)
        val before = repository.capture()
        repository.setContent(asset.book.id, asset.book.entries.single().id, "changed")
        assertNull(before.overrides[asset.book.id]?.get(asset.book.entries.single().id)?.content)
        val restored = WorldBookRepository(temporary.root)
        assertEquals("changed", restored.capture().overrides[asset.book.id]?.get(asset.book.entries.single().id)?.content)
        assertEquals(WorldBookEntryMode.FORCED, restored.capture().overrides[asset.book.id]?.get(asset.book.entries.single().id)?.mode)
        repository.import(bytes, "again.json")
        assertEquals(1, repository.library.value.size)
        repository.delete(asset.book.id)
        assertTrue(repository.capture().books.isEmpty())
        assertEquals(1, before.books.size)
    }
    @Test fun `export reimport preserves unknown source and force without misusing constant`() = runTest {
        val repository = WorldBookRepository(temporary.root)
        val asset = repository.import(bytes, "sample.json")
        val id = asset.book.entries.single().id
        repository.setMode(asset.book.id, id, WorldBookEntryMode.FORCED)
        repository.setContent(asset.book.id, id, "edited")
        val exported = repository.export(repository.library.value.single())
        assertTrue(exported.contains("\"unknown\":3"))
        val imported = repository.import(exported.toByteArray(), "copy.json")
        assertEquals("edited", imported.book.entries.single().content)
        assertFalse(imported.book.entries.single().constant)
        assertEquals(WorldBookEntryMode.FORCED, imported.state.entryMode(imported.book.id, imported.book.entries.single()))
        repository.setContent(asset.book.id, id, "original")
        repository.setMode(asset.book.id, id, null)
        val original = repository.library.value.first()
        assertNull(original.overrides[id]?.content)
        assertEquals(WorldBookEntryMode.DISABLED, original.state.entryMode(original.book.id, original.book.entries.single()))
    }
    @Test fun `corrupt storage is preserved and cannot be silently overwritten`() = runTest {
        val file = java.io.File(temporary.root, "tavern/worldbooks/library.json")
        requireNotNull(file.parentFile).mkdirs(); file.writeText("broken")
        val repository = WorldBookRepository(temporary.root)
        try { repository.import(bytes, "sample.json"); fail("Must reject corrupt storage") } catch (_: IllegalArgumentException) { }
        assertEquals("broken", file.readText())
    }
}
