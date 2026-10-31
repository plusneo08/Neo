
package com.neoplusair.neo.gesture

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.BitmapImageBuilder
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * MediaPipe hand-tracking engine.
 *
 * Gesture vocabulary (custom heuristics on top of 21 hand landmarks):
 *  PINCH      – thumb tip touches index tip            -> select / voice link
 *  SWIPE_L/R  – fast horizontal index-tip motion        -> navigate tabs
 *  SWIPE_U/D  – fast vertical index-tip motion          -> future: scroll
 *  OPEN_PALM  – all four fingers extended               -> confirm / status
 *  EXPAND     – two hands move apart                    -> deploy holo panel
 *  MINIMIZE   – two hands move together                 -> collapse holo panel
 *
 * Requires gesture_recognizer.task in assets (see README). Degrades
 * gracefully when the model is missing.
 */
class HandTrackingManager(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onGesture(gesture: String)
        fun onUnavailable(reason: String)
    }

    private var recognizer: GestureRecognizer? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastPinch = false
    private var lastOpenPalm = false
    private var lastIndexX = 0.5f
    private var lastIndexY = 0.5f
    private var lastSwipeTime = 0L
    private var lastTwoHandDist = 0f
    private var lastExpandTime = 0L

    fun modelExists(): Boolean = try {
        context.assets.open(MODEL_PATH).close(); true
    } catch (e: Exception) { false }

    fun start(previewView: PreviewView, lifecycleOwner: LifecycleOwner) {
        if (!modelExists()) {
            listener.onUnavailable(
                "HAND TRACKING OFFLINE — gesture_recognizer.task not found in assets. " +
                "Download it per README.md. Voice and touch controls remain fully operational.")
            return
        }
        try {
            val baseOptions = BaseOptions.builder().setModelAssetPath(MODEL_PATH).build()
            val options = GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(baseOptions)
                .setNumHands(2)
                .setMinHandDetectionConfidence(0.6f)
                .setMinTrackingConfidence(0.5f)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener(this::onResults)
                .setErrorListener { e -> postUnavailable("Hand tracker error: ${e.message}") }
                .build()
            recognizer = GestureRecognizer.createFromOptions(context, options)

            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                val provider = future.get()
                cameraProvider = provider
                val preview = Preview.Builder().build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { proxy ->
                    val image = BitmapImageBuilder(proxy).build()
                    recognizer?.recognizeAsync(image, proxy.imageInfo.timestamp)
                    proxy.close()
                }
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(context))
        } catch (t: Throwable) {
            postUnavailable("Failed to start hand tracking: ${t.message}")
        }
    }

    fun stop() {
        cameraProvider?.unbindAll(); cameraProvider = null
        recognizer?.close(); recognizer = null
        executor.shutdown()
    }

    // ---------------- gesture heuristics ----------------

    private fun onResults(result: GestureRecognizerResult) {
        val hands = result.landmarks()
        if (hands.isEmpty()) { reset(); return }
        val now = System.currentTimeMillis()
        val hand = hands[0]

        detectPinch(hand, now)
        detectSwipe(hand, now)
        detectOpenPalm(hand)
        if (hands.size >= 2) detectTwoHandSpread(hands[0][9], hands[1][9], now)
        else lastTwoHandDist = 0f
    }

    private fun detectPinch(hand: List<NormalizedLandmark>, now: Long) {
        val ref = dist(hand[0], hand[9]) + 1e-6f
        val ratio = dist(hand[4], hand[8]) / ref
        val pinched = ratio < 0.55f
        if (pinched && !lastPinch) emit("PINCH")
        lastPinch = pinched
    }

    private fun detectSwipe(hand: List<NormalizedLandmark>, now: Long) {
        val ix = hand[8].x(); val iy = hand[8].y()
        if (now - lastSwipeTime > 500) {
            val dx = ix - lastIndexX; val dy = iy - lastIndexY
            if (abs(dx) > 0.11f && abs(dx) > abs(dy)) {
                emit(if (dx > 0) "SWIPE_RIGHT" else "SWIPE_LEFT"); lastSwipeTime = now
            } else if (abs(dy) > 0.14f) {
                emit(if (dy < 0) "SWIPE_UP" else "SWIPE_DOWN"); lastSwipeTime = now
            }
        }
        lastIndexX = ix; lastIndexY = iy
    }

    private fun detectOpenPalm(hand: List<NormalizedLandmark>) {
        val wrist = hand[0]
        val open = listOf(8, 12, 16, 20).all { dist(hand[it], wrist) > 0.34f }
        if (open && !lastOpenPalm) emit("OPEN_PALM")
        lastOpenPalm = open
    }

    private fun detectTwoHandSpread(a: NormalizedLandmark, b: NormalizedLandmark, now: Long) {
        val d = dist(a, b)
        if (lastTwoHandDist > 0f && now - lastExpandTime > 800) {
            val delta = d - lastTwoHandDist
            if (delta > 0.09f) { emit("EXPAND"); lastExpandTime = now }
            else if (delta < -0.09f) { emit("MINIMIZE"); lastExpandTime = now }
        }
        lastTwoHandDist = d
    }

    private fun reset() {
        lastPinch = false; lastOpenPalm = false; lastTwoHandDist = 0f
    }

    private fun dist(a: NormalizedLandmark, b: NormalizedLandmark): Float =
        sqrt((a.x() - b.x()).pow(2) + (a.y() - b.y()).pow(2) + (a.z() - b.z()).pow(2))

    private fun emit(gesture: String) =
        mainHandler.post { listener.onGesture(gesture) }

    private fun postUnavailable(msg: String) =
        mainHandler.post { listener.onUnavailable(msg) }

    companion object {
        private const val MODEL_PATH = "gesture_recognizer.task"
    }
}
