# Testing

USB Print separates deterministic source-level validation from Android connected tests, external reference-tool checks, and physical printer validation.

## Local release checks

Run with JDK 17 and Android SDK 35:

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

Windows:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

The first public release baseline is 61 JVM tests. The development branch baseline after IPP PWG integration and the hardware-test result wizard is 86 JVM tests. The number is not a target by itself; wire boundaries, protocol selection, cleanup, state transitions, and overflow behavior are the important coverage.

## JVM and unit tests

The unit suite covers:

- IEEE-1284 Device ID/CMD parsing and Printer Class port status;
- backend selection and effective capability intersection;
- settings validation, page ranges, copies, collation, odd/even, and reverse ordering;
- physical page layout, margins, positioning, scaling, and memory policy;
- USB partial-write handling;
- MIME/document classification and DataStore-compatible domain behavior.

## IPP tests

- Binary encoder/decoder values, groups, continuations, and nested collections
- Request-ID matching and status/error classification
- Malformed/truncated messages, bounded lengths, attribute counts, and collection depth
- HTTP/1.1 headers, partial reads, Content-Length, chunked bodies, and response limits
- Protocol-4 USB descriptor discovery with Bulk IN+OUT and equivalent-interface count
- Get-Printer-Attributes requested list
- Capability mapping, media-col, micrometre conversion, raw media keywords, and asymmetric resolution
- Print-Job attributes and document length
- Prevention of legacy raw fallback on an IPP-only interface
- IPP PWG selection only for Print-Job, exact MIME, supported raster type, and supported resolution
- Exact combined HTTP Content-Length and exact IPP-plus-document bytes
- Bounded unique app-cache spool creation, maximum size, close cleanup, and startup cleanup
- IPP PWG MIME, payload bytes, copies/range non-duplication, cancellation before generation and during upload
- Cleanup after HTTP failure and IPP rejection, with one exchange and no retry after transmission begins

## Hardware-test workflow tests

- All seven required user-observed outcomes and eleven printed-page issue classifications
- Invalid observation rejection: issues without “printed with issues”, missing issue classifications, and blank “other” descriptions
- Matching calibration jobs request one observation after `SENT` or `ERROR`
- Ordinary jobs, cancelled calibration jobs, duplicate terminal emissions, and foreground-service start failures do not request an observation

The Compose dialog is not covered by an Android connected test because no emulator/device target is available. This remains `NOT RUN`, not a passed UI test.

## Golden and invariant tests

PWG Raster uses a test-only inspector that parses complete generated streams. Fixtures check the sync word, full header, line encoding, page height, media, orientation, 300/600 DPI, color/grayscale/mono, duplex, and multi-page boundaries. Producer tests compare exact complete bytes, preserve multi-page order, and verify cancellation before the first byte. Job-planner tests verify software page range and copies without IPP duplication.

PCL 5 tests parse the exact emitted subset: reset, media, orientation, resolution, duplex, row payload boundaries, raster end, and form feed.

PostScript tests inspect complete two-page framing, DSC data, PageSize, image operator, ASCIIHex payload, showpage, trailer, and EOF.

These inspectors are intentionally separate from production parsing. They are still project-owned tests and do **not** equal external validation through CUPS, Ghostscript, ipptool, or a printer firmware implementation.

## Memory and stress tests

Tests cover checked arithmetic, allowed and rejected paper/DPI combinations, invalid dimensions, large source metadata, asymmetric resolutions, and extreme margins. These do not replace low-RAM device measurements.

## Instrumented tests

No Android instrumented test suite is currently included. `connectedDebugAndroidTest` was not run for the first release because no device or emulator was available. This is a known gap, not a unit-test success.

Future connected coverage should include Activity startup/recreation, settings restoration, SAF export, foreground service lifecycle, and notification cancellation.

## Hardware tests

Hardware-tested printers for 1.0.0 and the current development branch: **0**.

Do not treat any of the following as a visually confirmed print:

- USB permission granted;
- printer detected;
- capabilities parsed;
- bulk transfer returned success;
- IPP returned a job ID;
- application status changed to `SENT`.

A hardware result requires the exact printer model, Android/OTG environment, backend, settings, document type, and observed physical page result. Use the printer compatibility issue form and remove private data.

## External reference validation

Not run for 1.0.0 or the current development IPP PWG path. CUPS/cupsfilter, Ghostscript, and ipptool were unavailable in the development environment. No external validation badge or claim is made.

## CI

`.github/workflows/android.yml` runs the JVM tests, Android lint, debug build, and debug APK artifact upload on pushes and pull requests using JDK 17 and the project Gradle wrapper. It contains no signing key or production secret.

See [VALIDATION_REPORT.md](../VALIDATION_REPORT.md) for the recorded release results.
