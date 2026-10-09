# CLAUDE.md — Assistente de Treino IA

Antes de qualquer tarefa, leia `docs/escopo-mvp.md` (regras de negócio, decisões, o que está fora do MVP).
`progresso.md` mostra onde o projeto parou.

## Divisão de trabalho (regra do projeto, revista em 08/10/2026)
- Até o teste com amigos, **você escreve as fatias 1–9** (scripts SQL, DAOs, services, clientes HTTP,
  parse, loop do bot, site), sempre sob especificação do Vitor.
- **O Vitor não delega:** a especificação de cada fatia, o critério de aceite (ele roda), a segurança
  antes de cada push e o texto que vai para os amigos.
- Fatia 0 (`ConnectionFactory`) é do Vitor: não altere sem pedido explícito.

## Como trabalhar
- **Uma fatia por prompt.** Ao terminar, entregue o relatório, diga como testar e **pare**.
- Não altere arquivos fora do que foi pedido. Contradição encontrada: aponte no relatório, não corrija sozinho.
- Se o escopo não responder uma regra, pergunte. Não invente regra de negócio.
- Explique o "porquê" das decisões: o Vitor precisa saber explicar cada parte em entrevista.
- Não commite. Não execute SQL no banco: o Vitor roda os scripts no SQL Developer.

## Convenções
- Java 26, Maven Wrapper, pacote base `dev.vitorrodrigues.treino`. Testes são `main` em `spike/`.
- Banco: Oracle 19c (FIAP); objetos com prefixo `TR_`. DDL e testes em `db/`.
- JDBC: `PreparedStatement` com `?`; try-with-resources; `rs.next()` antes de ler; `setNull` para ausente;
  DAO recebe `Connection` por parâmetro e não comita (quem controla a transação é o service).
- JSON: Jackson 3 (`tools.jackson`). Obrigatório com `required()` + checagem de `isNull()`; opcional vira `null`.
  Nunca `path().asXxx()` (devolve 0 em silêncio).
- Ausente = `null` (tipos wrapper), nunca `0`, `false` ou `'N'`.
- Extrator: prompt v3 idêntico a `docs/prompt-extrator.md`; modelo `claude-haiku-4-5-20251001`, temperature 0.

## Segurança (o repositório é público)
- Segredos só em variável de ambiente: `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID_PERMITIDO` (= admin),
  `ORACLE_URL`, `ORACLE_USER`, `ORACLE_PASSWORD`, `ANTHROPIC_API_KEY`.
- Nunca imprimir token, senha, URL com credencial, texto de mensagem, nome ou id de usuário em log ou exceção.
- O bot processa só usuários com status ATIVO em `TR_USUARIO`; `/aprovar` só do admin; nada de grupos.
- `docs/escopo-mvp.md` fica fora do git: nunca remova a regra do `.gitignore`.

## Não faça
- Nada da seção "Fora do MVP" do `docs/escopo-mvp.md`.