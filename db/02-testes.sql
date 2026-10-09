-- =============================================================================
-- Testes das regras do MVP (rodar DEPOIS do 04-usuario.sql, como script: F5).
-- Derivados das REGRAS do escopo-mvp.md.
-- Esperado: 11 erros (testes R1-R11) e 5 sucessos (testes P1-P5).
-- O arquivo termina com ROLLBACK: nada fica gravado no banco.
-- Valores de teste usam ID_TELEGRAM 999000xxx e nomes "TESTE ..." para não
-- colidir com dados reais.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- PREPARAÇÃO (não conta como teste; tudo aqui deve passar)
-- -----------------------------------------------------------------------------

-- Dois usuários de teste. O A é dono dos dados abaixo; o B existe para provar
-- que o apelido é por usuário (P4).
INSERT INTO TR_USUARIO (ID_TELEGRAM, NOME, STATUS, DATA_CADASTRO)
VALUES (999000101, 'TESTE Usuário A', 'ATIVO', SYSTIMESTAMP);

INSERT INTO TR_USUARIO (ID_TELEGRAM, NOME, STATUS, DATA_CADASTRO)
VALUES (999000102, 'TESTE Usuário B', 'ATIVO', SYSTIMESTAMP);

INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('TESTE Supino reto', 'Peito', 'TOTAL', 8, 12);

INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('TESTE Supino inclinado halter', 'Peito', 'POR_LADO', 8, 12);

INSERT INTO TR_APELIDO (APELIDO, ID_EXERCICIO, ID_USUARIO)
VALUES ('teste supino',
        (SELECT ID_EXERCICIO FROM TR_EXERCICIO WHERE NOME = 'TESTE Supino reto'),
        (SELECT ID_USUARIO FROM TR_USUARIO WHERE ID_TELEGRAM = 999000101));

INSERT INTO TR_SESSAO (DATA_SESSAO, OBSERVACAO, ID_USUARIO)
VALUES (DATE '2026-10-07', 'academia lotada',
        (SELECT ID_USUARIO FROM TR_USUARIO WHERE ID_TELEGRAM = 999000101));

-- A busca da sessão filtra também pelo usuário: com usuários reais no banco,
-- pode existir sessão de outra pessoa na mesma data.
INSERT INTO TR_MENSAGEM (ID_TELEGRAM, DATA_HORA_ENVIO, ORIGEM, TEXTO_BRUTO, ID_SESSAO, ID_USUARIO)
VALUES (999000001,
        TO_DATE('2026-10-07 18:30:00', 'YYYY-MM-DD HH24:MI:SS'),
        'TEXTO',
        'academia lotada, fiz 3x20kg no supino, falhei na última',
        (SELECT MAX(ID_SESSAO) FROM TR_SESSAO
          WHERE DATA_SESSAO = DATE '2026-10-07'
            AND ID_USUARIO = (SELECT ID_USUARIO FROM TR_USUARIO WHERE ID_TELEGRAM = 999000101)),
        (SELECT ID_USUARIO FROM TR_USUARIO WHERE ID_TELEGRAM = 999000101));


-- -----------------------------------------------------------------------------
-- R1. Regra: gravação idempotente (a mesma mensagem do Telegram não entra 2 vezes
-- para o mesmo usuário).
-- ID_USUARIO preenchido de propósito: sem ele o erro seria o NOT NULL
-- (ORA-01400) e o teste "passaria" pelo motivo errado.
-- Esperado: ORA-00001 unique constraint (UQ_TR_MENSAGEM_USU_ID_TELEGRAM) violated
-- -----------------------------------------------------------------------------
INSERT INTO TR_MENSAGEM (ID_TELEGRAM, DATA_HORA_ENVIO, ORIGEM, TEXTO_BRUTO, ID_USUARIO)
VALUES (999000001,
        TO_DATE('2026-10-07 18:30:00', 'YYYY-MM-DD HH24:MI:SS'),
        'TEXTO',
        'mesma mensagem reprocessada',
        (SELECT ID_USUARIO FROM TR_USUARIO WHERE ID_TELEGRAM = 999000101));


-- -----------------------------------------------------------------------------
-- R2. Regra: para um mesmo usuário, cada apelido aponta para exatamente UM exercício.
-- "teste supino" já aponta para o supino reto (usuário A); tentar ligá-lo ao
-- inclinado, para o mesmo usuário A.
-- Esperado: ORA-00001 unique constraint (UQ_TR_APELIDO_USUARIO_APELIDO) violated
-- -----------------------------------------------------------------------------
INSERT INTO TR_APELIDO (APELIDO, ID_EXERCICIO, ID_USUARIO)
VALUES ('teste supino',
        (SELECT ID_EXERCICIO FROM TR_EXERCICIO WHERE NOME = 'TESTE Supino inclinado halter'),
        (SELECT ID_USUARIO FROM TR_USUARIO WHERE ID_TELEGRAM = 999000101));


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
-- R8. Regra: status do usuário só PENDENTE, ATIVO ou BLOQUEADO.
-- Esperado: ORA-02290 check constraint (CK_TR_USUARIO_STATUS) violated
-- -----------------------------------------------------------------------------
INSERT INTO TR_USUARIO (ID_TELEGRAM, NOME, STATUS, DATA_CADASTRO)
VALUES (999000103, 'TESTE Usuário C', 'INATIVO', SYSTIMESTAMP);


-- -----------------------------------------------------------------------------
-- R9. Regra: cada conta do Telegram é um único usuário (o mesmo from.id não
-- se cadastra duas vezes). 999000101 já é o usuário A.
-- Esperado: ORA-00001 unique constraint (UQ_TR_USUARIO_ID_TELEGRAM) violated
-- -----------------------------------------------------------------------------
INSERT INTO TR_USUARIO (ID_TELEGRAM, NOME, STATUS, DATA_CADASTRO)
VALUES (999000101, 'TESTE Usuário A de novo', 'PENDENTE', SYSTIMESTAMP);


-- -----------------------------------------------------------------------------
-- R10. Regra: o apelido não se repete para o mesmo usuário.
-- "teste supino" já existe para o usuário A; gravar de novo, igual, apontando
-- para o MESMO exercício. (O R2 tenta o mesmo apelido para OUTRO exercício;
-- quem barra os dois é a mesma constraint.)
-- Esperado: ORA-00001 unique constraint (UQ_TR_APELIDO_USUARIO_APELIDO) violated
-- -----------------------------------------------------------------------------
INSERT INTO TR_APELIDO (APELIDO, ID_EXERCICIO, ID_USUARIO)
VALUES ('teste supino',
        (SELECT ID_EXERCICIO FROM TR_EXERCICIO WHERE NOME = 'TESTE Supino reto'),
        (SELECT ID_USUARIO FROM TR_USUARIO WHERE ID_TELEGRAM = 999000101));


-- -----------------------------------------------------------------------------
-- R11. Regra: toda mensagem gravada tem um usuário.
-- ID_TELEGRAM novo (999000002) para que o erro não seja o UNIQUE do R1.
-- Esperado: ORA-01400 cannot insert NULL into ("...","TR_MENSAGEM","ID_USUARIO")
-- -----------------------------------------------------------------------------
INSERT INTO TR_MENSAGEM (ID_TELEGRAM, DATA_HORA_ENVIO, ORIGEM, TEXTO_BRUTO)
VALUES (999000002,
        TO_DATE('2026-10-07 18:35:00', 'YYYY-MM-DD HH24:MI:SS'),
        'TEXTO',
        'mensagem sem usuário');


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
-- Esperado: 1 linha inserida (já existe uma sessão do usuário A em 2026-10-07).
-- -----------------------------------------------------------------------------
INSERT INTO TR_SESSAO (DATA_SESSAO, OBSERVACAO, ID_USUARIO)
VALUES (DATE '2026-10-07', 'segundo treino do dia',
        (SELECT ID_USUARIO FROM TR_USUARIO WHERE ID_TELEGRAM = 999000101));


-- -----------------------------------------------------------------------------
-- P3. Regra: faixa alvo é opcional (troca de treino sem meta definida).
-- Exercício com as duas faixas nulas.
-- Esperado: 1 linha inserida.
-- -----------------------------------------------------------------------------
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('TESTE Remada curvada', 'Costas', 'TOTAL', NULL, NULL);


-- -----------------------------------------------------------------------------
-- P4. Regra: o apelido é por usuário ("voador" pode ser um exercício para um e
-- outro exercício para outro). "teste supino" já é do usuário A (supino reto);
-- o usuário B usa o mesmo apelido para o supino inclinado.
-- Esperado: 1 linha inserida.
-- -----------------------------------------------------------------------------
INSERT INTO TR_APELIDO (APELIDO, ID_EXERCICIO, ID_USUARIO)
VALUES ('teste supino',
        (SELECT ID_EXERCICIO FROM TR_EXERCICIO WHERE NOME = 'TESTE Supino inclinado halter'),
        (SELECT ID_USUARIO FROM TR_USUARIO WHERE ID_TELEGRAM = 999000102));


-- -----------------------------------------------------------------------------
-- P5. Regra: gravação idempotente é POR USUÁRIO. O message_id do Telegram só
-- é único dentro de cada chat: a mensagem 999000001 do usuário B é outra
-- mensagem, diferente da 999000001 do usuário A.
-- FICA POR ÚLTIMO: depois dele há duas mensagens 999000001, e as subconsultas
-- "WHERE ID_TELEGRAM = 999000001" dos testes acima deixariam de achar uma só
-- linha (ORA-01427).
-- Esperado: 1 linha inserida.
-- -----------------------------------------------------------------------------
INSERT INTO TR_MENSAGEM (ID_TELEGRAM, DATA_HORA_ENVIO, ORIGEM, TEXTO_BRUTO, ID_USUARIO)
VALUES (999000001,
        TO_DATE('2026-10-07 19:00:00', 'YYYY-MM-DD HH24:MI:SS'),
        'TEXTO',
        'mensagem do usuário B com o mesmo message_id',
        (SELECT ID_USUARIO FROM TR_USUARIO WHERE ID_TELEGRAM = 999000102));


-- -----------------------------------------------------------------------------
-- Desfaz tudo: o banco volta ao estado de antes dos testes.
-- -----------------------------------------------------------------------------
ROLLBACK;
