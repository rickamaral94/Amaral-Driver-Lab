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
- campanhas automatizadas de até 64 suítes, com ordem termicamente balanceada, retomada e manifesto auditável.

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

A versão atual usa `schema_version = 7`. Todas as evoluções foram aditivas: o workload legado `vulkan_transfer_stress/v1`, a correção v1, os cinco workloads da Fase 2 e `analysis_version = 1` permanecem inalterados.

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
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

O CMake usa o `glslc` do NDK para compilar os shaders fixos em SPIR-V durante o build. O APK é publicado somente para `arm64-v8a`.

## Issues automáticas

Siga [docs/GITHUB_APP_SETUP.md](docs/GITHUB_APP_SETUP.md). Sem GitHub App configurado, o resultado continua salvo e o APK abre um rascunho de issue no navegador. Nenhum PAT ou client secret deve ser compilado no APK.

## Estado do projeto

As Fases 1 e 2 fornecem correção, capacidades e workloads reais versionados. A Fase 3 adiciona inferência estatística conservadora. A Fase 4 organiza as suítes em histórico local, diff, ranking, bisect e envelope público validado. A Fase 5 adiciona command traces Vulkan próprios e versionados com correção obrigatória antes do veredito de performance. A Fase 6 executa matrizes de drivers × workloads/traces com plano imutável, retomada segura e rankings separados por chave comparável.


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
