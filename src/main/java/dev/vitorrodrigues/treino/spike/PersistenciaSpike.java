package dev.vitorrodrigues.treino.spike;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import dev.vitorrodrigues.treino.dao.MensagemDAO;
import dev.vitorrodrigues.treino.dao.SerieDAO;
import dev.vitorrodrigues.treino.dao.SessaoDAO;
import dev.vitorrodrigues.treino.dao.UsuarioDAO;
import dev.vitorrodrigues.treino.extrator.SerieExtraida;
import dev.vitorrodrigues.treino.factory.ConnectionFactory;
import dev.vitorrodrigues.treino.modelo.ResultadoInsercao;
import dev.vitorrodrigues.treino.modelo.Usuario;

/**
 * Aceite da fatia 4: exercita os quatro DAOs no banco real e desfaz TUDO no fim.
 *
 * Uma conexão só, com autoCommit desligado: tudo o que o spike grava fica numa
 * transação aberta, e o finally faz rollback() mesmo se algo der errado.
 * ATENÇÃO: no driver da Oracle, fechar a conexão sem commit nem rollback
 * COMITA o que estiver pendente. Por isso o rollback é explícito, e não
 * "deixar o try-with-resources fechar".
 *
 * Usa só usuários de teste (ID_TELEGRAM 999000201 e 999000202), nunca o do Vitor.
 * Não imprime nenhum dado real.
 *
 * Rodar:
 * ./mvnw -q compile exec:java "-Dexec.mainClass=dev.vitorrodrigues.treino.spike.PersistenciaSpike"
 */
public class PersistenciaSpike {

    private static final long ID_TELEGRAM_TESTE_A = 999000201L;
    private static final long ID_TELEGRAM_TESTE_B = 999000202L;

    // ID_USUARIO que não existe (o IDENTITY começa em 1).
    private static final long ID_USUARIO_INEXISTENTE = -1L;

    // ORA-02291: a FK não achou a linha "pai" (aqui, o usuário).
    private static final int ORA_FK_PAI_NAO_ENCONTRADO = 2291;

    // ORA-01400: NULL numa coluna NOT NULL.
    private static final int ORA_NOT_NULL = 1400;

    private static final UsuarioDAO usuarioDAO = new UsuarioDAO();
    private static final MensagemDAO mensagemDAO = new MensagemDAO();
    private static final SessaoDAO sessaoDAO = new SessaoDAO();
    private static final SerieDAO serieDAO = new SerieDAO();

    private static int ok = 0;
    private static int falhas = 0;

    public static void main(String[] args) {
        try (Connection conn = ConnectionFactory.getConnection()) {
            conn.setAutoCommit(false);

            try {
                executarCenarios(conn);
            } finally {
                // Roda com sucesso ou com exceção: nada do spike fica no banco.
                conn.rollback();
                System.out.println();
                System.out.println("Rollback feito.");
            }

            conferirSemLixo(conn);

            System.out.println();
            System.out.println("RESULTADO: " + ok + " ok, " + falhas + " falhas");

        } catch (SQLException e) {
            // Mesmo formato do ListarExerciciosSpike. A mensagem do Oracle não traz
            // dado de usuário real: o spike só grava dados de teste.
            System.err.println("Erro no banco: " + e.getMessage());
            System.err.println("Código Oracle: " + e.getErrorCode());
            System.err.println("SQLState: " + e.getSQLState());
        }
    }

    private static void executarCenarios(Connection conn) throws SQLException {
        LocalDateTime agora = LocalDateTime.now();
        LocalDate hoje = LocalDate.now();

        // --- Usuários de teste ------------------------------------------------
        System.out.println("=== Usuários ===");
        long idA = usuarioDAO.inserirPendente(conn, ID_TELEGRAM_TESTE_A, "Teste A", agora);
        long idB = usuarioDAO.inserirPendente(conn, ID_TELEGRAM_TESTE_B, "Teste B", agora);

        Optional<Usuario> lidoA = usuarioDAO.buscarPorIdTelegram(conn, ID_TELEGRAM_TESTE_A);
        conferir("buscarPorIdTelegram acha o usuário inserido, PENDENTE",
                lidoA.isPresent() && lidoA.get().idUsuario() == idA
                        && "PENDENTE".equals(lidoA.get().status()));

        conferir("buscarPorIdTelegram de quem nunca mandou /start -> vazio",
                usuarioDAO.buscarPorIdTelegram(conn, 999000299L).isEmpty());

        usuarioDAO.atualizarStatus(conn, idA, "ATIVO");
        conferir("atualizarStatus -> ATIVO",
                usuarioDAO.buscarPorIdTelegram(conn, ID_TELEGRAM_TESTE_A).map(Usuario::ativo).orElse(false));

        try {
            usuarioDAO.atualizarStatus(conn, ID_USUARIO_INEXISTENTE, "ATIVO");
            conferir("atualizarStatus de usuário inexistente -> exceção", false);
        } catch (IllegalStateException e) {
            conferir("atualizarStatus de usuário inexistente -> exceção", true);
        }

        // --- Mensagens ---------------------------------------------------------
        System.out.println();
        System.out.println("=== Mensagens ===");
        ResultadoInsercao primeira = mensagemDAO.inserir(conn, idA, 1L, agora, "TEXTO", "relato de teste");
        conferir("1ª gravação -> gravada, com id", !primeira.jaGravada() && primeira.idMensagem() != null);

        ResultadoInsercao segunda = mensagemDAO.inserir(conn, idA, 1L, agora, "TEXTO", "relato de teste");
        conferir("mesma mensagem 2ª vez -> já gravada, sem exceção", segunda.jaGravada());

        try {
            ResultadoInsercao r = mensagemDAO.inserir(conn, ID_USUARIO_INEXISTENTE, 2L, agora, "TEXTO", "x");
            conferir("usuário inexistente -> exceção de FK (veio " + (r.jaGravada() ? "'já gravada'" : "'gravada'")
                    + ")", false);
        } catch (SQLException e) {
            // Captura SQLException (e não só a subclasse de integridade) para que
            // QUALQUER erro diferente apareça como falha com o código, em vez de
            // derrubar o spike.
            conferir("usuário inexistente -> exceção de FK, não 'já gravada' (ORA-" + e.getErrorCode() + ")",
                    e.getErrorCode() == ORA_FK_PAI_NAO_ENCONTRADO);
        }

        ResultadoInsercao deB = mensagemDAO.inserir(conn, idB, 1L, agora, "TEXTO", "relato de teste");
        conferir("mesmo message_id, outro usuário -> gravada", !deB.jaGravada());

        // --- Sessões e vínculo ---------------------------------------------------
        System.out.println();
        System.out.println("=== Sessões ===");
        conferir("buscarPorUsuarioEData antes de inserir -> vazio",
                sessaoDAO.buscarPorUsuarioEData(conn, idA, hoje).isEmpty());

        long sessaoA = sessaoDAO.inserir(conn, idA, hoje, null);
        long sessaoB = sessaoDAO.inserir(conn, idB, hoje, "observação de teste");

        conferir("buscarPorUsuarioEData acha a sessão do próprio usuário",
                sessaoDAO.buscarPorUsuarioEData(conn, idA, hoje).equals(Optional.of(sessaoA)));
        conferir("observação null -> NULL", colunaEhNula(conn,
                "SELECT OBSERVACAO FROM TR_SESSAO WHERE ID_SESSAO = ?", sessaoA, "OBSERVACAO"));

        long idMensagemA = primeira.idMensagem();
        try {
            mensagemDAO.vincularSessao(conn, idMensagemA, sessaoB, idA);
            conferir("vincularSessao: mensagem de A + sessão de B -> exceção", false);
        } catch (IllegalStateException e) {
            conferir("vincularSessao: mensagem de A + sessão de B -> exceção", true);
        }

        mensagemDAO.vincularSessao(conn, idMensagemA, sessaoA, idA);
        conferir("vincularSessao: mensagem de A + sessão de A -> vinculada", lerLong(conn,
                "SELECT ID_SESSAO FROM TR_MENSAGEM WHERE ID_MENSAGEM = ?", idMensagemA, "ID_SESSAO") == sessaoA);

        // --- Séries ------------------------------------------------------------
        System.out.println();
        System.out.println("=== Séries ===");
        // Tudo o que pode faltar, faltando. Os 4 obrigatórios preenchidos.
        SerieExtraida semDados = new SerieExtraida(
                "pull down", null, 1, null, false, null, false, null, null);
        long serieVazia = serieDAO.inserir(conn, idMensagemA, semDados, null);
        imprimirSerie(conn, serieVazia);
        conferir("carga, repetições, falha, exercício e percepção ausentes -> NULL",
                serieTemNulos(conn, serieVazia));

        SerieExtraida semFalha = new SerieExtraida(
                "supino", null, 2, 22.5, false, 10, false, false, null);
        long serieN = serieDAO.inserir(conn, idMensagemA, semFalha, null);
        conferir("falha=false -> 'N'", "N".equals(lerTexto(conn, serieN, "FALHA")));
        BigDecimal carga = lerCarga(conn, serieN);
        conferir("carga 22.5 -> 22.5", carga != null && new BigDecimal("22.5").compareTo(carga) == 0);

        SerieExtraida comFalha = new SerieExtraida(
                "supino", null, 3, 22.5, false, 8, true, true, "pesado");
        long serieS = serieDAO.inserir(conn, idMensagemA, comFalha, null);
        conferir("falha=true -> 'S'", "S".equals(lerTexto(conn, serieS, "FALHA")));
        conferir("repeticoes_aproximadas=true -> 'S'", "S".equals(lerTexto(conn, serieS, "REPETICOES_APROX")));

        // Obrigatório ausente: o DAO não grava 'N' no lugar, o banco recusa.
        SerieExtraida quebrada = new SerieExtraida(
                "supino", null, 4, null, null, null, false, null, null);
        try {
            serieDAO.inserir(conn, idMensagemA, quebrada, null);
            conferir("carga_aproximada null -> recusado pelo banco (não vira 'N')", false);
        } catch (SQLException e) {
            conferir("carga_aproximada null -> recusado pelo banco, não vira 'N' (ORA-" + e.getErrorCode() + ")",
                    e.getErrorCode() == ORA_NOT_NULL);
        }
    }

    /**
     * Depois do rollback: as tabelas voltam ao estado de antes do spike.
     * Antes da fatia 5 não existe mensagem, sessão nem série reais, então o
     * esperado é 0, 0, 0 e só o administrador em TR_USUARIO.
     */
    private static void conferirSemLixo(Connection conn) throws SQLException {
        System.out.println();
        System.out.println("=== Sem lixo (depois do rollback) ===");
        conferir("TR_MENSAGEM = 0", contar(conn, "SELECT COUNT(*) FROM TR_MENSAGEM") == 0);
        conferir("TR_SESSAO = 0", contar(conn, "SELECT COUNT(*) FROM TR_SESSAO") == 0);
        conferir("TR_SERIE = 0", contar(conn, "SELECT COUNT(*) FROM TR_SERIE") == 0);
        conferir("TR_USUARIO = 1", contar(conn, "SELECT COUNT(*) FROM TR_USUARIO") == 1);
        conferir("nenhum usuário de teste sobrou", contar(conn,
                "SELECT COUNT(*) FROM TR_USUARIO WHERE ID_TELEGRAM BETWEEN 999000200 AND 999000299") == 0);
    }

    // --- Ajudantes ---------------------------------------------------------------

    private static void conferir(String cenario, boolean passou) {
        if (passou) {
            ok++;
            System.out.println("[ok]    " + cenario);
        } else {
            falhas++;
            System.out.println("[FALHA] " + cenario);
        }
    }

    // O padrão pedido no aceite: ler a coluna e perguntar ao ResultSet se o
    // último valor lido era NULL. getLong/getDouble devolvem 0 para NULL; só o
    // wasNull() diferencia "0 de verdade" de "vazio".
    private static boolean serieTemNulos(Connection conn, long idSerie) throws SQLException {
        String sql = """
                SELECT CARGA_KG, REPETICOES, FALHA, ID_EXERCICIO, PERCEPCAO
                  FROM TR_SERIE WHERE ID_SERIE = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, idSerie);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                rs.getDouble("CARGA_KG");
                boolean cargaNula = rs.wasNull();
                rs.getInt("REPETICOES");
                boolean repsNulas = rs.wasNull();
                rs.getString("FALHA");
                boolean falhaNula = rs.wasNull();
                rs.getLong("ID_EXERCICIO");
                boolean exercicioNulo = rs.wasNull();
                rs.getString("PERCEPCAO");
                boolean percepcaoNula = rs.wasNull();
                return cargaNula && repsNulas && falhaNula && exercicioNulo && percepcaoNula;
            }
        }
    }

    private static void imprimirSerie(Connection conn, long idSerie) throws SQLException {
        String sql = """
                SELECT CARGA_KG, REPETICOES, FALHA, ID_EXERCICIO
                  FROM TR_SERIE WHERE ID_SERIE = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, idSerie);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    System.out.println("        CARGA_KG=" + valorOuNull(rs, "CARGA_KG")
                            + " REPETICOES=" + valorOuNull(rs, "REPETICOES")
                            + " FALHA=" + valorOuNull(rs, "FALHA")
                            + " ID_EXERCICIO=" + valorOuNull(rs, "ID_EXERCICIO"));
                }
            }
        }
    }

    private static String valorOuNull(ResultSet rs, String coluna) throws SQLException {
        String valor = rs.getString(coluna);
        return rs.wasNull() ? "NULL" : valor;
    }

    private static boolean colunaEhNula(Connection conn, String sql, long id, String coluna) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                rs.getString(coluna);
                return rs.wasNull();
            }
        }
    }

    private static long lerLong(Connection conn, String sql, long id, String coluna) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return -1;
                }
                long valor = rs.getLong(coluna);
                return rs.wasNull() ? -1 : valor;
            }
        }
    }

    // A coluna vem de uma constante deste spike, nunca de entrada externa:
    // por isso pode ir concatenada. Valores, sempre por "?".
    private static String lerTexto(Connection conn, long idSerie, String coluna) throws SQLException {
        String sql = "SELECT " + coluna + " FROM TR_SERIE WHERE ID_SERIE = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, idSerie);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(coluna) : null;
            }
        }
    }

    private static BigDecimal lerCarga(Connection conn, long idSerie) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT CARGA_KG FROM TR_SERIE WHERE ID_SERIE = ?")) {
            ps.setLong(1, idSerie);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBigDecimal("CARGA_KG") : null;
            }
        }
    }

    private static long contar(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
