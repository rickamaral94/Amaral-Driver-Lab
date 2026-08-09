# Benchmark visual avançado · GPU Stress 3D v1

`visual_scene_gpu_stress/v1` é uma carga Vulkan visível, determinística e mais exigente que as cenas de quads da Fase 8. Ela usa uma série própria para preservar integralmente os workloads e rankings anteriores e, a partir do Recommended Validation v2, participa do perfil `turnip_full_qualification/v5`.

## Carga gráfica

- resolução interna fixa de 1280×720;
- 768 cubos procedurais, com 36 vértices por instância e 27.648 vértices por frame;
- lattice 3D animado com câmera orbital determinística e depth test/write;
- material procedural de seis oitavas, quatro luzes, especular, Fresnel e emissivos;
- imagem intermediária RGBA8 e passe final com 19 leituras de textura (aberração cromática e bloom de 16 taps), scanlines, vignette, tone mapping e gamma;
- apresentação por `VK_KHR_android_surface` e swapchain.

## Protocolo e métrica

A animação continua derivada de `frame_index / 60`, sem depender do relógio real. Os frames 30, 90 e 150 são capturados em PNG lossless para:

1. detectar não determinismo dentro de cada braço;
2. comparar sistema/referência e candidato com tolerância por pixel e bloco;
3. bloquear conclusões de performance quando a renderização diverge.

A métrica primária é `p99_gpu_frame_ms`, menor é melhor. P50, P95, média, 1% low descritivo, timestamps, telemetria térmica e energia continuam registrados como diagnóstico.

## Como executar

1. Abra **Ferramentas avançadas**.
2. Importe e selecione o driver.
3. Em **Workload**, escolha **Cena avançada: GPU Stress 3D v1**.
4. Use **A/B** e pelo menos cinco rodadas para comparação estatística.
5. Mantenha brilho, modo de desempenho, ventoinha e temperatura inicial equivalentes.

## Comparabilidade

Compare somente `visual_scene_gpu_stress/v1` com a mesma configuração e o mesmo hardware. A série não é equivalente a `visual_scene_geometry/v1`, `visual_scene_materials/v1` ou `visual_scene_postprocess/v1`. No Recommended v5 ela é a etapa `visual_gpu_stress`, tem peso de 20% e gate de compatibilidade; seus resultados não são misturados com Recommended v4 nem com Full v1–v3.

O benchmark é sintético: ele aumenta a cobertura de geometria, fragment shader, depth, sampling e pós-processamento, mas não reproduz CPU, I/O, compilação dinâmica, texturas comprimidas nem FPS de jogos e emuladores reais.
