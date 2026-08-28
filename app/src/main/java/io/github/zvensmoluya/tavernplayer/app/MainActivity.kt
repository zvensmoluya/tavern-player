package io.github.zvensmoluya.tavernplayer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import io.github.zvensmoluya.tavernplayer.connections.ModelConnectionsRoute
import io.github.zvensmoluya.tavernplayer.connections.ModelConnectionsViewModel
import io.github.zvensmoluya.tavernplayer.ui.theme.TavernPlayerTheme

class MainActivity : ComponentActivity() {
    private val graph by lazy { AppGraph(this) }
    private val modelConnectionsViewModel by viewModels<ModelConnectionsViewModel> {
        ModelConnectionsViewModel.Factory(graph.connectionRepository, graph.probeService)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TavernPlayerTheme {
                ModelConnectionsRoute(modelConnectionsViewModel)
            }
        }
    }
}
