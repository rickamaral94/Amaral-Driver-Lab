# Auditoria histórica da versão dos workloads

> Relatório determinístico e somente leitura. Nenhuma Issue é alterada. `provável`
> nunca é promovido a observação runtime.

## Resumo

| Classificação | Resultados | Elegível para ranking/bisect |
|---|---:|---|
| `confirmed` | 0 | sim |
| `mismatch` | 0 | não |
| `undeterminable` | 12 | não |
| **Total** | **12** | — |

## Resultado por resultado

| Issue | Qualification | App | Perfil declarado | Workload declarado | Provavelmente executado | Observado no runtime | Estado |
|---:|---|---|---:|---:|---:|---:|---|
| [#16](https://github.com/rickamaral94/Amaral-Driver-Lab/issues/16) | `qualification-1785925938453` | `0.13.0-alpha7` | v4 | v1 | v1 | — | `undeterminable` |
| [#17](https://github.com/rickamaral94/Amaral-Driver-Lab/issues/17) | `qualification-1785926706147` | `0.13.0-alpha7` | v4 | v1 | v1 | — | `undeterminable` |
| [#18](https://github.com/rickamaral94/Amaral-Driver-Lab/issues/18) | `qualification-1785927615001` | `0.13.0-alpha7` | v4 | v1 | v1 | — | `undeterminable` |
| [#19](https://github.com/rickamaral94/Amaral-Driver-Lab/issues/19) | `qualification-1785938825112` | `0.13.0-alpha7` | v4 | v1 | v1 | — | `undeterminable` |
| [#20](https://github.com/rickamaral94/Amaral-Driver-Lab/issues/20) | `qualification-1785939545017` | `0.13.0-alpha7` | v4 | v1 | v1 | — | `undeterminable` |
| [#21](https://github.com/rickamaral94/Amaral-Driver-Lab/issues/21) | `qualification-1785939989641` | `0.13.0-alpha7` | v4 | v1 | v1 | — | `undeterminable` |
| [#22](https://github.com/rickamaral94/Amaral-Driver-Lab/issues/22) | `qualification-1785940481459` | `0.13.0-alpha7` | v4 | v1 | v1 | — | `undeterminable` |
| [#30](https://github.com/rickamaral94/Amaral-Driver-Lab/issues/30) | `qualification-1786215503223` | `0.13.0-alpha10` | v4 | v1 | v1 | — | `undeterminable` |
| [#37](https://github.com/rickamaral94/Amaral-Driver-Lab/issues/37) | `qualification-1786370514371` | `0.13.0-alpha12` | v5 | v1 | v1 | — | `undeterminable` |
| [#38](https://github.com/rickamaral94/Amaral-Driver-Lab/issues/38) | `qualification-1786407085541` | `0.13.0-alpha12` | v5 | v1 | v1 | — | `undeterminable` |
| [#41](https://github.com/rickamaral94/Amaral-Driver-Lab/issues/41) | `qualification-1786408543260` | `0.13.0-alpha12` | v5 | v1 | v1 | — | `undeterminable` |
| [#42](https://github.com/rickamaral94/Amaral-Driver-Lab/issues/42) | `qualification-1786427681704` | `0.13.0-alpha12` | v5 | v1 | v1 | — | `undeterminable` |

## Interpretação

- Perfis v4 e v5 declaravam workload v1. O código dessas versões também tinha v1 como
  versão corrente; por isso v1 é a versão **provavelmente** executada.
- O executor histórico escolhia a versão corrente em vez da versão imutável do passo.
  Portanto, a coincidência provável não prova o que cada processo nativo executou.
- As Issues publicadas não contêm `native.workload_version` por fase. Todos os resultados
  acima são `undeterminable` e ficam fora de ranking, dataset e regression bisect.
- A Issue #36 foi excluída porque é um snapshot `running`, não um resultado concluído.
- A partir do perfil v6, cada passo carrega `workload_version`; o runner recusa versões
  incompatíveis e o relatório exige igualdade com toda observação nativa.

Gerado por `tools/audit-workload-versions.py` a partir de
`tools/workload-version-audit-input.json`.
