package io.github.zvensmoluya.tavernplayer.conversation.reading

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zvensmoluya.tavernplayer.conversation.*
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingExportAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val title = "别开门"
    private val state = ChatUiState(conversationId = "sample", character = DemoConversationContent.character.snapshot(),
        messages = listOf(
            entry("1", MessageRole.ASSISTANT, "雨落在窗沿。"),
            entry("2", MessageRole.USER, "我把信放在桌上。"),
            entry("3", MessageRole.ASSISTANT, "她摇摇头。\n\n**原话**。").copy(displayChanges = mapOf("原话" to "别回头")),
            entry("4", MessageRole.SYSTEM, "系统提示"),
            entry("5", MessageRole.USER, "隐藏发言").copy(hidden = true),
        ))

    private fun entry(id: String, role: MessageRole, display: String) = ChatMessageState(
        ConversationMessage(id, role, "内部原文", if (role == MessageRole.USER) "旅人" else "守灯人"),
        displayContent = display, displayReasoning = listOf("内部思考"))

    @Test fun exportedFileContainsOnlyPreviewAndCanBeReadThroughContentUri() = runBlocking {
        val book = prepareReadingBook(context, title, "守灯人", state.messages)
        assertEquals(3, book.entries.size)
        assertEquals("她摇摇头。\n\n别回头。", book.entries.last().plainText())
        val file = book.cachedEpub(context)
        try {
            ZipFile(file).use { zip ->
                assertEquals("mimetype", zip.entries().nextElement().name)
                val body = zip.getInputStream(zip.getEntry("EPUB/part0.xhtml")).bufferedReader().readText()
                assertTrue(body.contains("<strong>别回头</strong>"))
                assertFalse(listOf("内部原文", "内部思考", "系统提示", "隐藏发言").any(body::contains))
            }
            val intent = readerIntent(context, file, book.filename())
            assertEquals(EPUB_MIME, intent.type)
            assertEquals("content", intent.data!!.scheme)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertArrayEquals(file.readBytes(), context.contentResolver.openInputStream(intent.data!!)!!.use { it.readBytes() })
            val options = InstrumentationRegistry.getArguments()
            if (options.getString("keepReadingSample") == "true")
                file.copyTo(File(context.cacheDir, "reading-sample.epub"), overwrite = true)
            options.getString("readingSampleViewer")?.let { viewer ->
                val copy = book.cachedEpub(context)
                val open = readerIntent(context, copy, book.filename()).setPackage(viewer)
                compose.runOnIdle { compose.activity.startActivity(open) }
            }
        } finally { file.delete() }
        Unit
    }

    @Test fun menuRangeCopyAndSaveUseTheSameSelection() {
        val target = File(context.cacheDir, "reading").apply { mkdirs() }.let { File.createTempFile("test-", ".epub", it) }
        val destination: Uri = readerIntent(context, target, "sample.epub").data!!
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                    assertEquals("别开门.epub", input)
                    dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(destination))
                }
            }
        }
        try {
            compose.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    ChatScreen(state, ChatScreenActions({}, {}, {}, {}, {}, {}, {}), conversationTitle = title)
                }
            }
            compose.onNodeWithTag("chatMore").performClick()
            compose.onNodeWithText("导出阅读稿").performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithText("选择范围").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("选择范围").performClick()
            compose.onNodeWithText("2 · 旅人").performClick()
            compose.onNodeWithText("3 · 守灯人").performClick()
            compose.onNodeWithText("第 2–3 条").assertIsDisplayed()
            compose.onNodeWithText("复制选段").performClick()
            val clip = compose.runOnIdle { context.getSystemService(ClipboardManager::class.java).primaryClip }
            assertEquals("旅人\n我把信放在桌上。\n\n守灯人\n她摇摇头。\n\n别回头。", clip!!.getItemAt(0).text.toString())
            compose.onNodeWithText("保存电子书").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("电子书已保存").fetchSemanticsNodes().isNotEmpty() }
            ZipFile(target).use { zip ->
                val body = zip.getInputStream(zip.getEntry("EPUB/part0.xhtml")).bufferedReader().readText()
                assertTrue("我把信放在桌上。" in body && "<strong>别回头</strong>" in body)
                assertFalse("雨落在窗沿" in body)
            }
        } finally { target.delete() }
    }
}
