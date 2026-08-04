from __future__ import annotations

import base64
import gzip
import hashlib
import subprocess
from pathlib import Path

PARTS = [
    ("part-00.b64", "a84d3f3ddef5ce63fd167b1ae67e14009df8305f26ac110a66328b0eea9c77ec"),
    ("part-01.b64", "9a47b3de235dd40bf3f3ad7892d1db0dbb67d16c9a02f59e6eca055b24399617"),
    ("part-02.b64", "0509ec4a6d46905ff1caae3723b05dbefe090114efb7ede76137a905506d4454"),
    ("part-03-0.b64", "d8f6df41652c929b66347f80023aec62787079197818b553876709716db7182f"),
    ("part-03-1.b64", "7f17a16316b2ae75e6c203a9fd6cd7daeff4231161eb61d7776d3e95f6ff7a34"),
    ("part-03-2.b64", "504b9fe0d9aa97993b630e1b2e34eaca14f59c23e20d1cc8d1b3ba5049a17c65"),
    ("part-04.b64", "e3772a45b8a0016f34cb36d9432c081096c6527d6b5293b4e24bd0898fd77596"),
    ("part-05.b64", "dd05d8d6cd4e8d24a48c20cb21a39980c94a4290b6fdd4269fdc72c821f06f3c"),
]
EXPECTED_BASE64_SHA256 = "5bfc0f848e607018100eb29faa732a824e53930148d7241dc45c62532493baae"
EXPECTED_GZIP_SHA256 = "7fc3c3d0d6320298078c72b939c9ede5e48c041948279275d58ececa92ba867c"
ROOT = Path(".github/phase7-patch")
PATCH_PATH = Path(".github/phase7.patch")
ALLOWED_PREFIXES = ("README.md", "app/", "docs/", "tools/")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


encoded_parts: list[bytes] = []
for filename, expected in PARTS:
    path = ROOT / filename
    if not path.is_file():
        raise SystemExit(f"Missing Phase 7 patch part: {path}")
    data = path.read_bytes()
    actual = sha256(data)
    if actual != expected:
        raise SystemExit(f"Phase 7 part digest mismatch for {filename}: {actual}")
    encoded_parts.append(data)

encoded = b"".join(encoded_parts)
if sha256(encoded) != EXPECTED_BASE64_SHA256:
    raise SystemExit("Phase 7 aggregate base64 digest mismatch")

compressed = base64.b64decode(encoded, validate=True)
if sha256(compressed) != EXPECTED_GZIP_SHA256:
    raise SystemExit("Phase 7 gzip digest mismatch")
patch = gzip.decompress(compressed)

text = patch.decode("utf-8")
for line in text.splitlines():
    if not (line.startswith("+++ b/") or line.startswith("--- a/")):
        continue
    path = line[6:]
    if path == "/dev/null":
        continue
    if ".." in Path(path).parts or not path.startswith(ALLOWED_PREFIXES):
        raise SystemExit(f"Unexpected path in Phase 7 patch: {path}")

PATCH_PATH.write_bytes(patch)
subprocess.run(["git", "apply", "--check", str(PATCH_PATH)], check=True)
subprocess.run(["git", "apply", str(PATCH_PATH)], check=True)
print(f"Materialized Phase 7 ({len(patch)} bytes)")
