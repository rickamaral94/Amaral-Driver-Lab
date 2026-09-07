# Changelog

Schema changes are called out explicitly. `schemaVersion` is an integer that rises
only when a reader written for the old shape would get the wrong answer from the
new one — not when a field is added.

## 1.0.0-alpha01 — unreleased

Rebuilt from scratch. The previous Java/XML application (0.13.0-alpha12) is not
carried forward; it remains in git history and on `main`.

### Schema

- **`schemaVersion` 1.** First version of the new format. Not compatible with the
  previous app's `schema_version = 13`, which described a different set of
  workloads and a different comparison model. There is no migration: a v13 payload
  describes measurements this app does not produce.
- Every raw frametime series is included in the payload. Aggregates alone cannot be
  re-checked, and the ingestion pipeline recomputes them from the series.
- Published as [`schema/result-v1.schema.json`](schema/result-v1.schema.json), and
  validated in CI against payloads the app's own serializer produced.

### Added

- `tools/analysis/` — the 150 A/A run medians the Odin2 produced, and the harness that
  replays them. Seventy minutes of device time is expensive enough that the data is kept and
  every claim about cheaper calibration is checked against it rather than argued.

### Changed

- **The null test stops as soon as its outcome is fixed.** The Odin2's first run spent fifty
  of its seventy minutes confirming a verdict already decided: calibration had measured a
  27.7% floor, past the limit, and no sequence of further comparisons could rescue that. Two
  conditions settle a run — a calibrated floor past `MAXIMUM_USABLE_NOISE_FLOOR`, and any
  single test comparison that is not a technical tie — and both are deterministic rather
  than statistical, so neither peeks at a trend. **Only failure ever settles**: a test
  allowed to end early on favourable evidence passes far more often than its stated
  criterion, which is how a stopping rule manufactures results. On a device that fails this
  roughly halves the run; on one that passes it changes nothing.
- `NullTestResult.explain()` reports the decisive failures before incompleteness. A run that
  stopped as soon as its answer was fixed is short on purpose, and calling it "incomplete"
  would name the symptom instead of the reason and invite a pointless re-run.

- **The noise floor is the worst A/A dispersion calibration saw, not a high quantile of it.**
  A quantile is unstable here and not symmetrically so: over five comparisons the 0.95
  quantile *is* the maximum, and raising the budget to ten made it interpolate a single
  bin-straddle away to almost nothing — an Odin2 that manufactured an 11.8% difference out of
  nothing one time in ten came out claiming it resolved 9.7%, a claim its own calibration
  data contradicts. A difference the device was seen to invent is one it can invent.
- **Calibration spends ten comparisons instead of five**, and the run opens with a warm-up
  comparison that is discarded. Seven of the eight slowest runs in a 150-run session were in
  its first four minutes: that is the GPU's cold ramp-up, and measuring it as the device's
  resolution is the same mistake as timing a workload's first frame.

### Added

- **Anchor-normalised scores**, replacing the absolute-score model as the basis for ranking.
  The score is `1000 × median(anchor) / median(candidate)`, measured paired in the same
  thermal session: 1000 is the anchor, 1240 is 24% faster than it. It composes into a table
  the way a raw score does, because the device cancels in the division, and it survives
  drift the way a paired comparison does, because the anchor was measured beside the
  candidate rather than looked up. An absolute score cannot do either: finding 4 measured
  the same driver scoring 10.6% apart on one device depending on a clock bin, so a raw-score
  leaderboard would rank the phone and its cooling. Described in `docs/RANKING.md`.
- An anchor is pinned by the SHA-256 of its `.so`, never by a name or a version, and the
  system driver can never be one — it is a different binary on every device, so a ratio
  against it is no more comparable across devices than a raw score. Registry at
  `schema/anchors.json`, currently empty: no anchor has been chosen yet.
- The ingestion pipeline derives the score a second time, from its own code, and **ignores
  the published ratio** for the point estimate — it recomputes the run medians from the
  frametime series. A score is what places a driver in a public table, so it is not taken
  on the submitter's word. The leaderboard groups scores by workload *and* by anchor:
  comparing a score against one anchor with a score against another is the same mistake as
  comparing raw scores across devices, one level up.

- `:core-stats` — frametime summaries with tail percentiles taken on frametime, a
  seeded percentile bootstrap, Mann-Whitney U (exact where there are no ties,
  tie-corrected normal approximation otherwise), Cliff's delta, the A/B verdict
  engine, and the A/A null test with the ranking gate it controls.
- `:core-driver` — package import hardened against path traversal and zip
  expansion, ELF inspection before load, SHA-256 of the library that will actually
  run, and the blocking identity guard.
- `:core-vk` — Vulkan engine built on a function-pointer table rather than a link
  against libvulkan, plus workloads `baseline/v1` and `tiling_gmem/v1`.
- `:core-telemetry` — thermal zones, battery, the blocking preflight checklist, and
  the comparability rules.
- `:core-bench` — the runner state machine with its truth table, the counterbalanced
  AB/BA protocol, and the orchestration.
- `:core-report` — the versioned schema, the issue payload, and Device Flow
  publishing.
- `:app` — Compose UI in three taps, with the run isolated in the `:bench` process.
- Repository side — issue form, ingestion workflow, plausibility checks, and a
  static leaderboard on GitHub Pages.
- `libadrenotools` vendored as a submodule pinned to `8fae8ce`, with its four hook
  libraries built for arm64 and packaged into the APK.
- On-device diagnostics — a rotating log under
  `Android/data/<package>/files/logs`, written per process and flushed line by line,
  plus an uncaught-exception handler that records the stack trace and the last lines
  before it. Logcat is only available on a cable, and the protocol requires the device
  to be unplugged. Since Android 11 most file managers cannot browse `Android/data`,
  so the app also shares the whole tree as a zip. GitHub tokens are redacted on the way
  in, because a log meant to be shared must never be holding one.

### Added

- **The null test can actually be run, so the ranking gate can actually open.** The A/A
  statistics, the gate and its simulated criterion were all implemented, and nothing in the
  app could reach them: `BenchmarkPlan.nullTest(...)` had no caller, the service passed
  `nullTestResult = null` unconditionally, and there was nowhere to keep a result. P3 was
  blocked by construction — no sequence of user actions unlocked it. There is now a
  calibration step on the home screen, the runner performs the whole 5 + 10 comparison
  sequence with the leading arm flipping between them, and the result is stored per device.
- **Only the raw A/A run medians are stored, never the verdict.** The pass or fail is
  re-derived on every read by the same code that produced it. A file that said `passed: true`
  would be the authority on whether ranking is allowed, and a file is a thing that can be
  edited; a file of measurements can only support the claim the measurements support. It is
  the rule the ingestion pipeline applies to published reports, pointed at ourselves.
- **The noise floor is measured and applied per workload.** A device is not equally able to
  resolve every workload — a cheap one sits inside the frequency noise while an expensive one
  clears it — so one global floor would over-claim on the noisy workload or under-claim on the
  clean one. A measured floor may widen what the harness admits it can see, never sharpen it.
- A calibration that does not cover the workloads of the selected profile reads as *unknown*
  rather than as a pass. Ranking on a workload the device never ran against itself would be
  exactly the claim the null test exists to refuse.

### Changed

- **The short profile is one workload instead of two.** Scaling the complete profile left
  the quick one at frame counts that no longer clear `MINIMUM_USEFUL_GPU_NANOS` — 400 baseline
  frames is about 2.3 s on an Adreno 740 — so every quick comparison would have come back
  marked too brief to tell drivers apart. Half the frames of the workload that has actually
  resolved a difference is a more useful short profile than two workloads that cannot.
- A failing calibration blocks the *ranking*, not the measurement. Refusing to run at all
  would leave a drifty device with no way to see its own numbers, and P3 asks for results
  without a ranking, not for no results.

### Fixed

- **The same Binder mistake, left standing in the other half of the app.** The
  completion message was fixed to carry a path instead of the report, but the
  **Export** and **Publish** buttons still put the whole JSON into
  `Intent.EXTRA_TEXT` — and an Intent extra is a Binder transaction too. With the
  scaled workloads the report reached 2.2 MB and both buttons crashed the UI
  process with `TransactionTooLargeException` on any real run, at the one moment
  the user had a result worth keeping. Everything the app shares now leaves as a
  content URI through the FileProvider: `ReportFiles.writeForSharing` puts the
  report in the exported cache directory under a name carrying the session id, and
  only that URI crosses the boundary.

- **The standard profiles were far too small to compare drivers.** A Complete run on an
  Adreno 740 finished in 3.6 minutes against the 20 the spec asks for, and only 18% of
  that was measurement — 39 seconds of GPU work against 180 seconds of cooldown, about
  two seconds per execution. At that size a comparison measures fixed submit overhead
  rather than the driver, and it duly reported a technical tie between Turnip and the
  Qualcomm blob. The counts are scaled from the measured figures, and an execution that
  still falls under five seconds of GPU time now carries a `WORKLOAD_TOO_BRIEF` warning
  and is described as "too short to tell them apart" instead of "the same speed" —
  saying the drivers match when the app did not look long enough is the more misleading
  of the two answers. The per-execution GPU time is written to the log, so the next run
  reports the real figure rather than leaving it to be inferred from timestamps.

- **Every `VkDriverId` from Intel onwards was wrong.** The table was written from memory:
  Qualcomm's blob was recorded as 9 (which is Arm) and Turnip as 15 (which is CoreAVI).
  On a real Odin2 Portal that displayed "System driver · Imagination proprietary" for an
  Adreno, and — far worse — meant `isQualcommProprietary`, the check that refuses a Turnip
  request answered by the system driver, was comparing against a number no driver reports.
  The constants are now transcribed from `vulkan_core.h` and a test pins every one of them
  against the specification.
- **A finished run never reached the screen.** The completion message carried the whole
  report inline through a Binder transaction. A real run is around 260 KB — twenty
  executions, thousands of frametimes, sixty-six thermal zones sampled twice each — against
  a limit of roughly a megabyte for the entire process, and the failure was swallowed by a
  bare `runCatching`. The user was left watching "20 of 20" forever on a benchmark that had
  already finished. The report is written to a file and only its path crosses the boundary,
  and a failed send is now logged rather than discarded.

- **The rootless hook was being pointed at the wrong directory.** `hookLibDir` was
  passed a scratch path when `libadrenotools` requires `applicationInfo.nativeLibraryDir`
  — the library creates a linker namespace over it and `dlopen`s its hooks from inside.
  Its own header warns that a wrong path returns a valid pointer and then quietly falls
  back to the system driver, which is the exact failure P1 exists to prevent. Found by
  reading the header after vendoring the library rather than by trusting the earlier
  guess. The directory now travels the whole chain and an empty value is refused before
  the call.

- **A finished run was lost to a value JSON cannot write.** The display refresh rate
  came back as `NaN` when the display could not be queried, and encoding the report
  threw and took the runner process down — after the work was already done. The rule
  that a missing reading must never become a zero was right; `NaN` was the wrong way
  to say it, because JSON has no such value. Missing readings are `null` now, and
  encoding happens inside the guard so a formatting fault can never again cost a
  completed run.
- **A cold device was refused because an unrelated sensor was warm.** The preflight
  took the maximum across every `/sys/class/thermal` zone, including power-management
  and modem sensors that idle warm, and blocked on it — a freshly booted Odin2 Portal
  reported 62 °C from a PMIC die and could not run at all. Zones are now classified by
  role, only CPU, GPU and skin sensors are quoted to the user, and a hot reading warns
  and names the sensor rather than blocking. Android's own thermal status remains the
  gate for a genuinely hot device, because it is the only signal calibrated per device.
- **An imported driver package could never have loaded.** `RunCoordinator` built every
  `LoadRequest` with a null directory and library name, so the package's location never
  reached the loader — and because the loader answers "could not open it" either way,
  the failure would have read as a driver problem rather than a missing argument. The
  location now travels with `RequestedDriver.Package`, and a request that carries no
  location fails as `PACKAGE_LOCATION_MISSING` before the loader is asked, so the two
  are never confused again.

### Design decisions that came from measurement

Recorded in [docs/STATISTICS.md](docs/STATISTICS.md).

- The noise floor is calibrated from the device's own A/A runs rather than asserted
  as a constant, because a 1%-spread device cannot resolve the 2% a constant would
  claim.
- A calibrated floor coarser than 10% fails the null test, so an unstable device
  cannot pass by calibrating itself blind.
- The protocol counterbalances AB/BA and flips the leading arm between comparisons.
  Plain A,B,A,B alternation leaves the first arm in the cooler slot of every pair,
  which a sign test on direction now catches.

### Not implemented

Listed in the README under *What has not been verified*. In short: workloads 3–9,
Room persistence and run history, and anything that needs a GPU to prove.
