# Phases and acceptance criteria

Section 13 of the spec defines six phases and what each has to demonstrate. This is
where each one actually stands. Section 15 asks the agent to run the acceptance
criteria and report failures without dressing them up, so criteria that cannot be
run yet are marked as such rather than assumed.

Legend: **done** — implemented and its criterion demonstrated. **built, unproven** —
implemented, criterion needs hardware. **not started**.

## Phase 0 — skeleton and identity

*Criterion: load v3 and the system blob on the Odin2 and show distinct, correct
identities; an attempt to run Turnip that lands on the blob is blocked with an error.*

**Built, unproven on hardware.** `IdentityGuard` implements the block and is
unit-tested against a simulated fall-back to the Qualcomm blob, a driver from an
unexpected family, a device with no `VkPhysicalDeviceDriverProperties`, and a run
with no identity at all.

`libadrenotools` is now vendored and compiles, and its four hook libraries are
packaged into the APK. Vendoring it also settled two of the assumptions this file
used to carry and exposed a bug: `hookLibDir` was being passed a scratch directory
when it must be `nativeLibraryDir`, which per the library's own header returns a
valid pointer and then silently falls back to the system driver — the exact failure
P1 exists to prevent. Fixed, and the app now refuses an empty value before calling.

What remains is a single run on an Odin2: whether the hook takes at API 33, and
whether the loaded ICD answers `vkEnumeratePhysicalDevices`. See
[DRIVER_LOADING.md](DRIVER_LOADING.md).

## Phase 1 — harness and null test

*Criterion: A/A with the same `.so` returns a technical tie in 10 consecutive runs.
Nothing is released until it does.*

**Built, demonstrated in simulation only.** Against a simulated device with 1%
run-to-run spread, ten consecutive A/A comparisons tie in at least 38 of 40 trials.
The same settings still detect a real 10% regression in at least 28 of 30 trials,
which is the check that stops the gate being loosened into uselessness.

Getting there changed the design three times; [STATISTICS.md](STATISTICS.md) records
what was measured and what it contradicted. On hardware, the criterion may still
fail — that is what the null test is for, and a failure would be a finding about the
device, not a bug to route around.

## Phase 2 — correctness

*Criterion: a deliberately broken driver fails the gate even when it is faster.*

**Partially built.** Each workload reads its final frame back and hashes it, and the
hash travels in the report; differing hashes within one arm are surfaced. What is
missing is the reference-image store per GPU and the tolerant perceptual hash, so
there is no gate to fail yet — only evidence to compare by hand. The criterion has
not been run.

## Phase 3 — full suite

*Criterion: each workload separates two known-different builds with significance.*

**Not started.** Workloads 3–9 are unimplemented on purpose. The spec requires each
workload to prove its dynamic range against two known-different builds before
entering the default profile, and that proof needs hardware. Writing seven more
workloads whose value cannot be checked would be adding weight, and section 6 is
explicit that a workload which gives the same number for everything is dead weight
to be removed rather than calibrated on faith.

## Phase 4 — persistence and comparison

*Criterion: schema migration tested; export and re-import preserves everything.*

**Partially built.** The schema is versioned, round-trips exactly including the raw
series, and refuses a payload from a newer app rather than reading it partially.
Room and the run history are not implemented, so there is no migration to test yet
and results live for the session.

## Phase 5 — publication

*Criterion: a run from the app becomes a leaderboard row with no manual step; an
invalid submission is rejected with a clear reason.*

**Done, apart from the app-to-issue leg.** The issue form, the ingestion workflow,
the plausibility checks and the leaderboard generator all work and are tested
end-to-end on payloads the app's own serializer produced. The GitHub publishing code
is written but has never contacted GitHub — it needs an OAuth client id and a real
device.

## Phase 6 — ranking

*Criterion: removing a build from the set does not change the others' scores.*

**Not started, and blocked by design.** Section 13 puts ranking last, after
everything above. The gate is in place — `RankingGate` blocks and stamps every
comparison — but no score is computed, because a score built on a harness that has
never run on hardware would be exactly the thing this project exists not to produce.

## Summary

| Phase | State |
|---|---|
| 0 — identity | Built, unproven on hardware |
| 1 — null test | Built, demonstrated in simulation |
| 2 — correctness | Partially built, no gate yet |
| 3 — full suite | Not started |
| 4 — persistence | Partially built, no Room |
| 5 — publication | Done except the app-to-GitHub leg |
| 6 — ranking | Not started, deliberately |

The next thing that unblocks the most is an Odin2 Portal with a `libadrenotools`
build: it turns phase 0 from unproven into either done or a concrete failure, and
phase 1's criterion from a simulation into a measurement.
