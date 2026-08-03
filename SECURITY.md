# Segurança

## Drivers importados

Um driver Vulkan é código nativo com o mesmo UID do aplicativo. O importador bloqueia path traversal, entradas duplicadas, tamanhos excessivos e bibliotecas fora da raiz, registra SHA-256 e torna as `.so` somente leitura antes do carregamento. Isso não torna um driver desconhecido confiável.

Use pacotes compilados por você ou hashes publicados por uma fonte que você controla. Não teste ZIP recebido aleatoriamente em um aparelho com dados sensíveis.

## GitHub

Nunca coloque PAT, senha ou client secret em `local.properties`, variáveis do APK, commits ou `BuildConfig`. O fluxo suportado usa GitHub App, Device Flow e Android Keystore. Instale o GitHub App apenas no repositório de resultados e conceda somente `Issues: write` mais `Metadata: read`.

## Reportar vulnerabilidade

Não publique tokens, dumps privados nem fingerprints completos em uma issue pública. Revogue a instalação/token antes de compartilhar uma reprodução que envolva credenciais.
