# Android app

Native Kotlin app, package `com.adwar.chargingcutoff`. Minimum Android 8.0/API 26;
compile/target SDK 35. Uses Android battery broadcasts and BLE GATT; no account,
Internet permission or cloud service. A Java class holds the host-testable codec.

## Build and install

Open **this folder** in Android Studio. Use JDK 17, install Android SDK Platform 35
and Build Tools 35.0.0, and accept their SDK license terms. The included wrapper
selects Gradle 8.11.1. Android Gradle Plugin 8.9.2 and Kotlin 2.0.21 are pinned.
Initial dependency downloads require Internet access; the running app does not.

Or set `ANDROID_HOME` to your installed SDK and run:

```sh
./gradlew assembleDebug lintDebug
```

On Windows use `gradlew.bat`. The APK is `app/build/outputs/apk/debug/app-debug.apk`.
Install from Android Studio or use `adb install -r app/build/outputs/apk/debug/app-debug.apk`
with USB debugging enabled. Release signing is not configured; do not commit
signing keys or local SDK paths.

## Use

1. Power the ESP32 independently of the switched phone supply. Configure its GPIO
   and polarity; test the relay with a low-voltage load first.
2. Tap **Find controller** and grant Nearby devices/Bluetooth permissions.
   Android 8–11 also needs location permission and Location services for scanning.
   Notification permission on Android 13+ makes the reporting notification visible.
3. Select `ChargeCutoff`, then tap **Connect & start charging**.
4. Enter the pairing PIN shown by `idf.py monitor` on first use.
5. Keep Bluetooth enabled and the phone in range. Use the notification's **Stop**
   action or the app's **Stop charging** button to finish manually.

At reported 100%, firmware commands OFF; the app disconnects and stops reporting.
New sessions are manual. An operation failure closes the connection rather than
retrying an ambiguous command. Firmware also times out if the process disappears.

## Background reporting and energy use

A user-started `connectedDevice` foreground service sends percentage changes and
30-second heartbeats. A partial wake lock with a 120-second bound is refreshed
after accepted reports, allowing reports with the screen off. Cleanup releases
the lock and stops reporting. There is no boot receiver, automatic reconnect or
background restart after process death (`START_NOT_STICKY`).

This **does consume phone energy**: Bluetooth, reporting and keeping the CPU
available all have a cost. That cost has not been measured. Android/OEM power
management still affects operation. Test screen-locked operation, Battery Saver,
background restrictions and force-stop on your phone; a missed deadline must
leave the controller off.

The percentage is Android's estimate, not exact chemical state of charge. The
app does not override the phone's charging circuitry. A powered-off phone cannot
report: charge it normally enough to boot before using this system.

## Source map

Files under `app/src/main/java/com/adwar/chargingcutoff/`:

| File | Purpose |
| --- | --- |
| `MainActivity.kt` | Scan, select, start/stop, display state |
| `ChargingService.kt` | Pairing, serialized GATT transactions, reporting and cleanup |
| `AppState.kt` | Main-thread UI state and fresh battery reads |
| `Wire.java` | Versioned command encoding and status validation |

The UI shows **relay commanded ON/OFF**, not measured contact state. A lost
connection shows an unconfirmed state rather than a false physical OFF
confirmation. See the [protocol](../docs/BLE_PROTOCOL.md).
