package io.github.zvensmoluya.tavernplayer.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.zvensmoluya.tavernplayer.characters.CharacterDetailScreen
import io.github.zvensmoluya.tavernplayer.characters.CharacterLibraryRoute
import io.github.zvensmoluya.tavernplayer.characters.CharacterLibraryViewModel
import io.github.zvensmoluya.tavernplayer.connections.ModelConnectionsRoute
import io.github.zvensmoluya.tavernplayer.connections.ModelConnectionsViewModel
import io.github.zvensmoluya.tavernplayer.conversation.ChatRoute
import io.github.zvensmoluya.tavernplayer.conversation.ChatViewModel

@Composable
fun TavernPlayerApp(
    chatViewModel: ChatViewModel,
    connectionsViewModel: ModelConnectionsViewModel,
    characterLibraryViewModel: CharacterLibraryViewModel,
) {
    val libraryState by characterLibraryViewModel.uiState.collectAsState()
    var surface by rememberSaveable { mutableStateOf(AppSurface.CHARACTER_LIBRARY) }
    var returnFromModels by rememberSaveable { mutableStateOf(AppSurface.CHARACTER_LIBRARY) }

    fun openModels() {
        returnFromModels = surface
        surface = AppSurface.MODEL_CONFIGURATION
    }

    LaunchedEffect(libraryState.openConversationId) {
        libraryState.openConversationId?.let { conversationId ->
            chatViewModel.loadConversation(conversationId)
            characterLibraryViewModel.consumeOpenConversation()
            surface = AppSurface.CHAT
        }
    }
    LaunchedEffect(libraryState.selectedCharacterId, libraryState.importing) {
        if (
            surface == AppSurface.CHARACTER_LIBRARY &&
            !libraryState.importing &&
            libraryState.selectedCharacterId != null
        ) {
            surface = AppSurface.CHARACTER_DETAIL
        }
    }
    val displayedSurface = selectAppSurface(surface, libraryState.selectedCharacter != null)
    LaunchedEffect(displayedSurface) {
        if (surface != displayedSurface) surface = displayedSurface
    }

    when (displayedSurface) {
        AppSurface.CHARACTER_LIBRARY -> CharacterLibraryRoute(
            state = libraryState,
            viewModel = characterLibraryViewModel,
            onSelectCharacter = { characterId ->
                characterLibraryViewModel.selectCharacter(characterId)
                surface = AppSurface.CHARACTER_DETAIL
            },
            onOpenModels = ::openModels,
        )
        AppSurface.CHARACTER_DETAIL -> {
            val character = libraryState.selectedCharacter
            if (character != null) {
                CharacterDetailScreen(
                    character = character,
                    conversations = libraryState.conversationsFor(character.id),
                    avatarPath = characterLibraryViewModel.avatarPath(character.id),
                    onBack = {
                        characterLibraryViewModel.selectCharacter(null)
                        surface = AppSurface.CHARACTER_LIBRARY
                    },
                    onNewConversation = { characterLibraryViewModel.createConversation(character.id) },
                    onOpenConversation = characterLibraryViewModel::openConversation,
                )
            }
        }
        AppSurface.CHAT -> ChatRoute(
            viewModel = chatViewModel,
            onBack = { surface = AppSurface.CHARACTER_DETAIL },
            onOpenModels = ::openModels,
        )
        AppSurface.MODEL_CONFIGURATION -> ModelConnectionsRoute(
            viewModel = connectionsViewModel,
            onBackToChat = { surface = returnFromModels },
        )
    }
}

internal enum class AppSurface {
    CHARACTER_LIBRARY,
    CHARACTER_DETAIL,
    CHAT,
    MODEL_CONFIGURATION,
}

internal fun selectAppSurface(
    requested: AppSurface,
    hasSelectedCharacter: Boolean,
): AppSurface = when {
    requested == AppSurface.CHARACTER_DETAIL && !hasSelectedCharacter -> AppSurface.CHARACTER_LIBRARY
    requested == AppSurface.CHAT && !hasSelectedCharacter -> AppSurface.CHARACTER_LIBRARY
    else -> requested
}
