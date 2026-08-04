# Esquema de resultado

Cada suíte grava `files/runs/suite-<timestamp>/suite.json`. Cada processo isolado grava um `phase-*.json`; workloads de correção também preservam um `phase-*.png` lossless como evidência visual.

## Versão atual: `schema_version = 7`

A versão 7 é uma evolução **aditiva e compatível** das versões anteriores. Leitores antigos podem continuar consumindo os campos do workload de transferência. Leitores novos devem selecionar séries por `workload_id` **e** `workload_version`, nunca somente pelo nome da métrica.

| Campo | Significado |
|---|---|
| `schema_version` | Versão do formato do resultado; atualmente `7` |
| `suite_id` | Identificador local imutável da suíte |
| `app_version` | Versão do APK que gerou o resultado |
| `mode` | `system_only`, `candidate_only` ou `ab_system_vs_candidate` |
| `order_policy` | Política de ordem; A/B usa `AB/BA alternating` |
| `workload_id` | Identidade estável do workload, sem versão embutida |
| `workload_version` | Versão metodológica do workload |
| `workload` | Nome de compatibilidade, como `vulkan_transfer_stress_v1` |
| `workload_config` | Parâmetros que afetam a execução ou o julgamento |
| `metric_limitations` | Frase obrigatória dizendo o que o resultado não prova |
| `candidate.sha256` | SHA-256 do ZIP importado |
| `phases[]` | Telemetria e resultado bruto de cada processo isolado |
| `summary` | Resumo específico do workload |
| `render_correctness` | Comparação tolerante da evidência; `null` para outros workloads |
| `capability_diff` | Diferenças Vulkan sistema × candidato; `null` sem dois braços válidos |
| `failure_catalog[]` | Crashes, timeouts, erros Vulkan, validation errors e mismatches |
| `verdict` | Veredito explícito da suíte |
| `validity_warnings[]` | Condições que pedem repetição ou cautela |

## Contrato do workload legado de transferência

```json
{
  "workload_id": "vulkan_transfer_stress",
  "workload_version": 1,
  "workload": "vulkan_transfer_stress_v1",
  "native": {
    "transfer_payload_gib_s": 42.0
  }
}
```

`transfer_payload_gib_s` **não foi redefinido**. O workload v1 continua usando os mesmos comandos `fill/copy`, tamanhos de buffer adaptativos de 8–32 MiB e o mesmo cálculo. Sua série histórica permanece comparável quando APK, aparelho, driver, parâmetros e condições também forem equivalentes.

A métrica mede somente a carga sintética fill/copy. Ela não representa largura de banda física da VRAM e não prova ganho em jogos.

## `render_correctness_offscreen` versão 1

O workload renderiza uma cena determinística de 256 × 256 pixels em `VK_FORMAT_R8G8B8A8_UNORM`, usando geometria, shaders SPIR-V, clear color e ordem de draws fixos. Não há dependência de relógio, tempo transcorrido ou execução multithread da cena.

### Configuração versionada

```json
{
  "workload_id": "render_correctness_offscreen",
  "workload_version": 1,
  "workload_config": {
    "image_width": 256,
    "image_height": 256,
    "pixel_tolerance": 2,
    "block_size_px": 16,
    "minimum_block_match_percent": 99.0,
    "maximum_divergent_blocks": 8
  }
}
```

- `pixel_tolerance`: diferença absoluta máxima aceita em cada canal RGBA, de `0` a `255`.
- `block_size_px`: lado do bloco usado para distinguir ruído localizado de alteração estrutural.
- `minimum_block_match_percent`: percentual mínimo de pixels compatíveis para o bloco não ser divergente.
- `maximum_divergent_blocks`: quantidade máxima de blocos divergentes permitida para aprovação.

O SHA-256 é calculado sobre os bytes RGBA exatos e serve para integridade e detecção de não determinismo. **O hash sozinho não decide aprovação**, pois implementações corretas podem arredondar valores de forma ligeiramente diferente.

### Evidência por fase

```json
{
  "evidence": {
    "kind": "render_preview_png",
    "relative_path": "phase-01-system-r1.png",
    "sha256_rgba": "<64 hex>",
    "width": 256,
    "height": 256,
    "format": "RGBA8_UNORM",
    "png_size_bytes": 12345
  }
}
```

`relative_path` sempre aponta para um arquivo dentro da própria pasta da suíte. O RGBA temporário é removido depois da conversão lossless para PNG.

### Comparação de correção

```json
{
  "render_correctness": {
    "comparison_available": true,
    "comparison_count": 3,
    "pixel_match_percent": 99.9984,
    "maximum_divergent_block_count": 0,
    "passed": true,
    "comparisons": [
      {
        "round": 1,
        "pixel_match_percent": 99.9984,
        "divergent_block_count": 0,
        "max_channel_delta": 2,
        "divergent_blocks": [],
        "passed": true
      }
    ],
    "system_unique_render_hashes": ["<sha256>"],
    "candidate_unique_render_hashes": ["<sha256>"],
    "system_nondeterministic": false,
    "candidate_nondeterministic": false
  }
}
```

`pixel_match_percent` na raiz de `render_correctness` é o pior percentual observado entre os pares A/B válidos. `maximum_divergent_block_count` é o pior número de blocos divergentes. O candidato é reprovado se qualquer comparação exceder `maximum_divergent_blocks`, independentemente de qualquer resultado de performance.

A cena fixa identifica divergências somente neste caminho de renderização. Ela não prova desempenho em jogos nem correção em outros shaders, APIs ou workloads.

## Capacidades Vulkan

Cada fase bem-sucedida do workload de correção inclui `native.capabilities`:

- lista ordenada de extensões e respectivos `specVersion`;
- features core obtidas por `VkPhysicalDeviceFeatures2`, com fallback legado explicitamente indicado;
- limites relevantes de imagens, descriptors, vertex/fragment, compute, samples, timestamps e memória;
- `driverID`, `driverName`, `driverInfo` e `conformanceVersion` quando expostos;
- versão bruta/decodificada do driver;
- `mesa_version_major` e `mesa_version_minor` quando `driverInfo` contém uma versão Mesa reconhecível.

`capability_diff` apresenta:

- `extensions_gained[]` e `extensions_lost[]`;
- `features_gained[]` e `features_lost[]`;
- `limits_increased[]` e `limits_decreased[]`;
- alterações de `specVersion` das extensões;
- identidade dos dois drivers e indicação de mudança.

Uma extensão perdida pode explicar incompatibilidade, mas sua presença não garante que todos os caminhos estejam corretos.

## Catálogo de falhas

`failure_catalog[]` é persistido no `suite.json` e registra, por braço e rodada:

- `crash` do processo isolado;
- `timeout`, após encerramento forçado do runner;
- `vk_error_device_lost`;
- outros `vulkan_error` com operação e `VkResult`;
- `runner_exception`/`native_error`;
- `validation_error` capturado na cauda do logcat;
- `render_mismatch` após comparação dos previews.

Exemplo:

```json
{
  "phase": "candidate",
  "driver_mode": "custom",
  "round": 2,
  "failure_type": "vk_error_device_lost",
  "failure_stage": "queue_wait_idle",
  "vulkan_operation": "vkQueueWaitIdle",
  "vk_result": -4,
  "message": "vkQueueWaitIdle failed with VkResult=-4"
}
```

Uma fase travada nunca é convertida em resultado numérico. O processo é encerrado e uma fase sintética de falha é gravada no histórico local.

## Vereditos da Fase 1

| `verdict` | Significado |
|---|---|
| `passed_render_correctness` | Todos os pares válidos ficaram dentro da tolerância |
| `failed_render_correctness` | Pelo menos um par excedeu o limite de blocos divergentes |
| `failed_execution` | Crash, timeout, erro Vulkan, validation error ou outra falha bloqueante |
| `completed_no_reference` | Execução single-driver concluída, sem referência A/B para julgar correção relativa |
| `completed_transfer_measurement` | Workload legado de transferência concluído sem falhas |

## Regras de comparação e evolução

1. Compare somente o mesmo `workload_id` **e** `workload_version`.
2. Mudança de geometria, SPIR-V, seeds, ordem de draws, formato, resolução, cálculo, blocos padrão ou semântica de aprovação exige uma nova `workload_version`.
3. Mudança de comandos, tamanho de buffer ou cálculo da transferência exige `vulkan_transfer_stress` versão 2; nunca reutilize `transfer_payload_gib_s` com outra definição.
4. Todo campo novo de resultado exige incremento compatível de `schema_version` e atualização deste documento na mesma alteração.
5. Cada braço continua sendo executado em processo `:runner` descartável. Resultados de sistema e candidato nunca compartilham o loader Vulkan.
6. Nenhum delta ou veredito de correção pode ser apresentado como ganho em jogos.

### Comparabilidade histórica

A passagem de `schema_version` 1 para 2 **não quebra** a comparabilidade do workload de transferência v1. Ela inicia uma série nova e independente para `render_correctness_offscreen/v1`. Resultados antigos não possuem `render_correctness`, `capability_diff`, `failure_catalog` nem `verdict`; consumidores devem tratar esses campos como opcionais ao ler schema 1.


## Fase 3: `schema_version = 4`

A versão 4 é aditiva. Workloads e métricas primárias mantêm suas versões anteriores; a nova inferência fica isolada em `analysis_contract` e `statistical_analysis`.

```json
{
  "analysis_contract": {
    "analysis_version": 1,
    "sample_unit": "paired_ab_round",
    "primary_estimator": "median_paired_improvement_percent",
    "confidence_level": 0.95,
    "bootstrap_iterations": 5000,
    "minimum_paired_samples": 5,
    "practical_margin_percent": 3.0
  },
  "statistical_analysis": {
    "available": true,
    "primary_metric": "throughput_gops",
    "paired_sample_count": 7,
    "median_paired_improvement_percent": 8.4,
    "confidence_interval_95_percent": {
      "lower": 5.1,
      "upper": 11.2
    },
    "wins": 7,
    "ties": 0,
    "losses": 0,
    "probability_of_superiority_percent": 100.0,
    "matched_rank_biserial_correlation": 1.0,
    "exact_sign_test_two_sided_p_value": 0.015625,
    "classification": "candidate_better"
  }
}
```

A melhora percentual pareada usa o driver do sistema como denominador. Para métricas em que menor é melhor: `(sistema - candidato) / sistema × 100`. Para métricas em que maior é melhor: `(candidato - sistema) / sistema × 100`.

A unidade amostral é a rodada A/B completa. Frames, dispatches, pipelines ou janelas térmicas dentro de uma fase não são promovidos a observações independentes. Falhas e pares incompletos permanecem explícitos e não recebem valores numéricos sintéticos.

Ver [PHASE3_STATISTICAL_ANALYSIS.md](PHASE3_STATISTICAL_ANALYSIS.md).

## Fase 4: `schema_version = 5`

A versão 5 adiciona metadados para catálogo e identidade de hardware. Ela não redefine nenhum workload nem a inferência da Fase 3.

```json
{
  "schema_version": 5,
  "phase4_contract": {
    "catalog_version": 1,
    "suite_diff_version": 1,
    "ranking_version": 1,
    "bisect_version": 1,
    "public_dataset_schema_version": 1,
    "blocking_validity_policy": "failures_or_blocking_warnings"
  },
  "hardware_identity": {
    "identity_version": 1,
    "manufacturer": "AYN",
    "model": "Odin 2 Portal",
    "soc_manufacturer": "Qualcomm",
    "soc_model": "SM8550",
    "gpu_model": "Adreno 740",
    "device_key": "ayn/odin-2-portal/sm8550/adreno-740",
    "public_hardware_key": "sm8550/adreno-740"
  }
}
```

Leitores de histórico aceitam schema 1–5 e derivam `hardware_identity` quando o campo não existe. Essa derivação não altera o arquivo antigo.

### Chave de comparação

Rankings, diffs numéricos e bisect exigem a mesma combinação de:

- `hardware_identity.device_key`;
- `workload_id`;
- `workload_version`;
- `analysis_contract.analysis_version`;
- SHA-256 da forma canônica de `workload_config`.

Misturar qualquer um desses elementos marca o resultado como historicamente incompatível.

### Envelope público

O envelope público usa um schema separado e não substitui `suite.json`:

```json
{
  "public_dataset_schema_version": 1,
  "signature_algorithm": "SHA-256-canonical-json-v1",
  "payload": {
    "hardware": {
      "public_hardware_key": "sm8550/adreno-740",
      "soc_model": "SM8550",
      "gpu_model": "Adreno 740"
    },
    "workload": {
      "workload_id": "compute_arithmetic",
      "workload_version": 1,
      "workload_config_sha256": "<64 hex>"
    },
    "candidate": {
      "zip_sha256": "<64 hex>"
    }
  },
  "payload_sha256": "<64 hex>"
}
```

A assinatura é uma verificação de integridade determinística, não uma assinatura criptográfica de identidade. A publicação é recusada diante de falhas, avisos bloqueantes, hardware desconhecido ou amostra insuficiente.

Veja [PHASE4_HISTORY_REGRESSION.md](PHASE4_HISTORY_REGRESSION.md).


## Fase 5: `schema_version = 6`

A versão 6 adiciona `vulkan_command_trace_replay/v1`, sem redefinir qualquer workload anterior.

```json
{
  "workload_id": "vulkan_command_trace_replay",
  "workload_version": 1,
  "workload_config": {
    "warmup_seconds": 3,
    "measure_seconds": 10,
    "primary_metric": "median_replay_ms",
    "trace": {
      "trace_id": "mixed_graphics_compute_barrier",
      "trace_version": 1,
      "trace_format_version": 1,
      "definition_sha256": "<64 hex>"
    }
  },
  "trace_contract": {
    "trace_analysis_version": 1,
    "correctness_gate": "exact_output_sha256_before_performance_verdict"
  },
  "trace_replay": {
    "comparison_available": true,
    "complete_pair_count": 5,
    "output_mismatch_count": 0,
    "system_nondeterministic": false,
    "candidate_nondeterministic": false,
    "passed_correctness_gate": true
  }
}
```

A métrica `median_replay_ms` é aceita para inferência somente depois da aprovação do gate de correção. `trace_output_mismatch` e `trace_nondeterminism` entram no `failure_catalog` e bloqueiam ranking, bisect conclusivo e publicação pública.

Alterar ordem de comandos, shaders, seeds, dimensões, contagens de draw/dispatch, barreiras, formato de saída ou política de comparação exige nova `trace_version` ou `trace_format_version`. Resultados schema 1–5 permanecem válidos, mas não possuem os blocos `trace_contract` e `trace_replay`.


## Fase 6: `schema_version = 7`

A versão 7 adiciona somente o vínculo opcional de uma suíte a uma campanha automatizada. Workloads, traces, métricas, análise estatística e regras de correção permanecem nas mesmas versões.

```json
{
  "schema_version": 7,
  "phase6_contract": {
    "campaign_schema_version": 1,
    "campaign_scheduler_version": 1,
    "campaign_summary_version": 1,
    "order_policy": "rotating_serpentine_v1",
    "cross_workload_score": false
  },
  "campaign_context": {
    "campaign_id": "campaign-1700000000000",
    "job_id": "job-001",
    "job_ordinal": 1,
    "job_count": 12,
    "thermal_position": 1,
    "plan_sha256": "<64 hex>"
  }
}
```

Execuções manuais gravam esses campos como `null`. O `campaign.json` possui schema próprio e não substitui `suite.json`. Seu bloco `plan` é imutável e protegido por SHA-256 da forma canônica; o bloco `execution` registra estados, tentativas, retomadas e caminhos relativos para as suítes.

Resultados schema 7 continuam comparáveis com schema 1–6 quando hardware, workload, versão, configuração e `analysis_version` forem idênticos. A campanha não cria score composto entre workloads.

Veja [PHASE6_AUTOMATED_CAMPAIGNS.md](PHASE6_AUTOMATED_CAMPAIGNS.md).
