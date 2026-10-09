-- -----------------------------------------------------------------------------
-- DROPs (ordem inversa das dependências: filhas antes das mães)
-- NA PRIMEIRA EXECUÇÃO, ESTES 6 DROPs DÃO ERRO ORA-00942 ("table or view does
-- not exist"). ISSO É ESPERADO: as tabelas ainda não existem. No SQL Developer,
-- rode como script (F5); ele registra o erro e continua.
-- CASCADE CONSTRAINTS: remove junto as FKs que apontam para a tabela.
-- PURGE: não manda a tabela para a lixeira (recycle bin) do esquema.
-- -----------------------------------------------------------------------------
DROP TABLE TR_SERIE     CASCADE CONSTRAINTS PURGE;
DROP TABLE TR_MENSAGEM  CASCADE CONSTRAINTS PURGE;
DROP TABLE TR_SESSAO    CASCADE CONSTRAINTS PURGE;
DROP TABLE TR_APELIDO   CASCADE CONSTRAINTS PURGE;
DROP TABLE TR_EXERCICIO CASCADE CONSTRAINTS PURGE;
DROP TABLE TR_USUARIO   CASCADE CONSTRAINTS PURGE;
