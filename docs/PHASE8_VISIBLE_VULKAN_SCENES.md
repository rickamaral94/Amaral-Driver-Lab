# Fase 8 · Cenas Vulkan visíveis e Full Qualification v2

A Fase 8 torna o teste gráfico observável: o aparelho mostra animações Vulkan reais em tela cheia enquanto coleta timestamps de GPU, checkpoints determinísticos e telemetria. Ela não substitui nem altera `render_correctness_offscreen/v1`, que permanece como gate rápido.

## Contratos

- `schema_version = 9`;
- `visual_scene_contract_version = 1`;
- `checkpoint_analysis_version = 1`;
- resolução interna: 960×540;
- checkpoints: frames 30, 90 e 150;
- métrica primária: `p99_gpu_frame_ms`, menor é melhor;
- Full Qualification atual: `turnip_full_qualification/v2`;
- relatório e score Full atuais: v2.

## Workloads

### `visual_scene_geometry/v1`

Renderiza 144 instâncias animadas com câmera determinística, depth test e variação de posição, escala e orientação. O objetivo é exercitar transformação, rasterização, depth e caminhos de render target em uma superfície Android real.

### `visual_scene_materials/v1`

Usa materiais procedurais, padrões de alta frequência, gradientes e variações temporais determinísticas. O objetivo é ampliar a cobertura de fragment shader e amostragem sem depender de assets proprietários.

### `visual_scene_postprocess/v1`

Renderiza uma cena intermediária e um passe final com múltiplas amostras, bloom, blur aproximado, tone mapping, vignette e gamma. O objetivo é exercitar dependências entre passes, image layouts, descriptors e sampling.

## Superfície e processo

Cada braço é iniciado no processo isolado `:runner` por uma Activity em landscape e fullscreen. O runner cria `VK_KHR_android_surface`, swapchain e imagens offscreen fixas. A animação usa `frame_index / 60`, não o relógio do aparelho; por isso, frames equivalentes devem produzir a mesma imagem mesmo quando a velocidade do driver varia.

A cena é apresentada na tela, mas a métrica usa timestamps Vulkan em torno dos passes submetidos. Quando timestamps não estão disponíveis, o resultado registra fallback para relógio de parede e recebe advertência de validade.

## Checkpoints e gate visual

Frames 30, 90 e 150 são copiados para memória e preservados como PNG lossless. Para cada rodada A/B:

1. o sistema e o candidato precisam produzir todos os checkpoints;
2. cada braço precisa repetir o mesmo hash em rodadas equivalentes;
3. cada par sistema/candidato é comparado por pixel e por blocos de 24×24;
4. divergências geram `visual-diff-rXX-fXXXX.png`;
5. performance só recebe veredito quando o gate visual passa.

Política v1:

- tolerância RGBA padrão: 3;
- mínimo de blocos compatíveis: 99%;
- máximo padrão de blocos divergentes: 2.

## Métricas

O runner grava:

- `p50_gpu_frame_ms`;
- `p95_gpu_frame_ms`;
- `p99_gpu_frame_ms`;
- `mean_gpu_frame_ms`;
- `one_percent_low_fps`;
- número de frames e uso de timestamps.

O ranking utiliza somente `p99_gpu_frame_ms`. As demais métricas são diagnósticas e não criam séries paralelas implícitas.

## Full Qualification v2

O perfil v2 contém 13 etapas:

1. correção offscreen inicial;
2. geometria/depth visível;
3. materiais visíveis;
4. pós-processamento visível;
5. compilação de shaders;
6. render pass/tiling;
7. compute;
8. transferência;
9. frametime estável;
10. trace misto;
11. trace compute;
12. sustentação térmica;
13. correção offscreen final.

As três cenas são gates de compatibilidade e representam 50% do índice v2. O perfil v1 permanece verificável e retomável com suas dez etapas. Nenhum score ou leaderboard mistura v1 e v2.

## Comparabilidade histórica

Não há redefinição de séries anteriores. Os três workloads visuais começam novas séries v1. Alterar shaders, resolução, instâncias, clock de animação, checkpoints, passes, formatos, tolerâncias padrão ou métrica primária exige uma nova `workload_version` ou uma nova versão do perfil Full.

## Limitações

As cenas não são capturas de jogos e não reproduzem CPU de emuladores, I/O, áudio, compilação dinâmica de aplicações nem todos os formatos de textura. Exigir superfície Android e blit para swapchain também pode excluir implementações Vulkan incompletas; essa falha deve ser reportada como compatibilidade, não transformada em zero de performance.
