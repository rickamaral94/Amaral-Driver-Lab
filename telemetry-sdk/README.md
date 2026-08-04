# Amaral Driver Lab Telemetry SDK v1

Biblioteca Android sem permissão de rede para produzir `session.json` compatível com a Fase 9.

## Propriedades

- coleta somente após opt-in do usuário;
- identidade do jogo exclusivamente por SHA-256;
- recusa título, caminho e identificadores de conta;
- frame samples e eventos técnicos com limites explícitos;
- publicação atômica;
- SHA-256 do payload JSON canônico;
- nenhum upload automático.

A API principal é `TelemetrySessionWriter`. Consulte `docs/PHASE9_EMULATOR_TELEMETRY.md` no repositório raiz.
