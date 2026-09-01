package io.github.zvensmoluya.tavernplayer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import io.github.zvensmoluya.tavernplayer.characters.CharacterLibraryViewModel
import io.github.zvensmoluya.tavernplayer.connections.ModelConnectionsViewModel
import io.github.zvensmoluya.tavernplayer.conversation.ChatViewModel
import io.github.zvensmoluya.tavernplayer.ui.theme.TavernPlayerTheme

class MainActivity : ComponentActivity() {
    private val graph by lazy { AppGraph(this) }
    private val modelConnectionsViewModel by viewModels<ModelConnectionsViewModel> {
        ModelConnectionsViewModel.Factory(graph.connectionRepository, graph.probeService)
    }
    private val chatViewModel by viewModels<ChatViewModel> {
        ChatViewModel.Factory(
            graph.connectionRepository,
            graph.promptCompiler,
            graph.conversationGenerator,
            graph.conversationRepository,
            graph.presetRepository,
        )
    }
    private val characterLibraryViewModel by viewModels<CharacterLibraryViewModel> {
        CharacterLibraryViewModel.Factory(
            graph.characterRepository,
            graph.conversationRepository,
            graph.defaultPersona,
            graph.presetRepository,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TavernPlayerTheme {
                TavernPlayerApp(chatViewModel, modelConnectionsViewModel, characterLibraryViewModel)
            }
        }
    }
}
