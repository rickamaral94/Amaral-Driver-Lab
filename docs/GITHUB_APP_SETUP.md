# GitHub App para publicar resultados

A autenticação automática usa o Device Flow de um GitHub App. O APK contém somente o **Client ID**, que é público; o usuário autoriza no navegador e o token fica criptografado pelo Android Keystore.

## 1. Criar o GitHub App

Na conta/organização que manterá `Amaral-Driver-Lab`:

1. Abra **Settings → Developer settings → GitHub Apps → New GitHub App**.
2. Use um nome como `Amaral Driver Lab Reporter` e a URL do repositório como homepage.
3. Desative webhook, pois este app não recebe eventos.
4. Em **Repository permissions**, conceda somente:
   - **Metadata: Read-only** (obrigatória pelo GitHub);
   - **Issues: Read and write**.
5. Limite a instalação à conta desejada e instale o app **somente** em `Amaral-Driver-Lab`.
6. Nas configurações do app, habilite **Device Flow**.

Não gere nem distribua PAT. O client secret também não é necessário para este fluxo e não deve entrar em Gradle, Actions ou no APK.

## 2. Configurar builds

Para GitHub Actions, crie a variável de repositório `GITHUB_CLIENT_ID` em **Settings → Secrets and variables → Actions → Variables**. O workflow a injeta durante o build.

Para build local, adicione ao `local.properties`:

```properties
GITHUB_CLIENT_ID=Iv1.seuClientIdPublico
```

## 3. Fluxo no aparelho

1. Toque em **Conectar GitHub**.
2. Copie/confira o código mostrado e autorize no domínio `github.com`.
3. Execute a suíte com **Criar issue automaticamente** marcado.

Se o token expirar, a API responderá sem autorização; o JSON permanece no aparelho e basta desconectar/conectar novamente. A publicação nunca é condição para preservar o resultado.

## Modelo de ameaça resumido

O GitHub App reduz o impacto de um token vazado porque a instalação e as permissões ficam restritas. Ainda assim, uma biblioteca `.so` importada executa no UID do APK. No MVP, importe apenas pacotes confiáveis e use hashes revisados. Uma futura versão pode mover o runner para um serviço isolado com passagem de descritores de arquivo.
