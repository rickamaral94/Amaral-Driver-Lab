# Ranking

How a measurement becomes a place in a table, and what has to be true first.

## Why not an absolute score

The obvious design is 3DMark's: run a fixed scene, turn the frame rate into a score, sort
the scores. It works there because 3DMark ranks *hardware*, where the differences are
enormous — a 4090 against a 1060 is a factor of ten, and a few percent of thermal noise
changes nothing.

This project ranks *drivers on the same GPU*, where a large difference is 20% and an
interesting one is 5%. At that scale an absolute score measures the phone, not the driver.
The measurement in `docs/STATISTICS.md` finding 4 is the concrete version: on an Adreno 740,
ten runs of the same Turnip build produced two distinct medians 10.6% apart, depending only
on which DVFS bin the GPU settled into. A raw score for that driver would move 10% between
two runs of the identical thing — and across devices, with different silicon bins, cooling
and governors, a leaderboard of raw scores would rank whose console was coldest.

## Why not plain A/B either

Paired A/B measurement survives all of that, because both arms sit inside the same drift and
the device cancels out. What it does not do is compose. A comparison says "X beat Y on my
Odin2"; it does not place X and Y in a table with thirty other builds, and getting there
through pairwise comparisons would need thirty times thirty of them.

## Anchor-normalised scores

The score is a ratio against a pinned anchor driver, measured **paired, in the same thermal
session**, exactly like any other A/B comparison:

```
score = 1000 × median(anchor) / median(candidate)
```

1000 is the anchor itself. 1240 is 24% faster than the anchor, 800 is 20% slower. The
interval comes from the same bootstrap that produced the ratio, so the headline number and
the interval bracket the same quantity — the coherence P5 asks for.

This composes the way a raw score does, because the device cancels in the division, and it
survives drift the way A/B does, because the anchor was measured beside the candidate rather
than looked up from a table. Every workload has its own scale and its own ranking, as in
3DMark.

### The anchor has to be one binary

An anchor is identified by the SHA-256 of its `.so`, never by a name or a version. "Turnip
25.1" can be two different builds; **the system driver can never be an anchor at all**,
because it is a different binary on every device — a ratio against it is no more comparable
across devices than a raw score is.

The registry lives in [`schema/anchors.json`](../schema/anchors.json), and adding to it is
close to permanent: every score is a ratio against an anchor, so replacing one does not
adjust the numbers, it invalidates them.

## What a score needs before it places

A score is computed and shown whenever a comparison contains the anchor. It **places in the
ranking** only when all of these hold:

1. The device passed its null test **for that workload** — `docs/STATISTICS.md`. A device
   that cannot tell a driver apart from itself produces a ratio made of noise.
2. The comparison is not marked untrustworthy by the ranking gate.
3. The preflight was not overridden: a low-confidence run is published and never ranked.
4. Both arms ran in the same thermal session (P7), which the paired protocol enforces by
   construction.

A score whose interval straddles 1000 ties with the anchor. That is a result, not a missing
one — P6 — and the table says so rather than breaking the tie on the point estimate.

## Where the score is computed

Twice, from different code, on purpose.

- `core-report/.../AnchorScore.kt` derives it from a comparison so the app can show the
  user their own score.
- `tools/ingest/scoring.py` derives it again during ingestion, and **ignores the published
  ratio** for the point estimate: it recomputes the run medians from the frametime series
  in the payload. A score is what places a driver in a public table, so it is not taken on
  the submitter's word. What cannot be recomputed without re-running the bootstrap is the
  interval, so that is read from the payload and used only to say whether the result ties
  with the anchor.

The leaderboard groups scores by workload **and by anchor**. Comparing a score measured
against one anchor with a score measured against another is the same mistake as comparing
raw scores across devices, one level up.

## Status

The score model, the ingestion side and the leaderboard section are implemented and tested.
One thing is missing, and it is a decision rather than code:

- **The registry is empty.** No anchor has been chosen, so nothing scores yet and the page
  says so instead of showing an empty table. Choosing one is close to permanent.
