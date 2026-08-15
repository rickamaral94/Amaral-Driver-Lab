#!/usr/bin/env python3
"""Generate a deterministic, read-only audit from sanitized Issue evidence."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


POLICY_VERSION = 1
QUALCOMM_VENDOR = re.compile(r"\bQualcomm\s+(?:Proprietary|Technologies(?:,?\s*Inc\.?)?)\b", re.I)
TURNIP_IDENTITY = re.compile(r"\b(?:turnip|freedreno|VK_DRIVER_ID_MESA_TURNIP|MESA_TURNIP)\b", re.I)
BLOB_VERSION = re.compile(r"(?<![0-9.])\d{3}\.\d{3}\.\d{1,3}(?![0-9.])")
RUNTIME_FIELD = re.compile(r"(?:Driver(?:\s+Name)?|Using GPU|\[Driver\]\s*Device)\s*:", re.I)


def canonicalize_published(value: str) -> str:
    if QUALCOMM_VENDOR.search(value):
        return "qualcomm_blob"
    if TURNIP_IDENTITY.search(value):
        return "turnip"
    if value.strip().lower() in {"", "unknown"}:
        return "unknown"
    return "other"


def resolve(evidence: list[str]) -> tuple[str, str, list[str]]:
    qualcomm = [line for line in evidence if QUALCOMM_VENDOR.search(line)]
    turnip = [line for line in evidence if RUNTIME_FIELD.search(line) and TURNIP_IDENTITY.search(line)]
    blob_versions = [line for line in evidence if BLOB_VERSION.search(line)]
    cited = sorted(set(qualcomm + turnip + blob_versions))
    if qualcomm:
        return "qualcomm_blob", "disputed" if turnip else "runtime_confirmed", cited
    if turnip:
        return "turnip", "runtime_confirmed", cited
    if blob_versions:
        return "unknown", "disputed", cited
    return "unknown", "inferred", sorted(set(evidence))


def classification(published: str, resolved: str) -> str:
    if resolved == "unknown":
        return "não verificável"
    return "correto" if canonicalize_published(published) == resolved else "errado"


def render(payload: dict) -> str:
    if payload.get("driver_identity_policy_version") != POLICY_VERSION:
        raise ValueError("driver_identity_policy_version incompatível")
    rows = []
    counts = {"correto": 0, "errado": 0, "não verificável": 0}
    for item in sorted(payload["issues"], key=lambda value: int(value["issue"])):
        resolved, confidence, evidence = resolve(list(item.get("evidence", [])))
        status = classification(item.get("published_driver", "Unknown"), resolved)
        counts[status] += 1
        rows.append((item, resolved, confidence, status, evidence))

    output = [
        "# Auditoria histórica da identidade do driver",
        "",
        "> Relatório determinístico e somente leitura. Ele reprocessa apenas evidências já",
        "> sanitizadas e publicadas; nenhuma Issue é alterada ou republicada.",
        "",
        "## Resumo",
        "",
        "| Classificação | Issues |",
        "|---|---:|",
        f"| Rótulo correto | {counts['correto']} |",
        f"| Rótulo errado | {counts['errado']} |",
        f"| Não verificável | {counts['não verificável']} |",
        f"| **Total** | **{sum(counts.values())}** |",
        "",
        f"Política aplicada: `driver_identity_policy_version = {POLICY_VERSION}`.",
        "",
        "## Divergências",
        "",
        "| Issue | Rótulo publicado | Identidade recalculada | Confiança | Classificação |",
        "|---:|---|---|---|---|",
    ]
    for item, resolved, confidence, status, _ in rows:
        published = str(item.get("published_driver", "Unknown")).replace("|", "\\|")
        output.append(
            f"| [#{item['issue']}](https://github.com/rickamaral94/Amaral-Driver-Lab/issues/{item['issue']}) "
            f"| {published} | `{resolved}` | `{confidence}` | **{status}** |"
        )

    output.extend(["", "## Evidências sanitizadas usadas", ""])
    for item, resolved, confidence, status, evidence in rows:
        output.extend([
            f"### Issue #{item['issue']}",
            "",
            f"Resultado: `{resolved}` / `{confidence}` / **{status}**.",
            "",
        ])
        if evidence:
            output.extend(f"- `{line.replace('`', chr(39))}`" for line in evidence)
        else:
            output.append("- Nenhuma evidência de identidade verificável.")
        output.append("")

    output.extend([
        "## Limites",
        "",
        "- A auditoria usa somente os trechos sanitizados preservados nas Issues; linhas omitidas não são inferidas.",
        "- Versão no formato do blob sem vendor explícito gera `unknown` / `disputed`, nunca veto Qualcomm.",
        "- `unknown`, `disputed` e relatórios v1 `unaudited` ficam fora de agregações por driver.",
        "- O comando apenas escreve este Markdown; ele não usa a API de escrita do GitHub.",
        "",
        "Gerado por `tools/audit-driver-identities.py` a partir de",
        "`tools/driver-identity-audit-input.json`.",
        "",
    ])
    return "\n".join(output)


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, default=root / "tools/driver-identity-audit-input.json")
    parser.add_argument("--output", type=Path, default=root / "docs/DRIVER_IDENTITY_AUDIT.md")
    args = parser.parse_args()
    payload = json.loads(args.input.read_text(encoding="utf-8"))
    content = render(payload)
    args.output.write_text(content, encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
