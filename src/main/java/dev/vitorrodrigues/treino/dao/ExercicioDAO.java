package dev.vitorrodrigues.treino.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.vitorrodrigues.treino.factory.ConnectionFactory;
import dev.vitorrodrigues.treino.modelo.ExercicioComApelidos;

public class ExercicioDAO {

    // Uma consulta só, com LEFT JOIN, em vez de uma consulta de apelidos para cada
    // exercício: com 46 exercícios seriam 47 idas ao banco (o problema "N+1");
    // assim é uma.
    //
    // LEFT JOIN: exercício sem apelido também aparece, com APELIDO nulo.
    // O filtro do usuário fica no ON, e não no WHERE: no WHERE, a linha de um
    // exercício sem apelido (A.ID_USUARIO nulo) seria descartada e o LEFT JOIN
    // viraria um INNER JOIN, sumindo com esses exercícios da lista.
    //
    // ORDER BY: grupamento e nome; A.ID_APELIDO deixa os apelidos de um mesmo
    // exercício na ordem em que foram cadastrados.
    private static final String SQL_LISTAR_COM_APELIDOS = """
            SELECT E.ID_EXERCICIO, E.NOME, E.GRUPAMENTO, E.FORMA_CARGA,
                   E.REPS_MIN, E.REPS_MAX, A.APELIDO
              FROM TR_EXERCICIO E
              LEFT JOIN TR_APELIDO A
                ON A.ID_EXERCICIO = E.ID_EXERCICIO
               AND A.ID_USUARIO = ?
             ORDER BY E.GRUPAMENTO, E.NOME, A.ID_APELIDO
            """;

    /**
     * Todos os exercícios do catálogo, cada um com os apelidos do usuário informado.
     *
     * @param idUsuario ID_USUARIO de TR_USUARIO (não é o id do Telegram)
     */
    public List<ExercicioComApelidos> listarComApelidos(long idUsuario) throws SQLException {
        // Agrupa as linhas por exercício. LinkedHashMap mantém a ordem de inserção,
        // que é a ordem do ORDER BY.
        Map<Long, ExercicioComApelidos> exerciciosPorId = new LinkedHashMap<>();

        try (Connection con = ConnectionFactory.getConnection();
             PreparedStatement ps = con.prepareStatement(SQL_LISTAR_COM_APELIDOS)) {

            ps.setLong(1, idUsuario);

            try (ResultSet rs = ps.executeQuery()) {
                // Um exercício vem em uma linha por apelido, ou numa linha só
                // (com APELIDO nulo) quando o usuário não tem apelido para ele.
                while (rs.next()) {
                    long idExercicio = rs.getLong("ID_EXERCICIO");

                    ExercicioComApelidos exercicio = exerciciosPorId.get(idExercicio);
                    if (exercicio == null) {
                        exercicio = new ExercicioComApelidos(
                                rs.getString("NOME"),
                                rs.getString("GRUPAMENTO"),
                                rs.getString("FORMA_CARGA"),
                                // getObject(..., Integer.class): NULL vira null.
                                // getInt devolveria 0 para NULL.
                                rs.getObject("REPS_MIN", Integer.class),
                                rs.getObject("REPS_MAX", Integer.class),
                                new ArrayList<>());
                        exerciciosPorId.put(idExercicio, exercicio);
                    }

                    String apelido = rs.getString("APELIDO");
                    if (apelido != null) {
                        exercicio.apelidos().add(apelido);
                    }
                }
            }
        }

        return new ArrayList<>(exerciciosPorId.values());
    }
}
