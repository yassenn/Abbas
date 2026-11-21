package ai.alal.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import ai.alal.app.data.DigestEntity
import ai.alal.app.data.PersonalSource
import ai.alal.app.data.TodoEntity
import ai.alal.app.personal.HealthCollector
import androidx.health.connect.client.PermissionController
import ai.alal.app.ui.theme.AlalBlue
import ai.alal.app.viewmodel.PersonalViewModel
import ai.alal.app.viewmodel.SourceState
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.material3.Material3RichText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Summaries & To-dos tab: digests and tasks drafted from the user's phone activity. */
@Composable
fun DigestScreen(viewModel: PersonalViewModel) {
    val sources by viewModel.sources.collectAsState()
    val message by viewModel.message.collectAsState()
    var page by rememberSaveable { mutableStateOf(0) }

    // Permissions and system toggles are granted in other screens; re-read on return.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); viewModel.consumeMessage() }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TabRow(
                selectedTabIndex = page,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = AlalBlue
            ) {
                listOf("Summaries", "Data access", "Write access").forEachIndexed { i, title ->
                    Tab(selected = page == i, onClick = { page = i }, text = { Text(title) })
                }
            }
            when (page) {
                0 -> SummariesPage(viewModel, sources, onOpenSources = { page = 1 })
                1 -> DataAccessPage(viewModel, sources)
                else -> WriteAccessPage(viewModel, sources)
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

private fun SourceState.anyOn(): Boolean =
    sms || calls || notifications || (files && folders.isNotEmpty()) || allFiles || (email && emailAccounts.isNotEmpty()) || usage || calendar || health || location || screen

@Composable
private fun SummariesPage(viewModel: PersonalViewModel, sources: SourceState, onOpenSources: () -> Unit) {
    val todos by viewModel.todos.collectAsState()
    val digests by viewModel.digests.collectAsState()
    val pending by viewModel.pendingCount.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val routine by viewModel.routine.collectAsState()
    var showErase by remember { mutableStateOf(false) }

    if (showErase) {
        AlertDialog(
            onDismissRequest = { showErase = false },
            title = { Text("Erase collected data?") },
            text = { Text("Deletes every collected message, call, notification, document, usage, health, location and screen record, plus all summaries and to-dos. Your chats are not affected.") },
            confirmButton = {
                TextButton(onClick = { viewModel.eraseAll(); showErase = false }) {
                    Text("Erase", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showErase = false }) { Text("Cancel") } }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ControlCard(
                sources = sources,
                pending = pending,
                progress = progress,
                onBackground = viewModel::setBackground,
                onSummarize = viewModel::summarizeNow,
                enabled = sources.anyOn()
            )
        }
        if (!sources.anyOn()) {
            item {
                TextButton(onClick = onOpenSources) {
                    Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Turn on a data source in Data access")
                }
            }
        }

        item { SummarySettingsCard(viewModel, sources) }
        item { SummaryInstructionsCard(viewModel, sources) }

        item { DmCheckCard(viewModel, sources, busy = progress != null) }

        item { RoutineCard(routine?.describe()) }

        item {
            SectionHeader("To-dos", Icons.Outlined.Checklist) {
                if (todos.any { it.done }) {
                    TextButton(onClick = { viewModel.clearDoneTodos() }) { Text("Clear done") }
                }
            }
        }
        if (todos.isEmpty()) {
            item { EmptyLine("No to-dos yet. They're drafted from your activity when you summarize, or added from chat.") }
        } else {
            items(todos, key = { "todo-${it.id}" }) { todo ->
                TodoRow(todo, onToggle = { viewModel.setTodoDone(todo, it) }, onDelete = { viewModel.deleteTodo(todo) })
            }
        }

        item { SectionHeader("Summaries", Icons.Outlined.AutoAwesome) }
        if (digests.isEmpty()) {
            item { EmptyLine("No summaries yet. Turn on a data source and tap Summarize now.") }
        } else {
            items(digests, key = { "digest-${it.id}" }) { digest ->
                DigestCard(digest, onDelete = { viewModel.deleteDigest(digest) })
            }
        }

        item {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Everything is collected and summarized on this phone, in an encrypted database. Nothing is uploaded.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
                TextButton(onClick = { showErase = true }) {
                    Text("Erase collected data", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun RoutineCard(text: String?) {
    OutlinedCard(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.15f))) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Schedule, contentDescription = null, tint = AlalBlue, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Your routine", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            if (text == null) {
                Text(
                    "Turn on App usage (and Location for places) in Data access, and Alal will learn your daily routine.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            } else {
                Material3RichText {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp)) {
                        Markdown(content = text.lines().drop(1).joinToString("\n"))
                    }
                }
            }
        }
    }
}

@Composable
private fun DataAccessPage(viewModel: PersonalViewModel, sources: SourceState) {
    val counts by viewModel.counts.collectAsState()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SourcesCard(sources, counts.associate { it.source to it.count }, viewModel) }
        item { EmailAndImportCard(viewModel, sources) }
        item { NetworkPrivacyCard() }
    }
}

@Composable
private fun ControlCard(
    sources: SourceState,
    pending: Int,
    progress: String?,
    onBackground: (Boolean) -> Unit,
    onSummarize: () -> Unit,
    enabled: Boolean
) {
    OutlinedCard(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.15f))) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Background summaries", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (sources.background) "About every 4 hours while charging"
                        else "Off — summaries only run when you tap Summarize now",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
                Switch(
                    checked = sources.background,
                    onCheckedChange = onBackground,
                    colors = SwitchDefaults.colors(checkedTrackColor = AlalBlue)
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onSummarize,
                    enabled = enabled && progress == null,
                    colors = ButtonDefaults.buttonColors(containerColor = AlalBlue)
                ) {
                    if (progress != null) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Summarize now")
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    progress ?: buildString {
                        append(if (pending == 1) "1 new item" else "$pending new items")
                        if (sources.lastRunAt > 0) append(" · last run ${formatStamp(sources.lastRunAt)}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
private fun SourcesCard(sources: SourceState, counts: Map<String, Int>, viewModel: PersonalViewModel) {
    val context = LocalContext.current
    fun open(action: String, withPackage: Boolean = false) {
        val intent = Intent(action)
        if (withPackage) intent.data = Uri.parse("package:${context.packageName}")
        try { context.startActivity(intent) } catch (_: Exception) { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.setSms(it[Manifest.permission.READ_SMS] == true)
    }
    val callPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.setCalls(it[Manifest.permission.READ_CALL_LOG] == true)
    }
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.setCalendar(it)
    }
    val contactsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.setContacts(it)
    }
    val healthPermission = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted -> viewModel.setHealth(granted.isNotEmpty()) }
    val backgroundLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.refresh()
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val ok = r[Manifest.permission.ACCESS_FINE_LOCATION] == true || r[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        viewModel.setLocation(ok)
        // "All the time" must be asked separately, after foreground access (Android 11+).
        if (ok && Build.VERSION.SDK_INT >= 29) backgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::addFolder)
    }

    OutlinedCard(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.15f))) {
        Column(Modifier.padding(vertical = 8.dp)) {
            GroupLabel("Messages & calls")
            SourceRow(Icons.Outlined.Sms, "Text messages", countLabel(counts[PersonalSource.SMS.name]), sources.sms) { on ->
                if (on) smsPermission.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS))
                else viewModel.setSms(false)
            }
            SourceRow(Icons.Outlined.Call, "Call log", countLabel(counts[PersonalSource.CALL.name]), sources.calls) { on ->
                if (on) callPermission.launch(arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS))
                else viewModel.setCalls(false)
            }
            SourceRow(
                Icons.Outlined.Notifications, "App notifications",
                if (sources.notifications && !sources.notificationAccess) "Tap to grant notification access"
                else "WhatsApp, Telegram, email and more · " + countLabel(counts[PersonalSource.NOTIFICATION.name]),
                sources.notifications && sources.notificationAccess,
                onClick = if (sources.notifications && !sources.notificationAccess) {
                    { open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
                } else null
            ) { on ->
                viewModel.setNotifications(on)
                if (on && !sources.notificationAccess) open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            }
            SourceRow(Icons.Outlined.Contacts, "Contacts", "Names, numbers and emails for chat and messages", sources.contacts) { on ->
                if (on) contactsPermission.launch(Manifest.permission.READ_CONTACTS) else viewModel.setContacts(false)
            }

            GroupLabel("Your day")
            SourceRow(
                Icons.Outlined.QueryStats, "App usage & routine",
                if (sources.usage && !sources.usageAccess) "Tap to grant Usage access"
                else "Screen time per app, wake and sleep times · " + countLabel(counts[PersonalSource.USAGE.name]),
                sources.usage && sources.usageAccess,
                onClick = if (sources.usage && !sources.usageAccess) {
                    { open(Settings.ACTION_USAGE_ACCESS_SETTINGS) }
                } else null
            ) { on ->
                viewModel.setUsage(on)
                if (on && !sources.usageAccess) open(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            }
            SourceRow(Icons.Outlined.Event, "Calendar", "Upcoming events · " + countLabel(counts[PersonalSource.CALENDAR.name]), sources.calendar) { on ->
                if (on) calendarPermission.launch(Manifest.permission.READ_CALENDAR) else viewModel.setCalendar(false)
            }
            SourceRow(
                Icons.Outlined.FavoriteBorder, "Health",
                when {
                    !sources.healthAvailable -> "Needs the Health Connect app"
                    sources.health && !sources.healthGranted -> "Tap to grant Health Connect access"
                    else -> "Steps, sleep, heart rate, workouts · " + countLabel(counts[PersonalSource.HEALTH.name])
                },
                sources.health && sources.healthGranted,
                onClick = if (!sources.healthAvailable) {
                    {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata")))
                        } catch (_: Exception) { }
                    }
                } else null
            ) { on ->
                if (!on) viewModel.setHealth(false)
                else if (sources.healthAvailable) healthPermission.launch(HealthCollector.PERMISSIONS)
            }
            SourceRow(
                Icons.Outlined.Place, "Location",
                when {
                    sources.location && !sources.backgroundLocation -> "Tap to allow \"All the time\" so places are tracked"
                    else -> "Places & visits from now on, never uploaded · " + countLabel(counts[PersonalSource.LOCATION.name])
                },
                sources.location,
                onClick = if (sources.location && !sources.backgroundLocation) {
                    { open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, withPackage = true) }
                } else null
            ) { on ->
                if (on) locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                else viewModel.setLocation(false)
            }

            GroupLabel("Files & screen")
            val allFilesPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                viewModel.setAllFiles(it)
            }
            SourceRow(
                Icons.Outlined.SdStorage, "All files on phone",
                if (sources.allFiles && !sources.allFilesAccess) "Tap to grant \"All files access\""
                else "PDF, Word and text files in all folders · " + countLabel(counts[PersonalSource.FILE.name]),
                sources.allFiles && sources.allFilesAccess,
                onClick = if (sources.allFiles && !sources.allFilesAccess) {
                    { open(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, withPackage = true) }
                } else null
            ) { on ->
                when {
                    !on -> viewModel.setAllFiles(false)
                    Build.VERSION.SDK_INT >= 30 -> {
                        viewModel.setAllFiles(true)
                        if (!sources.allFilesAccess) open(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, withPackage = true)
                    }
                    else -> allFilesPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
            SourceRow(
                Icons.Outlined.Folder, "Specific folders",
                if (sources.folders.isEmpty()) "Pick a folder such as Downloads" else countLabel(counts[PersonalSource.FILE.name]),
                sources.files && sources.folders.isNotEmpty()
            ) { on ->
                if (on && sources.folders.isEmpty()) folderPicker.launch(null) else viewModel.setFiles(on)
            }
            sources.folders.forEach { folder ->
                Row(Modifier.fillMaxWidth().padding(start = 56.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(folderName(folder), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel.removeFolder(folder) }) {
                        Icon(Icons.Outlined.Close, contentDescription = "Stop watching folder", modifier = Modifier.size(16.dp))
                    }
                }
            }
            if (sources.folders.isNotEmpty()) {
                TextButton(onClick = { folderPicker.launch(null) }, modifier = Modifier.padding(start = 48.dp)) {
                    Text("Add another folder")
                }
            }
            SourceRow(
                Icons.Outlined.Visibility, "Screen reader",
                if (sources.screen && !sources.screenAccess) "Tap to switch on Alal in Accessibility settings"
                else "Reads text shown in any app · " + countLabel(counts[PersonalSource.SCREEN.name]),
                sources.screen && sources.screenAccess,
                onClick = if (sources.screen && !sources.screenAccess) {
                    { open(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
                } else null
            ) { on ->
                viewModel.setScreen(on)
                if (on && !sources.screenAccess) open(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            }
            if (sources.screen && !sources.screenAccess && Build.VERSION.SDK_INT >= 33) {
                Text(
                    "If Alal is greyed out in Accessibility: open App info → ⋮ menu → Allow restricted settings, then try again.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(horizontal = 56.dp)
                )
                TextButton(onClick = { open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, withPackage = true) }, modifier = Modifier.padding(start = 48.dp)) {
                    Text("Open App info")
                }
            }

            Text(
                "Android keeps other apps' own data (e.g. WhatsApp history) private. Notifications are collected from the moment access is granted; the screen reader captures what you view.",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = AlalBlue,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp)
    )
}

@Composable
private fun WriteAccessPage(viewModel: PersonalViewModel, sources: SourceState) {
    val w = sources.write
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        viewModel.setWrite { it.writeCalendar = r.values.all { granted -> granted } }
    }
    val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        viewModel.setWrite { it.writeSms = r[Manifest.permission.SEND_SMS] == true }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "Choose what Alal may do for you from chat. Every action appears as a card you must Confirm — nothing happens on its own.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
        item {
            OutlinedCard(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.15f))) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    GroupLabel("Phone")
                    SourceRow(Icons.Outlined.Alarm, "Alarms & timers", "Set alarms and timers in your Clock app", w.alarms) { on ->
                        viewModel.setWrite { it.writeAlarms = on }
                    }
                    SourceRow(Icons.Outlined.Event, "Calendar", "Add reminders and events", w.calendar) { on ->
                        if (on) calendarPermission.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                        else viewModel.setWrite { it.writeCalendar = false }
                    }
                    SourceRow(Icons.Outlined.Checklist, "To-dos", "Add items to your To-dos list", w.todos) { on ->
                        viewModel.setWrite { it.writeTodos = on }
                    }
                    GroupLabel("Messages on your behalf")
                    SourceRow(Icons.Outlined.Sms, "Send text messages", "Sent from your SIM after you confirm", w.sms) { on ->
                        if (on) smsPermission.launch(arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS))
                        else viewModel.setWrite { it.writeSms = false }
                    }
                    SourceRow(Icons.Outlined.Email, "Emails", "Opens a ready-to-send draft in your mail app", w.email) { on ->
                        viewModel.setWrite { it.writeEmail = on }
                    }
                    SourceRow(Icons.Outlined.Chat, "WhatsApp, Telegram, Signal", "Opens the chat with your message filled in", w.appMessages) { on ->
                        viewModel.setWrite { it.writeAppMessages = on }
                    }
                }
            }
        }
        item {
            Text(
                "Android doesn't let any app send email or WhatsApp messages silently from your accounts, so those open as drafts and you press send. Text messages are the only thing Alal can send directly. Phone settings stay read-only.",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray
            )
        }
        if (!sources.contacts) {
            item {
                Text(
                    "Tip: turn on Contacts in Data access so you can say “text Mom” instead of typing numbers.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AlalBlue
                )
            }
        }
    }
}

@Composable
private fun SourceRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onClick: (() -> Unit)? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.Gray) },
        leadingContent = { Icon(icon, contentDescription = null, tint = AlalBlue) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(checkedTrackColor = AlalBlue)
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    )
}

@Composable
private fun SectionHeader(title: String, icon: ImageVector, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = AlalBlue, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun TodoRow(todo: TodoEntity, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    val overdue = !todo.done && todo.dueAt != null && todo.dueAt < System.currentTimeMillis()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = todo.done,
            onCheckedChange = onToggle,
            colors = CheckboxDefaults.colors(checkedColor = AlalBlue)
        )
        Column(Modifier.weight(1f)) {
            Text(
                todo.text,
                textDecoration = if (todo.done) TextDecoration.LineThrough else null,
                color = if (todo.done) Color.Gray else MaterialTheme.colorScheme.onBackground
            )
            val meta = listOfNotNull(
                todo.dueAt?.let { "Due ${formatStamp(it)}" },
                todo.origin?.let { "from $it" }
            ).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (overdue) MaterialTheme.colorScheme.error else Color.Gray
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = "Delete to-do", tint = Color.Gray, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun DigestCard(digest: DigestEntity, onDelete: () -> Unit) {
    OutlinedCard(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.15f))) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(formatStamp(digest.createdAt), fontWeight = FontWeight.SemiBold)
                    Text(
                        "${digest.itemCount} items · ${formatStamp(digest.periodStart)} – ${formatStamp(digest.periodEnd)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Delete summary", tint = Color.Gray, modifier = Modifier.size(18.dp))
                }
            }
            Material3RichText(Modifier.padding(end = 12.dp, top = 4.dp)) {
                ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp)) {
                    Markdown(content = digest.summary)
                }
            }
        }
    }
}

private fun countLabel(count: Int?): String =
    if (count == null || count == 0) "Nothing collected yet" else "$count collected"

private fun folderName(treeUri: String): String = try {
    DocumentsContract.getTreeDocumentId(Uri.parse(treeUri)).substringAfter(':').ifBlank { "Internal storage" }
} catch (_: Exception) {
    treeUri
}

private fun formatStamp(at: Long): String = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(at))
