# Full v3 historical comparability

Full Qualification v3 is a new series because its composition, weights, gates, report, score and diagnostic bundle differ from v1 and v2.

Comparable v3 reports require:

- `profile_id = turnip_full_qualification`;
- `profile_version = 3`;
- identical `profile_sha256`;
- same public hardware identity;
- same workload/trace versions;
- deep diagnostics v1 with 128 MiB memory profile;
- short soak v1 with five cycles.

Telemetry attachments are optional and are never used to decide historical comparability of the synthetic Full result.

No existing workload, trace, scene, campaign, deep-diagnostic or telemetry series is redefined by Phase 11.
