package com.neoplus.assistant.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.neoplus.assistant.NeoApp
import com.neoplus.assistant.voice.VoiceController
import com.neoplus.assistant.voice.WakeWordService

class MainActivity : ComponentActivity() {
    private lateinit var voice: VoiceController
    private lateinit var app: NeoApp
    private var wakeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as NeoApp
        voice = VoiceController(this, app.prefs)
        val session = AssistantSession(app, voice, lifecycleScope)
        if (intent?.getBooleanExtra(WakeWordService.EXTRA_WAKE, false) == true) wakeTick++
        setContent {
            var themeMode by remember { mutableStateOf(app.prefs.theme) }
            var tab by rememberSaveable { mutableStateOf(0) }
            NeoTheme(themeMode) {
                Column(Modifier.fillMaxSize().systemBarsPadding()) {
                    Box(Modifier.weight(1f)) {
                        when (tab) {
                            0 -> AssistantScreen(app, session, wakeTick)
                            1 -> DevicesScreen(app)
                            else -> SettingsScreen(app, session) { themeMode = it }
                        }
                    }
                    NavigationBar {
                        listOf("Assistant", "Devices", "Settings").forEachIndexed { i, label ->
                            NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(listOf("◉", "⌁", "⚙")[i]) }, label = { Text(label) })
                        }
                    }
                }
                session.pending?.let { (prompt, d) ->
                    AlertDialog(
                        onDismissRequest = { d.complete(false) },
                        title = { Text("Confirm") }, text = { Text(prompt) },
                        confirmButton = { TextButton({ d.complete(true) }) { Text("Yes") } },
                        dismissButton = { TextButton({ d.complete(false) }) { Text("Cancel") } },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(WakeWordService.EXTRA_WAKE, false)) wakeTick++
    }

    override fun onResume() {
        super.onResume()
        WakeWordService.uiVisible = true
        WakeWordService.instance?.pause() // the app owns the microphone while it is on screen
    }

    override fun onPause() {
        WakeWordService.uiVisible = false
        if (app.prefs.wakeWord) WakeWordService.instance?.resume()
        super.onPause()
    }

    override fun onDestroy() { voice.release(); super.onDestroy() }
}
