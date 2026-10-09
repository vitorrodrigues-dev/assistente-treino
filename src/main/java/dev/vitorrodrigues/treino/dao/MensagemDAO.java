package dev.vitorrodrigues.treino.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDateTime;

import dev.vitorrodrigues.treino.modelo.ResultadoInsercao;

/**
 * Acesso a TR_MENSAGEM. Recebe a Connection e não comita (ver UsuarioDAO).
 */
public class MensagemDAO {

    // Erro Oracle ORA-00001: violação de UNIQUE.
    private static final int ORA_UNIQUE = 1;

    private static final String UQ_MENSAGEM = "UQ_TR_MENSAGEM_USU_ID_TELEGRAM";

    // ID_SESSAO fica de fora: a mensagem é gravada crua ANTES do extrator e só
    // ganha sessão depois (vincularSessao). Coluna omitida no INSERT = NULL.
    private static final String SQL_INSERIR = """
            INSERT INTO TR_MENSAGEM (ID_USUARIO, ID_TELEGRAM, DATA_HORA_ENVIO, ORIGEM, TEXTO_BRUTO)
            VALUES (?, ?, ?, ?, ?)
            """;

    // As duas condições de usuário fecham a lacuna do banco: nenhuma constraint
    // impede a mensagem do usuário A de apontar para a sessão do B (o escopo
    // registra isso em "Lacuna conhecida"). Se a mensagem não for do usuário, OU
    // a sessão não for dele, o WHERE não acha linha e nada é atualizado.
    private static final String SQL_VINCULAR_SESSAO = """
            UPDATE TR_MENSAGEM
               SET ID_SESSAO = ?
             WHERE ID_MENSAGEM = ?
               AND ID_USUARIO = ?
               AND EXISTS (SELECT 1
                             FROM TR_SESSAO S
                            WHERE S.ID_SESSAO = ?
                              AND S.ID_USUARIO = ?)
            """;

    /**
     * Grava a mensagem crua.
     *
     * @param idUsuario     ID_USUARIO de TR_USUARIO (não o from.id)
     * @param idTelegram    message_id do Telegram (único só dentro do chat do usuário)
     * @param dataHoraEnvio campo "date" do Telegram já convertido para America/Sao_Paulo
     * @param origem        'TEXTO' ou 'AUDIO' (CK_TR_MENSAGEM_ORIGEM)
     * @return gravada (com id) ou já gravada (a mesma mensagem já estava no banco)
     */
    public ResultadoInsercao inserir(Connection conn, long idUsuario, long idTelegram,
            LocalDateTime dataHoraEnvio, String origem, String textoBruto) throws SQLException {

        try (PreparedStatement ps = conn.prepareStatement(SQL_INSERIR, new String[] {"ID_MENSAGEM"})) {
            ps.setLong(1, idUsuario);
            ps.setLong(2, idTelegram);
            // DATA_HORA_ENVIO é DATE: guarda data e hora até o segundo.
            ps.setObject(3, dataHoraEnvio);
            ps.setString(4, origem);
            // TEXTO_BRUTO é CLOB. setString funciona para o tamanho de uma
            // mensagem do Telegram (até 4096 caracteres).
            ps.setString(5, textoBruto);
            ps.executeUpdate();

            return ResultadoInsercao.gravada(JdbcUtil.lerIdGerado(ps));

        } catch (SQLIntegrityConstraintViolationException e) {
            // SQLIntegrityConstraintViolationException cobre QUALQUER violação:
            // UNIQUE (ORA-00001), FK (ORA-02291), NOT NULL (ORA-01400)...
            // Só a UNIQUE desta tabela significa "já gravada". Tratar uma FK como
            // "já gravada" faria a mensagem sumir em silêncio.
            if (e.getErrorCode() == ORA_UNIQUE && e.getMessage() != null
                    && e.getMessage().contains(UQ_MENSAGEM)) {
                return ResultadoInsercao.jaEstavaGravada();
            }
            throw e;
        }
    }

    /**
     * Liga a mensagem à sessão, só se as duas forem do mesmo usuário.
     *
     * @throws IllegalStateException se nada foi atualizado: a mensagem não existe,
     *         não é do usuário, ou a sessão não existe / não é do usuário
     */
    public void vincularSessao(Connection conn, long idMensagem, long idSessao, long idUsuario)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_VINCULAR_SESSAO)) {
            ps.setLong(1, idSessao);
            ps.setLong(2, idMensagem);
            ps.setLong(3, idUsuario);
            ps.setLong(4, idSessao);
            ps.setLong(5, idUsuario);

            int linhas = ps.executeUpdate();
            if (linhas == 0) {
                // Só ids internos na mensagem: nada de texto, nome ou id do Telegram.
                throw new IllegalStateException("Mensagem " + idMensagem + " e sessão " + idSessao
                        + " não pertencem ambas ao usuário " + idUsuario + ".");
            }
        }
    }
}
