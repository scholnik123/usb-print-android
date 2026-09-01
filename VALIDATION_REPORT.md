# USB Print 1.0.1 validation report

Validation date: 2026-09-01.

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
| `testDebugUnitTest` | 134 tests passed, 0 failed, 0 skipped |
| `lintDebug` | Passed; 0 errors, 10 GradleDependency warnings |
| `assembleDebug` | Passed |
| `assembleDebugAndroidTest` | Passed; 7 Compose test scenarios compiled into the test APK |
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

## Responsive UI

Status: **passed source/JVM/build verification; connected execution not run**.

The current UI uses Compact/Medium/Expanded width classes, scroll-safe single-column layouts, an Expanded two-pane layout, safe-drawing/IME insets, bounded previews and printer lists, large-text reflow, dark system-bar resources, and accessible labeled toggles. Width boundaries have deterministic JVM coverage and seven Compose instrumentation scenarios compile successfully. No device or AVD was available, so screenshots, TalkBack, real IME/system-bar behavior, rotation, foldable posture, and connected test execution remain `NOT RUN`. See `docs/UI_VALIDATION.md` for the exact matrix and remaining manual checklist.

## APK verification

The public release asset is `USB-Print-1.0.1-debug.apk` (17,589,392 bytes). It is debug-signed for direct installation and testing; no production signing key is stored in the repository.

Android build tools verified:

- SHA-256 `284385bd4bb82d3f6169b6041c739878b12d9b5a5a837d19ccfe9ead49a24a7b`;
- package `ru.usbprint`;
- `versionName 1.0.1` and `versionCode 2`;
- minSdk 26 and targetSdk 35;
- APK Signature Scheme v2 with one Android Debug signer;
- no `INTERNET`, `ACCESS_NETWORK_STATE`, Wi-Fi, Bluetooth, or broad external-storage permission.

The complete release verification summary is recorded in `RELEASE_NOTES_1.0.1.md`, and the distributable checksum is recorded in `SHA256SUMS.txt`.

## Pre-1.0.1 development validation history

The following entries record the incremental checks that preceded the combined 1.0.1 release. The final release results above supersede their intermediate test counts.

At commit `18cd740`, the development branch adds IPP PWG Raster Print-Job with a bounded app-cache spool and exact Content-Length. Local verification completed with 79 JVM tests passed, 0 failed, 0 skipped; lint passed with 0 errors and the existing 8 GradleDependency warnings; `assembleDebug` passed. GitHub Android CI run `33409651029` passed tests, lint, assembly, and artifact upload.

The added deterministic coverage includes backend selection, exact MIME and body length, exact PWG producer bytes, multi-page order, software copies/ranges, landscape fixtures, color/grayscale, 600 DPI, cancellation before generation and during upload, spool cleanup, HTTP failure, IPP rejection, and single-exchange/no-retry behavior.

External CUPS/ipptool validation: **not run**. Physical IPP PWG printer validation: **not run**. Hardware-tested printers remain **0**.

The subsequent development hardware-test wizard verification completed with 86 JVM tests passed, 0 failed, 0 skipped; lint passed with 0 errors and the existing 8 GradleDependency warnings; `assembleDebug` passed. Its unit tests cover required outcomes, issue validation, matching-job terminal transitions, cancellation, duplicate terminal state, unrelated jobs, and service-start failure. Compose connected tests remain **not run: no device/emulator**. No observation was created during automated tests and the hardware-tested printer count remains **0**.

The subsequent versioned-profile verification completed with 95 JVM tests passed, 0 failed, 0 skipped; lint passed with 0 errors and the existing 8 GradleDependency warnings; `assembleDebug` passed. Tests cover explicit-only status promotion, mixed histories, bounded history, hashed identity, encoder invalidation, actual encoder-version mapping, codec round trip, and malformed profile rejection. DataStore connected tests remain **not run: no device/emulator**. The hardware-tested printer count remains **0**.

The subsequent compatibility-export verification completed with 97 JVM tests passed, 0 failed, 0 skipped; lint passed with 0 errors and the existing 8 GradleDependency warnings; `assembleDebug` passed. Tests cover required JSON facts, escaping, and absence of the actual stored identity hash, raw serial/device key, notes, document filename/content URI, and payload fields. SAF CreateDocument interaction remains **not run: no device/emulator**. No record was uploaded automatically and the hardware-tested printer count remains **0**.

The subsequent software N-up verification completed with 112 JVM tests passed, 0 failed, 0 skipped; lint passed with 0 errors and the existing 8 GradleDependency warnings; `assembleDebug` passed. Tests cover 2-up/4-up sheet counts, odd pages, range/reverse ordering, collated and uncollated copies, portrait/landscape grids, spacing/border/rotation settings, long-edge/short-edge duplex planning, backend selection, and preset backward compatibility. The Compose settings flow, Canvas preview, Android bitmap compositor, and physical output remain **not run: no device/emulator or printer**. Hardware-tested printers remain **0**.

The subsequent confirmed custom-paper verification completed with 127 JVM tests passed, 0 failed, 0 skipped; lint passed with 0 errors and the existing 8 GradleDependency warnings; `assembleDebug` passed. Tests cover millimetre/inch conversion to microns, confirmed-range and writable-member gating, min/max and unknown-range rejection, hardware margins and orientation, checked raster dimensions/memory budget, exact PWG numeric page size without an invented standard keyword, IPP nested `media-col/media-size`, exclusion of unreported collection members, IPP PWG passthrough, preset/profile persistence, and privacy-safe schema-2 export. The Compose custom-paper dialog, Android device lifecycle, external IPP/CUPS tools, and physical output remain **not run: no device/emulator or printer**. Hardware-tested printers remain **0**.

The subsequent local progress/metrics verification completed with 133 JVM tests passed, 0 failed, 0 skipped; lint passed with 0 errors and the existing 8 GradleDependency warnings; `assembleDebug` passed. Tests cover byte/page/sheet progress and indeterminate unknown totals, phase timing and counters, peak raster-buffer tracking, bounded 20-job history, completed and failed USB-write accounting, bounded general logs, exact IPP spool length handoff, and PWG producer integration. Android notification/UI lifecycle, low-RAM profiling, external IPP/CUPS tools, and physical output remain **not run: no device/emulator or printer**. Metrics are local, process-scoped, and contain no document payload or URI. Hardware-tested printers remain **0**.
