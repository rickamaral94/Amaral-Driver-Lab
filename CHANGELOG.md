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
- **`summary.trimmedMeanNs` added**, and it is the statistic the comparison, the noise
  floor and the anchor score are computed on. `medianNs` stays for the smoothness
  story it answers well. `schemaVersion` does not rise: a reader written for the old
  shape still gets the right answer from every field it knows about.
- Published as [`schema/result-v1.schema.json`](schema/result-v1.schema.json), and
  validated in CI against payloads the app's own serializer produced.

### Fixed

- **The noise floor was reporting the sample size, not the device.** With five runs per arm a
  bootstrap interval is about 20% wide whatever it measures, so the reference device
  calibrated a 21.8% floor from two arms that were 4.0% apart. Worse, the protocol was shaped
  to maximise exactly that: the floor is the *worst* comparison's interval, so comparisons
  widen it and runs narrow it, and ten comparisons of five runs is the worst arrangement of
  its own budget. Now **eight comparisons of fifteen runs per arm**, which simulates at about
  10% instead of 22%. Eight rather than fewer because the ordering check is measured to fire
  24 times in 40 against a biased protocol at eight and only 10 at six; below five comparisons
  it cannot fire at all, since signed-rank has no two-sided p below 2/2^n. `DEFAULT_RUNS_PER_ARM`
  stays shared with the A/B profiles on purpose — raising it only for the gate would have hidden
  that a five-run comparison cannot resolve a driver difference below about 20% either. Finding
  11 in [`docs/STATISTICS.md`](docs/STATISTICS.md), including the arm-statistic change that was
  measured and **rejected**: a mean beats the median on a clean pool and collapses under one
  corrupted run, which an Android device can always supply.
- **The calibration screen reported an invented number as a measurement, on a failure.** A run
  that settled after one comparison stored too little for cross-validation to leave one out, so
  reading it back fell through to the assumed default and rendered it as "Measured on 0 A/A
  comparisons: this device resolves differences of about 2.0%" — directly under "the
  calibration did not pass", and flattering by a factor of ten. It now says the resolution is
  unknown and names the number as a default.
- **A failing null test named the symptom and hid the cause.** A too-coarse floor and an
  ordering effect can both be present; only the floor was reported, so the advice was "cool the
  device down" when the actual problem was drift landing on one arm. Both are reported now.
- **The warm-up comparison is gone.** Finding 8 refuted its only stated justification and
  nothing replaced it; at fifteen runs per arm it cost thirty executions.
- **The A/B comparison read a statistic that manufactured the differences it reported.**
  It summarised each run by its median frametime. A run's frametimes are multimodal —
  the GPU changes DVFS step during the run — so the median reports whichever step held
  the middle sample, making it a threshold function of the fast/slow mix rather than a
  measure of cost. One percentage point of mix moves it a whole 11.8% step. On the
  reference device this put two arms of the *same driver* 11.8% apart at flat
  temperature under correct counterbalancing, while their actual costs were 4.4%
  apart, and it is most of why that device had failed the null test five times. The
  comparison, the floor and the score now read a 5% trimmed mean of the run's
  frametimes, which moves continuously with the mix and still ignores a hitch — one
  500 ms frame in a 1000-frame run moves a plain mean by 6.2% and moves this by
  nothing. Both statistics are now logged per execution. Full measurement in finding
  10 of [`docs/STATISTICS.md`](docs/STATISTICS.md).
- **The published Turnip-versus-Qualcomm magnitudes were an artefact of that statistic**
  and have been withdrawn. The direction holds — Turnip was faster in every A/B run
  recorded, under either statistic — but "15.6-23.6%" was quoted off quantised
  medians; recomputed on run means the same four runs read +18.2%, +29.8%, +13.4% and
  +7.3%. Five runs per arm cannot pin the magnitude, whichever statistic is used.
- **The message that abandons a null test now names the comparison that decided it**, with
  both arm medians. It gave a floor and nothing else, so understanding why a fifty-minute run
  stopped meant rebuilding the counterbalanced schedule by hand against the log to work out
  which executions had been which arm. That answer mattered: on the Odin2 the deciding
  comparison had one arm at 8.76 ms and the other at 7.57 ms because the device was heating
  through a frequency step while it ran.

- **The per-execution log line now carries the temperature.** The app had the number and was
  already showing it on the progress screen, and it stopped there — so working out why a
  device's medians moved meant inferring thermal state from which frequency step they snapped
  to. That inference is how finding 5's "cold ramp-up" reading got the direction backwards.

### Changed

- **The null test is one cross-validated pool of ten comparisons, not ten to calibrate plus
  ten to judge.** What keeps it non-circular is that no comparison is judged against a floor
  that saw it, and holding one out at a time achieves that with half the runs: **210
  executions down to 110**. It is also stricter — under two pools the widest calibration
  comparison inflated the floor sheltering every test comparison, and now the widest is
  judged against a limit excluding its own contribution.
- **The ordering-bias check moved from a sign test to Wilcoxon signed-rank.** Halving the
  pool cost the sign test most of its power: against a deliberately biased protocol it fired
  17 times in 40, because it reads direction and discards magnitude. Signed-rank uses both and
  catches the same protocol 28 times in 40, flagging the correct one just as rarely — 1 in 40
  either way. Reference values come from `scipy.stats.wilcoxon`.
- `NullTestRecord` is version 2. A version 1 record split its comparisons into halves judged
  under different rules and cannot be reinterpreted as a pool, so old records are ignored
  rather than migrated.
- The running screen shows a null test's budget as a ceiling — "of at most N" — and says the
  run stops as soon as the result is decided. Presenting the full plan as the number to expect
  told the user to settle in for a hundred minutes of a run that ends in ten.

### Added

- **A cooldown experiment.** The 20 s wait between arms is about 70% of a calibration's wall
  time and has never been measured. The experiment alternates it with a shorter wait in ABBA
  order inside a single thermal session, so neither setting is confounded with how far in it
  ran, and writes what it measured beside the logs — never to the null test store, because a
  run that varies the protocol on purpose is not a floor anything should be judged against.
  Read with `tools/analysis/cooldown_experiment.py`.

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
