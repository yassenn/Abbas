package ai.alal.app.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.alal.app.data.AppDatabase
import ai.alal.app.data.DigestEntity
import ai.alal.app.data.SourceCount
import ai.alal.app.data.TodoEntity
import ai.alal.app.data.GenerationSettings
import ai.alal.app.personal.LocalSummaryGenerator
import ai.alal.app.personal.RoomSummaryStore
import ai.alal.app.personal.SensitiveClipboard
import ai.alal.app.personal.SummaryException
import ai.alal.app.personal.SummaryGenerator
import ai.alal.app.personal.SummaryManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import ai.alal.app.personal.DigestRunResult
import ai.alal.app.personal.PersonalDigestWorker
import ai.alal.app.personal.PersonalSettings
import ai.alal.app.personal.PersonalSummarizer
import ai.alal.app.personal.HealthCollector
import ai.alal.app.personal.LocationTracker
import ai.alal.app.personal.PlaceAnalyzer
import ai.alal.app.personal.Routine
import ai.alal.app.personal.RoutineAnalyzer
import ai.alal.app.personal.WriteCapabilities
import ai.alal.app.personal.ChatImporter
import ai.alal.app.personal.DmApp
import ai.alal.app.personal.DmApps
import ai.alal.app.personal.EmailAccounts
import ai.alal.app.personal.EmailCollector
import ai.alal.app.personal.OutlookAuth
import ai.alal.app.personal.ScreenReaderService
import ai.alal.app.data.EmailAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Toggle state for every data source, refreshed when the screen resumes. */
data class SourceState(
    val sms: Boolean = false,
    val calls: Boolean = false,
    val notifications: Boolean = false,
    val notificationAccess: Boolean = false,
    val files: Boolean = false,
    val folders: List<String> = emptyList(),
    val background: Boolean = false,
    val lastRunAt: Long = 0L,
    val allFiles: Boolean = false,
    val allFilesAccess: Boolean = false,
    val usage: Boolean = false,
    val usageAccess: Boolean = false,
    val calendar: Boolean = false,
    val contacts: Boolean = false,
    val health: Boolean = false,
    val healthAvailable: Boolean = false,
    val healthGranted: Boolean = false,
    val location: Boolean = false,
    val locationPermission: Boolean = false,
    val backgroundLocation: Boolean = false,
    val screen: Boolean = false,
    val screenAccess: Boolean = false,
    val write: WriteCapabilities = WriteCapabilities(),
    val summaryInstructions: String = "",
    val summaryOptions: ai.alal.app.data.SummaryOptions = ai.alal.app.data.SummaryOptions(),
    /** Downloaded models usable for summaries (id to name), best first. */
    val summaryModels: List<Pair<String, String>> = emptyList(),
    val email: Boolean = false,
    val emailAccounts: List<EmailAccount> = emptyList(),
    val dmApps: Set<String> = emptySet(),
    val installedDmApps: List<DmApp> = emptyList()
)

/** Progress of the Outlook device-code sign-in. */
data class OutlookSignIn(val code: String? = null, val url: String? = null, val error: String? = null, val busy: Boolean = false)

class PersonalViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = PersonalSettings(app)
    private val dao = AppDatabase.getInstance(app).personalDao()

    val todos: StateFlow<List<TodoEntity>> =
        dao.todos().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val digests: StateFlow<List<DigestEntity>> =
        dao.digests().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val pendingCount: StateFlow<Int> =
        dao.pendingCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val counts: StateFlow<List<SourceCount>> =
        dao.countsBySource().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _sources = MutableStateFlow(SourceState())
    val sources: StateFlow<SourceState> = _sources.asStateFlow()

    /** Non-null while a "Summarize now" run is in progress. */
    private val _progress = MutableStateFlow<String?>(null)
    val progress: StateFlow<String?> = _progress.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _routine = MutableStateFlow<Routine?>(null)
    val routine: StateFlow<Routine?> = _routine.asStateFlow()

    private fun readSources(healthGranted: Boolean = _sources.value.healthGranted) = SourceState(
        allFiles = settings.allFilesEnabled,
        allFilesAccess = settings.allFilesGranted(),
        usage = settings.usageEnabled,
        usageAccess = settings.usageAccessGranted(),
        calendar = settings.calendarEnabled && settings.hasPermission(android.Manifest.permission.READ_CALENDAR),
        contacts = settings.contactsEnabled && settings.hasPermission(android.Manifest.permission.READ_CONTACTS),
        health = settings.healthEnabled,
        healthAvailable = HealthCollector.isAvailable(getApplication()),
        healthGranted = healthGranted,
        location = settings.locationEnabled && LocationTracker.hasPermission(getApplication()),
        locationPermission = LocationTracker.hasPermission(getApplication()),
        backgroundLocation = android.os.Build.VERSION.SDK_INT < 29 ||
            settings.hasPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        screen = settings.screenEnabled,
        screenAccess = settings.screenReaderEnabled(),
        write = settings.writeCapabilities(),
        summaryInstructions = settings.summaryInstructions,
        summaryOptions = settings.summaryOptions,
        summaryModels = ai.alal.app.personal.SummaryModels.downloaded(getApplication()).map { it.id to it.name },
        email = settings.emailEnabled,
        emailAccounts = EmailAccounts(getApplication()).all(),
        dmApps = settings.dmApps,
        installedDmApps = DmApps.installed(getApplication()),
        sms = settings.smsEnabled,
        calls = settings.callsEnabled,
        notifications = settings.notificationsEnabled,
        notificationAccess = settings.notificationAccessGranted(),
        files = settings.filesEnabled,
        folders = settings.watchedFolders.toList().sorted(),
        background = settings.backgroundEnabled,
        lastRunAt = settings.lastRunAt
    )

    fun refresh() {
        _sources.value = readSources()
        viewModelScope.launch(Dispatchers.IO) {
            val granted = HealthCollector.grantedPermissions(getApplication()).isNotEmpty()
            _sources.value = _sources.value.copy(healthGranted = granted)
            val places = if (settings.locationEnabled) PlaceAnalyzer.routineLines(getApplication()) else emptyList()
            _routine.value = RoutineAnalyzer.analyze(getApplication(), settings, places)
        }
    }

    // ── Email ─────────────────────────────────────────────────────

    private val _outlook = MutableStateFlow(OutlookSignIn())
    val outlook: StateFlow<OutlookSignIn> = _outlook.asStateFlow()

    fun setEmail(on: Boolean) { settings.emailEnabled = on; refresh() }

    /** Gmail (app password) or any other IMAP server. Tests the login before saving. */
    fun addPasswordAccount(label: String, email: String, password: String, host: String, port: Int) {
        if (_progress.value != null) return
        _progress.value = "Checking ${email.trim()}…"
        viewModelScope.launch {
            val account = EmailAccount(
                id = java.util.UUID.randomUUID().toString(),
                label = label.ifBlank { email.substringAfter('@') },
                email = email.trim(),
                host = host.trim(),
                port = port,
                secret = password.replace(" ", "")
            )
            val error = EmailCollector.test(account)
            _progress.value = null
            if (error == null) {
                EmailAccounts(getApplication()).upsert(account)
                settings.emailEnabled = true
                _message.value = "Added ${account.email}"
            } else {
                _message.value = "Couldn't sign in: $error"
            }
            refresh()
        }
    }

    /** Starts Microsoft sign-in; [code] is shown to the user to enter at microsoft.com/devicelogin. */
    fun startOutlook(clientId: String, email: String) {
        val id = clientId.trim()
        if (id.isBlank() || email.isBlank()) { _outlook.value = OutlookSignIn(error = "Enter your email and app (client) ID"); return }
        _outlook.value = OutlookSignIn(busy = true)
        viewModelScope.launch {
            try {
                val code = OutlookAuth.startDeviceCode(id)
                _outlook.value = OutlookSignIn(code = code.userCode, url = code.verificationUri, busy = true)
                val tokens = OutlookAuth.awaitTokens(id, code)
                val account = EmailAccount(
                    id = java.util.UUID.randomUUID().toString(),
                    label = "Outlook",
                    email = email.trim(),
                    host = EmailAccount.OUTLOOK_HOST,
                    auth = EmailAccount.AUTH_OAUTH_MICROSOFT,
                    secret = tokens.refreshToken,
                    clientId = id
                )
                val error = EmailCollector.test(account)
                if (error != null) throw IllegalStateException(error)
                EmailAccounts(getApplication()).upsert(account)
                settings.emailEnabled = true
                _outlook.value = OutlookSignIn()
                _message.value = "Added ${account.email}"
                refresh()
            } catch (e: Exception) {
                _outlook.value = OutlookSignIn(error = e.message ?: "Sign-in failed")
            }
        }
    }

    fun cancelOutlook() { _outlook.value = OutlookSignIn() }

    fun removeEmailAccount(id: String) { EmailAccounts(getApplication()).remove(id); refresh() }

    // ── DM sweep + imports ────────────────────────────────────────

    fun toggleDmApp(pkg: String, on: Boolean) {
        settings.dmApps = if (on) settings.dmApps + pkg else settings.dmApps - pkg
        refresh()
    }

    /** Opens each selected app's inbox via the screen reader, then summarises. */
    fun checkDms() {
        if (_progress.value != null) return
        val service = ScreenReaderService.instance
        if (service == null) {
            _message.value = "Turn on Screen reader in Data access first"
            return
        }
        val apps = DmApps.installed(getApplication()).filter { it.pkg in settings.dmApps }
        if (apps.isEmpty()) {
            _message.value = "Pick at least one app to check"
            return
        }
        _progress.value = "Opening apps…"
        viewModelScope.launch {
            val captured = service.sweep(apps) { _progress.value = it }
            val result = PersonalSummarizer.run(getApplication()) { _progress.value = it }
            _progress.value = null
            _message.value = "Read $captured of ${apps.size} inboxes · " + when (result) {
                is DigestRunResult.Done -> "${result.todosAdded} new to-dos"
                DigestRunResult.NothingNew -> "nothing new"
                DigestRunResult.AlreadyRunning -> "a summary is already running"
                is DigestRunResult.Failed -> result.reason
            }
            refresh()
        }
    }

    fun importFiles(uris: List<android.net.Uri>) {
        if (uris.isEmpty() || _progress.value != null) return
        _progress.value = "Importing…"
        viewModelScope.launch {
            _message.value = try {
                val r = ChatImporter.import(getApplication(), uris)
                "Imported ${r.messages} messages from ${r.conversations} conversation(s)"
            } catch (e: Exception) {
                "Import failed: ${e.message ?: "unknown error"}"
            }
            _progress.value = null
        }
    }

    fun updateSummaryOptions(transform: (ai.alal.app.data.SummaryOptions) -> ai.alal.app.data.SummaryOptions) {
        val before = settings.summaryOptions
        val after = transform(before)
        settings.summaryOptions = after
        if (settings.backgroundEnabled &&
            (before.scheduleHours != after.scheduleHours || before.requireCharging != after.requireCharging)
        ) {
            PersonalDigestWorker.schedule(getApplication(), true)
        }
        refresh()
    }

    fun resetSummaryOptions() = updateSummaryOptions { ai.alal.app.data.SummaryOptions() }

    fun setSummaryInstructions(text: String) {
        settings.summaryInstructions = text
        _message.value = if (text.isBlank()) "Summary instructions cleared" else "Summary instructions saved — used from the next summary"
        refresh()
    }

    fun setAllFiles(on: Boolean) { settings.allFilesEnabled = on; refresh() }
    fun setUsage(on: Boolean) { settings.usageEnabled = on; refresh() }
    fun setCalendar(on: Boolean) { settings.calendarEnabled = on; refresh() }
    fun setContacts(on: Boolean) { settings.contactsEnabled = on; refresh() }
    fun setHealth(on: Boolean) { settings.healthEnabled = on; refresh() }
    fun setScreen(on: Boolean) { settings.screenEnabled = on; refresh() }

    fun setLocation(on: Boolean) {
        settings.locationEnabled = on
        LocationTracker.schedule(getApplication(), on)
        refresh()
    }

    fun setWrite(transform: (PersonalSettings) -> Unit) {
        transform(settings)
        refresh()
    }

    fun setSms(on: Boolean) { settings.smsEnabled = on; refresh() }
    fun setCalls(on: Boolean) { settings.callsEnabled = on; refresh() }
    fun setNotifications(on: Boolean) { settings.notificationsEnabled = on; refresh() }
    fun setFiles(on: Boolean) { settings.filesEnabled = on; refresh() }

    fun setBackground(on: Boolean) {
        settings.backgroundEnabled = on
        PersonalDigestWorker.schedule(getApplication(), on)
        refresh()
    }

    fun addFolder(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) { /* provider doesn't offer persistable grants */ }
        settings.addFolder(uri)
        settings.filesEnabled = true
        refresh()
    }

    fun removeFolder(uri: String) {
        try {
            getApplication<Application>().contentResolver
                .releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) { }
        settings.removeFolder(uri)
        refresh()
    }

    fun summarizeNow() {
        if (_progress.value != null) return
        _progress.value = "Starting…"
        viewModelScope.launch {
            val result = PersonalSummarizer.run(getApplication()) { _progress.value = it }
            _progress.value = null
            _message.value = when (result) {
                is DigestRunResult.Done ->
                    "Summarized ${result.itemsSummarized} items · ${result.todosAdded} new to-dos"
                DigestRunResult.NothingNew -> "Nothing new since the last summary"
                DigestRunResult.AlreadyRunning -> "A summary is already running in the background"
                is DigestRunResult.Failed -> result.reason
            }
            refresh()
        }
    }

    fun consumeMessage() { _message.value = null }

    fun setTodoDone(todo: TodoEntity, done: Boolean) =
        viewModelScope.launch(Dispatchers.IO) { dao.setTodoDone(todo.id, done) }

    fun deleteTodo(todo: TodoEntity) = viewModelScope.launch(Dispatchers.IO) { dao.deleteTodo(todo.id) }
    fun clearDoneTodos() = viewModelScope.launch(Dispatchers.IO) { dao.deleteDoneTodos() }
    // ── Per-summary management (each call affects exactly one summary) ──

    private val store = RoomSummaryStore(AppDatabase.getInstance(app))
    /** Management that never needs the model (rename, pin, delete). */
    private val crud = SummaryManager(store, object : SummaryGenerator {
        override val charBudget = 0
        override suspend fun generate(system: String, prompt: String): String = throw SummaryException("No model")
    })
    private var summaryJob: Job? = null
    private val _summaryBusy = MutableStateFlow(false)
    /** True while a cancellable summarize/regenerate job runs. */
    val summaryBusy: StateFlow<Boolean> = _summaryBusy.asStateFlow()

    /** How many parents each summary was generated from (absent = none). */
    val linkCounts: StateFlow<Map<Long, Int>> = dao.linkCounts()
        .map { rows -> rows.associate { it.childId to it.count } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    suspend fun parentsOf(id: Long): List<DigestEntity> = withContext(Dispatchers.IO) { dao.parentsOf(id) }

    fun deleteDigest(digest: DigestEntity) = viewModelScope.launch(Dispatchers.IO) { crud.delete(digest.id) }
    fun renameDigest(digest: DigestEntity, title: String) = viewModelScope.launch(Dispatchers.IO) { crud.rename(digest.id, title) }
    fun setDigestPinned(digest: DigestEntity, pinned: Boolean) = viewModelScope.launch(Dispatchers.IO) { crud.setPinned(digest.id, pinned) }

    fun copyDigest(digest: DigestEntity) {
        SensitiveClipboard.copy(getApplication(), digest.summary)
        _message.value = "Copied. The clipboard clears in a minute."
    }

    /** Combines 2+ summaries into a NEW linked summary using the on-device model only. */
    fun summarizeSummaries(ids: Collection<Long>) =
        runSummaryTask("Combining ${ids.size} summaries…") { it.summarizeSummaries(ids.toList(), ::setProgress); "Created a combined summary" }

    /** Re-runs one summary from its original source, replacing only its text. */
    fun regenerateDigest(digest: DigestEntity) =
        runSummaryTask("Regenerating…") { it.regenerate(digest.id, ::setProgress); "Summary regenerated" }

    fun cancelSummaryTask() { summaryJob?.cancel() }

    private fun setProgress(text: String) { _progress.value = text }

    private fun runSummaryTask(start: String, work: suspend (SummaryManager) -> String) {
        if (_progress.value != null) return
        _progress.value = start
        _summaryBusy.value = true
        summaryJob = viewModelScope.launch {
            try {
                val msg = withContext(Dispatchers.Default) {
                    PersonalSummarizer.exclusive {
                        PersonalSummarizer.withSummaryEngine(getApplication(), ::setProgress) { engine ->
                            val gen = GenerationSettings(
                                temperature = 0.3f, topP = 0.9f,
                                maxTokens = settings.summaryOptions.maxTokens, enableThinking = false
                            )
                            work(
                                SummaryManager(
                                    store, LocalSummaryGenerator(engine, gen),
                                    itemsSystemPrompt = { PersonalSummarizer.systemPrompt(settings.summaryInstructions, settings.summaryOptions) }
                                )
                            )
                        }
                    } ?: "A summary is already running in the background"
                }
                _message.value = msg
            } catch (e: CancellationException) {
                _message.value = "Cancelled"
                throw e
            } catch (e: SummaryException) {
                _message.value = e.message ?: "Summary failed"
            } catch (e: Exception) {
                _message.value = "Summary failed" // never include content in messages
            } finally {
                _progress.value = null
                _summaryBusy.value = false
                summaryJob = null
            }
        }
    }

    /** Wipes everything collected or generated, and restarts collection from now. */
    fun eraseAll() = viewModelScope.launch(Dispatchers.IO) {
        dao.deleteAllItems()
        dao.deleteAllDigests()
        dao.deleteAllTodos()
        dao.deleteAllPoints()
        settings.lastUsageTimestamp = System.currentTimeMillis()
        val now = System.currentTimeMillis()
        settings.lastSmsTimestamp = now
        settings.lastCallTimestamp = now
        settings.lastFileTimestamp = now
        _message.value = "All collected phone data erased"
    }

    // Last, so every property above is initialised before the first read.
    init { refresh() }
}
