package com.neoplus.assistant.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.neoplus.assistant.ui.MainActivity

/**
 * Optional "Hey NEO" listener. It loops Android's SpeechRecognizer and looks for the phrase in the transcript.
 * This is NOT a dedicated low-power wake-word engine: it uses more battery, the phone's recognizer may beep or
 * pause between sessions, and Android may stop it. The foreground notification stays visible while it runs.
 */
class WakeWordService : Service() {
    companion object {
        @Volatile var instance: WakeWordService? = null
        @Volatile var uiVisible = false
        const val EXTRA_WAKE = "neo_wake"
        private const val CH_ONGOING = "neo_wake_ongoing"
        private const val CH_ALERT = "neo_wake_alert"
        private val WAKE = Regex("\\bhey[, ]+(neo|neon|nio)\\b")

        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, WakeWordService::class.java))
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, WakeWordService::class.java)) }
    }

    private val main = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    @Volatile private var paused = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ONGOING, "Wake word listening", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "NEO heard you", NotificationManager.IMPORTANCE_HIGH))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n: Notification = Notification.Builder(this, CH_ONGOING)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("NEO PLUS is listening for \"Hey NEO\"")
            .setContentText("Turn off in Settings > Wake word")
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)
        if (!paused && !uiVisible) main.post { begin() }
        return START_STICKY
    }

    private fun begin() {
        if (paused) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) = scan(partialResults)
            override fun onResults(results: Bundle?) { scan(results); restart(400) }
            override fun onError(error: Int) { restart(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 3000 else 600) }
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        )
    }

    private fun restart(delayMs: Long) { if (!paused) main.postDelayed({ begin() }, delayMs) }

    private fun scan(b: Bundle?) {
        if (paused) return
        val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.joinToString(" ")?.lowercase() ?: return
        if (WAKE.containsMatchIn(t)) wake()
    }

    private fun wake() {
        pause()
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_WAKE, true)
        val pi = PendingIntent.getActivity(this, 7, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, CH_ALERT).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("NEO PLUS").setContentText("I heard you. Tap to talk.")
            .setFullScreenIntent(pi, true).setContentIntent(pi).setAutoCancel(true).build()
        getSystemService(NotificationManager::class.java).notify(2, n)
        // Android 10+ often blocks activity launches from the background; the notification above is the reliable path.
        try { startActivity(open) } catch (_: Exception) {}
        main.postDelayed({ if (!uiVisible) resume() }, 20_000)
    }

    fun pause() { paused = true; main.post { destroy() } }
    fun resume() { paused = false; main.post { begin() } }

    private fun destroy() { rec?.destroy(); rec = null }

    override fun onDestroy() { instance = null; paused = true; destroy(); super.onDestroy() }
}
