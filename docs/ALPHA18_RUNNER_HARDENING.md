# Alpha18 · robustez do runner visual

Esta versão responde aos dois defeitos reproduzidos no AYN Odin 2 Portal com a alpha17:

1. `SIGSEGV` no despacho de `vkCreateAndroidSurfaceKHR` durante a calibração de cenas visuais;
2. remoção da interface principal e retorno ao launcher depois dos crashes consecutivos do
   processo `:runner`.

## Inicialização Vulkan/WSI

A alpha18 enumera e seleciona o dispositivo físico, consulta suas propriedades e confirma
`VK_KHR_swapchain` antes de adquirir a `ANativeWindow` e criar `VK_KHR_android_surface`. O fluxo
passa a registrar checkpoints separados antes e depois de cada operação crítica, incluindo:

- `enumerate_physical_devices_count`;
- `enumerate_physical_devices_list`;
- `query_physical_device`;
- `enumerate_device_extensions_count`;
- `enumerate_device_extensions_list`;
- `acquire_android_window`;
- `create_android_surface_call`;
- `create_android_surface_returned`.

O último checkpoint nativo é persistido em arquivo próprio com `fsync`. Em uma morte abrupta, o
coordenador promove esse valor para `failure_stage` e preserva também os breadcrumbs Java.

## Isolamento e interrupção segura

As três Activities do runner usam a afinidade `${applicationId}.runner` e são iniciadas em uma
tarefa nova, descartável e excluída dos recentes. A tarefa principal permanece atrás dela e volta
ao primeiro plano quando o runner termina ou sofre crash.

Se um probe de calibração falhar, a suíte ainda produz `suite.json`, porém declara
`qualification_abort_recommended`. O Full Qualification registra a etapa como falha, mantém o
caminho do relatório, muda para `paused_after_runner_failure` e não inicia a próxima cena.

Ao destruir a `QualificationActivity`, callbacks pendentes são removidos e o runner atual é
cancelado. Isso impede que um coordenador ligado a uma Activity antiga continue abrindo telas.

## Limite da correção

O isolamento impede que um driver nativo derrube a interface do aplicativo. Ele não converte um
driver sem WSI Android funcional em resultado válido: se `vkCreateAndroidSurfaceKHR` continuar
falhando, a cena será classificada como incompatível, a qualificação será pausada e os logs ficarão
disponíveis para exportação.
