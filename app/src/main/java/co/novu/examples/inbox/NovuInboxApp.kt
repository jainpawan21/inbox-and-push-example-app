package co.novu.examples.inbox

import android.app.Application
import co.novu.examples.inbox.push.PushSupport

class NovuInboxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PushSupport.ensureChannel(this)
        PushSupport.ensureFirebase(this)
    }
}
