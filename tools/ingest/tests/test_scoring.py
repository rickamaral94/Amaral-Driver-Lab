import copy
import json
import statistics

import pytest

import scoring

ANCHOR_SHA = "b" * 64
CANDIDATE_SHA = "a" * 64


@pytest.fixture
def anchors(tmp_path):
    path = tmp_path / "anchors.json"
    path.write_text(
        json.dumps(
            {
                "registryVersion": 1,
                "anchors": [
                    {"id": "turnip-v4", "libraryChecksum": ANCHOR_SHA, "displayName": "Turnip v4"}
                ],
            }
        )
    )
    return scoring.load_anchors(path)


def test_an_empty_registry_scores_nothing(report, tmp_path):
    path = tmp_path / "anchors.json"
    path.write_text(json.dumps({"registryVersion": 1, "anchors": []}))

    assert scoring.score_report(report, scoring.load_anchors(path)) == []


def test_an_anchor_without_a_full_sha_is_refused(tmp_path):
    path = tmp_path / "anchors.json"
    path.write_text(
        json.dumps({"registryVersion": 1, "anchors": [{"id": "x", "libraryChecksum": "abc"}]})
    )

    with pytest.raises(ValueError, match="one binary"):
        scoring.load_anchors(path)


def test_the_score_comes_from_the_series_not_the_payload(report, anchors):
    """The published ratio is ignored for the point estimate; the raw runs decide."""
    doc = copy.deepcopy(report)
    # A submitter claiming a huge win must not get one by editing the aggregate.
    for comparison in doc["comparisons"]:
        comparison["speedupOfA"] = 9.0

    score = scoring.score_report(doc, anchors)[0]

    anchor_runs = scoring._run_medians(doc, "B", "tiling_gmem/v1")
    candidate_runs = scoring._run_medians(doc, "A", "tiling_gmem/v1")
    expected = round(
        statistics.median(anchor_runs) / statistics.median(candidate_runs) * scoring.PARITY
    )
    assert score["score"] == expected
    assert score["score"] < 2000


def test_a_faster_candidate_scores_above_parity(report, anchors):
    # In the sample, arm A is 10.7% faster than arm B, and B is the anchor.
    score = scoring.score_report(report, anchors)[0]

    assert score["score"] > scoring.PARITY
    assert score["candidateLabel"] == "Turnip v3"
    assert score["anchorId"] == "turnip-v4"
    assert not score["tiesWithAnchor"]


def test_swapping_the_arms_does_not_move_the_score(report, anchors):
    """Which side the anchor landed on is a scheduling detail, not a result."""
    straight = scoring.score_report(report, anchors)[0]

    swapped = copy.deepcopy(report)
    for driver in swapped["drivers"]:
        driver["arm"] = "B" if driver["arm"] == "A" else "A"
    for execution in swapped["executions"]:
        execution["arm"] = "B" if execution["arm"] == "A" else "A"
    for comparison in swapped["comparisons"]:
        comparison["labelA"], comparison["labelB"] = comparison["labelB"], comparison["labelA"]
        interval = comparison["speedupInterval"]
        # The report's own convention: speedupOfA is medianB / medianA, so swapping the
        # arms inverts it and turns the interval inside out.
        comparison["speedupOfA"] = 1.0 / comparison["speedupOfA"]
        interval["low"], interval["high"] = 1.0 / interval["high"], 1.0 / interval["low"]

    result = scoring.score_report(swapped, anchors)[0]

    assert result["score"] == straight["score"]
    assert result["low"] == straight["low"]
    assert result["high"] == straight["high"]
    assert result["candidateLabel"] == straight["candidateLabel"]


def test_the_interval_keeps_low_below_high_when_inverted(report, anchors):
    swapped = copy.deepcopy(report)
    for driver in swapped["drivers"]:
        driver["arm"] = "B" if driver["arm"] == "A" else "A"
    for execution in swapped["executions"]:
        execution["arm"] = "B" if execution["arm"] == "A" else "A"
    for comparison in swapped["comparisons"]:
        interval = comparison["speedupInterval"]
        interval["low"], interval["high"] = 1.0 / interval["high"], 1.0 / interval["low"]

    score = scoring.score_report(swapped, anchors)[0]

    assert score["low"] < score["score"] < score["high"]


def test_a_comparison_of_two_candidates_has_no_scale(report, tmp_path):
    path = tmp_path / "anchors.json"
    path.write_text(
        json.dumps(
            {"registryVersion": 1, "anchors": [{"id": "other", "libraryChecksum": "f" * 64}]}
        )
    )

    assert scoring.score_report(report, scoring.load_anchors(path)) == []


def test_the_anchor_against_itself_has_nothing_to_place(report, anchors):
    doc = copy.deepcopy(report)
    for driver in doc["drivers"]:
        driver["libraryChecksum"] = ANCHOR_SHA

    assert scoring.score_report(doc, anchors) == []


def test_the_system_driver_can_never_be_an_anchor(report, tmp_path):
    """It is a different binary on every device, so a ratio against it is not portable."""
    doc = copy.deepcopy(report)
    doc["drivers"][1]["systemDriver"] = True
    path = tmp_path / "anchors.json"
    path.write_text(
        json.dumps(
            {
                "registryVersion": 1,
                "anchors": [{"id": "sys", "libraryChecksum": doc["drivers"][1]["libraryChecksum"]}],
            }
        )
    )

    assert scoring.score_report(doc, scoring.load_anchors(path)) == []


def test_an_untrustworthy_comparison_scores_but_does_not_place(report, anchors):
    doc = copy.deepcopy(report)
    for comparison in doc["comparisons"]:
        comparison["trustworthy"] = False

    score = scoring.score_report(doc, anchors)[0]

    assert score["score"] > 0
    assert not score["rankable"]


def test_a_workload_with_no_executions_is_skipped(report, anchors):
    doc = copy.deepcopy(report)
    doc["executions"] = [e for e in doc["executions"] if e["arm"] != "B"]

    assert scoring.score_report(doc, anchors) == []


def test_the_page_says_why_there_are_no_scores_when_no_anchor_is_registered(report):
    """An empty table with no explanation reads as "nobody submitted", which is wrong."""
    import leaderboard

    row = leaderboard.row_from_report(report, issue_number=1, rankable=True, reason="")
    page = leaderboard.render([row])

    assert "No scores yet" in page
    assert "anchors.json" in page


def test_a_scored_row_reaches_the_page_grouped_by_workload_and_anchor(report, anchors):
    import leaderboard

    row = leaderboard.row_from_report(report, issue_number=1, rankable=True, reason="")
    row["scores"] = scoring.score_report(report, anchors)
    page = leaderboard.render([row])

    assert "against turnip-v4" in page
    assert "tiling_gmem/v1" in page
    assert str(row["scores"][0]["score"]) in page


def test_an_unrankable_row_contributes_no_score_line(report, anchors):
    import leaderboard

    row = leaderboard.row_from_report(report, issue_number=1, rankable=False, reason="null test")
    row["scores"] = scoring.score_report(report, anchors)

    assert "No scores yet" in leaderboard.render([row])
