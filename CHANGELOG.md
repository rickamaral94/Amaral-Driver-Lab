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
- On-device diagnostics — a rotating log under
  `Android/data/<package>/files/logs`, written per process and flushed line by line,
  plus an uncaught-exception handler that records the stack trace and the last lines
  before it. Logcat is only available on a cable, and the protocol requires the device
  to be unplugged. Since Android 11 most file managers cannot browse `Android/data`,
  so the app also shares the whole tree as a zip. GitHub tokens are redacted on the way
  in, because a log meant to be shared must never be holding one.

### Fixed

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
