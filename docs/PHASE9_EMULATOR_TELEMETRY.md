# Fase 9 — SDK de telemetria para emuladores

## Objetivo

A Fase 9 permite que um emulador ou adaptador externo produza uma sessão técnica versionada e opt-in. O Amaral Driver Lab importa o JSON localmente, valida privacidade e integridade, calcula métricas descritivas e pode vinculá-lo a uma suíte sintética já existente.

Esta fase não modifica emuladores de terceiros. Eden, GameHub ou qualquer outro projeto precisam adotar o SDK ou produzir o contrato equivalente. A existência do SDK no repositório não significa integração oficial com esses aplicativos.

## Versões

- `schema_version = 10` para novas suítes do Driver Lab, com `phase9_contract` aditivo;
- `emulator_telemetry_schema_version = 1`;
- `telemetry_sdk_version = 1`;
- `telemetry_summary_version = 1`;
- `telemetry_comparison_version = 1`;
- `telemetry_link_schema_version = 1`.

O módulo público está em `telemetry-sdk/` e não declara permissão de rede.

## Privacidade obrigatória

O bloco `privacy` exige:

```json
{
  "game_identity_mode": "sha256",
  "game_key_sha256": "<64 hex>",
  "contains_title": false,
  "contains_paths": false,
  "contains_account_identifiers": false
}
```

Uma sessão que declare título, caminho ou identificador de conta é recusada, mesmo que o SHA-256 de integridade esteja correto.

O hash do jogo deve ser produzido pelo integrador a partir de uma chave estável local. O Driver Lab não recebe o título original nem fornece serviço de reversão.

## Estrutura mínima

```json
{
  "telemetry_schema_version": 1,
  "telemetry_sdk_version": 1,
  "session_id": "eden-20260804-001",
  "created_at_ms": 1785800000000,
  "finished_at_ms": 1785800600000,
  "source": {
    "emulator_id": "eden.android",
    "display_name": "Eden",
    "package_name": "org.eden.emulator",
    "version_name": "0.2.1",
    "build_id": "nightly-20260804"
  },
  "privacy": {
    "game_identity_mode": "sha256",
    "game_key_sha256": "<64 hex>",
    "contains_title": false,
    "contains_paths": false,
    "contains_account_identifiers": false
  },
  "environment": {
    "hardware_public_key": "sm8550/adreno-740",
    "soc_model": "SM8550",
    "gpu_model": "Adreno 740",
    "android_sdk": 36,
    "settings_sha256": "<64 hex>"
  },
  "driver": {
    "mode": "custom",
    "name": "Turnip",
    "version": "25.x",
    "package_sha256": "<64 hex>"
  },
  "collection": {
    "method": "embedded_sdk",
    "frame_time_clock": "emulator_internal",
    "sample_policy": "every_frame",
    "includes_gpu_time": true
  },
  "samples": [
    {
      "relative_ms": 17,
      "frame_time_ms": 16.7,
      "gpu_time_ms": 8.2,
      "present_wait_ms": 0.4
    }
  ],
  "events": [],
  "integrity": {
    "algorithm": "sha256",
    "canonicalization": "json_canonical_v1",
    "payload_sha256": "<64 hex>"
  }
}
```

## Uso do SDK

No projeto Android do emulador, adicione o módulo ou publique a biblioteca internamente e crie o writer somente após consentimento explícito do usuário:

```java
JSONObject metadata = new JSONObject()
        .put("session_id", "eden-20260804-001")
        .put("created_at_ms", System.currentTimeMillis())
        .put("source", sourceJson)
        .put("privacy", privacyJson)
        .put("environment", environmentJson)
        .put("driver", driverJson)
        .put("collection", collectionJson);

TelemetrySessionWriter writer = TelemetrySessionWriter.create(outputFile, metadata);
writer.appendFrame(relativeMs, frameTimeMs, gpuTimeMs, presentWaitMs);
writer.appendEvent(relativeMs, "graphics_warning", "warning",
        "renderer.descriptor_fallback", detailsJson);
writer.finish(System.currentTimeMillis());
```

O writer publica o arquivo de forma atômica e assina o payload canônico. Ele não envia dados pela rede.

## Eventos permitidos

- `shader_compile`;
- `graphics_warning`;
- `device_lost`;
- `crash`;
- `stutter_marker`;
- `thermal_sample`;
- `present_timeout`;
- `session_note`.

Severidades: `info`, `warning`, `error` e `fatal`.

## Resumo local

O Driver Lab calcula:

- P50, P95, P99 e média de `frame_time_ms`;
- pior frame;
- 1% low baseado na média do pior 1% dos frames;
- frames acima de 25 ms e 50 ms;
- razão de stutter;
- distribuição opcional de GPU time e present wait;
- crashes, device lost, warnings gráficos e eventos fatais;
- temperatura inicial, final e máxima quando disponível.

Frames sucessivos não são tratados como observações independentes. Não há bootstrap, teste de hipótese ou intervalo de confiança para sessões reais.

## Comparação A/B

Uma comparação historicamente compatível exige igualdade de:

- schema e versão do resumo;
- emulador, package, versão e build;
- hash anônimo do jogo;
- hash das configurações;
- identidade pública do hardware;
- método de coleta;
- relógio de frame time;
- política de amostragem.

Também exige pelo menos 120 frames por braço e diferença máxima de 10% na duração e na quantidade de samples.

A classificação é apenas descritiva:

- `candidate_better_descriptive`;
- `system_better_descriptive`;
- `mixed_or_equivalent_descriptive`;
- `candidate_regressed_stability`;
- `not_historically_comparable`.

Crash, device lost ou evento fatal extra no candidato tem precedência sobre uma melhora de P99.

## Vínculo com suítes

O arquivo `suite-link.json` registra:

- hash canônico da sessão;
- `suite_id` e hash canônico da suíte;
- caminho relativo local;
- braço do driver;
- identidade pública de hardware;
- versão do contrato.

Para uma sessão custom, o SHA-256 do pacote precisa corresponder ao candidato da suíte. Hardware também precisa coincidir. O vínculo é um sidecar: nenhum byte da sessão ou da suíte é alterado.

## Comparabilidade histórica

Não há quebra nas séries anteriores. Permanecem intactos todos os workloads v1, traces, cenas visuais, campanhas e perfis Full v1/v2.

O bump para `schema_version = 10` adiciona somente `phase9_contract` a novas suítes. Uma suíte schema 10 continua comparável a schema 1–9 quando a chave completa de hardware, workload, versão, configuração e análise coincidir.

Sessões de telemetria possuem histórico próprio e nunca entram automaticamente em ranking sintético, bisect, campanha ou score Full.

## Limitações

- a coleta depende da instrumentação do emulador ou adaptador;
- cenas, entrada e progresso do jogo podem divergir entre sessões;
- caches, CPU, térmica e processos em segundo plano não são controlados;
- `gpu_time_ms` pode não estar disponível;
- o SDK não prova compatibilidade oficial com nenhum emulador;
- nenhuma sessão equivale a um benchmark científico controlado.
