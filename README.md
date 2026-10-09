# Android-Reported Mobile Charging Cutoff

An ESP32-based controller that disconnects a mobile phone's 5 V charging supply when an Android app reports that its battery has reached 100%.

**This** describes the proposed Android-reporting version.

Proposed first version

- An Android app reads the phone-reported battery percentage.
- The app sends that percentage to the ESP32 over Bluetooth Low Energy (BLE).
- The ESP32 controls a 5 V relay module to connect or disconnect the phone's supply.
- The INA219 is optional for monitoring current; it does not decide the cutoff.
- Communication is local, with no cloud service or internet connection required.

The percentage is the phone's own state-of-charge estimate. The phone's internal charging electronics continue to manage its battery.

## Hardware and software

| Part | Role |
| --- | --- |
| Android phone and app | Read and report battery percentage |
| BLE-capable ESP32 board | Receive reports and control the relay |
| 5 V adapter | Provide the charging supply |
| 5 V relay module | Switch the phone's charging supply |
| Kotlin / Android Studio, proposed | Develop the Android app |
| ESP-IDF, proposed | Develop the ESP32 firmware |

The exact ESP32 board, relay input compatibility, pin connections, and supported Android versions still need to be documented.

## Functional diagram

```
flowchart TD
    A["5 V adapter"] -->|"Charging supply"| R["Relay contacts"]
    R -->|"Switched supply"| P["Android phone"]
    A -->|"Unswitched power via suitable regulation"| E["ESP32 board"]
    P -.->|"BLE battery reports"| E
    E -.->|"GPIO through relay module interface"| R
```

This is a functional diagram. Relay-module power, grounds, USB connector wiring, and the optional INA219 are omitted. The controller must remain powered after the phone's charging supply is disconnected.

## Proposed control behavior

| Condition | Intended response |
| --- | --- |
| Power-up or reset | Keep the phone's supply disconnected until the user starts a session and a valid battery report is received. |
| Active session, fresh report below 100% | Permit charging. |
| Fresh report reaches 100% | Disconnect charging and keep it off for that session. |
| BLE disconnects | Disconnect charging and end the session. |
| Battery reports become invalid or stale | Disconnect charging after a defined timeout and report a communication fault. |
| User starts a new session | Require a fresh, valid report below 100% before enabling charging again. |

The app would send updates when the percentage changes, plus a small periodic heartbeat so the ESP32 can detect missing reports. Heartbeat and timeout values remain to be selected and tested.

A user-started Android foreground service is proposed for reporting while the screen is locked. Its behavior and power consumption must be measured on the chosen phone. Monitoring would stop when the session ends.

## Planned source areas

| Folder | Intended contents |
| --- | --- |
| `android-app/` | Android application and build/install instructions |
| `firmware/` | ESP32 firmware and configuration |
| `schematics/` | Wiring, component details, and circuit exports |
| `validation/` | Logs and clearly labelled simulation or hardware results |

These are planned locations; this README update does not add those folders.

## First build and validation steps

1. Read and display the battery percentage in the Android app.
2. Send real battery updates to the ESP32 over BLE and log the received values.
3. Check cutoff decisions using simulated percentage reports and an indicator.
4. Integrate the relay after confirming its input interface and power wiring.
5. Verify cutoff current, reset behavior, lost connections, screen-locked operation, and app power consumption on hardware.

Reported 100% is the control trigger. Overcurrent, overtemperature, and other battery-protection functions are not established by this design.

## Technical references

- [Android BatteryManager](https://developer.android.com/reference/android/os/BatteryManager)
- [Android BLE overview](https://developer.android.com/develop/connectivity/bluetooth/ble/ble-overview)
- [Android BLE background operation](https://developer.android.com/develop/connectivity/bluetooth/ble/background)
- [ESP-IDF NimBLE](https://docs.espressif.com/projects/esp-idf/en/stable/esp32/api-reference/bluetooth/nimble/index.html)
