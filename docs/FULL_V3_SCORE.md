# Full v3 score

## Deep-diagnostic score bridge

Phase 10 reports remain ineligible for the older Full v1/v2 score. Full v3 defines `deep_diagnostic_score_bridge_version = 1`, which maps only pipeline cold/warm and synchronization P99 into 10% of the product performance index. All raw Phase 10 fields remain unchanged.

## Performance index

Each valid performance category is mapped to a 0–100 normalized scale centered at 50 and weighted by the immutable v3 profile. Raw metrics and original statistical analysis remain in the report. The normalized index is a product summary, not a replacement for physical units.

## Compatibility index

The compatibility index is calculated separately from performance. It summarizes passed checks for offscreen correctness, visible checkpoints, trace determinism/correctness, format capabilities, shader corpus completion, memory safe target, synchronization, reliability and the five-cycle soak.

A non-zero compatibility index does not override a blocker. Any blocking reason makes the candidate ineligible for recommendation.

## Recommendation

When all gates pass and enough performance categories are valid:

- weighted improvement above +3%: candidate recommended;
- below -3%: system recommended;
- within ±3%: technical tie.

Deep-diagnostic timing is descriptive. It contributes only to the versioned product index and does not claim statistical significance.
