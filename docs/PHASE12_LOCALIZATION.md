# Fase 12 — Internacionalização completa

## Objetivo

A Fase 12 permite que o idioma do Amaral Driver Lab seja escolhido dentro do próprio aplicativo, sem depender permanentemente do idioma do Android. A escolha é persistida localmente e aplicada a todas as Activities e aos processos isolados usados pelos runners Vulkan.

## Idiomas suportados

- Português do Brasil (`pt-BR`)
- Inglês (`en`)
- Espanhol (`es`)
- Francês (`fr`)
- Alemão (`de`)
- Italiano (`it`)
- Japonês (`ja`)
- Chinês simplificado (`zh-CN`)
- Usar idioma do sistema

O inglês é o recurso padrão e funciona como fallback quando o Android não encontra uma tradução específica.

## Seletor e persistência

O botão de bandeira no canto superior direito da tela principal abre a lista de idiomas. A preferência é salva em `SharedPreferences`:

```text
preferences = driver_lab_language
key = language_preference
value = system | pt-BR | en | es | fr | de | it | ja | zh-CN
```

Depois de uma escolha manual, o aplicativo mantém esse idioma até que o usuário selecione outro ou volte para **Usar idioma do sistema**.

## Aplicação do locale

`DriverLabApplication` aplica o locale no processo principal e nos processos `:runner`. `LocalizedActivity` reaplica o locale antes da criação de cada Activity. Em Android 13 ou superior, o aplicativo também sincroniza a seleção com `LocaleManager.setApplicationLocales`.

As telas antigas foram construídas programaticamente em Java. Para não reescrever a arquitetura visual antes da Fase 13, a Fase 12 adiciona uma ponte auditável entre os textos herdados em português e os recursos localizados. Novas telas devem usar `R.string` diretamente.

## Relatórios

O `summary.html` é renderizado no idioma selecionado e declara esse idioma no atributo HTML `lang`. Cabeçalhos, explicações, rótulos e números apresentados ao usuário são localizados.

O JSON técnico não é traduzido. Campos, enums, hashes, IDs de workload, métricas e códigos Vulkan permanecem estáveis. Exemplos que não mudam:

```text
VK_ERROR_DEVICE_LOST
p99_gpu_frame_ms
driver_sha256
visual_scene_geometry/v1
candidate_not_recommended
```

## Contrato

A Fase 12 adiciona `phase12_contract` aos novos relatórios:

```json
{
  "localization_schema_version": 1,
  "result_schema_version": 13,
  "supported_language_tags": ["pt-BR", "en", "es", "fr", "de", "it", "ja", "zh-CN"],
  "system_language_option": true,
  "default_fallback_language": "en",
  "preference_persisted_locally": true,
  "html_report_localized": true,
  "technical_json_localized": false,
  "technical_identifiers_stable": true,
  "legacy_full_profiles_preserved": [1, 2, 3]
}
```

## Comparabilidade

`schema_version = 13` é uma evolução aditiva. A metodologia dos workloads, traces, cenas, diagnósticos e perfis Full não foi alterada. Full Qualification v1, v2 e v3 continuam séries históricas separadas pelas versões de perfil já existentes.

A linguagem do HTML não deve ser usada como chave de comparabilidade. Resultados técnicos continuam sendo comparados pelos IDs, versões, hashes, hardware e configuração de execução.

## Validação

A conclusão da fase exige:

- troca de idioma sem apagar drivers, resultados ou preferências técnicas;
- persistência após recriação e reinício do aplicativo;
- fallback em inglês;
- todas as Activities e processos runners usando o locale escolhido;
- dialogs, spinners, mensagens e relatórios localizados;
- arrays de tradução com a mesma cardinalidade em todos os idiomas;
- japonês e chinês preservados em UTF-8;
- JSON técnico e IDs estáveis;
- seletor com descrição para leitor de tela;
- build, testes JVM e APK arm64 aprovados na CI.

A validação automatizada deve ser executada sobre o commit final da própria branch, sem arquivos auxiliares de implantação ou snapshot.

## Limitação de validação física

A implementação e a CI não substituem um teste manual no AYN Odin 2 Portal. Antes de encerrar a PR, devem ser conferidos no aparelho: persistência após reinício, troca durante navegação, processos runners, textos longos, caracteres CJK, HTML exportado e acessibilidade do seletor.
