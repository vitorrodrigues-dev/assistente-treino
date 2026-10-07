-- =============================================================================
-- Testes das regras do MVP (rodar DEPOIS do 01-ddl.sql, como script: F5).
-- Derivados das REGRAS do escopo-mvp.md.
-- Esperado: 7 erros (testes R1-R7) e 3 sucessos (testes P1-P3).
-- O arquivo termina com ROLLBACK: nada fica gravado no banco.
-- Valores de teste usam ID_TELEGRAM 999000xxx e nomes "TESTE ..." para não
-- colidir com dados reais.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- PREPARAÇÃO (não conta como teste; tudo aqui deve passar)
-- -----------------------------------------------------------------------------
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('TESTE Supino reto', 'Peito', 'TOTAL', 8, 12);

INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('TESTE Supino inclinado halter', 'Peito', 'POR_LADO', 8, 12);

INSERT INTO TR_APELIDO (APELIDO, ID_EXERCICIO)
VALUES ('teste supino',
        (SELECT ID_EXERCICIO FROM TR_EXERCICIO WHERE NOME = 'TESTE Supino reto'));

INSERT INTO TR_SESSAO (DATA_SESSAO, OBSERVACAO)
VALUES (DATE '2026-10-07', 'academia lotada');

INSERT INTO TR_MENSAGEM (ID_TELEGRAM, DATA_HORA_ENVIO, ORIGEM, TEXTO_BRUTO, ID_SESSAO)
VALUES (999000001,
        TO_DATE('2026-10-07 18:30:00', 'YYYY-MM-DD HH24:MI:SS'),
        'TEXTO',
        'academia lotada, fiz 3x20kg no supino, falhei na última',
        (SELECT MAX(ID_SESSAO) FROM TR_SESSAO WHERE DATA_SESSAO = DATE '2026-10-07'));


-- -----------------------------------------------------------------------------
-- R1. Regra: gravação idempotente (a mesma mensagem do Telegram não entra 2 vezes).
-- Esperado: ORA-00001 unique constraint (UQ_TR_MENSAGEM_ID_TELEGRAM) violated
-- -----------------------------------------------------------------------------
INSERT INTO TR_MENSAGEM (ID_TELEGRAM, DATA_HORA_ENVIO, ORIGEM, TEXTO_BRUTO)
VALUES (999000001,
        TO_DATE('2026-10-07 18:30:00', 'YYYY-MM-DD HH24:MI:SS'),
        'TEXTO',
        'mesma mensagem reprocessada');


-- -----------------------------------------------------------------------------
-- R2. Regra: cada apelido aponta para exatamente UM exercício.
-- "teste supino" já aponta para o supino reto; tentar ligá-lo ao inclinado.
-- Esperado: ORA-00001 unique constraint (UQ_TR_APELIDO_APELIDO) violated
-- -----------------------------------------------------------------------------
INSERT INTO TR_APELIDO (APELIDO, ID_EXERCICIO)
VALUES ('teste supino',
        (SELECT ID_EXERCICIO FROM TR_EXERCICIO WHERE NOME = 'TESTE Supino inclinado halter'));


-- -----------------------------------------------------------------------------
-- R3. Regra: a IA nunca inventa; "falha" só aceita 'S', 'N' ou NULL.
-- Gravar 0 (o "valor padrão silencioso" que o escopo proíbe).
-- Esperado: ORA-02290 check constraint (CK_TR_SERIE_FALHA) violated
-- -----------------------------------------------------------------------------
INSERT INTO TR_SERIE (ID_MENSAGEM, EXERCICIO_RELATADO, NUMERO_SERIE,
                      CARGA_KG, CARGA_APROX, REPETICOES, REPETICOES_APROX, FALHA)
VALUES ((SELECT ID_MENSAGEM FROM TR_MENSAGEM WHERE ID_TELEGRAM = 999000001),
        'supino', 1, 20, 'N', 10, 'N', '0');


-- -----------------------------------------------------------------------------
-- R4. Regra: dúvida no nome nunca descarta a série; o exercício como foi
-- relatado é obrigatório. Série sem EXERCICIO_RELATADO.
-- Esperado: ORA-01400 cannot insert NULL into ("...","TR_SERIE","EXERCICIO_RELATADO")
-- (NOT NULL não tem nome próprio no padrão adotado; o erro cita a coluna.)
-- -----------------------------------------------------------------------------
INSERT INTO TR_SERIE (ID_MENSAGEM, EXERCICIO_RELATADO, NUMERO_SERIE,
                      CARGA_KG, CARGA_APROX, REPETICOES, REPETICOES_APROX, FALHA)
VALUES ((SELECT ID_MENSAGEM FROM TR_MENSAGEM WHERE ID_TELEGRAM = 999000001),
        NULL, 1, 20, 'N', 10, 'N', 'S');


-- -----------------------------------------------------------------------------
-- R5. Regra: faixa alvo de repetições coerente (mínimo <= máximo).
-- Esperado: ORA-02290 check constraint (CK_TR_EXERCICIO_REPS_MAX) violated
-- -----------------------------------------------------------------------------
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('TESTE Rosca direta', 'Bíceps', 'TOTAL', 12, 8);


-- -----------------------------------------------------------------------------
-- R6. Regra: carga em kg nunca é negativa (ex.: erro de transcrição "menos 20").
-- Esperado: ORA-02290 check constraint (CK_TR_SERIE_CARGA_KG) violated
-- -----------------------------------------------------------------------------
INSERT INTO TR_SERIE (ID_MENSAGEM, EXERCICIO_RELATADO, NUMERO_SERIE,
                      CARGA_KG, CARGA_APROX, REPETICOES, REPETICOES_APROX, FALHA)
VALUES ((SELECT ID_MENSAGEM FROM TR_MENSAGEM WHERE ID_TELEGRAM = 999000001),
        'supino', 1, -20, 'N', 10, 'N', 'S');


-- -----------------------------------------------------------------------------
-- R7. Regra: faixa alvo é opcional, mas vem inteira (as duas ou nenhuma).
-- Só o mínimo preenchido. (CK_TR_EXERCICIO_REPS_MAX não reprova: 8 <= NULL
-- dá "desconhecido"; quem pega este caso é a CK_TR_EXERCICIO_FAIXA.)
-- Esperado: ORA-02290 check constraint (CK_TR_EXERCICIO_FAIXA) violated
-- -----------------------------------------------------------------------------
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('TESTE Rosca martelo', 'Bíceps', 'TOTAL', 8, NULL);


-- -----------------------------------------------------------------------------
-- P1. Regras: exercício oficial vazio até confirmar; "não contei" = repetições
-- NULL; carga não dita = NULL; falha não dita = NULL. Nada vira 0.
-- Esperado: 1 linha inserida.
-- -----------------------------------------------------------------------------
INSERT INTO TR_SERIE (ID_MENSAGEM, EXERCICIO_RELATADO, ID_EXERCICIO, NUMERO_SERIE,
                      CARGA_KG, CARGA_APROX, REPETICOES, REPETICOES_APROX, FALHA, PERCEPCAO)
VALUES ((SELECT ID_MENSAGEM FROM TR_MENSAGEM WHERE ID_TELEGRAM = 999000001),
        'pull down', NULL, 1,
        NULL, 'N', NULL, 'N', NULL, 'beirando a falha');


-- -----------------------------------------------------------------------------
-- P2. Regra: pode haver mais de uma sessão na mesma data.
-- Esperado: 1 linha inserida (já existe uma sessão em 2026-10-07).
-- -----------------------------------------------------------------------------
INSERT INTO TR_SESSAO (DATA_SESSAO, OBSERVACAO)
VALUES (DATE '2026-10-07', 'segundo treino do dia');


-- -----------------------------------------------------------------------------
-- P3. Regra: faixa alvo é opcional (troca de treino sem meta definida).
-- Exercício com as duas faixas nulas.
-- Esperado: 1 linha inserida.
-- -----------------------------------------------------------------------------
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('TESTE Remada curvada', 'Costas', 'TOTAL', NULL, NULL);


-- -----------------------------------------------------------------------------
-- Desfaz tudo: o banco volta ao estado de antes dos testes.
-- -----------------------------------------------------------------------------
ROLLBACK;
