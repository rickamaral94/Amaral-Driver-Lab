"""Server-side sanity checks on a submitted benchmark result.

None of this proves a result is genuine. The signing key would live in the APK, so
any signature the app produced could be produced by anyone who unpacked it, and
promising "verified results" on that basis would be a lie told with cryptography.

What is actually available is cheaper and honest: demand the raw series rather than
aggregates, check that the aggregates follow from the series, and reject numbers
that are not physically possible. A determined forger can still generate a
consistent fake. Someone editing a number to look better cannot.
"""

from __future__ import annotations

import statistics
from dataclasses import dataclass, field

# A frame that takes under 100 microseconds on this class of hardware, at these
# resolutions, is not a frame that was rendered.
MINIMUM_PLAUSIBLE_FRAMETIME_NS = 100_000

# Ten seconds per frame is past the workload's own fence timeout, so a series
# containing one did not come from this app.
MAXIMUM_PLAUSIBLE_FRAMETIME_NS = 10_000_000_000

# Real GPU frametimes are never perfectly constant. A series with zero spread was
# generated, not measured.
MINIMUM_RELATIVE_SPREAD = 1e-9

# How far a reported aggregate may sit from the value recomputed from the series.
SUMMARY_TOLERANCE = 1e-6


@dataclass
class Findings:
    """Reasons to refuse, and reasons to be careful."""

    rejections: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)

    @property
    def accepted(self) -> bool:
        return not self.rejections

    def reject(self, reason: str) -> None:
        self.rejections.append(reason)

    def warn(self, reason: str) -> None:
        self.warnings.append(reason)


def _percentile(sorted_values: list[float], q: float) -> float:
    """Linear interpolation, matching the app's Quantiles (numpy type 7)."""
    if not sorted_values:
        raise ValueError("empty series")
    if len(sorted_values) == 1:
        return sorted_values[0]
    position = q * (len(sorted_values) - 1)
    lower = int(position)
    upper = min(lower + 1, len(sorted_values) - 1)
    fraction = position - lower
    return sorted_values[lower] + fraction * (sorted_values[upper] - sorted_values[lower])


def check_execution(execution: dict, index: int) -> Findings:
    findings = Findings()
    label = f"execution {index} ({execution.get('arm', '?')}#{execution.get('runIndexWithinArm', '?')})"

    series = execution.get("frametimesNs") or []
    if not series:
        findings.reject(f"{label} has no frametime series. Aggregates alone cannot be re-checked.")
        return findings

    fastest, slowest = min(series), max(series)
    if fastest < MINIMUM_PLAUSIBLE_FRAMETIME_NS:
        findings.reject(
            f"{label} contains a frame of {fastest} ns, under the "
            f"{MINIMUM_PLAUSIBLE_FRAMETIME_NS} ns floor. That is not a rendered frame."
        )
    if slowest > MAXIMUM_PLAUSIBLE_FRAMETIME_NS:
        findings.reject(
            f"{label} contains a frame of {slowest} ns, past the workload's own "
            f"{MAXIMUM_PLAUSIBLE_FRAMETIME_NS} ns fence timeout. The app would have "
            "recorded a timeout instead."
        )

    median = statistics.median(series)
    if median > 0:
        spread = (slowest - fastest) / median
        if spread < MINIMUM_RELATIVE_SPREAD:
            findings.reject(
                f"{label} has zero variance across {len(series)} frames. Measured "
                "frametimes are never perfectly constant."
            )

    summary = execution.get("summary") or {}
    if summary:
        findings.rejections.extend(_check_summary(series, summary, label))

    declared = (execution.get("workload") or {}).get("frameCount")
    if declared is not None and declared != len(series):
        findings.reject(
            f"{label} declares {declared} frames and carries {len(series)}. The work "
            "is fixed by the workload, so these cannot differ."
        )

    return findings


def _check_summary(series: list[int], summary: dict, label: str) -> list[str]:
    """The aggregates must follow from the series that was published alongside them."""
    problems: list[str] = []
    values = sorted(float(v) for v in series)

    expected = {
        "frameCount": float(len(series)),
        "medianNs": _percentile(values, 0.5),
        "meanNs": statistics.fmean(values),
        "p95Ns": _percentile(values, 0.95),
        "p99Ns": _percentile(values, 0.99),
        "p999Ns": _percentile(values, 0.999),
        "totalDurationNs": float(sum(series)),
    }

    for key, want in expected.items():
        if key not in summary:
            continue
        got = float(summary[key])
        scale = max(abs(want), 1.0)
        if abs(got - want) / scale > SUMMARY_TOLERANCE:
            problems.append(
                f"{label} reports {key} = {got:.6g}, but the published series gives "
                f"{want:.6g}. The summary does not describe the data it was submitted with."
            )
    return problems


def check_report(report: dict) -> Findings:
    """Every plausibility rule, plus the eligibility gates from section 10."""
    findings = Findings()

    for index, execution in enumerate(report.get("executions") or []):
        result = check_execution(execution, index)
        findings.rejections.extend(result.rejections)
        findings.warnings.extend(result.warnings)

    # Driver identity: an unattributable result is worse than no result.
    for driver in report.get("drivers") or []:
        if not driver.get("systemDriver") and not driver.get("libraryChecksum"):
            findings.reject(
                f"Driver {driver.get('arm')} ({driver.get('label')}) has no library SHA-256. "
                "A result that cannot name the bytes that ran cannot be ingested."
            )
        if (driver.get("identity") or {}).get("driverId") == 0:
            findings.reject(
                f"Driver {driver.get('arm')} reports driverID 0, so the driver that ran "
                "could not be identified."
            )

    # Started while already throttling: the run measures the throttle, not the driver.
    for index, execution in enumerate(report.get("executions") or []):
        before = (execution.get("telemetryBefore") or {}).get("thermalStatus")
        if before not in (None, "NONE", "UNKNOWN"):
            findings.reject(
                f"Execution {index} started at thermal status {before}. The device was "
                "already being held back, so this measures the throttle."
            )
            break

    if (report.get("preflight") or {}).get("lowConfidence"):
        findings.reject(
            "The run started with blocking preflight issues overridden, so it describes a "
            "device state nobody else can reproduce. It is kept as a record and not ranked."
        )

    return findings


def rankable(report: dict) -> tuple[bool, str]:
    """Whether the result may appear in the leaderboard's ranked section."""
    null_test = report.get("nullTest")
    if not null_test:
        return False, "no null test has been run on this device"
    if not null_test.get("passed"):
        return False, "the null test did not pass: " + str(null_test.get("explanation", ""))
    if not (report.get("comparisons") or []):
        return False, "the run produced no comparison"
    if all(not c.get("trustworthy") for c in report["comparisons"]):
        return False, "every comparison is marked untrustworthy"
    if report.get("failures"):
        return False, (
            f"{len(report['failures'])} execution(s) failed, so the compatibility gate "
            "disqualifies this result from ranking. It still appears with the reason."
        )
    return True, ""
