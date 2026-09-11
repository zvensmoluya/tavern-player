package io.github.zvensmoluya.tavernplayer.personas

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PersonaRepositoryTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `saved default persona is restored with a stable identity`() = runTest {
        val root = temporary.newFolder("persona")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = PersonaRepository(root, dispatcher)

        repository.save("  小舟  ", "喜欢雨夜。", "content://portrait")
        val restored = PersonaRepository(root, dispatcher).persona.value

        assertEquals(PersonaRepository.DEFAULT_ID, restored.id)
        assertEquals("小舟", restored.name)
        assertEquals("喜欢雨夜。", restored.description)
        assertEquals("content://portrait", restored.avatar)
    }

    @Test
    fun `persona storage can initialize after construction`() = runTest {
        val root = temporary.newFolder("persona-lazy")
        val dispatcher = StandardTestDispatcher(testScheduler)
        PersonaRepository(root, dispatcher).save("旧身份", "已保存", null)

        val deferred = PersonaRepository(root, dispatcher, loadOnInit = false)
        assertEquals("旅人", deferred.persona.value.name)

        deferred.initialize()

        assertEquals("旧身份", deferred.persona.value.name)
        assertEquals("已保存", deferred.persona.value.description)
    }

    @Test
    fun `blank name is rejected without replacing saved persona`() = runTest {
        val root = temporary.newFolder("persona-invalid")
        val repository = PersonaRepository(root, StandardTestDispatcher(testScheduler))
        repository.save("旅人", "原来的描述", null)

        val failure = runCatching { repository.save("   ", "新描述", "") }.exceptionOrNull()

        assertEquals("身份名称不能为空", failure?.message)
        assertEquals("旅人", repository.persona.value.name)
        assertEquals("原来的描述", repository.persona.value.description)
        assertNull(repository.persona.value.avatar)
    }
}
