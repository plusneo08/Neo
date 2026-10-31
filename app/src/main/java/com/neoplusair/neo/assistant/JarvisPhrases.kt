
package com.neoplusair.neo.assistant

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** JARVIS personality phrase bank. */
object JarvisPhrases {
    fun greeting(): String {
        val h = SimpleDateFormat("HH", Locale.US).format(Date()).toInt()
        val daypart = when {
            h < 12 -> "Good morning"
            h < 18 -> "Good afternoon"
            else -> "Good evening"
        }
        val time = SimpleDateFormat("h:mm a", Locale.US).format(Date())
        return "$daypart, sir. NEO PLUS AIR online and at your service. The time is $time. " +
               "All systems are running at optimal capacity."
    }

    fun help(): String =
        "At your command: say 'show my applications', 'open gallery', 'check my battery', " +
        "'activate system monitoring', 'volume up', 'brightness down', or 'close the current application'. " +
        "Air gestures: pinch to select, swipe to navigate, spread both hands for the holographic panel."
}
