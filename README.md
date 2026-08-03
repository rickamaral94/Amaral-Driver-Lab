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
