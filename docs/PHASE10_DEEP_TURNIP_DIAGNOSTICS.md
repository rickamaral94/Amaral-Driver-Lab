# Fase 10 — Diagnóstico profundo do Turnip

A Fase 10 cria uma série diagnóstica separada para investigar diferenças internas entre o driver Vulkan do sistema e um pacote Turnip. Ela não substitui o Full Qualification, não entra no ranking histórico de workloads e não produz uma nota global misturando unidades incompatíveis.

## Contratos

- `schema_version = 11` para novos artefatos do aplicativo;
- `deep_diagnostic_schema_version = 1`;
- `deep_diagnostic_report_version = 1`;
- `deep_diagnostic_comparison_version = 1`;
- `deep_diagnostic_bundle_version = 1`;
- `format_matrix_version = 1`;
- `shader_corpus_version = 1`;
- `pipeline_cache_diagnostic_version = 1`;
- `memory_pressure_version = 1`;
- `synchronization_version = 1`;
- `soak_test_version = 1`;
- perfil `turnip_deep_diagnostics/v1`.

Mudanças futuras no conjunto de formatos, SPIR-V, limites de memória, comandos de sincronização ou ciclo do soak exigem nova versão do módulo ou do perfil.

## Matriz de formatos

O runner consulta 26 formatos representativos:

- formatos de cor de 8, 10, 11, 16 e 32 bits;
- formatos depth/stencil;
- ETC2;
- ASTC 4×4, 6×6 e 8×8;
- BC1, BC3 e BC7 quando expostos;
- formatos usados como storage image, attachment e texel/vertex buffer.

Para cada formato são registrados recursos de tiling linear e ótimo, amostragem, filtro linear, color/depth attachment, storage image, blit, usos de buffer e sample counts representativos.

A matriz não testa todas as combinações possíveis de `VkImageCreateFlags`, image types, modifiers, external memory ou tiling interno do driver.

## Corpus de shaders e pipeline cache

O corpus compute v1 executa seis casos determinísticos:

1. loop de FMA em ponto flutuante;
2. cadeia de dependências inteiras;
3. branches divergentes e loops;
4. atomics em SSBO;
5. shared memory e `barrier()`;
6. shader grande com operações inteiras e float.

Para cada caso são registrados:

- criação do shader module;
- criação cold do compute pipeline;
- segunda criação warm usando o mesmo cache;
- execução controlada;
- hash FNV-1a da saída;
- falha e estágio Vulkan, quando houver.

O cache é serializado e seu tamanho é registrado. O corpus v1 ainda não executa shaders gráficos específicos de derivatives, gathers, discard ou subgroups; essa ausência é declarada no relatório.

## Pressão de memória

O módulo aloca buffers em blocos, libera posições alternadas e tenta substituições menores para exercitar churn e fragmentação.

O limite é seguro: usa o menor valor entre o solicitado pelo usuário e um oitavo do maior heap Vulkan relevante. O intervalo permitido é de 16 a 256 MiB. O teste não tenta esgotar deliberadamente a memória do Android.

## Sincronização

A versão 1 valida:

- submit com fence;
- espera e reset de fence;
- cadeia de binary semaphore;
- `vkCmdFillBuffer`;
- barrier de transfer write para transfer read;
- cópia e validação do padrão em memória;
- distribuição de latência de 32 submits vazios com fence.

A extensão de timeline semaphore é reportada, mas não é habilitada silenciosamente no device utilizado pelos workloads existentes. Transferência de ownership entre famílias também fica fora da execução v1, porque o protocolo usa uma única família graphics+compute.

## Soak Test

O Soak Test é uma execução separada com 1 a 50 ciclos. Cada ciclo recria shader, pipeline, cache, buffers limitados, command pool, command buffer e fence. O primeiro erro encerra a sequência e registra o ciclo exato.

Esse teste ajuda a revelar vazamentos, degradação acumulada, falhas de criação e `device lost`, mas não simula uma sessão longa de jogo, emulador, temperatura sustentada ou pressão irrestrita do sistema.

## Comparação sistema × candidato

Falhas têm precedência sobre tempo:

- perda de capacidade de formato;
- caso de shader que passa no sistema e falha no candidato;
- sincronização incorreta;
- falha no reliability probe ou soak;
- crash, timeout ou `VK_ERROR_DEVICE_LOST`.

Sem bloqueios, mudanças de tempo usam margem prática de ±3% e recebem somente classificação descritiva:

- `candidate_improved_descriptive`;
- `candidate_regressed_descriptive`;
- `mixed_or_equivalent_descriptive`.

Não há bootstrap ou significância estatística sobre os microtempos internos.

## Pacote de diagnóstico

O ZIP exportado contém:

- `report.json`;
- resultado do braço sistema;
- resultado do braço candidato;
- arquivos `.state`;
- cauda do logcat do próprio processo quando disponível;
- amostras térmicas e de bateria;
- `manifest.json` com SHA-256 de cada entrada.

Logcat global, tombstones, KGSL e dumps de kernel continuam exigindo ADB/root.

## Comparabilidade histórica

A Fase 10 não redefine nenhum workload anterior. Schemas 1–10 continuam válidos. A comparação de diagnósticos exige o mesmo `profile_sha256`, modo (`full` ou `soak`), limites e hardware.

Resultados `turnip_deep_diagnostics/v1` não são misturados com:

- workloads das Fases 1–2;
- análise estatística v1;
- traces da Fase 5;
- campanhas da Fase 6;
- Full Qualification v1/v2;
- cenas visuais da Fase 8;
- telemetria de emuladores da Fase 9.
