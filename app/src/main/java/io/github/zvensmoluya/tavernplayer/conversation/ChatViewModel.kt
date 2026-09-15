package io.github.zvensmoluya.tavernplayer.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.zvensmoluya.tavernplayer.connections.ConnectionRepository
import io.github.zvensmoluya.tavernplayer.content.CharacterAsset
import io.github.zvensmoluya.tavernplayer.presets.ActivePresetSource
import io.github.zvensmoluya.tavernplayer.conversation.mvu.MvuConversationRuntime
import io.github.zvensmoluya.tavernplayer.conversation.ejs.QuickJsEjsRuntime
import io.github.zvensmoluya.tavernplayer.conversation.script.QuickJsNativeRuntime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/** Lifecycle and UI adapter. ConversationSession is the sole owner of mutable conversation state. */
class ChatViewModel(
    repository: ConnectionRepository,
    compiler: PromptCompiler,
    generator: ConversationGenerator,
    conversationRepository: ConversationRepository? = null,
    presetSource: ActivePresetSource,
    characterAsset: CharacterAsset = DemoConversationContent.character,
    persona: Persona = DemoConversationContent.persona,
    idGenerator: () -> String = { UUID.randomUUID().toString() },
    now: () -> Long = System::currentTimeMillis,
    projectionDispatcher: CoroutineDispatcher = Dispatchers.Default,
    adaptationRuntime: NativeAdaptationRuntime = NativeAdaptationRuntime(),
    mvuRuntime: MvuConversationRuntime = MvuConversationRuntime(),
    ejsRuntime: QuickJsEjsRuntime = QuickJsEjsRuntime(),
    nativeScriptRuntime: QuickJsNativeRuntime = QuickJsNativeRuntime(),
    val browserEnvironment: io.github.zvensmoluya.tavernplayer.conversation.web.BrowserEnvironment? = null,
    globalWorldBooks: suspend () -> GlobalWorldBookSnapshot = { GlobalWorldBookSnapshot() },
) : ViewModel() {
    private val session = ConversationSession(repository, compiler, generator, conversationRepository, presetSource,
        characterAsset, persona, idGenerator, now, projectionDispatcher, adaptationRuntime, mvuRuntime, ejsRuntime,
        nativeScriptRuntime, browserEnvironment, globalWorldBooks)
    val uiState = session.uiState
    fun loadConversation(conversationId: String) = session.loadConversation(conversationId)
    fun updateInput(value: String) = session.updateInput(value)
    fun setWorldBookEntryMode(bookId: String, entryId: String, mode: WorldBookEntryMode?) = session.setWorldBookEntryMode(bookId, entryId, mode)
    fun setWorldBookEntryContent(bookId: String, entryId: String, content: String, onSaved: () -> Unit = {}) = session.setWorldBookEntryContent(bookId, entryId, content, onSaved)
    fun invokeNativeAction(invocation: NativeSurfaceInvocation) = session.invokeNativeAction(invocation)
    fun cancelNativeAction() = session.cancelNativeAction()
    fun submitNativeForm(formId: String, values: Map<String, List<String>>) = session.submitNativeForm(formId, values)
    fun previewPlayerChoice(choiceId: String) = session.previewPlayerChoice(choiceId)
    fun cancelPlayerChoice() = session.cancelPlayerChoice()
    fun confirmPlayerChoice() = session.confirmPlayerChoice()
    fun send() = session.send()
    fun retry() = session.retry()
    fun regenerate() = session.regenerate()
    fun editMessage(messageId: String, sourceText: String, mode: MessageEditMode) = session.editMessage(messageId, sourceText, mode)
    fun previousVariant() = session.previousVariant()
    fun nextVariant() = session.nextVariant()
    fun selectOpening(sourceIndex: Int) = session.selectOpening(sourceIndex)
    fun cancel() = session.cancel()
    suspend fun invokeBrowser(actor: BrowserActor, revision: String, method: String, args: JsonObject) = session.invokeBrowser(actor, revision, method, args)
    fun selectConnection(connectionId: String) = session.selectConnection(connectionId)
    fun resetConversation() = session.resetConversation()
    fun refreshMemories() = session.refreshMemories()
    fun retrySave() = session.retrySave()
    override fun onCleared() { session.close() }
    class Factory(
        private val repository: ConnectionRepository,
        private val compiler: PromptCompiler,
        private val generator: ConversationGenerator,
        private val conversationRepository: ConversationRepository? = null,
        private val presetSource: ActivePresetSource,
        private val mvuRuntime: MvuConversationRuntime = MvuConversationRuntime(),
        private val ejsRuntime: QuickJsEjsRuntime = QuickJsEjsRuntime(),
        private val browserEnvironment: io.github.zvensmoluya.tavernplayer.conversation.web.BrowserEnvironment? = null,
        private val nativeScriptRuntime: QuickJsNativeRuntime = QuickJsNativeRuntime(),
        private val globalWorldBooks: suspend () -> GlobalWorldBookSnapshot = { GlobalWorldBookSnapshot() },
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ChatViewModel(repository, compiler, generator, conversationRepository, presetSource, mvuRuntime = mvuRuntime, ejsRuntime = ejsRuntime, nativeScriptRuntime = nativeScriptRuntime, browserEnvironment = browserEnvironment, globalWorldBooks = globalWorldBooks) as T
    }

}
