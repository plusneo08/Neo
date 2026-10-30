package com.neoplus.assistant.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.neoplus.assistant.NeoApp

@Composable
fun AssistantScreen(app: NeoApp, session: AssistantSession, wakeTick: Int) {
    val ctx = LocalContext.current
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    fun micGranted() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) session.onOrbTap() }

    LaunchedEffect(wakeTick) { if (wakeTick > 0 && micGranted()) session.onOrbTap() }
    LaunchedEffect(session.msgs.size) { if (session.msgs.isNotEmpty()) list.animateScrollToItem(session.msgs.lastIndex) }

    val status = when (session.orbState) {
        OrbState.LISTENING -> session.voice.partial.ifBlank { "Listening…" }
        OrbState.THINKING -> "Thinking…"
        OrbState.SPEAKING -> "Speaking. Tap the orb to interrupt."
        OrbState.IDLE -> if (!app.prefs.configured) "Open Settings to connect the backend." else "Tap the orb and speak, or type below."
    }
    val paired = app.registry.devices
    val live = paired.count { app.remote.isConnected(it.id) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("NEO PLUS", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Orb(session.orbState, session.voice.level, Modifier
                .semantics { contentDescription = "Voice orb. Tap to talk." }
                .clickable { if (micGranted()) session.onOrbTap() else mic.launch(Manifest.permission.RECORD_AUDIO) })
            Text(status, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (paired.isEmpty()) "No devices paired" else "${paired.size} device(s) paired, $live connected",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary,
            )
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(session.msgs) { m ->
                Box(Modifier.fillMaxWidth(), contentAlignment = if (m.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (m.fromUser) MaterialTheme.colorScheme.primary.copy(alpha = .2f) else MaterialTheme.colorScheme.surface,
                    ) { Text(m.text, Modifier.padding(12.dp)) }
                }
            }
        }
        if (session.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Tell NEO what to do") }, maxLines = 3)
            Spacer(Modifier.width(8.dp))
            Button(onClick = { session.sendTyped(input); input = "" }, enabled = !session.busy && app.prefs.configured && input.isNotBlank()) { Text("Send") }
        }
    }
}
