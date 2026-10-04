# 5G Monitor (Android)

A starter Android Studio project for detecting reported 5G display-state changes and alerting when a previously observed 5G connection changes to a non-5G state.

## Open and build
1. Install Android Studio (current stable) and the Android SDK Platform 35.
2. Open this folder (`5GMonitor`) in Android Studio and allow Gradle sync.
3. Connect your OnePlus 11R with USB debugging enabled.
4. Build > Build Bundle(s) / APK(s) > Build APK(s), then install the generated APK.
5. Grant Phone permission and notifications when prompted. Start monitoring in the app.

## Current scope / limitations
- Monitoring begins only after the user starts it in the app; automatic boot start is not included.
- Android's TelephonyDisplayInfo is used to infer 5G NSA/SA status. Carrier, modem, Android version and OEM behavior can affect what is reported. Some 5G transitions may not be exposed reliably.
- The app detects a loss only after it has observed a 5G state during that monitoring session. An initial non-5G state is not counted as a loss.
- A foreground service keeps monitoring more reliably, but Android/OxygenOS battery management may still stop it. Exempt the app from battery optimization if needed, where supported.
- Alarm loudness is not guaranteed: Android volume, Do Not Disturb, alarm-channel configuration, OEM restrictions and user settings apply. The app cannot force maximum volume or bypass silent modes.
- This is a starter implementation and should be compiled and tested on a physical device before relying on it.

## Security and reliability notes
- No INTERNET, location, contacts, SMS, or storage permissions are requested.
- Phone-state access is used only to observe telephony display-state changes on-device.
- Alarm audio is played locally; the notification channel is silent to avoid duplicate tones.
- Monitoring is user-initiated and uses a foreground service with an ongoing notification.
- Android/OxygenOS may still restrict background execution or alarm audibility. Do not rely on this app for safety-critical alerts.
- Source review is not a substitute for a successful build, APK inspection, and physical-device testing.
