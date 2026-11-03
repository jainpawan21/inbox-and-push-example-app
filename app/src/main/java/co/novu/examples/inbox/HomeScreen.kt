package co.novu.examples.inbox

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import co.novu.examples.inbox.push.PushSupport
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(model: InboxViewModel, onOpenInbox: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        fetchAndRegister(context, model, granted)
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Novu", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Inbox uses the public Novu API. Push tokens go to your Python server, which stores them with the secret key.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = model.backendUrl,
                onValueChange = { model.backendUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Backend URL") },
                supportingText = {
                    Text("The emulator reaches this computer at http://10.0.2.2:3004. A phone uses your computer's Wi-Fi address.")
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
            )
            if (model.subscriberId.isNotBlank()) {
                Text("Subscriber ${model.subscriberId}", style = MaterialTheme.typography.bodyMedium)
            }
            Button(
                onClick = {
                    scope.launch {
                        if (model.openInbox()) onOpenInbox()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("View inbox")
            }
            Text("Live updates: ${model.liveUpdates}")
            Text("Unread: ${model.unreadCount}")
            Text(
                model.status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("FCM device", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Text(
                "The app sends the FCM token to your backend. The backend stores it on the Novu subscriber from SUBSCRIBER_ID.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        fetchAndRegister(context, model, notificationsAllowed = true)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Register FCM token")
            }
            model.deviceToken?.let { token ->
                Text(
                    token.shortToken(),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedTextField(
                value = model.manualToken,
                onValueChange = { model.manualToken = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Or paste a device token") },
                singleLine = true,
            )
            OutlinedButton(
                onClick = { model.registerManualToken() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Register pasted token")
            }
            OutlinedButton(
                onClick = { model.unregisterCurrentDevice() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Remove token on logout")
            }
            OutlinedButton(
                onClick = { model.sendTest() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Trigger test workflow")
            }
        }
    }
}

private fun appAllowsNotifications(context: android.content.Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
}

private fun fetchAndRegister(
    context: android.content.Context,
    model: InboxViewModel,
    notificationsAllowed: Boolean,
) {
    if (!PushSupport.isConfigured(context)) {
        model.status = "Add app/google-services.json from Firebase, then rebuild, to get a real FCM token."
        return
    }
    PushSupport.ensureFirebase(context)
    val allowed = notificationsAllowed && appAllowsNotifications(context)
    PushSupport.currentToken { token, error ->
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            when {
                error != null -> model.status = error
                token != null -> model.registerFetchedToken(token, allowed)
            }
        }
    }
}

private val InboxViewModel.liveUpdates: String
    get() = when {
        !sessionStarted -> "Off"
        connected -> "Connected"
        else -> "Reconnecting"
    }

private fun String.shortToken(): String {
    if (length <= 18) return this
    return "${take(8)}…${takeLast(8)}"
}
