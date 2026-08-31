# Contributing

Thank you for helping improve USB Print. The project values protocol correctness, conservative capability claims, local privacy, and evidence-backed compatibility.

## Development setup

Requirements:

- JDK 17
- Android SDK 35
- Git
- Android Studio is optional

Build with the project wrapper:

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

On Windows, use `gradlew.bat`.

## Pull requests

- Keep changes focused and explain the user-visible or protocol impact.
- Preserve existing backends unless the change explicitly repairs a demonstrated defect.
- Add tests for protocol framing, capability logic, or boundary behavior.
- Update relevant documentation and the backend matrix.
- Do not commit build outputs, private diagnostics, printer serial numbers, documents, signing keys, tokens, or local paths.
- Do not introduce cloud, analytics, advertising, or network permissions without an explicit project decision.

## Printer compatibility reports

Use the printer compatibility issue form. A VID/PID, manufacturer name, successful detection, or successful USB transfer alone is not a compatibility result.

Useful evidence includes:

- exact printer and Android device models;
- USB VID:PID and detected protocol;
- redacted IEEE-1284/IPP diagnostics;
- selected backend, document format, paper, DPI, color, and duplex settings;
- what physically happened and how it differed from the expected page.

Remove private document details and serial numbers before posting. Never generalize one tested model to an entire brand.

## Adding a printing backend

A backend should not enter automatic selection until it has:

1. a reliable printer capability signal;
2. a specification-based encoder or transport;
3. a declared `BackendCapabilityDescriptor` limited to settings it really transmits;
4. bounded input and memory handling;
5. deterministic wire-format or golden tests;
6. documented limitations and validation status.

`FakeUsbTransport` is for tests only. Production code must use the replaceable USB transport boundary and must never label fake transport success as hardware validation.

## Hardware claims

Hardware support claims require evidence. The strongest record is a user-confirmed physical print with exact settings; multiple separately confirmed tests provide stronger confidence. Repository maintainers may keep a report at a lower evidence level if the physical result is unclear.
