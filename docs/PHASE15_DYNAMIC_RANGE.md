# Fase 15 — faixa dinâmica calibrada

## Estado da entrega

| Checkpoint | Estado | O que foi verificado |
|---|---|---|
| Implementação | concluído | contratos Java/JNI, unidades de repetição, cache persistente, linearidade, n adaptativo sem espiada, gates térmicos, auditoria histórica e testes locais |
| Primeira rodada física A740 | falha de orquestração | a Issue #48 executou correção, mas os probes seguintes foram interrompidos pela reutilização prematura do processo `:runner`; nenhum número de performance é válido |
| Requalificação A740 | pendente | exige o APK alpha14 com intervalo de relançamento corrigido e duas execuções físicas completas |

O checkpoint de implementação prova estrutura e regras do protocolo. Só o checkpoint físico pode
provar tempos, estabilidade, temperatura, sensibilidade de 1%/3%/10% ou comportamento GMEM no
A740.

## Aprendizado da primeira rodada física — Issue #48

O alpha13 gravava o resultado de um probe e o coordenador iniciava o probe seguinte
imediatamente. As activities de benchmark compartilham o processo Android `:runner`; ao concluir,
a activity anterior ainda tinha um `Process.killProcess()` agendado para 350 ms depois. O novo
probe podia nascer no mesmo processo e ser morto pelo callback da execução anterior.

O sintoma foi reproduzível na estrutura do relatório: correção offscreen concluiu, enquanto os
workloads v2 falharam no início da calibração e a cobertura de identidade ficou em 12/82. Isso é
falha do laboratório, não regressão do driver e não evidência de performance.

O contrato corrigido centraliza dois tempos:

- conclusão visual: até 250 ms, seguida pelo autoencerramento do runner em 350 ms;
- relançamento pelo coordenador: 1.200 ms.

Todos os caminhos que encadeiam probes, piloto e fases aguardam o intervalo de relançamento. Um
teste unitário preserva uma margem mínima de 500 ms entre os dois eventos. A correção precisa ser
confirmada no aparelho; análise de fonte e teste unitário não provam o comportamento do Android.

## Contrato de calibração

O perfil `turnip_full_qualification/v6` fixa a versão de workload em cada passo. Correção permanece
v1; workloads de performance do Recommended passam a v2. O mesmo `repetitions_per_sample` é usado
nos dois braços. Calibração por driver é proibida.

A chave persistida contém modelo, SoC, GPU observada pelo runtime Vulkan, `workload_id`,
`workload_version`, hash canônico da configuração, fonte de timestamp e perfil térmico inicial. Um
probe não escalado serve apenas para observar a GPU e encontrar a chave; em cache hit ele não
reestima `m`. O multiplicador salvo é reusado e validado em cada qualificação.

| Campo | Regra |
|---|---|
| alvo | 8–16 ms por amostra; centro de calibração 12 ms |
| piso de validade | 2 ms |
| multiplicador | inteiro, 1–256; 512 é reservado para o teste `2m` |
| linearidade | `median(2m) / median(m)` em [1,8; 2,2] |
| falha de linearidade | `nonlinear_scaling`; tempo normalizado vira `null` |
| pós-validação | referência novamente em `m`; se começou em 8–16 ms, sair da faixa gera `calibration_drift` |
| orçamento | 2.700 s por suíte; ao exceder, interrompe novas rodadas e bloqueia ranking |

Quando `m=1` já excede 16 ms (caso possível no shader compile), a pós-validação usa o baseline
calibrado e tolerância relativa máxima de 20%; ela não finge que o lote cabia na faixa alvo.
Calibração fria não é automaticamente válida a quente. O cache separa perfis térmicos e a execução
compara o primeiro e o último terço das amostras de referência; deriva absoluta acima de 5% gera
`thermal_drift_detected`.

## Unidade de repetição

| Família | `repetition_unit` | Limite preservado |
|---|---|---|
| cenas visuais | `renderpass` | cena e pós-processamento completos, com load/store e apresentação única por amostra |
| tiling/GMEM | `renderpass` | cada repetição abre e fecha um render pass completo; bin setup não é amortizado |
| cena estável | `frame` | frame offscreen completo |
| trace | `frame` | seed restaurado e trace inteiro reexecutado |
| compute/térmico | `dispatch` | uma repetição v2 é um dispatch completo |
| shader compile | `draw_batch` | 24 pipelines com variantes distintas |

O shader cold v2 usa um corpus de especializações distinto por repetição, `VkPipelineCache` vazio e
`MESA_SHADER_CACHE_DISABLE=true` antes de inicializar o loader no processo isolado. O timestamp
engloba apenas criação de pipelines; não existe custo de limpeza subtraído da medição.

## Tamanho amostral sem optional stopping

São executados três pares piloto AB/BA independentes. O CV das razões pareadas fixa
`required_paired_rounds` uma única vez, limitado operacionalmente a 5–20 pares. Depois disso, `n`
não é reestimado a partir do delta parcial. Os arquivos piloto são separados e nunca entram em
`statistical_analysis`; o resultado declara `pilot_samples_reused=false`.

`statistical_analysis.analysis_version` permanece 1 porque estimador, bootstrap e regra de decisão
não mudaram. A justificativa fica registrada no contrato: agregadores precisam ler
`completed_paired_rounds`, pois o tamanho final já não é constante.

## Injeção de efeito

Os níveis nominais são 0%, 1%, 3% e 10%. A injeção acrescenta unidades reais do workload dentro do
domínio original: renderpasses/frames/dispatches no timestamp GPU e pipelines adicionais no tempo
de criação do shader compile. `sleep` e busy-wait de CPU são proibidos. O resultado registra
`effect_injection_percent`, `effective_repetitions_per_sample` e o efeito observado; ensaio físico
que não recuperar o efeito permanece falho, sem correção numérica.

## Comparabilidade

- Dentro do aparelho, compare braços somente com a mesma chave e o mesmo multiplicador.
- A740 e A825 podem ter multiplicadores distintos; compare apenas tamanho de efeito pareado, nunca
  tempo absoluto normalizado.
- Perfis v1–v5 permanecem imutáveis e executam workloads v1.
- Resultado sem igualdade observada entre versão declarada e runtime fica fora de ranking, dataset
  e bisect. A auditoria das Issues já publicadas está em `WORKLOAD_VERSION_AUDIT.md`.

## Protocolo físico A740 pendente

1. Compilar e instalar o APK alpha14 no A740.
2. Executar a qualificação v6 em perfil térmico controlado e repetir em outro dia.
3. Confirmar faixa 8–16 ms, linearidade, ausência de drift e `completed_paired_rounds` planejado.
4. Rodar a injeção 0%/1%/3%/10% no mesmo workload e conferir o efeito realmente medido.
5. Publicar artefatos brutos. Falha em qualquer gate permanece falha; não ajustar o delta depois.
