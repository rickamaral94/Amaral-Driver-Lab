# Phase 13 alpha3 — Recommended Turnip Validation

## Home-first driver workflow

The first app screen now exposes the complete practical setup before any technical workspace:

- import a valid AdrenoTools ZIP;
- choose System × Turnip or Turnip × Turnip;
- select the candidate driver;
- select a different reference Turnip when applicable;
- start the recommended validation directly.

Selections persist locally. The technical workspace remains optional and the legacy Full Qualification v3 is available only under advanced tools.

## Recommended profile v5

The daily recommendation profile contains nine decision-relevant stages:

1. offscreen correctness before load;
2. visible geometry and depth;
3. visible materials and sampling;
4. visible post-processing;
5. 720p GPU Stress 3D with a mandatory visual gate;
6. shader compilation;
7. stable-scene frametime;
8. mixed graphics, compute and synchronization trace;
9. offscreen correctness after load.

The following remain in Extended Full v3 rather than the daily test: isolated renderpass/tiling, arithmetic compute, transfer, isolated compute trace, 30-second thermal sustain, deep diagnostics and five-cycle soak.

Profile v5 has its own immutable definition and SHA-256. The GPU Stress stage carries 20% of the performance weight, while the complete profile remains normalized to 100%. Results are grouped by profile, hardware, comparison mode and reference-driver SHA, so they are not mixed with Full v1–v3 or Recommended v4 series. Profile v4 remains verifiable and importable with its original definition and scoring thresholds.

## Completion flow

After completion or terminal failure, the app opens the human-readable log with the full `qualification.json`, copy/export actions and a prefilled GitHub Issue handoff for `rickamaral94/Amaral-Driver-Lab`.
