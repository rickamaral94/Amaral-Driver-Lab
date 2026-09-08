#!/usr/bin/env python3
"""Is the 20 s wait between arms doing anything?

It is about seventy percent of a calibration's wall time and it has never been measured — a
number that was chosen, not one that was justified. The app's cooldown experiment alternates
between the standard wait and a shorter one in ABBA order within a single thermal session, so
neither setting is confounded with how far into the session it ran. This reads what came back.

    python3 tools/analysis/cooldown_experiment.py path/to/cooldown-<stamp>.json

The file rides along in the diagnostics zip, under logs/experiments/.

What the answer means: if the short cooldown produces no more A/A dispersion than the long
one, the wait is not buying stability and can be halved, taking a calibration run with it. If
it produces measurably more, the wait is earning its place and stays.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import statistics

import numpy as np

BOOTSTRAP_ITERATIONS = 4000
RNG_SEED = 20260907


def interval_distance(a: np.ndarray, b: np.ndarray, rng: np.random.Generator) -> float:
    """max(|low-1|, |high-1|) of the bootstrap interval on median(b) / median(a)."""
    ia = rng.integers(0, len(a), size=(BOOTSTRAP_ITERATIONS, len(a)))
    ib = rng.integers(0, len(b), size=(BOOTSTRAP_ITERATIONS, len(b)))
    draws = np.median(b[ib], axis=1) / np.median(a[ia], axis=1)
    low, high = np.percentile(draws, [2.5, 97.5])
    return max(abs(low - 1.0), abs(high - 1.0))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("experiment", type=pathlib.Path)
    args = parser.parse_args()

    payload = json.loads(args.experiment.read_text())
    rng = np.random.default_rng(RNG_SEED)

    print(f"{payload['deviceFingerprint']} · {payload['driverLabel']}")
    print(f"{payload['runsPerArm']} runs per arm, {len(payload['samples'])} comparison(s)\n")

    by_setting: dict[int, list[float]] = {}
    order: list[tuple[int, int, float]] = []
    for sample in payload["samples"]:
        a = np.array(sample["armFirstMedianNs"])
        b = np.array(sample["armSecondMedianNs"])
        if len(a) == 0 or len(b) == 0:
            continue
        distance = interval_distance(a, b, rng)
        by_setting.setdefault(sample["cooldownMs"], []).append(distance)
        order.append((sample["comparisonIndex"], sample["cooldownMs"], distance))

    if len(by_setting) < 2:
        print("Only one cooldown setting is present; there is nothing to compare.")
        return 1

    print("In the order they ran, so drift across the session is visible:")
    for index, cooldown, distance in sorted(order):
        print(f"  #{index + 1:>2}  {cooldown / 1000:>4.0f}s  {distance:.3f}")

    print(f"\n{'cooldown':>9} {'n':>3} {'median':>8} {'worst':>8}")
    for cooldown in sorted(by_setting, reverse=True):
        values = by_setting[cooldown]
        print(
            f"{cooldown / 1000:>8.0f}s {len(values):>3} "
            f"{statistics.median(values):>8.3f} {max(values):>8.3f}"
        )

    long_ms, short_ms = max(by_setting), min(by_setting)
    long_values, short_values = by_setting[long_ms], by_setting[short_ms]

    # The floor is the worst dispersion seen, so that is the comparison that decides whether
    # the wait can be shortened — a better median at a worse maximum buys nothing.
    worse = max(short_values) - max(long_values)
    saved_per_execution = (long_ms - short_ms) / 1000.0
    print(
        f"\nThe short setting's worst dispersion is {abs(worse):.3f} "
        f"{'higher' if worse > 0 else 'lower'} than the long one's."
    )
    if worse <= 0:
        print(
            f"On this evidence the wait is not buying stability. Halving it saves "
            f"{saved_per_execution:.0f} s per execution — about "
            f"{saved_per_execution * 110 / 60:.0f} minutes of a 110-execution calibration."
        )
    else:
        print(
            "On this evidence the wait is earning its place: the shorter one lets the device "
            "manufacture a larger difference from nothing, which is exactly what the floor "
            "would have to absorb."
        )
    print(
        f"\n{len(long_values)} and {len(short_values)} comparisons is a small sample. "
        "Read this as a direction to act on, not a precise effect size."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
