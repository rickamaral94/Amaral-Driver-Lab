# Amaral Driver Lab Telemetry SDK

Módulo Java 17 sem dependência de Android UI para produzir bundles compatíveis com a Fase 7.
O módulo usa apenas `org.json`, que já existe no Android. A aplicação hospedeira deve coletar
telemetria somente após consentimento explícito e manter `collection.opt_in=true` e
`collection.local_only=true`.

## Exemplo mínimo

```java
String contentHash = TelemetryContract.hashContentId(
        installLocalSalt, "switch-title-id", titleId);

TelemetrySession session = TelemetrySession.builder(
        "eden-driverlab-adapter", "1.0.0",
        "Eden", "org.eden.emulator", emulatorVersion,
        contentHash, "switch")
    .customDriver(turnipZipSha256, driverName, driverVersion)
    .build();

session.recordState(System.nanoTime(), "started");
session.recordFrame(System.nanoTime(), frameDeltaNs);
JSONObject bundle = session.finish("complete", System.currentTimeMillis());
TelemetrySession.write(bundle, outputStream);
```

O SDK não acessa arquivos, jogos, contas, rede ou identificadores do usuário. O produtor escolhe
o destino do `OutputStream`. O contrato recusa caminhos locais, URIs, tokens e outros campos
sensíveis conhecidos. O identificador do conteúdo deve ser um SHA-256 salgado; nunca envie título,
ROM path, save path ou ID bruto.
