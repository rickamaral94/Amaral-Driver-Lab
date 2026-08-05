# Fase 5 — Versioned Vulkan Trace Replay

## Objetivo

A Fase 5 adiciona um workload de **command trace Vulkan próprio, determinístico e versionado**. O objetivo é repetir a mesma sequência de comandos em processos isolados com o driver do sistema e com um pacote Turnip, preservando simultaneamente:

- correção da saída;
- tempo de replay;
- catálogo de falhas;
- identidade do trace e do driver;
- comparabilidade histórica explícita.

O formato não importa capturas de jogos, RenderDoc ou gfxreconstruct. Essa limitação é intencional: o contrato v1 precisa ser pequeno, auditável e executável sem root em aparelhos Android arm64.

## Contratos

- `schema_version = 6`;
- `workload_id = vulkan_command_trace_replay`;
- `workload_version = 1`;
- `trace_format_version = 1`;
- `trace_analysis_version = 1`;
- métrica primária: `median_replay_ms`;
- direção: menor é melhor;
- unidade estatística: rodada A/B completa;
- correção: SHA-256 exato da saída binária de cada par.

A análise de performance da Fase 3 continua em `analysis_version = 1`. A Fase 5 apenas adiciona um gate de correção antes de aceitar o veredito estatístico.

## Traces embutidos

### `mixed_graphics_compute_barrier/v1`

Sequência fixa:

1. restauração do buffer de seed;
2. barreira transferência → compute;
3. render pass offscreen de 320 × 180;
4. bind de pipeline gráfico e 64 draws;
5. transição de color attachment para leitura de transferência;
6. bind de pipeline compute inteiro;
7. quatro dispatches com dependências explícitas;
8. barreira compute → transferência;
9. cópia da imagem e do buffer para readback.

O clear e a saída dos draws são pretos de propósito. Assim, diferenças legítimas de cobertura de borda não criam falsos mismatches; os comandos gráficos ainda são gravados e executados. A parte compute usa somente aritmética inteira e fornece a carga de correção determinística.

### `compute_dependency_chain/v1`

Sequência fixa:

1. restauração do seed;
2. barreira transferência → compute;
3. doze dispatches inteiros;
4. barreira de dependência entre cada dispatch;
5. barreira compute → transferência;
6. cópia para readback.

## Evidência por fase

Cada fase bem-sucedida preserva:

```json
{
  "evidence": {
    "kind": "versioned_vulkan_trace_output",
    "relative_path": "phase-01-system-r1.trace.bin",
    "sha256_output": "<64 hex>",
    "output_size_bytes": 492800,
    "output_format": "RGBA8_UNORM+UINT32",
    "trace_id": "mixed_graphics_compute_barrier",
    "trace_version": 1,
    "preview_png": "phase-01-system-r1.trace.png"
  }
}
```

O `.trace.bin` é a saída canônica usada no hash. O PNG é somente uma visualização da região gráfica e não substitui o binário.

## Gate de correção

Em A/B, cada rodada compara o hash do sistema com o hash do candidato. O gate falha quando:

- algum par possui hashes diferentes;
- um mesmo braço produz mais de um hash entre rodadas;
- há fase ausente, crash, timeout, `VK_ERROR_DEVICE_LOST` ou erro de validação.

Quando o gate falha, o app pode continuar mostrando as medições brutas, mas o veredito final nunca declara ganho de performance.

Vereditos específicos:

- `failed_trace_replay_execution`;
- `failed_trace_nondeterminism`;
- `failed_trace_output_mismatch`;
- `insufficient_trace_reference`;
- `completed_single_driver_trace_replay`;
- ou um veredito estatístico da Fase 3 quando a correção foi aprovada.

## Comparabilidade histórica

A Fase 5 inaugura uma série nova. Permanecem inalterados:

- `vulkan_transfer_stress/v1`;
- `render_correctness_offscreen/v1`;
- os cinco workloads da Fase 2, todos versão 1;
- `analysis_version = 1`;
- contratos da Fase 4.

Compare trace replays somente quando coincidirem:

- hardware;
- `workload_id` e `workload_version`;
- `trace_id`, `trace_version` e `trace_format_version`;
- hash canônico da configuração;
- tempos de warm-up e medição;
- versão de análise.

## Limitação da métrica

`median_replay_ms` mede somente os comandos gravados no trace próprio. Não inclui lógica do jogo, CPU do emulador, I/O, áudio, rede, compilação dinâmica, apresentação em tela ou sincronização do compositor. O resultado não é FPS e não garante compatibilidade com jogos reais.
