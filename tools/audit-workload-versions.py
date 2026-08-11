#!/usr/bin/env python3
"""Generate the deterministic, read-only historical workload-version audit."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


POLICY_VERSION = 1


def classify(item: dict) -> str:
    declared = item.get("declared_workload_version")
    runtime = item.get("runtime_workload_version_observed")
    if runtime is None:
        return "undeterminable"
    return "confirmed" if runtime == declared else "mismatch"


def render(payload: dict) -> str:
    if payload.get("workload_version_audit_policy_version") != POLICY_VERSION:
        raise ValueError("workload_version_audit_policy_version incompatível")
    rows = []
    counts = {"confirmed": 0, "mismatch": 0, "undeterminable": 0}
    for item in sorted(payload["results"], key=lambda value: int(value["issue"])):
        status = classify(item)
        counts[status] += 1
        rows.append((item, status))

    output = [
        "# Auditoria histórica da versão dos workloads",
        "",
        "> Relatório determinístico e somente leitura. Nenhuma Issue é alterada. `provável`",
        "> nunca é promovido a observação runtime.",
        "",
        "## Resumo",
        "",
        "| Classificação | Resultados | Elegível para ranking/bisect |",
        "|---|---:|---|",
        f"| `confirmed` | {counts['confirmed']} | sim |",
        f"| `mismatch` | {counts['mismatch']} | não |",
        f"| `undeterminable` | {counts['undeterminable']} | não |",
        f"| **Total** | **{sum(counts.values())}** | — |",
        "",
        "## Resultado por resultado",
        "",
        "| Issue | Qualification | App | Perfil declarado | Workload declarado | Provavelmente executado | Observado no runtime | Estado |",
        "|---:|---|---|---:|---:|---:|---:|---|",
    ]
    for item, status in rows:
        runtime = item.get("runtime_workload_version_observed")
        output.append(
            f"| [#{item['issue']}](https://github.com/rickamaral94/Amaral-Driver-Lab/issues/{item['issue']}) "
            f"| `{item['qualification_id']}` | `{item['app_version']}` "
            f"| v{item['declared_profile_version']} | v{item['declared_workload_version']} "
            f"| v{item['probably_executed_workload_version']} "
            f"| {'—' if runtime is None else f'v{runtime}'} | `{status}` |"
        )

    output.extend([
        "",
        "## Interpretação",
        "",
        "- Perfis v4 e v5 declaravam workload v1. O código dessas versões também tinha v1 como",
        "  versão corrente; por isso v1 é a versão **provavelmente** executada.",
        "- O executor histórico escolhia a versão corrente em vez da versão imutável do passo.",
        "  Portanto, a coincidência provável não prova o que cada processo nativo executou.",
        "- As Issues publicadas não contêm `native.workload_version` por fase. Todos os resultados",
        "  acima são `undeterminable` e ficam fora de ranking, dataset e regression bisect.",
        "- A Issue #36 foi excluída porque é um snapshot `running`, não um resultado concluído.",
        "- A partir do perfil v6, cada passo carrega `workload_version`; o runner recusa versões",
        "  incompatíveis e o relatório exige igualdade com toda observação nativa.",
        "",
        "Gerado por `tools/audit-workload-versions.py` a partir de",
        "`tools/workload-version-audit-input.json`.",
        "",
    ])
    return "\n".join(output)


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path,
                        default=root / "tools/workload-version-audit-input.json")
    parser.add_argument("--output", type=Path,
                        default=root / "docs/WORKLOAD_VERSION_AUDIT.md")
    args = parser.parse_args()
    payload = json.loads(args.input.read_text(encoding="utf-8"))
    content = render(payload)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(content, encoding="utf-8")


if __name__ == "__main__":
    main()
