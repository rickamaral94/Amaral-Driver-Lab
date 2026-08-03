from pathlib import Path
import base64
import gzip
import hashlib
import json

EXPECTED_SHA256 = "5cdb043e97fcd0795cc30724327f6c12e4d8a11af2429314082b6aa1f0fc17ea"
root = Path(__file__).resolve().parents[2]
payload_dir = root / ".github" / "phase5_payload"
encoded = "".join(
    path.read_text(encoding="utf-8").strip()
    for path in sorted(payload_dir.glob("phase5_payload_*.txt"))
)
actual_sha256 = hashlib.sha256(encoded.encode("ascii")).hexdigest()
if actual_sha256 != EXPECTED_SHA256:
    raise SystemExit(
        f"Phase 5 payload digest mismatch: expected {EXPECTED_SHA256}, got {actual_sha256}"
    )
files = json.loads(gzip.decompress(base64.b64decode(encoded)).decode("utf-8"))
for relative, content in files.items():
    target = root / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")
print(f"Materialized {len(files)} Phase 5 files")
