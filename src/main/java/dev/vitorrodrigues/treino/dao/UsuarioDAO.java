package dev.vitorrodrigues.treino.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;

import dev.vitorrodrigues.treino.modelo.Usuario;

/**
 * Acesso a TR_USUARIO.
 *
 * Regra de todos os DAOs a partir da fatia 4: o método RECEBE a Connection e não
 * abre, não fecha e não comita. Motivo: sessão + séries têm que entrar numa
 * transação só (tudo ou nada). Se cada DAO abrisse a própria conexão pela
 * ConnectionFactory, cada um teria a sua transação e um erro no meio deixaria
 * metade gravada. Quem abre, comita e faz rollback é o service (fatia 5).
 */
public class UsuarioDAO {

    private static final String SQL_BUSCAR_POR_ID_TELEGRAM = """
            SELECT ID_USUARIO, ID_TELEGRAM, NOME, STATUS, DATA_CADASTRO
              FROM TR_USUARIO
             WHERE ID_TELEGRAM = ?
            """;

    // Status fixo no SQL: este método só serve para o cadastro pelo /start.
    // Quem entra ATIVO direto é só o administrador, pelo 04-usuario.sql.
    private static final String SQL_INSERIR_PENDENTE = """
            INSERT INTO TR_USUARIO (ID_TELEGRAM, NOME, STATUS, DATA_CADASTRO)
            VALUES (?, ?, 'PENDENTE', ?)
            """;

    private static final String SQL_ATUALIZAR_STATUS = """
            UPDATE TR_USUARIO
               SET STATUS = ?
             WHERE ID_USUARIO = ?
            """;

    /**
     * Usuário pelo id do Telegram (from.id). Vazio = a pessoa nunca mandou /start.
     */
    public Optional<Usuario> buscarPorIdTelegram(Connection conn, long idTelegram) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_BUSCAR_POR_ID_TELEGRAM)) {
            ps.setLong(1, idTelegram);

            try (ResultSet rs = ps.executeQuery()) {
                // UQ_TR_USUARIO_ID_TELEGRAM garante no máximo uma linha.
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Usuario(
                        rs.getLong("ID_USUARIO"),
                        rs.getLong("ID_TELEGRAM"),
                        rs.getString("NOME"),
                        rs.getString("STATUS"),
                        // getObject(..., LocalDateTime.class): o driver converte o
                        // TIMESTAMP direto, sem passar por java.sql.Timestamp.
                        rs.getObject("DATA_CADASTRO", LocalDateTime.class)));
            }
        }
    }

    /**
     * Cadastra quem mandou /start, com status PENDENTE.
     *
     * @return ID_USUARIO gerado pelo banco
     */
    public long inserirPendente(Connection conn, long idTelegram, String nome, LocalDateTime dataCadastro)
            throws SQLException {
        // new String[]{"ID_USUARIO"}: pede ao driver o valor gerado DESTA coluna.
        // No Oracle, RETURN_GENERATED_KEYS devolveria o ROWID, não o id.
        try (PreparedStatement ps = conn.prepareStatement(SQL_INSERIR_PENDENTE, new String[] {"ID_USUARIO"})) {
            ps.setLong(1, idTelegram);
            ps.setString(2, nome);
            ps.setObject(3, dataCadastro);
            ps.executeUpdate();

            return JdbcUtil.lerIdGerado(ps);
        }
    }

    /**
     * Troca o status (ATIVO no /aprovar, BLOQUEADO).
     * Status fora da lista é recusado pelo CK_TR_USUARIO_STATUS (SQLException).
     *
     * @throws IllegalStateException se o usuário não existe (0 linhas atualizadas)
     */
    public void atualizarStatus(Connection conn, long idUsuario, String status) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_ATUALIZAR_STATUS)) {
            ps.setString(1, status);
            ps.setLong(2, idUsuario);

            // UPDATE com 0 linhas NÃO é erro para o banco: ele só não achou ninguém.
            // Para nós é: o chamador achava que o usuário existia. Falha alto.
            int linhas = ps.executeUpdate();
            if (linhas == 0) {
                throw new IllegalStateException("Nenhum usuário com ID_USUARIO " + idUsuario + ".");
            }
        }
    }
}
