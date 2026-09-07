# Statistics: what the harness claims, and what it refuses to claim

This is the design record for `:core-stats`. It covers principles P3, P5 and P6, and it records
three things that turned out to be false when they were measured rather than assumed.

## The unit of comparison is a run, not a frame

A workload produces thousands of frametimes. Feeding them straight into a significance test is
pseudo-replication: consecutive frames are correlated, so the test treats one run's worth of
evidence as thousands of independent observations and reports significance for any two runs at
all. Each run is therefore reduced to a single number — its median frametime — and the A/B test
compares the handful of runs. With the protocol's five runs per arm, the smallest two-sided
p value Mann-Whitney can produce is 0.0079, so five runs is enough to detect something and not
enough to detect everything. That is the honest resolution of the design.

## Two estimators, one quantity

`AbComparison` reports a single quantity: the ratio of median frametimes, oriented so that above
1.0 means the first driver is better. The headline percentage and the bootstrap interval are the
same number, which is what stops the app from ever showing "A is 3% faster" beside an interval
that allows B to be faster. When the rank test and the interval disagree about whether there is
any difference at all, the comparison returns `INCONCLUSIVE` with reason `ESTIMATORS_DISAGREE`
rather than picking the more flattering one.

Percentiles are always percentiles of frametime. FPS is produced at the very last moment, for
display only. A percentile of FPS converted back to frametime is a different number, and the
difference lands exactly on the slow frames that matter.

## Finding 1: a fixed noise floor does not work

The first implementation declared a 2% minimum practical difference — anything smaller is a tie.
Simulating a device with a 1% run-to-run spread and five runs per arm showed the harness's own
A/A dispersion sitting at a median of 2.2% and a 90th percentile of 3.1%. The harness could not
resolve the 2% it claimed, so a driver compared with itself failed the null test about a quarter
of the time.

The floor is therefore measured, not asserted. `NullTest.calibrate` spends its own A/A runs
establishing how far apart the device puts a driver from itself, and later comparisons are judged
against that. The measured figure is shown to the user: *this device resolves differences of about
5% or larger*. It also makes the trade-off legible — more runs per arm buy a finer floor, and the
test `more runs per arm buy a finer floor` holds that property in place.

## Finding 2: a calibrated floor absorbs the defect it should expose

Measuring the floor on the same data the null test is judged on makes the null test pass by
construction, which is worse than not running it. Calibration and the null test therefore consume
separate runs.

That is still not sufficient. A device so unstable that it resolves nothing calibrates itself an
enormous floor, inside which every A/A comparison trivially ties — the harness reports "steady"
precisely when it has gone blind. `MAXIMUM_USABLE_NOISE_FLOOR` (10%) closes this: a floor coarser
than that fails the null test outright, with an explanation that tells the user to cool the device
or raise the run count.

## Finding 3: A,B,A,B interleaving is not enough

The spec asks for interleaved arms to dilute thermal drift. Plain alternation does reduce the
drift each arm sees, but it leaves a systematic effect: the first arm takes the earlier, cooler
slot of *every* pair, so under drift it wins every single comparison by a hair. The margin is
small enough for the calibrated floor to swallow, so all ten comparisons tie and the null test
passes while the protocol is quietly biased.

Two changes fix it:

- **Counterbalancing.** Pairs run AB, BA, AB, BA so each arm spends equal time in the cool slots
  and the hot ones. With an odd number of runs per arm the balance cannot be exact within one
  comparison, so the leading arm also flips between successive comparisons and the remainder
  cancels across the ten.
- **A sign test on direction.** A driver compared with itself has no reason to favour either arm,
  so the ten comparisons should split like coin flips. Ten wins out of ten is p = 0.002. This
  catches an ordering effect that is too small to break any individual comparison and too
  systematic to be chance — which is exactly the class of bug calibration hides.

`plain alternation still leaves a one-sided ordering effect` and `counterbalanced order survives
the same drift` are the two tests that pin this down.

## Finding 4: the noise is not noise, it is two DVFS bins

Findings 1 to 3 came from simulation. This one came off an AYN Odin2 Portal (Adreno 740), and it
is the reason the rest of this document matters.

Ten runs of `baseline/v1`, same Turnip build on both arms, same thermal session, in order:

```
5.78  5.78  6.39  5.78  5.78  6.39  5.78  6.39  6.39  6.39  ms
```

Two values. Not a spread around a centre — two. The GPU settles into one of two DVFS steps and
the median frametime snaps to whichever it picked, and the picks drift from the fast bin to the
slow one as the session heats up. The two bins are 10.6% apart.

Three consequences:

- **A/A on this device produces a ~10% apparent difference from nothing at all.** Under the
  counterbalanced schedule the early slots skew to the fast bin, so one arm's median lands at
  5.78 and the other's at 6.39, and the harness reports a driver beating itself by 10.6%. This is
  the null test's job and it is not a hypothetical: this device, today, is close to failing it.
  That is the correct outcome, not a defect to tune away.
- **An absolute score would be worthless.** The same driver, same device, same session, same
  workload scores 10.6% apart depending on a clock bin. Across devices, with different SoC bins,
  thermal designs and governors, a raw score ranks the phone and its cooling, not the driver.
  A paired comparison survives because both arms sit inside the same drift.
- **Averaging more frames does not help.** The variation is between runs, not within them —
  every frame in a run sits in the same bin. Only more *runs*, spread across the session, sample
  the bins fairly. This is the same reason a run, not a frame, is the unit of comparison.

The A/B measurement on the same log survives it: Turnip's slowest bin (6.39) still beats the
Qualcomm driver's stable 7.57 by 15.6%, and its fast bin by 23.6% — comfortably outside the
10.6% the device manufactures on its own. A difference smaller than that is not measurable here,
which is exactly what the calibrated floor is for.

## Finding 5: five calibration comparisons cannot measure a bin-hopping device

The first null test run on real hardware — 150 A/A runs of the system driver against itself
on the Odin2, 70 minutes — failed, and the way it failed is more interesting than the failure.

The run medians landed on **four** discrete values, not the two finding 4 saw:

```
6.12 (×3)   6.77 (×32)   7.57 (×107)   8.76 (×8)        extremes 43% apart
```

Seven of the eight `8.76` runs are in the first four minutes: that bin is the GPU's cold
ramp-up, not thermal throttling, and it is an artifact of starting to measure immediately.

Of the five calibration comparisons, four came back at *exactly* 1.0000 — both arms in the
`7.57` bin — and one straddled, arm A at `6.77` against arm B at `7.57`, for a ratio of
1.118 and a bootstrap interval reaching 0.185 from parity. With `CALIBRATION_QUANTILE` at
0.95 over five samples, that one comparison **is** the quantile, and the 1.5 safety factor
turned it into a 27.7% floor. Every one of the ten test comparisons then tied inside it, and
`MAXIMUM_USABLE_NOISE_FLOOR` failed the device for exactly the reason finding 2 predicted in
simulation: it tied because the harness had gone blind, not because the device was steady.

Two things follow.

**The device fails robustly, under every variant of the arithmetic.** The worst calibration
comparison's point estimate alone is 11.8%, past the 10% limit before any margin is applied.
And two of the ten *test* comparisons came back at 0.894 — the same ~11% manufactured out of
nothing — so a device that had calibrated a narrow floor would have failed the other way, on
the harness separating a driver from itself. There is no floor this device passes with.

**The calibration is under-powered, which is a defect of ours.** A 0.95 quantile over five
samples is the maximum, so on a bin-hopping device the floor is decided by whether any one
of five comparisons happened to straddle. Had none straddled, the floor would have been the
2% default and the test would have failed on the test comparisons instead — the same verdict
reached by luck rather than by measurement. A number that swings between 2% and 27.7% on the
toss of a coin is not an estimate.

So calibration now spends ten comparisons rather than five, and the run begins with a warm-up
comparison whose results are discarded, because measuring the GPU's ramp-up and calling it
the device's resolution is the same mistake as timing a workload's first frame.

## What passing the null test means

`NullTestResult.passed` requires all four of:

1. ten consecutive A/A comparisons completed;
2. every one of them a technical tie;
3. no ordering bias (sign test p ≥ 0.05);
4. a calibrated floor at or under 10%.

Until all four hold, `RankingGate` blocks ranking and stamps every comparison as untrustworthy.
A harness that ties everything would satisfy 1–3, so the suite also checks the other direction:
`the same harness settings still detect a real ten percent regression` fails if the gate's
settings have been loosened into uselessness.

## Reproducibility

The bootstrap is seeded (`SplitMix64`, default seed in `Bootstrap`). Two devices given the same
raw series and the same seed derive the same interval, so an ingested run can be re-checked from
its published frametimes. A clock-seeded bootstrap would make every published interval unverifiable.

## Reference values

`MannWhitneyUTest` and `QuantilesTest` check against values produced by `scipy.stats.mannwhitneyu`
(two-sided; exact where there are no ties, asymptotic with continuity correction otherwise) and
`numpy.percentile`. They are not regression snapshots of this implementation — they are an
independent implementation's answers.
