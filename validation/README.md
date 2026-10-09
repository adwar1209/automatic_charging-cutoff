# Validation Evidence

**Status:** No tests have been run or results recorded in this repository.
The table below is a plan, not evidence of a passing design.

## Initial checks

| Planned check | What to observe | Status |
| --- | --- | --- |
| Startup / reset | Charging remains inhibited until required inputs and configuration are valid. | Not run |
| Normal charging | Charging is permitted under the selected valid operating conditions. | Not run |
| Completion cutoff | The defined completion criterion triggers inhibition; charging current is checked. | Not run |
| Limit boundaries / noisy input | Behavior at either side of each chosen limit matches the defined timing and hysteresis policy. | Not run |
| Missing or invalid required measurement | The design requests charging inhibition. | Not run |
| Defined fault conditions | Each selected protection responds according to its documented limit and mechanism. | Not run |
| Restart after cutoff | Re-enabling follows the documented manual or automatic restart policy. | Not run |

Select safe test methods and expected outcomes after the battery, charger, and
circuit are defined. Only test protections that have an explicit design.

## Recording evidence

For each future result, record:

- **Mode:** simulation or physical hardware; never present one as the other.
- **Revision:** source commit and circuit/firmware revisions.
- **Setup:** simulator/version or hardware/instruments, wiring, and configuration.
- **Procedure:** inputs, limits, steps, and expected behavior.
- **Observation:** actual behavior, measured values, and pass/fail/not-run outcome.
- **Evidence:** linked logs, traces, screenshots, or measurement files, plus limitations.

Store simulation evidence under `validation/simulation/` and physical
measurements under `validation/hardware/` when those artifacts exist. These
subfolders are not populated by this foundation. Simulation alone does not
establish physical protection performance.
