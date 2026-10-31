package com.neoplusair.neo.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.neoplusair.neo.MainActivity
import com.neoplusair.neo.databinding.FragmentGestureBinding
import com.neoplusair.neo.gesture.HandTrackingManager
import com.neoplusair.neo.services.FloatingPanelService
import android.content.Intent

/** Camera-backed air gestures. Camera is active only while this page is visible. */
class GestureFragment : Fragment(), HandTrackingManager.Listener {

    private var _b: FragmentGestureBinding? = null
    private val b get() = _b!!
    private var manager: HandTrackingManager? = null
    private var started = false

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && isResumed) tryStart()
        else if (!granted) showCameraPermissionRequired()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _b = FragmentGestureBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.btnEnableCamera.setOnClickListener {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
        showCameraPermissionRequired()
    }

    override fun onResume() {
        super.onResume()
        tryStart()
    }

    override fun onPause() {
        stopTracking()
        super.onPause()
    }

    private fun showCameraPermissionRequired() {
        val binding = _b ?: return
        binding.gestureStatus.text = "CAMERA PERMISSION REQUIRED FOR AIR GESTURES"
        binding.btnEnableCamera.visibility = View.VISIBLE
    }

    private fun tryStart() {
        val binding = _b ?: return
        val ctx = context ?: return
        if (!isResumed || started) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            showCameraPermissionRequired()
            return
        }
        binding.btnEnableCamera.visibility = View.GONE
        started = true
        manager = HandTrackingManager(ctx, this).also {
            it.start(binding.previewView, viewLifecycleOwner)
        }
    }

    private fun stopTracking() {
        manager?.stop()
        manager = null
        started = false
    }

    override fun onGesture(gesture: String) {
        val binding = _b ?: return
        binding.gestureStatus.text = "GESTURE DETECTED: $gesture"
        val activity = activity as? MainActivity ?: return
        val pager = activity.findViewById<androidx.viewpager2.widget.ViewPager2>(
            com.neoplusair.neo.R.id.viewPager)
        when (gesture) {
            "SWIPE_RIGHT" -> pager.setCurrentItem((pager.currentItem + 1).coerceAtMost(2), true)
            "SWIPE_LEFT" -> pager.setCurrentItem((pager.currentItem - 1).coerceAtLeast(0), true)
            "PINCH" -> activity.toggleListening()
            "OPEN_PALM" -> activity.speak("Gesture link active, sir.")
            "EXPAND" -> activity.startMonitoringOverlay()
            "MINIMIZE" -> context?.stopService(Intent(context, FloatingPanelService::class.java))
        }
    }

    override fun onUnavailable(reason: String) {
        _b?.gestureStatus?.text = reason
    }

    override fun onDestroyView() {
        stopTracking()
        _b = null
        super.onDestroyView()
    }
}
