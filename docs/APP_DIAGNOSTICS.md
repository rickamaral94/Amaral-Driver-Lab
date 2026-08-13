# Logs persistentes do aplicativo

A partir da `v0.13.0-alpha16`, o Amaral Driver Lab mantém diagnóstico próprio no diretório
específico do app:

```text
/storage/emulated/0/Android/data/com.amaral.driverlab/files/logs/
```

O diretório não exige permissão ampla de armazenamento. Em Android 11 ou superior, alguns
gerenciadores bloqueiam a navegação manual em `Android/data`; por isso a tela inicial também oferece
**EXPORTAR LOGS DO APP (.ZIP)** usando o seletor de documentos do sistema.

## Arquivos

| Arquivo | Conteúdo |
|---|---|
| `app-main.log` | início do processo principal, lifecycle das Activities, exportações, exceções Java e histórico de encerramentos reportado pelo Android |
| `app-runner.log` | lifecycle do processo isolado, breadcrumbs dos workloads e pontos nativos da apresentação Vulkan |
| `exit-trace-*.trace` | trace disponibilizado pelo Android para ANR ou crash quando a plataforma o fornece |
| `diagnostic-info.json` | versão do app, aparelho, SDK e caminho efetivo do diretório |
| `README.txt` | explicação curta preservada junto aos logs |

Cada linha dos arquivos `.log` é um objeto JSON independente. As gravações críticas são
sincronizadas em disco. O runner visual registra criação da superfície, etapas de inicialização
Vulkan e, para o primeiro frame e checkpoints, retorno de acquire, envio à fila, retorno de present,
conclusão do fence e escrita da evidência.

Os `.log` rotacionam ao atingir 2 MiB e mantêm três arquivos anteriores por processo. Cada trace é
limitado a 1 MiB e somente os oito mais recentes são mantidos. O diretório é removido pelo Android quando o aplicativo é desinstalado; para
preservar evidências, atualize o APK por cima da instalação existente ou exporte o ZIP antes de
desinstalar.

## Limites

- O app registra o último ponto que conseguiu confirmar; ele não atribui um sinal nativo sem
  evidência do Android.
- `ApplicationExitInfo` está disponível a partir do Android 11. Em versões anteriores permanecem
  os logs de lifecycle, exceção Java e breadcrumbs próprios.
- Morte abrupta pode impedir o último evento em memória, por isso cada checkpoint crítico usa
  flush e sincronização imediata.
