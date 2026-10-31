
package com.neoplusair.neo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.tabs.TabLayoutMediator
import com.neoplusair.neo.assistant.NeoActions
import com.neoplusair.neo.assistant.NeoBrain
import com.neoplusair.neo.databinding.ActivityMainBinding
import com.neoplusair.neo.services.FloatingPanelService
import com.neoplusair.neo.services.NeoAccessibilityService
import com.neoplusair.neo.ui.SectionsPagerAdapter
import com.neoplusair.neo.voice.JarvisTts
import com.neoplusair.neo.voice.JarvisVoiceAssistant

/**
 * NEO PLUS AIR — Personal AI Operating Interface.
 * Hosts the holographic dashboard, application launcher and gesture link.
 */
class MainActivity : AppCompatActivity(), NeoActions {

    private lateinit var binding: ActivityMainBinding
    private lateinit var tts: JarvisTts
    private lateinit var voice: JarvisVoiceAssistant
    lateinit var brain: NeoBrain

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tts = JarvisTts(this)
        brain = NeoBrain(this, this)
        voice = JarvisVoiceAssistant(this, object : JarvisVoiceAssistant.Listener {
            override fun onCommand(text: String) = runOnUiThread { executeCommand(text) }
            override fun onState(state: JarvisVoiceAssistant.State) = runOnUiThread { updateMicState(state) }
        })

        binding.viewPager.adapter = SectionsPagerAdapter(this)
        binding.viewPager.offscreenPageLimit = 2
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, pos ->
            tab.text = SectionsPagerAdapter.TITLES[pos]
        }.attach()

        requestPermissionsIfNeeded()
    }

    private fun executeCommand(text: String) {
        val reply = brain.handleCommand(text)
        tts.speak(reply)
    }

    private fun updateMicState(state: JarvisVoiceAssistant.State) {
        when (state) {
            JarvisVoiceAssistant.State.LISTENING -> {
                binding.micState.text = getString(R.string.mic_listening)
                binding.micDot.alpha = 1f
            }
            JarvisVoiceAssistant.State.PROCESSING -> {
                binding.micState.text = getString(R.string.mic_processing)
                binding.micDot.alpha = 0.7f
            }
            JarvisVoiceAssistant.State.IDLE -> {
                binding.micState.text = getString(R.string.mic_idle)
                binding.micDot.alpha = 0.3f
            }
        }
    }

    // ---------- NeoActions ----------

    override fun speak(text: String) = tts.speak(text)

    override fun openTab(index: Int) {
        binding.viewPager.setCurrentItem(index.coerceIn(0, 2), true)
    }

    override fun toggleListening() {
        if (voice.isListening) voice.stop() else voice.start()
    }

    override fun startMonitoringOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            return
        }
        ContextCompat.startForegroundService(this,
            Intent(this, FloatingPanelService::class.java))
    }

    override fun goBack() = NeoAccessibilityService.back()
    override fun goHome() = NeoAccessibilityService.home()
    override fun openRecents() = NeoAccessibilityService.recents()

    // ---------- Permissions ----------

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) binding.viewPager.adapter?.notifyDataSetChanged()
    }

    override fun onDestroy() {
        voice.destroy()
        tts.shutdown()
        super.onDestroy()
    }

    companion object { private const val REQ_PERMISSIONS = 42 }
}
