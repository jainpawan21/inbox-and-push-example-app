package co.novu.examples.inbox

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import co.novu.examples.inbox.push.PushSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InboxViewModel(application: Application) : AndroidViewModel(application) {
    var backendUrl by mutableStateOf(DeviceStore.backendUrl(application))
    var manualToken by mutableStateOf("")
    var notifications by mutableStateOf<List<InboxNotification>>(emptyList())
    var unreadCount by mutableStateOf(0)
    var showingArchived by mutableStateOf(false)
    var connected by mutableStateOf(false)
    var sessionStarted by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var status by mutableStateOf("Tap View inbox. The subscriber id comes from the server.")
    var inboxError by mutableStateOf<String?>(null)
    var subscriberId by mutableStateOf("")
    var deviceToken by mutableStateOf(DeviceStore.token(application))
    var busyId by mutableStateOf<String?>(null)

    private val client = InboxClient(viewModelScope)
    private var refreshGeneration = 0
    private val pendingNotifications = linkedMapOf<String, InboxNotification>()

    init {
        client.onConnectionChanged = { isConnected ->
            connected = isConnected
            if (isConnected) status = "Live updates connected."
        }
        client.onConnectionProblem = { problem ->
            connected = false
            status = problem
        }
        client.onNotificationReceived = { notification -> receive(notification) }
        client.onUnreadCount = { count -> unreadCount = count }
        client.onRealtimeHint = { refreshQuiet() }
    }

    override fun onCleared() {
        client.stop()
    }

    suspend fun openInbox(): Boolean {
        if (!sessionStarted) connect() else refresh()
        return sessionStarted
    }

    fun resume() {
        if (sessionStarted) client.resume()
    }

    fun showArchived(archived: Boolean) {
        if (showingArchived == archived) return
        showingArchived = archived
        viewModelScope.launch { refreshNow(background = false) }
    }

    fun refresh() {
        viewModelScope.launch { refreshNow(background = false) }
    }

    /** Socket hints and the open inbox use this so the list updates without a full-screen spinner. */
    fun refreshQuiet() {
        if (!sessionStarted) return
        viewModelScope.launch { refreshNow(background = true) }
    }

    fun markRead(notification: InboxNotification, read: Boolean) {
        if (notification.isRead == read) return
        mutate(notification.id) { client.setRead(notification.id, read) }
    }

    fun toggleArchive(notification: InboxNotification) {
        mutate(notification.id) { client.setArchived(notification.id, !notification.isArchived) }
    }

    fun delete(notification: InboxNotification) {
        mutate(notification.id) { client.delete(notification.id) }
    }

    fun registerManualToken() {
        val token = manualToken.trim()
        if (token.isEmpty()) return
        viewModelScope.launch { registerToken(token) }
    }

    fun unregisterCurrentDevice() {
        val token = deviceToken ?: return
        viewModelScope.launch {
            val removed = perform("Removed this device token and closed the Inbox session.") { backend ->
                backend.unregisterDevice(token)
            }
            if (!removed) return@launch
            PushSupport.deleteToken(getApplication())
            DeviceStore.saveToken(getApplication(), null)
            deviceToken = null
            client.stop()
            sessionStarted = false
            connected = false
        }
    }

    fun sendTest() {
        viewModelScope.launch {
            val triggered = perform("Workflow triggered.") { it.triggerTest() }
            if (triggered && sessionStarted) refreshNow(background = true)
        }
    }

    private suspend fun connect() {
        DeviceStore.saveBackend(getApplication(), backendUrl.trim())
        loading = true
        status = "Opening Inbox session..."
        try {
            val bootstrap = withContext(Dispatchers.IO) { backend().bootstrap() }
            subscriberId = bootstrap.subscriberId
            sessionStarted = true
            client.start(bootstrap)
            if (refreshNow(background = false) == RefreshResult.Updated) {
                status = "Inbox is ready for ${bootstrap.subscriberId}."
            }
        } catch (error: Exception) {
            loading = false
            sessionStarted = false
            status = error.message ?: "Could not open the Inbox."
        }
    }

    private suspend fun refreshNow(background: Boolean): RefreshResult {
        refreshGeneration += 1
        val generation = refreshGeneration
        val archived = showingArchived
        if (!background) loading = true
        return try {
            val items = client.fetchNotifications(archived)
            val unread = client.fetchUnreadCount()
            if (generation != refreshGeneration) return RefreshResult.Superseded
            val fetchedIds = items.map { it.id }.toSet()
            pendingNotifications.keys.retainAll { it !in fetchedIds }
            val pending = pendingNotifications.values.filter { !it.isArchived }.reversed()
            notifications = if (archived) items else pending + items.filter { it.id !in pendingNotifications }
            unreadCount = unread
            loading = false
            inboxError = null
            RefreshResult.Updated
        } catch (error: Exception) {
            if (generation != refreshGeneration) return RefreshResult.Superseded
            loading = false
            inboxError = error.message ?: "Could not load notifications."
            RefreshResult.Failed
        }
    }

    private fun receive(notification: InboxNotification) {
        if (notification.isArchived || pendingNotifications.containsKey(notification.id)) return
        if (!showingArchived && notifications.any { it.id == notification.id }) return
        pendingNotifications[notification.id] = notification
        if (!showingArchived) {
            notifications = listOf(notification) + notifications
        }
    }

    private fun mutate(id: String, work: suspend () -> Unit) {
        viewModelScope.launch {
            busyId = id
            try {
                work()
                refreshNow(background = false)
            } catch (error: Exception) {
                inboxError = error.message ?: "Could not update the notification."
            }
            busyId = null
        }
    }

    fun registerFetchedToken(token: String, notificationsAllowed: Boolean) {
        viewModelScope.launch {
            val stored = registerToken(token)
            if (stored && !notificationsAllowed) {
                status = "Token stored. Allow notifications for this app before a banner can appear."
            }
        }
    }

    private suspend fun registerToken(token: String): Boolean {
        val previous = deviceToken
        val stored = perform("FCM token stored on the subscriber for this integration.") { backend ->
            if (!previous.isNullOrBlank() && previous != token) backend.unregisterDevice(previous)
            backend.registerDevice(token)
        }
        if (!stored) return false
        deviceToken = token
        DeviceStore.saveToken(getApplication(), token)
        manualToken = ""
        return true
    }

    private suspend fun perform(success: String, work: suspend (BackendClient) -> Unit): Boolean {
        DeviceStore.saveBackend(getApplication(), backendUrl.trim())
        return try {
            withContext(Dispatchers.IO) { work(backend()) }
            status = success
            true
        } catch (error: Exception) {
            status = error.message ?: "Backend request failed."
            false
        }
    }

    private fun backend(): BackendClient {
        val url = backendUrl.trim()
        if (url.isBlank()) throw BackendException("Backend URL is not valid.")
        return BackendClient(url)
    }
}

private enum class RefreshResult { Updated, Failed, Superseded }

object DeviceStore {
    private const val PREFS = "novu-inbox"
    private const val BACKEND = "backend"
    private const val TOKEN = "deviceToken"

    fun backendUrl(context: Context): String {
        return prefs(context).getString(BACKEND, "http://10.0.2.2:3004") ?: "http://10.0.2.2:3004"
    }

    fun saveBackend(context: Context, url: String) {
        prefs(context).edit().putString(BACKEND, url.trim()).apply()
    }

    fun token(context: Context): String? = prefs(context).getString(TOKEN, null)

    fun saveToken(context: Context, token: String?) {
        prefs(context).edit().putString(TOKEN, token).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
