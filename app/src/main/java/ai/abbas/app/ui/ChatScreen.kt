package ai.abbas.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import ai.abbas.app.BuildConfig
import ai.abbas.app.data.ModelConfig
import ai.abbas.app.data.GenerationSettings
import ai.abbas.app.viewmodel.ChatMessage
import ai.abbas.app.viewmodel.ChatUiState
import ai.abbas.app.viewmodel.ChatViewModel
import ai.abbas.app.viewmodel.ModelDownloadState
import ai.abbas.app.ui.theme.AbbasBlue
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.material3.Material3RichText
import kotlinx.coroutines.launch

const val APP_VERSION = "v${BuildConfig.VERSION_NAME}"

private enum class AppTab { CHAT, HUB }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    val currentSessionId by viewModel.currentSessionId.collectAsState()
    val currentModel by viewModel.currentModel.collectAsState()
    val isWebSearchEnabled by viewModel.isWebSearchEnabled.collectAsState()
    val generationSettings by viewModel.generationSettings.collectAsState()
    val downloadStates by viewModel.downloadStates.collectAsState()
    val downloadedModelIds by viewModel.downloadedModelIds.collectAsState()

    var currentTab by rememberSaveable { mutableStateOf(AppTab.CHAT) }
    var showAddCustomModel by remember { mutableStateOf(false) }
    var showClearChats by remember { mutableStateOf(false) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.ingestDocument(it) }
    }

    // Observe transient errors
    LaunchedEffect(viewModel.errors) {
        viewModel.errors.collect { error ->
            snackbarHostState.showSnackbar(
                message = error,
                duration = SnackbarDuration.Long
            )
        }
    }

    if (showAddCustomModel) {
        AddCustomModelDialog(
            onDismiss = { showAddCustomModel = false },
            onAdd = { model ->
                viewModel.addCustomModel(model)
                showAddCustomModel = false
            }
        )
    }

    if (showClearChats) {
        AlertDialog(
            onDismissRequest = { showClearChats = false },
            title = { Text("Clear all chats?") },
            text = {
                Text(
                    "All chat sessions and their messages will be permanently deleted. " +
                        "This cannot be undone. Downloaded models are not affected."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllChats()
                        showClearChats = false
                    }
                ) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearChats = false }) { Text("Cancel") }
            }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(300.dp),
                drawerContainerColor = MaterialTheme.colorScheme.surface
            ) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.History, contentDescription = null, tint = AbbasBlue)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Chat History",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Divider(modifier = Modifier.padding(vertical = 8.dp), color = Color.Gray.copy(alpha = 0.1f))

                NavigationDrawerItem(
                    label = { Text("Chat") },
                    selected = currentTab == AppTab.CHAT,
                    onClick = {
                        currentTab = AppTab.CHAT
                        scope.launch { drawerState.close() }
                    },
                    icon = {
                        Icon(
                            if (currentTab == AppTab.CHAT) Icons.Filled.Chat else Icons.Outlined.Chat,
                            contentDescription = null
                        )
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = AbbasBlue.copy(alpha = 0.1f),
                        selectedTextColor = AbbasBlue,
                        selectedIconColor = AbbasBlue
                    )
                )

                NavigationDrawerItem(
                    label = { Text("AI Hub") },
                    selected = currentTab == AppTab.HUB,
                    onClick = {
                        currentTab = AppTab.HUB
                        scope.launch { drawerState.close() }
                    },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (downloadStates.isNotEmpty()) Badge { Text("${downloadStates.size}") }
                            }
                        ) {
                            Icon(
                                if (currentTab == AppTab.HUB) Icons.Filled.CloudDownload else Icons.Outlined.CloudDownload,
                                contentDescription = null
                            )
                        }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = AbbasBlue.copy(alpha = 0.1f),
                        selectedTextColor = AbbasBlue,
                        selectedIconColor = AbbasBlue
                    )
                )

                Divider(modifier = Modifier.padding(vertical = 8.dp), color = Color.Gray.copy(alpha = 0.1f))

                NavigationDrawerItem(
                    label = { Text("New Chat") },
                    selected = currentSessionId == null,
                    onClick = {
                        viewModel.newChat()
                        scope.launch { drawerState.close() }
                    },
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = AbbasBlue.copy(alpha = 0.1f),
                        selectedTextColor = AbbasBlue,
                        selectedIconColor = AbbasBlue
                    )
                )

                Spacer(Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f)
                ) {
                    items(sessions) { session ->
                        val isSelected = session.id == currentSessionId
                        Box(modifier = Modifier.fillMaxWidth().padding(NavigationDrawerItemDefaults.ItemPadding)) {
                            NavigationDrawerItem(
                                label = {
                                    Column {
                                        Text(
                                            session.title,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                        Text(
                                            session.lastMessage,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.Gray,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                    }
                                },
                                selected = isSelected,
                                onClick = {
                                    viewModel.selectSession(session.id)
                                    scope.launch { drawerState.close() }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = NavigationDrawerItemDefaults.colors(
                                    selectedContainerColor = AbbasBlue.copy(alpha = 0.1f),
                                    selectedTextColor = AbbasBlue,
                                    selectedIconColor = AbbasBlue
                                )
                            )
                            
                            IconButton(
                                onClick = { viewModel.deleteSession(session.id) },
                                modifier = Modifier.align(Alignment.CenterEnd).size(32.dp).padding(end = 8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete Chat",
                                    tint = if (isSelected) AbbasBlue.copy(alpha = 0.5f) else Color.Gray.copy(alpha = 0.5f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
                
                Divider(modifier = Modifier.padding(vertical = 8.dp), color = Color.Gray.copy(alpha = 0.1f))
                
                DropdownMenuItem(
                    text = { Text("Clear All Chats") },
                    onClick = {
                        showClearChats = true
                        scope.launch { drawerState.close() }
                    },
                    leadingIcon = { Icon(Icons.Outlined.DeleteSweep, contentDescription = null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )

                Divider(modifier = Modifier.padding(vertical = 8.dp), color = Color.Gray.copy(alpha = 0.1f))
                
                var showGenerationSettings by remember { mutableStateOf(false) }
                if (showGenerationSettings) {
                    GenerationSettingsDialog(
                        settings = viewModel.generationSettings.collectAsState().value,
                        onDismiss = { showGenerationSettings = false },
                        onSave = { settings ->
                            viewModel.updateGenerationSettings(settings)
                            showGenerationSettings = false
                        }
                    )
                }
                
                NavigationDrawerItem(
                    label = { Text("Generation Settings") },
                    selected = false,
                    onClick = { showGenerationSettings = true },
                    icon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )

                Divider(modifier = Modifier.padding(vertical = 8.dp), color = Color.Gray.copy(alpha = 0.1f))

                NavigationDrawerItem(
                    label = { Text("Donate to Support") },
                    selected = false,
                    onClick = {
                        viewModel.enterDonation()
                        scope.launch { drawerState.close() }
                    },
                    icon = { Icon(Icons.Default.Favorite, contentDescription = null, tint = AbbasBlue) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )

                Spacer(Modifier.height(12.dp))
                Divider(color = Color.Gray.copy(alpha = 0.1f))
                Text(
                    text = APP_VERSION,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = Color.Gray.copy(alpha = 0.5f),
                        fontSize = 10.sp
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                when {
                    uiState == ChatUiState.Donating -> Unit
                    currentTab == AppTab.CHAT -> CenterAlignedTopAppBar(
                        title = {
                            ModelDropdown(
                                currentModel = currentModel,
                                onSwitchModel = viewModel::switchModel
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Outlined.Menu, contentDescription = "History")
                            }
                        },
                        actions = {
                            if (uiState == ChatUiState.Ready) {
                                IconButton(onClick = { viewModel.enterDonation() }) {
                                    Icon(
                                        Icons.Default.Favorite,
                                        contentDescription = "Support",
                                        tint = AbbasBlue
                                    )
                                }
                            }
                            IconButton(onClick = { viewModel.newChat() }) {
                                Icon(Icons.Outlined.Add, contentDescription = "New Chat")
                            }
                        },
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        )
                    )
                    // AI Hub top bar lives here so it shares the Chat screen's inset
                    // handling (otherwise it re-applies the status-bar inset itself).
                    else -> CenterAlignedTopAppBar(
                        title = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "AI Hub",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Model Weights",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.Gray
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Outlined.Menu, contentDescription = "Menu")
                            }
                        },
                        actions = {
                            IconButton(onClick = { showAddCustomModel = true }) {
                                Icon(Icons.Outlined.AddCircleOutline, contentDescription = "Add custom model")
                            }
                        },
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        )
                    )
                }
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize()) {
                if (currentTab == AppTab.HUB && uiState != ChatUiState.Donating) {
                    Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                        AiHubScreen(
                            viewModel = viewModel,
                            onOpenChat = { currentTab = AppTab.CHAT }
                        )
                    }
                } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(if (uiState == ChatUiState.Donating) PaddingValues(0.dp) else paddingValues)
                ) {
                    when (uiState) {
                        is ChatUiState.Initializing -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = AbbasBlue)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text("Starting Abbas Engine...", color = MaterialTheme.colorScheme.onBackground)
                                }
                            }
                        }
                        is ChatUiState.SelectingModel -> {
                            val state = uiState as ChatUiState.SelectingModel
                            ModelLoadScreen(
                                models = state.models.filter { downloadedModelIds.contains(it.id) },
                                onInitialize = viewModel::initModel,
                                onOpenHub = { currentTab = AppTab.HUB },
                                onCancel = if (currentModel != null) { { viewModel.onCancelModelSelection() } } else null
                            )
                        }
                        is ChatUiState.LoadingModel -> {
                            val state = uiState as ChatUiState.LoadingModel
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(color = AbbasBlue)
                                Spacer(modifier = Modifier.height(20.dp))
                                Text(
                                    "Loading ${state.model.name}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )
                                Text(
                                    state.status,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray
                                )
                            }
                        }
                        is ChatUiState.Donating -> {
                            DonationScreen(
                                onBack = { viewModel.exitDonation() },
                                onStripeDonate = { amount, onResult ->
                                    viewModel.processStripeDonation(amount, onResult)
                                },
                                onPayPalDonate = { amount, onResult ->
                                    viewModel.processPayPalDonation(amount, onResult)
                                },
                                stripePaymentEvent = viewModel.stripePaymentEvent,
                                payPalPaymentEvent = viewModel.payPalPaymentEvent
                            )
                        }
                        is ChatUiState.Error -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(64.dp))
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "Something went wrong",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = (uiState as ChatUiState.Error).message,
                                        color = MaterialTheme.colorScheme.error,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Spacer(modifier = Modifier.height(32.dp))
                                    
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = { viewModel.switchModel() },
                                            border = BorderStroke(1.dp, AbbasBlue)
                                        ) {
                                            Text("Switch Model", color = AbbasBlue)
                                        }

                                        Button(
                                            onClick = { viewModel.newChat() },
                                            colors = ButtonDefaults.buttonColors(containerColor = AbbasBlue)
                                        ) {
                                            Text("Retry")
                                        }
                                    }
                                }
                            }
                        }
                        is ChatUiState.Corrupted -> {
                            val state = uiState as ChatUiState.Corrupted
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(64.dp))
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "Model Corrupted",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "The model files for ${state.model.name} appear to be incomplete or corrupted. They need to be re-downloaded.",
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "Reason: ${state.message}",
                                        color = Color.Gray,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Spacer(modifier = Modifier.height(32.dp))
                                    
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Button(
                                            onClick = {
                                                viewModel.deleteCorruptedAndRetry(state.model)
                                                currentTab = AppTab.HUB
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = AbbasBlue),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("Re-download Model")
                                        }

                                        OutlinedButton(
                                            onClick = { viewModel.deleteModelWeights(state.model) },
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("Delete Corrupted Files")
                                        }

                                        TextButton(
                                            onClick = { viewModel.switchModel() },
                                            modifier = Modifier.align(Alignment.CenterHorizontally)
                                        ) {
                                            Text("Switch to Different Model", color = Color.Gray)
                                        }
                                    }
                                }
                            }
                        }
                        is ChatUiState.Incompatible -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(48.dp))
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        "Device Incompatible",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        "Your device does not meet the minimum requirements for any available offline LLMs. At least 4GB of RAM and Vulkan support are generally required.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.Gray,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        }
                        is ChatUiState.Ready -> {
                            MessageList(
                                messages = messages,
                                onRegenerate = { viewModel.regenerate() },
                                modifier = Modifier.weight(1f)
                            )
                            MessageInput(
                                onSendMessage = viewModel::sendMessage,
                                onStopGeneration = viewModel::stopGeneration,
                                isGenerating = messages.lastOrNull()?.isGenerating == true,
                                onAttach = {
                                    filePickerLauncher.launch(
                                        arrayOf(
                                            "text/plain",
                                            "text/markdown",
                                            "text/csv",
                                            "application/pdf",
                                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                                        )
                                    )
                                },
                                isWebSearchEnabled = isWebSearchEnabled,
                                onToggleWebSearch = { viewModel.toggleWebSearch() },
                                isThinkingEnabled = generationSettings.enableThinking,
                                onToggleThinking = { viewModel.toggleThinking() }
                            )
                        }
                    }
                }
                }

                // App version is shown in the navigation drawer footer.
            }
        }
    }
}

@Composable
fun ModelDropdown(
    currentModel: ModelConfig?,
    onSwitchModel: () -> Unit
) {
    // The title acts as the model-initializing button: tapping it opens the
    // load screen where a downloaded model can be picked and loaded into the engine.
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onSwitchModel() }
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Abbas",
                style = TextStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    currentModel?.name ?: "Select Model",
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                )
                Icon(
                    Icons.Outlined.Memory,
                    contentDescription = "Initialize model",
                    modifier = Modifier.size(14.dp),
                    tint = Color.Gray
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelLoadScreen(
    models: List<ModelConfig>,
    onInitialize: (ModelConfig) -> Unit,
    onOpenHub: () -> Unit,
    onCancel: (() -> Unit)? = null
) {
    var selectedModel by remember { mutableStateOf<ModelConfig?>(null) }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (onCancel != null) {
            IconButton(
                onClick = onCancel,
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Cancel")
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp).fillMaxWidth()
        ) {
            Icon(
                Icons.Outlined.Memory,
                contentDescription = null,
                tint = AbbasBlue,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                if (models.isEmpty()) "No models ready" else "Load a model",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                if (models.isEmpty())
                    "You haven't downloaded any model weights yet."
                else
                    "Pick a downloaded model to load into the engine",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))

            if (models.isEmpty()) {
                Button(
                    onClick = onOpenHub,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AbbasBlue),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open AI Hub to download", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(models, key = { it.id }) { model ->
                        val isSelected = model.id == selectedModel?.id

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedModel = model },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected)
                                    AbbasBlue.copy(alpha = 0.08f)
                                else
                                    MaterialTheme.colorScheme.surface
                            ),
                            border = if (isSelected) BorderStroke(1.dp, AbbasBlue) else null,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(16.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(model.name, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "~${String.format("%.1f", model.estimatedRamBytes / (1024 * 1024 * 1024.0))} GB RAM",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.Gray
                                    )
                                }
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = "Downloaded",
                                    tint = AbbasBlue,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = { selectedModel?.let { onInitialize(it) } },
                    enabled = selectedModel != null,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AbbasBlue),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Text(
                        "Initialize Engine",
                        fontSize = 16.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun MessageList(messages: List<ChatMessage>, onRegenerate: () -> Unit, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    // Track if the user is at the bottom to determine if we should auto-scroll
    val isAtBottom by remember {
        derivedStateOf {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            if (lastVisibleItem == null) true
            else {
                val isLastItemVisible = lastVisibleItem.index == listState.layoutInfo.totalItemsCount - 1
                val isAtVeryBottom = lastVisibleItem.offset + lastVisibleItem.size <= listState.layoutInfo.viewportEndOffset
                isLastItemVisible && isAtVeryBottom
            }
        }
    }

    // Auto-scroll logic: only trigger if we were already at the bottom and not manually scrolling
    val shouldAutoScroll = isAtBottom && !listState.isScrollInProgress

    // Auto-scroll when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && shouldAutoScroll) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Also scroll when the last message text changes (streaming)
    val lastMessageText = messages.lastOrNull()?.text ?: ""
    LaunchedEffect(lastMessageText) {
        if (messages.lastOrNull()?.isGenerating == true && shouldAutoScroll) {
            listState.scrollToItem(messages.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        reverseLayout = false
    ) {
        items(messages, key = { it.id }) { message ->
            val isLastMessage = messages.lastOrNull()?.id == message.id
            MessageBubble(
                message = message,
                isLastMessage = isLastMessage,
                onRegenerate = onRegenerate
            )
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage, isLastMessage: Boolean = false, onRegenerate: () -> Unit = {}) {
    val isUser = message.isUser
    var isThoughtExpanded by remember { mutableStateOf(true) }
    val clipboardManager = LocalClipboardManager.current
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(AbbasBlue),
                contentAlignment = Alignment.Center
            ) {
                Text("A", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.width(12.dp))
        }

        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .widthIn(max = 340.dp)
        ) {
            if (!isUser) {
                Text(
                    "Abbas",
                    style = TextStyle(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = Color.Gray
                    ),
                    modifier = Modifier.padding(bottom = 4.dp, start = 4.dp)
                )
            }

            // Thought Block (Abbas style)
            if (!isUser && (message.thought != null || (message.isGenerating && message.text.isEmpty()))) {
                Column(
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Gray.copy(alpha = 0.05f))
                        .border(1.dp, Color.Gray.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isThoughtExpanded = !isThoughtExpanded }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (isThoughtExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Color.Gray
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                if (message.isGenerating && message.thought == null) "Thinking..." else "Thought",
                                style = TextStyle(
                                    fontSize = 13.sp,
                                    color = Color.Gray,
                                    fontWeight = FontWeight.Medium
                                )
                            )
                        }
                        if (message.isGenerating && message.thought == null) {
                            ThinkingAnimation(color = AbbasBlue)
                        }
                    }
                    if (isThoughtExpanded && message.thought != null) {
                        SelectionContainer {
                            Material3RichText(
                                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                            ) {
                                ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(
                                    color = Color.Gray.copy(alpha = 0.8f),
                                    lineHeight = 18.sp
                                )) {
                                    Markdown(content = message.thought)
                                }
                            }
                        }
                    }
                }
            }

            if (message.text.isNotEmpty()) {
                Surface(
                    color = if (isUser) AbbasBlue else Color.Transparent,
                    shape = RoundedCornerShape(
                        topStart = if (isUser) 16.dp else 4.dp,
                        topEnd = 16.dp,
                        bottomStart = 16.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp
                    ),
                    border = if (isUser) null else BorderStroke(1.dp, Color.Gray.copy(alpha = 0.1f))
                ) {
                    // Plain text during generation avoids expensive markdown re-parse on every token
                    if (message.isGenerating) {
                        Text(
                            text = message.text,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyLarge.copy(
                                color = if (isUser) Color.White else MaterialTheme.colorScheme.onSurface,
                                lineHeight = 22.sp
                            )
                        )
                    } else {
                        SelectionContainer {
                            Material3RichText(
                                modifier = Modifier.padding(12.dp)
                            ) {
                                ProvideTextStyle(MaterialTheme.typography.bodyLarge.copy(
                                    color = if (isUser) Color.White else MaterialTheme.colorScheme.onSurface,
                                    lineHeight = 22.sp
                                )) {
                                    Markdown(content = message.text)
                                }
                            }
                        }
                    }
                }
            }

            // Action Row and Metrics
            if (!isUser && !message.isGenerating) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, start = 4.dp, end = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Metrics
                    if (message.tokensPerSecond != null) {
                        val metricsText = when {
                            message.thoughtTimeMs != null -> {
                                String.format("%.1f t/s • Thought for %.1fs", message.tokensPerSecond, message.thoughtTimeMs / 1000f)
                            }
                            message.generationTimeMs != null -> {
                                String.format("%.1f t/s • Response in %.1fs", message.tokensPerSecond, message.generationTimeMs / 1000f)
                            }
                            else -> {
                                String.format("%.1f tokens/s", message.tokensPerSecond)
                            }
                        }
                        
                        Text(
                            text = metricsText,
                            style = TextStyle(fontSize = 10.sp, color = Color.Gray)
                        )
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }
                    
                    // Actions
                    Row {
                        IconButton(
                            onClick = { clipboardManager.setText(AnnotatedString(message.text)) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(14.dp), tint = Color.Gray)
                        }
                        if (isLastMessage) {
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                onClick = onRegenerate,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Regenerate", modifier = Modifier.size(16.dp), tint = Color.Gray)
                            }
                        }
                    }
                }
            }
        }

        if (isUser) {
            Spacer(modifier = Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color.Gray.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.Gray)
            }
        }
    }
}

@Composable
fun ThinkingAnimation(color: Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        repeat(3) { _ ->
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.5f))
            )
        }
    }
}

@Composable
fun MessageInput(
    onSendMessage: (String) -> Unit,
    onStopGeneration: () -> Unit,
    isGenerating: Boolean,
    onAttach: () -> Unit,
    isWebSearchEnabled: Boolean,
    onToggleWebSearch: () -> Unit,
    isThinkingEnabled: Boolean,
    onToggleThinking: () -> Unit
) {
    var textState by remember { mutableStateOf(TextFieldValue("")) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                var optionsExpanded by remember { mutableStateOf(false) }

                // All composer options live behind a single "+" (DeepSeek/Qwen style):
                // attach, web search, and thinking.
                Box {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color.LightGray.copy(alpha = 0.3f))
                            .clickable { optionsExpanded = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "More options",
                            tint = AbbasBlue,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = optionsExpanded,
                        onDismissRequest = { optionsExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Attach file") },
                            leadingIcon = { Icon(Icons.Outlined.AttachFile, contentDescription = null, tint = AbbasBlue) },
                            onClick = {
                                optionsExpanded = false
                                onAttach()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Web search") },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (isWebSearchEnabled) Icons.Filled.Public else Icons.Outlined.Public,
                                    contentDescription = null,
                                    tint = if (isWebSearchEnabled) AbbasBlue else Color.Gray
                                )
                            },
                            trailingIcon = {
                                if (isWebSearchEnabled) Icon(Icons.Default.Check, contentDescription = null, tint = AbbasBlue)
                            },
                            onClick = { onToggleWebSearch() }
                        )
                        DropdownMenuItem(
                            text = { Text("Thinking") },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Psychology,
                                    contentDescription = null,
                                    tint = if (isThinkingEnabled) AbbasBlue else Color.Gray
                                )
                            },
                            trailingIcon = {
                                if (isThinkingEnabled) Icon(Icons.Default.Check, contentDescription = null, tint = AbbasBlue)
                            },
                            onClick = { onToggleThinking() }
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                BasicTextField(
                    value = textState,
                    onValueChange = { textState = it },
                    modifier = Modifier.weight(1f),
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp
                    ),
                    cursorBrush = SolidColor(AbbasBlue),
                    decorationBox = { innerTextField ->
                        if (textState.text.isEmpty()) {
                            Text(
                                "Message Abbas...",
                                color = Color.Gray,
                                fontSize = 16.sp
                            )
                        }
                        innerTextField()
                    }
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                if (isGenerating) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color.Red.copy(alpha = 0.1f))
                            .clickable { onStopGeneration() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.Stop,
                            contentDescription = "Stop",
                            tint = Color.Red,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(if (textState.text.isNotBlank()) AbbasBlue else Color.LightGray)
                            .clickable(enabled = textState.text.isNotBlank()) {
                                onSendMessage(textState.text)
                                textState = TextFieldValue("")
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = "Send",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Abbas can make mistakes. Check important info.",
            modifier = Modifier.align(Alignment.CenterHorizontally),
            style = TextStyle(fontSize = 10.sp, color = Color.Gray)
        )
    }
}

@Composable
fun HfTokenItem(viewModel: ChatViewModel) {
    val hfToken by viewModel.hfToken.collectAsState()
    var showDialog by remember { mutableStateOf(false) }
    var tempToken by remember { mutableStateOf("") }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Outlined.VpnKey, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Hugging Face Token", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = if (hfToken.isBlank()) "No token set" else "••••" + hfToken.takeLast(4),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
            TextButton(onClick = { 
                tempToken = hfToken
                showDialog = true 
            }) {
                Text(if (hfToken.isBlank()) "Set" else "Edit", color = AbbasBlue)
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Hugging Face Token") },
            text = {
                Column {
                    Text(
                        "Required for gated models. Accept the license on HF first.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = tempToken,
                        onValueChange = { tempToken = it },
                        label = { Text("Enter Read Token") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.updateHfToken(tempToken)
                        showDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AbbasBlue)
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

