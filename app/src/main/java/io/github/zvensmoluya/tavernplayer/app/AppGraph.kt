package io.github.zvensmoluya.tavernplayer.app

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import io.github.zvensmoluya.modelgateway.ModelGateway
import io.github.zvensmoluya.tavernplayer.connections.AndroidKeystoreCredentialStore
import io.github.zvensmoluya.tavernplayer.connections.ConnectionRepository
import io.github.zvensmoluya.tavernplayer.connections.JsonConnectionDataStore
import io.github.zvensmoluya.tavernplayer.connections.ProbeService

private val Context.gatewayDataStore by preferencesDataStore(name = "model_gateway_connections")

class AppGraph(context: Context) {
    private val appContext = context.applicationContext
    val credentialStore = AndroidKeystoreCredentialStore(appContext)
    val gateway = ModelGateway(credentialStore)
    val connectionRepository = ConnectionRepository(
        stateStore = JsonConnectionDataStore(appContext.gatewayDataStore),
        credentialStore = credentialStore,
        catalogLoader = { gateway.modelCatalog.list(it) },
    )
    val probeService = ProbeService(gateway, connectionRepository)
}
