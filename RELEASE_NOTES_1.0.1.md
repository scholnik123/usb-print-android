# USB Print 1.0.1

USB Print 1.0.1 publishes the completed post-1.0.0 work from `main` as an updated APK. It expands IPP/PWG printing, physical-result recording, N-up/custom-media support, progress diagnostics, and responsive Android UI behavior.

The application remains local-only: it has no Android `INTERNET` permission, account, analytics, advertising, Firebase, or cloud document upload.

## Upgrade

The APK uses package `ru.usbprint`, `versionName 1.0.1`, and `versionCode 2`. Its signing-certificate SHA-256 is the same as the published 1.0.0 debug APK, so Android can install it as an in-place update over that APK. This remains a debug-signed testing release; a production signing key is not included in the repository.

## Printing and capability updates

- IPP PWG Raster Print-Job for rendered PDF, image, and UTF-8 text when the printer reports Print-Job, `image/pwg-raster`, and compatible raster capabilities.
- One bounded, exact-length PWG spool per IPP job with cleanup after success, error, cancellation, and application restart.
- Exact Content-Length and single-submission behavior; no automatic retry after transmission begins.
- Confirmed IPP custom paper in millimetres or inches, persisted exactly in micrometres and encoded with nested `media-col/media-size` only when the printer reports the required writable members.
- Software 2-up and 4-up for IPP PWG, legacy PWG USB, PostScript Raster, and PCL 5 Raster, including spacing, optional borders, auto-rotation, copies/ranges/order, and duplex-aware physical-sheet planning.
- Honest progress in bytes, rendered pages, or completed physical sheets when a total is known; indeterminate progress otherwise.
- Bounded local per-job timing/byte/page/sheet/raster-buffer metrics and bounded general diagnostics.

## Physical-result workflow

- A hardware-test result wizard records only what the user actually observes on paper.
- Seven result outcomes and eleven issue classifications distinguish correct output, partial output, accepted-without-page, printer error, no action, disconnect, and another described result.
- Versioned local printer profiles preserve up to 20 observations and become `NEEDS_REVALIDATION` after an encoder/schema change.
- Compatibility JSON export includes reviewed technical evidence while excluding identity hashes, raw serial/device keys, notes, document metadata, URI, filename, and payload.
- USB/IPP success and `SENT` never automatically count as a physically verified print.

## Responsive UI update

- Compact below 600 dp, Medium at 600–839 dp, and Expanded from 840 dp.
- One scrollable column on Compact/Medium; two balanced panes on Expanded.
- Expanded returns to one column at font scale 1.5 or greater.
- Edge-to-edge safe-drawing insets, IME padding, dark system-bar resources, and rotation-safe dialog state.
- Bounded `LazyColumn` printer selector for multiple devices and long names.
- Responsive settings flows/fields, bounded aspect-preserving previews/placeholders, minimum-height actions, large-text wrapping, accessible labeled toggles, descriptive delete actions, and full scrollable error messages.
- Compose previews for 320 dp, short landscape, Medium, Expanded, dark theme, and font scale 2.0.

## Documents and backends

Documents:

- Rendered: PDF, JPEG/JPG, PNG, WEBP, BMP, and UTF-8 TXT.
- Raw passthrough: PS, PCL, and PRN for matching reported printer languages.
- Office files and HTML are not rendered; convert them to PDF first.

Implemented backends:

- IPP Direct PDF.
- IPP PWG Raster.
- PDF Direct over legacy USB Printer Class.
- PWG Raster over legacy USB.
- PostScript Raster subset.
- PCL 5 Raster subset.
- ESC/POS subset.
- Raw PostScript/PCL passthrough.

## Verification

| Check | Result |
| --- | --- |
| Clean `testDebugUnitTest` | 134 passed, 0 failed, 0 skipped |
| Clean `lintDebug` | Passed; 0 errors, 10 dependency-version warnings |
| Clean `assembleDebug` | Passed |
| Clean `assembleDebugAndroidTest` | Passed; 7 responsive Compose scenarios compiled |
| APK package | `ru.usbprint` |
| APK version | `versionName 1.0.1`, `versionCode 2` |
| Android SDK range | minSdk 26, targetSdk 35 |
| APK signature | APK Signature Scheme v2; 1 Android Debug signer |
| Connected Android tests | Not run; no device or configured AVD available |
| Screenshots/TalkBack/manual UI matrix | Not run; no device or configured AVD available |
| External CUPS/Ghostscript/ipptool validation | Not run; tools unavailable |
| Hardware-tested printers | 0 |

The internal tests validate project-owned protocol framing, generated structures, lifecycle decisions, layout policy, and invariants. They do not replace an external parser, Android device session, or physical printer result.

## Known limitations

- Printer compatibility depends on the exact firmware protocol/command language; host-based/GDI printers may require proprietary desktop drivers.
- IPP Create-Job + Send-Document is not implemented.
- PCLm, PCL XL/PCL6, URF, and vendor-specific GDI protocols are not implemented.
- IPP PWG, custom media, N-up, UI behavior, and physical output still require device/printer validation.
- No hardware-verified printer model is claimed by this release.
- Connected UI tests, screenshots, TalkBack, real IME/system-bar/cutout behavior, API-26/API-35 runtime checks, rotation, split-screen, and foldable posture remain `NOT RUN`.
- The APK is debug-signed and intended for direct installation/testing, not store distribution.

See `CHANGELOG.md`, `VALIDATION_REPORT.md`, `docs/UI_VALIDATION.md`, `BACKEND_MATRIX.md`, and `COMPATIBILITY.md` for detailed evidence and constraints.

## Download verification

Asset: `USB-Print-1.0.1-debug.apk`

Size: 17,589,392 bytes

SHA-256:

```text
284385bd4bb82d3f6169b6041c739878b12d9b5a5a837d19ccfe9ead49a24a7b  USB-Print-1.0.1-debug.apk
```
