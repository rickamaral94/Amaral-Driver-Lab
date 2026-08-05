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

- `schema_version = 1`
- sanitized SHA-256 for reproducibility
- source byte/line counts and explicit truncation state
- device and Android identity from the app
- detected emulator/game/GPU/API/driver fields
- fatal/error/warning counts
- selected findings and a bounded sanitized excerpt
- user-context placeholders for reproduction steps and expected behavior

## Limitations

The parser uses conservative pattern matching. It can misidentify fields, does not prove that a Turnip driver caused the problem, and includes only selected relevant lines rather than guaranteeing the complete source log.
