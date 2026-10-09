package dev.vitorrodrigues.treino.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import dev.vitorrodrigues.treino.extrator.SerieExtraida;

/**
 * Acesso a TR_SERIE. Recebe a Connection e não comita (ver UsuarioDAO).
 */
public class SerieDAO {

    private static final String SQL_INSERIR = """
            INSERT INTO TR_SERIE (ID_MENSAGEM, EXERCICIO_RELATADO, ID_EXERCICIO, NUMERO_SERIE,
                                  CARGA_KG, CARGA_APROX, REPETICOES, REPETICOES_APROX,
                                  FALHA, PERCEPCAO)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /**
     * Grava uma série como o extrator entendeu.
     *
     * Transformar o nome oficial ("exercicio" no JSON) em ID_EXERCICIO NÃO é papel
     * do DAO: é regra de negócio do service (fatia 5). Aqui só chega o id já resolvido.
     *
     * Nada é preenchido por padrão. O que veio null vai como NULL; nas colunas
     * NOT NULL isso faz o Oracle recusar o INSERT (ORA-01400), que é o que queremos:
     * o LeitorRelatoJson já garante esses campos, então se chegarem null é bug.
     * Texto maior que a coluna (EXERCICIO_RELATADO 100, PERCEPCAO 500) também é
     * recusado (ORA-12899); o DAO não corta texto do usuário.
     *
     * @param idExercicio null = exercício ainda não confirmado
     * @return ID_SERIE gerado pelo banco
     */
    public long inserir(Connection conn, long idMensagem, SerieExtraida serie, Long idExercicio)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_INSERIR, new String[] {"ID_SERIE"})) {
            ps.setLong(1, idMensagem);
            JdbcUtil.setTextoOuNull(ps, 2, serie.exercicioRelatado());
            JdbcUtil.setLongOuNull(ps, 3, idExercicio);
            JdbcUtil.setIntOuNull(ps, 4, serie.numeroSerie());
            JdbcUtil.setDecimalOuNull(ps, 5, serie.cargaKg());
            JdbcUtil.setSimNaoOuNull(ps, 6, serie.cargaAproximada());
            JdbcUtil.setIntOuNull(ps, 7, serie.repeticoes());
            JdbcUtil.setSimNaoOuNull(ps, 8, serie.repeticoesAproximadas());
            // FALHA aceita NULL: três estados ('S', 'N', não foi dito).
            JdbcUtil.setSimNaoOuNull(ps, 9, serie.falha());
            JdbcUtil.setTextoOuNull(ps, 10, serie.percepcao());
            ps.executeUpdate();

            return JdbcUtil.lerIdGerado(ps);
        }
    }
}
