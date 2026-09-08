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

`AbComparison` reports a single quantity: the ratio of *trimmed mean* frametimes, oriented so
that above 1.0 means the first driver is better. It was the ratio of medians until finding 10
showed a median cannot do this job on real hardware. The headline percentage and the bootstrap interval are the
same number, which is what stops the app from ever showing "A is 3% faster" beside an interval
that allows B to be faster. When the rank test and the interval disagree about whether there is
any difference at all, the comparison returns `INCONCLUSIVE` with reason `ESTIMATORS_DISAGREE`
rather than picking the more flattering one.

Percentiles are always percentiles of frametime. FPS is produced at the very last moment, for
display only. A percentile of FPS converted back to frametime is a different number, and the
difference lands exactly on the slow frames that matter.

The median and the percentiles are still reported, and they still answer the question they are
good at — *what does a typical frame feel like*, where one hitch must not redefine "typical".
They are simply not what the comparison, the floor or the score read. Those answer a different
question — *how long did the fixed work take* — and finding 10 is the measurement that forced
the two apart.

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

  **This is wrong, and finding 10 is the correction.** Frames within a run do *not* all sit in
  the same bin — the GPU hops between steps during the run, and the ratio of a run's median to
  its own mean swings from 0.84 to 1.09 across the runs in this very log, which is impossible
  if a run is single-valued. What is single-valued is the *median*, because a median of a
  two-valued series reports whichever step held the middle sample. The two-value appearance
  above is the estimator, not the device. A run, not a frame, is still the unit of comparison,
  for the reason in the section at the top of this document; but averaging within a run helps
  after all, and the statistic that does the averaging is now a trimmed mean.

The A/B measurement on the same log survives it, and its *direction* has never been in doubt:
Turnip beat the Qualcomm driver on `baseline/v1` in every run recorded on this device, under
either estimator. The magnitude quoted here — 15.6% and 23.6% — does not survive, because it was
read off the same quantised medians. Recomputed on run means, the four real A/B runs read:

| run   | ratio from run medians | ratio from run means |
|-------|-----------------------:|---------------------:|
| 03:00 |                  +5.9% |               +18.2% |
| 08:09 |                 +37.1% |               +29.8% |
| 09:14 |                 +31.0% |               +13.4% |
| 09:29 |                 +20.8% |                +7.3% |

Consistent in sign, nowhere near consistent in size, and the two estimators disagree by up to
17 points on the same runs. The honest statement is that Turnip is faster here and this app
cannot yet say by how much — five runs per arm is not enough to pin the magnitude, whichever
statistic is used. The precision of "15.6-23.6%" was an artefact of quoting a quantised number
to one decimal place.

## Finding 5: five calibration comparisons cannot measure a bin-hopping device

The first null test run on real hardware — 150 A/A runs of the system driver against itself
on the Odin2, 70 minutes — failed, and the way it failed is more interesting than the failure.

The run medians landed on **four** discrete values, not the two finding 4 saw:

```
6.12 (×3)   6.77 (×32)   7.57 (×107)   8.76 (×8)        extremes 43% apart
```

Seven of the eight `8.76` runs are in the first four minutes, which read at the time like a
cold ramp-up. **That was backwards, and finding 8 has the evidence.** The device had been
running for the previous fifty-seven minutes; it started that session hot, and `8.76` is the
throttled step, not a cold one.

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

> The switch from a quantile to the maximum was argued from a *synthetic* case: nine
> comparisons tying exactly and one straddling. Finding 6 replayed the real 150 runs and
> found that case does not occur here — straddling is the norm on this device, eleven of
> fifteen comparisons, so `max`, `q0.95` and `q0.90` all land on the same 0.287. The rule is
> still the right one, for a reason that only became visible later: it is the monotonicity
> `max` provides, not the stability, that earns its place.

So calibration spends more comparisons than five, and the run begins with a comparison whose
results are discarded. **The reason given for that discard was wrong** — see finding 8 — and
whether it earns its place for a different reason is an open question rather than a settled
one.

## Finding 6: the expensive part cannot be resampled away, but it can be abandoned early

Seventy minutes of device time is a lot to ask before a benchmark can rank anything, so the
150 A/A runs from finding 5 were replayed offline to see whether a cheaper estimator gets the
same answer. The data and the harness are in `tools/analysis/`, so every claim below can be
re-run: `python3 tools/analysis/calibration_cost.py`.

**The obvious saving does not work.** Forming overlapping windows over a shorter prefix of the
run sequence gives many more pseudo-comparisons for the same device time, and the floor they
produce is wrong. Windows taken at stride 1 pair runs the counterbalanced protocol would never
have paired, and inflate the floor from 0.287 to 0.441 — a 54% over-estimate, manufactured
purely by scrambling the phase of the interleave. Stride 2 keeps each window in phase and is
much better behaved, but still reads 0.315 at twenty runs and only converges on 0.287 at
sixty. Against a hundred runs of real calibration that is a 40% saving on one phase, bought at
the cost of an estimator whose bias depends on how much of it you can afford. Not worth it.

The reason is worth stating, because it is the same reason a plain bootstrap would fail here:
the dispersion this test measures *is* the temporal structure. Drift and bin-hopping over the
session are the signal, not noise around it, so any resampling that treats the runs as
exchangeable measures something else.

**The saving that does work costs nothing.** The floor is the maximum dispersion seen, and a
maximum only ever grows as comparisons are added. So a partial calibration whose floor already
exceeds `MAXIMUM_USABLE_NOISE_FLOOR` can never come back under it, and the run can stop the
moment that happens rather than after the full budget. On this device the very first
calibration comparison reads 0.157, which is 0.236 with the safety factor and already past the
limit: the seventy-minute run had its answer after about ten.

That coupling is load-bearing and easy to break by accident. **Early exit during calibration is
sound only because the floor rule is monotonic in the comparisons it has seen.** Swapping the
maximum for a quantile — which finding 5's synthetic reasoning nearly did — would make a
partial floor able to fall as evidence accumulates, and a run could then abandon itself over a
threshold its final answer would not have crossed. There is a test pinning the monotonicity for
exactly this reason.

## Finding 7: halving the protocol costs power, and the sign test was where it showed

Ten comparisons to calibrate plus ten to judge is two hundred executions, and the split is not
what makes the test non-circular — *not judging a comparison against a floor that saw it* is.
Holding one out at a time achieves that with a single pool of ten, so the protocol became one
cross-validated set: each comparison judged against a floor calibrated on the other nine.

That is also stricter than the split, which is the part worth noticing. Under two pools the
widest calibration comparison inflates the floor every test comparison is then judged against,
sheltering them. Leaving each out of its own floor means the widest is judged against a limit
that excludes its own contribution: it cannot hide behind itself.

**Halving the pool cost real power, and simulating it is what found that.** Against a
deliberately biased protocol — plain A,B,A,B alternation under drift — the sign test on ten
comparisons fired 17 times in 40. It reads only the *direction* of each comparison and throws
the size away, which a twenty-comparison pool could afford and a ten-comparison one cannot.
Wilcoxon signed-rank on the log ratios uses both, and on the same simulations catches the same
biased protocol 28 times in 40 while flagging the correct, counterbalanced protocol just as
rarely — 1 in 40 either way. Same false alarms, more of the effect seen, no extra device time.

A third candidate looked better than both and was **completely wrong**. Testing at the level of
each *pair of slots* rather than each comparison gives fifty observations instead of ten, and
it flagged the correct protocol 39 times in 40 while never once flagging the biased one. It
measures drift — the earlier slot of every pair is faster under drift, which is true and is
exactly what counterbalancing is designed to cancel at the arm level. It answers a different
question, confidently, in the wrong direction. It is only not in the codebase because the
alternatives were measured against a known-biased protocol before one was chosen.

The other price is a slightly higher false block: a clean device passes 93.5% of the time
against roughly 95% before. A blocked device can re-run; a falsely passed one pollutes a public
leaderboard, so that asymmetry is the right way round.

## Finding 8: a cold device is the fast one, and this one never settles

Three more null tests on the same Odin2, back to back, and the shape only becomes visible when
the runs are read against how long the device had been idle beforehand:

```
run     idle before   n    first 10   last 10    drift
10:30             —  122     6.88 ms   7.20 ms    +4.7%
11:27         0 min  150     8.40 ms   7.41 ms   -11.8%
13:22        44 min   68     6.96 ms   8.28 ms   +19.0%
13:55         1 min   12     8.20 ms   7.79 ms    -5.0%
14:00         1 min   20     7.35 ms   7.25 ms    -1.3%
```

The 13:22 run had the device to itself for forty-four minutes first. It starts in the fastest
bin the hardware ever produced and climbs steadily for thirty-two minutes without levelling
off. Every run that began within a minute of the previous one starts slow instead.

So **cold is fast and hot is slow**, which is ordinary thermal throttling and the opposite of
what finding 5 concluded. The 11:27 run looked like a cold ramp-up because its first four
minutes were slow; it was not cold at all — it began the moment a fifty-seven minute run
ended. What that reading cost is a design decision: the warm-up comparison was added to
discard a ramp-up that does not exist. It may still be worth keeping, because starting cold
means the first comparisons of a pool are fast and the last are slow, which is the drift
counterbalancing is fighting. But ten executions is nowhere near enough to reach a steady
state on a device that is still climbing at thirty-two minutes, so what it currently buys is
unclear and it is flagged rather than defended.

The larger finding is that **this device does not reach thermal equilibrium inside a null
test at all**. A 19% drift across one run dwarfs the 10.6% between the DVFS bins, and it means
each comparison's dispersion depends on where in the session it happened to sit. Counter-
balancing handles drift inside a comparison; nothing handles a session-long ramp except
running shorter or waiting longer, and both are the user's time.

There is also a smaller lesson about instrumentation. All of the above had to be inferred
from *which frequency step the medians snapped to*, because the per-execution log line carried
frames, GPU time and the median but not the temperature — a number the app had in hand and
was already showing on the progress screen. It is on the log line now. An hour of reading a
file backwards is what a missing field costs.

## Finding 9: the warm-up hands the first comparison the steepest part of the ramp

A cross-validated run on the same Odin2, abandoned after 2 of 11 comparisons in nine and a half
minutes. The floor it stopped on was **45.2%**, the widest yet, and the log says why once the
schedule is reconstructed against it:

```
warm-up      (1-10) : 6.12 6.77 7.57 8.76 8.76 8.76 7.57 8.76 8.76 8.76
comparison 1 (11-20): 6.12 7.57 8.76 8.76 8.76 8.76 8.76 7.57 7.57 7.57
                      arm 1 median 8.76 ms · arm 2 median 7.57 ms · ratio 0.864
```

GPU time per execution climbs from 6426 ms to 8803 ms across the run, +37%. The warm-up walks
the device from the fastest frequency step it has to the slowest, and comparison 1 then runs
across the boundary with one arm mostly in each. That 45.2% is not this device's measurement
noise; it is the thermal ramp, sampled by a comparison that happened to sit on it.

Two things I built interact badly here. The warm-up puts the device mid-ramp, and early exit
makes the comparison right after it decisive. Neither is wrong on its own — the floor really
is monotonic, so a run whose first comparison reads 45.2% really cannot pass — but together
they mean **a fifty-minute test is settled by the single comparison most likely to be
contaminated**.

What this does *not* license is ripping the warm-up out. It has now produced first-comparison
floors of 45.2% and 17.7%, against 23.6% for a run that had no warm-up at all: three points,
no signal. Its stated justification was wrong (finding 8) and the mechanism by which it could
hurt is now visible, and that is still not evidence that removing it helps. The question is
open and marked open.

The Odin2's verdict is unaffected either way. It has now failed four times, on floors from
17.7% to 45.2%, and every one of those is past the limit by a wide margin.

There is a smaller lesson, and it is the second time the same one: the line that abandoned the
run named a floor but not the comparison behind it, so understanding the decision meant
rebuilding the counterbalanced schedule by hand to work out which executions had been which
arm. The settle message now spells out the deciding comparison's arm medians.

## Finding 10: the median was manufacturing the instability, not measuring it

Nine findings treated this device's A/A dispersion as a property of the hardware. Most of it was
the summary statistic.

The run that settled it is an A/A null test of the system driver against itself, 14:58 on the
reference Odin2. Its second comparison, the one that failed the run:

```
slot :     0     1     2     3     4     5     6     7     8     9
arm  :     B     A     A     B     B     A     A     B     B     A
med  :  7.57  7.57  7.57  6.77  7.57  7.57  7.57  6.77  6.77  7.57   ms
degC :  51.4  51.4  52.2  53.4  52.2  52.2  51.8  53.4  51.8  51.4

arm A medians : 7.57 7.57 7.57 7.57 7.57  ->  7.570
arm B medians : 7.57 6.77 7.57 6.77 6.77  ->  6.770   ratio 1.1182, floor 15.8%
```

Nothing is wrong with that run. The counterbalancing is correct (BAAB BAAB BA), the temperature
is flat across two degrees, there is no drift to cancel, and both arms are the same driver. It
still reported an 11.8% difference, and the noise floor it produced failed the device.

The same ten runs, summarised by mean frametime per run instead — a number the log already
carried, as GPU time over frames:

```
arm A means : 7.63 8.04 8.40 8.03 8.15  ->  8.05
arm B means : 7.99 7.56 8.10 7.24 7.68  ->  7.71   ratio 1.044
```

Two arms of one driver, 4.4% apart on a continuous statistic and 11.8% apart on a median.

**Why.** The GPU changes DVFS step *during* a run, so a run's frametimes are multimodal. The
median then reports whichever step held the middle sample, which makes it a threshold function
of the fast/slow mix. Simulated on the two steps this device actually uses:

| slow frames in the run | median  | trimmed mean |
|-----------------------:|--------:|-------------:|
|                  49.5% | 6.77 ms |      7.17 ms |
|                  50.5% | 7.57 ms |      7.17 ms |

One percentage point of mix — a fraction of a percent of real cost — moves the median a whole
11.8% step and the trimmed mean by 0.12%. A hundredfold difference in sensitivity to something
that is not the drivers. That is the whole of finding 4's "two values and nothing between":
the ladder `6.12 / 6.77 / 7.57 / 8.76 ms` that 122 run medians landed on is the DVFS ladder
being reported verbatim by an estimator that rounds to it. The runs' actual mean costs are
spread continuously and overlap between the arms.

**The fix.** The comparison, the noise floor and the anchor score now read a 5% trimmed mean of
each run's frametimes. Trimming rather than a plain mean keeps the one property the median was
chosen for: a single 500 ms hitch in a 1000-frame run moves a plain mean by 6.2% and moves this
by nothing at all. Both statistics are logged per execution, the trimmed mean first, because
logging only the median is what cost a day of reading device instability into a file that
contained the answer.

**What it does not fix.** Replaying every A/A comparison ever recorded on this device under both
statistics:

| session | floor from medians | floor from means |
|---------|-------------------:|-----------------:|
| 13:22   |        23.6%  fail |       9.5%  pass |
| 13:55   |        44.1%  fail |      25.3%  fail |
| 14:00   |        17.7%  fail |      16.6%  fail |
| 14:42   |        45.2%  fail |      32.6%  fail |
| 14:58   |        44.1%  fail |      30.0%  fail |

Better everywhere, decisive only where the session was long: 13:22 is the one with 34 runs per
arm. The sessions that still fail are the ones with five runs per arm, and there a second effect
dominates that has nothing to do with the estimator — **a bootstrap interval from five samples is
intrinsically wide**. The 14:58 comparison whose arms differ by 2.0% on means still produces an
interval reaching 20% from parity, because resampling five values cannot say more than that.

Simulating a device with pure Gaussian run-to-run noise and no DVFS steps at all, the floor it
calibrates and whether it clears the 10% limit:

| run-to-run noise | n=5   | n=11  | n=21  | n=41 |
|------------------|------:|------:|------:|-----:|
| 0.5%             |  2.4% |  1.6% |  1.1% | 0.8% |
| 1%               |  4.7% |  3.2% |  2.2% | 1.5% |
| 2%               |  9.8% |  6.0% |  4.5% | 3.2% |
| 5%               | 23.8% | 15.3% | 11.1% | 8.1% |

So the gate is reachable and the 10% limit is not absurd: a device with 1% run-to-run noise
passes at five runs per arm, and one with 2% passes at eleven. What is not reachable is passing
it with an estimator that quantises to 11.8% steps — which is what every failure before this
finding was really reporting.

The reference device sits near 4% run-to-run noise on the trimmed mean, which the table puts at
roughly twenty runs per arm to pass. Whether that is best bought with more runs or with longer
runs is open and is the next thing to measure: if the 4% is the GPU hopping steps *within* a run,
more frames per run buys the same variance reduction at a quarter of the wall time, because the
20 s cooldown is charged per run and not per frame. The logs cannot settle it — every run so far
is 1000 frames — so it needs one deliberate experiment at two frame counts.

## Finding 11: the floor was measuring the sample size, and the protocol was shaped to maximise that

Finding 10 fixed what a run is summarised by. This is about how many runs there are, and it is
the reason the reference device still could not pass.

A cold Odin2, A/A on the system driver, on the build with the trimmed mean:

```
arm  :    B    A    A    B    B    A    A    B    B    A
ms   : 9.08 8.19 7.80 8.25 7.78 7.93 7.46 8.31 7.64 8.26
degC : 38.2 37.8 39.4 37.8 39.0 39.0 39.0 37.8 38.6 37.8

arm A median 7.93   arm B median 8.25   ->  point estimate +4.0%
                                            calibrated floor  21.8%
```

The estimator change worked: the same device reported its two arms 11.8% apart before and 4.0%
apart here. And the floor still came out at 21.8%, because **the floor is not the point estimate**
— it is the bootstrap interval around it, and a bootstrap interval over five values is about 20%
wide however steady the device is. The Odin2 was being failed for the sample size, not for the
hardware.

### The protocol was the worst arrangement of its own budget

The floor is the **worst** comparison's interval. So comparisons and runs pull in opposite
directions: every comparison added is another chance to draw a bad one and can only widen the
floor, while every run added narrows all of them. Ten comparisons of five runs maximises both
harms. Simulated at the measured per-run spread, keeping the total device time roughly fixed:

| comparisons | runs/arm | executions | wall time | floor @3.9% | floor @5.7% |
|------------:|---------:|-----------:|----------:|------------:|------------:|
|          10 |        5 |        100 |      50 m |       17.6% |       24.7% |
|           5 |       11 |        110 |      55 m |       11.1% |       14.1% |
|           6 |       15 |        180 |      90 m |        9.2% |       12.9% |
|           8 |       15 |        240 |     120 m |      ~10%   |       ~13%  |
|          10 |       15 |        300 |     150 m |        9.7% |       15.6% |

### Why eight and not fewer

Fewer comparisons is cheaper and tightens the floor, so the count wants to fall — and the
ordering check is what stops it. Measured against plain A,B,A,B alternation under drift, the
protocol finding 3 rejected:

| comparisons | alternation flagged | counterbalanced flagged (false alarms) |
|------------:|--------------------:|---------------------------------------:|
|           6 |             10 / 40 |                                  2 / 40 |
|           8 |             24 / 40 |                                  0 / 40 |
|          10 |             27 / 40 |                                  1 / 40 |

Six comparisons saves half an hour and hands back most of finding 3's safety net. Eight keeps
it. Below five the check cannot fire at all — Wilcoxon signed-rank on n comparisons has no
two-sided p smaller than 2/2^n, so at five the smallest attainable p is 0.0625 and no sensible
alpha can ever be crossed. A check that cannot fail is decoration.

**Shipped: 8 comparisons of 15 runs per arm**, and the warm-up comparison is gone.

### The same-constant rule

`DEFAULT_RUNS_PER_ARM` is deliberately shared by the A/A and A/B runs. It is tempting to raise
it only for the null test so the gate passes, and that would be the dishonest fix: the gate is
not a formality bolted onto the measurement, it measures **the very protocol the comparison
uses**. A 21.8% floor at five runs per arm was not the gate being pedantic — it was the correct
statement that a five-run-per-arm A/B comparison on this device cannot resolve anything below
about 20%. Two of the four Turnip-versus-Qualcomm runs in finding 10 (+13.4% and +7.3%) sit
under that. Removing the gate would not have made those measurements real, only silent.

### A change that was measured and rejected

The obvious next move was to apply finding 10 one level up: the arm statistic is the *median* of
the per-run values, and a median of five is coarse for the same reason a median of a bimodal
frametime series is. On the real comparison above it helps — 21.8% down to 15.7%. It was
rejected anyway, because the objection turned out to dominate:

| arm statistic | clean floor | floor with one 2×-slow run | point error, contaminated |
|---------------|------------:|---------------------------:|--------------------------:|
| median        |        8.6% |                       9.2% |                      0.2% |
| mean          |        6.5% |                      42.5% |                      9.0% |
| trim 1 each end |      7.0% |                      36.0% |                      0.5% |

An Android device can always hand you one corrupted run — a background app, a thermal event.
The median costs about two points on a clean pool and is the only one of the three that survives
that. Trimming does not rescue the mean here, because the bootstrap resamples with replacement
and a contaminated value reappears often enough to survive the trim.

The reason this is the opposite of finding 10's answer is worth stating, because "use the
trimmed mean" is not a rule: within a run the frametimes are **multimodal**, so the median
quantises onto a mode and reports the wrong quantity. Across runs the values are continuous and
roughly symmetric, so the median is merely a little inefficient. Same estimator, different
distribution, opposite verdict.

### Two things the app was saying that were not true

The screen for the run above read **"this device resolves differences of about 2.0%"** directly
underneath **"the calibration did not pass"**, and below that, "0 of 10 A/A comparisons done".

The run had settled after one comparison on a 21.8% floor. Cross-validation needs at least two
comparisons to leave one out, so reading a one-comparison record back yielded no usable
comparisons and fell through to `NoiseFloorCalibration.assumed()` — the default claim of 2.0%,
which `describe()` then rendered as "Measured on 0 A/A comparisons". The one number a user takes
away from that screen was both invented and flattering, on a failure. It now says the resolution
is unknown and that the number is a default rather than a finding.

## Finding 12: fifteen runs fixed the estimate and not the interval

The 8x15 shape from finding 11, run on a cold Odin2, A/A on the system driver:

```
arm A median 8.07 ms     arm B median 8.11 ms     point estimate +0.50%
                                                  calibrated floor  12.4%
```

Half a percent apart, same driver, thirty runs. The protocol is doing its job — that is the
tightest A/A estimate this project has produced, against 4.0% at five runs per arm and 11.8%
before finding 10. And the floor is still past the 10% limit, by 2.4 points.

The width comes from the run-to-run spread, which measured **7.0%** — higher than the 3.9-5.7%
that finding 11's simulations assumed, which is why 8x15 landed at 12.4% rather than the
predicted ~10%.

### It is scatter, not drift

Worth checking before spending anything on it, because the two have opposite fixes — drift
wants *shorter* comparisons, scatter wants longer ones:

```
correlation with slot order         r = +0.30
linear trend across the comparison  +7.0% over 30 runs
variance explained by that trend    9%
floor with the trend removed        10.5%  (from 12.1%)
```

So 91% of it is scatter that counterbalancing cannot cancel and ordering cannot explain.
Scatter averages down; the only question is what to spend to average it.

### The arithmetic that decides where to spend

Wall time for one comparison is

```
2 * (frames_per_arm / frames_per_second  +  runs_per_arm * cooldown)
```

The cooldown is charged **per run**, not per frame. So for a fixed number of frames per arm —
which is what sets the precision, *if* the scatter lives inside a run — the second term shrinks
as runs per arm falls. Fewer, longer runs would then be strictly cheaper for the same
precision. Concretely, closing 12.4% to under 10% needs about 1.5x the frames per arm:

| shape                  | executions | wall time |
|------------------------|-----------:|----------:|
| 23 runs x 1000 frames  |        368 |     183 m |
| 8 runs x 2830 frames   |        128 |     103 m |
| 5 runs x 4530 frames   |         80 |      87 m |

All three buy the same precision **if** the scatter is within-run. If it is between-run, only
the first works and it costs three hours.

Nothing in any log can settle it, because every run ever recorded is 1000 frames. That is the
experiment now in the app beside the cooldown one: it quadruples the frame count on half the
comparisons in ABBA order and compares the spread at each size. Read it with
`tools/analysis/frame_count_experiment.py`. A halving says within-run and the calibration gets
much cheaper; no change says between-run and it is genuinely expensive.

### What the 12.4% already permits

A floor is not a verdict on the device, it is the smallest difference the harness will call.
At 12.4% this Odin2 can still separate anything larger — and Turnip against the Qualcomm driver
measured +18.2%, +29.8%, +13.4% and +7.3% on run means. Three of those four clear it. The
measurement works today; what 12.4% blocks is the *ranking*, which needs to resolve differences
between two Turnip builds, and those are much smaller than the gap to a different vendor.

### An aside worth not over-reading

Frametime correlated with the reported temperature at r = -0.59: hotter runs were *faster*.
That is almost certainly the sensor lagging the work rather than heat making the GPU quick — a
fast run does more work per second and the reading taken afterwards is higher. Recorded because
it will look like a finding to the next person who plots it, and it is not one.

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
