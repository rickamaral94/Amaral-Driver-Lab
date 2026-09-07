#!/usr/bin/env python3
"""Was it the device, or was it the median?

Nine findings treated the reference device's A/A dispersion as hardware. Finding 10 is the
measurement that says most of it was the summary statistic: a run's frametimes are multimodal
because the GPU changes DVFS step during the run, so the run's *median* reports whichever step
held the middle sample and jumps a whole step when the fast/slow mix crosses half.

This replays the measured sessions under both statistics and prints the floor each would have
calibrated. Run it with no arguments to use the fixtures in data/.

    python3 tools/analysis/estimator_replay.py

The mean here is GPU time over frames, which is what the logs carried. The app computes a 5%
trimmed mean of the frametime series instead — same continuity, but a single hitch cannot drag
it. The series are not in the logs, so this is the closest reconstruction the data allows, and
it is a proxy for direction rather than a precise figure.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import statistics

import numpy as np

BOOTSTRAP_ITERATIONS = 4000
RNG_SEED = 20260907
SAFETY_FACTOR = 1.5
LIMIT = 0.10

DATA = pathlib.Path(__file__).resolve().parent / "data"


def interval_distance(a: np.ndarray, b: np.ndarray, rng: np.random.Generator) -> float:
    """max(|low-1|, |high-1|) of the bootstrap interval on median(b) / median(a)."""
    ia = rng.integers(0, len(a), size=(BOOTSTRAP_ITERATIONS, len(a)))
    ib = rng.integers(0, len(b), size=(BOOTSTRAP_ITERATIONS, len(b)))
    draws = np.median(b[ib], axis=1) / np.median(a[ia], axis=1)
    low, high = np.percentile(draws, [2.5, 97.5])
    return max(abs(low - 1.0), abs(high - 1.0))


def replay(path: pathlib.Path, rng: np.random.Generator) -> None:
    doc = json.loads(path.read_text())
    executions = doc.get("executions")
    if not executions or "arm" not in executions[0]:
        return

    print(f"\n=== {path.name} ===")
    print(f"    {doc.get('note', '').strip()[:200]}")

    comparisons = sorted({e.get("comparison") for e in executions})
    worst = {"median": 0.0, "mean": 0.0}
    for c in comparisons:
        block = [e for e in executions if e.get("comparison") == c]
        arms = {arm: [e for e in block if e["arm"] == arm] for arm in ("A", "B")}
        if min(len(v) for v in arms.values()) < 3:
            continue

        row = {}
        for key, field in (("median", "medianMs"), ("mean", "meanMs")):
            a = np.array([e[field] for e in arms["A"]], float)
            b = np.array([e[field] for e in arms["B"]], float)
            distance = interval_distance(a, b, rng)
            worst[key] = max(worst[key], distance)
            row[key] = (statistics.median(b) / statistics.median(a), distance)

        print(
            f"  comparison {c}: "
            f"median ratio {row['median'][0]:.4f} floor {row['median'][1] * SAFETY_FACTOR:>6.1%} | "
            f"mean ratio {row['mean'][0]:.4f} floor {row['mean'][1] * SAFETY_FACTOR:>6.1%}"
        )

    for key in ("median", "mean"):
        floor = worst[key] * SAFETY_FACTOR
        print(f"  floor from run {key + 's':<8} {floor:>6.1%}  {'FAIL' if floor > LIMIT else 'pass'}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("sessions", nargs="*", type=pathlib.Path)
    args = parser.parse_args()

    rng = np.random.default_rng(RNG_SEED)
    for path in args.sessions or sorted(DATA.glob("*.json")):
        replay(path, rng)

    print(
        "\nThe estimator is better everywhere and decisive only where the session was long. "
        "\nWhere five runs per arm remain, a second effect dominates that the estimator cannot "
        "\ntouch: a bootstrap interval over five samples is intrinsically ~20% wide. See "
        "\nfinding 10 in docs/STATISTICS.md for the simulation that separates the two."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
