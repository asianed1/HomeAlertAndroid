# HomeAlertAndroid

Standalone sideloaded Android home security & passive motion-detector application built in **Kotlin**, **Jetpack Compose (Material 3)**, and **CameraX**.

## Key Capabilities
- **CameraX YUV_420_888 Motion Engine**: Subsampled Y-plane luma comparison throttled to ~3.5 FPS, with global auto-exposure jump suppression, 2-frame debounce, and configurable cooldown.
- **Stealth / Blackout Mode**: Pure `#000000` OLED overlay, `0.01f` screen brightness, `FLAG_KEEP_SCREEN_ON`, dim pulsing status indicator, and double-tap or long-press unlock.
- **3-Tier Smart Auto-Failover**:
  1. **Tier 1 (Wi-Fi)**: Requires `NET_CAPABILITY_VALIDATED` + strict 4-second timeout.
  2. **Tier 2 (Telco Data)**: Explicitly requests `TRANSPORT_CELLULAR` via `ConnectivityManager.requestNetwork(...)` and binds OkHttp / SMTP sockets directly to the cellular `Network` even if Wi-Fi router stays connected without outside internet.
  3. **Tier 3 (Telco SMS)**: Falls back to `SmsManager` (Dual-SIM aware) with an independent 90-second anti-flood guard.
- **Alert Channels**: Telegram `sendPhoto` multipart upload, Gmail TLS SMTP (`smtp.gmail.com`), or Both in parallel with a short-lived retry queue.

## Building & Signing the Standalone APK
1. Open `HomeAlertAndroid` in **Android Studio Ladybug / Meerkat** (JDK 17, Android SDK 35).
2. Generate a release keystore for sideloading:
   ```bash
   chmod +x generate_keystore.sh
   ./generate_keystore.sh
   ```
3. Run unit tests:
   ```bash
   ./gradlew testDebugUnitTest
   ```
4. Build the ProGuard-optimized, signed release APK:
   ```bash
   ./gradlew assembleRelease
   ```
   Output APK: `app/build/outputs/apk/release/app-release.apk`
