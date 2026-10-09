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

    // Por que o CASE e não só OBSERVACAO || CHR(10) || ?: no Oracle, concatenar
    // com NULL trata o NULL como texto vazio. Sem o CASE, a primeira observação
    // de uma sessão que não tinha nenhuma seria gravada começando com uma quebra
    // de linha. CHR(10) = '\n'.
    // A soma das duas observações pode passar de 1000 caracteres: aí o Oracle
    // recusa (ORA-12899) e o DAO não corta texto do usuário (mesma regra do
    // SerieDAO).
    // ID_USUARIO no WHERE: mesma ideia do vincularSessao, ninguém acrescenta
    // texto na sessão de outro usuário.
    private static final String SQL_ACRESCENTAR_OBSERVACAO = """
            UPDATE TR_SESSAO
               SET OBSERVACAO = CASE
                                  WHEN OBSERVACAO IS NULL THEN ?
                                  ELSE OBSERVACAO || CHR(10) || ?
                                END
             WHERE ID_SESSAO = ?
               AND ID_USUARIO = ?
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

    /**
     * Junta a observação de mais uma mensagem do mesmo dia à da sessão:
     * se a sessão não tinha observação, grava esta; se tinha, acrescenta numa
     * linha nova. Nada é apagado: as duas mensagens podem trazer contexto.
     *
     * @param observacao texto a acrescentar; não pode ser null (sem observação,
     *        o service nem chama este método)
     * @throws IllegalStateException se nada foi atualizado: a sessão não existe
     *         ou não é do usuário
     */
    public void acrescentarObservacao(Connection conn, long idSessao, long idUsuario, String observacao)
            throws SQLException {
        // Com null, o ELSE do CASE gravaria a observação antiga + uma quebra de
        // linha solta no fim. Falha alto em vez de sujar o texto.
        if (observacao == null) {
            throw new IllegalArgumentException("Observação null: não há o que acrescentar.");
        }
        try (PreparedStatement ps = conn.prepareStatement(SQL_ACRESCENTAR_OBSERVACAO)) {
            // O mesmo texto vai nos dois "?" do CASE.
            ps.setString(1, observacao);
            ps.setString(2, observacao);
            ps.setLong(3, idSessao);
            ps.setLong(4, idUsuario);

            int linhas = ps.executeUpdate();
            if (linhas == 0) {
                // Só ids internos na mensagem, como no vincularSessao.
                throw new IllegalStateException("Sessão " + idSessao + " não pertence ao usuário "
                        + idUsuario + ".");
            }
        }
    }
}
