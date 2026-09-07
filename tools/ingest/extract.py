"""Pulls the result JSON out of an issue body.

The app fills the issue form, but people file these by hand too, so the extractor
accepts what a person would plausibly paste: a fenced block, a bare object, or a
Gist link. What it will not do is repair malformed JSON — a report that had to be
guessed at is not a report.
"""

from __future__ import annotations

import json
import re
import urllib.request

FENCED = re.compile(r"```(?:json)?\s*(\{.*?\})\s*```", re.DOTALL)
GIST_URL = re.compile(r"https://gist\.github\.com/[A-Za-z0-9_-]+/([0-9a-f]+)")


class ExtractionError(Exception):
    pass


def find_gist_id(body: str) -> str | None:
    match = GIST_URL.search(body or "")
    return match.group(1) if match else None


def fetch_gist(gist_id: str, opener=urllib.request.urlopen) -> str:
    """Reads the first JSON file in a Gist through the public API."""
    with opener(f"https://api.github.com/gists/{gist_id}") as response:
        payload = json.load(response)
    files = payload.get("files") or {}
    for name, meta in files.items():
        if name.endswith(".json"):
            if meta.get("truncated"):
                with opener(meta["raw_url"]) as raw:
                    return raw.read().decode("utf-8")
            return meta.get("content", "")
    raise ExtractionError(f"gist {gist_id} contains no .json file")


def extract_json(body: str, opener=urllib.request.urlopen) -> dict:
    """
    @raise ExtractionError when nothing parseable is present. Rejecting is the right
    outcome: a partially understood payload would be ingested with fields missing.
    """
    body = body or ""

    for candidate in FENCED.findall(body):
        try:
            return json.loads(candidate)
        except json.JSONDecodeError:
            continue

    gist_id = find_gist_id(body)
    if gist_id:
        try:
            return json.loads(fetch_gist(gist_id, opener))
        except json.JSONDecodeError as error:
            raise ExtractionError(f"the linked gist is not valid JSON: {error}") from error

    stripped = body.strip()
    if stripped.startswith("{"):
        try:
            return json.loads(stripped)
        except json.JSONDecodeError as error:
            raise ExtractionError(f"the issue body is not valid JSON: {error}") from error

    raise ExtractionError(
        "no result JSON found. Paste the app's export in a ```json block, or link the "
        "gist the app created for you."
    )
