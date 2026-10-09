-- =============================================================================
-- Assistente de Treino IA - Fatia 1: usuário (TR_USUARIO) + ID_USUARIO
-- Alvo: Oracle 19c (FIAP). Só sintaxe compatível com a 19c.
-- Fonte das regras: escopo-mvp.md (decisões de 08/10/2026).
--
-- ORDEM DE EXECUÇÃO (no SQL Developer, como script: F5):
--   1. 03-plano.sql    (exercícios da ficha)
--   2. 04-usuario.sql  (este arquivo)
-- O 02-testes.sql passa a depender deste arquivo: rode-o só depois dele.
--
-- PRÉ-REQUISITO: TR_MENSAGEM, TR_SESSAO e TR_APELIDO VAZIAS.
-- Adicionar coluna NOT NULL sem DEFAULT numa tabela com linhas dá ORA-01758:
-- o Oracle não teria o que pôr nas linhas antigas (e a convenção proíbe DEFAULT).
--
-- NÃO é reexecutável (a segunda vez dá ORA-00955 / ORA-01430).
-- Para refazer do zero: 00-drop.sql -> 01-ddl.sql -> 03-plano.sql -> 04-usuario.sql.
--
-- ENCODING: UTF-8, como o 03-plano.sql (ver o aviso no cabeçalho dele).
--
-- Convenções: as mesmas do 01-ddl.sql (prefixo TR_, constraints nomeadas com
-- até 30 caracteres, VARCHAR2(n CHAR), nenhum DEFAULT, nenhum ON DELETE CASCADE).
-- =============================================================================


-- -----------------------------------------------------------------------------
-- Id do Telegram do administrador: NÃO fica neste arquivo (repositório público).
-- O ACCEPT pergunta uma vez só e guarda o valor na variável de substituição
-- ID_TELEGRAM_ADMIN, usada mais abaixo. Sem o ACCEPT, cada uso da variável
-- abriria uma janela de pergunta nova.
-- SET VERIFY OFF: o SQL Developer não ecoa as linhas "antigo/novo" com o
-- valor já substituído na saída do script.
-- -----------------------------------------------------------------------------
SET VERIFY OFF
ACCEPT ID_TELEGRAM_ADMIN NUMBER PROMPT 'Id do Telegram do administrador (from.id): '


-- -----------------------------------------------------------------------------
-- TR_USUARIO: quem pode usar o bot. Cadastro pelo bot (/start), aprovação
-- pelo administrador (/aprovar).
-- ATENÇÃO ao nome ID_TELEGRAM: aqui é o from.id (a PESSOA no Telegram);
-- em TR_MENSAGEM a coluna de mesmo nome é o message_id (a MENSAGEM).
-- -----------------------------------------------------------------------------
CREATE TABLE TR_USUARIO (
    ID_USUARIO     NUMBER GENERATED ALWAYS AS IDENTITY,
    ID_TELEGRAM    NUMBER(19)         NOT NULL,
    NOME           VARCHAR2(100 CHAR) NOT NULL,
    STATUS         VARCHAR2(9 CHAR)   NOT NULL,
    DATA_CADASTRO  TIMESTAMP          NOT NULL,

    CONSTRAINT PK_TR_USUARIO PRIMARY KEY (ID_USUARIO),

    -- Regra: cada conta do Telegram é um único usuário. É por este id que o
    -- bot reconhece quem mandou a mensagem (filtro de segurança por from.id).
    CONSTRAINT UQ_TR_USUARIO_ID_TELEGRAM UNIQUE (ID_TELEGRAM),

    -- Regra: o cadastro entra PENDENTE; o administrador aprova (ATIVO) ou
    -- bloqueia (BLOQUEADO). O bot só processa quem está ATIVO.
    -- (VARCHAR2(9): tamanho do maior valor permitido, 'BLOQUEADO'.)
    CONSTRAINT CK_TR_USUARIO_STATUS CHECK (STATUS IN ('PENDENTE', 'ATIVO', 'BLOQUEADO'))
);


-- -----------------------------------------------------------------------------
-- ID_USUARIO nas tabelas cujas linhas pertencem a um usuário.
-- Fora desta lista:
--   - TR_EXERCICIO: o catálogo é compartilhado entre os usuários.
--   - TR_SERIE: chega ao usuário pela mensagem de onde veio (mesma ideia de
--     não ter FK direta para TR_SESSAO no 01-ddl.sql).
-- Coluna e FK em comandos separados: se um falhar, o erro aponta qual.
-- -----------------------------------------------------------------------------

-- Regra: toda mensagem gravada tem um remetente conhecido.
ALTER TABLE TR_MENSAGEM ADD (ID_USUARIO NUMBER NOT NULL);

ALTER TABLE TR_MENSAGEM ADD CONSTRAINT FK_TR_MENSAGEM_USUARIO
    FOREIGN KEY (ID_USUARIO) REFERENCES TR_USUARIO (ID_USUARIO);

-- Regra: gravação idempotente POR USUÁRIO. O message_id do Telegram só é
-- único DENTRO de cada chat (documentação da Bot API). Com mais de um
-- usuário, a mensagem 150 de um e a 150 de outro são mensagens diferentes;
-- com o UNIQUE antigo, a segunda seria recusada e tratada como "já gravada"
-- (perda silenciosa). O UNIQUE passa a ser (usuário, id da mensagem).
-- Nome abreviado (USU) para caber em 30 caracteres.
ALTER TABLE TR_MENSAGEM DROP CONSTRAINT UQ_TR_MENSAGEM_ID_TELEGRAM DROP INDEX;

ALTER TABLE TR_MENSAGEM ADD CONSTRAINT UQ_TR_MENSAGEM_USU_ID_TELEGRAM
    UNIQUE (ID_USUARIO, ID_TELEGRAM);

-- Regra: cada treino (sessão) é de um usuário.
ALTER TABLE TR_SESSAO ADD (ID_USUARIO NUMBER NOT NULL);

ALTER TABLE TR_SESSAO ADD CONSTRAINT FK_TR_SESSAO_USUARIO
    FOREIGN KEY (ID_USUARIO) REFERENCES TR_USUARIO (ID_USUARIO);

-- Regra: o vocabulário é de cada usuário ("voador" pode ser um exercício
-- para um e outro exercício para outro).
ALTER TABLE TR_APELIDO ADD (ID_USUARIO NUMBER NOT NULL);

ALTER TABLE TR_APELIDO ADD CONSTRAINT FK_TR_APELIDO_USUARIO
    FOREIGN KEY (ID_USUARIO) REFERENCES TR_USUARIO (ID_USUARIO);

-- Regra: o apelido deixa de ser único no banco inteiro e passa a ser único
-- POR USUÁRIO. O mesmo apelido pode existir para usuários diferentes.
-- DROP INDEX: remove junto o índice que o Oracle criou para o UNIQUE antigo
-- (já é o padrão; aqui fica escrito para não restar dúvida).
ALTER TABLE TR_APELIDO DROP CONSTRAINT UQ_TR_APELIDO_APELIDO DROP INDEX;

ALTER TABLE TR_APELIDO ADD CONSTRAINT UQ_TR_APELIDO_USUARIO_APELIDO
    UNIQUE (ID_USUARIO, APELIDO);


-- -----------------------------------------------------------------------------
-- Administrador (eu). Entra ATIVO direto: é quem aprova os outros.
-- SYSTIMESTAMP = data e hora do servidor do banco no momento do INSERT.
-- -----------------------------------------------------------------------------
INSERT INTO TR_USUARIO (ID_TELEGRAM, NOME, STATUS, DATA_CADASTRO)
VALUES (&ID_TELEGRAM_ADMIN, 'Vitor', 'ATIVO', SYSTIMESTAMP);


-- -----------------------------------------------------------------------------
-- Apelidos do administrador (docs/plano-treino.md), sempre em minúsculas.
-- Exercício e usuário buscados por subconsulta (nome oficial e id do
-- Telegram), nunca por id fixo: o IDENTITY não garante qual número saiu.
-- Se um nome estiver errado, a subconsulta não acha nada, o valor vira NULL
-- e o INSERT falha com ORA-01400 (NOT NULL): erro visível, não apelido solto.
-- "voador" antes de "peck deck": o DAO lista os apelidos na ordem de cadastro.
-- -----------------------------------------------------------------------------
INSERT INTO TR_APELIDO (APELIDO, ID_EXERCICIO, ID_USUARIO)
VALUES ('voador',
        (SELECT ID_EXERCICIO FROM TR_EXERCICIO WHERE NOME = 'Crucifixo máquina'),
        (SELECT ID_USUARIO FROM TR_USUARIO WHERE ID_TELEGRAM = &ID_TELEGRAM_ADMIN));

INSERT INTO TR_APELIDO (APELIDO, ID_EXERCICIO, ID_USUARIO)
VALUES ('peck deck',
        (SELECT ID_EXERCICIO FROM TR_EXERCICIO WHERE NOME = 'Crucifixo máquina'),
        (SELECT ID_USUARIO FROM TR_USUARIO WHERE ID_TELEGRAM = &ID_TELEGRAM_ADMIN));

INSERT INTO TR_APELIDO (APELIDO, ID_EXERCICIO, ID_USUARIO)
VALUES ('peito inferior',
        (SELECT ID_EXERCICIO FROM TR_EXERCICIO WHERE NOME = 'Crossover polia alta'),
        (SELECT ID_USUARIO FROM TR_USUARIO WHERE ID_TELEGRAM = &ID_TELEGRAM_ADMIN));

INSERT INTO TR_APELIDO (APELIDO, ID_EXERCICIO, ID_USUARIO)
VALUES ('voador posterior',
        (SELECT ID_EXERCICIO FROM TR_EXERCICIO WHERE NOME = 'Crucifixo inverso máquina'),
        (SELECT ID_USUARIO FROM TR_USUARIO WHERE ID_TELEGRAM = &ID_TELEGRAM_ADMIN));

INSERT INTO TR_APELIDO (APELIDO, ID_EXERCICIO, ID_USUARIO)
VALUES ('francês',
        (SELECT ID_EXERCICIO FROM TR_EXERCICIO WHERE NOME = 'Tríceps francês'),
        (SELECT ID_USUARIO FROM TR_USUARIO WHERE ID_TELEGRAM = &ID_TELEGRAM_ADMIN));

INSERT INTO TR_APELIDO (APELIDO, ID_EXERCICIO, ID_USUARIO)
VALUES ('testa',
        (SELECT ID_EXERCICIO FROM TR_EXERCICIO WHERE NOME = 'Tríceps testa barra'),
        (SELECT ID_USUARIO FROM TR_USUARIO WHERE ID_TELEGRAM = &ID_TELEGRAM_ADMIN));

INSERT INTO TR_APELIDO (APELIDO, ID_EXERCICIO, ID_USUARIO)
VALUES ('cavalinho',
        (SELECT ID_EXERCICIO FROM TR_EXERCICIO WHERE NOME = 'Remada cavalinho'),
        (SELECT ID_USUARIO FROM TR_USUARIO WHERE ID_TELEGRAM = &ID_TELEGRAM_ADMIN));

INSERT INTO TR_APELIDO (APELIDO, ID_EXERCICIO, ID_USUARIO)
VALUES ('hack',
        (SELECT ID_EXERCICIO FROM TR_EXERCICIO WHERE NOME = 'Agachamento hack'),
        (SELECT ID_USUARIO FROM TR_USUARIO WHERE ID_TELEGRAM = &ID_TELEGRAM_ADMIN));

INSERT INTO TR_APELIDO (APELIDO, ID_EXERCICIO, ID_USUARIO)
VALUES ('crunch',
        (SELECT ID_EXERCICIO FROM TR_EXERCICIO WHERE NOME = 'Abdominal na corda'),
        (SELECT ID_USUARIO FROM TR_USUARIO WHERE ID_TELEGRAM = &ID_TELEGRAM_ADMIN));


COMMIT;

UNDEFINE ID_TELEGRAM_ADMIN
