package ai.alal.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import ai.alal.app.BuildConfig
import ai.alal.app.data.ModelConfig
import ai.alal.app.repository.IngestedDocument
import ai.alal.app.viewmodel.ChatMessage
import ai.alal.app.viewmodel.ChatUiState
import ai.alal.app.viewmodel.ChatViewModel
import ai.alal.app.viewmodel.PersonalViewModel
import ai.alal.app.personal.PhoneAction
import ai.alal.app.personal.PhoneActionParser
import ai.alal.app.ui.theme.AlalBlue
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.material3.Material3RichText
import kotlinx.coroutines.launch

const val APP_VERSION = "v${BuildConfig.VERSION_NAME}"

private enum class AppTab { CHAT, HUB, DIGEST, DOCS, PROMPTS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    val currentSessionId by viewModel.currentSessionId.collectAsState()
    val currentModel by viewModel.currentModel.collectAsState()
    val isWebSearchEnabled by viewModel.isWebSearchEnabled.collectAsState()
    val webSearchAllowed by viewModel.webSearchAllowed.collectAsState()
    val generationSettings by viewModel.generationSettings.collectAsState()
    val documents by viewModel.documents.collectAsState()
    val attachedDocuments by viewModel.attachedDocuments.collectAsState()
    val systemPromptSettings by viewModel.systemPromptSettings.collectAsState()

    var currentTab by rememberSaveable { mutableStateOf(AppTab.CHAT) }
    var showAddCustomModel by remember { mutableStateOf(false) }
    var showClearChats by remember { mutableStateOf(false) }
    var showDocumentPicker by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showGenerationSettings by remember { mutableStateOf(false) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.ingestDocument(it) }
    }

    // .gguf has no registered MIME type, so accept anything; the importer checks the GGUF header.
    val modelFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importModelFile(it) }
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

    if (showDocumentPicker) {
        DocumentPickerDialog(
            documents = documents,
            currentSessionId = currentSessionId,
            onDismiss = { showDocumentPicker = false },
            onPick = { ids ->
                viewModel.attachExistingDocuments(ids)
                showDocumentPicker = false
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

    if (showSettings) {
        SettingsSheet(
            currentTab = currentTab,
            onDismiss = { showSettings = false },
            onSelectTab = { tab ->
                currentTab = tab
                showSettings = false
            },
            onGenerationSettings = {
                showSettings = false
                showGenerationSettings = true
            },
            webSearchAllowed = webSearchAllowed,
            onWebSearchAllowedChange = { viewModel.setWebSearchAllowed(it) }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(300.dp),
                drawerContainerColor = MaterialTheme.colorScheme.surface
            ) {
                val drawerItemColors = NavigationDrawerItemDefaults.colors(
                    selectedContainerColor = AlalBlue.copy(alpha = 0.1f),
                    selectedTextColor = AlalBlue,
                    selectedIconColor = AlalBlue
                )
                Spacer(Modifier.height(12.dp))

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
                    colors = drawerItemColors
                )
                NavigationDrawerItem(
                    label = { Text("AI Hub") },
                    selected = currentTab == AppTab.HUB,
                    onClick = {
                        currentTab = AppTab.HUB
                        scope.launch { drawerState.close() }
                    },
                    icon = {
                        BadgedBox(badge = { DownloadBadge(viewModel) }) {
                            Icon(
                                if (currentTab == AppTab.HUB) Icons.Filled.CloudDownload else Icons.Outlined.CloudDownload,
                                contentDescription = null
                            )
                        }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    colors = drawerItemColors
                )
                NavigationDrawerItem(
                    label = { Text("Summaries & To-dos") },
                    selected = currentTab == AppTab.DIGEST,
                    onClick = {
                        currentTab = AppTab.DIGEST
                        scope.launch { drawerState.close() }
                    },
                    icon = {
                        Icon(
                            if (currentTab == AppTab.DIGEST) Icons.Filled.AutoAwesome else Icons.Outlined.AutoAwesome,
                            contentDescription = null
                        )
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    colors = drawerItemColors
                )
                Divider(modifier = Modifier.padding(vertical = 8.dp), color = Color.Gray.copy(alpha = 0.1f))

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.History, contentDescription = null, tint = AlalBlue)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Chat History",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
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
                        selectedContainerColor = AlalBlue.copy(alpha = 0.1f),
                        selectedTextColor = AlalBlue,
                        selectedIconColor = AlalBlue
                    )
                )

                Spacer(Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f)
                ) {
                    items(sessions, key = { it.id }) { session ->
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
                                    selectedContainerColor = AlalBlue.copy(alpha = 0.1f),
                                    selectedTextColor = AlalBlue,
                                    selectedIconColor = AlalBlue
                                )
                            )
                            
                            IconButton(
                                onClick = { viewModel.deleteSession(session.id) },
                                modifier = Modifier.align(Alignment.CenterEnd).size(32.dp).padding(end = 8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete Chat",
                                    tint = if (isSelected) AlalBlue.copy(alpha = 0.5f) else Color.Gray.copy(alpha = 0.5f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
                
                Divider(modifier = Modifier.padding(vertical = 8.dp), color = Color.Gray.copy(alpha = 0.1f))

                NavigationDrawerItem(
                    label = { Text("Settings") },
                    selected = false,
                    onClick = {
                        showSettings = true
                        scope.launch { drawerState.close() }
                    },
                    icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    label = { Text("Clear All Chats") },
                    selected = false,
                    onClick = {
                        showClearChats = true
                        scope.launch { drawerState.close() }
                    },
                    icon = { Icon(Icons.Outlined.DeleteSweep, contentDescription = null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                // Hidden mid-load: exitDonation() can't restore LoadingModel progress.
                if (uiState != ChatUiState.Initializing && uiState !is ChatUiState.LoadingModel) {
                    NavigationDrawerItem(
                        label = { Text("Donate to Support") },
                        selected = false,
                        onClick = {
                            viewModel.enterDonation()
                            scope.launch { drawerState.close() }
                        },
                        icon = { Icon(Icons.Default.Favorite, contentDescription = null, tint = AlalBlue) },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )
                }
                Spacer(Modifier.height(8.dp))
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
                            // Hidden mid-load: exitDonation() can't restore LoadingModel progress.
                            if (uiState != ChatUiState.Initializing && uiState !is ChatUiState.LoadingModel) {
                                IconButton(onClick = { viewModel.enterDonation() }) {
                                    Icon(
                                        Icons.Default.Favorite,
                                        contentDescription = "Support",
                                        tint = AlalBlue
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
                    currentTab == AppTab.DOCS -> CenterAlignedTopAppBar(
                        title = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "Documents",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Knowledge Base",
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
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        )
                    )
                    currentTab == AppTab.DIGEST -> CenterAlignedTopAppBar(
                        title = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "Summaries & To-dos",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "From your phone activity",
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
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        )
                    )
                    currentTab == AppTab.PROMPTS -> CenterAlignedTopAppBar(
                        title = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "System Prompts",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Persona & tone",
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
                            if (systemPromptSettings.isConfigured) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = "System prompt active",
                                    tint = AlalBlue,
                                    modifier = Modifier.padding(end = 16.dp).size(20.dp)
                                )
                            }
                        },
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        )
                    )
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
                            IconButton(onClick = { modelFileLauncher.launch(arrayOf("*/*")) }) {
                                Icon(Icons.Outlined.FileOpen, contentDescription = "Import .gguf file")
                            }
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
                } else if (currentTab == AppTab.DIGEST && uiState != ChatUiState.Donating) {
                    Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                        DigestScreen(viewModel = androidx.lifecycle.viewmodel.compose.viewModel<PersonalViewModel>())
                    }
                } else if (currentTab == AppTab.DOCS && uiState != ChatUiState.Donating) {
                    Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                        DocumentsScreen(
                            documents = documents,
                            onDelete = viewModel::deleteDocument
                        )
                    }
                } else if (currentTab == AppTab.PROMPTS && uiState != ChatUiState.Donating) {
                    Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                        SystemPromptScreen(
                            savedSettings = systemPromptSettings,
                            onSave = viewModel::updateSystemPromptSettings,
                            onReset = viewModel::resetSystemPromptSettings
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
                                    CircularProgressIndicator(color = AlalBlue)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text("Starting Alal Engine...", color = MaterialTheme.colorScheme.onBackground)
                                }
                            }
                        }
                        is ChatUiState.SelectingModel -> {
                            val state = uiState as ChatUiState.SelectingModel
                            // Scoped here so download progress ticks don't
                            // recompose the drawer/top bar.
                            val downloadedModelIds by viewModel.downloadedModelIds.collectAsState()
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
                                CircularProgressIndicator(color = AlalBlue)
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
                                            border = BorderStroke(1.dp, AlalBlue)
                                        ) {
                                            Text("Switch Model", color = AlalBlue)
                                        }

                                        Button(
                                            onClick = { viewModel.newChat() },
                                            colors = ButtonDefaults.buttonColors(containerColor = AlalBlue)
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
                                            colors = ButtonDefaults.buttonColors(containerColor = AlalBlue),
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
                            // Collected here, not at the top of ChatScreen: the
                            // message list changes ~10x/second while a reply
                            // streams, and at the top it recomposed the drawer,
                            // top bar and composer along with it.
                            val messages by viewModel.messages.collectAsState()
                            val actionStates by viewModel.actionStates.collectAsState()
                            MessageList(
                                messages = messages,
                                onRegenerate = { viewModel.regenerate() },
                                actionStates = actionStates,
                                onConfirmAction = viewModel::confirmAction,
                                onDismissAction = viewModel::dismissAction,
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
                                onAddExisting = { showDocumentPicker = true },
                                isWebSearchEnabled = isWebSearchEnabled,
                                onToggleWebSearch = { viewModel.toggleWebSearch() },
                                webSearchAllowed = webSearchAllowed,
                                isThinkingEnabled = generationSettings.enableThinking,
                                onToggleThinking = { viewModel.toggleThinking() },
                                attachedDocuments = attachedDocuments,
                                onDetachDocument = viewModel::detachDocument
                            )
                        }
                    }
                }
                }

                // App version is shown at the bottom of the Settings sheet.
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadBadge(viewModel: ChatViewModel) {
    // Collected locally: download progress updates several times a second and
    // should only repaint this badge, not the whole screen.
    val downloadStates by viewModel.downloadStates.collectAsState()
    if (downloadStates.isNotEmpty()) {
        Badge { Text("${downloadStates.size}") }
    }
}

/**
 * Everything that used to live in the navigation drawer apart from the chat
 * list: screen navigation, generation settings, chat cleanup and donations.
 * Keeping it here leaves the drawer's full height to chat history.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    currentTab: AppTab,
    onDismiss: () -> Unit,
    onSelectTab: (AppTab) -> Unit,
    onGenerationSettings: () -> Unit,
    webSearchAllowed: Boolean,
    onWebSearchAllowedChange: (Boolean) -> Unit
) {
    val itemColors = NavigationDrawerItemDefaults.colors(
        unselectedContainerColor = Color.Transparent,
        selectedContainerColor = AlalBlue.copy(alpha = 0.1f),
        selectedTextColor = AlalBlue,
        selectedIconColor = AlalBlue
    )

    @Composable
    fun TabItem(tab: AppTab, label: String, filled: androidx.compose.ui.graphics.vector.ImageVector, outlined: androidx.compose.ui.graphics.vector.ImageVector, badge: @Composable () -> Unit = {}) {
        val selected = currentTab == tab
        NavigationDrawerItem(
            label = { Text(label) },
            selected = selected,
            onClick = { onSelectTab(tab) },
            icon = { BadgedBox(badge = { badge() }) { Icon(if (selected) filled else outlined, contentDescription = null) } },
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
            colors = itemColors
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Settings, contentDescription = null, tint = AlalBlue)
                Spacer(Modifier.width(12.dp))
                Text("Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Divider(modifier = Modifier.padding(vertical = 8.dp), color = Color.Gray.copy(alpha = 0.1f))

            TabItem(AppTab.DOCS, "Documents", Icons.Filled.Description, Icons.Outlined.Description)
            TabItem(AppTab.PROMPTS, "System Prompts", Icons.Filled.Psychology, Icons.Outlined.Psychology)

            Divider(modifier = Modifier.padding(vertical = 8.dp), color = Color.Gray.copy(alpha = 0.1f))

            NavigationDrawerItem(
                label = { Text("Generation Settings") },
                selected = false,
                onClick = onGenerationSettings,
                icon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                colors = itemColors
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Public, contentDescription = null, tint = if (webSearchAllowed) AlalBlue else Color.Gray)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Web search", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Off means no web requests are made, even when the composer toggle is on.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                }
                Switch(
                    checked = webSearchAllowed,
                    onCheckedChange = onWebSearchAllowedChange,
                    colors = SwitchDefaults.colors(checkedTrackColor = AlalBlue)
                )
            }

            Text(
                text = APP_VERSION,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = Color.Gray.copy(alpha = 0.5f),
                    fontSize = 10.sp
                ),
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 10.dp)
            )
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
                "Alal",
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
                tint = AlalBlue,
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
                    colors = ButtonDefaults.buttonColors(containerColor = AlalBlue),
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
                                    AlalBlue.copy(alpha = 0.08f)
                                else
                                    MaterialTheme.colorScheme.surface
                            ),
                            border = if (isSelected) BorderStroke(1.dp, AlalBlue) else null,
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
                                    tint = AlalBlue,
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
                    colors = ButtonDefaults.buttonColors(containerColor = AlalBlue),
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
fun MessageList(
    messages: List<ChatMessage>,
    onRegenerate: () -> Unit,
    modifier: Modifier = Modifier,
    actionStates: Map<String, String> = emptyMap(),
    onConfirmAction: (String, PhoneAction) -> Unit = { _, _ -> },
    onDismissAction: (String) -> Unit = {}
) {
    val listState = rememberLazyListState()
    val lastMessage = messages.lastOrNull()
    val lastMessageId = lastMessage?.id

    // True while the newest message is fully visible. Reading this state inside
    // the effect (not during composition) matters: reading listState values in
    // composition makes the whole list recompose on every scroll frame, which
    // is what made scrolling stutter.
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            lastVisible.index == info.totalItemsCount - 1 &&
                lastVisible.offset + lastVisible.size <= info.viewportEndOffset + 8
        }
    }

    // Follow the answer while it streams, but only when the user hasn't
    // scrolled away to read something.
    LaunchedEffect(lastMessageId, lastMessage?.text, messages.size) {
        if (messages.isNotEmpty() && isAtBottom) {
            listState.scrollToItem(messages.lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(
            items = messages,
            key = { it.id },
            // Separate reuse pools: user rows are cheap, bot rows render markdown.
            contentType = { if (it.isUser) "user" else "assistant" }
        ) { message ->
            MessageBubble(
                message = message,
                isLastMessage = message.id == lastMessageId,
                onRegenerate = onRegenerate,
                actionStates = actionStates,
                onConfirmAction = onConfirmAction,
                onDismissAction = onDismissAction
            )
        }
    }
}

@Composable
fun MessageBubble(
    message: ChatMessage,
    isLastMessage: Boolean = false,
    onRegenerate: () -> Unit = {},
    actionStates: Map<String, String> = emptyMap(),
    onConfirmAction: (String, PhoneAction) -> Unit = { _, _ -> },
    onDismissAction: (String) -> Unit = {}
) {
    val isUser = message.isUser
    // Action tags are machine-readable; show the sentence and render confirm cards instead.
    val displayText = remember(message.text, isUser) {
        if (isUser) message.text else PhoneActionParser.strip(message.text)
    }
    val actions = remember(message.text, message.isGenerating, isUser) {
        if (isUser || message.isGenerating) emptyList() else PhoneActionParser.parse(message.text)
    }
    var isThoughtExpanded by remember { mutableStateOf(true) }
    val context = androidx.compose.ui.platform.LocalContext.current
    
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
                    .background(AlalBlue),
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
                    "Alal",
                    style = TextStyle(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = Color.Gray
                    ),
                    modifier = Modifier.padding(bottom = 4.dp, start = 4.dp)
                )
            }

            // Thought Block (Alal style)
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
                            ThinkingAnimation(color = AlalBlue)
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

            if (displayText.isNotEmpty()) {
                Surface(
                    color = if (isUser) AlalBlue else Color.Transparent,
                    shape = RoundedCornerShape(
                        topStart = if (isUser) 16.dp else 4.dp,
                        topEnd = 16.dp,
                        bottomStart = 16.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp
                    ),
                    border = if (isUser) null else BorderStroke(1.dp, Color.Gray.copy(alpha = 0.1f))
                ) {
                    // Format live as tokens arrive so markdown renders immediately,
                    // instead of staying plain text until generation completes.
                    SelectionContainer {
                        Material3RichText(
                            modifier = Modifier.padding(12.dp)
                        ) {
                            ProvideTextStyle(MaterialTheme.typography.bodyLarge.copy(
                                color = if (isUser) Color.White else MaterialTheme.colorScheme.onSurface,
                                lineHeight = 22.sp
                            )) {
                                Markdown(content = displayText)
                            }
                        }
                    }
                }
            }

            actions.forEachIndexed { index, action ->
                val key = "${message.id}#$index"
                PhoneActionCard(
                    action = action,
                    state = actionStates[key],
                    onConfirm = { onConfirmAction(key, action) },
                    onDismiss = { onDismissAction(key) }
                )
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
                            onClick = { copySensitive(context, displayText) },
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
    onAddExisting: () -> Unit = {},
    isWebSearchEnabled: Boolean,
    onToggleWebSearch: () -> Unit,
    webSearchAllowed: Boolean = true,
    isThinkingEnabled: Boolean,
    onToggleThinking: () -> Unit,
    attachedDocuments: List<IngestedDocument> = emptyList(),
    onDetachDocument: (Long) -> Unit = {}
) {
    var textState by remember { mutableStateOf(TextFieldValue("")) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        if (attachedDocuments.isNotEmpty()) {
            AttachmentStrip(documents = attachedDocuments, onDetach = onDetachDocument)
            Spacer(modifier = Modifier.height(8.dp))
        }
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
                            tint = AlalBlue,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = optionsExpanded,
                        onDismissRequest = { optionsExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Attach file") },
                            leadingIcon = { Icon(Icons.Outlined.AttachFile, contentDescription = null, tint = AlalBlue) },
                            onClick = {
                                optionsExpanded = false
                                onAttach()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Add from documents") },
                            leadingIcon = { Icon(Icons.Outlined.Description, contentDescription = null, tint = AlalBlue) },
                            onClick = {
                                optionsExpanded = false
                                onAddExisting()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Web search") },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (isWebSearchEnabled) Icons.Filled.Public else Icons.Outlined.Public,
                                    contentDescription = null,
                                    tint = if (isWebSearchEnabled) AlalBlue else Color.Gray
                                )
                            },
                            trailingIcon = {
                                if (isWebSearchEnabled) Icon(Icons.Default.Check, contentDescription = null, tint = AlalBlue)
                            },
                            enabled = webSearchAllowed,
                            onClick = { onToggleWebSearch() }
                        )
                        DropdownMenuItem(
                            text = { Text("Thinking") },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Psychology,
                                    contentDescription = null,
                                    tint = if (isThinkingEnabled) AlalBlue else Color.Gray
                                )
                            },
                            trailingIcon = {
                                if (isThinkingEnabled) Icon(Icons.Default.Check, contentDescription = null, tint = AlalBlue)
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
                    cursorBrush = SolidColor(AlalBlue),
                    decorationBox = { innerTextField ->
                        if (textState.text.isEmpty()) {
                            Text(
                                "Message Alal...",
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
                            .background(if (textState.text.isNotBlank()) AlalBlue else Color.LightGray)
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
            "Alal can make mistakes. Check important info.",
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
                Text(if (hfToken.isBlank()) "Set" else "Edit", color = AlalBlue)
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
                    colors = ButtonDefaults.buttonColors(containerColor = AlalBlue)
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

/**
 * Copy a reply flagged as sensitive, so Android 13+ hides it from the clipboard
 * preview and keyboards' clipboard history.
 */
private fun copySensitive(context: android.content.Context, text: String) {
    val clip = android.content.ClipData.newPlainText("Alal", text)
    clip.description.extras = android.os.PersistableBundle().apply {
        putBoolean("android.content.extra.IS_SENSITIVE", true)
    }
    (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
}
