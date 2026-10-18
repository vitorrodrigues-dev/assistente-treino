-- =============================================================================
-- Assistente de Treino IA - Fatia 6c: correção com /desfazer (exclusão lógica)
-- Alvo: Oracle 19c (FIAP). Só sintaxe compatível com a 19c.
-- Fonte das regras: escopo-mvp.md e especificação da 6c (decisões de 12/10/2026).
--
-- ORDEM DE EXECUÇÃO (no SQL Developer, como script: F5):
--   1. 03-plano.sql
--   2. 04-usuario.sql
--   3. 05-desfazer.sql  (este arquivo)
-- O 02-testes.sql passa a depender deste arquivo: rode-o só depois dele.
--
-- SEM PRÉ-REQUISITO de tabela vazia (diferente do 04-usuario.sql): as duas
-- colunas novas são anuláveis, então as mensagens que já existem ficam com NULL
-- nas duas, que é justamente "mensagem válida". A constraint e o índice aceitam
-- essas linhas pelo mesmo motivo (ver abaixo).
--
-- NÃO é reexecutável (a segunda vez dá ORA-01430 nas colunas, ORA-02264 na
-- constraint e ORA-00955 no índice: tudo já existe).
-- Para refazer do zero: 00-drop.sql -> 01-ddl.sql -> 03-plano.sql
--                       -> 04-usuario.sql -> 05-desfazer.sql.
-- (O DROP TABLE do 00-drop.sql leva junto as colunas, a constraint e o índice.)
--
-- Sem COMMIT no fim: só há DDL aqui, e no Oracle cada DDL comita sozinha.
--
-- ENCODING: UTF-8, como o 03-plano.sql (ver o aviso no cabeçalho dele).
--
-- Convenções: as mesmas do 01-ddl.sql (prefixo TR_, nomes com até 30
-- caracteres, nenhum DEFAULT, nenhum ON DELETE CASCADE).
-- =============================================================================


-- -----------------------------------------------------------------------------
-- TR_MENSAGEM.DATA_HORA_DESFEITA: o /desfazer MARCA a mensagem, nada é apagado.
--   NULL       = mensagem válida (o registro dela vale).
--   preenchida = desfeita, com o momento do /desfazer (a data de envio do
--                comando no Telegram, em America/Sao_Paulo, como em todo o bot).
-- As séries da mensagem continuam em TR_SERIE e o texto bruto continua aqui:
-- dá para saber o que foi desfeito e quando, e a métrica "extrações que
-- precisaram de correção" vira um COUNT(*) WHERE DATA_HORA_DESFEITA IS NOT NULL.
-- DATE (e não TIMESTAMP) para combinar com DATA_HORA_ENVIO, da mesma tabela:
-- o "date" do Telegram vem em segundos inteiros, e DATE guarda até o segundo.
-- Sem DEFAULT (convenção): quem desfaz é o Java, sempre com o valor explícito.
-- Cada coluna, a constraint e o índice em comandos separados: se um falhar, o
-- erro aponta qual.
-- -----------------------------------------------------------------------------
ALTER TABLE TR_MENSAGEM ADD (DATA_HORA_DESFEITA DATE);


-- -----------------------------------------------------------------------------
-- TR_MENSAGEM.DESFEITA_POR_ID_TELEGRAM: o message_id do /desfazer que desfez a
-- mensagem. NULL enquanto a mensagem é válida.
-- Para que serve: IDEMPOTÊNCIA. Se o bot cair depois de comitar o /desfazer e
-- antes de confirmar o offset ao Telegram, o Telegram entrega o MESMO comando
-- de novo (mesmo message_id). Sem esta coluna, a reentrega desfaria MAIS uma
-- mensagem. Com ela, o Java vê que este comando já foi aplicado e não faz nada.
-- É a mesma ideia do UQ_TR_MENSAGEM_USU_ID_TELEGRAM na gravação: repetir a
-- operação não muda o resultado.
-- NUMBER(19), como o ID_TELEGRAM desta tabela: é o mesmo tipo de número (um
-- message_id do Telegram, único só dentro do chat de cada usuário).
-- -----------------------------------------------------------------------------
ALTER TABLE TR_MENSAGEM ADD (DESFEITA_POR_ID_TELEGRAM NUMBER(19));


-- -----------------------------------------------------------------------------
-- Regra: as duas colunas andam JUNTAS, e só mensagem que GEROU REGISTRO pode ser
-- desfeita.
--   - Ou as duas nulas (mensagem válida), ou as duas preenchidas (desfeita:
--     quando e por qual comando). Data sem comando seria uma mensagem desfeita
--     que a checagem de reentrega não enxerga (a idempotência falharia em
--     silêncio); comando sem data seria uma mensagem "meio desfeita", válida
--     para as consultas e marcada para a idempotência.
--   - Desfeita exige ID_SESSAO. Mensagem sem sessão (SEM_SERIES, NAO_REGISTRADO,
--     consulta) não tem séries nem observação a desfazer; marcá-la contaria uma
--     "correção" que não corrigiu nada e inflaria a métrica.
-- A regra fica no banco, e não só no Java, para valer também num UPDATE feito à
-- mão no SQL Developer.
-- (Sem a armadilha de NULL dos outros CHECK: IS NULL e IS NOT NULL nunca dão
-- "desconhecido", só verdadeiro ou falso. As linhas que já existem têm as duas
-- colunas nulas e passam pelo primeiro ramo: o ALTER não recusa nenhuma.)
-- -----------------------------------------------------------------------------
ALTER TABLE TR_MENSAGEM ADD CONSTRAINT CK_TR_MENSAGEM_DESFEITA CHECK (
    (DATA_HORA_DESFEITA IS NULL AND DESFEITA_POR_ID_TELEGRAM IS NULL)
    OR (DATA_HORA_DESFEITA IS NOT NULL
        AND DESFEITA_POR_ID_TELEGRAM IS NOT NULL
        AND ID_SESSAO IS NOT NULL)
);


-- -----------------------------------------------------------------------------
-- Regra: um mesmo /desfazer desfaz NO MÁXIMO UMA mensagem, garantido no banco
-- (o Java confere antes; o índice segura se o Java um dia errar).
-- Por usuário: o message_id só é único dentro do chat. O /desfazer 300 de um
-- usuário e o /desfazer 300 de outro são comandos diferentes.
--
-- POR QUE NÃO UM UNIQUE (ID_USUARIO, DESFEITA_POR_ID_TELEGRAM) COMUM: no Oracle,
-- uma chave composta com uma parte nula e a outra preenchida ENTRA na
-- comparação; só fica de fora a linha com TODAS as colunas da chave nulas. Toda
-- mensagem válida tem DESFEITA_POR_ID_TELEGRAM nulo, então duas mensagens
-- válidas do mesmo usuário seriam (5, NULL) e (5, NULL): ORA-00001, e o bot não
-- conseguiria gravar a segunda mensagem de ninguém.
--
-- A SAÍDA: índice único baseado em função. Nas mensagens válidas o CASE devolve
-- NULL e a outra coluna também é NULL: a chave inteira fica nula, e o Oracle não
-- guarda no índice linha com a chave toda nula. As válidas não entram (nem
-- colidem); só as desfeitas entram, como (ID_USUARIO, DESFEITA_POR_ID_TELEGRAM).
-- Índice e não constraint porque UNIQUE constraint só aceita colunas, não
-- expressão. A violação dá o mesmo ORA-00001, citando o nome do índice.
-- -----------------------------------------------------------------------------
CREATE UNIQUE INDEX UQ_TR_MENSAGEM_DESFEITA_POR ON TR_MENSAGEM (
    CASE WHEN DESFEITA_POR_ID_TELEGRAM IS NOT NULL THEN ID_USUARIO END,
    DESFEITA_POR_ID_TELEGRAM
);
