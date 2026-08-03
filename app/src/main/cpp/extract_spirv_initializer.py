#!/usr/bin/env python3
"""Extract only the uint32 initializer from glslc -mfmt=c output."""

from pathlib import Path
import sys


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit("usage: extract_spirv_initializer.py <input.c> <output.inc>")

    source = Path(sys.argv[1]).read_text(encoding="utf-8")
    start = source.find("{")
    end = source.rfind("}")
    if start < 0 or end <= start:
        raise SystemExit("glslc output did not contain a C initializer")

    initializer = source[start : end + 1].strip()
    if "0x07230203" not in initializer.lower():
        raise SystemExit("glslc output did not contain the SPIR-V magic word")

    destination = Path(sys.argv[2])
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(initializer + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
