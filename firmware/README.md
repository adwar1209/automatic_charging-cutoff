# ESP32 firmware

ESP-IDF **v5.4.2**, C, FreeRTOS, and NimBLE. The initial build target is the
original BLE-capable **ESP32** (`idf.py set-target esp32`). Other targets have
not been validated; ESP32-S2 has no Bluetooth and cannot run this design.

- `main/app_main.c`: BLE service, authenticated pairing, relay GPIO and timeout task.
- `main/cutoff_core.c`: hardware-independent session and cutoff decisions.
- `main/Kconfig.projbuild`: relay pin, input polarity and report timeout.

## Build and flash

Install the [ESP-IDF v5.4.2 toolchain](https://docs.espressif.com/projects/esp-idf/en/v5.4.2/esp32/get-started/index.html)
and activate its environment. From this folder:

```sh
idf.py set-target esp32
idf.py menuconfig
idf.py build
idf.py -p YOUR_SERIAL_PORT flash monitor
```

In **Charging cutoff**, configure:

| Setting | Default | Meaning |
| --- | --- | --- |
| Relay GPIO | -1 | Physical GPIO disabled; BLE and control decisions still run |
| Active-low input | Enabled | Low enables the relay; high disables it |
| Report timeout | 90000 ms | OFF if accepted battery reports stop |

Choose an output-capable, non-strapping GPIO suitable for your exact board.
GPIO26 is an example for a conventional ESP32 DevKitC when available, **not a
universal wiring assignment**. Check your board documentation; avoid pins used
for flash, PSRAM, boot configuration, and other board functions. Match polarity
to measured relay behavior. Keep the timeout comfortably above the app's
30-second heartbeat plus connection and scheduling delays.

GPIO -1 is intentional: source cannot know your wiring. With -1, an ON status
is only an internal command. Configure the actual GPIO before expecting charging.
No INA219 driver is included; current sensing does not control this version.

## First pairing

1. Keep `idf.py monitor` open; the ESP32 advertises as `ChargeCutoff`.
2. In the app, find the controller, select it, and tap **Connect & start charging**.
3. Enter the six-digit `PAIRING PIN` from the serial monitor into Android's dialog.
4. A valid report below 100 commands ON; a report at 100 finishes with OFF.

Bonds are stored in NVS. Repeated pairing does not silently replace an old bond.
If pairing becomes inconsistent, stop charging, use Android's **Forget** device
option, then `idf.py -p YOUR_SERIAL_PORT erase-flash` and flash again.
Erase-flash removes all firmware/NVS data on this board; use it only for deliberate
reprovisioning. A new PIN is generated for each pairing attempt.

## Output behavior

Use **normally open (NO)** contacts: an unpowered coil should disconnect charging.
Firmware sets the off level before enabling the GPIO output. An external
bias/interface must keep the module off while the ESP32 resets and its pin is
high impedance. Software cannot control that boot interval.

Power the ESP32 and relay from the unswitched branch. See [wiring](../schematics/README.md)
and [protocol](../docs/BLE_PROTOCOL.md). Default-off, cutoff and timeout are software
behaviors, not protection against welded contacts, wrong wiring or a stalled MCU.
Status has no physical contact/current feedback. Hardware validation is pending.

From the repository root, run `python3 tools/test_host.py` for host control tests.
