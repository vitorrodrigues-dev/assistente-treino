package dev.vitorrodrigues.treino.dao;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.vitorrodrigues.treino.modelo.SerieConsultada;
import dev.vitorrodrigues.treino.modelo.UltimoTreino;

/**
 * Leitura do último treino de um exercício, para o /carga (fatia 7). Só leitura.
 * Recebe a Connection e não comita (ver UsuarioDAO).
 *
 * DAO próprio, e não um método no SerieDAO: a consulta atravessa TR_SERIE,
 * TR_MENSAGEM e TR_SESSAO e devolve um "treino", não séries soltas. Segue a
 * regra da 6c escrita no SerieDAO: série de mensagem desfeita não conta.
 */
public class ConsultaDAO {

    // Uma consulta só: TODAS as séries válidas do exercício, do usuário, do treino
    // mais recente para o mais antigo. O Java lê só o PRIMEIRO grupo (a primeira
    // sessão que aparece) e para. Numa consulta só, o Oracle lê tudo no mesmo
    // instante (consistência de leitura por comando): um /desfazer comitado no meio
    // não deixa a sessão escolhida sem séries.
    //
    // Como a série chega à mensagem e à sessão: TR_SERIE.ID_MENSAGEM (NOT NULL, FK)
    // -> TR_MENSAGEM.ID_SESSAO -> TR_SESSAO. A série não tem sessão nem usuário
    // próprios (01-ddl.sql: "dois caminhos que possam discordar").
    //
    // - SE.ID_USUARIO = ?: só sessão do PRÓPRIO usuário. O banco não impede a
    //   mensagem do A de apontar para a sessão do B ("lacuna conhecida" do
    //   escopo); sem esta condição, o A veria o treino do B.
    // - M.ID_USUARIO = ?: só série que veio de mensagem do próprio usuário. Pelo
    //   mesmo motivo, ao contrário: a sessão é do B, mas a mensagem é do A.
    // - M.DATA_HORA_DESFEITA IS NULL: regra da 6c, série de mensagem desfeita não
    //   conta. O filtro é por MENSAGEM, não por sessão: uma sessão com duas
    //   mensagens, uma desfeita e outra não, continua com as séries da válida. E a
    //   sessão em que todas as mensagens do exercício foram desfeitas não aparece:
    //   o último treino passa a ser o anterior.
    // - SR.ID_EXERCICIO = ?: série com ID_EXERCICIO NULL (exercício não
    //   reconhecido) nunca é igual a nada, então fica de fora sozinha.
    // - JOIN (não LEFT JOIN) com TR_SESSAO: mensagem sem sessão (consulta,
    //   NAO_REGISTRADO) não tem série nenhuma, mas fica de fora de qualquer jeito.
    //
    // ORDER BY: primeiro o que separa os treinos (data desc; no mesmo dia, o
    // ID_SESSAO maior), para as séries de uma sessão virem juntas e a mais recente
    // primeiro. Dentro da sessão, a ordem da resposta é MENSAGEM POR MENSAGEM:
    // - M.DATA_HORA_ENVIO, depois M.ID_MENSAGEM: a ordem em que o usuário mandou.
    //   Duas mensagens no mesmo dia, cada uma numerada a partir de 1, não se
    //   intercalam (nunca 1, 1, 2, 2); a que veio depois, mesmo que corrija a "2ª",
    //   aparece depois, com o número dela. O ID_MENSAGEM só desempata no mesmo
    //   segundo (a data não tem fração); sozinho não serviria, porque o IDENTITY
    //   pode sair fora de ordem num Oracle RAC (mesmo critério do /desfazer).
    // - SR.NUMERO_SERIE: dentro da mensagem, a ordem das séries.
    // - SR.ID_SERIE: número repetido dentro da mesma mensagem -> ordem de gravação.
    private static final String SQL_SERIES_DO_EXERCICIO = """
            SELECT SE.ID_SESSAO, SE.DATA_SESSAO, SR.NUMERO_SERIE,
                   SR.CARGA_KG, SR.CARGA_APROX, SR.REPETICOES, SR.REPETICOES_APROX, SR.FALHA
              FROM TR_SERIE SR
              JOIN TR_MENSAGEM M
                ON M.ID_MENSAGEM = SR.ID_MENSAGEM
              JOIN TR_SESSAO SE
                ON SE.ID_SESSAO = M.ID_SESSAO
             WHERE SE.ID_USUARIO = ?
               AND M.ID_USUARIO = ?
               AND M.DATA_HORA_DESFEITA IS NULL
               AND SR.ID_EXERCICIO = ?
             ORDER BY SE.DATA_SESSAO DESC, SE.ID_SESSAO DESC,
                      M.DATA_HORA_ENVIO, M.ID_MENSAGEM, SR.NUMERO_SERIE, SR.ID_SERIE
            """;

    /**
     * O último treino do exercício: a sessão mais recente do usuário (data desc;
     * desempate pelo ID_SESSAO desc) com ao menos uma série válida do exercício, e
     * as séries válidas do exercício nessa sessão.
     *
     * @param idUsuario   ID_USUARIO de TR_USUARIO (não o from.id)
     * @param idExercicio ID_EXERCICIO do catálogo
     * @return vazio = o usuário não tem série válida desse exercício
     */
    public Optional<UltimoTreino> buscarUltimoTreino(Connection conn, long idUsuario, long idExercicio)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_SERIES_DO_EXERCICIO)) {
            ps.setLong(1, idUsuario);
            ps.setLong(2, idUsuario);
            ps.setLong(3, idExercicio);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                // A primeira linha é da sessão mais recente (ORDER BY). ID_SESSAO e
                // DATA_SESSAO são NOT NULL: getLong não esconde NULL nenhum aqui.
                long idSessao = rs.getLong("ID_SESSAO");
                // DATA_SESSAO é DATE sem hora (CK_TR_SESSAO_DATA_SESSAO).
                LocalDate dataSessao = rs.getObject("DATA_SESSAO", LocalDate.class);
                List<SerieConsultada> series = new ArrayList<>();
                do {
                    if (rs.getLong("ID_SESSAO") != idSessao) {
                        // Começou a sessão seguinte: um treino mais antigo. Para.
                        break;
                    }
                    series.add(serieDaLinha(rs));
                } while (rs.next());
                return Optional.of(new UltimoTreino(dataSessao, series));
            }
        }
    }

    // Nenhum getInt/getDouble em coluna que aceita NULL: getBigDecimal e
    // getObject(..., Integer.class) devolvem null para NULL, nunca 0 (getInt
    // devolveria 0 e exigiria o wasNull). NUMERO_SERIE é NOT NULL.
    private static SerieConsultada serieDaLinha(ResultSet rs) throws SQLException {
        int numeroSerie = rs.getInt("NUMERO_SERIE");

        BigDecimal carga = rs.getBigDecimal("CARGA_KG");
        Double cargaKg = null;
        Boolean cargaAproximada = null;
        if (carga != null) {
            // NUMBER(6,2) -> double sem perda: até 2 casas e no máximo 9999.99,
            // e o texto curto do double é o mesmo número (é o que a confirmação da
            // 6a formata).
            cargaKg = carga.doubleValue();
            // Só com carga: com CARGA_KG NULL, CARGA_APROX nem é lida. Um "~" sem
            // número não diz nada, e o 'N'/'S' gravado ali não foi dito sobre
            // carga nenhuma.
            cargaAproximada = simNao(rs.getString("CARGA_APROX"), "CARGA_APROX");
        }

        Integer repeticoes = rs.getObject("REPETICOES", Integer.class);
        Boolean repeticoesAproximadas = null;
        if (repeticoes != null) {
            // Mesma regra da carga.
            repeticoesAproximadas = simNao(rs.getString("REPETICOES_APROX"), "REPETICOES_APROX");
        }

        // Três estados: 'S', 'N' e NULL (não foi dito). NULL continua null.
        Boolean falha = simNao(rs.getString("FALHA"), "FALHA");

        return new SerieConsultada(numeroSerie, cargaKg, cargaAproximada, repeticoes, repeticoesAproximadas,
                falha);
    }

    // O contrário do JdbcUtil.setSimNaoOuNull: 'S' -> true, 'N' -> false, NULL ->
    // null. Nunca false por padrão. Outro valor = o CHECK do DDL mudou sem o código
    // acompanhar: falha alto (a mensagem cita só a coluna).
    private static Boolean simNao(String valor, String coluna) {
        if (valor == null) {
            return null;
        }
        return switch (valor) {
            case "S" -> true;
            case "N" -> false;
            default -> throw new IllegalStateException(coluna + " fora de 'S'/'N'.");
        };
    }
}
