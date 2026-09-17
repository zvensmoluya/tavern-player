package io.github.zvensmoluya.tavernplayer.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
    var surface by rememberSaveable { mutableStateOf(AppSurface.CONVERSATIONS) }
    var returnFromChat by rememberSaveable { mutableStateOf(AppSurface.CONVERSATIONS) }
    var activeConversationId by rememberSaveable { mutableStateOf<String?>(null) }
    var detailCharacterId by rememberSaveable { mutableStateOf<String?>(null) }
    val screenState = rememberSaveableStateHolder()
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
            returnFromChat = if (surface == AppSurface.CHARACTER_DETAIL) AppSurface.CHARACTER_DETAIL else AppSurface.CONVERSATIONS
            activeConversationId = conversationId
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
            detailCharacterId = libraryState.selectedCharacterId
            surface = AppSurface.CHARACTER_DETAIL
        }
    }
    val selectedCharacter = libraryState.characters.firstOrNull { it.id == detailCharacterId } ?: libraryState.selectedCharacter
    val displayedSurface = if (libraryState.initialLoading) surface else if (surface == AppSurface.CHAT && activeConversationId == null) {
        AppSurface.CONVERSATIONS
    } else selectAppSurface(surface, selectedCharacter != null)
    fun navigateHome(target: AppSurface) {
        characterLibraryViewModel.selectCharacter(null)
        detailCharacterId = null
        surface = target
    }
    val modelEditorOpen = if (displayedSurface == AppSurface.MODELS) {
        val modelState by connectionsViewModel().uiState.collectAsState()
        modelState.editor != null
    } else false
    BackHandler(enabled = displayedSurface in homeTabs && displayedSurface != AppSurface.CONVERSATIONS && !modelEditorOpen) {
        navigateHome(AppSurface.CONVERSATIONS)
    }
    LaunchedEffect(displayedSurface) {
        if (surface != displayedSurface) surface = displayedSurface
    }

    StorybookStartup(loading = libraryState.initialLoading) {
        HomeScaffold(displayedSurface, showNavigation = !modelEditorOpen, onNavigate = ::navigateHome) {
            screenState.SaveableStateProvider(displayedSurface.name) {
                when (displayedSurface) {
                    AppSurface.CONVERSATIONS -> ConversationLibraryScreen(
                        libraryState, characterLibraryViewModel::avatarPath, characterLibraryViewModel::openConversation,
                        onOpenCharacters = { navigateHome(AppSurface.CHARACTER_LIBRARY) },
                    )
                    AppSurface.MY -> MyScreen(libraryState.persona, ::openPersona, ::openPresets, ::openWorldBooks)
                    AppSurface.MODELS -> ModelConnectionsRoute(viewModel = connectionsViewModel())
                    AppSurface.CHARACTER_LIBRARY -> CharacterLibraryRoute(
                        state = libraryState,
                        viewModel = characterLibraryViewModel,
                        onSelectCharacter = { characterId ->
                            characterLibraryViewModel.selectCharacter(characterId)
                            detailCharacterId = characterId
                            surface = AppSurface.CHARACTER_DETAIL
                        },
                    )
                    AppSurface.CHARACTER_DETAIL -> {
                        val character = selectedCharacter
                        if (character != null) {
                            CharacterDetailScreen(
                                character = character,
                                conversations = libraryState.conversationsFor(character.id),
                                avatarPath = characterLibraryViewModel.avatarPath(character.id),
                                onBack = {
                                    navigateHome(AppSurface.CHARACTER_LIBRARY)
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
                    AppSurface.CHARACTER_WORLD_BOOKS -> selectedCharacter?.let { character ->
                        WorldBookReaderScreen(character, onBack = { surface = AppSurface.CHARACTER_DETAIL })
                    }
                    AppSurface.CHARACTER_RESOURCES -> selectedCharacter?.let { character ->
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
                    AppSurface.CHAT -> OpenedConversation(
                        conversationId = requireNotNull(activeConversationId),
                        viewModel = chatViewModel(),
                        presetViewModel = presetViewModel,
                        characterLibraryViewModel = characterLibraryViewModel,
                        onBack = { activeConversationId = null; surface = returnFromChat },
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
        }
    }
}

internal enum class AppSurface {
    CONVERSATIONS,
    MODELS,
    MY,
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
    else -> requested
}

/** Never show the previous conversation (or demo content) while a different record is opening. */
@Composable
private fun OpenedConversation(
    conversationId: String,
    viewModel: ChatViewModel,
    presetViewModel: () -> PresetViewModel,
    characterLibraryViewModel: CharacterLibraryViewModel,
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenPresets: () -> Unit,
    onOpenGlobalWorldBooks: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    var requested by remember(conversationId) { mutableStateOf(false) }
    LaunchedEffect(conversationId) {
        if (viewModel.uiState.value.conversationId != conversationId) viewModel.loadConversation(conversationId)
        requested = true
    }
    if (state.conversationId == conversationId && !state.loadingConversation) {
        ChatRoute(viewModel, presetViewModel(), resolveAssetPath = characterLibraryViewModel::assetPath,
            onBack = onBack, onOpenModels = onOpenModels, onOpenPresets = onOpenPresets,
            onOpenGlobalWorldBooks = onOpenGlobalWorldBooks)
    } else {
        BackHandler { if (!state.loadingConversation) onBack() }
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            if (!requested || state.loadingConversation) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("正在打开对话…")
            } else {
                Text(state.message ?: "无法打开这场对话，请重试")
                Button(onClick = { viewModel.loadConversation(conversationId) }) { Text("重试") }
                TextButton(onClick = onBack) { Text("返回") }
            }
        }
    }
}
