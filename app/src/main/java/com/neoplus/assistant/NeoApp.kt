package com.neoplus.assistant

import android.app.Application
import com.neoplus.assistant.ai.GeminiProvider
import com.neoplus.assistant.automation.Orchestrator
import com.neoplus.assistant.devices.DeviceRegistry
import com.neoplus.assistant.devices.DeviceTools
import com.neoplus.assistant.devices.RemoteCommandManager
import com.neoplus.assistant.phone.PhoneActionEngine
import com.neoplus.assistant.security.AuditLogger
import com.neoplus.assistant.security.Prefs

/** Process-wide singletons, so the activity and the wake-word service share one state. */
class NeoApp : Application() {
    lateinit var prefs: Prefs; private set
    lateinit var audit: AuditLogger; private set
    lateinit var registry: DeviceRegistry; private set
    lateinit var remote: RemoteCommandManager; private set
    lateinit var orchestrator: Orchestrator; private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        audit = AuditLogger(this)
        registry = DeviceRegistry(prefs)
        remote = RemoteCommandManager(registry)
        orchestrator = Orchestrator(
            GeminiProvider(baseUrl = { prefs.backendUrl }, token = { prefs.deviceToken }),
            PhoneActionEngine(applicationContext, audit),
            DeviceTools(registry, remote, audit),
        )
    }
}
