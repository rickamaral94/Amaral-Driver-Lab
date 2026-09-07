#!/usr/bin/env python3
"""Appends an accepted report to data/results.jsonl and regenerates the leaderboard.

The stored row is a summary plus a pointer. The full payload, raw frametime series
and all, stays on the issue or the gist it came from, and every leaderboard row
links back to it — an entry nobody can re-check is not evidence.
"""

from __future__ import annotations

import argparse
import html
import json
import pathlib
from collections import defaultdict

import scoring

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
RESULTS = REPO_ROOT / "data" / "results.jsonl"
PAGES_DIR = REPO_ROOT / "docs" / "leaderboard"

# A result reproduced by an independent submission is worth more than one that is
# not, and that is the only strength claim this project can honestly make.
CORROBORATION_THRESHOLD = 2


def row_from_report(report: dict, issue_number: int, rankable: bool, reason: str) -> dict:
    drivers = report.get("drivers") or []
    headline = max(
        report.get("comparisons") or [],
        key=lambda c: abs(c.get("percentDifference", 0.0)),
        default=None,
    )
    null_test = report.get("nullTest") or {}

    return {
        "issue": issue_number,
        "schemaVersion": report["schemaVersion"],
        "appVersion": report.get("appVersion", ""),
        "generatedAtEpochMs": report.get("generatedAtEpochMs", 0),
        "device": {
            "model": report["device"]["model"],
            "soc": report["device"]["soc"],
            "androidRelease": report["device"]["androidRelease"],
        },
        "gpu": (drivers[0].get("identity") or {}).get("deviceName", "") if drivers else "",
        "drivers": [
            {
                "arm": d["arm"],
                "label": d["label"],
                "libraryChecksum": d["libraryChecksum"],
                "systemDriver": d["systemDriver"],
                "unverified": d["unverified"],
                "driverName": (d.get("identity") or {}).get("driverName", ""),
            }
            for d in drivers
        ],
        "headline": None
        if headline is None
        else {
            "workloadId": headline["workloadId"],
            "verdict": headline["verdict"],
            "percentDifference": headline["percentDifference"],
            "intervalLow": headline["speedupInterval"]["low"],
            "intervalHigh": headline["speedupInterval"]["high"],
            "pValue": headline["pValue"],
            "cliffsDelta": headline["cliffsDelta"],
            "noiseFloor": headline["noiseFloor"],
        },
        # Derived here from the published series rather than read from the payload, and
        # empty until an anchor is registered. See docs/RANKING.md.
        "scores": scoring.score_report(report, scoring.load_anchors()),
        "nullTestPassed": bool(null_test.get("passed")),
        "noiseFloor": null_test.get("appliedNoiseFloor"),
        "failures": len(report.get("failures") or []),
        "rankable": rankable,
        "unrankableReason": reason,
        "plainVerdict": report.get("plainVerdict", ""),
    }


def load_rows() -> list[dict]:
    if not RESULTS.is_file():
        return []
    rows = []
    for line in RESULTS.read_text().splitlines():
        line = line.strip()
        if line:
            rows.append(json.loads(line))
    return rows


def append_row(row: dict) -> list[dict]:
    rows = load_rows()
    # Re-running the workflow on an edited issue must update the row, not add one.
    rows = [r for r in rows if r.get("issue") != row["issue"]]
    rows.append(row)
    rows.sort(key=lambda r: (-r.get("generatedAtEpochMs", 0), r.get("issue", 0)))
    RESULTS.parent.mkdir(parents=True, exist_ok=True)
    RESULTS.write_text("".join(json.dumps(r, separators=(",", ":")) + "\n" for r in rows))
    return rows


def corroboration(rows: list[dict]) -> dict[tuple, int]:
    """Counts independent submissions of the same driver pair on the same GPU."""
    counts: dict[tuple, int] = defaultdict(int)
    for row in rows:
        key = (
            row.get("gpu", ""),
            tuple(sorted(d["libraryChecksum"] for d in row.get("drivers", []) if d["libraryChecksum"])),
        )
        if key[1]:
            counts[key] += 1
    return counts


def confidence_of(row: dict, counts: dict[tuple, int]) -> str:
    key = (
        row.get("gpu", ""),
        tuple(sorted(d["libraryChecksum"] for d in row.get("drivers", []) if d["libraryChecksum"])),
    )
    if not row.get("rankable"):
        return "unranked"
    if counts.get(key, 0) >= CORROBORATION_THRESHOLD:
        return "corroborated"
    return "single submission"


def score_tables(rows: list[dict]) -> str:
    """One table per (workload, anchor), because a score only means something inside one.

    Comparing a score measured against anchor X with one measured against anchor Y is the
    same mistake as comparing raw scores across devices, one level up.
    """
    grouped: dict[tuple, list[dict]] = defaultdict(list)
    for row in rows:
        for score in row.get("scores") or []:
            if not (row.get("rankable") and score.get("rankable")):
                continue
            grouped[(score["workloadId"], score["anchorId"])].append((score, row))

    if not grouped:
        return (
            '<p class="empty">No scores yet. Scoring needs a registered anchor driver, '
            'and <code>schema/anchors.json</code> is empty — see '
            '<a href="https://github.com/rickamaral94/Amaral-Driver-Lab/blob/main/docs/RANKING.md">'
            "docs/RANKING.md</a>.</p>"
        )

    sections = []
    for (workload_id, anchor_id), entries in sorted(grouped.items()):
        entries.sort(key=lambda pair: -pair[0]["score"])
        head = (
            "<tr><th>#</th><th>Driver</th><th>Score</th><th>95% interval</th>"
            "<th>Device</th><th>GPU</th><th>Source</th></tr>"
        )
        body = []
        for position, (score, row) in enumerate(entries, start=1):
            interval = (
                f"{score['low']} – {score['high']}" if score.get("low") is not None else "—"
            )
            label = html.escape(score["candidateLabel"])
            if score.get("tiesWithAnchor"):
                label += " <em>(ties with the anchor)</em>"
            body.append(
                "<tr>"
                + "".join(
                    f"<td>{c}</td>"
                    for c in [
                        position,
                        label,
                        f"<strong>{score['score']}</strong>",
                        interval,
                        html.escape(row["device"]["model"]),
                        html.escape(row.get("gpu", "")),
                        f'<a href="../../issues/{row["issue"]}">#{row["issue"]}</a>',
                    ]
                )
                + "</tr>"
            )
        sections.append(
            f"<h3>{html.escape(workload_id)} · against {html.escape(anchor_id)}</h3>"
            f'<div class="wrap"><table>{head}{"".join(body)}</table></div>'
        )
    return "".join(sections)


def render(rows: list[dict]) -> str:
    counts = corroboration(rows)
    ranked = [r for r in rows if r.get("rankable")]
    unranked = [r for r in rows if not r.get("rankable")]

    def table(entries: list[dict], show_reason: bool) -> str:
        if not entries:
            return "<p class=\"empty\">Nothing here yet.</p>"
        head = (
            "<tr><th>Device</th><th>GPU</th><th>Drivers</th><th>Workload</th>"
            "<th>Verdict</th><th>Difference</th><th>95% interval</th>"
            "<th>Noise floor</th><th>Confidence</th>"
            + ("<th>Why unranked</th>" if show_reason else "")
            + "<th>Source</th></tr>"
        )
        body = []
        for row in entries:
            headline = row.get("headline") or {}
            drivers = " vs ".join(
                f"{html.escape(d['label'])}{' <em>(unverified)</em>' if d['unverified'] else ''}"
                for d in row.get("drivers", [])
            )
            difference = (
                f"{headline['percentDifference']:+.1f}%" if headline.get("percentDifference") is not None else "—"
            )
            interval = (
                f"{headline['intervalLow']:.3f} – {headline['intervalHigh']:.3f}"
                if headline.get("intervalLow") is not None
                else "—"
            )
            floor = f"{row['noiseFloor'] * 100:.1f}%" if row.get("noiseFloor") is not None else "—"
            cells = [
                html.escape(f"{row['device']['model']} · Android {row['device']['androidRelease']}"),
                html.escape(row.get("gpu", "")),
                drivers,
                html.escape(headline.get("workloadId", "—")),
                html.escape(headline.get("verdict", "—")),
                difference,
                interval,
                floor,
                confidence_of(row, counts),
            ]
            if show_reason:
                cells.append(html.escape(row.get("unrankableReason", "")))
            cells.append(f'<a href="../../issues/{row["issue"]}">#{row["issue"]}</a>')
            body.append("<tr>" + "".join(f"<td>{c}</td>" for c in cells) + "</tr>")
        return f"<table>{head}{''.join(body)}</table>"

    return f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Amaral Driver Lab leaderboard</title>
<style>
  :root {{ color-scheme: light dark; --line: #8883; }}
  body {{ font: 15px/1.5 system-ui, sans-serif; margin: 0 auto; padding: 2rem 1rem; max-width: 72rem; }}
  h1 {{ font-size: 1.6rem; margin-bottom: .25rem; }}
  h2 {{ font-size: 1.1rem; margin-top: 2.5rem; }}
  h3 {{ font-size: .95rem; margin-top: 1.5rem; opacity: .85; }}
  p.lede {{ opacity: .8; max-width: 46rem; }}
  table {{ border-collapse: collapse; width: 100%; margin-top: .75rem; font-size: 14px; }}
  th, td {{ border-bottom: 1px solid var(--line); padding: .45rem .6rem; text-align: left; vertical-align: top; }}
  th {{ font-weight: 600; opacity: .75; }}
  .wrap {{ overflow-x: auto; }}
  .empty {{ opacity: .6; }}
  .note {{ border-left: 3px solid var(--line); padding-left: .9rem; opacity: .85; max-width: 46rem; }}
  input {{ padding: .4rem .6rem; font: inherit; width: 100%; max-width: 22rem; margin-top: 1rem; }}
</style>
</head>
<body>
<h1>Amaral Driver Lab leaderboard</h1>
<p class="lede">
  Vulkan driver benchmarks submitted from the app. Every row links to the issue it came
  from, where the raw frametime series live. Numbers you cannot re-check are not evidence.
</p>

<input id="filter" placeholder="Filter by device, GPU or driver…" aria-label="Filter results">

<h2>Scores</h2>
<p class="note">
  A score is a ratio against a pinned anchor driver, measured beside the candidate in the
  same thermal session: 1000 × median(anchor) / median(candidate). The anchor scores
  {scoring.PARITY}, so 1240 means 24% faster than it. The device cancels in the division, which is
  what makes two phones comparable — a raw score would rank the silicon bin and the cooling
  instead. A result whose interval contains {scoring.PARITY} ties with the anchor, and says so.
</p>
{score_tables(rows)}

<h2>Ranked</h2>
<p class="note">
  These passed the A/A null test, completed every workload, and started from a clean
  preflight. The noise floor is what that device demonstrated it can resolve — a
  difference smaller than it is reported as a tie, not as a win.
</p>
<div class="wrap">{table(ranked, show_reason=False)}</div>

<h2>Recorded, not ranked</h2>
<p class="note">
  Real runs that do not meet the bar for ranking. A driver that crashed appears here
  with the reason rather than with a position.
</p>
<div class="wrap">{table(unranked, show_reason=True)}</div>

<h2>About confidence</h2>
<p class="note">
  <strong>corroborated</strong> means the same driver pair was submitted independently at
  least {CORROBORATION_THRESHOLD} times on the same GPU. <strong>single submission</strong>
  means one person ran it once. Nothing here is cryptographically verified: any signature
  the app could produce, anyone who unpacked the APK could produce too, so this project
  does not claim verification it cannot deliver.
</p>

<script>
  const filter = document.getElementById('filter');
  filter.addEventListener('input', () => {{
    const needle = filter.value.toLowerCase();
    for (const row of document.querySelectorAll('tbody tr, table tr')) {{
      if (row.querySelector('th')) continue;
      row.hidden = needle !== '' && !row.textContent.toLowerCase().includes(needle);
    }}
  }});
</script>
</body>
</html>
"""


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", help="accepted report JSON to append")
    parser.add_argument("--issue", type=int)
    parser.add_argument("--result", help="validate.py's result.json")
    parser.add_argument("--rebuild-only", action="store_true")
    args = parser.parse_args()

    if args.rebuild_only:
        rows = load_rows()
    else:
        report = json.loads(pathlib.Path(args.report).read_text())
        verdict = json.loads(pathlib.Path(args.result).read_text())
        rows = append_row(
            row_from_report(
                report,
                issue_number=args.issue,
                rankable=verdict.get("rankable", False),
                reason=verdict.get("unrankableReason", ""),
            )
        )

    PAGES_DIR.mkdir(parents=True, exist_ok=True)
    (PAGES_DIR / "index.html").write_text(render(rows))
    print(f"leaderboard rebuilt with {len(rows)} row(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
