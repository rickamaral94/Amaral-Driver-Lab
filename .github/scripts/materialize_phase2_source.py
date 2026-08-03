#!/usr/bin/env python3
"""Materialize the reviewable Phase 2 C++ source from deterministic gzip payload chunks."""

from __future__ import annotations

import base64
import gzip
import hashlib
from pathlib import Path

PAYLOAD_DIR = Path(".github/phase2_payload")
OUTPUT = Path("app/src/main/cpp/phase2_workloads.cpp")
EXPECTED_SHA256 = "d9df8381332ab410837ad7c0b049338e57ebd98460be999b621d3fd8759ccb6a"


def main() -> int:
    chunks = sorted(PAYLOAD_DIR.glob("phase2_payload_*.txt"))
    if len(chunks) != 8:
        raise SystemExit(f"expected 8 payload chunks, found {len(chunks)}")
    encoded = "".join(path.read_text(encoding="ascii").strip() for path in chunks)
    source = gzip.decompress(base64.b64decode(encoded, validate=True))
    actual = hashlib.sha256(source).hexdigest()
    if actual != EXPECTED_SHA256:
        raise SystemExit(f"Phase 2 source SHA-256 mismatch: {actual}")
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_bytes(source)
    print(f"materialized {OUTPUT} ({len(source)} bytes, sha256={actual})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
