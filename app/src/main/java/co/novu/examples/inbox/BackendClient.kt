package co.novu.examples.inbox

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class BackendException(message: String) : Exception(message)

/**
 * Calls the Python server. This client never sees the Novu secret key.
 * The server computes subscriberHash and stores the FCM token.
 */
class BackendClient(
    baseUrl: String,
    private val http: OkHttpClient = defaultHttp,
) {
    private val baseUrl = baseUrl.trim().trimEnd('/')

    fun bootstrap(): InboxBootstrap {
        val body = send("POST", "inbox/bootstrap", "{}")
        return json.decodeFromString(body)
    }

    fun registerDevice(deviceToken: String) {
        send("POST", "devices", json.encodeToString(DeviceRequest(deviceToken)))
    }

    fun unregisterDevice(deviceToken: String) {
        send("DELETE", "devices", json.encodeToString(DeviceRequest(deviceToken)))
    }

    fun triggerTest() {
        send(
            "POST",
            "workflows/trigger",
            json.encodeToString(
                TriggerRequest(
                    title = "Hello from the example",
                    body = "This workflow should deliver Inbox and FCM.",
                    deeplink = "novu-example://notifications",
                ),
            ),
        )
    }

    private fun send(method: String, path: String, body: String): String {
        val relative = path.trim().trimStart('/')
        if (baseUrl.isBlank() || relative.isBlank()) {
            throw BackendException("Backend URL is not valid.")
        }
        val request = Request.Builder()
            .url("$baseUrl/$relative")
            .method(method, body.toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .build()
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw BackendException("Backend request failed (${response.code}): $text")
                }
                return text
            }
        } catch (error: BackendException) {
            throw error
        } catch (error: IOException) {
            throw BackendException("Could not reach the backend at $baseUrl. ${error.message}")
        }
    }

    @Serializable
    private data class DeviceRequest(val deviceToken: String)

    @Serializable
    private data class TriggerRequest(val title: String, val body: String, val deeplink: String)

    private companion object {
        val JSON = "application/json".toMediaType()
        val json = Json { ignoreUnknownKeys = true }
        val defaultHttp: OkHttpClient = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
