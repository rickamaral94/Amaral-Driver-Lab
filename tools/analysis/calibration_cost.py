#!/usr/bin/env python3
"""How cheaply can the noise floor be measured without changing what it claims?

Run against `data/odin2-portal-baseline-v1-aa.json`: 150 A/A run medians an Odin2 Portal
produced in acquisition order, seventy minutes of device time. Every alternative here is
scored against what those runs actually measured, so a cheaper estimator has to earn its
place rather than be argued into it.

    python3 tools/analysis/calibration_cost.py

Findings are written up in docs/STATISTICS.md, finding 6.
"""

from __future__ import annotations

import argparse
import json
import pathlib

import numpy as np

HERE = pathlib.Path(__file__).resolve().parent
DEFAULT_DATA = HERE / "data" / "odin2-portal-baseline-v1-aa.json"

BOOTSTRAP_ITERATIONS = 4000
SAFETY_FACTOR = 1.5
RUNS_PER_ARM = 5
#: The app refuses to rank a device whose floor is coarser than this.
MAXIMUM_USABLE_FLOOR = 0.10


def schedule(runs_per_arm: int, comparison_index: int) -> np.ndarray:
    """The app's counterbalanced order: pairs AB, BA, AB, BA, the lead flipping per comparison."""
    out: list[str] = []
    for pair in range(runs_per_arm):
        a_leads = (pair + comparison_index) % 2 == 0
        out += ["A" if a_leads else "B", "B" if a_leads else "A"]
    return np.array(out)


def interval_distance(a: np.ndarray, b: np.ndarray, rng: np.random.Generator) -> float:
    """max(|low-1|, |high-1|) of the bootstrap interval on median(b) / median(a).

    The same quantity `AbResult.intervalDistanceFromParity` reports, which is what
    calibration is built out of.
    """
    ia = rng.integers(0, len(a), size=(BOOTSTRAP_ITERATIONS, len(a)))
    ib = rng.integers(0, len(b), size=(BOOTSTRAP_ITERATIONS, len(b)))
    draws = np.median(b[ib], axis=1) / np.median(a[ia], axis=1)
    low, high = np.percentile(draws, [2.5, 97.5])
    return max(abs(low - 1.0), abs(high - 1.0))


def comparison(window: np.ndarray, index: int, rng: np.random.Generator) -> float:
    arms = schedule(len(window) // 2, index)
    return interval_distance(window[arms == "A"], window[arms == "B"], rng)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", type=pathlib.Path, default=DEFAULT_DATA)
    parser.add_argument("--seed", type=int, default=20260907)
    args = parser.parse_args()

    payload = json.loads(args.data.read_text())
    runs = np.array(payload["medianNs"])
    per_execution = payload.get("secondsPerExecution", 28.4)
    rng = np.random.default_rng(args.seed)
    width = RUNS_PER_ARM * 2

    print(f"{payload['device']} · {payload['workload']} · {len(runs)} runs")
    print(f"{len(set(runs))} distinct medians, extremes {runs.max() / runs.min() - 1:.1%} apart\n")

    physical = np.array(
        [comparison(runs[i * width:(i + 1) * width], i, rng) for i in range(len(runs) // width)]
    )
    print("What the device actually measured, one value per real comparison:")
    print("  " + " ".join(f"{d:.3f}" for d in physical) + "\n")

    print("The floor these produce, by rule and by how many comparisons are in scope.")
    print("A rule worth having gives the same answer whichever column it is read from.\n")
    print(f"{'rule':<12} {'first 5':>9} {'first 10':>9} {'all 15':>9}")
    for name, rule in (
        ("max", lambda v: v.max()),
        ("q0.95", lambda v: np.quantile(v, 0.95)),
        ("q0.90", lambda v: np.quantile(v, 0.90)),
    ):
        cells = " ".join(f"{rule(physical[:n]) * SAFETY_FACTOR:>9.3f}" for n in (5, 10, 15))
        print(f"{name:<12} {cells}")

    print("\nOverlapping windows over a shorter prefix, so a quantile has samples to stand on.")
    print("Stride 2 keeps each window in phase with the order the runs were acquired in;")
    print("stride 1 pairs runs the protocol would never have paired.\n")
    print(f"{'runs':>5} {'stride':>7} {'windows':>8} {'q0.95':>8} {'max':>8}  device time")
    for n in (20, 30, 40, 60, 100, len(runs)):
        if n > len(runs):
            continue
        for stride in (2, 1):
            windows = np.array(
                [comparison(runs[i:i + width], i // stride, rng) for i in range(0, n - width + 1, stride)]
            )
            print(
                f"{n:>5} {stride:>7} {len(windows):>8} "
                f"{np.quantile(windows, 0.95) * SAFETY_FACTOR:>8.3f} "
                f"{windows.max() * SAFETY_FACTOR:>8.3f}  ~{n * per_execution / 60:.0f} min"
            )

    truth = np.quantile(physical, 0.95) * SAFETY_FACTOR
    print(f"\nReference: q0.95 over every real comparison = {truth:.3f}")
    print(
        f"This device is refused a ranking either way ({truth:.3f} > {MAXIMUM_USABLE_FLOOR:.2f}), "
        "so the question is only how long it takes to find that out."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
