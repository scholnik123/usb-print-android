# UI validation report

Date: 2026-09-01  
Branch: `main`  
Scope: responsive/adaptive Compose UI stability pass for USB Print 1.0.0  
Result vocabulary: `PASS` means that the named automated check actually ran successfully; `NOT RUN` means that the environment did not permit the check. A compiled preview or test is not reported as a rendered or executed device check.

## Summary

The application UI no longer assumes one fixed phone width. The main screen, printer selection, document preview, print settings, hardware-test result, diagnostics, and error presentation were audited and made scroll-safe. Layout classification is based on the current Compose window constraints, so it is recalculated during rotation, split-screen resizing, or a foldable window-size change rather than inferred from a device name.

The production changes are intentionally confined to presentation, activity lifecycle handling, theme resources, and test code. USB discovery, transport, IPP, encoders, print validation, printer capability decisions, and print-job execution protocols were not changed by this pass.

Device rendering, screenshot capture, TalkBack review, real IME interaction, and connected instrumentation execution are `NOT RUN`: `adb devices -l` returned no devices and `emulator -list-avds` returned no configured AVDs. No screenshot files were created, and this report does not claim visual validation that did not happen.

## Adaptive layout contract

| Window class | Available width | Main layout |
| --- | ---: | --- |
| Compact | below 600 dp | One scrollable column, 12 dp horizontal padding, printer action stacked where needed |
| Medium | 600–839 dp | One centered scrollable column, 24 dp horizontal padding |
| Expanded | 840 dp and above | Centered content limited to 840 dp, two balanced panes with 20 dp gutter |

At font scale 1.5 or greater, Expanded also uses one column. This prevents two narrow text columns from becoming less usable than the phone layout. Settings fields that would normally share a row are stacked when their container is below 360 dp or font scale is at least 1.5.

The layout uses actual `BoxWithConstraints` values. Consequently, the same physical device can move between Compact, Medium, and Expanded while its app window is resized. There is no statement that every possible resolution is visually certified.

## Screens and surfaces audited

### Main application surface

- Top app bar and diagnostics action.
- USB states: checking, host unsupported, no printer, permission required, connecting, ready, and error.
- Zero/one/many printer presentation and the selected-printer summary.
- Document empty, loading, selected, preview-present, and preview-unavailable states.
- Backend availability/reason text.
- Print disabled, active progress, cancellation, sent, and last-status presentation.
- Hardware-test follow-up and compatibility-profile export actions.

### Dialogs

- Printer selector.
- Print settings, including long capability sets, custom media dimensions, margins, N-up, presets, and experimental overrides.
- Hardware-test result wizard.
- Diagnostics/export dialog.
- Full error dialog.

No modal bottom sheet or custom dropdown exists in the current UI. Android's system document picker is external to the application; only its launcher action was in scope.

## Problems found and fixed

| Finding | Resolution |
| --- | --- |
| Main content used a single unconstrained-width column with fixed side padding. | Added one window-width policy, centered readable width, compact padding, and live constraint reclassification. |
| Content could conflict with edge-to-edge system bars or the IME. | Enabled edge-to-edge; `Scaffold` consumes safe-drawing insets and the content applies consumed insets plus IME padding. |
| Compact printer name and action competed for the same horizontal row. | Compact and narrow two-pane printer cards stack the action under the summary; long names use bounded lines and ellipsis. |
| Multiple printers expanded inline and could make the main screen excessively tall. | Replaced the inline list with a bounded, keyed `LazyColumn` selector dialog. |
| Choice chips and paired numeric fields had insufficient wrapping/spacing rules. | Added shared responsive FlowRow spacing and adaptive one/two-column field sizing. |
| Settings margins depended on `fillMaxWidth(0.47f)`. | Fields now use measured available width and stack for narrow/large-text configurations. |
| Main preview used a fixed 220 dp height. | Preview now preserves bitmap aspect ratio, is centered and width-bounded; unavailable preview uses a bounded placeholder. |
| Document name was limited to one line. | It can occupy two bounded lines with ellipsis. |
| Print action used a fixed height. | It now has only a 56 dp minimum and may grow for wrapped large text. |
| Checkbox/switch text was not one coherent touch target. | Each labeled control uses a full-width, minimum-48 dp toggleable row with Checkbox/Switch role semantics. |
| Custom preset delete icon had no accessible label. | Added a preset-specific content description. |
| Long errors were transient snackbar content. | Errors now use a scrollable dialog that preserves the complete message until dismissal. |
| Initial share/view intent could be processed again after Activity recreation. | Initial intent and notification request run only on first creation; later documents still use `onNewIntent`. |
| Dialog and hardware-test draft state could be lost during recreation. | Visibility and draft scalar state use `rememberSaveable`; domain/print state remains ViewModel/application-owned. |
| Platform theme was light-only. | Added night and API-27-specific system-bar resources with correct light/dark icon flags. |
| Wide tablet content was merely stretched. | Expanded main content separates source/document from print actions; large text deliberately collapses it back to one column. |
| No deterministic responsive fixtures existed. | Added width-policy unit tests, six Compose previews, and seven Compose instrumentation scenarios. |

## Automated evidence

| Check | Result | Evidence |
| --- | --- | --- |
| `testDebugUnitTest` | PASS | 134 tests, 0 failures, 0 errors, 0 skipped |
| Responsive width classification | PASS | JVM coverage at 280, 320, 360, 400, 480, 599, 600, 720, 839, 840, 1024, and 1280 dp |
| `lintDebug` | PASS | 0 errors; 10 dependency-update warnings only |
| `assembleDebug` | PASS | Debug APK assembled |
| `compileDebugAndroidTestKotlin` | PASS | Seven Compose responsive test cases compile |
| `connectedDebugAndroidTest` | NOT RUN | No connected device and no configured AVD |
| Screenshot/golden tests | NOT RUN | No screenshot infrastructure or runnable emulator |
| Compose Preview rendering | NOT RUN | Preview functions compile, but no Android Studio visual render was inspected in this environment |

The ten lint warnings are all `GradleDependency` notices for newer versions of existing AndroidX libraries (eight production dependencies and two test-only dependencies). They are not UI correctness or accessibility findings and were not mixed into this UI-only pass.

## Responsive test fixtures added

The production screen is exposed as a pure internal composable so tests do not need a real USB controller or printer. The instrumentation suite covers:

1. A 320 × 640 dp surface with a long printer name, a long document name, a bounded missing-preview state, scrolling to settings, and an actionable print button.
2. A 360 × 640 dp surface at font scale 2.0 with lower document and print actions reached through scrolling.
3. A 1024 × 720 dp surface whose source and actions occupy separate panes.
4. A 1024 × 720 dp surface at font scale 2.0 that reflows back to one column.
5. A bounded selector with 20 long printer names and selection of the last entry.
6. A 320 dp/font-scale-2.0 settings fixture with 15 paper sizes, 10 resolutions, 8 media-type keywords, 6 tray keywords, 4 output-bin keywords, color, duplex, margins, scaling, positioning, collation, page order, and N-up.
7. A diagnostics dialog with approximately 100 KB in one scrollable text surface rather than thousands of individual composables.

These seven tests are compiled but not reported as executed because no device target is available.

## Configuration matrix

| Configuration | Deterministic/compile evidence | Real device or emulator result |
| --- | --- | --- |
| 280 dp stress width | Width policy unit test PASS | NOT RUN |
| 320 dp Compact portrait | Width test PASS; Preview/test fixture compile PASS | NOT RUN |
| 360 dp Compact portrait | Width test PASS; dark/large-text previews and test fixture compile PASS | NOT RUN |
| 400 dp Compact portrait | Width policy unit test PASS | NOT RUN |
| 480 dp Compact/large phone | Width policy unit test PASS | NOT RUN |
| 600 dp Medium boundary | Width policy unit test PASS | NOT RUN |
| 640 × 360 dp short landscape | Landscape Preview compile PASS | NOT RUN |
| 720 dp Medium | Width test PASS; Preview compile PASS | NOT RUN |
| 839/840 dp class boundary | Width policy unit test PASS | NOT RUN |
| 1024 × 720 dp Expanded | Width test PASS; two-pane Preview/test compile PASS | NOT RUN |
| 1280 dp Expanded | Width policy unit test PASS; content max-width rule applies | NOT RUN |
| Split-screen live resize | Constraint-driven implementation inspected | NOT RUN |
| Foldable/resizable window | Constraint-driven implementation inspected; no hinge-specific layout | NOT RUN |
| Font scale 1.0 | Default Preview/test compile PASS | NOT RUN |
| Font scale 1.5 | Stack threshold implemented and unit-independent | NOT RUN |
| Font scale 2.0 | Preview and two responsive test fixtures compile PASS | NOT RUN |
| Light theme | Theme source and Preview compile PASS | NOT RUN |
| Dark theme | Night resources and dark Preview compile PASS | NOT RUN |
| Edge-to-edge/system bars/cutout | Insets implementation and lint PASS | NOT RUN |
| IME with numeric/text fields | IME padding and keyboard types compile/lint PASS | NOT RUN |
| Activity rotation | Ownership and recreation guards reviewed | NOT RUN |
| TalkBack traversal/announcements | Roles, labels, and content descriptions reviewed | NOT RUN |
| API 26 | minSdk build/lint PASS | NOT RUN |
| API 35 | compileSdk/targetSdk build PASS | NOT RUN |

## Rotation and state ownership

- `MainViewModel` owns USB, document, preview, settings, backend decision, job status/progress, hardware observations, and compatibility profile state. Recreating `MainActivity` does not construct a new ViewModel for the same owner.
- The application container owns the printer controller and print executor; a running job is not Activity-owned.
- Settings/diagnostics visibility, error presentation, hardware-test outcome, selected issues, and notes use saveable scalar state.
- Initial ACTION_SEND/ACTION_VIEW ingestion is guarded by `savedInstanceState == null`, avoiding a duplicate document reload on rotation. `onNewIntent` remains active for a genuinely new incoming document.
- Actual rotation while idle, selecting a document, printing, and editing every dialog remains `NOT RUN` without a device.

## Accessibility status

Code-level improvements include minimum-height primary actions, wrapping button labels, two-line bounded names, complete error text, descriptive delete semantics, coherent labeled toggles with roles, and content descriptions for meaningful toolbar/preview actions. Decorative printer/document/print icons remain intentionally unlabeled where adjacent text already supplies the accessible name.

TalkBack focus order, spoken announcements, switch/checkbox interaction, and external keyboard/D-pad navigation require a real accessibility session and are `NOT RUN`. No WCAG/TalkBack certification is claimed.

## Manual device checklist still required

Run this checklist before calling a specific device family visually certified:

1. Test API 26 and a current API matching the target SDK, in light and dark theme.
2. Exercise 320/360/400/480 dp Compact widths, 600/720 dp Medium, and 840/1024+ dp Expanded.
3. Rotate portrait ↔ landscape while idle, after document selection, while settings and each dialog are open, and during an active/cancelling print job.
4. Resize through split-screen boundaries and, on a foldable, through folded/unfolded postures; confirm there is no hinge occlusion.
5. Repeat primary flows at font scales 1.0, 1.5, and 2.0 with a long printer name, long document name, long backend reason, long job status, and long error.
6. Open the keyboard for preset name, page range, custom paper, scale, margins, spacing, and notes; verify the focused field and confirm/dismiss actions remain reachable.
7. Verify no printer, permission required/denied, one printer, 2/5/20 printers, disconnect, unsupported backend, loading document, missing preview, printing, cancelling, waiting status, sent, and error states.
8. Use TalkBack to traverse the top action, printer actions, document actions, settings toggles/chips/fields, print/cancel, dialog actions, preset deletion, and error dismissal.
9. Check status/navigation bar contrast with gesture and three-button navigation, display cutouts, and landscape bars.
10. Capture a small representative screenshot set only after visual inspection. Suggested names: `compact-320-light.png`, `compact-360-font-2.png`, `landscape-640x360.png`, `expanded-1024-dark.png`, and `settings-capability-stress.png` under `docs/ui-validation/`.

## Not implemented or not completed in this environment

- No emulator/AVD was created, because none existed in the supplied environment and creating/downloading device images is infrastructure work outside this UI patch.
- No physical Android device was connected.
- `connectedDebugAndroidTest` was not executed; the seven tests are compile-verified only.
- No screenshots or golden baselines were generated, so `docs/ui-validation/` was not created.
- No manual portrait, landscape, tablet, foldable, split-screen, IME, system-bar, cutout, TalkBack, API-26, or API-35 visual session was possible.
- Fold posture is handled by available-width reflow only; there is no Jetpack WindowManager hinge-aware pane separation. Add that only if testing finds an occluding hinge that width classes cannot handle.
- There is no screenshot regression framework. A later task may add Roborazzi/Paparazzi/Compose screenshot tooling after choosing a stable CI rendering environment.
- Actual USB permission dialogs, system document picker rendering, printer hardware behavior, physical paper output, and printer-specific firmware UI effects cannot be simulated by the pure Compose fixtures.
- Dependency upgrades reported by lint were deliberately deferred to avoid expanding a UI-only change into a dependency migration.

## Commits and CI checkpoints

| Commit | Stage | CI |
| --- | --- | --- |
| `24c252c` `fix(ui): make core screens responsive` | Adaptive containers/insets | GitHub Actions PASS, run 33489039186 |
| `4ba18f6` `fix(ui): adapt print settings to compact screens` | Settings/dialog responsiveness | GitHub Actions PASS, run 33489703545 |
| `e8b96cf` `fix(ui): support large text and accessible controls` | Large text/accessibility/state | GitHub Actions PASS, run 33493224765 |
| `e371fb3` `feat(ui): add adaptive tablet layouts` | Expanded layout/previews | GitHub Actions PASS, run 33494014585 |
| `test(ui): add responsive layout coverage` | Instrumentation fixtures and this report | Commit hash and CI run are reported in the final task handoff |

All completed stage commits above were pushed directly to `origin/main`. The final test/docs stage must also pass `testDebugUnitTest`, `lintDebug`, `assembleDebug`, Android-test compilation, push, and GitHub Actions before the UI pass is considered complete.
