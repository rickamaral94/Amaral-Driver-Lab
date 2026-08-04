# Fase 6 · Campanhas automatizadas de regressão

A Fase 6 organiza várias suítes A/B existentes em uma campanha auditável. Ela não cria um novo workload e não altera métricas, shaders, traces ou regras estatísticas das Fases 1–5.

## Contratos

- `schema_version = 7` para suítes executadas dentro de campanha;
- `campaign_schema_version = 1`;
- `campaign_scheduler_version = 1`;
- `campaign_summary_version = 1`;
- política de ordem `rotating_serpentine_v1`;
- máximo de 8 drivers, 8 workloads/traces e 64 jobs por campanha.

Cada job é uma suíte `ab_system_vs_candidate` completa. A ordem AB/BA dentro da suíte continua sendo controlada pelo `RunCoordinator`; a Fase 6 controla somente a ordem entre candidatos e workloads.

## Plano imutável

Cada campanha fica em:

```text
files/campaigns/campaign-<timestamp>/campaign.json
```

O arquivo contém um bloco `plan` imutável e `plan_sha256`, calculado com a serialização JSON canônica já usada no projeto. O plano registra:

- drivers e SHA-256 dos ZIPs;
- workloads, versões e trace IDs;
- rodadas, warm-up, medição, cooldown e tolerâncias;
- ordem completa dos jobs;
- posição térmica de cada candidato em cada grupo.

Alterar qualquer item do plano invalida o hash e impede a retomada.

## Ordem termicamente balanceada

`rotating_serpentine_v1` usa uma rotação cíclica dos drivers para cada workload. Em um ciclo com quantidade de workloads igual à quantidade de drivers, cada candidato ocupa cada posição térmica uma vez. Ciclos seguintes invertem a direção da rotação.

Isso reduz viés de ordem, mas não torna temperatura, bateria ou frequência constantes. O cooldown configurável ocorre entre suítes completas, não entre braços A/B da mesma suíte.

## Retomada após interrupção

O estado mutável fica em `execution.jobs[]`:

- `pending`;
- `running`;
- `completed`;
- `failed`;
- `skipped`.

Ao reabrir uma campanha, qualquer job deixado como `running` volta para `pending`. Seu `attempt_count` permanece registrado e `recovery_count` é incrementado. Um job interrompido nunca é convertido em aprovação ou resultado numérico.

## Vínculo com suite.json

Suítes iniciadas pela Fase 6 recebem campos aditivos:

```json
{
  "schema_version": 7,
  "phase6_contract": {
    "campaign_schema_version": 1,
    "campaign_scheduler_version": 1,
    "campaign_summary_version": 1
  },
  "campaign_context": {
    "campaign_id": "campaign-1700000000000",
    "job_id": "job-001",
    "job_ordinal": 1,
    "job_count": 12,
    "scheduler_version": 1,
    "order_policy": "rotating_serpentine_v1",
    "thermal_position": 1,
    "plan_sha256": "<64 hex>"
  }
}
```

Suítes manuais continuam válidas e gravam `phase6_contract` e `campaign_context` como `null`.

## Resumo e rankings

Ao terminar, `campaign.summary` separa resultados pela chave histórica já definida na Fase 4:

- hardware;
- `workload_id` e `workload_version`;
- `analysis_version`;
- hash canônico de `workload_config`.

Traces diferentes ficam em grupos diferentes porque sua definição faz parte de `workload_config`. Cada grupo pode gerar seu próprio ranking. A campanha grava explicitamente:

```json
{
  "cross_workload_score_available": false,
  "cross_workload_winner": null
}
```

Não existe média de compute, frametime, compilação, correção e trace replay. Unidades e objetivos incompatíveis nunca são transformados em uma nota única.

## Falhas bloqueantes

- falha ao carregar um ZIP;
- crash, timeout ou erro Vulkan;
- render mismatch;
- trace mismatch ou não determinismo;
- suite concluída sem arquivo válido;
- plano ou hash inválido.

Jobs falhos permanecem no manifesto e não recebem valor artificial. Suítes com validade bloqueante aparecem no grupo, mas são excluídas do ranking pela mesma política da Fase 4.

## Comparabilidade histórica

Não há quebra nas séries anteriores. Permanecem inalterados:

- `vulkan_transfer_stress/v1`;
- `render_correctness_offscreen/v1`;
- os cinco workloads da Fase 2, todos v1;
- `analysis_version = 1`;
- catálogo, diff, ranking, bisect e envelope público da Fase 4;
- `vulkan_command_trace_replay/v1` e os dois traces v1.

O incremento para `schema_version = 7` apenas adiciona contexto de campanha. Uma suíte schema 7 pode ser comparada com schema 1–6 quando a chave histórica completa for idêntica.

## Limitações

Campanhas ainda executam cargas sintéticas locais. A rotação reduz, mas não elimina, aquecimento, variação de bateria, processos em segundo plano e mudanças de frequência. Retomar uma campanha em outro estado térmico pode ampliar a incerteza. Nenhum ranking representa FPS ou garante compatibilidade em jogos e emuladores.
