# Changelog

All notable public changes are documented here. This project begins its public semantic-version history with 1.0.0.

## Unreleased

Development changes below are not part of the published 1.0.0 APK.

### Added

- IPP PWG Raster Print-Job for PDF, image, and UTF-8 text when the printer reports Print-Job, `image/pwg-raster`, and a raster type/resolution emitted by the local encoder
- Reusable PWG stream producer shared by legacy USB PWG and IPP PWG backends
- Unique, 512 MiB-bounded PWG spool files under application cache, with cleanup after success/error/cancellation and abandoned-file cleanup at startup
- Exact HTTP Content-Length coverage for combined IPP and PWG document bytes
- Tests for IPP PWG backend selection, exact MIME/payload/length, multi-page order, copies, ranges, landscape, color/grayscale, 600 DPI, cancellation, cleanup, HTTP errors, IPP rejection, and single-submission behavior
- Post-print hardware-test result wizard with seven physical outcomes, eleven issue classifications, optional privacy warning/notes, and a deferred “evaluate later” action
- Test-job tracking that opens the wizard only for the matching calibration job after `SENT` or `ERROR`, never for ordinary, cancelled, or failed-to-start jobs
- Versioned local `VerifiedPrinterProfile` persistence with app/encoder versions, hashed device identity, reported protocols, exact tested settings, result date, and bounded 20-record history
- Conservative statuses: `UNTESTED`, `USER_CONFIRMED`, `MULTIPLE_TESTS_CONFIRMED`, `PARTIAL`, `FAILED`, and `NEEDS_REVALIDATION`
- Automatic profile invalidation when a recorded backend encoder version or profile schema no longer matches, without deleting prior observations
- User-triggered privacy-safe compatibility JSON export with app/Android version, printer model and VID/PID, backend/encoder, reported protocols, tested settings, and physical result

### Changed

- Software copies, page selection, paper, orientation, margins, scaling, positioning, color, and resolution are encoded in PWG output; copies and page ranges are not duplicated as IPP job attributes
- Confirmed media source, media type, and output-bin keywords may pass through as IPP job attributes
- IPP PWG rejects unimplemented raster encodings such as `srgb_16` instead of treating them as compatible

### Validation

- 97 JVM tests pass on the development branch; Android lint and debug assembly pass
- Hardware-tested printers remain 0; no physical IPP PWG compatibility is claimed

## 1.0.0 - 2026-08-31

First public release.

### Added

- Direct local Android USB Host / OTG printing
- USB Printer Class discovery, permission handling, IEEE-1284 Device ID, and port status
- Multiple connected printer selection and foreground print jobs
- Capability-driven settings through `EffectivePrintCapabilities`
- IPP-over-USB discovery and bounded HTTP/1.1 transport over USB bulk endpoints
- IPP Get-Printer-Attributes, direct PDF Print-Job, short-lived job status polling, and conditional Cancel-Job
- IPP media-col, media source/type/output-bin, and asymmetric resolution mapping
- PDF Direct, PWG Raster, PostScript Raster, PCL 5 Raster, ESC/POS, and raw PS/PCL passthrough backends
- PDF, image, and UTF-8 text rendering with page planning and memory limits
- Local presets, printer-specific experimental overrides, calibration-page generation, and redacted TXT/JSON diagnostics
- IPP wire/HTTP/capability tests, protocol golden/invariant tests, and raster stress tests

### Known limitations

- No hardware-verified printers are recorded yet.
- IPP PWG and Create-Job + Send-Document are not implemented.
- PCLm, PCL XL/PCL6, URF, N-up, and vendor-specific GDI protocols are not implemented.
- Custom paper ranges can be discovered through IPP, but custom-size UI and job encoding are incomplete.
- The distributed APK is debug-signed for direct installation and testing.
