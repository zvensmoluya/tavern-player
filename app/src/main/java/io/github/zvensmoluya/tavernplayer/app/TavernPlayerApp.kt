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
import io.github.zvensmoluya.tavernplayer.characters.WorldBookReaderScreen
import io.github.zvensmoluya.tavernplayer.connections.ModelConnectionsRoute
import io.github.zvensmoluya.tavernplayer.connections.ModelConnectionsViewModel
import io.github.zvensmoluya.tavernplayer.conversation.ChatRoute
import io.github.zvensmoluya.tavernplayer.conversation.ChatViewModel
import io.github.zvensmoluya.tavernplayer.personas.PersonaRoute
import io.github.zvensmoluya.tavernplayer.personas.PersonaViewModel
import io.github.zvensmoluya.tavernplayer.presets.PresetRoute
import io.github.zvensmoluya.tavernplayer.presets.PresetViewModel

@Composable
fun TavernPlayerApp(
    chatViewModel: () -> ChatViewModel,
    connectionsViewModel: () -> ModelConnectionsViewModel,
    characterLibraryViewModel: CharacterLibraryViewModel,
    presetViewModel: () -> PresetViewModel,
    personaViewModel: () -> PersonaViewModel,
    worldBookRepository: (() -> io.github.zvensmoluya.tavernplayer.worldbooks.WorldBookRepository)? = null,
) {
    val libraryState by characterLibraryViewModel.uiState.collectAsState()
    var surface by rememberSaveable { mutableStateOf(AppSurface.CHARACTER_LIBRARY) }
    var returnFromModels by rememberSaveable { mutableStateOf(AppSurface.CHARACTER_LIBRARY) }
    var returnFromPresets by rememberSaveable { mutableStateOf(AppSurface.CHARACTER_LIBRARY) }
    var returnFromWorldBooks by rememberSaveable { mutableStateOf(AppSurface.CHARACTER_LIBRARY) }
    fun openWorldBooks() { returnFromWorldBooks = surface; surface = AppSurface.GLOBAL_WORLD_BOOKS }
    var returnFromPersona by rememberSaveable { mutableStateOf(AppSurface.CHARACTER_LIBRARY) }

    fun openModels() {
        returnFromModels = surface
        surface = AppSurface.MODEL_CONFIGURATION
    }

    fun openPresets() {
        returnFromPresets = surface
        surface = AppSurface.PRESET_CENTER
    }

    fun openPersona() {
        returnFromPersona = surface
        personaViewModel().startEditing()
        surface = AppSurface.PERSONA
    }

    LaunchedEffect(libraryState.openConversationId) {
        libraryState.openConversationId?.let { conversationId ->
            chatViewModel().loadConversation(conversationId)
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
            onOpenPresets = ::openPresets,
            onOpenGlobalWorldBooks = ::openWorldBooks,
            onOpenPersona = ::openPersona,
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
                    importing = libraryState.busy,
                    onReadWorldBooks = { surface = AppSurface.CHARACTER_WORLD_BOOKS },
                    onOpenResources = { surface = AppSurface.CHARACTER_RESOURCES },
                    message = libraryState.message,
                )
            }
        }
        AppSurface.CHARACTER_WORLD_BOOKS -> libraryState.selectedCharacter?.let { character ->
            WorldBookReaderScreen(character, onBack = { surface = AppSurface.CHARACTER_DETAIL })
        }
        AppSurface.CHARACTER_RESOURCES -> libraryState.selectedCharacter?.let { character ->
            LaunchedEffect(character.id) { characterLibraryViewModel.loadImages(character.id) }
            io.github.zvensmoluya.tavernplayer.characters.CharacterResourcesScreen(
                state = libraryState.imageStates[character.id],
                working = character.id in libraryState.imageWorkingIds,
                error = libraryState.imageErrors[character.id],
                onPrepare = { characterLibraryViewModel.prepareImages(character.id) },
                onCancel = { characterLibraryViewModel.cancelImages(character.id) },
                onReload = { characterLibraryViewModel.loadImages(character.id) },
                resolvePath = { characterLibraryViewModel.imagePath(character.id, it) },
                onBack = { surface = AppSurface.CHARACTER_DETAIL },
            )
        }
        AppSurface.CHAT -> ChatRoute(
            viewModel = chatViewModel(),
            presetViewModel = presetViewModel(),
            resolveAssetPath = characterLibraryViewModel::assetPath,
            onBack = { surface = AppSurface.CHARACTER_DETAIL },
            onOpenModels = ::openModels,
            onOpenPresets = ::openPresets,
            onOpenGlobalWorldBooks = ::openWorldBooks,
        )
        AppSurface.MODEL_CONFIGURATION -> ModelConnectionsRoute(
            viewModel = connectionsViewModel(),
            onBackToChat = { surface = returnFromModels },
        )
        AppSurface.PRESET_CENTER -> PresetRoute(
            viewModel = presetViewModel(),
            onBack = {
                presetViewModel().cancelEditor()
                surface = returnFromPresets
            },
        )
        AppSurface.GLOBAL_WORLD_BOOKS -> worldBookRepository?.let {
            io.github.zvensmoluya.tavernplayer.worldbooks.WorldBookLibraryScreen(it(), onBack = { surface = returnFromWorldBooks })
        }
        AppSurface.PERSONA -> PersonaRoute(
            viewModel = personaViewModel(),
            onBack = {
                personaViewModel().cancelEditing()
                surface = returnFromPersona
            },
        )
    }
}

internal enum class AppSurface {
    CHARACTER_LIBRARY,
    CHARACTER_DETAIL,
    CHARACTER_WORLD_BOOKS,
    CHARACTER_RESOURCES,
    CHAT,
    MODEL_CONFIGURATION,
    PRESET_CENTER,
    GLOBAL_WORLD_BOOKS,
    PERSONA,
}

internal fun selectAppSurface(
    requested: AppSurface,
    hasSelectedCharacter: Boolean,
): AppSurface = when {
    requested == AppSurface.CHARACTER_DETAIL && !hasSelectedCharacter -> AppSurface.CHARACTER_LIBRARY
    requested == AppSurface.CHARACTER_WORLD_BOOKS && !hasSelectedCharacter -> AppSurface.CHARACTER_LIBRARY
    requested == AppSurface.CHARACTER_RESOURCES && !hasSelectedCharacter -> AppSurface.CHARACTER_LIBRARY
    requested == AppSurface.CHAT && !hasSelectedCharacter -> AppSurface.CHARACTER_LIBRARY
    else -> requested
}
