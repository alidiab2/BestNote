package app.bestnote.feature.editor.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.bestnote.feature.editor.R
import java.io.File

/**
 * Records the microphone to AAC in an m4a file as a foreground service (MEDIA-04), so the recording
 * survives leaving the app and the screen turning off. The ongoing notification has a Stop action.
 * The finished file is announced on [RecorderHub]; an editor that isn't open picks it up next time it
 * opens the notebook (it adopts any complete `.m4a` in the notebook's audio folder).
 */
class AudioRecorderService : Service() {
    private var recorder: MediaRecorder? = null
    private var active: RecorderHub.Active? = null
    private var part: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent.getStringExtra(EXTRA_NOTEBOOK).orEmpty(), intent.getStringExtra(EXTRA_RECORDING).orEmpty(), intent.getStringExtra(EXTRA_TITLE))
            ACTION_STOP -> finish()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(notebookId: String, recordingId: String, title: String?) {
        if (recorder != null) return
        val startedAt = System.currentTimeMillis()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(title, startedAt), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        val file = AudioFiles.partOf(this, notebookId, recordingId)
        try {
            file.parentFile?.mkdirs()
            val r = MediaRecorder(this)
            recorder = r
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(SAMPLE_RATE)
            r.setAudioEncodingBitRate(BIT_RATE)
            r.setOutputFile(file.path)
            r.prepare()
            r.start()
            part = file
            active = RecorderHub.Active(notebookId, recordingId, SystemClock.uptimeMillis(), startedAt).also(RecorderHub::started)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.e(TAG, "Cannot start recording", e)
            release()
            file.delete()
            RecorderHub.failed(e.message ?: "Cannot record")
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** Stops the recorder and publishes the finished file; also called when the system destroys the service. */
    private fun finish() {
        val r = recorder
        val a = active
        val file = part
        recorder = null
        active = null
        part = null
        var result: RecorderHub.Finished? = null
        if (r != null && a != null && file != null) {
            try {
                r.stop()
                val done = AudioFiles.fileOf(this, a.notebookId, a.recordingId)
                if (file.renameTo(done)) result = RecorderHub.Finished(a.notebookId, a.recordingId, a.startedAtMillis)
            } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
                Log.w(TAG, "Recording had no audio", e) // stop() throws when stopped immediately
                file.delete()
            } finally {
                r.release()
            }
        } else {
            r?.release()
        }
        RecorderHub.stopped(result)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun release() {
        recorder?.release()
        recorder = null
        part = null
        active = null
    }

    override fun onDestroy() {
        if (recorder != null) finish()
        super.onDestroy()
    }

    private fun notification(title: String?, startedAt: Long): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.audio_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val stop = PendingIntent.getService(
            this, 1, stopIntent(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_recording)
            .setContentTitle(title ?: getString(R.string.audio_notification_title))
            .setContentText(getString(R.string.audio_notification_text))
            .setUsesChronometer(true)
            .setWhen(startedAt)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(0, getString(R.string.audio_stop), stop)
            .build()
    }

    companion object {
        private const val TAG = "BestNoteAudio"
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 4401
        private const val ACTION_START = "app.bestnote.audio.START"
        private const val ACTION_STOP = "app.bestnote.audio.STOP"
        private const val EXTRA_NOTEBOOK = "notebook"
        private const val EXTRA_RECORDING = "recording"
        private const val EXTRA_TITLE = "title"
        private const val SAMPLE_RATE = 44_100
        private const val BIT_RATE = 64_000

        internal fun start(context: Context, notebookId: String, recordingId: String, title: String?) {
            val intent = Intent(context, AudioRecorderService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_NOTEBOOK, notebookId)
                .putExtra(EXTRA_RECORDING, recordingId)
                .putExtra(EXTRA_TITLE, title)
            context.startForegroundService(intent)
        }

        internal fun stop(context: Context) {
            context.startService(stopIntent(context))
        }

        private fun stopIntent(context: Context) = Intent(context, AudioRecorderService::class.java).setAction(ACTION_STOP)
    }
}
