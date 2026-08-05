# Guia de tradução

## Princípios

1. Traduza o que o usuário lê; preserve o que ferramentas e desenvolvedores processam.
2. Não altere IDs, enums, nomes de métricas, hashes, nomes de arquivos ou códigos Vulkan.
3. Use inglês como fallback e mantenha todas as listas de tradução com a mesma ordem e cardinalidade.
4. Preserve placeholders (`%1$s`, `%2$d`, `%.2f`) e caracteres XML escapados.
5. Evite traduzir nomes próprios: Amaral Driver Lab, Turnip, Vulkan, GitHub, AdrenoTools e nomes oficiais de workloads quando usados como identificadores.

## Estrutura

```text
res/values/strings.xml              # inglês / fallback
res/values-pt-rBR/strings.xml       # português do Brasil
res/values-es/strings.xml           # espanhol
res/values-fr/strings.xml           # francês
res/values-de/strings.xml           # alemão
res/values-it/strings.xml           # italiano
res/values-ja/strings.xml           # japonês
res/values-zh-rCN/strings.xml       # chinês simplificado
res/values/legacy_ui_source.xml     # textos herdados em português
res/xml/locales_config.xml          # idiomas declarados ao Android
```

## Interface nova

Todo texto novo deve nascer como recurso:

```java
button.setText(R.string.action_start_test);
status.setText(getString(R.string.status_ready));
```

Não adicione novos literais de interface em Java. A ponte `LegacyUiTranslations` existe somente para preservar as telas programáticas das Fases 1–11 até o redesign da Fase 13.

## Interface herdada

Ao alterar um texto herdado:

1. atualize a entrada correspondente em `legacy_ui_source_pt_br`;
2. atualize a mesma posição em `legacy_ui_translation` em todos os oito diretórios;
3. execute `Phase12ResourcesTest`;
4. valide ao menos inglês, português, espanhol, alemão, japonês e chinês em uma tela estreita.

Mensagens dinâmicas devem manter partes técnicas intactas. Prefira recursos formatados em código novo. Quando uma mensagem herdada precisar continuar dinâmica, traduza o prefixo estável e preserve valores, caminhos, hashes e IDs.

## Relatórios e JSON

O HTML pode conter texto localizado. O JSON deve continuar canônico e independente do idioma. Não acrescente um campo localizado quando ele puder ser gerado na camada de apresentação.

Correto:

```json
{"recommendation":"candidate_not_recommended"}
```

Incorreto:

```json
{"recommendation":"driver_não_recomendado"}
```

## Checklist de revisão

- XML válido e UTF-8;
- placeholders idênticos ao recurso inglês;
- nenhuma tradução vazia;
- nenhum ID técnico traduzido;
- textos longos sem truncamento crítico;
- botões e dialogs compreensíveis;
- leitor de tela anuncia o seletor;
- relatório HTML no idioma selecionado;
- JSON idêntico entre idiomas, exceto metadados explicitamente não técnicos.
