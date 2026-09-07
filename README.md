# Amaral Driver Lab

An Android app that compares Vulkan drivers on a phone, and a repository that turns
the results into something other people can check.

Rootless, arm64 only. It loads a Turnip/Mesa driver package the user imported,
proves which driver actually answered, runs a fixed amount of graphics work, and
reports a difference only when it can support one.

**Status: rebuilt from scratch, and not yet run on hardware.** Everything below is
implemented and unit-tested; nothing has been executed on a real GPU. See
[What has not been verified](#what-has-not-been-verified).

## Why it is built this way

Most of the design exists because the obvious version of it is wrong.

**A benchmark that cannot name the driver is worse than no benchmark.** A Turnip
package that silently fails to load, followed by real numbers filed under
"Turnip", looks exactly like evidence. So the app asks Vulkan which driver
answered and refuses to run if it is not the one you chose, and the loader fails
outright rather than falling back to the system driver.

**A driver that renders wrong is not fast.** Each workload reads its final frame
back and hashes it before any timing counts.

**A ranking is only as honest as the harness under it.** Before ranking anything,
the app runs one driver against itself. If it can tell a driver apart from itself,
the measurements are the problem, and the ranking stays blocked.

**A tie is a real answer.** Two drivers performing the same is the most common
outcome, and the app says so instead of inventing a winner from noise.

## The three findings that changed the design

These were measured, not assumed, and each contradicted the obvious approach.
[docs/STATISTICS.md](docs/STATISTICS.md) has the detail.

1. **A fixed noise floor does not work.** Claiming "differences under 2% are ties"
   sounds careful. Simulating a device with 1% run-to-run spread showed its A/A
   dispersion sitting at 2.2% median — the harness could not resolve what it
   claimed. The floor is now measured from the device's own A/A runs and shown to
   the user: *this device resolves differences of about 5% or larger*.

2. **A calibrated floor hides the defect it should expose.** A device too unstable
   to resolve anything calibrates itself an enormous floor, inside which every A/A
   comparison trivially ties — the harness reports "steady" exactly when it has
   gone blind. A floor coarser than 10% now fails the null test outright.

3. **A,B,A,B interleaving is not enough.** The spec asks for interleaved arms, and
   it helps, but the first arm still takes the earlier, cooler slot of *every*
   pair. Under thermal drift it wins all ten comparisons by a margin small enough
   for the noise floor to swallow. The protocol now counterbalances AB/BA and flips
   the leading arm between comparisons, and a sign test on direction catches what
   is left.

## What it measures

| Workload | What it catches |
|---|---|
| `baseline/v1` | Sanity, and the cost of getting a frame into the queue at all |
| `tiling_gmem/v1` | Binning and GMEM decisions — the classic Turnip regression vector |

Two subpasses over a 4x multisampled colour target and a packed depth/stencil
target, resolved inside the render pass and read back through an input attachment
with `BY_REGION`, both multisampled attachments `DONT_CARE` on store. Every part of
that shape is a decision the driver has to make about what stays on chip.

Work is fixed as counts, never as a duration. Running "for thirty seconds" rewards
a faster driver with more work and the same wall clock, which turns throughput into
a measurement of how hot the device happened to be.

Percentiles are percentiles *of frametime*. FPS is produced at the very last
moment, for display only.

## Modules

```
:app             Compose UI, the :bench service, the process boundary
:core-bench      Runner state machine, the counterbalanced protocol, orchestration
:core-vk         C++17 Vulkan engine, workloads, JNI
:core-driver     Package import, validation, driver identity, the P1 guard
:core-stats      Percentiles, bootstrap, Mann-Whitney U, Cliff's delta, the null test
:core-report     Versioned schema, issue payload, GitHub publishing
:core-telemetry  Thermal, battery, preflight, comparability
```

`:core-stats` is a plain Kotlin JVM module on purpose, so its statistics can be run
and checked without a device.

## Building

Requires JDK 17, Android SDK 35, NDK 27.2.12479018, CMake 3.22.1.

```
./gradlew test testDebugUnitTest    # 189 Kotlin tests
./gradlew :app:assembleDebug
python -m pytest tools/ingest/tests # 28 ingestion tests
```

Rootless driver loading needs `libadrenotools`, which is not vendored here — see
[docs/DRIVER_LOADING.md](docs/DRIVER_LOADING.md) for the pinned commit. Without it
the app builds and runs against the system driver, and says clearly that an
imported package was not loaded and the system driver was *not* used instead.

## Publishing a result

The result screen has **Publish to GitHub**. The app shows exactly what will be
sent before sending it. Publication is always an explicit action; there is no
automatic telemetry, and no persistent device identifier is collected — see
[docs/PRIVACY.md](docs/PRIVACY.md).

A submission becomes an issue, which
[the ingestion workflow](.github/workflows/ingest-report.yml) validates against
[`schema/result-v1.schema.json`](schema/result-v1.schema.json) and comments on. If
it passes, the row is appended to `data/results.jsonl` and the
[leaderboard](docs/leaderboard/index.html) is regenerated.

Refusals, each with the reason commented on the issue: invalid schema, missing
driver hash, failed null test, a failed execution under the compatibility gate, and
a run that started while the device was already throttling.

## About "verified results"

This project does not claim them, and cannot.

Any signature the app could generate, anyone who unpacked the APK could generate
too, because the key would be in the APK. Promising verification on that basis
would be a lie told with cryptography.

What is actually done: the raw frametime series is required rather than aggregates;
the median, mean and percentiles are recomputed server-side from that series and a
summary that does not follow from it is refused; physically impossible frametimes
are rejected. Editing one number to look faster fails these checks. A determined
forger who generates a consistent fake will pass them, and the leaderboard says so.
A result is marked `corroborated` only when an independent submission reproduces it.

## What has not been verified

Honest list, per section 15 of the spec. None of this has run on a GPU.

- **Nothing has been executed on hardware.** All 217 tests are unit tests. The
  Vulkan code compiles for arm64 and the APK builds; no frame has been rendered.
- **`libadrenotools` integration is unrun.** Six specific assumptions about it are
  listed in [docs/DRIVER_LOADING.md](docs/DRIVER_LOADING.md), including the
  argument semantics of `adrenotools_open_libvulkan` and whether the linker
  namespace hook works at minSdk 30.
- **The null test's acceptance criterion is met in simulation only.** Ten
  consecutive A/A ties pass against simulated devices; whether a real Odin2 Portal
  holds still enough is exactly what the null test is for, and it has not run.
- **Workloads 3 through 9 are not implemented.** The spec asks each workload to
  prove it separates two known-different builds before entering the default
  profile. That proof needs hardware, so the workloads that would need it are not
  written yet.
- **Dynamic range is unproven for workloads 1 and 2.** Same reason.
- **Room persistence and run history are not implemented.** Results currently live
  for the session and are exported or published from there.

## Licence

See [LICENSE](LICENSE).
