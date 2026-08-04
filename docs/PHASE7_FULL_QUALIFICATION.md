# Fase 7 · Turnip Full Qualification v1

A Fase 7 cria o modo recomendado do Amaral Driver Lab: selecionar um driver, executar todo o protocolo oficial e receber uma recomendação clara acompanhada de um pacote completo de diagnóstico.

## Contratos

- `schema_version = 8` para novas suítes;
- `qualification_schema_version = 1`;
- `qualification_profile_id = turnip_full_qualification`;
- `qualification_profile_version = 1`;
- `qualification_report_version = 1`;
- `qualification_score_version = 1`;
- `diagnostic_bundle_version = 1`.

O perfil é imutável e protegido por `profile_sha256`. Alterar ordem, rounds, tempos, pesos, traces, tolerâncias ou qualquer etapa exige uma nova versão do perfil.

## Protocolo oficial

1. correção visual inicial;
2. compilação de shaders;
3. render pass, tiling e GMEM;
4. compute aritmético;
5. transferência fill/copy;
6. frametime da cena estável;
7. trace gráfico/compute/barreiras;
8. trace de dependências compute;
9. sustentação térmica;
10. correção visual após carga.

Cada suíte continua sendo A/B entre o driver do sistema e o candidato, com processo novo e ordem AB/BA alternada. As cargas e métricas das fases anteriores não foram redefinidas.

## Preflight e validade

Antes de executar, o aplicativo captura bateria, temperatura, status térmico, memória e identidade do aparelho. Condições muito inadequadas podem bloquear ranking, mas o usuário ainda pode executar para coletar diagnóstico.

Ao final, uma segunda captura verifica mudança de estado de carregamento, temperatura e status térmico. Mudanças severas entram no gate de validade.

## Índice de Qualificação

O índice é um resumo de produto versionado, não uma nova métrica física. Cada melhoria pareada é normalizada para uma escala 0–100 e ponderada:

| Área | Peso |
|---|---:|
| Render pass / tiling / GMEM | 20% |
| Frametime estável | 20% |
| Trace misto | 15% |
| Trace compute | 10% |
| Compilação de shaders | 10% |
| Sustentação térmica | 10% |
| Compute | 10% |
| Transferência | 5% |

O gate de compatibilidade sempre tem precedência. Crash, timeout, corrupção visual, trace mismatch, não determinismo, etapa ausente ou cobertura estatística insuficiente impedem que o candidato seja recomendado, mesmo quando alguma métrica de performance é melhor.

A decisão usa margem prática de ±3%:

- acima de +3%: candidato recomendado, desde que o gate passe;
- abaixo de -3%: driver do sistema recomendado;
- entre -3% e +3%: empate técnico;
- qualquer bloqueio: candidato não recomendado ou resultado inválido.

## Pausa e retomada

O estado de cada etapa é persistido. Uma etapa deixada como `running` após encerramento volta para `pending`, mantém `attempt_count` e é repetida. Resultados parciais nunca viram aprovação.

## Log Full

A qualificação produz `diagnostic-bundle.zip` com:

- `manifest.json` com SHA-256 de cada entrada;
- `profile.json` e `preflight.json`;
- `final-environment.json` e comparação ambiental;
- `report.json` e `summary.html`;
- todas as suítes, fases, PNGs, traces e dados brutos das etapas concluídas.

O aplicativo não tem acesso irrestrito ao logcat global ou tombstones do Android. Para diagnóstico externo, use:

```text
tools/capture-full-diagnostics.sh
tools/capture-full-diagnostics.ps1
```

## Ranking local

Relatórios elegíveis são ranqueados somente quando possuem o mesmo `profile_sha256` e a mesma chave de hardware. Perfis, aparelhos ou versões incompatíveis não são misturados.

## Comparabilidade histórica

Não há quebra nas séries das Fases 1–6. O schema 8 adiciona `phase7_contract` e `qualification_context`. Workloads, versões, shaders, traces, métricas e `analysis_version = 1` permanecem inalterados.

Full Qualification v1 somente compara com Full Qualification v1 e o mesmo hash de perfil. A futura inclusão de cenas animadas ocorrerá em uma nova versão de workload e em Full Qualification v2.
