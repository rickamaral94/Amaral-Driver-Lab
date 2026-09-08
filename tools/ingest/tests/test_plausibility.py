import copy

import plausibility


def test_a_real_payload_passes(report):
    findings = plausibility.check_report(report)
    assert findings.accepted, findings.rejections


def test_a_real_payload_is_rankable(report):
    ok, reason = plausibility.rankable(report)
    assert ok, reason


def test_missing_series_is_refused(report):
    doc = copy.deepcopy(report)
    doc["executions"][0]["frametimesNs"] = []
    findings = plausibility.check_report(doc)
    assert not findings.accepted
    assert "cannot be re-checked" in findings.rejections[0]


def test_impossibly_fast_frame_is_refused(report):
    doc = copy.deepcopy(report)
    doc["executions"][0]["frametimesNs"][3] = 500
    assert not plausibility.check_report(doc).accepted


def test_frame_past_the_fence_timeout_is_refused(report):
    doc = copy.deepcopy(report)
    doc["executions"][0]["frametimesNs"][3] = 20_000_000_000
    assert any("fence timeout" in r for r in plausibility.check_report(doc).rejections)


def test_zero_variance_is_refused(report):
    doc = copy.deepcopy(report)
    count = len(doc["executions"][0]["frametimesNs"])
    doc["executions"][0]["frametimesNs"] = [16_000_000] * count
    assert any("zero variance" in r for r in plausibility.check_report(doc).rejections)


def test_a_summary_that_does_not_match_its_series_is_refused(report):
    """The exact edit someone would make to look faster: change the median only."""
    doc = copy.deepcopy(report)
    doc["executions"][0]["summary"]["medianNs"] = doc["executions"][0]["summary"]["medianNs"] * 0.8
    rejections = plausibility.check_report(doc).rejections
    assert any("does not describe the data" in r for r in rejections)


def test_editing_the_series_without_the_summary_is_also_refused(report):
    doc = copy.deepcopy(report)
    doc["executions"][0]["frametimesNs"] = [
        int(v * 0.8) for v in doc["executions"][0]["frametimesNs"]
    ]
    assert not plausibility.check_report(doc).accepted


def test_declared_frame_count_must_match_the_series(report):
    doc = copy.deepcopy(report)
    doc["executions"][0]["frametimesNs"].pop()
    assert not plausibility.check_report(doc).accepted


def test_a_driver_with_no_checksum_is_refused(report):
    doc = copy.deepcopy(report)
    doc["drivers"][0]["libraryChecksum"] = ""
    assert any("SHA-256" in r for r in plausibility.check_report(doc).rejections)


def test_an_unidentifiable_driver_is_refused(report):
    doc = copy.deepcopy(report)
    doc["drivers"][0]["identity"]["driverId"] = 0
    assert any("driverID 0" in r for r in plausibility.check_report(doc).rejections)


def test_a_run_that_started_while_throttling_is_refused(report):
    doc = copy.deepcopy(report)
    doc["executions"][0]["telemetryBefore"]["thermalStatus"] = "MODERATE"
    assert any("already being held back" in r for r in plausibility.check_report(doc).rejections)


def test_an_overridden_preflight_is_refused(report):
    doc = copy.deepcopy(report)
    doc["preflight"]["lowConfidence"] = True
    assert any("nobody else can reproduce" in r for r in plausibility.check_report(doc).rejections)


def test_without_a_null_test_the_result_is_kept_but_not_ranked(unranked_report):
    findings = plausibility.check_report(unranked_report)
    assert findings.accepted, findings.rejections

    ok, reason = plausibility.rankable(unranked_report)
    assert not ok
    assert "null test" in reason


def test_a_failed_execution_disqualifies_from_ranking(report):
    doc = copy.deepcopy(report)
    doc["failures"] = [
        {
            "slot": 0, "arm": "A", "label": "Turnip v3",
            "workloadId": "tiling_gmem/v1", "stage": "DEVICE_LOST", "message": "device lost",
        }
    ]
    ok, reason = plausibility.rankable(doc)
    assert not ok
    assert "compatibility gate" in reason
