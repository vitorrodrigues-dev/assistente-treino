# CLAUDE.md — Assistente de Treino IA

Antes de qualquer tarefa, leia `escopo-mvp.md` (regras de negócio, decisões, o que está fora do MVP).
`progresso.md` mostra onde o projeto parou.

## Divisão de trabalho (regra do projeto)
- **O Vitor escreve:** `ConnectionFactory`, DAOs e as classes de regra de negócio (services).
  Nessas partes você revisa e aponta erros, mas **não escreve o código**, nem parcial, nem "exemplo".
- **Você pode escrever:** configuração de build (`pom.xml`), clientes HTTP (Telegram e API da Anthropic),
  parse de JSON com Jackson, records de modelo, o `main`/loop de cola e scripts SQL de dados quando pedido.
- Se um pedido cair na parte do Vitor, avise e pare.

## Como trabalhar
- Uma fatia por vez. Ao terminar, diga como testar e **pare**.
- Não altere arquivos fora do que foi pedido.
- Se o escopo não responder uma regra, pergunte. Não invente regra de negócio.
- Explique o "porquê" das decisões: o Vitor está aprendendo e precisa saber explicar cada parte.

## Convenções
- Java 26, Maven Wrapper, pacote base `dev.vitorrodrigues.treino`.
- Banco: Oracle 19c (FIAP) em desenvolvimento; objetos com prefixo `TR_`. DDL e testes em `db/`.
- JSON: Jackson 3 (`tools.jackson`). Campo obrigatório com `required()`; opcional vira `null`.
  Nunca `path().asXxx()` (devolve 0 em silêncio).
- Ausente = `null` (tipos wrapper), nunca `0` ou valor padrão.

## Segurança (o repositório é público)
- Segredos só em variável de ambiente: `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID_PERMITIDO`,
  `ORACLE_URL`, `ORACLE_USER`, `ORACLE_PASSWORD`, `ANTHROPIC_API_KEY`.
- Nunca imprimir token, senha, URL com credencial ou texto de terceiros em log ou exceção.
- `PreparedStatement` em toda query; nunca concatenar SQL.
- O bot processa somente o `from.id` permitido.

## Não faça
- Nada da seção "Fora do MVP" do `escopo-mvp.md`.
- Não execute SQL no banco: o Vitor roda os scripts no SQL Developer.
