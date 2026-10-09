# Hardware wiring scope

Functional guidance, **not a tested schematic or PCB layout**. Record the exact
relay, connectors, ESP32 board and adapter before verifying a pin-level circuit.
Use a regulated low-voltage 5 V path; do not switch the adapter's mains input.

| Connection | Intended wiring |
| --- | --- |
| Adapter +5 V charging branch | Relay COM |
| Relay NO | Phone connector VBUS/+5 V |
| Relay NC | Unused |
| Adapter ground | Phone ground; ESP32/relay control ground as required by the module |
| Adapter unswitched branch | Appropriate ESP32 board power input and relay supply |
| Configured ESP32 GPIO | Relay IN through a verified 3.3 V compatible interface |
| Optional INA219 | Not required; no sensing/protection implementation included |

Charging current flows through relay contacts, never through an ESP32 GPIO or
relay IN pin. Use appropriately rated contacts, wires, connectors, enclosure and
a suitable fuse/current-limited supply. The adapter must support the phone,
ESP32 and relay coil together.

A **5 V relay supply rating does not establish 3.3 V input compatibility**.
Check the module's input circuit; add a suitable transistor/level interface if
needed. Never connect 5 V to an ESP32 GPIO. Provide external bias so reset or loss
of MCU power keeps the relay off. An active-low input generally needs an
appropriately interfaced pull-up; never pull the ESP32 pin directly to 5 V.
Confirm that de-energized NO contacts open the charging path.

Preserve required USB ground, data and configuration wiring. USB-C CC wiring
and charging negotiation depend on the connectors; cutting VBUS does not create
a standards-compliant USB-C charger. This source implements no USB PD or
higher-voltage fast charging. Use and document a verified 5 V charging interface.

## Before connecting a phone

1. Verify GPIO/polarity with a low-voltage dummy load. GPIO defaults to -1 (disabled).
2. Measure OFF at power-up/reset, manual stop, disconnect and reporting timeout.
3. Confirm the ESP32 stays powered when NO opens and no USB path back-powers it.
4. Measure phone input voltage/current during ON and after cutoff. A relay log
   is not proof that charging current stopped.

Add the actual schematic, module part numbers, wiring photos and measurements
here once available. No completed circuit is claimed.
