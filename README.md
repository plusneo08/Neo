# NEO PLUS: Phase 1-3 (brain, phone control, voice + orb UI, Windows PC connection)

Standalone Android app + secure Gemini backend + Windows companion.
**Not built yet:** Android-to-Android companion, smart-home (Matter etc.), full security dashboard, notification reading, file transfer, Firebase/WebRTC, backup/restore.
**Nothing in `app/` or `windows-companion/` has been compiled or run** (no Android SDK or .NET in the authoring sandbox). Only `backend/` was run and tested. Expect small compile errors on first build.

## Layout
```
app/            Android (Kotlin, Compose)  ai/ phone/ devices/ voice/ automation/ security/ ui/
backend/        Node: holds the Gemini key, authenticates the app, rate-limits
windows-companion/NEO-Windows/   .NET 8 console companion (TLS + WebSocket)
```

## 1. Backend
```
cd backend && cp .env.example .env     # DEVICE_TOKENS, ADMIN_TOKEN, KEYS_SECRET (each: openssl rand -hex 32), GEMINI_MODEL / ALLOWED_MODELS
npm install && node --env-file=.env server.js
```
**Multiple Gemini keys:** in the app, Settings > Gemini API keys: enter the admin token, Add key, then Use on the one you want. Any number can be stored; exactly one is active; the backend never switches by itself, and the active one cannot be deleted. Keys are encrypted on the backend (`KEYS_SECRET`), never returned in full by the API, and not stored on the phone. Without `ADMIN_TOKEN` the key endpoints are disabled and you can seed one key with `GEMINI_API_KEY`.
Deploy behind HTTPS (Cloud Run, Render, Fly, VPS + Caddy). The app refuses plain http.
Tested here (with a fake upstream): key add/duplicate/short-key rejection, only one active, chat uses exactly the active key, delete-active blocked, encrypted file mode 600, reload after restart, wrong secret rejected, device token cannot reach admin; plus health, 401 without/with bad token, 400 on bad body, 504 when Gemini unreachable, rate limit (30/min) returns 429.

## 2. Android
Open in Android Studio, or push to GitHub and use the `Build debug APK` workflow. First run: Settings tab -> Backend URL + device token.
- Orb: tap to talk, tap while speaking to interrupt. Typed input works too.
- Settings: speak replies, continuous conversation, wake word, language, theme, command history, disconnect all.
- Wake word is a foreground service looping Android's SpeechRecognizer. It is not a low-power wake-word engine (more battery, may be killed, recognizer may beep). Android 10+ often blocks launching the app from the background, so NEO also posts a "tap to talk" notification.
- QR scanning uses Google Play services' code scanner; without Play services use "Enter manually".

## 3. Windows companion
Requires Windows 10/11 and the .NET 8 SDK: `cd windows-companion/NEO-Windows && dotnet run`.
Type `pair` -> QR code + 6-digit code (2 min, single use). On the phone: Devices -> Scan QR. Then **type `y` on the PC** to approve. Allow Windows Firewall on Private networks.
Console commands: `pair`, `devices`, `revoke <n>`, `stop` (emergency stop: disables remote control, closes sessions), `resume`, `quit`.
Open-app allow-list: `%APPDATA%\NEO-Windows\apps.json`. Audit log: `%APPDATA%\NEO-Windows\audit.jsonl`.
It is a console app that must be running in your logged-in session; it is not a Windows service yet.

### Security model
- PC makes a self-signed TLS cert on first run (DPAPI-protected). The QR/manual pairing carries its SHA-256 fingerprint; the phone trusts **only** that cert (pinned), so no CA, no MITM.
- Pairing needs the short-lived code **and** a manual `y` on the PC. 5 wrong codes kill the code. Codes are single-use.
- Phone gets a random 256-bit token (stored in Keystore-encrypted prefs); the PC stores only its SHA-256 hash. Every WebSocket connection is authenticated with it; revoked devices are dropped on their next message.
- The PC accepts only: ping, status, open_app (allow-list), media, volume, lock, revoke. No shell, no arbitrary paths or executables.
- LAN only. The phone stores the PC's IP; if DHCP changes it, re-pair (mDNS discovery not built).

## What each tool really reports
| Tool | Status you can trust |
|---|---|
| flashlight, battery, storage | VERIFIED (system callback / read) |
| set_volume, set_brightness | VERIFIED (read back) |
| send_sms | VERIFIED "sent by phone" (send report); delivery not confirmed |
| open_app, camera, navigate, alarm, timer, call, lock_screen | HANDED_OFF (Android gives no confirmation) |
| open_settings | opens the page; apps can't toggle Wi-Fi/Bluetooth on modern Android |
| pc_get_status, pc_set_volume | VERIFIED (read from the PC) |
| pc_open_app | VERIFIED if a new process appeared, else HANDED_OFF/FAILED |
| pc_media, pc_lock | HANDED_OFF (Windows gives no feedback) |
| disconnect_device | VERIFIED (socket observed closed) |

Lock (phone and PC), calls and SMS ask for on-screen confirmation first. Spoken confirmation is deliberately not accepted for them.

## Test checklist (real devices)
1. Backend `curl` test; Setup in app; "what's my battery?" (compare with the phone).
2. "Turn on the flashlight" / off; "set music volume to 30%".
3. Tap the orb, say "open YouTube". Interrupt a long reply by tapping the orb. Turn on continuous mode and hold a 3-turn conversation. Change language and repeat.
4. Enable wake word, lock the phone, say "Hey NEO": expect the notification (and maybe the app).
5. PC: `pair`, scan, approve. "Show connected devices". "PC status". "Open Chrome on my PC". "Set PC volume to 20" (check the mixer). "Lock my PC" (confirm dialog first).
6. Wrong code 6 times -> 429. Let a code expire -> 401. Decline on the PC -> 403.
7. `revoke 1` on the PC, then "PC status" on the phone -> must fail with "access revoked". `stop` -> commands must fail until `resume`.
8. Turn Wi-Fi off on the phone mid-command -> honest "can't reach" error.
