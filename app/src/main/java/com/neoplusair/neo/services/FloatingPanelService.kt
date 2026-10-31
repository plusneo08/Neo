
package com.neoplusair.neo.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.neoplusair.neo.R
import com.neoplusair.neo.databinding.OverlayPanelBinding
import com.neoplusair.neo.system.SystemMonitor

/** Floating holographic system-monitor panel (draggable overlay window). */
class FloatingPanelService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var binding: OverlayPanelBinding? = null
    private val handler = Handler(Looper.getMainLooper())
    private val loop = object : Runnable {
        override fun run() {
            binding?.holoOverlay?.setStats(SystemMonitor.summary(this@FloatingPanelService))
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (overlayView != null) return START_STICKY
        showOverlay()
        startForeground(NOTIF_ID, buildNotification())
        handler.post(loop)
        return START_STICKY
    }

    private fun showOverlay() {
        val b = OverlayPanelBinding.inflate(layoutInflater)
        binding = b
        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40; y = 160
        }

        b.btnCloseOverlay.setOnClickListener { stopSelf() }
        enableDrag(b.root, params)
        overlayView = b.root
        windowManager.addView(b.root, params)
    }

    private fun enableDrag(view: View, params: WindowManager.LayoutParams) {
        view.setOnTouchListener(object : View.OnTouchListener {
            private var dx = 0; private var dy = 0
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        dx = params.x - event.rawX.toInt()
                        dy = params.y - event.rawY.toInt()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = dx + event.rawX.toInt()
                        params.y = dy + event.rawY.toInt()
                        windowManager.updateViewLayout(view, params)
                    }
                }
                return false
            }
        })
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "NEO Overlay", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder
            .setContentTitle("NEO PLUS AIR")
            .setContentText("Holographic panel active")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(loop)
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null; binding = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "neo_overlay"
        private const val NOTIF_ID = 1
    }
}
