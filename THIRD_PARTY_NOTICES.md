# Third-party notices

This document identifies direct build and runtime dependencies declared by USB Print 1.0.0. Transitive dependency versions are resolved by Gradle and the AndroidX Compose BOM.

| Component | Declared version | License | Purpose |
|---|---:|---|---|
| Android Gradle Plugin | 8.7.3 | Apache-2.0 | Android build tooling |
| Gradle wrapper | 8.9 | Apache-2.0 | Reproducible build bootstrap |
| Kotlin and Kotlin test | 2.0.21 | Apache-2.0 | Application language and test assertions |
| AndroidX Compose BOM | 2024.09.03 | Apache-2.0 | Compose dependency alignment |
| AndroidX Core KTX | 1.13.1 | Apache-2.0 | Android platform extensions |
| AndroidX Lifecycle | 2.8.6 | Apache-2.0 | Runtime, ViewModel, and Compose lifecycle integration |
| AndroidX Activity Compose | 1.9.3 | Apache-2.0 | Compose activity integration |
| AndroidX DocumentFile | 1.0.1 | Apache-2.0 | Storage Access Framework document access |
| AndroidX ExifInterface | 1.3.7 | Apache-2.0 | Image orientation metadata |
| AndroidX DataStore Preferences | 1.1.1 | Apache-2.0 | Local application preferences |
| JUnit 4 | 4.13.2 | EPL-1.0 | Local unit tests |

Compose UI, Material 3, Material Icons Extended, UI Tooling, and UI Test Manifest are version-aligned by the Compose BOM above.

No native driver stacks, CUPS, Gutenprint, libusb, analytics SDKs, cloud SDKs, or advertising SDKs are bundled. CUPS/Gutenprint require native ports, driver/filter chains, and often a daemon-like runtime that cannot be responsibly claimed as working in this rootless Android APK without hardware validation.
