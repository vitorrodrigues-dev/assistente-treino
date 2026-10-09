package dev.vitorrodrigues.treino.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Acesso a TR_SESSAO. Recebe a Connection e não comita (ver UsuarioDAO).
 */
public class SessaoDAO {

    // O banco permite várias sessões na mesma data; no MVP o service usa uma por
    // usuário por data. Se por algum motivo houver mais de uma, pega sempre a
    // mais antiga (menor id), para a resposta não mudar de uma execução para outra.
    // FETCH FIRST existe desde a 12c, então vale na 19c da FIAP.
    private static final String SQL_BUSCAR_POR_USUARIO_E_DATA = """
            SELECT ID_SESSAO
              FROM TR_SESSAO
             WHERE ID_USUARIO = ?
               AND DATA_SESSAO = ?
             ORDER BY ID_SESSAO
             FETCH FIRST 1 ROW ONLY
            """;

    private static final String SQL_INSERIR = """
            INSERT INTO TR_SESSAO (ID_USUARIO, DATA_SESSAO, OBSERVACAO)
            VALUES (?, ?, ?)
            """;

    /**
     * Sessão do usuário naquela data. Vazio = ainda não treinou (registrou) nesse dia.
     */
    public Optional<Long> buscarPorUsuarioEData(Connection conn, long idUsuario, LocalDate data)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_BUSCAR_POR_USUARIO_E_DATA)) {
            ps.setLong(1, idUsuario);
            // LocalDate vira DATE à meia-noite: é o que o CK_TR_SESSAO_DATA_SESSAO
            // exige (data sem hora), e por isso o "=" acha a linha.
            ps.setObject(2, data);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(rs.getLong("ID_SESSAO"));
            }
        }
    }

    /**
     * Cria a sessão.
     *
     * @param observacao observação do dia; null = o relato não trouxe contexto
     * @return ID_SESSAO gerado pelo banco
     */
    public long inserir(Connection conn, long idUsuario, LocalDate data, String observacao)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_INSERIR, new String[] {"ID_SESSAO"})) {
            ps.setLong(1, idUsuario);
            ps.setObject(2, data);
            JdbcUtil.setTextoOuNull(ps, 3, observacao);
            ps.executeUpdate();

            return JdbcUtil.lerIdGerado(ps);
        }
    }
}
