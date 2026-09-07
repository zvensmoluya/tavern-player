package io.github.zvensmoluya.tavernplayer.app

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import io.github.zvensmoluya.modelgateway.ModelGateway
import io.github.zvensmoluya.tavernplayer.connections.AndroidKeystoreCredentialStore
import io.github.zvensmoluya.tavernplayer.connections.ConnectionRepository
import io.github.zvensmoluya.tavernplayer.connections.JsonConnectionDataStore
import io.github.zvensmoluya.tavernplayer.connections.ProbeService
import io.github.zvensmoluya.tavernplayer.characters.CharacterRepository
import io.github.zvensmoluya.tavernplayer.conversation.ConversationRepository
import io.github.zvensmoluya.tavernplayer.conversation.ModelGatewayConversationGenerator
import io.github.zvensmoluya.tavernplayer.conversation.PromptCompiler
import io.github.zvensmoluya.tavernplayer.conversation.StateConfirmingConversationGenerator
import io.github.zvensmoluya.tavernplayer.personas.PersonaRepository
import io.github.zvensmoluya.tavernplayer.presets.PresetRepository
import io.github.zvensmoluya.tavernplayer.transfer.ShelfTransferClient

private val Context.gatewayDataStore by preferencesDataStore(name = "model_gateway_connections")

class AppGraph(context: Context) {
    private val appContext = context.applicationContext
    val credentialStore = AndroidKeystoreCredentialStore(appContext)
    val gateway = ModelGateway(credentialStore, io.github.zvensmoluya.tavernplayer.connections.PlayerModelHttpClient.create())
    val connectionRepository = ConnectionRepository(
        stateStore = JsonConnectionDataStore(appContext.gatewayDataStore),
        credentialStore = credentialStore,
        catalogLoader = { gateway.modelCatalog.list(it) },
    )
    val probeService = ProbeService(gateway, connectionRepository)
    val promptCompiler = PromptCompiler()
    val personaRepository = PersonaRepository(appContext.filesDir)
    val characterRepository = CharacterRepository(appContext.filesDir)
    val nativeCompilationService = io.github.zvensmoluya.tavernplayer.characters.NativeCompilationService(
        ModelGatewayConversationGenerator(gateway, connectionRepository),
    )
    val presetRepository = PresetRepository(appContext.filesDir)
    val shelfTransferClient = ShelfTransferClient()
    val mvuRuntime = io.github.zvensmoluya.tavernplayer.conversation.mvu.MvuConversationRuntime {
        appContext.assets.open("mvu/runtime.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
    val ejsRuntime = io.github.zvensmoluya.tavernplayer.conversation.ejs.QuickJsEjsRuntime(loadBundle = {
        appContext.assets.open("ejs/runtime.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
    })
    val conversationRepository = ConversationRepository(
        filesDir = appContext.filesDir,
        compiler = promptCompiler,
        mvuRuntime = mvuRuntime,
    )
    val conversationGenerator = StateConfirmingConversationGenerator(
        ModelGatewayConversationGenerator(gateway, connectionRepository),
    )
}
