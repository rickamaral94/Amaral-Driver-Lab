# Fase 3 — comparação estatística reproduzível

A Fase 3 não cria nem redefine workloads. Ela adiciona uma camada de inferência sobre os resultados brutos das séries já versionadas.

## Contrato

- `schema_version = 4`;
- `analysis_version = 1`;
- unidade amostral: uma rodada A/B completa, pareada por `round`;
- ordem AB/BA alternada continua obrigatória;
- estimador principal: mediana da melhora percentual pareada;
- melhora positiva sempre favorece o candidato, respeitando `lower_is_better`;
- intervalo de 95%: bootstrap percentil determinístico com 5.000 reamostragens;
- diagnóstico adicional: teste exato bilateral dos sinais, probabilidade de superioridade e correlação bisserial de postos pareada;
- mínimo para classificação: cinco pares válidos;
- margem prática padrão: ±3%.

## Classificação

| Classificação | Regra |
|---|---|
| `candidate_better` | limite inferior do IC95% acima de +3% |
| `candidate_worse` | limite superior do IC95% abaixo de −3% |
| `practically_equivalent` | IC95% inteiro dentro de −3% a +3% |
| `inconclusive` | IC cruza a margem ou contém efeitos conflitantes |
| `insufficient_samples` | menos de cinco pares válidos |
| `insufficient_data` | nenhum par A/B completo |

O teste de sinais é informativo e não substitui a política de classificação pelo intervalo e pela margem prática.

## Comparabilidade histórica

Não há quebra das séries históricas de workload. Permanecem inalterados:

- `vulkan_transfer_stress/v1` e `transfer_payload_gib_s`;
- `render_correctness_offscreen/v1`;
- os cinco workloads da Fase 2, todos em `workload_version = 1`;
- shaders, draws, dispatches, resoluções e métricas primárias.

O novo objeto `statistical_analysis` inicia uma série metodológica própria identificada por `analysis_version = 1`. Comparações inferenciais devem usar o mesmo `analysis_version`, margem prática, confiança e política de pareamento.

## Limitações

As fases internas de um único processo nativo não são tratadas como observações independentes. O bootstrap reamostra rodadas A/B completas, não frames, dispatches ou janelas térmicas. Com no máximo dez pares, intervalos podem permanecer largos. Não há correção de multiplicidade entre suítes/workloads diferentes, e nenhum resultado equivale a FPS ou ganho em jogos.
