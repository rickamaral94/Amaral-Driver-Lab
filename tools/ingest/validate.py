#!/usr/bin/env python3
"""Validates a submitted benchmark report and says, in one comment, what happened.

Run by .github/workflows/ingest-report.yml. Writes a markdown verdict and a machine
readable summary, and exits non-zero when the submission is refused.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys

try:
    import jsonschema
except ImportError:  # pragma: no cover - the workflow installs it
    jsonschema = None

from extract import ExtractionError, extract_json
from plausibility import check_report, rankable

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
SCHEMA_DIR = REPO_ROOT / "schema"
SUPPORTED_SCHEMA_VERSIONS = (1,)


def load_schema(version: int) -> dict:
    path = SCHEMA_DIR / f"result-v{version}.schema.json"
    if not path.is_file():
        raise FileNotFoundError(f"no schema for version {version}")
    return json.loads(path.read_text())


def validate_schema(report: dict) -> list[str]:
    version = report.get("schemaVersion")
    if version is None:
        return ["The payload has no schemaVersion, so there is no way to know how to read it."]
    if version not in SUPPORTED_SCHEMA_VERSIONS:
        return [
            f"Schema version {version} is not one this repository knows how to read "
            f"(supported: {', '.join(map(str, SUPPORTED_SCHEMA_VERSIONS))}). Guessing at an "
            "unknown schema is how a leaderboard fills with numbers that mean something else."
        ]

    validator = jsonschema.Draft202012Validator(load_schema(version))
    errors = sorted(validator.iter_errors(report), key=lambda e: list(e.path))
    return [
        f"`{'.'.join(str(p) for p in error.path) or '(root)'}`: {error.message}"
        for error in errors[:25]
    ]


def build_comment(status: str, detail: dict) -> str:
    lines: list[str] = []
    if status == "accepted":
        lines.append("### Accepted")
        lines.append("")
        lines.append(detail["plainVerdict"])
        lines.append("")
        if detail["rankable"]:
            lines.append("This result is eligible for the ranked leaderboard.")
        else:
            lines.append(f"Recorded, but **not ranked**: {detail['unrankableReason']}")
        if detail["warnings"]:
            lines.append("")
            lines.append("Worth noting:")
            lines.extend(f"- {w}" for w in detail["warnings"])
    else:
        lines.append("### Not accepted")
        lines.append("")
        lines.append("This submission was not ingested, for these reasons:")
        lines.append("")
        lines.extend(f"- {reason}" for reason in detail["rejections"])
        lines.append("")
        lines.append(
            "Nothing here is a judgement about the driver. Re-run it with the app and "
            "publish from the result screen, which fills this form in for you."
        )

    lines.append("")
    lines.append(
        "_Checked against `schema/result-v1.schema.json`. These are plausibility checks, "
        "not proof of authenticity — see the antifraud note in the README._"
    )
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--issue-body-file", required=True)
    parser.add_argument("--issue-number", required=True)
    parser.add_argument("--output-dir", required=True)
    args = parser.parse_args()

    output = pathlib.Path(args.output_dir)
    output.mkdir(parents=True, exist_ok=True)

    body = pathlib.Path(args.issue_body_file).read_text(encoding="utf-8")

    def refuse(reasons: list[str]) -> int:
        detail = {"rejections": reasons}
        (output / "comment.md").write_text(build_comment("rejected", detail))
        (output / "result.json").write_text(
            json.dumps({"status": "rejected", "reasons": reasons}, indent=2)
        )
        for reason in reasons:
            print(f"rejected: {reason}", file=sys.stderr)
        return 1

    try:
        report = extract_json(body)
    except ExtractionError as error:
        return refuse([str(error)])

    schema_errors = validate_schema(report)
    if schema_errors:
        return refuse(["The payload does not match the schema:"] + schema_errors)

    findings = check_report(report)
    if not findings.accepted:
        return refuse(findings.rejections)

    is_rankable, reason = rankable(report)
    detail = {
        "plainVerdict": report.get("plainVerdict", ""),
        "rankable": is_rankable,
        "unrankableReason": reason,
        "warnings": findings.warnings,
    }
    (output / "comment.md").write_text(build_comment("accepted", detail))
    (output / "report.json").write_text(json.dumps(report, separators=(",", ":")))
    (output / "result.json").write_text(
        json.dumps(
            {
                "status": "accepted",
                "rankable": is_rankable,
                "unrankableReason": reason,
                "labels": derive_labels(report, is_rankable),
                "issue": int(args.issue_number),
            },
            indent=2,
        )
    )
    print(f"accepted issue #{args.issue_number}, rankable={is_rankable}")
    return 0


def derive_labels(report: dict, is_rankable: bool) -> list[str]:
    labels = ["benchmark-report", f"schema/v{report['schemaVersion']}"]
    drivers = report.get("drivers") or []
    if drivers:
        device_name = (drivers[0].get("identity") or {}).get("deviceName", "")
        slug = "".join(c if c.isalnum() else "-" for c in device_name.lower().replace("(tm)", " "))
        slug = "-".join(part for part in slug.split("-") if part)
        if slug:
            labels.append(f"gpu/{slug}")
    labels.append("ranked" if is_rankable else "unranked")
    if any(d.get("unverified") for d in drivers):
        labels.append("unverified-build")
    return labels


if __name__ == "__main__":
    sys.exit(main())
