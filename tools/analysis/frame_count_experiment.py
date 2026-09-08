#!/usr/bin/env python3
"""Is this device unsteady inside a run, or between runs?

Finding 11 left the reference Odin2 with its two A/A arms 0.50% apart — the protocol is
sound — against a 12.4% noise floor it still could not pass. The floor is the bootstrap
interval, and its width is set by the run-to-run spread of the trimmed mean, measured at
7.0%. A linear trend across the session explains only 9% of that, so it is scatter and not
drift, and scatter can be averaged down. The question is what to spend on it.

    if the scatter lives INSIDE a run   ->  spread falls as 1/sqrt(frames)
    if it lives BETWEEN runs            ->  only more runs help

The two cost wildly different amounts, because the arm cooldown is charged per *run* and not
per frame. For a fixed number of frames per arm, wall time is

    2 * comparisons * (frames_per_arm / frames_per_second  +  runs_per_arm * cooldown)

whose second term shrinks as runs_per_arm falls. So if the scatter is inside a run, the same
precision is bought with fewer, longer runs at a fraction of the wall time — and a two-hour
calibration becomes something a person will actually run.

    python3 tools/analysis/frame_count_experiment.py path/to/frames-<stamp>.json

The file rides along in the diagnostics zip, under logs/experiments/.
"""

from __future__ import annotations

import argparse
import collections
import json
import math
import pathlib
import statistics


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("experiment", type=pathlib.Path)
    args = parser.parse_args()

    payload = json.loads(args.experiment.read_text())
    print(f"{payload['deviceFingerprint']} · {payload['driverLabel']}")
    print(
        f"{payload['runsPerArm']} runs per arm, {len(payload['samples'])} comparison(s), "
        f"{payload['standardFrameCount']} against {payload['longFrameCount']} frames\n"
    )

    by_size: dict[int, list[float]] = collections.defaultdict(list)
    order: list[tuple[int, int, float]] = []
    for sample in payload["samples"]:
        runs = list(sample["armFirstNs"]) + list(sample["armSecondNs"])
        runs = [r for r in runs if r > 0]
        if len(runs) < 3:
            continue
        cv = statistics.stdev(runs) / statistics.fmean(runs)
        by_size[sample["frameCount"]].extend(runs)
        order.append((sample["comparisonIndex"], sample["frameCount"], cv))

    if len(by_size) < 2:
        print("Only one frame count is present; there is nothing to compare.")
        return 1

    print("In the order they ran, so drift across the session stays visible:")
    for index, frames, cv in sorted(order):
        print(f"  #{index + 1:>2}  {frames:>5} frames   run-to-run CV {cv:>6.2%}")

    print(f"\n{'frames':>7} {'runs':>5} {'mean ms':>9} {'run-to-run CV':>14}")
    stats = {}
    for frames in sorted(by_size):
        runs = by_size[frames]
        cv = statistics.stdev(runs) / statistics.fmean(runs)
        stats[frames] = cv
        print(f"{frames:>7} {len(runs):>5} {statistics.fmean(runs) / 1e6:>9.2f} {cv:>13.2%}")

    short, long = min(stats), max(stats)
    ratio = stats[short] / stats[long] if stats[long] else float("inf")
    expected = math.sqrt(long / short)
    print(
        f"\nThe spread fell by {ratio:.2f}x when the run got {long / short:.1f}x longer. "
        f"\nPure within-run scatter would predict {expected:.2f}x; pure between-run scatter, 1.00x."
    )

    if ratio >= 1 + 0.6 * (expected - 1):
        saved = payload["armCooldownMs"] / 1000.0
        print(
            "\nMostly inside a run. Longer runs buy precision at a fraction of the wall time, "
            f"\nbecause the {saved:.0f} s cooldown is charged per run and not per frame: the same "
            "\nprecision as fifteen 1000-frame runs comes from about four 4000-frame ones, and "
            "\nthe cooldown bill falls with it. Raise the frame count and cut runs per arm."
        )
    elif ratio <= 1.15:
        print(
            "\nMostly between runs. Longer runs buy almost nothing, so precision has to come "
            "\nfrom more runs, and on this device an honest calibration is genuinely expensive. "
            "\nThe arm cooldown is then the only remaining lever on wall time — see "
            "\ncooldown_experiment.py."
        )
    else:
        print(
            "\nBetween the two, so some of the scatter is inside a run and some is not. Longer "
            "\nruns help but do not scale as 1/sqrt(frames); the two experiments together are "
            "\nwhat sets the cheapest shape."
        )
    print(
        f"\n{len(by_size[short])} and {len(by_size[long])} runs is a small sample for a variance. "
        "Read the direction, not the second decimal."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
