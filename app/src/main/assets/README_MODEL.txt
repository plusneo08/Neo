
=====================================================================
NEO PLUS AIR - HAND TRACKING MODEL (required for air gestures only)
=====================================================================

Download this file:
    gesture_recognizer.task

Official MediaPipe model bundle (float16, ~7 MB):
https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/latest/gesture_recognizer.task

Place it HERE:
    app/src/main/assets/gesture_recognizer.task

The GESTURE tab will show "HAND TRACKING OFFLINE" until the file is
present. Everything else (voice, launcher, holo panel) works without it.

Do NOT commit binary models to version control if your workflow
forbids it - download it at build time instead.
