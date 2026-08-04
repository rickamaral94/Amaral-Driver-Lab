# Fase 13 — Redesign completo de UX e identidade Amaral

## Objetivo

Transformar o Amaral Driver Lab em um produto visualmente consistente, intuitivo, guiado, acessível e reconhecível como parte da marca Amaral, sem remover funcionalidades técnicas ou alterar contratos, workloads, métricas, IDs, scores, gates ou comparabilidade histórica.

## Princípios

1. **Teste Full primeiro** — o Full Qualification v3 é a ação principal para a maioria dos usuários.
2. **Complexidade progressiva** — modo básico por padrão e modo avançado persistente para investigação técnica.
3. **Conclusão antes dos detalhes** — recomendações e problemas aparecem antes das métricas brutas.
4. **Ajuda contextual** — cada métrica relevante explica o que é, por que importa e como interpretar.
5. **Estados acessíveis** — cor, ícone e texto sempre trabalham juntos.
6. **Compatibilidade preservada** — a Fase 13 altera apresentação e fluxo, não metodologia técnica.
7. **Internacionalização nativa** — todo componente novo deve funcionar nos oito idiomas da Fase 12.

## Nova arquitetura de navegação

### Tela inicial

- **Teste recomendado**
  - Iniciar Teste Full
- **Comparações**
  - Sistema × Turnip
  - Turnip A × Turnip B
- **Testes individuais**
  - Cenas visuais
  - Performance
  - Diagnóstico profundo
  - Soak Test
- **Resultados**
  - Histórico
  - Rankings
  - Relatórios
  - Telemetria de emuladores

## Fluxo guiado

1. **Escolha do driver** — sistema/candidato, SHA-256, importação e compatibilidade conhecida.
2. **Escolha do teste** — destaque para o Teste Full Recomendado.
3. **Preparação** — checklist de apps, desempenho, temperatura, carregamento e ventilação.
4. **Execução** — progresso segmentado por correção visual, performance, diagnóstico e soak.
5. **Resultado** — conclusão simples, problemas, categorias e detalhes técnicos expansíveis.

## Modo básico

Mostra:

- Teste Full;
- recomendação;
- principais categorias;
- problemas encontrados;
- exportação.

## Modo avançado

Libera:

- testes individuais;
- número de rodadas;
- pressão de memória;
- traces e formatos;
- ciclos de soak;
- métricas brutas;
- configurações de comparabilidade.

A preferência deve permanecer após recriação da Activity, reinício do app e reinício do aparelho.

## Identidade visual

A paleta deve ser derivada dos ativos oficiais do aplicativo e organizada em tokens de marca e tokens funcionais.

Estados funcionais:

- ✓ Recomendado
- ⚠ Recomendado com ressalvas
- ✕ Não recomendado
- = Empate técnico
- ? Inconclusivo

Nenhum estado pode depender apenas de cor.

## Componentes

- cards consistentes;
- espaçamento e raios padronizados;
- tipografia hierárquica;
- botões com estados claros;
- progress bar segmentada;
- chips de status;
- comparação lado a lado;
- gráficos simples e heatmaps acessíveis;
- seções recolhíveis;
- skeleton/loading;
- mensagens de erro acionáveis;
- confirmação antes de testes longos;
- resumo fixo no topo dos resultados.

## Resultado compreensível

O primeiro bloco deve responder:

- qual driver é recomendado;
- ganho ou perda de performance;
- compatibilidade;
- estabilidade/corrupção;
- diferença térmica;
- principais ressalvas.

Detalhes técnicos ficam disponíveis em seção expansível sem perda de informação.

## Ranking

Separações:

- melhor geral;
- melhor performance;
- melhor compatibilidade;
- melhor estabilidade;
- melhor térmico;
- melhor shader cache;
- drivers bloqueados;
- empates técnicos.

## Erros acionáveis

Toda falha deve informar:

- teste e etapa;
- erro técnico preservado;
- significado localizado;
- ações recomendadas;
- opção de exportar diagnóstico.

## Acessibilidade

- alvo mínimo de toque;
- contraste verificável;
- suporte a fontes maiores;
- labels para leitores de tela;
- foco previsível;
- portrait e landscape onde aplicável;
- textos adaptáveis;
- suporte a caracteres largos/CJK;
- animações reduzidas seguindo o Android.

## Arquivos previstos

Novos:

- `AppTheme.java`
- `AmaralColors.java`
- `HelpContent.java`
- `HelpDialog.java`
- `GuidedTestFlowActivity.java`
- `DriverSelectionFragment.java`
- `TestPreparationFragment.java`
- `TestProgressFragment.java`
- `ResultOverviewFragment.java`
- `AdvancedSettingsActivity.java`
- `UxPreferenceStore.java`
- `docs/BRAND_GUIDELINES.md`
- `docs/HELP_CONTENT_GUIDE.md`
- `docs/ACCESSIBILITY.md`

## Critérios de aceite

- fluxo completo em modo básico;
- fluxo completo em modo avançado;
- ajuda disponível nas métricas prioritárias;
- contraste e tamanho mínimo de toque;
- textos longos e CJK sem perda funcional;
- persistência do modo;
- rotação e recriação de Activity;
- retorno após interrupção;
- ranking legível;
- erros acionáveis;
- progresso segmentado correto;
- nenhuma funcionalidade antiga perdida;
- oito idiomas funcionando no novo layout;
- testes JVM e CI Android/nativa aprovados;
- APK separado, SHA-256 e checklist físico do Odin 2 Portal.

## Validação automatizada

A CI deve executar sobre o commit final da branch, sem arquivos auxiliares de implantação, e validar recursos multilíngues, contratos JVM, compilação Java, toolchain nativa Vulkan e geração do APK arm64.

## Comparabilidade

A Fase 13 não cria uma nova série de resultados. `schema_version`, perfis Full, IDs de workload, hashes, métricas, gates, pesos e enums técnicos permanecem os mesmos da Fase 12. Alterações de apresentação não entram como chave de comparação.
