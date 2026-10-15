package app.bestnote.core.designsystem.showcase

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/** Debug-only host for [ThemeShowcaseScreen]. Extras: `dark` and `rtl` (booleans). */
class ThemeShowcaseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val dark = intent.getBooleanExtra("dark", false)
        val rtl = intent.getBooleanExtra("rtl", false)
        setContent { ThemeShowcaseScreen(initialDark = dark, initialRtl = rtl) }
    }
}
