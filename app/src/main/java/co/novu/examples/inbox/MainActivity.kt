package co.novu.examples.inbox

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    private val openInboxFromPush = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openInboxFromPush.value = intent.opensInbox()
        setContent {
            NovuTheme {
                val model: InboxViewModel = viewModel()
                val openFromPush by openInboxFromPush
                var showInbox by remember { mutableStateOf(false) }
                LaunchedEffect(openFromPush) {
                    if (openFromPush) showInbox = true
                }
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner, model) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) model.resume()
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
                if (showInbox) {
                    InboxScreen(model, onBack = { showInbox = false })
                } else {
                    HomeScreen(model, onOpenInbox = { showInbox = true })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.opensInbox()) openInboxFromPush.value = true
    }
}

/** True when a push tap or novu-example://notifications should open the inbox. */
fun Intent.opensInbox(): Boolean {
    if (isInboxLink(dataString)) return true
    return isInboxLink(getStringExtra(DEEPLINK_EXTRA))
}
