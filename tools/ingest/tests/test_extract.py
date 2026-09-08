import json

import pytest
from extract import ExtractionError, extract_json, find_gist_id


def test_reads_a_fenced_block():
    body = "Some text\n\n```json\n{\"schemaVersion\": 1}\n```\n\nmore text"
    assert extract_json(body) == {"schemaVersion": 1}


def test_reads_a_bare_object():
    assert extract_json('{"schemaVersion": 1}') == {"schemaVersion": 1}


def test_skips_a_malformed_block_and_finds_the_good_one():
    body = "```json\n{not json}\n```\n\n```json\n{\"schemaVersion\": 1}\n```"
    assert extract_json(body) == {"schemaVersion": 1}


def test_finds_a_gist_id():
    body = "The result was too large: https://gist.github.com/rickamaral94/abc123def456"
    assert find_gist_id(body) == "abc123def456"


class _JsonResponse:
    """Stands in for urlopen so the extractor can be tested without a network."""

    def __init__(self, payload):
        self.payload = payload

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def read(self):
        return json.dumps(self.payload).encode()


def test_fetches_from_a_gist():
    payload = {
        "files": {
            "result.json": {"truncated": False, "content": json.dumps({"schemaVersion": 1})}
        }
    }
    requested = []

    def opener(url):
        requested.append(url)
        return _JsonResponse(payload)

    body = "The result was too large: https://gist.github.com/rickamaral94/abc123"
    assert extract_json(body, opener=opener) == {"schemaVersion": 1}
    assert "gists/abc123" in requested[0]


def test_nothing_parseable_is_an_error_not_an_empty_report():
    with pytest.raises(ExtractionError) as error:
        extract_json("I ran the benchmark and it was faster, trust me.")
    assert "no result JSON found" in str(error.value)


def test_malformed_json_is_never_repaired():
    with pytest.raises(ExtractionError):
        extract_json('{"schemaVersion": 1,}')
