# Fase 4 — histórico, diff, ranking e bisect

A Fase 4 adiciona uma camada de comparação entre suítes completas. Ela não modifica shaders, workloads, métricas primárias ou a análise estatística da Fase 3.

## Contrato

- `schema_version = 5` para novas suítes;
- `catalog_version = 1`;
- `suite_diff_version = 1`;
- `ranking_version = 1`;
- `bisect_version = 1`;
- `public_dataset_schema_version = 1`;
- resultados antigos de schema 1–4 continuam indexáveis;
- nenhum resultado é migrado ou regravado silenciosamente.

## Histórico local

O app indexa até 1.000 arquivos `suite.json` presentes em:

- `files/runs/suite-*/suite.json`;
- `files/imported-suites/*.json`.

A tela permite filtrar por workload e identidade de hardware, incluir ou excluir resultados bloqueados e ordenar por data, melhoria ou nome do driver.

A identidade local usa fabricante, modelo, SoC e GPU. A chave pública usa somente SoC e GPU. Android 12 ou superior fornece `Build.SOC_MODEL`; relatórios antigos usam `hardware`/`board` como fallback e podem ficar menos específicos.

## Diff de duas suítes

Um delta numérico é permitido somente quando os dois resultados têm:

1. mesmo `workload_id`;
2. mesmo `workload_version`;
3. mesma identidade de hardware;
4. mesma configuração canônica do workload;
5. mesmo `analysis_version`.

Caso contrário, o diff continua mostrando metadados, mudanças de veredito, avisos e capacidades, mas marca `historically_comparable = false` e não calcula o delta.

## Ranking

Rankings usam a mediana de `median_paired_improvement_percent` por hash do ZIP candidato. Entram apenas suítes A/B de performance sem falhas ou avisos bloqueantes e com a mesma chave de comparação. Workloads de correção não são convertidos em pontuação artificial.

Um ranking local não representa liderança universal: ele reflete somente o aparelho, configuração e conjunto de suítes presentes no dispositivo.

## Bisect

O bisect ordena as suítes pelo momento de conclusão e classifica cada build como:

- `good`: melhor ou praticamente equivalente com confiança;
- `bad`: pior com confiança;
- `unknown`: inconclusivo, insuficiente ou bloqueado.

O resultado identifica o último bom, o primeiro ruim e, quando existe uma lacuna, a próxima posição intermediária a testar. O método assume que a sequência informada corresponde à ordem das builds Mesa e que a regressão é monotônica. Resultado não monotônico é marcado explicitamente.

## Envelope público anônimo

A exportação é manual e não envia dados automaticamente. O envelope preserva:

- SoC e GPU;
- workload, versão e hash da configuração;
- hash SHA-256 do ZIP candidato;
- resultado estatístico e métricas agregadas.

Ele remove fabricante/modelo do aparelho, fingerprint do Android, caminhos locais, logs e evidências de imagem. A integridade é verificada por `SHA-256-canonical-json-v1`. Essa assinatura detecta alteração acidental ou posterior, mas não autentica a identidade de quem produziu o arquivo.

A exportação é recusada quando existem falhas, avisos bloqueantes, hash do ZIP inválido, SoC/GPU desconhecidos ou amostra A/B insuficiente.

## Comparabilidade histórica

A passagem de schema 4 para 5 é aditiva. Permanecem inalterados:

- `vulkan_transfer_stress/v1` e `transfer_payload_gib_s`;
- `render_correctness_offscreen/v1`;
- os cinco workloads da Fase 2 em `workload_version = 1`;
- `analysis_version = 1` da Fase 3;
- regras de pareamento, bootstrap, margem prática, shaders, draws, dispatches e cálculo das métricas.

A Fase 4 inaugura somente versões de catálogo e comparação entre suítes. Não existe quebra das séries históricas anteriores.
