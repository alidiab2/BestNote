/*
 * Debug-only (REC-10): runs RecognitionEval inside `am instrument`, which keeps the process alive and unfrozen for
 * as long as the corpus takes, without any UI (a busy emulator ANRs the activity and freezes background work):
 *
 *   adb shell am instrument -w -e files para_tight.json,arabic.json -e opts order=0 \
 *       <pkg>/app.bestnote.debug.recognition.RecognitionEvalInstrumentation
 *
 * Same arguments as the RECOGNIZE broadcast (files, opts, detail, download); the report is printed and saved to
 * files/replay/recognize.txt.
 */
package app.bestnote.debug.recognition

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import kotlinx.coroutines.runBlocking

class RecognitionEvalInstrumentation : Instrumentation() {
    private var args: Bundle = Bundle()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        args = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        super.onStart()
        val report = runBlocking { RecognitionEval.evaluate(targetContext.applicationContext) { args.getString(it) } }
        finish(Activity.RESULT_OK, Bundle().apply { putString(REPORT_KEY_STREAMRESULT, report) })
    }
}
