# USB Print 1.0.2

USB Print 1.0.2 is a real-device Android UI validation patch for the 1.0.x release line. It fixes the diagnostics dialog discovered during testing on a physical Nothing A059 running Android 16 and updates the responsive instrumentation coverage.

The application remains local-only: it has no Android `INTERNET` permission, account, analytics, advertising, Firebase, or cloud document upload.

## Upgrade

The APK uses package `ru.usbprint`, `versionName 1.0.2`, and `versionCode 3`. It is signed with the same Android debug certificate as the 1.0.0 and 1.0.1 release APKs, so Android can install it as an in-place update. This remains a debug-signed testing release; a production signing key is not included in the repository.

## Fixed

- Copy, TXT export, JSON export, and Close remain visible and reachable in the diagnostics dialog instead of overflowing the Material dialog action area.
- The diagnostics content remains bounded and scrollable while Close stays in the fixed confirmation area.
- The 100 KB diagnostics regression scenario now requires every action to be displayed.
- The 20-printer scenario scrolls the virtualized list by index before selecting the final item.
- Expanded large-text coverage uses actual layout coordinates and verifies that lower controls can be scrolled into view.

## Real-device validation

Device: Nothing A059, Android 16, API 36, 1080 x 2392, density 420.

Actually checked on the device:

- cold startup and printer-empty state;
- document picker and generated A4 test-page preview;
- settings, advanced settings, diagnostics, long content, and scrolling;
- light and dark mode;
- portrait and landscape;
- Activity recreation with state preservation;
- font scale 1.0, 1.5, and 2.0;
- IME and system-bar insets;
- seven connected Compose instrumentation scenarios.

Not run because no USB Host printer was connected:

- physical printing and physical test-page evaluation;
- active print foreground service and posted job notification;
- notification Cancel action.

No app-process FATAL EXCEPTION, ANR, IllegalStateException, OutOfMemoryError, Compose layout exception, or foreground-service exception was found in the final runtime and instrumentation logs.

## Verification

| Check | Result |
| --- | --- |
| `testDebugUnitTest` | 134 passed, 0 failed, 0 skipped |
| `lintDebug` | Passed; 0 errors, 10 dependency-version warnings |
| `assembleDebug` | Passed |
| `connectedDebugAndroidTest` | 7 passed, 0 failed, 0 skipped on Nothing A059 / Android 16 / API 36 |
| APK package | `ru.usbprint` |
| APK version | `versionName 1.0.2`, `versionCode 3` |
| Android SDK range | minSdk 26, targetSdk 35 |
| Hardware-tested printers | 0 |

## Known limitations

- Printer compatibility depends on the exact firmware protocol and command language; host-based/GDI printers may require proprietary desktop drivers.
- IPP Create-Job + Send-Document is not implemented.
- PCLm, PCL XL/PCL6, URF, and vendor-specific GDI protocols are not implemented.
- No physical printer model is claimed as verified by this release.
- TalkBack, split-screen, foldable posture, API 26 runtime behavior, and physical printing remain unverified.
- The APK is debug-signed and intended for direct installation/testing, not store distribution.

## Download verification

Asset: `USB-Print-1.0.2-debug.apk`

Size: 17,589,388 bytes

SHA-256:

```text
3465f415195e216895eeefa2eed46e113bb2384df597df80ac349a8762ade557  USB-Print-1.0.2-debug.apk
```
