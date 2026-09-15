package io.github.zvensmoluya.tavernplayer.app

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import io.github.zvensmoluya.modelgateway.ModelGateway
import io.github.zvensmoluya.tavernplayer.characters.CharacterRepository
import io.github.zvensmoluya.tavernplayer.connections.AndroidKeystoreCredentialStore
import io.github.zvensmoluya.tavernplayer.connections.ConnectionRepository
import io.github.zvensmoluya.tavernplayer.connections.JsonConnectionDataStore
import io.github.zvensmoluya.tavernplayer.connections.ProbeService
import io.github.zvensmoluya.tavernplayer.conversation.ConversationRepository
import io.github.zvensmoluya.tavernplayer.conversation.ModelGatewayConversationGenerator
import io.github.zvensmoluya.tavernplayer.conversation.PromptCompiler
import io.github.zvensmoluya.tavernplayer.conversation.StateConfirmingConversationGenerator
import io.github.zvensmoluya.tavernplayer.personas.PersonaRepository
import io.github.zvensmoluya.tavernplayer.presets.PresetRepository
import io.github.zvensmoluya.tavernplayer.transfer.ShelfTransferClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

private val Context.gatewayDataStore by preferencesDataStore(name = "model_gateway_connections")

class AppGraph(context: Context) {
    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val credentialStore by lazy { AndroidKeystoreCredentialStore(appContext) }
    val gateway by lazy {
        ModelGateway(credentialStore, io.github.zvensmoluya.tavernplayer.connections.PlayerModelHttpClient.create())
    }
    val connectionRepository by lazy {
        ConnectionRepository(
            stateStore = JsonConnectionDataStore(appContext.gatewayDataStore, appScope),
            credentialStore = credentialStore,
            catalogLoader = { gateway.modelCatalog.list(it) },
        )
    }
    val probeService by lazy { ProbeService(gateway, connectionRepository) }
    val promptCompiler by lazy { PromptCompiler() }
    val personaRepository by lazy { PersonaRepository(appContext.filesDir, loadOnInit = false) }
    val characterRepository by lazy { CharacterRepository(appContext.filesDir, loadOnInit = false) }
    val presetRepository by lazy { PresetRepository(appContext.filesDir) }
    val worldBookRepository by lazy { io.github.zvensmoluya.tavernplayer.worldbooks.WorldBookRepository(appContext.filesDir) }
    val shelfTransferClient by lazy { ShelfTransferClient() }
    val mvuRuntime by lazy {
        io.github.zvensmoluya.tavernplayer.conversation.mvu.MvuConversationRuntime {
            appContext.assets.open("mvu/runtime.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }
    val nativeCompilationService by lazy {
        io.github.zvensmoluya.tavernplayer.characters.NativeCompilationService(
            ModelGatewayConversationGenerator(gateway, connectionRepository),
            mvuRuntime = mvuRuntime,
        )
    }
    val ejsRuntime by lazy {
        io.github.zvensmoluya.tavernplayer.conversation.ejs.QuickJsEjsRuntime(loadBundle = {
            appContext.assets.open("ejs/runtime.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
        })
    }
    val browserEnvironment by lazy {
        io.github.zvensmoluya.tavernplayer.conversation.web.BrowserEnvironment(
            appContext,
            characterRepository.imageResources,
            io.github.zvensmoluya.tavernplayer.conversation.web.WebResourceRepository(appContext.filesDir),
            characterRepository::assetFile,
            characterRepository::avatarFile,
        )
    }
    val conversationRepository by lazy {
        ConversationRepository(
            context = appContext,
            filesDir = appContext.filesDir,
            compiler = promptCompiler,
            mvuRuntime = mvuRuntime,
            prepareBrowser = browserEnvironment::prepare,
        )
    }
    val conversationGenerator by lazy {
        StateConfirmingConversationGenerator(ModelGatewayConversationGenerator(gateway, connectionRepository))
    }
}
