package co.novu.examples.inbox.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import co.novu.examples.inbox.BackendClient
import co.novu.examples.inbox.DEEPLINK_EXTRA
import co.novu.examples.inbox.DeviceStore
import co.novu.examples.inbox.INBOX_LINK
import co.novu.examples.inbox.MainActivity
import co.novu.examples.inboxandpush.R
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val CHANNEL_ID = "novu"

object PushSupport {
    /** True only after a rebuild that included app/google-services.json. */
    fun isConfigured(context: Context): Boolean {
        return context.resources.getIdentifier("google_app_id", "string", context.packageName) != 0
    }

    fun ensureFirebase(context: Context) {
        if (!isConfigured(context) || FirebaseApp.getApps(context).isNotEmpty()) return
        FirebaseApp.initializeApp(context)
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null && existing.importance < NotificationManager.IMPORTANCE_HIGH) {
            manager.deleteNotificationChannel(CHANNEL_ID)
        }
        val channel = NotificationChannel(CHANNEL_ID, "Novu", NotificationManager.IMPORTANCE_HIGH)
        channel.description = "Notifications sent through Novu"
        manager.createNotificationChannel(channel)
    }

    fun currentToken(onResult: (String?, String?) -> Unit) {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { onResult(it, null) }
            .addOnFailureListener { onResult(null, it.message ?: "Could not get an FCM token.") }
    }

    fun show(context: Context, title: String, body: String, deeplink: String?) {
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            val link = deeplink?.takeIf { it.isNotBlank() } ?: INBOX_LINK
            data = Uri.parse(link)
            putExtra(DEEPLINK_EXTRA, link)
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pending)
            .build()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(title.hashCode(), notification)
    }

    /** Ask Firebase to discard this install's token after the server has removed it. */
    fun deleteToken(context: Context) {
        if (!isConfigured(context)) return
        ensureFirebase(context)
        FirebaseMessaging.getInstance().deleteToken()
    }
}

class NovuMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val context = applicationContext
        val previous = DeviceStore.token(context)
        DeviceStore.saveToken(context, token)
        val url = DeviceStore.backendUrl(context)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val backend = BackendClient(url)
            if (!previous.isNullOrBlank() && previous != token) {
                runCatching { backend.unregisterDevice(previous) }
            }
            runCatching { backend.registerDevice(token) }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"] ?: return
        val body = message.notification?.body ?: message.data["body"].orEmpty()
        PushSupport.show(this, title, body, message.data["deeplink"])
    }
}
