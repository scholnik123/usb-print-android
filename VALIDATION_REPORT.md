# USB Print 1.0.0 validation report

Validation date: 2026-08-31.

This report separates deterministic local checks from external reference-tool and physical printer validation. A successful JVM test or USB transfer is not presented as proof of correct paper output.

## IPP wire format

Status: **passed internal JVM tests**.

Covered behavior:

- big-endian version, operation/status, and request ID fields;
- attribute groups, end-of-attributes, repeated values, and zero-name-length continuation;
- integer, boolean, enum, resolution, range, string, URI, MIME, out-of-band, unknown raw value, and nested collection handling;
- bounded message, name, value, attribute count, and collection depth;
- malformed lengths, truncated records, invalid continuation, and unknown value preservation;
- response request-ID matching and unsupported IPP version classification;
- exact job attributes, asymmetric resolution, and raw media keywords.

Physical IPP firmware interoperability: **not run**.

## IPP HTTP framing

Status: **passed internal JVM tests**.

Covered behavior:

- HTTP/1.1 POST framing for `/ipp/print`;
- Host, Content-Type, Accept, Content-Length, and Connection headers;
- partial response reads;
- Content-Length and chunked response bodies;
- HTTP/1.0 rejection and bounded response sizes.

Real IPP-over-USB HTTP capture: **not run**.

## IPP capability mapping

Status: **passed internal JVM tests**.

Tests cover printer-reported media, formats, operations, color, duplex, asymmetric resolution, media source/type/output-bin raw keywords, nested `media-col`, explicit micrometre conversion, and `IPP/CONFIRMED` provenance.

## PWG Raster

Status: **passed internal golden/invariant tests**.

A test-only inspector independently parses complete generated fixtures and verifies `RaS2`, the 1,796-byte page header, big-endian fields, dimensions, DPI, bits per pixel, bytes per line, color space, media keyword, duplex/tumble, row compression, page height, multi-page boundaries, and trailing data.

Fixtures cover A4 300 DPI mono/gray/RGB, A4 600 DPI mono, Letter 300 DPI, landscape, both duplex directions, and a two-page stream.

CUPS/PWG external parser validation: **not run; tool unavailable**.

## PCL 5

Status: **passed internal golden/invariant tests**.

The test-only subset parser verifies reset, media code, orientation, 300/600 DPI, simplex/long-edge/short-edge duplex, raster row command boundaries, raster end, and form feed.

External PCL interpreter and physical printer validation: **not run**.

## PostScript

Status: **passed internal golden/invariant tests**.

Two-page fixtures verify the Adobe header, DSC page comments, PageSize/setpagedevice, colorimage framing, exact ASCIIHex payload, showpage, trailer, page count, and EOF.

Ghostscript syntax/render validation: **not run; tool unavailable**.

## Memory and stress

Status: **passed internal JVM tests**.

Covered behavior includes checked Long arithmetic, A4 600 DPI streaming dimensions, A2 300 DPI, rejection of unsafe A3/A0 600 DPI combinations, invalid dimensions, extremely large image metadata, asymmetric 600×1200 DPI capabilities, and large margins.

Low-RAM physical Android validation: **not run**.

## Automated build verification

| Check | Result |
|---|---|
| `testDebugUnitTest` | 61 tests passed, 0 failed, 0 skipped |
| `lintDebug` | Passed; 0 errors, 8 GradleDependency warnings |
| `assembleDebug` | Passed |
| Connected Android tests | Not run: no device/emulator |
| Hardware printer tests | 0 printers |

The table is updated from the final public-release verification run before tagging.

## External tools

The following tools were unavailable in the release environment and were not used:

- CUPS / `cupsfilter`;
- Ghostscript;
- `ipptool`;
- Android `adb` connected test target.

Internal inspectors are useful deterministic tests, but they do not equal external reference validation.

## Hardware validation

Hardware-tested printers: **0**.

No manufacturer or model is claimed as hardware-compatible in this release. Evidence-backed results can be submitted with the printer compatibility issue form after removing private document information and serial numbers.

## APK verification

The public release asset is `USB-Print-1.0.0-debug.apk` (17,424,924 bytes). It is debug-signed for direct installation and testing; no production signing key is stored in the repository.

Android build tools verified:

- SHA-256 `d8657cd9da12689628eec136d085d8e34bbafc75dd397fdd86679b1ee81f9370`;
- package `ru.usbprint`;
- `versionName 1.0.0` and `versionCode 1`;
- minSdk 26 and targetSdk 35;
- APK Signature Scheme v2 with one Android Debug signer;
- no `INTERNET`, `ACCESS_NETWORK_STATE`, Wi-Fi, Bluetooth, or broad external-storage permission.

The complete release verification summary is recorded in `RELEASE_NOTES_1.0.0.md`, and the distributable checksum is recorded in `SHA256SUMS.txt`.

## Development branch addendum

This section is not part of the public 1.0.0 release verification and does not change the released APK claims.

At commit `18cd740`, the development branch adds IPP PWG Raster Print-Job with a bounded app-cache spool and exact Content-Length. Local verification completed with 79 JVM tests passed, 0 failed, 0 skipped; lint passed with 0 errors and the existing 8 GradleDependency warnings; `assembleDebug` passed. GitHub Android CI run `33409651029` passed tests, lint, assembly, and artifact upload.

The added deterministic coverage includes backend selection, exact MIME and body length, exact PWG producer bytes, multi-page order, software copies/ranges, landscape fixtures, color/grayscale, 600 DPI, cancellation before generation and during upload, spool cleanup, HTTP failure, IPP rejection, and single-exchange/no-retry behavior.

External CUPS/ipptool validation: **not run**. Physical IPP PWG printer validation: **not run**. Hardware-tested printers remain **0**.

The subsequent development hardware-test wizard verification completed with 86 JVM tests passed, 0 failed, 0 skipped; lint passed with 0 errors and the existing 8 GradleDependency warnings; `assembleDebug` passed. Its unit tests cover required outcomes, issue validation, matching-job terminal transitions, cancellation, duplicate terminal state, unrelated jobs, and service-start failure. Compose connected tests remain **not run: no device/emulator**. No observation was created during automated tests and the hardware-tested printer count remains **0**.

The subsequent versioned-profile verification completed with 95 JVM tests passed, 0 failed, 0 skipped; lint passed with 0 errors and the existing 8 GradleDependency warnings; `assembleDebug` passed. Tests cover explicit-only status promotion, mixed histories, bounded history, hashed identity, encoder invalidation, actual encoder-version mapping, codec round trip, and malformed profile rejection. DataStore connected tests remain **not run: no device/emulator**. The hardware-tested printer count remains **0**.
