package com.neoplus.assistant.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/** Backend URL, device token, paired-device tokens and settings, encrypted with an Android Keystore-backed key. */
class Prefs(ctx: Context) {
    val sp: SharedPreferences = EncryptedSharedPreferences.create(
        ctx, "neoplus_secure",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    var backendUrl: String
        get() = sp.getString("url", "") ?: ""
        set(v) = sp.edit().putString("url", v.trim()).apply()
    var deviceToken: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v.trim()).apply()
    /** Separate from the device token: only this one may add, switch or delete Gemini keys on the backend. */
    var adminToken: String
        get() = sp.getString("admin", "") ?: ""
        set(v) = sp.edit().putString("admin", v.trim()).apply()
    val configured get() = backendUrl.startsWith("https://") && deviceToken.isNotEmpty()

    var speakReplies: Boolean
        get() = sp.getBoolean("speak", true)
        set(v) = sp.edit().putBoolean("speak", v).apply()
    var continuous: Boolean
        get() = sp.getBoolean("continuous", false)
        set(v) = sp.edit().putBoolean("continuous", v).apply()
    var wakeWord: Boolean
        get() = sp.getBoolean("wake", false)
        set(v) = sp.edit().putBoolean("wake", v).apply()
    /** BCP-47 tag such as "en-US"; empty = phone default. */
    var language: String
        get() = sp.getString("lang", "") ?: ""
        set(v) = sp.edit().putString("lang", v).apply()
    /** "system", "dark" or "light". */
    var theme: String
        get() = sp.getString("theme", "system") ?: "system"
        set(v) = sp.edit().putString("theme", v).apply()

    /** This phone's own identity, sent when pairing so a PC can list and revoke it. */
    val phoneId: String
        get() {
            var id = sp.getString("phone_id", null)
            if (id == null) { id = UUID.randomUUID().toString(); sp.edit().putString("phone_id", id).apply() }
            return id
        }
}
