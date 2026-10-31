
package com.neoplusair.neo.ar

import android.content.Context
import com.google.ar.core.ArCoreApk

/** ARCore availability probe — the UI runs a holographic fallback when unsupported. */
object ArSupport {
    fun isSupported(context: Context): Boolean = try {
        ArCoreApk.getInstance().checkAvailability(context).isSupported
    } catch (t: Throwable) {
        false
    }
}
