package dev.vitorrodrigues.treino.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import dev.vitorrodrigues.treino.extrator.SerieExtraida;
import dev.vitorrodrigues.treino.modelo.SerieResumida;

/**
 * Acesso a TR_SERIE. Recebe a Connection e não comita (ver UsuarioDAO).
 *
 * REGRA DA FATIA 6c, para TODA leitura de séries daqui em diante (inclusive as
 * consultas da fatia 7): série de mensagem DESFEITA não conta. O /desfazer não
 * apaga nada: marca TR_MENSAGEM.DATA_HORA_DESFEITA, e as séries continuam nesta
 * tabela. Então toda consulta, resumo ou métrica sobre séries passa pela mensagem
 * e filtra:
 *
 *     JOIN TR_MENSAGEM M ON M.ID_MENSAGEM = S.ID_MENSAGEM
 *     WHERE M.DATA_HORA_DESFEITA IS NULL
 *       AND M.ID_USUARIO = ?   -- a série não tem ID_USUARIO próprio
 *
 * Sem o filtro, a pergunta "quanto peguei na remada?" responderia com a carga
 * errada que o usuário acabou de desfazer.
 * A única leitura que não filtra é o listarResumoDaMensagem, que lê UMA mensagem
 * já escolhida pelo chamador (ver o javadoc dele).
 */
public class SerieDAO {

    private static final String SQL_INSERIR = """
            INSERT INTO TR_SERIE (ID_MENSAGEM, EXERCICIO_RELATADO, ID_EXERCICIO, NUMERO_SERIE,
                                  CARGA_KG, CARGA_APROX, REPETICOES, REPETICOES_APROX,
                                  FALHA, PERCEPCAO)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    // LEFT JOIN: série de exercício não reconhecido (ID_EXERCICIO NULL) também
    // vem, com NOME nulo; com INNER JOIN ela sumiria do resumo.
    // ORDER BY ID_SERIE: a ordem de gravação, que é a ordem do relato (o
    // RegistroService grava as séries na ordem em que o modelo as devolveu).
    // NUMERO_SERIE não serve: recomeça em 1 a cada exercício.
    private static final String SQL_LISTAR_RESUMO_DA_MENSAGEM = """
            SELECT S.ID_EXERCICIO, E.NOME, S.EXERCICIO_RELATADO
              FROM TR_SERIE S
              LEFT JOIN TR_EXERCICIO E
                ON E.ID_EXERCICIO = S.ID_EXERCICIO
             WHERE S.ID_MENSAGEM = ?
             ORDER BY S.ID_SERIE
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

    /**
     * As séries de UMA mensagem, reduzidas ao exercício (resumo do /desfazer, fatia 6c).
     *
     * Exceção à regra da classe: NÃO filtra DATA_HORA_DESFEITA. Quem chama já
     * escolheu a mensagem aplicando a regra (o CorrecaoService lê o alvo ANTES de
     * marcá-lo, na mesma transação). Com o filtro aqui, o resumo sairia vazio no
     * dia em que alguém trocasse a ordem para "marca, depois lê".
     *
     * O nome oficial vem do catálogo (TR_EXERCICIO.NOME pelo ID_EXERCICIO), nunca do
     * que o modelo devolveu: TR_SERIE nem guarda esse nome.
     *
     * @return na ordem de gravação; vazia = a mensagem não tem séries (só observação)
     */
    public List<SerieResumida> listarResumoDaMensagem(Connection conn, long idMensagem) throws SQLException {
        List<SerieResumida> series = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(SQL_LISTAR_RESUMO_DA_MENSAGEM)) {
            ps.setLong(1, idMensagem);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    series.add(new SerieResumida(
                            // getObject(..., Long.class): NULL vira null. getLong
                            // devolveria 0, e 0 não é "não reconhecido".
                            rs.getObject("ID_EXERCICIO", Long.class),
                            rs.getString("NOME"),
                            rs.getString("EXERCICIO_RELATADO")));
                }
            }
        }
        return series;
    }
}
