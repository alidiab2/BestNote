package app.bestnote.feature.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Target of `<applicationId>://ai-account/done`, the link on the page the browser shows after
 * "Sign in with OpenRouter". It carries no data (the code already reached the app on the loopback
 * callback); it only brings the app's own screen back on top, which closes the Custom Tab.
 */
class AiAccountReturnActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            startActivity(it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        finish()
    }
}
