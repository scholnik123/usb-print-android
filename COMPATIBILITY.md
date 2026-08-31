# Printer compatibility

USB Print communicates with printers through standards and command languages reported by the device. A brand name or VID/PID alone is not evidence that a printer can interpret a generated print job.

## Evidence levels

| Level | Meaning |
|---|---|
| Detected | Android and USB Print can see a Printer Class or IPP-over-USB interface |
| Capabilities read | IEEE-1284 or IPP attributes were received and parsed |
| Job transferred | The USB connection accepted the job bytes |
| Printed with issues | Paper output was observed but size, orientation, color, content, or page handling was incorrect |
| User-confirmed print | A user visually confirmed the expected physical output for the recorded settings |
| Multiple tests confirmed | Multiple separately recorded settings/jobs were visually confirmed |

Successful USB or IPP transfer does not automatically mean that the physical page was printed correctly. `SENT` is therefore not equivalent to hardware verification.

## Hardware-verified printers

No hardware-verified printers have been recorded yet.

The development IPP PWG backend is covered by deterministic tests but has not been promoted to hardware-verified compatibility. It does not add a printer row without a user-observed physical result.

The development hardware-test wizard records only an explicit physical observation. It offers: printed correctly, printed with issues, accepted with no page, printer error, nothing happened, connection lost, or other. “Printed with issues” can classify crop, paper, orientation, color, grayscale, blank/garbage output, extra pages, scale, margins, and duplex defects. `SENT`, bulk-transfer success, or IPP completed never selects a successful outcome.

Wizard observations are currently session state. Versioned persistent profiles, revalidation rules, and privacy-safe JSON compatibility export are separate upcoming stages, so the table remains empty.

| Manufacturer | Model | VID:PID | Protocol | Reported formats | Backend | Paper | Resolution | Color | Duplex | Result | App version |
|---|---|---|---|---|---|---|---|---|---|---|---|
| — | — | — | — | — | — | — | — | — | — | No verified records | 1.0.0 |

The placeholder above is not a compatibility claim and will be replaced only by evidence-backed reports.

## Reporting a printer result

Use the [printer compatibility issue form](https://github.com/scholnik123/usb-print-android/issues/new?template=printer_compatibility.yml). Include the exact printer model, Android device, VID:PID, detected protocol/backend, reported formats, document type, settings, and observed physical result.

Before posting:

- remove private document names and content;
- remove or redact printer/device serial numbers;
- review exported diagnostics for unique identifiers;
- do not generalize one model result to an entire manufacturer.

Reports that only confirm detection or byte transfer remain at that evidence level. A user-confirmed print requires a visual comparison with the expected output.
