
# NEO PLUS AIR — Personal AI Operating Interface

A Tony Stark / JARVIS-inspired Android interface: holographic dashboard,
air-gesture control (MediaPipe), a voice assistant, an application
launcher, live system telemetry, and a floating holographic panel.

Pure **Kotlin**, minSdk 26, targetSdk 34, APK-ready Gradle config.

---------------------------------------------------------------------

## 1. PROJECT MAP

    app/src/main/java/com/neoplusair/neo/
    ├── MainActivity.kt                  Host: tabs, mic state, permission flow
    ├── ar/ArSupport.kt                  ARCore probe (optional at runtime)
    ├── assistant/
    │   ├── NeoActions.kt                UI actions the AI may invoke
    │   ├── NeoBrain.kt                  Command parser -> system actions
    │   ├── ConversationalAi.kt          Pluggable AI backend (offline default)
    │   └── JarvisPhrases.kt             JARVIS phrase bank
    ├── gesture/HandTrackingManager.kt   MediaPipe engine + gesture heuristics
    ├── launcher/InstalledAppsRepository.kt  Installed-app discovery/launch
    ├── services/
    │   ├── FloatingPanelService.kt      Draggable holographic overlay panel
    │   └── NeoAccessibilityService.kt   OPTIONAL back/home/recents only
    ├── system/SystemMonitor.kt          Battery / RAM / storage / network /
    │                                    volume / brightness
    ├── ui/
    │   ├── HoloDashboardView.kt         Animated circular holographic HUD
    │   ├── DashboardFragment.kt         Live telemetry dashboard
    │   ├── AppsFragment.kt              Holographic app launcher grid
    │   ├── GestureFragment.kt           Camera + gesture link
    │   └── SectionsPagerAdapter.kt
    └── voice/
        ├── JarvisVoiceAssistant.kt      Continuous SpeechRecognizer loop
        └── JarvisTts.kt                 JARVIS-style text-to-speech

---------------------------------------------------------------------

## 2. BUILD

Option A — Android Studio (recommended):
    1. Open the NEO_PLUS_AIR folder in Android Studio (Hedgehog+).
    2. Let Gradle sync. The wrapper jar is generated automatically by
       Android Studio; if you use the CLI first, run:
           gradle wrapper --gradle-version 8.7
    3. Download the hand model (section 3) — optional but required for
       air gestures.
    4. Run on a device (min Android 8.0), or Build > Generate Signed APK.

Option B — command line:
    ./gradlew assembleDebug          (after generating the wrapper)
Output: app/build/outputs/apk/debug/app-debug.apk

---------------------------------------------------------------------

## 3. HAND-GESTURE MODEL (MediaPipe)

Air gestures use the MediaPipe Gesture Recognizer model bundle.
Google does not permit redistributing the binary in source form, so
download it once and drop it in:

    URL:  https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/latest/gesture_recognizer.task
    Into: app/src/main/assets/gesture_recognizer.task

Until then, the app says so on the GESTURE tab and every other feature
keeps working — this is the graceful fallback.

---------------------------------------------------------------------

## 4. PERMISSIONS (all user-consented at runtime)

| Permission                        | Why                                   |
|-----------------------------------|---------------------------------------|
| CAMERA                            | hand tracking                          |
| RECORD_AUDIO                      | voice commands                         |
| POST_NOTIFICATIONS (API 33+)      | keeps the holo panel service alive     |
| SYSTEM_ALERT_WINDOW               | floating holographic panel (prompt)    |
| WRITE_SETTINGS                    | brightness control (prompt on first use)|
| Accessibility service (optional)  | back/home/recents on voice command     |

Nothing is granted silently. The accessibility service is OFF by default
and performs global navigation actions ONLY when you command it.

---------------------------------------------------------------------

## 5. CONTROLS

Voice (tap ◉ VOICE LINK, or pinch in the air):
    "Good morning, NEO PLUS."      greeting + time + status
    "Show my applications."        launcher tab
    "Open my gallery."             launches Gallery (any app name works)
    "Check my battery."            spoken battery report
    "Activate system monitoring."  floating holographic panel
    "Close the current application."  global back (accessibility)
    "Volume up / down", "Brightness up / down", "Go home", "Recents"

Air gestures (GESTURE tab, front camera):
    Pinch thumb+index      -> select / toggle voice link
    Swipe hand left/right  -> navigate tabs
    Open palm              -> confirm / status
    Spread two hands       -> deploy holo panel
    Bring hands together   -> dismiss holo panel

---------------------------------------------------------------------

## 6. ARCore FALLBACK

ARCore is declared OPTIONAL in the manifest and queried at runtime
(ArSupport). On unsupported devices the dashboard shows
"AR: N/A — HOLO FALLBACK MODE" and the full 2D holographic experience
runs normally. No AR feature hard-fails.

---------------------------------------------------------------------

## 7. CLOUD AI INTEGRATION POINT

NeoBrain delegates anything it doesn't understand to ConversationalAi.
The default OfflineJarvisBrain is fully local and functional. To add a
cloud LLM, implement ConversationalAi with your HTTP client + API key
and change one line in NeoBrain (`OfflineJarvisBrain()` -> your class).
No UI changes needed.

---------------------------------------------------------------------

## 8. HONEST LIMITATIONS

- Two-hand *rotation of 3D objects* is mapped to panel expand/minimize;
  true free-rotation would need a GL scene graph — HoloDashboardView is
  a 2D canvas HUD by design for device compatibility.
- Android's SpeechRecognizer is on-device/OS-provided; quality varies
  by manufacturer. No third-party STT is bundled.
- Reading the notification stream requires NotificationListener
  permission, which this build intentionally leaves out to keep the
  permission surface minimal.
