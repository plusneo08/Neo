package com.neoplus.assistant.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.neoplus.assistant.NeoApp
import com.neoplus.assistant.ai.ApiKeyInfo
import com.neoplus.assistant.ai.KeyAdminApi
import kotlinx.coroutines.launch
import com.neoplus.assistant.voice.WakeWordService

private val LANGS = listOf("" to "Phone default", "en-US" to "English (US)", "en-GB" to "English (UK)", "es-ES" to "Español",
    "fr-FR" to "Français", "de-DE" to "Deutsch", "pt-BR" to "Português (BR)", "ar-SA" to "العربية", "hi-IN" to "हिन्दी",
    "zh-CN" to "中文", "ja-JP" to "日本語", "ru-RU" to "Русский")

@Composable
fun SettingsScreen(app: NeoApp, session: AssistantSession, onTheme: (String) -> Unit) {
    val ctx = LocalContext.current
    val p = app.prefs
    val scope = rememberCoroutineScope()
    val keyApi = remember { KeyAdminApi({ p.backendUrl }, { p.adminToken }) }
    var keys by remember { mutableStateOf<List<ApiKeyInfo>?>(null) }
    var keyMsg by remember { mutableStateOf("") }
    var adminTok by remember { mutableStateOf(p.adminToken) }
    var showAdd by remember { mutableStateOf(false) }
    fun applyKeys(o: com.neoplus.assistant.ai.KeyOutcome) { if (o.keys != null) keys = o.keys; keyMsg = o.message }
    var speak by remember { mutableStateOf(p.speakReplies) }
    var continuous by remember { mutableStateOf(p.continuous) }
    var wake by remember { mutableStateOf(p.wakeWord) }
    var lang by remember { mutableStateOf(p.language) }
    var theme by remember { mutableStateOf(p.theme) }
    var showBackend by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.RECORD_AUDIO] == true) { p.wakeWord = true; wake = true; WakeWordService.start(ctx) }
        else info = "Microphone permission is required for the wake word."
    }
    val phonePerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("SETTINGS", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)

        Section("AI backend")
        Text(if (p.configured) "Connected to ${p.backendUrl}" else "Not configured")
        Button(onClick = { showBackend = true }) { Text("Backend URL and token") }

        Section("Gemini API keys (stored on your backend)")
        Text("Add as many as you like. Exactly one is active at a time; nothing switches automatically.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(adminTok, { adminTok = it; p.adminToken = it }, label = { Text("Admin token") }, singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scope.launch { keyMsg = "Loading…"; applyKeys(keyApi.list()) } }, enabled = p.configured) { Text("Load keys") }
            OutlinedButton(onClick = { showAdd = true }, enabled = p.configured) { Text("Add key") }
        }
        keys?.let { list ->
            if (list.isEmpty()) Text("No keys stored yet.")
            list.forEach { k ->
                Card { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(k.label + if (k.active) "  ● ACTIVE" else "", style = MaterialTheme.typography.titleSmall)
                        Text(k.masked, style = MaterialTheme.typography.bodySmall)
                    }
                    if (!k.active) {
                        TextButton({ scope.launch { applyKeys(keyApi.activate(k.id)) } }) { Text("Use") }
                        TextButton({ scope.launch { applyKeys(keyApi.delete(k.id)) } }) { Text("Delete") }
                    }
                } }
            }
        }
        if (keyMsg.isNotBlank()) Text(keyMsg, color = MaterialTheme.colorScheme.secondary)

        Section("Voice")
        SwitchRow("Speak replies aloud", speak) { speak = it; p.speakReplies = it }
        SwitchRow("Continuous conversation (listen again after each reply)", continuous) { continuous = it; p.continuous = it }
        SwitchRow("Wake word \"Hey NEO\" (background listening)", wake) { on ->
            if (on) {
                val req = mutableListOf(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) req += Manifest.permission.POST_NOTIFICATIONS
                perms.launch(req.toTypedArray())
            } else { wake = false; p.wakeWord = false; WakeWordService.stop(ctx) }
        }
        Text("Wake word uses Android's speech recognizer in a foreground service: more battery, not a dedicated wake-word engine.",
            style = MaterialTheme.typography.bodySmall)
        Text("Language")
        var open by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { open = true }) { Text(LANGS.firstOrNull { it.first == lang }?.second ?: lang) }
            DropdownMenu(open, { open = false }) {
                LANGS.forEach { (tag, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { lang = tag; p.language = tag; session.voice.applyLocale(); open = false })
                }
            }
        }

        Section("Appearance")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("system", "dark", "light").forEach { m ->
                if (theme == m) Button(onClick = {}) { Text(m) }
                else OutlinedButton(onClick = { theme = m; p.theme = m; onTheme(m) }) { Text(m) }
            }
        }

        Section("Phone permissions")
        Button(onClick = {
            phonePerms.launch(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS))
        }) { Text("Grant call, SMS and contacts") }

        Section("Security")
        Text("${app.registry.devices.size} paired device(s).")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { app.remote.disconnectAll(); info = "All live device connections closed." }) { Text("Disconnect all") }
            OutlinedButton(onClick = { showLog = true }) { Text("Command history") }
        }
        OutlinedButton(onClick = { session.clear(); info = "Conversation cleared." }) { Text("Clear conversation") }
        if (info.isNotBlank()) Text(info, color = MaterialTheme.colorScheme.secondary)
        Text("NEO PLUS 0.2.0 · Intelligence at your command.", style = MaterialTheme.typography.bodySmall)
    }

    if (showBackend) {
        var url by remember { mutableStateOf(p.backendUrl) }
        var tok by remember { mutableStateOf(p.deviceToken) }
        AlertDialog(
            onDismissRequest = { showBackend = false }, title = { Text("Backend setup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(url, { url = it }, label = { Text("Backend URL (https://…)") }, singleLine = true)
                    OutlinedTextField(tok, { tok = it }, label = { Text("Device token") }, singleLine = true)
                }
            },
            confirmButton = { TextButton({ p.backendUrl = url; p.deviceToken = tok; showBackend = false }) { Text("Save") } },
            dismissButton = { TextButton({ showBackend = false }) { Text("Cancel") } },
        )
    }
    if (showAdd) {
        var label by remember { mutableStateOf("") }
        var key by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false }, title = { Text("Add Gemini API key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(label, { label = it }, label = { Text("Label (e.g. personal)") }, singleLine = true)
                    OutlinedTextField(key, { key = it }, label = { Text("API key") }, singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                    Text("It is sent over HTTPS to your backend and is not kept on this phone.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton({
                val l = label; val k = key; showAdd = false
                scope.launch { keyMsg = "Saving…"; applyKeys(keyApi.add(l, k)) }
            }) { Text("Save") } },
            dismissButton = { TextButton({ showAdd = false }) { Text("Cancel") } },
        )
    }
    if (showLog) AlertDialog(
        onDismissRequest = { showLog = false }, title = { Text("Recent commands") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val lines = app.audit.recent(40).reversed()
                if (lines.isEmpty()) Text("Nothing yet.")
                lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(6.dp)) }
            }
        },
        confirmButton = { TextButton({ showLog = false }) { Text("Close") } },
    )
}

@Composable private fun Section(t: String) { Spacer(Modifier.height(6.dp)); Text(t, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary) }

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(checked, onChange)
    }
}
