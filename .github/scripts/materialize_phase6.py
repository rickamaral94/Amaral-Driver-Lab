from __future__ import annotations

import hashlib
import subprocess
from pathlib import Path

EXPECTED_SHA256 = "e0cbe95d97c61e2c124660117d23251626581b02a57cf66ef1209e53415f6e70"
PATCH_DIR = Path(".github/phase6-patch")
PATCH_FILE = Path(".github/phase6.patch")
EXPECTED_PARTS = [PATCH_DIR / f"part-{index:02d}.patch" for index in range(1, 9)]

missing = [str(path) for path in EXPECTED_PARTS if not path.is_file()]
if missing:
    raise SystemExit("Missing Phase 6 patch parts: " + ", ".join(missing))

payload = b"".join(path.read_bytes() for path in EXPECTED_PARTS)
digest = hashlib.sha256(payload).hexdigest()
if digest != EXPECTED_SHA256:
    raise SystemExit(f"Phase 6 patch SHA-256 mismatch: {digest}")

PATCH_FILE.write_bytes(payload)
subprocess.run(["git", "apply", "--check", str(PATCH_FILE)], check=True)
subprocess.run(["git", "apply", str(PATCH_FILE)], check=True)
print(f"Materialized Phase 6 patch ({len(payload)} bytes, {digest})")
