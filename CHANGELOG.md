# Changelog

All notable public changes are documented here. This project begins its public semantic-version history with 1.0.0.

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
