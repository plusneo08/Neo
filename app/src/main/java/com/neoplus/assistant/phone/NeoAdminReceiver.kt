package com.neoplus.assistant.phone

import android.app.admin.DeviceAdminReceiver

/** Needed only so lock_screen can call DevicePolicyManager.lockNow(). Grants no other policy. */
class NeoAdminReceiver : DeviceAdminReceiver()
