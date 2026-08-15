# Phase 14 — Emulator log issue reports

## Goal

Phase 14 adds a first-screen workflow for importing an emulator log, extracting a privacy-safe technical summary and opening a prepared issue in `rickamaral94/Amaral-Driver-Lab`.

## User flow

1. Select an emulator log from the home screen.
2. The app reads at most 2,000,000 bytes locally.
3. It detects emulator, version, game/title ID, GPU, graphics API, driver and severity markers when present.
4. Sensitive e-mail addresses, user paths, secrets and IPv4 addresses are redacted.
5. A preview is shown before submission.
6. GitHub opens with a prepared title and body. Large reports use the existing clipboard fallback.

## Report contract

- `schema_version = 2`
- sanitized SHA-256 for reproducibility
- source byte/line counts and explicit truncation state
- device and Android identity from the app
- detected emulator/game/GPU/API fields
- canonical driver identity resolved per initialization block from sanitized runtime evidence
- driver identity confidence, policy version, evidence and block summaries
- fatal/error/warning counts
- selected findings and a bounded sanitized excerpt
- user-context placeholders for reproduction steps and expected behavior

## Limitations

The parser uses conservative pattern matching. It can misidentify fields, does not prove that a Turnip driver caused the problem, and includes only selected relevant lines rather than guaranteeing the complete source log.

## Driver identity policy v2

Identity precedence is explicit:

1. driver identity emitted by the emulator at runtime;
2. Vulkan `driverID`, `driverName` and `driverInfo` fields present in the log;
3. app configuration only as a last resort, marked `inferred`.

Each emulator initialization block is resolved independently. Blocks that identify different
drivers make the report `disputed`. Explicit `Qualcomm Proprietary` or `Qualcomm Technologies`
runtime evidence selects `qualcomm_blob`; a conflicting Turnip/configuration signal is preserved
and makes confidence `disputed`. A version matching `\d{3}\.\d{3}\.\d{1,3}` without an explicit
vendor never identifies the blob by itself and yields `unknown` / `disputed`. Mesa version strings
corroborate a named Turnip/Freedreno identity but never create one alone.

Canonical values are `qualcomm_blob`, `turnip`, `other` and `unknown`. Confidence values emitted by
schema v2 are `runtime_confirmed`, `inferred` and `disputed`. Readers expose schema v1 reports as
`unaudited`, not `null`. Only a known `runtime_confirmed` identity may enter driver aggregation;
`unknown`, `disputed`, `inferred` and `unaudited` are excluded.

An inferred or disputed report uses the stable token `[driver-unconfirmed]` in the Issue title.
The report body contains the sanitized line/content evidence and an emphasized warning. Fixtures
must be created only from content already sanitized by the app.

## Historical audit

`tools/audit-driver-identities.py` deterministically regenerates
`docs/DRIVER_IDENTITY_AUDIT.md` from the sanitized snapshot in
`tools/driver-identity-audit-input.json`. It is read-only with respect to GitHub and never edits or
re-publishes an Issue.

## Phase 1b — validation-flow identity (implemented)

The shared policy now also normalizes validation-flow evidence. Every disposable runner records
`driverID`, `driverName`, `driverInfo`, API/driver versions and nullable conformance version, then
the suite reconciles them with the untrusted package label. Policy v2 does not reclassify the
sanitized historical emulator fixtures; it centralizes the canonical family rules used by both
flows. Build identity remains separate: runtime confirmation proves the driver family, not which
Turnip build was loaded.
