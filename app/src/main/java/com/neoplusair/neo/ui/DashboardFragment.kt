
package com.neoplusair.neo.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.neoplusair.neo.MainActivity
import com.neoplusair.neo.ar.ArSupport
import com.neoplusair.neo.databinding.FragmentDashboardBinding
import com.neoplusair.neo.system.SystemMonitor

class DashboardFragment : Fragment() {
    private var _b: FragmentDashboardBinding? = null
    private val b get() = _b!!
    private val handler = Handler(Looper.getMainLooper())

    private val loop = object : Runnable {
        override fun run() { refreshStats(); handler.postDelayed(this, 2000) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentDashboardBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val activity = activity as? MainActivity ?: return
        b.btnVoice.setOnClickListener { activity.toggleListening() }
        b.btnOverlay.setOnClickListener { activity.startMonitoringOverlay() }
        b.btnSettings.setOnClickListener { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        b.txtAr.text = if (ArSupport.isSupported(requireContext()))
            "AR: ONLINE" else "AR: N/A — HOLO FALLBACK MODE"
    }

    private fun refreshStats() {
        val ctx = context ?: return
        val summary = SystemMonitor.summary(ctx)
        b.txtStats.text = summary
        b.holo.setStats(summary)
    }

    override fun onResume() { super.onResume(); refreshStats(); handler.post(loop) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(loop) }
    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
