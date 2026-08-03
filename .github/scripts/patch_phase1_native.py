#!/usr/bin/env python3
"""Apply narrowly-scoped, idempotent corrections to the phase-one renderer."""

from pathlib import Path
import re


SOURCE = Path("app/src/main/cpp/render_correctness.cpp")


def replace_once(text: str, old: str, new: str, label: str) -> tuple[str, bool]:
    count = text.count(old)
    if count == 1:
        return text.replace(old, new, 1), True
    if count == 0 and new in text:
        return text, False
    raise SystemExit(f"{label}: expected exactly one old or already-corrected form, found {count}")


def replace_call(text: str, old: str, new: str) -> tuple[str, bool]:
    pattern = re.compile(rf"\b{re.escape(old)}\(")
    matches = list(pattern.finditer(text))
    if matches:
        return pattern.sub(new + "(", text), True
    if re.search(rf"\b{re.escape(new)}\(", text):
        return text, False
    raise SystemExit(f"Vulkan dispatch call {old} was not found")


def main() -> int:
    text = SOURCE.read_text(encoding="utf-8")
    changed = False

    text, did_change = replace_once(
        text,
        '        case VK_DRIVER_ID_MESA_HONEYKRISP: return "MESA_HONEYKRISP";\n',
        "",
        "NDK-compatible VkDriverId mapping",
    )
    changed |= did_change

    text, did_change = replace_once(
        text,
        '             << "\\\",\\\"features\":{";',
        '             << "\\\",\\\"features\\\":{";',
        "features JSON object",
    )
    changed |= did_change

    for old, new in (
        ("getDeviceQueue", "vkGetDeviceQueue"),
        ("queueSubmit", "vkQueueSubmit"),
        ("queueWaitIdle", "vkQueueWaitIdle"),
        ("mapMemory", "vkMapMemory"),
        ("invalidateMappedMemoryRanges", "vkInvalidateMappedMemoryRanges"),
        ("unmapMemory", "vkUnmapMemory"),
    ):
        text, did_change = replace_call(text, old, new)
        changed |= did_change

    if changed:
        SOURCE.write_text(text, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
