package co.novu.examples.inbox

import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

class InboxException(message: String) : Exception(message)

/**
 * Inbox client for Novu Cloud.
 *
 * REST is the source of truth. The WebSocket only says that something changed.
 * Novu does not replay events missed while the socket was down, so every
 * successful reconnect asks the screen to refetch.
 *
 * Session and socket state is updated on the main thread. One coroutine owns
 * the socket: it connects, reads, and backs off. Cancelling that coroutine is
 * how a socket is replaced.
 */
class InboxClient(private val scope: CoroutineScope) {
    var onRealtimeHint: (() -> Unit)? = null
    var onNotificationReceived: ((InboxNotification) -> Unit)? = null
    var onUnreadCount: ((Int) -> Unit)? = null
    var onConnectionChanged: ((Boolean) -> Unit)? = null
    var onConnectionProblem: ((String) -> Unit)? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    // A call timeout would cancel the socket after 30 seconds. A read timeout
    // would cancel it whenever Novu is quiet. This client stays open and we
    // send the text "ping" ourselves.
    private val sockets = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val sessionLock = Mutex()

    private var bootstrap: InboxBootstrap? = null
    private var token: String? = null
    private var tokenExpiresAt: Instant? = null
    private var connectionJob: Job? = null
    private var socket: WebSocket? = null
    private var refreshJob: Job? = null
    private var hintJob: Job? = null
    private var stopped = true
    private var hasOpenedSocket = false
    private var suppressNextHint = false

    suspend fun start(next: InboxBootstrap) {
        stop()
        stopped = false
        bootstrap = next
        try {
            ensureSession(force = true)
            startConnection()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            stop()
            throw error
        }
    }

    /** A suspended phone can leave a socket looking open. Refetch, then reconnect. */
    fun resume() {
        scope.launch {
            if (stopped) return@launch
            suppressNextHint = true
            onRealtimeHint?.invoke()
            socket?.close(1000, "resume")
        }
    }

    fun stop() {
        stopped = true
        connectionJob?.cancel()
        connectionJob = null
        socket?.close(1000, "stop")
        socket = null
        refreshJob?.cancel()
        refreshJob = null
        hintJob?.cancel()
        hintJob = null
        token = null
        tokenExpiresAt = null
        bootstrap = null
        hasOpenedSocket = false
        onConnectionChanged?.invoke(false)
    }

    suspend fun fetchNotifications(archived: Boolean): List<InboxNotification> {
        val notifications = mutableListOf<InboxNotification>()
        var after: String? = null
        while (true) {
            val page = fetchNotificationPage(archived, after)
            notifications += page.data
            val last = page.data.lastOrNull()?.id
            if (!page.hasMore || last == null || last == after) return notifications
            after = last
        }
    }

    suspend fun fetchUnreadCount(): Int {
        val url = inboxUrl("/inbox/notifications/count")
            .newBuilder()
            .addQueryParameter("filters", """[{"read":false,"archived":false,"snoozed":false}]""")
            .build()
        val body = send(url.toString(), "GET")
        return json.decodeFromString<UnreadCountPage>(body).data.firstOrNull()?.count ?: 0
    }

    suspend fun setRead(id: String, read: Boolean) {
        send(actionUrl(id, if (read) "read" else "unread"), "PATCH")
    }

    suspend fun setArchived(id: String, archived: Boolean) {
        send(actionUrl(id, if (archived) "archive" else "unarchive"), "PATCH")
    }

    suspend fun delete(id: String) {
        send(actionUrl(id, "delete"), "DELETE")
    }

    private suspend fun fetchNotificationPage(archived: Boolean, after: String?): NotificationsPage {
        val builder = inboxUrl("/inbox/notifications").newBuilder()
            .addQueryParameter("limit", "100")
            .addQueryParameter("archived", if (archived) "true" else "false")
        if (after != null) builder.addQueryParameter("after", after)
        val body = send(builder.build().toString(), "GET")
        return json.decodeFromString(body)
    }

    /**
     * The subscriber JWT lasts days. Refresh it five minutes early, and again
     * immediately when Novu returns 401. Callers share one in-flight refresh
     * because the mutex lets only one session request run.
     */
    private suspend fun ensureSession(force: Boolean) {
        withContext(Dispatchers.Main.immediate) {
            sessionLock.withLock {
                if (stopped) return@withLock
                if (!force && isTokenFresh) return@withLock
                openSession()
            }
        }
    }

    private val isTokenFresh: Boolean
        get() = token != null && (tokenExpiresAt ?: Instant.EPOCH).isAfter(Instant.now().plusSeconds(5 * 60))

    private suspend fun openSession() {
        val current = bootstrap ?: throw InboxException("Inbox session is not open.")
        val payload = json.encodeToString(
            SessionRequest(
                applicationIdentifier = current.applicationIdentifier,
                subscriberHash = current.subscriberHash,
                subscriber = SubscriberRef(current.subscriberId),
            ),
        )
        val url = joinUrl(current.inboxApiUrl, "/inbox/session")
        val (status, body) = withContext(Dispatchers.IO) {
            execute(url, "POST", payload, bearer = null)
        }
        if (stopped) return
        if (status !in 200..299) {
            throw InboxException("Inbox session failed ($status): $body")
        }
        val envelope = json.decodeFromString<DataEnvelope<SessionToken>>(body)
        if (envelope.data.token.isBlank()) {
            throw InboxException("Inbox session did not return a token.")
        }
        token = envelope.data.token
        tokenExpiresAt = jwtExpiry(envelope.data.token) ?: Instant.now().plusSeconds(10 * 60)
        scheduleTokenRefresh()
    }

    private fun scheduleTokenRefresh() {
        refreshJob?.cancel()
        val delayMs = ((tokenExpiresAt ?: Instant.now()).minusSeconds(5 * 60).toEpochMilli() - System.currentTimeMillis())
            .coerceAtLeast(1_000)
        refreshJob = scope.launch {
            delay(delayMs)
            refreshSession()
        }
    }

    private suspend fun refreshSession() {
        if (stopped) return
        try {
            ensureSession(force = true)
            if (stopped) return
            suppressNextHint = true
            socket?.close(1000, "refresh")
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (stopped) return
            refreshJob = scope.launch {
                delay(30_000)
                refreshSession()
            }
        }
    }

    private suspend fun send(url: String, method: String, allowRefresh: Boolean = true): String {
        ensureSession(force = false)
        val bearer = withContext(Dispatchers.Main.immediate) { token }
            ?: throw InboxException("Inbox session did not return a token.")
        val (status, body) = withContext(Dispatchers.IO) {
            execute(url, method, body = null, bearer = bearer)
        }
        if (status == 401 && allowRefresh) {
            withContext(Dispatchers.Main.immediate) {
                token = null
                tokenExpiresAt = null
                suppressNextHint = true
                socket?.close(1000, "unauthorized")
            }
            ensureSession(force = true)
            return send(url, method, allowRefresh = false)
        }
        if (status !in 200..299) {
            throw InboxException("Inbox request failed ($status): $body")
        }
        return body
    }

    private fun startConnection() {
        connectionJob?.cancel()
        socket?.close(1000, "replace")
        socket = null
        connectionJob = scope.launch { runConnection() }
    }

    private suspend fun runConnection() {
        var attempt = 0
        while (scope.isActive && !stopped) {
            var failed = false
            try {
                ensureSession(force = false)
                openAndHold()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failed = true
                onConnectionProblem?.invoke(error.message ?: "Novu socket disconnected")
            }
            if (stopped || !scope.isActive) return
            onConnectionChanged?.invoke(false)
            delay(backoff(if (failed) attempt else 0))
            if (failed) attempt += 1 else attempt = 0
        }
    }

    private suspend fun openAndHold() {
        val closed = CompletableDeferred<Throwable?>()
        val opened = CompletableDeferred<Unit>()
        val created = sockets.newWebSocket(
            Request.Builder().url(socketRequestUrl()).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.complete(Unit)
                    scope.launch {
                        if (socket !== webSocket || stopped) {
                            webSocket.close(1000, "replaced")
                            return@launch
                        }
                        webSocket.send("ping")
                        onConnectionChanged?.invoke(true)
                        if (hasOpenedSocket && !suppressNextHint) onRealtimeHint?.invoke()
                        suppressNextHint = false
                        hasOpenedSocket = true
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    scope.launch {
                        if (socket === webSocket) handle(text)
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    onMessage(webSocket, bytes.utf8())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    closed.complete(null)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    closed.complete(t)
                }
            },
        )
        socket = created
        try {
            coroutineScope {
                val handshake = launch {
                    delay(15_000)
                    if (!opened.isCompleted) {
                        created.cancel()
                        closed.complete(IOException("Timed out opening the Novu socket"))
                    }
                }
                val heartbeat = launch {
                    opened.await()
                    handshake.cancel()
                    while (isActive && !stopped && socket === created) {
                        delay(25_000)
                        if (socket !== created || stopped) return@launch
                        created.send("ping")
                    }
                }
                val error = closed.await()
                handshake.cancel()
                heartbeat.cancel()
                if (error != null && socket === created) throw error
            }
        } finally {
            if (socket === created) socket = null
            created.cancel()
        }
    }

    private fun handle(text: String) {
        if (text == "ping" || text == "pong") return
        val message = runCatching { JSONObject(text) }.getOrNull() ?: return
        val event = message.optString("event")
        val data = message.optJSONObject("data")
        when (event) {
            "notification_received" -> {
                val notification = parseIncoming(data)
                if (notification != null) onNotificationReceived?.invoke(notification)
            }
            "unread_count_changed" -> {
                val count = parseUnread(data)
                if (count != null) onUnreadCount?.invoke(count)
            }
            "unseen_count_changed" -> Unit
            else -> return
        }
        hintJob?.cancel()
        hintJob = scope.launch {
            delay(250)
            onRealtimeHint?.invoke()
        }
    }

    private fun parseIncoming(data: JSONObject?): InboxNotification? {
        val message = data?.optJSONObject("message") ?: return null
        val id = message.optString("_id").ifBlank { message.optString("id") }
        if (id.isBlank()) return null
        val createdAt = when (val raw = message.opt("createdAt")) {
            is String -> raw
            is Number -> Instant.ofEpochMilli(raw.toLong()).toString()
            else -> ""
        }
        val workflowName = message.optJSONObject("template")?.optString("name").orEmpty()
            .ifBlank { message.optJSONObject("workflow")?.optString("name").orEmpty() }
        return InboxNotification(
            id = id,
            subject = message.optString("subject").takeIf { it.isNotBlank() },
            body = message.optString("content").ifBlank { message.optString("body") },
            isRead = message.optBoolean("read", message.optBoolean("isRead", false)),
            isArchived = message.optBoolean("archived", message.optBoolean("isArchived", false)),
            createdAt = createdAt,
            avatar = message.optString("avatar").takeIf { it.isNotBlank() },
            severity = message.optString("severity").ifBlank { "none" },
            workflow = WorkflowSnippet(workflowName),
        )
    }

    private fun parseUnread(data: JSONObject?): Int? {
        if (data == null) return null
        if (data.has("unreadCount")) return data.optInt("unreadCount")
        val counts = data.optJSONObject("counts") ?: return null
        return if (counts.has("total")) counts.optInt("total") else null
    }

    private fun socketRequestUrl(): String {
        val current = bootstrap ?: throw InboxException("Inbox session is not open.")
        val currentToken = token ?: throw InboxException("Inbox session did not return a token.")
        val raw = current.socketUrl.trim()
        val secure = when {
            raw.startsWith("wss:", ignoreCase = true) -> true
            raw.startsWith("ws:", ignoreCase = true) -> false
            else -> throw InboxException("Socket URL must start with wss://: $raw")
        }
        // HttpUrl cannot store a wss scheme. Build the query on the https form, then
        // put wss back. Request.Builder accepts that wss URL and opens the socket.
        val httpForm = if (secure) "https:${raw.substring(4)}" else "http:${raw.substring(3)}"
        val withToken = httpForm.toHttpUrlOrThrow()
            .newBuilder()
            .removeAllQueryParameters("token")
            .addQueryParameter("token", currentToken)
            .build()
            .toString()
        return if (secure) "wss:${withToken.substring(6)}" else "ws:${withToken.substring(5)}"
    }

    private fun actionUrl(id: String, action: String): String {
        return inboxUrl("/inbox/notifications/$id/$action").toString()
    }

    private fun inboxUrl(path: String): HttpUrl {
        val base = bootstrap?.inboxApiUrl ?: throw InboxException("Inbox session is not open.")
        return joinUrl(base, path).toHttpUrlOrThrow()
    }

    private fun execute(url: String, method: String, body: String?, bearer: String?): Pair<Int, String> {
        val builder = Request.Builder().url(url)
        if (bearer != null) builder.header("Authorization", "Bearer $bearer")
        val requestBody = when (method) {
            "GET", "DELETE" -> null
            else -> (body ?: "{}").toRequestBody(JSON_MEDIA)
        }
        if (requestBody != null) builder.header("Content-Type", "application/json")
        builder.method(method, requestBody)
        try {
            http.newCall(builder.build()).execute().use { response ->
                return response.code to response.body?.string().orEmpty()
            }
        } catch (error: IOException) {
            throw InboxException("Inbox request failed: ${error.message}")
        }
    }

    private fun backoff(attempt: Int): Long {
        val seconds = minOf(1 shl minOf(attempt, 5), 30)
        return ((seconds + Random.nextDouble(0.0, 0.5)) * 1_000).toLong()
    }

    private fun jwtExpiry(raw: String): Instant? {
        val parts = raw.split('.')
        if (parts.size != 3) return null
        val padded = parts[1]
            .replace('-', '+')
            .replace('_', '/')
            .let { value -> value + "=".repeat((4 - value.length % 4) % 4) }
        return runCatching {
            val decoded = String(Base64.decode(padded, Base64.DEFAULT))
            val exp = JSONObject(decoded).optLong("exp", 0L)
            if (exp == 0L) null else Instant.ofEpochSecond(exp)
        }.getOrNull()
    }

    @kotlinx.serialization.Serializable
    private data class SessionRequest(
        val applicationIdentifier: String,
        val subscriberHash: String,
        val subscriber: SubscriberRef,
    )

    @kotlinx.serialization.Serializable
    private data class SubscriberRef(val subscriberId: String)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val JSON_MEDIA = "application/json".toMediaType()

        fun joinUrl(base: String, path: String): String {
            val prefix = base.trimEnd('/')
            val suffix = if (path.startsWith("/")) path else "/$path"
            return prefix + suffix
        }
    }
}

private fun String.toHttpUrlOrThrow(): HttpUrl =
    toHttpUrlOrNull() ?: throw InboxException("Inbox API URL is not valid: $this")
