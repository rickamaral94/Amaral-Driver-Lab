# Esquema de resultado v1

Cada suíte grava `files/runs/suite-<timestamp>/suite.json`. Os arquivos `phase-*.json` preservam a evidência bruta de cada processo.

| Campo | Significado |
|---|---|
| `schema_version` | Versão do esquema, atualmente `1` |
| `suite_id` | Identificador local imutável da suíte |
| `mode` | `system_only`, `candidate_only` ou `ab_system_vs_candidate` |
| `order_policy` | Política de ordem; A/B usa `AB/BA alternating` |
| `candidate.sha256` | SHA-256 do ZIP importado |
| `phases[]` | Telemetria e resultado nativo de cada processo |
| `native.gpu_name` | Nome reportado por `VkPhysicalDeviceProperties` |
| `native.driver_version_raw` | Valor bruto reportado pelo Vulkan |
| `native.gpu_timestamps_used` | Se o tempo veio de timestamp da GPU |
| `native.transfer_payload_gib_s` | Métrica comparativa do workload v1 |
| `summary.*` | Mediana, média, CV, falhas e delta A/B |
| `validity_warnings[]` | Condições que pedem repetição ou cautela |

## Regras de comparação

Uma comparação é adequada para triagem quando há pelo menos três amostras por braço, zero falhas, mesma versão do APK/workload e nenhuma diferença térmica inicial relevante. O número não prova ganho em jogos: ele identifica regressões, instabilidade e diferenças no caminho Vulkan de transferência.

## Evolução compatível

Novos workloads devem ganhar nomes e métricas próprios, sem redefinir `transfer_payload_gib_s`. Mudanças de comandos, tamanho de buffer ou cálculo exigem uma nova versão de workload (`v2`) para não misturar séries históricas.
