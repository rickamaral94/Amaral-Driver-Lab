# Amaral Driver Lab

APK Android arm64, sem root, para comparar o driver Vulkan do sistema com pacotes Turnip/AdrenoTools em processos isolados e reproduzíveis. A prioridade atual é **correção de renderização antes de performance**.

## O que existe

- importação de ZIP AdrenoTools com validação de `meta.json`, ELF64/AArch64, limites contra ZIP bomb/path traversal e SHA-256;
- carregamento rootless via [`libadrenotools`](https://github.com/bylaws/libadrenotools), fixada no commit `8fae8ce254dfc1344527e05301e43f37dea2df80`;
- processo `:runner` descartado depois de cada fase, sem reaproveitar o loader Vulkan entre sistema e candidato;
- modos `system_only`, `candidate_only` e `ab_system_vs_candidate`, com ordem AB/BA alternada;
- **correção offscreen v1**: cena determinística 256 × 256, SHA-256 do RGBA, preview PNG lossless e comparação tolerante por blocos;
- reprovação explícita quando o candidato excede a tolerância de render, independentemente de velocidade;
- diff de extensões, features core, limites Vulkan, identidade do driver, conformance e versão Mesa;
- catálogo persistente de crash, timeout, `VK_ERROR_DEVICE_LOST`, outros erros Vulkan, validation errors e render mismatch;
- workload legado `vulkan_transfer_stress_v1`, preservado sem redefinir `transfer_payload_gib_s`;
- telemetria de aparelho, Vulkan, bateria, temperatura, energia/corrente disponíveis, estado térmico e cauda do logcat;
- exportação JSON e publicação opcional em GitHub Issues por Device Flow, sem PAT ou client secret no APK;
- histórico local de suítes, diff lado a lado, ranking por hardware/workload e bisect de builds sequenciais;
- envelope público anônimo opcional, validado e assinado por hash canônico, sem envio automático;
- command trace Vulkan próprio, versionado, com gate de correção e análise A/B;
- campanhas automatizadas de até 64 suítes, com ordem termicamente balanceada, retomada e manifesto auditável;
- SDK Android de telemetria opt-in para emuladores, importação local, resumo de frame pacing, comparação descritiva e vínculo imutável com suítes;
- diagnóstico profundo A/B de formatos, corpus de shaders, pipeline cache, memória, sincronização e Soak Test.

A correção offscreen valida somente a cena fixa incluída no APK. Ela **não prova ganho em jogos** nem correção em todos os shaders, APIs ou emuladores.

## Como instalar e usar

### 1. Instale o APK

1. Baixe o APK mais recente em [Releases](https://github.com/rickamaral94/Amaral-Driver-Lab/releases) ou no artifact da CI.
2. Permita a instalação pelo navegador/gerenciador de arquivos, caso o Android solicite.
3. Instale e abra **Amaral Driver Lab**. Root não é necessário.

O app não troca o driver global do Android. O pacote escolhido é carregado somente no processo isolado da fase.

### 2. Prepare o teste

1. Feche jogos e emuladores.
2. Fixe brilho, modo de desempenho e velocidade da ventoinha.
3. Desconecte o carregador quando possível.
4. Espere o aparelho chegar a uma temperatura inicial comparável.

### 3. Importe o candidato

1. Toque em **IMPORTAR ZIP ADRENOTOOLS**.
2. Confirme o aviso de execução de código nativo.
3. Escolha o ZIP ainda compactado.
4. Aguarde a validação e confira a biblioteca principal e o SHA-256 exibidos.

### 4. Execute correção sistema × candidato

1. Em **Workload**, mantenha **Correção offscreen v1 · recomendado**.
2. Em **Protocolo**, escolha **A/B · sistema × candidato**.
3. Comece com tolerância RGBA `2`, máximo de blocos divergentes `0` e pelo menos `3` rodadas.
4. Toque em **▶ INICIAR TESTE** e não use outros apps até a conclusão.

Cada braço abre um processo novo. O app salva um PNG por fase, compara sistema e candidato da mesma rodada e apresenta um veredito:

- `passed_render_correctness`: todos os pares ficaram dentro da tolerância;
- `failed_render_correctness`: pelo menos um par teve alteração estrutural acima do limite;
- `failed_execution`: ocorreu crash, timeout, erro Vulkan, device lost ou validation error;
- `completed_no_reference`: o modo single-driver terminou, mas não havia braço de referência.

O hash exato serve para integridade e detecção de não determinismo. A aprovação usa tolerância por canal e por bloco, porque arredondamentos pequenos entre implementações podem ser legítimos.

### 5. Revise capacidades e falhas

No `suite.json`:

- `render_correctness` contém percentual de pixels compatíveis, blocos divergentes e detalhes por rodada;
- `capability_diff` mostra extensões/features ganhas e perdidas, além de limites que aumentaram ou diminuíram;
- `failure_catalog` registra a fase, o braço, a rodada, o estágio e o tipo de cada falha;
- `validity_warnings` informa condições que pedem repetição.

Uma extensão perdida pode explicar uma regressão percebida, mas uma extensão presente não garante que sua implementação esteja correta.

### 6. Use o workload legado de transferência quando necessário

Selecione **Transferência fill/copy v1 · legado** para reproduzir a série histórica existente.

`transfer_payload_gib_s` continua medindo exatamente o mesmo protocolo fill/copy v1. A métrica não representa largura de banda física da VRAM e não prova ganho em jogos.

Compare apenas resultados com o mesmo `workload_id`, `workload_version`, aparelho, APK, configuração e condições térmicas.

## Pacote de driver esperado

O ZIP precisa ter `meta.json` e as bibliotecas `.so` na raiz. A biblioteca indicada por `libraryName` deve existir.

```json
{
  "schemaVersion": 1,
  "name": "Turnip A740 Odin 2 Portal",
  "description": "Perfil de teste",
  "author": "Amaral",
  "packageVersion": "2.1.0-rc1",
  "vendor": "Mesa/Turnip",
  "driverVersion": "25.x",
  "minApi": 28,
  "libraryName": "libvulkan_freedreno.so"
}
```

O APK executa código nativo do ZIP. Use somente pacotes próprios ou hashes verificados. O SHA-256 registrado identifica o ZIP completo, não apenas a biblioteca principal.

## Esquema de resultados

A versão atual usa `schema_version = 13`. Todas as evoluções foram aditivas: o workload legado `vulkan_transfer_stress/v1`, a correção v1, os cinco workloads da Fase 2 e `analysis_version = 1` permanecem inalterados.

Mudanças na geometria, SPIR-V, ordem dos draws, resolução, formato, cálculo ou regra padrão de comparação exigem uma nova `workload_version`.

Veja [docs/RESULT_SCHEMA.md](docs/RESULT_SCHEMA.md).

## Compilar e testar

Requisitos:

- JDK 17;
- Android SDK 35 e Build Tools 35.0.0;
- Android NDK `27.2.12479018`;
- CMake 3.22.1;
- Gradle 8.11.1.

```bash
cp local.properties.example local.properties
# ajuste sdk.dir
./gradlew :app:testDebugUnitTest :telemetry-sdk:testDebugUnitTest :app:assembleDebug
```

O CMake usa o `glslc` do NDK para compilar os shaders fixos em SPIR-V durante o build. O APK é publicado somente para `arm64-v8a`.

## Issues automáticas

Siga [docs/GITHUB_APP_SETUP.md](docs/GITHUB_APP_SETUP.md). Sem GitHub App configurado, o resultado continua salvo e o APK abre um rascunho de issue no navegador. Nenhum PAT ou client secret deve ser compilado no APK.

## Estado do projeto

As Fases 1 e 2 fornecem correção, capacidades e workloads reais versionados. A Fase 3 adiciona inferência estatística conservadora. A Fase 4 organiza as suítes em histórico local, diff, ranking, bisect e envelope público validado. A Fase 5 adiciona command traces Vulkan próprios e versionados com correção obrigatória antes do veredito de performance. A Fase 6 executa matrizes de drivers × workloads/traces com plano imutável, retomada segura e rankings separados por chave comparável. A Fase 7 adiciona o Teste Full Recomendado, recomendação geral com gate de compatibilidade e pacote completo de diagnóstico. A Fase 8 adiciona três cenas Vulkan visíveis e animadas, checkpoints determinísticos e atualiza o Full Qualification para v2 sem redefinir as séries anteriores. A Fase 9 adiciona um SDK versionado de telemetria para emuladores, armazenamento local e comparação descritiva sem incorporar sessões reais ao score Full. A Fase 10 adiciona uma série separada de diagnóstico profundo do Turnip, com matriz de formatos, corpus compute, cache, memória, sincronização e soak controlado. A Fase 11 consolida tudo isso no Full Qualification v3. A Fase 12 adiciona seleção persistente de idioma, oito traduções e relatório HTML localizado sem alterar os contratos técnicos.


## Fase 3: comparação estatística

Suítes A/B de performance agora geram `statistical_analysis` com pareamento por rodada, melhora percentual orientada pela direção da métrica, bootstrap determinístico de 95%, teste exato dos sinais, tamanho de efeito e diagnóstico de viés de ordem. O padrão recomendado é **5 a 10 rodadas**.

A classificação usa uma margem prática de ±3%:

- `candidate_better_with_confidence`;
- `candidate_worse_with_confidence`;
- `practically_equivalent_with_confidence`;
- `inconclusive_statistical_comparison`;
- `insufficient_statistical_data`.

Essa camada não altera nenhuma série de workload v1. Compare inferências somente com o mesmo `analysis_version` e as mesmas condições do aparelho. Veja [docs/PHASE3_STATISTICAL_ANALYSIS.md](docs/PHASE3_STATISTICAL_ANALYSIS.md).


## Fase 4: laboratório histórico

Abra **HISTÓRICO · DIFF · RANKING · BISECT** na tela principal para indexar resultados locais ou importar outros `suite.json`. O app permite:

- filtrar por workload e identidade de hardware;
- comparar duas suítes sem calcular deltas quando a metodologia é incompatível;
- ranquear hashes de drivers somente dentro da mesma chave hardware/workload/configuração;
- localizar a fronteira entre uma build conhecida como boa e a primeira build ruim;
- exportar manualmente um envelope técnico sem modelo do aparelho, fingerprint, caminhos, logs ou imagens.

Avisos bloqueantes e falhas impedem ranking válido, bisect conclusivo e publicação pública. Veja [docs/PHASE4_HISTORY_REGRESSION.md](docs/PHASE4_HISTORY_REGRESSION.md).


## Fase 5: trace replay Vulkan versionado

Selecione **Trace replay Vulkan v1** e escolha um dos traces embutidos:

- **Misto: render pass + compute + barreiras v1**;
- **Compute: cadeia de dependências v1**.

Cada braço é executado em um processo novo. A saída binária recebe SHA-256, e o modo A/B exige hashes idênticos em todos os pares antes de aceitar a análise de performance. O workload usa `median_replay_ms`, mas não é uma captura de jogo e não representa FPS. Veja [docs/PHASE5_VERSIONED_TRACE_REPLAY.md](docs/PHASE5_VERSIONED_TRACE_REPLAY.md).


## Fase 6: campanhas automatizadas de regressão

Abra **CAMPANHAS DE REGRESSÃO · FASE 6** para selecionar até 8 drivers e até 8 workloads/traces. Cada combinação vira uma suíte A/B completa. O agendador rotaciona a posição dos candidatos, aplica cooldown entre jobs e salva um `campaign.json` com plano imutável e SHA-256 canônico.

Uma campanha interrompida pode ser retomada; o job que estava em execução volta para `pending` e é repetido. Ao final, rankings são gerados separadamente para cada hardware/workload/versão/configuração. Não existe vencedor ou score agregado entre workloads diferentes. Veja [docs/PHASE6_AUTOMATED_CAMPAIGNS.md](docs/PHASE6_AUTOMATED_CAMPAIGNS.md).

## Fase 7: Teste Full Recomendado

O botão **TESTE FULL RECOMENDADO · FASE 7** executa o perfil imutável `turnip_full_qualification/v1`: todos os workloads e traces oficiais, correção visual antes e depois da carga, preflight ambiental, pausa/retomada, índice de qualificação, recomendação clara e um `diagnostic-bundle.zip` com relatório HTML, JSONs, resultados brutos, PNGs e hashes.

O índice geral é um resumo versionado para facilitar decisão; as métricas científicas individuais continuam separadas. Falhas de compatibilidade ou validade sempre bloqueiam a recomendação, mesmo quando o candidato é mais rápido em algum teste. Veja [docs/PHASE7_FULL_QUALIFICATION.md](docs/PHASE7_FULL_QUALIFICATION.md).


## Fase 8: cenas Vulkan visíveis e Full Qualification v2

A Fase 8 adiciona três workloads independentes:

- `visual_scene_geometry/v1`: 144 instâncias animadas, depth e câmera determinística;
- `visual_scene_materials/v1`: materiais procedurais e padrões de alta frequência;
- `visual_scene_postprocess/v1`: amostragem intermediária, bloom, tone mapping e sincronização entre passes.

As cenas são exibidas em tela cheia, renderizadas internamente em 960×540 e capturam checkpoints nos frames 30, 90 e 150. O braço candidato só recebe veredito de performance quando seus checkpoints passam pela comparação visual A/B e permanecem determinísticos entre rodadas. A métrica primária é `p99_gpu_frame_ms`, menor é melhor.

O Teste Full Recomendado atual passa a usar `turnip_full_qualification/v2` com 13 etapas. Resultados v1 permanecem válidos, mas não entram no ranking v2. Veja [docs/PHASE8_VISIBLE_VULKAN_SCENES.md](docs/PHASE8_VISIBLE_VULKAN_SCENES.md).


## Fase 9: SDK de telemetria para emuladores

Abra **TELEMETRIA DE EMULADORES · FASE 9** para importar um `session.json` produzido pelo módulo `telemetry-sdk`. O contrato registra somente identidade anônima do jogo por SHA-256, versão do emulador, hash das configurações, identidade pública de hardware, braço do driver, frame times e eventos técnicos permitidos. Títulos, caminhos e identificadores de conta são recusados.

O app calcula P50/P95/P99, 1% low descritivo, razões de stutter, eventos de crash/device lost e amostras térmicas. Duas sessões são comparadas somente quando emulador, build, jogo anônimo, configurações, hardware, relógio e política de amostragem coincidem; duração e contagem de frames também precisam ficar dentro de 10%. A comparação não usa frames como amostras independentes, não gera confiança estatística e não altera Full Qualification, ranking ou campanhas.

O vínculo com uma suíte é salvo em `suite-link.json`, sem modificar `session.json` nem `suite.json`. Nenhum upload ocorre automaticamente. Veja [docs/PHASE9_EMULATOR_TELEMETRY.md](docs/PHASE9_EMULATOR_TELEMETRY.md).


## Fase 10: diagnóstico profundo do Turnip

Abra **DIAGNÓSTICO PROFUNDO TURNIP · FASE 10** para executar o perfil `turnip_deep_diagnostics/v1` em A/B. O relatório compara capacidades de formatos, seis shaders compute, criação cold/warm de pipelines, serialização de pipeline cache, pressão de memória com limite seguro, fences, binary semaphores, barriers e um reliability probe.

O botão **SOAK TEST A/B** repete ciclos de criação/destruição de objetos Vulkan, memória limitada e submits com fence. Falhas de capacidade, shader, sincronização, crash ou `device lost` bloqueiam qualquer conclusão favorável de tempo.

A Fase 10 forma uma série histórica própria. Ela não altera workloads anteriores, Full Qualification, campanhas, ranking ou telemetria de emuladores. Veja [docs/PHASE10_DEEP_TURNIP_DIAGNOSTICS.md](docs/PHASE10_DEEP_TURNIP_DIAGNOSTICS.md).

## Phase 11 — Full Qualification v3

The recommended Full test now orchestrates every automatic test added through Phase 10: existing A/B suites, visible Vulkan scenes, deep diagnostics at the safe 128 MiB memory profile and a five-cycle short soak. The report exposes separate performance and compatibility indices. Emulator telemetry remains optional, local and not scored automatically.

Historical Full v1 and v2 profiles remain immutable and separate from v3.


## Fase 12 — internacionalização completa

O botão de bandeira no canto superior direito permite escolher **Português do Brasil, Inglês, Espanhol, Francês, Alemão, Italiano, Japonês, Chinês simplificado** ou voltar ao idioma do sistema. A preferência é local, persiste após reiniciar o app e também é aplicada aos processos isolados de execução.

A interface herdada das Fases 1–11, dialogs, spinners, mensagens e o `summary.html` usam o idioma selecionado. O JSON permanece técnico e estável: nomes de campos, enums, códigos Vulkan, hashes, métricas e IDs de workload não são traduzidos. A mudança eleva o resultado para `schema_version = 13`, sem redefinir workloads nem os perfis Full v1, v2 ou v3.

Veja [docs/PHASE12_LOCALIZATION.md](docs/PHASE12_LOCALIZATION.md) e [docs/TRANSLATION_GUIDE.md](docs/TRANSLATION_GUIDE.md).

## Fase 13 — UX guiada e identidade Amaral

A tela inicial agora prioriza o **Full Qualification v3**, separa Comparações, Testes individuais e Resultados, e mantém a área técnica atrás do modo avançado persistente. O fluxo guiado organiza driver, teste, preparação, execução e resultado em cinco etapas, com progresso segmentado e conclusão antes das métricas brutas.

A identidade visual usa tokens derivados do logo Amaral, estados acessíveis com cor + ícone + texto, ajuda contextual em três níveis e suporte aos oito idiomas da Fase 12. A mudança é exclusivamente de apresentação: workloads, scores, gates, hashes, IDs técnicos e séries históricas permanecem inalterados.

Veja [docs/PHASE13_UX_REDESIGN.md](docs/PHASE13_UX_REDESIGN.md), [docs/BRAND_GUIDELINES.md](docs/BRAND_GUIDELINES.md), [docs/HELP_CONTENT_GUIDE.md](docs/HELP_CONTENT_GUIDE.md) e [docs/ACCESSIBILITY.md](docs/ACCESSIBILITY.md).

### Fase 13 alpha2 — fluxo direto de comparação

O card principal permite selecionar **Sistema × Turnip** ou **Turnip × Turnip**, escolher candidato e referência, importar o ZIP e iniciar o Full v3 diretamente. Ao finalizar, o aplicativo abre o log completo com exportação do diagnóstico e criação de issue no projeto, mantendo a área técnica opcional.


### Refinamento do teste recomendado (alpha3)

A home agora começa pela importação e seleção do candidato, da referência e do modo Sistema × Turnip ou Turnip × Turnip. O teste recomendado usa o perfil **Turnip Recommended Validation v1** de oito etapas, focado em correção visual, compatibilidade, shaders, frametime e sincronização. O Full Qualification v3 longo permanece em Ferramentas avançadas.
