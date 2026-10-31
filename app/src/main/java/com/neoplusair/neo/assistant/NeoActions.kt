
package com.neoplusair.neo.assistant

/** Actions the AI brain may invoke; implemented by MainActivity. */
interface NeoActions {
    fun speak(text: String)
    fun openTab(index: Int)
    fun toggleListening()
    fun startMonitoringOverlay()
    fun goBack(): Boolean
    fun goHome(): Boolean
    fun openRecents(): Boolean
}
