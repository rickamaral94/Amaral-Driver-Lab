#!/usr/bin/env python3
"""Anchor-normalised scores, recomputed from the published frametime series.

The report carries `speedupOfA` and its interval, and this module deliberately does not
use them for the point estimate. A score is what places a driver in a public table, so it
is derived here from the raw frametime series the submission published — the same rule the rest
of the pipeline applies to every aggregate. What cannot be recomputed without re-running
the bootstrap is the interval, so that is read from the payload and only ever used to say
whether the result ties with the anchor.

See docs/RANKING.md for why the score is a ratio against a pinned anchor rather than an
absolute number.
"""

from __future__ import annotations

import json
import pathlib
import statistics

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
ANCHORS = REPO_ROOT / "schema" / "anchors.json"

#: The anchor's own score. A candidate matching it exactly scores this.
PARITY = 1000


def load_anchors(path: pathlib.Path | None = None) -> dict[str, dict]:
    """Anchors by SHA-256 of the library they pin."""
    source = path or ANCHORS
    if not source.is_file():
        return {}
    registry = json.loads(source.read_text())
    anchors = {}
    for anchor in registry.get("anchors", []):
        checksum = anchor.get("libraryChecksum", "")
        if len(checksum) != 64:
            raise ValueError(
                f"anchor {anchor.get('id')!r} is not pinned by a SHA-256: {checksum!r}. "
                "A name or a version is not enough — an anchor has to be one binary."
            )
        anchors[checksum] = anchor
    return anchors


#: Dropped from each tail of a run's frametimes before averaging. Must stay equal to
#: FrametimeSummary.TRIM_FRACTION on the app side — the whole point of recomputing the score
#: here is that two independent implementations agree, and they only agree on the same
#: statistic. A test pins the two constants against each other.
TRIM_FRACTION = 0.05


def trimmed_mean(series: list[float]) -> float:
    """Mean frametime with the fastest and slowest TRIM_FRACTION of frames dropped.

    Not the median, and the difference decides results rather than polishing them. A run's
    frametimes are multimodal — the GPU hops between DVFS steps during the run — so the
    median reports whichever step held the middle sample and jumps a whole step when the
    fast/slow mix crosses half. On the reference device that put two arms of the *same
    driver* 11.8% apart at flat temperature. The trimmed mean moves continuously with the
    mix, while still ignoring the hitches a median was chosen to ignore.
    """
    ordered = sorted(series)
    cut = int(len(ordered) * TRIM_FRACTION)
    if len(ordered) - 2 * cut < 1:
        return statistics.fmean(ordered)
    return statistics.fmean(ordered[cut : len(ordered) - cut])


def _run_throughputs(report: dict, arm: str, workload_id: str) -> list[float]:
    """The per-run throughput for one arm, which is the unit a comparison is made of."""
    runs = []
    for execution in report.get("executions") or []:
        if execution.get("arm") != arm:
            continue
        if (execution.get("workload") or {}).get("workloadId") != workload_id:
            continue
        series = execution.get("frametimesNs") or []
        if series:
            runs.append((execution.get("runIndexWithinArm", 0), trimmed_mean(series)))
    return [value for _, value in sorted(runs)]


def _checksums(report: dict) -> dict[str, str]:
    """Library checksum per arm. The system driver has none and can never be an anchor."""
    result = {}
    for driver in report.get("drivers") or []:
        if driver.get("systemDriver"):
            continue
        result[driver.get("arm", "")] = driver.get("libraryChecksum", "")
    return result


def _label(report: dict, arm: str) -> str:
    for driver in report.get("drivers") or []:
        if driver.get("arm") == arm:
            return driver.get("label", "")
    return ""


def score_report(report: dict, anchors: dict[str, dict]) -> list[dict]:
    """One score per workload where exactly one arm is a registered anchor.

    Returns an empty list when no anchor took part, which is the normal case for a
    comparison of two candidates: it is a valid measurement with no scale to place it on.
    """
    if not anchors:
        return []

    checksums = _checksums(report)
    anchor_arms = [arm for arm, checksum in checksums.items() if checksum in anchors]
    if len(anchor_arms) != 1:
        # Neither arm is an anchor, or both are. A comparison of the anchor with itself
        # is a null test, not a placement.
        return []

    anchor_arm = anchor_arms[0]
    candidate_arm = "B" if anchor_arm == "A" else "A"
    anchor = anchors[checksums[anchor_arm]]

    scores = []
    for comparison in report.get("comparisons") or []:
        workload_id = comparison.get("workloadId", "")
        anchor_runs = _run_throughputs(report, anchor_arm, workload_id)
        candidate_runs = _run_throughputs(report, candidate_arm, workload_id)
        if not anchor_runs or not candidate_runs:
            continue

        candidate_median = statistics.median(candidate_runs)
        if candidate_median <= 0:
            continue
        ratio = statistics.median(anchor_runs) / candidate_median

        low, high = _interval_against_anchor(comparison, anchor_arm)

        scores.append(
            {
                "workloadId": workload_id,
                "anchorId": anchor.get("id", ""),
                "anchorChecksum": checksums[anchor_arm],
                "candidateLabel": _label(report, candidate_arm),
                "candidateChecksum": checksums.get(candidate_arm, ""),
                "score": round(ratio * PARITY),
                "low": None if low is None else round(low * PARITY),
                "high": None if high is None else round(high * PARITY),
                # An interval containing parity means this driver and the anchor are not
                # separable on this device. That is a result, not a missing one.
                "tiesWithAnchor": None if low is None else (low <= 1.0 <= high),
                "rankable": bool(comparison.get("trustworthy")),
            }
        )
    return scores


def _interval_against_anchor(comparison: dict, anchor_arm: str):
    """The published interval, oriented anchor-over-candidate.

    `speedupOfA` is medianB / medianA, so it is already the right way up when the anchor is
    arm B. When the anchor is arm A it has to be inverted — and inverting an interval swaps
    its ends, which is the step that would otherwise mirror every score around parity.
    """
    interval = comparison.get("speedupInterval") or {}
    low = interval.get("low")
    high = interval.get("high")
    if low is None or high is None:
        return None, None
    low = float(low)
    high = float(high)
    if anchor_arm == "B":
        return low, high
    if low <= 0 or high <= 0:
        return None, None
    return 1.0 / high, 1.0 / low
