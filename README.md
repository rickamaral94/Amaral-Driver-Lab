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
- exportação JSON e publicação opcional em GitHub Issues por Device Flow, sem PAT ou client secret no APK.

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

A Fase 1 usa `schema_version = 2`, de forma aditiva e compatível com resultados v1. A série `vulkan_transfer_stress/v1` não foi alterada; `render_correctness_offscreen/v1` inicia uma série independente.

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

## Próxima fase

A Fase 2 adicionará workloads independentes e versionados para compilação cold/warm de shaders, render pass/tiling, compute aritmético, frametime offscreen e sustentação térmica. Nenhuma dessas métricas reutilizará `transfer_payload_gib_s`.
