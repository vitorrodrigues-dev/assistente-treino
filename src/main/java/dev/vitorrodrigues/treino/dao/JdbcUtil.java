package dev.vitorrodrigues.treino.dao;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Três rituais que se repetem nos DAOs. Ficam num lugar só porque errar um deles
 * em silêncio (gravar 0 ou 'N' onde era NULL) é exatamente o risco que o escopo
 * manda evitar: corrigido aqui, está corrigido em todos.
 *
 * Package-private (sem "public"): é detalhe dos DAOs, ninguém fora de dao/ usa.
 */
final class JdbcUtil {

    private JdbcUtil() {
    }

    /**
     * Depois do executeUpdate de um INSERT preparado com new String[]{"ID_..."},
     * o id gerado pelo IDENTITY vem num ResultSet com uma linha e uma coluna.
     */
    static long lerIdGerado(PreparedStatement ps) throws SQLException {
        try (ResultSet chaves = ps.getGeneratedKeys()) {
            if (!chaves.next()) {
                throw new SQLException("O banco não devolveu o id gerado.");
            }
            return chaves.getLong(1);
        }
    }

    // Os métodos abaixo existem porque ps.setInt(i, valor) com um Integer null
    // não grava NULL: o Java tenta desembrulhar o null para int e lança
    // NullPointerException. E escrever 0 no lugar seria inventar dado.

    static void setLongOuNull(PreparedStatement ps, int indice, Long valor) throws SQLException {
        if (valor == null) {
            ps.setNull(indice, Types.NUMERIC);
        } else {
            ps.setLong(indice, valor);
        }
    }

    static void setIntOuNull(PreparedStatement ps, int indice, Integer valor) throws SQLException {
        if (valor == null) {
            ps.setNull(indice, Types.NUMERIC);
        } else {
            ps.setInt(indice, valor);
        }
    }

    // BigDecimal.valueOf(22.5) e não new BigDecimal(22.5): o double 22.5 é exato,
    // mas 22.3 não é (vira 22.300000000000000710...). valueOf usa o texto "22.3".
    static void setDecimalOuNull(PreparedStatement ps, int indice, Double valor) throws SQLException {
        if (valor == null) {
            ps.setNull(indice, Types.NUMERIC);
        } else {
            ps.setBigDecimal(indice, BigDecimal.valueOf(valor));
        }
    }

    static void setTextoOuNull(PreparedStatement ps, int indice, String valor) throws SQLException {
        if (valor == null) {
            ps.setNull(indice, Types.VARCHAR);
        } else {
            ps.setString(indice, valor);
        }
    }

    /**
     * true -> 'S', false -> 'N', null -> NULL. Nunca 'N' por padrão.
     * Nas colunas NOT NULL (CARGA_APROX, REPETICOES_APROX), um null chega ao banco
     * como NULL e o Oracle recusa (ORA-01400): falha alto em vez de gravar 'N'.
     */
    static void setSimNaoOuNull(PreparedStatement ps, int indice, Boolean valor) throws SQLException {
        if (valor == null) {
            ps.setNull(indice, Types.CHAR);
        } else {
            ps.setString(indice, valor ? "S" : "N");
        }
    }
}
