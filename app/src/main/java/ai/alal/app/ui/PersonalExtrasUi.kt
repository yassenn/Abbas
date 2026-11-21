package ai.alal.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ai.alal.app.data.EmailAccount
import ai.alal.app.ui.theme.AlalBlue
import ai.alal.app.viewmodel.PersonalViewModel
import ai.alal.app.viewmodel.SourceState

/** "Check DMs now": opens the chosen apps' inboxes through the screen reader. */
@Composable
internal fun DmCheckCard(viewModel: PersonalViewModel, sources: SourceState, busy: Boolean) {
    if (sources.installedDmApps.isEmpty()) return
    OutlinedCard(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.15f))) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Forum, contentDescription = null, tint = AlalBlue, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Check DMs & inboxes", fontWeight = FontWeight.SemiBold)
            }
            Text(
                if (sources.screenAccess) "Alal opens each app's inbox, reads it, comes back and drafts to-dos. Keep the phone unlocked."
                else "Needs Screen reader switched on in Data access.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sources.installedDmApps.forEach { app ->
                    val selected = app.pkg in sources.dmApps
                    FilterChip(
                        selected = selected,
                        onClick = { viewModel.toggleDmApp(app.pkg, !selected) },
                        label = { Text(app.label) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = viewModel::checkDms,
                enabled = !busy && sources.screenAccess && sources.dmApps.any { p -> sources.installedDmApps.any { it.pkg == p } },
                colors = ButtonDefaults.buttonColors(containerColor = AlalBlue)
            ) { Text("Check DMs now") }
        }
    }
}

/** Email accounts (IMAP) plus the import entry point. */
@Composable
internal fun EmailAndImportCard(viewModel: PersonalViewModel, sources: SourceState) {
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<String?>(null) } // "gmail" | "other" | "outlook"
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.importFiles(uris)
    }

    when (dialog) {
        "gmail" -> PasswordAccountDialog(
            title = "Add Gmail account",
            help = "Use an app password, not your Google password: turn on 2-Step Verification, then create one at myaccount.google.com/apppasswords. Work (Google Workspace) accounts don't allow this.",
            helpUrl = "https://myaccount.google.com/apppasswords",
            defaultHost = EmailAccount.GMAIL_HOST,
            showServer = false,
            onDismiss = { dialog = null },
            onAdd = { email, pw, host, port -> viewModel.addPasswordAccount("Gmail", email, pw, host, port); dialog = null }
        )
        "other" -> PasswordAccountDialog(
            title = "Add IMAP account",
            help = "Any provider with IMAP (Fastmail, Proton via Bridge, iCloud with an app password, work servers…).",
            helpUrl = null,
            defaultHost = "",
            showServer = true,
            onDismiss = { dialog = null },
            onAdd = { email, pw, host, port -> viewModel.addPasswordAccount("", email, pw, host, port); dialog = null }
        )
        "outlook" -> OutlookDialog(viewModel, onDismiss = { viewModel.cancelOutlook(); dialog = null })
    }

    OutlinedCard(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.15f))) {
        Column(Modifier.padding(vertical = 8.dp)) {
            ListItem(
                headlineContent = { Text("Email") },
                supportingContent = {
                    Text(
                        if (sources.emailAccounts.isEmpty()) "Add Gmail, Outlook or IMAP accounts below"
                        else "${sources.emailAccounts.size} account(s) · read-only, nothing is marked read",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray
                    )
                },
                leadingContent = { Icon(Icons.Outlined.Email, contentDescription = null, tint = AlalBlue) },
                trailingContent = {
                    Switch(
                        checked = sources.email && sources.emailAccounts.isNotEmpty(),
                        onCheckedChange = viewModel::setEmail,
                        enabled = sources.emailAccounts.isNotEmpty(),
                        colors = SwitchDefaults.colors(checkedTrackColor = AlalBlue)
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
            sources.emailAccounts.forEach { acc ->
                Row(Modifier.fillMaxWidth().padding(start = 56.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${acc.label} · ${acc.email}", style = MaterialTheme.typography.bodySmall)
                        acc.lastError?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 2)
                        }
                    }
                    IconButton(onClick = { viewModel.removeEmailAccount(acc.id) }) {
                        Icon(Icons.Outlined.Close, contentDescription = "Remove account", modifier = Modifier.size(16.dp))
                    }
                }
            }
            Row(Modifier.padding(start = 48.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { dialog = "gmail" }) { Text("+ Gmail") }
                TextButton(onClick = { dialog = "outlook" }) { Text("+ Outlook") }
                TextButton(onClick = { dialog = "other" }) { Text("+ Other") }
            }
            Text(
                "Tuta has no IMAP by design, so Alal reads Tuta through App notifications and Check DMs.",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            HorizontalDividerCompat()

            ListItem(
                headlineContent = { Text("Import chat history") },
                supportingContent = {
                    Text(
                        "WhatsApp: open a chat → ⋮ → More → Export chat → Alal. Or pick a Telegram Desktop export or an Instagram / Facebook / X data download (.zip).",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray
                    )
                },
                leadingContent = { Icon(Icons.Outlined.FileDownload, contentDescription = null, tint = AlalBlue) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
            Row(Modifier.padding(start = 48.dp)) {
                TextButton(onClick = { importPicker.launch(arrayOf("*/*")) }) { Text("Choose files") }
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://accountscenter.instagram.com/info_and_permissions/dyi/")))
                }) { Text("Get Instagram data") }
            }
        }
    }
}

@Composable
private fun HorizontalDividerCompat() {
    Divider(Modifier.padding(vertical = 8.dp), color = Color.Gray.copy(alpha = 0.1f))
}

@Composable
private fun PasswordAccountDialog(
    title: String,
    help: String,
    helpUrl: String?,
    defaultHost: String,
    showServer: Boolean,
    onDismiss: () -> Unit,
    onAdd: (email: String, password: String, host: String, port: Int) -> Unit
) {
    val context = LocalContext.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var host by remember { mutableStateOf(defaultHost) }
    var port by remember { mutableStateOf("993") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(help, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                if (helpUrl != null) {
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(helpUrl))) }) {
                        Text("Create app password")
                    }
                }
                OutlinedTextField(
                    value = email, onValueChange = { email = it }, label = { Text("Email address") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, label = { Text(if (helpUrl != null) "App password" else "Password") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                if (showServer) {
                    OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("IMAP server") }, singleLine = true)
                    OutlinedTextField(
                        value = port, onValueChange = { port = it.filter(Char::isDigit) }, label = { Text("Port (SSL)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(email, password, host, port.toIntOrNull() ?: 993) },
                enabled = email.contains('@') && password.isNotBlank() && host.isNotBlank()
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun OutlookDialog(viewModel: PersonalViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state by viewModel.outlook.collectAsState()
    var email by remember { mutableStateOf("") }
    var clientId by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Outlook account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.code == null) {
                    Text(
                        "Microsoft only allows sign-in through its own login. One-time setup: in the Azure portal → App registrations → New registration, choose \"Personal Microsoft accounts only\", turn on \"Allow public client flows\", then paste the Application (client) ID here.",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray
                    )
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://portal.azure.com/#view/Microsoft_AAD_RegisteredApps/ApplicationsListBlade")))
                    }) { Text("Open Azure app registrations") }
                    OutlinedTextField(
                        value = email, onValueChange = { email = it }, label = { Text("Outlook / Hotmail address") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                    )
                    OutlinedTextField(value = clientId, onValueChange = { clientId = it.trim() }, label = { Text("Application (client) ID") }, singleLine = true)
                } else {
                    Text("Open the Microsoft page, sign in, and enter this code:")
                    Text(state.code!!, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace, color = AlalBlue)
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.url ?: "https://microsoft.com/devicelogin")))
                    }) { Text("Open microsoft.com/devicelogin") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AlalBlue)
                        Spacer(Modifier.width(8.dp))
                        Text("Waiting for you to finish signing in…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (state.code == null) {
                TextButton(
                    onClick = { viewModel.startOutlook(clientId, email) },
                    enabled = !state.busy && email.contains('@') && clientId.length >= 30
                ) { Text("Sign in") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private val SUMMARY_PRESETS = listOf(
    "Short" to "Keep the summary to 5 bullets at most, most important first.",
    "By person" to "Group the summary by person or conversation.",
    "Work first" to "Put work-related items first, personal ones after.",
    "Money" to "Always flag anything about money, bills, payments or invoices, even if minor.",
    "No promos" to "Ignore newsletters, promotions, shipping updates and social media likes or follows.",
    "Deadlines only" to "Only create to-dos that have a clear deadline or someone waiting on me.",
    "Draft replies" to "For each reply I owe, add a one-line suggested reply to the to-do."
)

/** The user's own system prompt for digests, with quick presets. */
@Composable
internal fun SummaryInstructionsCard(viewModel: PersonalViewModel, sources: SourceState) {
    var expanded by remember { mutableStateOf(false) }
    var draft by remember(sources.summaryInstructions) { mutableStateOf(sources.summaryInstructions) }
    OutlinedCard(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.15f))) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.EditNote, contentDescription = null, tint = AlalBlue, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Summary instructions", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (sources.summaryInstructions.isBlank()) "Tell Alal how to summarize: focus, tone, what to ignore"
                        else sources.summaryInstructions.lines().first().take(60) + if (sources.summaryInstructions.length > 60) "…" else "",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 1
                    )
                }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Close" else "Edit") }
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SUMMARY_PRESETS.forEach { (label, rule) ->
                        val on = draft.contains(rule)
                        FilterChip(
                            selected = on,
                            onClick = {
                                draft = if (on) draft.replace(rule, "").lines().filter { it.isNotBlank() }.joinToString("\n")
                                else listOf(draft.trim(), rule).filter { it.isNotBlank() }.joinToString("\n")
                            },
                            label = { Text(label) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(2_000) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    placeholder = { Text("e.g. I'm a contractor: always flag client emails and invoices. Ignore group chats unless I'm mentioned.") },
                    supportingText = { Text("${draft.length}/2000 · applied to every summary, background or manual") }
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (sources.summaryInstructions.isNotBlank()) {
                        TextButton(onClick = { draft = ""; viewModel.setSummaryInstructions("") }) {
                            Text("Clear", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(
                        onClick = { viewModel.setSummaryInstructions(draft.trim()); expanded = false },
                        enabled = draft.trim() != sources.summaryInstructions
                    ) { Text("Save", color = AlalBlue, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

/** Every knob for how digests are written, scheduled and filtered. */
@Composable
internal fun SummarySettingsCard(viewModel: PersonalViewModel, sources: SourceState) {
    val o = sources.summaryOptions
    var expanded by remember { mutableStateOf(false) }
    // Text fields are saved explicitly so typing doesn't rewrite settings on every keystroke.
    var language by remember(o.language) { mutableStateOf(o.language) }
    var vip by remember(o.vip) { mutableStateOf(o.vip) }
    var mute by remember(o.mute) { mutableStateOf(o.mute) }
    val textDirty = language != o.language || vip != o.vip || mute != o.mute
    var creativity by remember(o.creativity) { mutableStateOf(o.creativity) }

    OutlinedCard(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.15f))) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Tune, contentDescription = null, tint = AlalBlue, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Summary settings", fontWeight = FontWeight.SemiBold)
                    Text(
                        listOf(
                            label(LENGTHS, o.length), "by " + label(GROUPINGS, o.grouping).lowercase(),
                            label(SCOPES, o.scope).lowercase(), "to-dos " + label(TODO_MODES, o.todoMode).lowercase()
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 1
                    )
                }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Close" else "Adjust") }
            }
            if (!expanded) return@Column

            SettingLabel("Length")
            ChoiceChips(LENGTHS, o.length) { v -> viewModel.updateSummaryOptions { it.copy(length = v) } }
            SettingLabel("Organize by")
            ChoiceChips(GROUPINGS, o.grouping) { v -> viewModel.updateSummaryOptions { it.copy(grouping = v) } }
            SettingLabel("Tone")
            ChoiceChips(TONES, o.tone) { v -> viewModel.updateSummaryOptions { it.copy(tone = v) } }
            SettingLabel("Each summary covers")
            ChoiceChips(SCOPES, o.scope) { v -> viewModel.updateSummaryOptions { it.copy(scope = v) } }

            SettingLabel("Include")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ai.alal.app.data.PersonalSource.values().forEach { src ->
                    val included = src.name !in o.excludedSources
                    FilterChip(
                        selected = included,
                        onClick = {
                            viewModel.updateSummaryOptions {
                                it.copy(excludedSources = if (included) it.excludedSources + src.name else it.excludedSources - src.name)
                            }
                        },
                        label = { Text(src.label) }
                    )
                }
            }

            SettingLabel("To-dos")
            ChoiceChips(TODO_MODES, o.todoMode) { v -> viewModel.updateSummaryOptions { it.copy(todoMode = v) } }
            ToggleLine("Suggest a reply for messages I owe", o.suggestReplies, enabled = o.todoMode != "OFF") { v ->
                viewModel.updateSummaryOptions { it.copy(suggestReplies = v) }
            }
            ToggleLine("Mark urgent items", o.markUrgent) { v -> viewModel.updateSummaryOptions { it.copy(markUrgent = v) } }
            ToggleLine("Combine long runs into one ranked digest (slower)", o.mergePass) { v ->
                viewModel.updateSummaryOptions { it.copy(mergePass = v) }
            }

            SettingLabel("Creativity · ${"%.1f".format(creativity)}")
            Slider(
                value = creativity,
                onValueChange = { creativity = Math.round(it * 10) / 10f },
                onValueChangeFinished = { viewModel.updateSummaryOptions { it.copy(creativity = creativity) } },
                valueRange = 0f..1f,
                steps = 9,
                colors = SliderDefaults.colors(thumbColor = AlalBlue, activeTrackColor = AlalBlue)
            )
            Text("Low sticks to the facts; high reads more between the lines.", style = MaterialTheme.typography.labelSmall, color = Color.Gray)

            SettingLabel("People & filters")
            OutlinedTextField(
                value = vip, onValueChange = { vip = it.take(500) }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Always highlight (★)") }, placeholder = { Text("Ana, Mom, invoices, landlord") }, singleLine = true
            )
            OutlinedTextField(
                value = mute, onValueChange = { mute = it.take(500) }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Always skip") }, placeholder = { Text("Family group, newsletter, Duolingo") }, singleLine = true
            )
            OutlinedTextField(
                value = language, onValueChange = { language = it.take(40) }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Language") }, placeholder = { Text("English") }, singleLine = true
            )
            if (textDirty) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        viewModel.updateSummaryOptions { it.copy(language = language.trim(), vip = vip.trim(), mute = mute.trim()) }
                    }) { Text("Save", color = AlalBlue, fontWeight = FontWeight.SemiBold) }
                }
            }

            SettingLabel("Background schedule")
            ChoiceChips(SCHEDULES, o.scheduleHours.toString()) { v ->
                viewModel.updateSummaryOptions { it.copy(scheduleHours = v.toInt()) }
            }
            ToggleLine("Only while charging", o.requireCharging) { v -> viewModel.updateSummaryOptions { it.copy(requireCharging = v) } }
            if (!o.requireCharging) {
                Text(
                    "Running the model on battery drains it fast; background runs still skip when the battery is low.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error
                )
            }

            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = viewModel::resetSummaryOptions) { Text("Reset to defaults", color = Color.Gray) }
            }
        }
    }
}

private val LENGTHS = listOf("BRIEF" to "Brief", "STANDARD" to "Standard", "DETAILED" to "Detailed")
private val GROUPINGS = listOf("IMPORTANCE" to "Importance", "PERSON" to "Person", "SOURCE" to "Source", "TIME" to "Time")
private val TONES = listOf("NEUTRAL" to "Neutral", "CASUAL" to "Casual", "PROFESSIONAL" to "Professional", "KEYWORDS" to "Keywords")
private val SCOPES = listOf("NEW" to "New since last", "DAY" to "Last 24 h", "WEEK" to "Last 7 days")
private val TODO_MODES = listOf("ALL" to "All tasks", "DEADLINES" to "Deadlines only", "OFF" to "Off")
private val SCHEDULES = listOf("1" to "1 h", "2" to "2 h", "4" to "4 h", "8" to "8 h", "12" to "12 h", "24" to "Daily")

private fun label(options: List<Pair<String, String>>, value: String) =
    options.firstOrNull { it.first == value }?.second ?: options.first().second

@Composable
private fun SettingLabel(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = AlalBlue, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
    )
}

@Composable
private fun ChoiceChips(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, text) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(text) })
        }
    }
}

@Composable
private fun ToggleLine(text: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else Color.Gray
        )
        Switch(
            checked = checked && enabled, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = AlalBlue)
        )
    }
}
