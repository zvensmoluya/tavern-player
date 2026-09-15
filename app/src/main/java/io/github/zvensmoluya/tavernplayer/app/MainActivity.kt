package io.github.zvensmoluya.tavernplayer.app

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.zvensmoluya.tavernplayer.characters.CharacterLibraryViewModel
import io.github.zvensmoluya.tavernplayer.connections.ModelConnectionsViewModel
import io.github.zvensmoluya.tavernplayer.conversation.ChatViewModel
import io.github.zvensmoluya.tavernplayer.personas.PersonaViewModel
import io.github.zvensmoluya.tavernplayer.presets.PresetViewModel
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
            graph.mvuRuntime,
            graph.ejsRuntime,
            graph.browserEnvironment,
            globalWorldBooks = graph.worldBookRepository::capture,
        )
    }
    private val characterLibraryViewModel by viewModels<CharacterLibraryViewModel> {
        CharacterLibraryViewModel.Factory(
            graph.characterRepository,
            { graph.conversationRepository },
            graph.personaRepository,
            { graph.presetRepository },
            graph.shelfTransferClient,
            { graph.nativeCompilationService },
            { graph.connectionRepository },
            { graph.worldBookRepository },
        )
    }
    private val presetViewModel by viewModels<PresetViewModel> {
        PresetViewModel.Factory(graph.presetRepository)
    }
    private val personaViewModel by viewModels<PersonaViewModel> {
        PersonaViewModel.Factory(graph.personaRepository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 键盘避让统一交给 Compose 的 WindowInsets：只有 API 30 起系统才会在关闭 decor 适配后
        // 继续派发 IME inset；更低版本保留系统的 adjustResize 收缩窗口路径，否则输入栏会被键盘盖住。
        // 应用只有浅色配色，系统栏图标固定用深色；默认样式会按系统深色模式切白图标，在浅色背景上不可读。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            TavernPlayerTheme {
                TavernPlayerApp(
                    { chatViewModel },
                    { modelConnectionsViewModel },
                    characterLibraryViewModel,
                    { presetViewModel },
                    { personaViewModel },
                    { graph.worldBookRepository },
                )
            }
        }
    }
}
