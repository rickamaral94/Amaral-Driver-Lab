from __future__ import annotations

import base64
import gzip
import hashlib
import subprocess
from pathlib import Path

PARTS = [
    ("part-00.b64", "d01989119e3b1fd7f7ca958a1ef5b27380731f5f10cc49ede79fb6d5cc2b47e9"),
    ("part-01.b64", "ca11be21425f4138f9114c7041383674d58c4087158c76e4ae14e874576b40a5"),
    ("part-02.b64", "66d860020a429eb1cc7ff0ef8311c95f7e6e67de362ec7559b123f7a559300fe"),
    ("part-03a-1.b64", "e2d71756c714bc548bdf633db276da3fb8a9f4667891e9656dd9e1d6f52c02e1"),
    ("part-03a-2.b64", "ea9b93a982673e5cf3a0f1fd4bdb83aeb07a2dec03fd3ebded26d6bec29b7ace"),
    ("part-03b.b64", "ab8b6af37f9f5f8153a3259116b3e496e2e27eb182a0afe1941b053258796e42"),
    ("part-04a.b64", "4c9c5beac5c018212a5add0a5f30c5914db9723e7ab4399349292b4205b39f70"),
    ("part-04b.b64", "0e89f1b7c20e99bf43db3c7e61c584ec5015922cc67e4972115d31fae2eb64e2"),
    ("part-05a.b64", "e181a0decdce7585ea707607460fffa9a8226c18b023633295362ada78872019"),
    ("part-05b.b64", "99f475805a43f55d2ac8da4249a9a26997b9453d4ad94d0d49a258372f22376a"),
]
EXPECTED_BASE64_SHA256 = "911b736a90afa9c19f71bb32397c75b29140e1062ae6a348f4f4d1ec6563a56c"
EXPECTED_GZIP_SHA256 = "619411522a054765a1d25b80d1d35ac2b4ac29596738508ce05ec60da24de23d"
EXPECTED_PATCH_SHA256 = "de8ae61c8bd74d453bb528eab0ad6fa3dc85b2cd96b92cf9868d1ca100977d6c"
ROOT = Path(".github/phase8-patch")
PATCH_PATH = Path(".github/phase8.patch")
ALLOWED_PREFIXES = ("README.md", "app/", "docs/")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


encoded_parts: list[bytes] = []
for filename, expected in PARTS:
    path = ROOT / filename
    if not path.is_file():
        raise SystemExit(f"Missing Phase 8 patch part: {path}")
    data = path.read_bytes()
    actual = sha256(data)
    if actual != expected:
        raise SystemExit(f"Phase 8 part digest mismatch for {filename}: {actual}")
    encoded_parts.append(data)

encoded = b"".join(encoded_parts)
actual_encoded = sha256(encoded)
if actual_encoded != EXPECTED_BASE64_SHA256:
    raise SystemExit(f"Phase 8 aggregate base64 digest mismatch: {actual_encoded}")

try:
    compressed = base64.b64decode(encoded, validate=True)
except Exception as error:
    raise SystemExit(f"Invalid Phase 8 base64: {error}") from error
actual_gzip = sha256(compressed)
if actual_gzip != EXPECTED_GZIP_SHA256:
    raise SystemExit(f"Phase 8 gzip digest mismatch: {actual_gzip}")

try:
    patch = gzip.decompress(compressed)
except Exception as error:
    raise SystemExit(f"Invalid Phase 8 gzip: {error}") from error
actual_patch = sha256(patch)
if actual_patch != EXPECTED_PATCH_SHA256:
    raise SystemExit(f"Phase 8 patch digest mismatch: {actual_patch}")

text = patch.decode("utf-8")
for line in text.splitlines():
    if not (line.startswith("+++ b/") or line.startswith("--- a/")):
        continue
    path = line[6:]
    if path == "/dev/null":
        continue
    if ".." in Path(path).parts or not path.startswith(ALLOWED_PREFIXES):
        raise SystemExit(f"Unexpected path in Phase 8 patch: {path}")

PATCH_PATH.write_bytes(patch)
subprocess.run(["git", "apply", "--check", str(PATCH_PATH)], check=True)
subprocess.run(["git", "apply", str(PATCH_PATH)], check=True)
print(
    "Materialized Phase 8 visible Vulkan scenes "
    f"({len(patch)} patch bytes, sha256 {EXPECTED_PATCH_SHA256})"
)
