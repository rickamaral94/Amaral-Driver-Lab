import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

import pytest

REPO_ROOT = pathlib.Path(__file__).resolve().parents[3]
SAMPLE = REPO_ROOT / "core-report" / "build" / "schema-samples" / "sample-ranked.json"
SAMPLE_UNRANKED = REPO_ROOT / "core-report" / "build" / "schema-samples" / "sample-unranked.json"


@pytest.fixture
def report():
    """A real payload produced by the app's own serializer, not one written by hand."""
    if not SAMPLE.is_file():
        pytest.skip("run :core-report:testDebugUnitTest to generate the sample payloads")
    return json.loads(SAMPLE.read_text())


@pytest.fixture
def unranked_report():
    if not SAMPLE_UNRANKED.is_file():
        pytest.skip("run :core-report:testDebugUnitTest to generate the sample payloads")
    return json.loads(SAMPLE_UNRANKED.read_text())
