# Assistente de Treino IA — Escopo do MVP

## O que é
Registro de treino por conversa. Mando texto ou áudio no Telegram como se falasse com um personal ("academia lotada, fiz 3x20kg no supino, falhei na última..."). O sistema extrai os dados, salva no banco, confirma no chat e mostra a evolução num dashboard web simples.

## Para quem
- **Usuário único no MVP: eu.** Treino todo dia e hoje anoto no bloco de notas.
- Amigos podem testar depois, se o MVP se provar.
- Não é produto comercial. Monetização está fora de discussão até o uso próprio funcionar.

## Objetivos
1. Parar de usar o bloco de notas.
2. Portfólio: projeto completo com back-end, front-end, banco de dados e IA integrada.
3. Revisar a Fase 06 (JDBC, DAO, Oracle) na prática.

## Dentro do MVP
- Entrada por **texto e áudio** via Telegram.
- Áudio → transcrição → texto.
- Texto → LLM (API da Anthropic) → JSON estruturado.
- **Validação em Java** antes de gravar (carga numérica, exercício reconhecido etc.).
- Gravação no Oracle via JDBC/DAO.
- **Texto bruto sempre salvo**, para reprocessar se o prompt melhorar.
- Bot **confirma o que entendeu**, e eu posso corrigir.
- Dashboard web minimalista: evolução de carga e histórico.

## Fora do MVP (explicitamente)
- WhatsApp (APIs não oficiais têm risco de banimento; trocar o canal depois é trocar uma classe).
- Análise de execução por vídeo.
- Fotos de evolução / leitura de ficha por imagem.
- Login, múltiplos usuários, cobrança (sem tabela de usuário no banco).
- Sugestão de carga (ver "Para depois do MVP").

## Decisões tomadas
- **Consultas por chat entram no MVP.** A dor principal é decorar a carga no meio do treino.
- **Plano de treino entra no MVP como script SQL de INSERT** (não formulário no site). Serve de lista oficial de exercícios para o extrator reconhecer nomes. Formulário web fica para depois.
- **Carga:** cada exercício tem uma forma de registro fixa (por lado ou total). O que importa é a consistência dentro do mesmo exercício.
- **Repetições:** "7 a 8 mais ou menos" vira o menor valor (7), marcado como aproximado. Conservador: nunca mostrar progresso que talvez não aconteceu.
- **A IA nunca estima nem inventa.** Registra o que foi dito, com o grau de certeza dito. Faltou informação = vazio.
- **O Java também nunca inventa.** Campo ausente no JSON vira `null` (wrapper `Long`/`Double` + `setNull` no JDBC), nunca `0`. Campo obrigatório ausente falha alto (`required()`), nunca segue com valor padrão. *(Descoberto no teste do `"idd"`: `path().asLong()` devolve 0 em silêncio.)*
- **Data da sessão = data de envio da mensagem** (campo `date` do Telegram, convertido de UTC para America/Sao_Paulo no Java), nunca a hora em que o Java leu. Com o notebook desligado, o Telegram entrega a mensagem até 24 h depois.
- **Gravação idempotente (07/10/2026).** O bot processa e só depois avança o `offset` (nunca perde mensagem, mas pode reprocessar em caso de erro de rede). Para não gravar duplicado, o id da mensagem do Telegram é salvo com restrição `UNIQUE`: na segunda tentativa o Oracle recusa e o Java sabe que já foi gravado.
- **Toda mensagem é gravada crua antes do extrator (07/10/2026).** Relato ou consulta, ela entra em `TR_MENSAGEM` primeiro. Assim nada se perde se o LLM classificar errado ou a API falhar, e o `UNIQUE` barra duplicata já na entrada. Mensagem sem sessão = consulta ou extração pendente/falha.
- **Várias sessões na mesma data** são permitidas pelo banco (sem `UNIQUE` na data). No MVP, o Java usa uma sessão por data.
- **Apelido aponta para um único exercício** e é gravado em minúsculas. Nome ambíguo ("remada") não vira apelido: vira dúvida que o bot pergunta.
- **Edição de mensagem (`edited_message`) é ignorada no MVP.** Correção é sempre mensagem nova. O bot deve avisar isso quando eu editar (passo 9).
- **Filtro de segurança por `from.id`** (quem enviou), não por `chat.id` (onde foi enviado). Bot bloqueado para grupos no BotFather (`/setjoingroups` → Disable).
- **Banco (07/10/2026):** desenvolvimento no Oracle da FIAP (19c), com prefixo `TR_` em todos os objetos, inclusive constraints (o esquema é o mesmo das atividades da faculdade). O instalador do Oracle Free não aceita Windows Home. **Antes do uso real**, migrar para Oracle Free em contêiner (Docker/WSL2) ou Oracle Cloud Always Free. A DDL fica versionada em `db/` e usa só sintaxe da 19c: migrar = rodar `01-ddl.sql` + trocar as variáveis de ambiente da ConnectionFactory.
- **Limitação conhecida:** o bot só responde com o notebook ligado e o Java rodando (independe de onde está o banco). Registro tolera isso (Telegram guarda 24 h); consulta no meio do treino não. Solução futura: rodar o Java numa máquina sempre ligada.

## Modelo de domínio
**Exercício** (vem do plano, cadastrado uma vez)
- nome oficial (único)
- grupamento
- forma de registro da carga: por lado / total
- faixa alvo de repetições (mín. e máx.), para dupla progressão

**Apelido** (vocabulário aprendido)
- ex.: "pull down" → Puxada pegada romana aberta. Cada apelido aponta para um único exercício.
- Quando confirmo um nome novo, ele vira apelido no banco e vai junto no prompt nas próximas vezes. O sistema aprende meu vocabulário sem a IA ter memória.

**Sessão** (um treino)
- data (sem hora)
- observação do dia (academia cheia, tempo curto, treino trocado, alimentação...)

**Mensagem** (cada mensagem recebida, relato ou consulta)
- id da mensagem do Telegram (`UNIQUE`: idempotência)
- data/hora de envio
- origem: texto ou áudio
- texto bruto (no áudio, a transcrição)
- sessão (vazia em consultas e extrações pendentes)

**Série** (cada série executada, ligada à mensagem de onde veio)
- exercício relatado (como eu falei) + exercício (ligado à lista oficial; vazio até eu confirmar)
- número da série
- carga em kg (vazio se não dita) + carga aproximada? (sim/não)
- repetições (vazio se "não contei") + repetições aproximadas? (sim/não)
- chegou na falha? (sim / não / vazio = não foi dito)
- percepção (texto livre; "beirando a falha" vai aqui)

## Banco — DDL concluída (07/10/2026)
Arquivos em `db/`: `00-drop.sql` (apagar é ato explícito, separado), `01-ddl.sql` (5 tabelas), `02-testes.sql` (termina com ROLLBACK).

**Convenções:** constraints nomeadas `PK_/FK_/CK_/UQ_TR_<TABELA>_...` em até 30 caracteres; `VARCHAR2(n CHAR)`; sim/não = `CHAR(1)` 'S'/'N' (a 19c não tem BOOLEAN); nenhum `DEFAULT`; nenhum `ON DELETE CASCADE`; IDs `GENERATED ALWAYS AS IDENTITY` (o script do plano busca o id pelo nome); texto bruto em `CLOB`.

**Testes:** 8/8 conforme o esperado (6 rejeições, cada uma pela constraint prevista; 2 inserções aceitas).

**Descoberta:** `CHECK (falha IN ('S','N'))` deixa o NULL passar. Comparar NULL dá "desconhecido", e o Oracle só rejeita quando o CHECK dá falso. É assim que o terceiro estado funciona sem regra extra.

**Pendente (passo 6):** áudio cuja transcrição falhou não pode ser gravado hoje (`TEXTO_BRUTO NOT NULL`). Resolver no spike de áudio com `ALTER TABLE` (ex.: guardar o `file_id` do áudio e aceitar texto nulo quando a origem for AUDIO).

## Para depois do MVP
- Sugestão de carga por dupla progressão (o modelo já guarda faixa alvo + reps realizadas).
- Repetições em reserva (RIR) como campo numérico. Exige prompt v4 + regressão.
- Formulário de plano de treino no site.
- Relação alimentação × desempenho (o texto bruto já fica guardado).

## Stack
| Camada | Escolha | Por quê |
|---|---|---|
| Canal | Telegram Bot API (long polling) | Oficial, grátis, sem URL pública nem hospedagem no MVP |
| Back-end | Java 26 + Maven (com Maven Wrapper) | Matéria da FIAP, foco em backend; wrapper fixa a versão do Maven para quem clonar |
| HTTP | `java.net.http` (HttpClient do JDK) | Já vem no Java; sem dependência extra |
| JSON | Jackson 3.x (`tools.jackson`) | Padrão de mercado (Spring Boot 4 usa); parser na mão quebraria com acento, emoji e objetos aninhados |
| Persistência | JDBC + DAO + ConnectionFactory | Revisão da Fase 06; troca de banco num ponto só |
| Banco | Oracle da FIAP 19c (dev) → Oracle Free em contêiner ou Cloud Always Free (antes do uso real) | Começar sem instalar; dado definitivo tem que ser meu e não expirar |
| Transcrição | Serviço de speech-to-text (a definir) | A API do Claude não transcreve áudio |
| Extração | API da Anthropic, modelo pequeno, saída JSON | Tarefa simples, custo baixo |
| Front | HTML/CSS/JS + Chart.js, servido pelo Java local | JS é lacuna minha: estudar, não copiar |
| Telas | Prototipar no Claude Design antes de codar | |

**Repositório:** github.com/vitorrodrigues-dev/assistente-treino (público).
**Orçamento:** até R$ 50/mês em API. Configurar limite de gasto no console desde o dia 1.
**Atenção:** a assinatura Claude Pro **não** inclui créditos de API; são cobrados à parte.

## Arquitetura
```
Telegram (texto/áudio)
  → Java (polling)
  → grava a mensagem crua (UNIQUE barra duplicata)
  → transcrição (se áudio)
  → LLM: relato → JSON
  → validação em Java
  → DAO/JDBC → Oracle
  → bot confirma no chat

Dashboard (HTML/JS) → endpoint REST em Java → mesmo banco
```

## Segurança (regras desde o primeiro commit)
- Chave da API e credenciais do banco **fora do código**: variáveis de ambiente / `.gitignore`. O repositório é público.
- Variáveis em uso: `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID_PERMITIDO`.
- **Segredo nunca é digitado no terminal**: o PowerShell grava o histórico em arquivo. Criar/editar pela tela "Editar as variáveis de ambiente para sua conta".
- Token, URL com token e texto de terceiros **nunca impressos** em log ou exceção.
- Bot processa **somente o meu `from.id`**; o resto é ignorado. Bot não entra em grupos.
- PreparedStatement em toda query.
- Dashboard sem login só enquanto for local. Online exige no mínimo um token.
- **Consumidor desconhecido usando o token = token vazado → `/revoke` no BotFather.** (Feito em 07/10/2026 após 409 e mensagens sumindo.)

## Regras de aprendizado
- **Eu escrevo:** ConnectionFactory, DAOs, regras de negócio. A IA revisa, não escreve.
- **DDL (mudança em 07/10/2026):** gerada com IA (Claude Code) a partir das minhas decisões, uma por uma. Eu reviso, rodo os testes e explico cada constraint.
- **IA pode acelerar:** cola de HTTP/JSON, configuração de build/Git e o front, desde que eu saiba explicar cada parte.
- Teste de entrevista: se eu não consigo explicar um trecho, ele não está pronto.

## Riscos conhecidos
- O LLM vai errar a extração → confirmação + correção + texto bruto salvo.
- Nomes de exercício inconsistentes ("supino" vs "supino reto") quebram as consultas → normalizar exercícios.
- **Registro duplicado** (reprocessamento após erro de rede, ou duas instâncias do bot rodando) → gravação idempotente com id da mensagem `UNIQUE`.
- **Rodar script de DROP no banco com dado real** → DROPs isolados em `00-drop.sql`; nunca rodar no banco definitivo depois que houver uso real.
- Projeto virar substituto das candidaturas a estágio → candidaturas da semana primeiro.
- Fase 06 tem prioridade; o projeto usa horários que não a atrapalham.

## Spike do extrator — concluído (06/10/2026)
Testado no Claude Haiku 4.5 (modelo barato que o sistema vai usar). Prompt atual: `prompt-extrator.md` (v3).

| Teste | v1 | v2 | v3 |
|---|---|---|---|
| Bloco de notas (casos difíceis) | `falha` inventada | ✔ | ✔ |
| Exercício fora da lista ("pull down") | — | descartou as séries | ✔ |
| Ditado por voz, fora de ordem | — | — | ✔ |

**Descobertas:**
1. O Haiku dá conta da extração: custo baixo confirmado.
2. "Nunca invente" precisa ser explícito campo a campo, senão ele chuta `false`.
3. Dúvida num campo nunca descarta a série → campo `exercicio_relatado`.
4. Apelidos de exercício ficam no banco.
5. O extrator não conhece meu histórico → **validação de salto de carga é regra de negócio em Java** (comparar com a última carga do exercício e pedir confirmação se o salto for absurdo; pega erro de transcrição).
6. A IA não tem memória: a memória do sistema é o banco. Consultas = IA interpreta a pergunta → Java faz SELECT → resposta.
7. Data de hoje e lista de exercícios são **injetadas** no prompt pelo Java; o prompt é um template fixo e o relato é o parâmetro (mesma ideia do PreparedStatement).
8. Toda mudança no prompt exige rerodar os casos antigos (regressão).

**Pendente:** testar nome parcial ambíguo quando a lista tiver dois exercícios parecidos (ex.: remada unilateral máquina × halter).

## Spike do Telegram — concluído (07/10/2026)
Classes em `dev.vitorrodrigues.treino.spike`: `GetUpdatesSpike`, `EcoSpike`.

**Testado:** eco de texto ✔ · aspas e acentos (`não "teste" ção`) ✔ · foto ignorada sem travar o bot ✔ · remetente filtrado por `from.id` ✔.

**Descobertas:**
1. **`offset`** confirma ao Telegram o que já foi lido (`update_id + 1`). Tem que avançar **até em update ignorado**, senão o bot fica preso nele para sempre.
2. **Long polling:** `getUpdates?timeout=30` segura a conexão até chegar mensagem; o timeout HTTP do Java precisa ser maior (40 s).
3. **409 Conflict** = duas instâncias fazendo `getUpdates` com o mesmo token. Duas instâncias também = resposta (e futuro registro) em dobro.
4. Telegram guarda update pendente por no máximo 24 h.
5. Jackson: `path()` → 0 silencioso; `get()` → `null` e NPE depois; `required()` → exceção imediata com o nome do campo.
6. Corpo do `sendMessage` montado com Jackson (`ObjectNode`), nunca concatenando string.

## Próximo passo
Script de INSERT do meu plano de treino + apelidos (`db/03-plano.sql`). Depois, ConnectionFactory lendo credenciais de variável de ambiente. A transcrição de áudio (passo 6) fica para depois da fatia vertical de texto.

## Ordem de construção
Fatia vertical fina primeiro: uma mensagem de texto atravessa todas as camadas e vira uma linha no banco com confirmação no chat. Depois engordar cada camada (áudio, consultas, dashboard).

## Critério de pronto do MVP
**2 semanas sem abrir o bloco de notas.**
