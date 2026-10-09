# Validation status

This directory separates host test evidence from target builds and physical tests.
The source implements the intended behavior; it is not a claim of a validated
charging product.

| Check | Status |
| --- | --- |
| C cutoff/session logic | PASS on host, with undefined-behavior sanitizer |
| Java command codec / malformed status rejection | PASS on host JVM |
| Real Java commands through C controller, decoded by Java | PASS; 99% ON, 100% OFF, restart rejected |
| Android APK and lint | PASS: debug APK assembled, lint 0 errors / 17 warnings; no device execution claimed |
| ESP-IDF ESP32 target | Not yet verified; local dependency setup incomplete |
| BLE pairing/radio and Android lifecycle | NOT RUN on a phone/ESP32 |
| Relay wiring, current interruption and reset behavior | NOT RUN on hardware |
| Screen-locked operation and phone energy use | NOT MEASURED |

[host-test-results.txt](host-test-results.txt) and
[android-build-results.txt](android-build-results.txt) contain the actual results.
The 17 lint warnings concern English-only UI text and manifest compatibility/backup
configuration. See [android-lint-results.txt](android-lint-results.txt) for the findings.
The tests exercise startup, 0/99/100%, manual stop, disconnect, timeout boundaries,
late packets, replay, nonce/session mismatch, invalid lengths/fields, reset and
terminal-state latching, plus 10,000 malformed-version packets. They do not
simulate the Bluetooth stack, Android service lifecycle or electrical circuit.

Run from the repository root with a C compiler, Python 3 and JDK 17:

```sh
python3 tools/test_host.py
```

GitHub Actions defines host, Android and ESP32 build jobs. Inspect the actual run
before treating those jobs as successful; a checked-in workflow is not a test result.

## Pending hardware evidence

Use a low-voltage dummy load before connecting a phone. Record board/module model,
GPIO/polarity, firmware commit, Android version, phone model and timestamps.

| Test | Expected observation | Result |
| --- | --- | --- |
| Boot, reset, power cycling | NO contacts open; no momentary charge pulse | Pending |
| Start below 100 | Fresh report acknowledged; commanded ON and current flows | Pending |
| Start already at 100 | Never commands ON | Pending |
| Percentage reaches 100 | Commanded OFF; measured charging current stops | Pending |
| Later report below 100 | Remains OFF until a new manually started connection | Pending |
| Manual Stop | OFF, service stopped, wake lock released | Pending |
| Walk out of BLE range / disable Bluetooth | OFF after disconnect detection or report timeout | Pending |
| Kill/force-stop app; suspend reporting | OFF by report timeout plus task latency if no earlier disconnect | Pending |
| Screen locked / Battery Saver / OEM restrictions | Heartbeats remain timely or controller faults OFF | Pending |
| Malformed, stale or wrong-session packet | Authenticated malformed input faults OFF; cannot restart same connection | Pending |
| Invalid pairing PIN or unpaired access | Cannot enable relay | Pending |
| Reset during charging | Output defaults OFF; new manual session required | Pending |
| Actual 5 V load/current and temperature | Within verified component ratings | Pending |
| App energy use over a full session | Measured and recorded; no assumed zero consumption | Pending |

Store serial logs, screenshots, measured voltage/current and wiring photos here
when performed. A software ON/OFF log is not proof of physical relay state.
