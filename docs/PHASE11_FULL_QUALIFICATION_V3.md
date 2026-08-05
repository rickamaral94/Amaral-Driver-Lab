# Phase 11 — Full Qualification v3

## Goal

Full Qualification v3 is the recommended one-button protocol after the addition of visible scenes, emulator telemetry and deep Turnip diagnostics. It preserves Full v1 and v2 as immutable historical series.

## Contracts

- `schema_version = 12`
- `qualification_schema_version = 3`
- `profile_id = turnip_full_qualification`
- `profile_version = 3`
- `qualification_report_version = 3`
- `qualification_score_version = 3`
- `diagnostic_bundle_version = 3`

## Composition

The profile has 15 orchestrated blocks. They represent 20 automatic tests:

1. Offscreen correctness before load.
2. Visible geometry scene.
3. Visible materials scene.
4. Visible post-processing scene.
5. Shader compilation workload.
6. Render pass / tiling / GMEM.
7. Compute arithmetic.
8. Transfer fill/copy.
9. Stable-scene frame time.
10. Mixed Vulkan trace.
11. Compute dependency trace.
12. Thermal sustain.
13. Offscreen correctness after load.
14. Deep diagnostics A/B: format matrix, shader corpus, pipeline cache, memory pressure at 128 MiB, synchronization and reliability probe.
15. Short A/B soak with five cycles.

Emulator telemetry is one optional evidence slot. Its absence never blocks the synthetic qualification and never changes the score automatically.

## Result model

Performance and compatibility remain separate:

- `performance_index`: normalized weighted performance evidence.
- `compatibility_index`: proportion of passed correctness/capability/reliability checks.

The recommendation requires no blocking gate. Corruption, capability regression, incorrect shader output, synchronization failure, memory safe-target failure, nondeterminism, timeout, crash, device lost or failed soak take precedence over speed.

## Performance weights

- visible scenes: 35%
- stable frame time: 12%
- render pass / tiling: 10%
- shader compile workload: 6%
- deep shader corpus and pipeline cache: 6%
- traces: 10%
- compute: 7%
- thermal sustain: 7%
- transfer: 3%
- deep synchronization: 4%

Total: 100%.

## Comparability

Full v1, v2 and v3 are separate historical series. A Full v3 result is comparable only when profile ID, profile version, profile SHA-256, hardware identity and required run configuration match.

## Limitations

Full v3 remains a synthetic Vulkan qualification. The five-cycle soak is a short reliability gate, not a long gaming session. Emulator telemetry depends on an external producer and is descriptive. Deep-diagnostic timing is descriptive and does not claim frame-level statistical significance.
