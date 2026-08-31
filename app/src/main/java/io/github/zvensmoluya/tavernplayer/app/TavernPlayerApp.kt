package io.github.zvensmoluya.tavernplayer.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.zvensmoluya.tavernplayer.connections.ModelConnectionsRoute
import io.github.zvensmoluya.tavernplayer.connections.ModelConnectionsViewModel
import io.github.zvensmoluya.tavernplayer.conversation.ChatRoute
import io.github.zvensmoluya.tavernplayer.conversation.ChatUiState
import io.github.zvensmoluya.tavernplayer.conversation.ChatViewModel

@Composable
fun TavernPlayerApp(
    chatViewModel: ChatViewModel,
    connectionsViewModel: ModelConnectionsViewModel,
) {
    val chatState by chatViewModel.uiState.collectAsState()
    var managingModels by rememberSaveable { mutableStateOf(false) }
    when (selectAppSurface(chatState, managingModels)) {
        AppSurface.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        AppSurface.MODEL_CONFIGURATION -> ModelConnectionsRoute(
            viewModel = connectionsViewModel,
            onBackToChat = if (chatState.readyConnections.isEmpty()) null else ({ managingModels = false }),
        )
        AppSurface.CHAT -> ChatRoute(
            viewModel = chatViewModel,
            onOpenModels = { managingModels = true },
        )
    }
}

internal enum class AppSurface {
    LOADING,
    MODEL_CONFIGURATION,
    CHAT,
}

internal fun selectAppSurface(state: ChatUiState, managingModels: Boolean) =
    when {
        state.loadingConnections -> AppSurface.LOADING
        state.readyConnections.isEmpty() || managingModels -> AppSurface.MODEL_CONFIGURATION
        else -> AppSurface.CHAT
    }
