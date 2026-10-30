package com.neoplus.assistant.ui

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.neoplus.assistant.NeoApp
import com.neoplus.assistant.devices.DevicePairing
import com.neoplus.assistant.devices.PairException
import com.neoplus.assistant.devices.PairInfo
import com.neoplus.assistant.devices.PairedDevice
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun DevicesScreen(app: NeoApp) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var banner by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }
    val notes = remember { mutableStateMapOf<String, String>() }

    fun pairWith(info: PairInfo) {
        scope.launch {
            banner = "Waiting for approval on the PC…"
            banner = try {
                val d = DevicePairing.pair(info, app.prefs.phoneId, Build.MODEL)
                app.registry.add(d)
                "Paired with ${d.name}."
            } catch (e: PairException) { e.message ?: "Pairing failed." }
        }
    }

    fun scan() {
        val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(ctx, opts).startScan()
            .addOnSuccessListener { b ->
                try { pairWith(DevicePairing.parse(b.rawValue.orEmpty())) } catch (e: PairException) { banner = e.message ?: "Bad code." }
            }
            .addOnFailureListener { banner = "Scan cancelled or unavailable (needs Google Play services): ${it.message}" }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("DEVICE CONNECTION", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Text("Windows PCs running the NEO PLUS companion. Android companion and smart-home devices are not built yet.",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = ::scan) { Text("Scan QR") }
            OutlinedButton(onClick = { manual = true }) { Text("Enter manually") }
            OutlinedButton(onClick = { app.remote.disconnectAll(); banner = "All live connections closed." }) { Text("Disconnect all") }
        }
        if (banner.isNotBlank()) Text(banner, color = MaterialTheme.colorScheme.secondary)

        val devices = app.registry.devices
        if (devices.isEmpty()) Text("Nothing paired. On the PC run the companion, type \"pair\", then scan the QR code here.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(devices, key = { it.id }) { d ->
                DeviceCard(app, d, notes[d.id].orEmpty()) { notes[d.id] = it }
            }
        }
    }

    if (manual) ManualPairDialog(onDismiss = { manual = false }) { host, port, code, fp ->
        manual = false
        try { pairWith(DevicePairing.fromManual(host, port, code, fp)) } catch (e: PairException) { banner = e.message ?: "Bad details." }
    }
}

@Composable
private fun DeviceCard(app: NeoApp, d: PairedDevice, note: String, setNote: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val live = app.remote.isConnected(d.id)
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(d.name, style = MaterialTheme.typography.titleMedium)
            Text("${d.category} · ${d.host}:${d.port} · " + if (live) "connected" else "no open session")
            Text("Paired ${DateFormat.getDateTimeInstance().format(Date(d.pairedAt))}" +
                if (d.lastConnected > 0) " · last connected ${DateFormat.getDateTimeInstance().format(Date(d.lastConnected))}" else "",
                style = MaterialTheme.typography.bodySmall)
            if (note.isNotBlank()) Text(note, color = MaterialTheme.colorScheme.secondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { setNote("Checking…"); setNote(app.remote.send(d, "status", timeoutMs = 15_000).message) } }) { Text("Status") }
                if (live) OutlinedButton(onClick = { app.remote.disconnect(d.id); setNote("Disconnected.") }) { Text("Disconnect") }
                else OutlinedButton(onClick = { scope.launch { setNote("Connecting…"); setNote(app.remote.send(d, "ping").message) } }) { Text("Connect") }
                TextButton(onClick = {
                    scope.launch {
                        val r = app.remote.revoke(d)
                        app.registry.remove(d.id)
                        setNote(if (r.status.name == "VERIFIED") "Removed and revoked on the PC."
                                else "Removed here, but the PC could not be told (${r.message}). Revoke this phone on the PC too.")
                    }
                }) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun ManualPairDialog(onDismiss: () -> Unit, onPair: (String, String, String, String) -> Unit) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8765") }
    var code by remember { mutableStateOf("") }
    var fp by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Pair manually") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(host, { host = it }, label = { Text("PC address") }, singleLine = true)
                OutlinedTextField(port, { port = it }, label = { Text("Port") }, singleLine = true)
                OutlinedTextField(code, { code = it }, label = { Text("6-digit code") }, singleLine = true)
                OutlinedTextField(fp, { fp = it }, label = { Text("Certificate fingerprint (64 hex)") })
            }
        },
        confirmButton = { TextButton({ onPair(host, port, code, fp) }) { Text("Pair") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
