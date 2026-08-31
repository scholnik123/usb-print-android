# USB Print 1.0.0

First public release.

USB Print performs direct, local printing from Android to compatible printers connected through USB Host / OTG. Compatibility depends on the protocol and command language implemented by the exact printer model.

## Highlights

- Direct USB Host / OTG printer discovery and Android USB permission handling
- Multiple connected-printer selection
- Foreground print jobs, cancellation, test-page generation, and redacted diagnostics export
- Capability-driven settings that are constrained by detected printer and backend support
- Local presets and printer-specific experimental overrides

## IPP-over-USB

- Strict IPP-over-USB interface discovery from USB descriptors
- HTTP/1.1 and binary IPP carried directly over USB bulk endpoints
- Get-Printer-Attributes capability discovery
- Direct PDF Print-Job when the printer reports the required operation and format
- Short-lived Get-Job-Attributes polling and Cancel-Job when the printer reports support
- No Wi-Fi path and no Android `INTERNET` permission

This release does not implement IPP PWG Raster or the Create-Job + Send-Document workflow.

## Printing backends

- IPP Direct PDF
- PDF Direct over USB Printer Class
- PWG Raster
- PostScript Raster
- PCL 5 Raster
- ESC/POS
- Raw PostScript, PCL, and PRN passthrough for compatible printer languages

Some non-IPP backends remain experimental until physical printer evidence is collected. A successful USB transfer does not by itself confirm correct paper output.

## Capability-driven settings

The application combines printer-reported capabilities, backend constraints, and explicit user overrides into `EffectivePrintCapabilities`. The settings UI and job validation use that effective set rather than advertising unsupported paper, resolution, color, duplex, media-source, media-type, or output-bin values.

Available controls include copies, page ranges, odd/even selection, reverse order, collation, paper, orientation, confirmed color and duplex modes, resolution, margins, scaling, positioning, and presets. Availability depends on the printer and selected backend.

## Documents

- Rendered: PDF, JPEG/JPG, PNG, WEBP, BMP, and UTF-8 TXT
- Raw passthrough: PS, PCL, and PRN
- Not supported directly: DOC/DOCX, XLS/XLSX, PPT/PPTX, and HTML; convert these documents to PDF first

## Privacy

- No account, analytics, Firebase, advertising, or cloud upload
- No Android `INTERNET` permission
- Documents are processed locally and sent directly to the connected USB printer
- Diagnostic exports apply redaction, but users should still review them before posting publicly

## Requirements

- Android 8.0 / API 26 or newer
- Android USB Host support
- A suitable USB OTG adapter or cable
- External printer power where required
- A printer that implements one of the supported protocols or command languages

## Known limitations

- No printer has been physically hardware-verified for this first public release
- Host-based/GDI printers may require proprietary desktop drivers
- IPP PWG Raster is not implemented
- IPP Create-Job + Send-Document is not implemented
- PCLm, PCL XL/PCL6, URF, and vendor-specific GDI protocols are not implemented
- N-up composition is not implemented
- Full custom-paper UI and job encoding are not complete
- Office formats require conversion to PDF
- Instrumented/connected Android tests were not run because no device or emulator was available
- External validation with CUPS, Ghostscript, or ipptool was not run because those tools were unavailable

See [`COMPATIBILITY.md`](COMPATIBILITY.md), [`BACKEND_MATRIX.md`](BACKEND_MATRIX.md), and [`VALIDATION_REPORT.md`](VALIDATION_REPORT.md) before interpreting compatibility claims.

## Verification

| Check | Result |
|---|---|
| JVM unit/golden/invariant tests | 61 passed, 0 failed, 0 skipped |
| Android Lint | Completed; 0 errors, 8 dependency-version warnings |
| `assembleDebug` | `BUILD SUCCESSFUL` |
| APK package | `ru.usbprint` |
| APK version | `versionName 1.0.0`, `versionCode 1` |
| Android SDK range | minSdk 26, targetSdk 35 |
| APK signature | APK Signature Scheme v2; 1 Android Debug signer |
| Connected tests | Not run; no Android device or emulator available |
| External reference tools | Not run; CUPS, Ghostscript, and ipptool unavailable |
| Hardware-tested printers | 0 |

The internal golden/invariant checks validate generated structures and protocol framing; they are not a substitute for external reference tools or physical printer testing.

## Download

Download `USB-Print-1.0.0-debug.apk` and `SHA256SUMS.txt` from the [USB Print 1.0.0 release](https://github.com/scholnik123/usb-print-android/releases/tag/v1.0.0).

SHA-256:

```text
d8657cd9da12689628eec136d085d8e34bbafc75dd397fdd86679b1ee81f9370  USB-Print-1.0.0-debug.apk
```

This APK is debug-signed and intended for direct installation/testing. A production signing key is not included in the repository.
