"""Runs the two scripts the workflow runs, on a real payload, end to end."""

import json
import pathlib
import subprocess
import sys

INGEST = pathlib.Path(__file__).resolve().parents[1]
REPO_ROOT = INGEST.parents[1]


def run(script, *args):
    return subprocess.run(
        [sys.executable, str(INGEST / script), *args],
        capture_output=True,
        text=True,
        cwd=str(REPO_ROOT),
    )


def test_a_real_report_is_accepted_and_lands_on_the_leaderboard(report, tmp_path, monkeypatch):
    body = tmp_path / "body.md"
    body.write_text("Ran this on the Odin2.\n\n```json\n" + json.dumps(report) + "\n```\n")
    out = tmp_path / "out"

    result = run("validate.py", "--issue-body-file", str(body), "--issue-number", "42",
                 "--output-dir", str(out))
    assert result.returncode == 0, result.stderr

    verdict = json.loads((out / "result.json").read_text())
    assert verdict["status"] == "accepted"
    assert verdict["rankable"] is True
    assert "ranked" in verdict["labels"]

    comment = (out / "comment.md").read_text()
    assert "Accepted" in comment
    assert "eligible for the ranked leaderboard" in comment

    # The leaderboard writes into the repo, so point it at a scratch copy.
    monkeypatch.setenv("PYTHONPATH", str(INGEST))
    sys.path.insert(0, str(INGEST))
    import leaderboard

    monkeypatch.setattr(leaderboard, "RESULTS", tmp_path / "results.jsonl")
    monkeypatch.setattr(leaderboard, "PAGES_DIR", tmp_path / "pages")

    row = leaderboard.row_from_report(report, issue_number=42, rankable=True, reason="")
    rows = leaderboard.append_row(row)
    (tmp_path / "pages").mkdir(exist_ok=True)
    (tmp_path / "pages" / "index.html").write_text(leaderboard.render(rows))

    html = (tmp_path / "pages" / "index.html").read_text()
    assert "Odin2 Portal" in html
    assert "Adreno (TM) 740" in html
    assert 'href="../../issues/42"' in html
    assert "single submission" in html


def test_a_forged_summary_is_rejected_with_a_readable_reason(report, tmp_path):
    forged = json.loads(json.dumps(report))
    forged["executions"][0]["summary"]["medianNs"] *= 0.7

    body = tmp_path / "body.md"
    body.write_text("```json\n" + json.dumps(forged) + "\n```")
    out = tmp_path / "out"

    result = run("validate.py", "--issue-body-file", str(body), "--issue-number", "43",
                 "--output-dir", str(out))
    assert result.returncode == 1

    comment = (out / "comment.md").read_text()
    assert "Not accepted" in comment
    assert "does not describe the data" in comment
    assert "Nothing here is a judgement about the driver" in comment


def test_an_unknown_schema_version_is_refused(report, tmp_path):
    doc = json.loads(json.dumps(report))
    doc["schemaVersion"] = 99

    body = tmp_path / "body.md"
    body.write_text("```json\n" + json.dumps(doc) + "\n```")
    out = tmp_path / "out"

    result = run("validate.py", "--issue-body-file", str(body), "--issue-number", "44",
                 "--output-dir", str(out))
    assert result.returncode == 1
    assert "not one this repository knows how to read" in (out / "comment.md").read_text()


def test_an_extra_field_is_refused_rather_than_ignored(report, tmp_path):
    doc = json.loads(json.dumps(report))
    doc["executions"][0]["frametimesNS"] = [1, 2, 3]  # a plausible typo

    body = tmp_path / "body.md"
    body.write_text("```json\n" + json.dumps(doc) + "\n```")
    out = tmp_path / "out"

    result = run("validate.py", "--issue-body-file", str(body), "--issue-number", "45",
                 "--output-dir", str(out))
    assert result.returncode == 1
    assert "does not match the schema" in (out / "comment.md").read_text()


def test_a_report_with_no_json_gets_a_useful_message(tmp_path):
    body = tmp_path / "body.md"
    body.write_text("It felt faster, honestly.")
    out = tmp_path / "out"

    result = run("validate.py", "--issue-body-file", str(body), "--issue-number", "46",
                 "--output-dir", str(out))
    assert result.returncode == 1
    assert "Paste the app's export" in (out / "comment.md").read_text()


def test_an_unranked_report_is_accepted_and_labelled(unranked_report, tmp_path):
    body = tmp_path / "body.md"
    body.write_text("```json\n" + json.dumps(unranked_report) + "\n```")
    out = tmp_path / "out"

    result = run("validate.py", "--issue-body-file", str(body), "--issue-number", "47",
                 "--output-dir", str(out))
    assert result.returncode == 0

    verdict = json.loads((out / "result.json").read_text())
    assert verdict["rankable"] is False
    assert "unranked" in verdict["labels"]
    assert "not ranked" in (out / "comment.md").read_text()
