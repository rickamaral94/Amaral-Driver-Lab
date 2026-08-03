# Amaral Driver Lab

APK Android arm64 para comparar o driver Vulkan do sistema com pacotes Turnip/AdrenoTools de forma repetível. O fluxo do MVP é direto: importar ZIP, selecionar driver, executar e revisar o JSON ou a issue criada no GitHub.

## O que já existe no MVP

- importação de ZIP AdrenoTools com validação de `meta.json`, ELF64/AArch64, limites contra ZIP bomb/path traversal e SHA-256;
- seleção entre múltiplos drivers já importados;
- carregamento rootless via [`libadrenotools`](https://github.com/bylaws/libadrenotools), fixada no commit `8fae8ce254dfc1344527e05301e43f37dea2df80`;
- processo `:runner` descartado depois de cada fase, evitando reaproveitar um loader Vulkan contaminado;
- teste stock, candidato ou A/B com ordem AB/BA alternada;
- stress Vulkan headless de `fill/copy` em buffers de 8–32 MiB, warm-up e janela de medição configuráveis;
- timestamps da GPU quando expostos pelo driver, com fallback explícito para relógio de parede;
- captura de aparelho, Vulkan, bateria, temperatura, corrente/energia disponíveis, estado térmico e cauda do logcat do próprio runner;
- mediana, coeficiente de variação, delta candidato × sistema e alertas de validade;
- exportação do JSON e publicação opcional em GitHub Issues por Device Flow;
- timeout que encerra o processo nativo travado e registra a fase como falha.

`transfer_payload_gib_s` é uma métrica interna da carga sintética deste APK. Ela **não** representa diretamente a largura de banda física da VRAM e não deve ser comparada com números de outros benchmarks.

## Como instalar e usar

### 1. Instale o APK

1. Baixe o APK mais recente em [Releases](https://github.com/rickamaral94/Amaral-Driver-Lab/releases).
2. Abra o arquivo no Android e, se solicitado, permita ao navegador ou gerenciador de arquivos instalar apps desconhecidos.
3. Instale e abra **Amaral Driver Lab**. O app não exige root.

O app não troca o driver global do Android. O driver escolhido é carregado somente no processo isolado do benchmark, sem alterar jogos, emuladores ou o sistema.

### 2. Prepare o teste

1. Baixe um pacote Turnip/AdrenoTools em ZIP compatível com seu aparelho e mantenha-o compactado.
2. Feche jogos e emuladores.
3. Fixe brilho, modo de desempenho e velocidade da ventoinha.
4. Desconecte o carregador e espere o aparelho chegar a uma temperatura inicial comparável.

### 3. Importe e selecione o driver

1. Toque em **IMPORTAR ZIP ADRENOTOOLS**.
2. Confirme o aviso e escolha o ZIP.
3. Aguarde a validação de `meta.json`, arquitetura arm64, bibliotecas e SHA-256.
4. Selecione o driver importado na lista. O app mostra a biblioteca principal e o hash do pacote.

### 4. Faça a linha de base

1. Em **Protocolo**, escolha **Somente sistema**.
2. Para o primeiro teste, mantenha **Warm-up 3 s**, **Medição 10 s** e **3 rodadas**.
3. Toque em **▶ INICIAR TESTE** e não use outros apps até a conclusão.

### 5. Compare sistema × candidato

1. Confirme que o driver desejado continua selecionado.
2. Escolha **A/B · sistema × candidato**.
3. Use os mesmos tempos e número de rodadas da linha de base.
4. Toque em **▶ INICIAR TESTE**. O app alterna a ordem AB/BA e cria um processo Vulkan limpo para cada fase.

### 6. Leia e compartilhe o resultado

- `candidate_vs_system_percent` positivo indica que o candidato foi mais rápido nessa carga; negativo indica regressão.
- CV alto, falhas, throttling ou diferença térmica inicial acima de 2 °C pedem repetição do teste.
- Toque em **EXPORTAR ÚLTIMO JSON** para salvar toda a evidência.
- Em **GitHub Issues**, mantenha `rickamaral94` e `Amaral-Driver-Lab`; sem GitHub App configurado, **ENVIAR / ABRIR ÚLTIMA ISSUE** abre um rascunho no navegador.

Compare somente resultados feitos com o mesmo aparelho, versão do APK, workload, driver, tempos e condições térmicas.

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

O APK executa código nativo do ZIP. Use somente pacotes próprios ou hashes verificados. O SHA-256 registrado identifica o **ZIP completo**, não apenas a biblioteca principal.

## Compilar

Requisitos locais:

- JDK 17;
- Android SDK 35 e Build Tools 35.0.0;
- Android NDK `27.2.12479018`;
- CMake 3.22.1;
- Gradle 8.11.1.

```bash
cp local.properties.example local.properties
# ajuste sdk.dir
./gradlew :app:assembleDebug
```

O workflow `Android APK` instala essas versões e publica `amaral-driver-lab-debug` como artifact. O build é somente `arm64-v8a`, porque essa é a arquitetura suportada pelo `libadrenotools`.

## Issues automáticas

Siga [docs/GITHUB_APP_SETUP.md](docs/GITHUB_APP_SETUP.md). Sem GitHub App configurado, o resultado continua salvo e o APK abre um rascunho de issue no navegador. Nenhum PAT ou client secret deve ser compilado no APK.

## Protocolo recomendado

1. Feche jogos/emuladores e mantenha brilho, modo de energia e ventilação constantes.
2. Comece com o aparelho em temperatura comparável.
3. Use A/B com três rodadas ou mais; o app alterna AB/BA.
4. Rejeite ou repita suites com falha, throttling ou variação térmica inicial acima de 2 °C.
5. Compare apenas o mesmo APK, workload, aparelho e configurações.

## Próximas cargas

O transfer stress é o primeiro canário de estabilidade e throughput. A evolução natural é adicionar, como métricas separadas: compute shader, rasterização offscreen, shader compilation/cache, frametime p50/p95/p99, consumo integrado e replay de cenas estáveis. Veja [docs/RESULT_SCHEMA.md](docs/RESULT_SCHEMA.md).
