
package com.neoplusair.neo.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.neoplusair.neo.MainActivity
import com.neoplusair.neo.databinding.FragmentGestureBinding
import com.neoplusair.neo.gesture.HandTrackingManager
import com.neoplusair.neo.services.FloatingPanelService

/**
 * Air-gesture control link. Hand tracking via MediaPipe; if the model is
 * absent or the camera is denied, voice + touch remain fully operational.
 */
class GestureFragment : Fragment(), HandTrackingManager.Listener {

    private var _b: FragmentGestureBinding? = null
    private val b get() = _b!!
    private var manager: HandTrackingManager? = null
    private var started = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentGestureBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.btnEnableCamera.setOnClickListener {
            ActivityCompat.requestPermissions(requireActivity(),
                arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
        tryStart()
    }

    private fun tryStart() {
        val ctx = context ?: return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            b.gestureStatus.text = "CAMERA PERMISSION REQUIRED FOR AIR GESTURES"
            b.btnEnableCamera.visibility = View.VISIBLE
            return
        }
        b.btnEnableCamera.visibility = View.GONE
        if (started) return
        started = true
        manager = HandTrackingManager(ctx, this).also { it.start(b.previewView, viewLifecycleOwner) }
    }

    override fun onGesture(gesture: String) {
        b.gestureStatus.text = "GESTURE DETECTED: $gesture"
        val activity = activity as? MainActivity ?: return
        val pager = activity.findViewById<androidx.viewpager2.widget.ViewPager2>(
            com.neoplusair.neo.R.id.viewPager)
        when (gesture) {
            "SWIPE_RIGHT" -> pager.setCurrentItem((pager.currentItem + 1).coerceAtMost(2), true)
            "SWIPE_LEFT" -> pager.setCurrentItem((pager.currentItem - 1).coerceAtLeast(0), true)
            "PINCH" -> activity.toggleListening()          // pinch to select / activate voice
            "OPEN_PALM" -> activity.speak("Gesture link active, sir.")
            "EXPAND" -> activity.startMonitoringOverlay()  // spread hands -> holo panel
            "MINIMIZE" -> context?.stopService(Intent(context, FloatingPanelService::class.java))
        }
    }

    override fun onUnavailable(reason: String) {
        b.gestureStatus.text = reason
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        if (requestCode == REQ_CAMERA && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            started = false
            tryStart()
        }
    }

    override fun onDestroyView() {
        manager?.stop(); manager = null; started = false
        super.onDestroyView(); _b = null
    }

    companion object { private const val REQ_CAMERA = 43 }
}
