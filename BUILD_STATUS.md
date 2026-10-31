# NEO PLUS AIR build status

- Source and Android resource files have been statically inspected.
- Android XML resources parse successfully.
- A GitHub Actions workflow is included at `.github/workflows/android-build.yml`.
- The workflow installs JDK 17, Android SDK 34, and Gradle 8.7, then runs `:app:assembleDebug`.
- This environment does not contain Gradle or the Android SDK, and outbound package downloads are unavailable. Therefore an APK build could not be executed or verified here.
- `app/src/main/assets/gesture_recognizer.task` is intentionally not included. Add the model using the instructions in `README.md` to enable MediaPipe air gestures. Without it, the app reports that gesture tracking is offline while other app functions remain available.
- The archive's original project structure, package name (`com.neoplusair.neo`), and existing UI/features have been preserved.
