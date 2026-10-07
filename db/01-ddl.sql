-- =============================================================================
-- Assistente de Treino IA - DDL do MVP
-- Alvo: Oracle 19c (FIAP) e Oracle Free 23ai. Só sintaxe compatível com a 19c.
-- Fonte das regras: escopo-mvp.md
--
-- Convenções:
--   - Todo objeto começa com TR_ (esquema compartilhado com atividades da faculdade).
--   - Constraints nomeadas: PK_TR_<TABELA>, FK_TR_<TABELA>_<REFERENCIA>,
--     CK_TR_<TABELA>_<COLUNA>, UQ_TR_<TABELA>_<COLUNA>.
--   - Todos os nomes têm no máximo 30 caracteres (limite antigo do Oracle; a 19c
--     aceita 128, mas só se o banco estiver com COMPATIBLE >= 12.2).
--   - VARCHAR2 sempre com CHAR explícito: o tamanho é em caracteres, não em bytes
--     (acento ocupa 2 bytes em UTF-8).
--   - Sim/não = CHAR(1) 'S'/'N'. A 19c não tem BOOLEAN.
--   - Nenhum DEFAULT: o Java sempre envia o valor; ausência é NULL, nunca padrão.
--   - ON DELETE padrão (sem CASCADE): apagar uma linha que ainda é referenciada
--     falha. Nada some sem querer.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- TR_EXERCICIO: lista oficial de exercícios (vem do plano de treino).
-- -----------------------------------------------------------------------------
CREATE TABLE TR_EXERCICIO (
    ID_EXERCICIO  NUMBER GENERATED ALWAYS AS IDENTITY,
    NOME          VARCHAR2(100 CHAR) NOT NULL,
    GRUPAMENTO    VARCHAR2(50 CHAR)  NOT NULL,
    FORMA_CARGA   VARCHAR2(8 CHAR)   NOT NULL,
    REPS_MIN      NUMBER(3),
    REPS_MAX      NUMBER(3),

    -- Regra: cada exercício é identificado por um id gerado pelo banco.
    CONSTRAINT PK_TR_EXERCICIO PRIMARY KEY (ID_EXERCICIO),

    -- Regra: nome oficial não se repete (o plano busca o id por subconsulta pelo nome).
    CONSTRAINT UQ_TR_EXERCICIO_NOME UNIQUE (NOME),

    -- Regra: forma de registro da carga é só "por lado" ou "total", fixa por exercício.
    CONSTRAINT CK_TR_EXERCICIO_FORMA_CARGA CHECK (FORMA_CARGA IN ('POR_LADO', 'TOTAL')),

    -- Regra: faixa alvo de repetições começa em pelo menos 1.
    CONSTRAINT CK_TR_EXERCICIO_REPS_MIN CHECK (REPS_MIN >= 1),

    -- Regra: faixa alvo coerente (mínimo não passa do máximo).
    CONSTRAINT CK_TR_EXERCICIO_REPS_MAX CHECK (REPS_MIN <= REPS_MAX),

    -- Regra: faixa alvo é opcional (ao trocar de treino, o usuário não é obrigado
    -- a inventar meta), mas vem inteira: as duas preenchidas ou as duas nulas.
    CONSTRAINT CK_TR_EXERCICIO_FAIXA CHECK (
        (REPS_MIN IS NULL AND REPS_MAX IS NULL)
        OR (REPS_MIN IS NOT NULL AND REPS_MAX IS NOT NULL)
    )
);


-- -----------------------------------------------------------------------------
-- TR_APELIDO: vocabulário aprendido ("pull down" -> exercício oficial).
-- -----------------------------------------------------------------------------
CREATE TABLE TR_APELIDO (
    ID_APELIDO    NUMBER GENERATED ALWAYS AS IDENTITY,
    APELIDO       VARCHAR2(100 CHAR) NOT NULL,
    ID_EXERCICIO  NUMBER             NOT NULL,

    CONSTRAINT PK_TR_APELIDO PRIMARY KEY (ID_APELIDO),

    -- Regra: cada apelido aponta para exatamente UM exercício (apelido único).
    -- Nome ambíguo não vira apelido; vira dúvida que o bot pergunta.
    CONSTRAINT UQ_TR_APELIDO_APELIDO UNIQUE (APELIDO),

    -- Regra: apelido sempre em minúsculas (o UNIQUE do Oracle diferencia caixa;
    -- sem isso "Pull down" e "pull down" seriam apelidos diferentes).
    CONSTRAINT CK_TR_APELIDO_APELIDO CHECK (APELIDO = LOWER(APELIDO)),

    -- Regra: apelido sempre ligado a um exercício oficial existente.
    CONSTRAINT FK_TR_APELIDO_EXERCICIO FOREIGN KEY (ID_EXERCICIO)
        REFERENCES TR_EXERCICIO (ID_EXERCICIO)
);


-- -----------------------------------------------------------------------------
-- TR_SESSAO: um treino. Pode haver mais de uma sessão na mesma data
-- (por isso NÃO há UNIQUE em DATA_SESSAO).
-- -----------------------------------------------------------------------------
CREATE TABLE TR_SESSAO (
    ID_SESSAO    NUMBER GENERATED ALWAYS AS IDENTITY,
    DATA_SESSAO  DATE                NOT NULL,
    OBSERVACAO   VARCHAR2(1000 CHAR),

    CONSTRAINT PK_TR_SESSAO PRIMARY KEY (ID_SESSAO),

    -- Regra: sessão é um DIA de treino; a data não carrega hora (senão
    -- WHERE DATA_SESSAO = DATE '2026-10-07' não acharia a sessão).
    CONSTRAINT CK_TR_SESSAO_DATA_SESSAO CHECK (DATA_SESSAO = TRUNC(DATA_SESSAO))
);


-- -----------------------------------------------------------------------------
-- TR_MENSAGEM: toda mensagem recebida, gravada ANTES do extrator.
-- ID_TELEGRAM = message_id do Telegram (único dentro do chat; usuário único no MVP).
-- -----------------------------------------------------------------------------
CREATE TABLE TR_MENSAGEM (
    ID_MENSAGEM      NUMBER GENERATED ALWAYS AS IDENTITY,
    ID_TELEGRAM      NUMBER(19)        NOT NULL,
    DATA_HORA_ENVIO  DATE              NOT NULL,
    ORIGEM           VARCHAR2(5 CHAR)  NOT NULL,
    TEXTO_BRUTO      CLOB              NOT NULL,
    ID_SESSAO        NUMBER,

    CONSTRAINT PK_TR_MENSAGEM PRIMARY KEY (ID_MENSAGEM),

    -- Regra: gravação idempotente. Reprocessar a mesma mensagem (erro de rede,
    -- duas instâncias do bot) é recusado pelo banco; o Java sabe que já gravou.
    CONSTRAINT UQ_TR_MENSAGEM_ID_TELEGRAM UNIQUE (ID_TELEGRAM),

    -- Regra: a mensagem chega como texto ou áudio (áudio grava a transcrição).
    CONSTRAINT CK_TR_MENSAGEM_ORIGEM CHECK (ORIGEM IN ('TEXTO', 'AUDIO')),

    -- Regra: uma sessão tem várias mensagens. FK ACEITA NULO: sem sessão =
    -- consulta, ou extração pendente/falha.
    CONSTRAINT FK_TR_MENSAGEM_SESSAO FOREIGN KEY (ID_SESSAO)
        REFERENCES TR_SESSAO (ID_SESSAO)
);


-- -----------------------------------------------------------------------------
-- TR_SERIE: cada série executada, extraída de uma mensagem.
-- Chega à sessão pela mensagem (sem FK direta para TR_SESSAO, para não haver
-- dois caminhos que possam discordar).
-- -----------------------------------------------------------------------------
CREATE TABLE TR_SERIE (
    ID_SERIE            NUMBER GENERATED ALWAYS AS IDENTITY,
    ID_MENSAGEM         NUMBER             NOT NULL,
    EXERCICIO_RELATADO  VARCHAR2(100 CHAR) NOT NULL,
    ID_EXERCICIO        NUMBER,
    NUMERO_SERIE        NUMBER(3)          NOT NULL,
    CARGA_KG            NUMBER(6,2),
    CARGA_APROX         CHAR(1)            NOT NULL,
    REPETICOES          NUMBER(3),
    REPETICOES_APROX    CHAR(1)            NOT NULL,
    FALHA               CHAR(1),
    PERCEPCAO           VARCHAR2(500 CHAR),

    CONSTRAINT PK_TR_SERIE PRIMARY KEY (ID_SERIE),

    -- Regra: toda série aponta para a mensagem de onde veio.
    CONSTRAINT FK_TR_SERIE_MENSAGEM FOREIGN KEY (ID_MENSAGEM)
        REFERENCES TR_MENSAGEM (ID_MENSAGEM),

    -- Regra: exercício oficial ACEITA NULO, vazio até o usuário confirmar.
    -- (EXERCICIO_RELATADO NOT NULL acima: dúvida no nome nunca descarta a série.)
    CONSTRAINT FK_TR_SERIE_EXERCICIO FOREIGN KEY (ID_EXERCICIO)
        REFERENCES TR_EXERCICIO (ID_EXERCICIO),

    -- Regra: a contagem de séries começa em 1.
    CONSTRAINT CK_TR_SERIE_NUMERO_SERIE CHECK (NUMERO_SERIE >= 1),

    -- Regra: carga em kg nunca é negativa (NULL continua aceito = não foi dita).
    CONSTRAINT CK_TR_SERIE_CARGA_KG CHECK (CARGA_KG >= 0),

    -- Regra: repetições nunca são negativas (NULL continua aceito = "não contei").
    CONSTRAINT CK_TR_SERIE_REPETICOES CHECK (REPETICOES >= 0),

    -- Regra: carga aproximada é sim/não, sempre informado pelo Java (sem padrão).
    CONSTRAINT CK_TR_SERIE_CARGA_APROX CHECK (CARGA_APROX IN ('S', 'N')),

    -- Regra: repetições aproximadas é sim/não, sempre informado pelo Java (sem padrão).
    CONSTRAINT CK_TR_SERIE_REPETICOES_APROX CHECK (REPETICOES_APROX IN ('S', 'N')),

    -- Regra: falha tem três estados: 'S', 'N' ou NULL (= não foi dito).
    -- A IA nunca inventa: ausência é NULL, nunca 0 ou valor padrão.
    -- (CHECK com NULL não reprova: NULL IN ('S','N') dá "desconhecido", e o
    -- Oracle só rejeita quando o CHECK dá FALSO.)
    CONSTRAINT CK_TR_SERIE_FALHA CHECK (FALHA IN ('S', 'N'))
);
