from pathlib import Path
import base64, gzip, json

root = Path(__file__).resolve().parents[2]
payload_dir = root / ".github" / "phase4_payload"
encoded = "".join(path.read_text(encoding="utf-8").strip() for path in sorted(payload_dir.glob("phase4_payload_*.txt")))
files = json.loads(gzip.decompress(base64.b64decode(encoded)).decode("utf-8"))
for relative, content in files.items():
    target = root / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")
print(f"Materialized {len(files)} Phase 4 files")
