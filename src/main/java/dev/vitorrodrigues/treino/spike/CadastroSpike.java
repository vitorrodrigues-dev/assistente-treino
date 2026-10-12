package dev.vitorrodrigues.treino.spike;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import dev.vitorrodrigues.treino.dao.UsuarioDAO;
import dev.vitorrodrigues.treino.factory.ConnectionFactory;
import dev.vitorrodrigues.treino.modelo.ResultadoAprovacao;
import dev.vitorrodrigues.treino.modelo.ResultadoAprovacao.Situacao;
import dev.vitorrodrigues.treino.modelo.Usuario;
import dev.vitorrodrigues.treino.service.CadastroService;

/**
 * Aceite da fatia 6b no banco real: UsuarioDAO (inserirPendente, buscarPorId,
 * atualizarStatus) e CadastroService (cadastrarPendente, aprovar).
 *
 * Mesmo esquema do RegistroSpike: o CadastroService abre e comita as PRÓPRIAS
 * conexões (é o que está sendo testado), então o spike grava de verdade e APAGA
 * depois, só a faixa de teste (TR_USUARIO.ID_TELEGRAM 999000400 a 999000499,
 * nunca o Vitor). A limpeza roda também no INÍCIO, para tirar sobras de uma
 * execução abortada, e no fim confere que a faixa ficou com 0 linhas.
 *
 * Toda verificação lê o banco (não só o objeto Java). Nada de nome, id do
 * Telegram ou ID_USUARIO é impresso, nem um Usuario inteiro (o toString() dele
 * tem nome e id): só o que foi conferido.
 *
 * Não chama a API da Anthropic nem a do Telegram.
 *
 * Rodar (da raiz do projeto):
 * ./mvnw -q compile exec:java "-Dexec.mainClass=dev.vitorrodrigues.treino.spike.CadastroSpike"
 * Conferir a última linha: "RESULTADO: N ok, 0 falhas".
 */
public class CadastroSpike {

    private static final long FAIXA_TESTE_INICIO = 999000400L;
    private static final long FAIXA_TESTE_FIM = 999000499L;
    // Um id do Telegram por cenário (todos dentro da faixa).
    private static final long ID_TELEGRAM_DAO = 999000401L;
    private static final long ID_TELEGRAM_SERVICE = 999000402L;
    private static final long ID_TELEGRAM_EMOJI = 999000403L;
    private static final long ID_TELEGRAM_BLOQUEADO = 999000404L;

    // ID_USUARIO que não existe: o IDENTITY começa em 1 e não chega perto disto.
    private static final long ID_USUARIO_INEXISTENTE = 999_999_999_999L;

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final int ORA_UNIQUE = 1;

    // Faixa de teste: o mesmo filtro serve para apagar e para conferir que sobrou
    // 0. Mesma lista e mesma ordem do RegistroSpike (filhos antes dos pais, sem ON
    // DELETE CASCADE): este spike só cria usuários, mas a limpeza não supõe isso.
    private static final String USUARIOS_DE_TESTE =
            "SELECT ID_USUARIO FROM TR_USUARIO WHERE ID_TELEGRAM BETWEEN ? AND ?";

    private record TabelaDeTeste(String nome, String filtro) {
    }

    private static final List<TabelaDeTeste> TABELAS_DE_TESTE = List.of(
            new TabelaDeTeste("TR_SERIE",
                    "ID_MENSAGEM IN (SELECT ID_MENSAGEM FROM TR_MENSAGEM WHERE ID_USUARIO IN ("
                            + USUARIOS_DE_TESTE + "))"),
            new TabelaDeTeste("TR_MENSAGEM", "ID_USUARIO IN (" + USUARIOS_DE_TESTE + ")"),
            new TabelaDeTeste("TR_SESSAO", "ID_USUARIO IN (" + USUARIOS_DE_TESTE + ")"),
            new TabelaDeTeste("TR_APELIDO", "ID_USUARIO IN (" + USUARIOS_DE_TESTE + ")"),
            new TabelaDeTeste("TR_USUARIO", "ID_TELEGRAM BETWEEN ? AND ?"));

    private static final String SQL_USUARIOS_COM_ID_TELEGRAM =
            "SELECT COUNT(*) FROM TR_USUARIO WHERE ID_TELEGRAM = ?";

    private static final UsuarioDAO usuarioDAO = new UsuarioDAO();

    private static int ok = 0;
    private static int falhas = 0;

    public static void main(String[] args) {
        try (Connection conn = ConnectionFactory.getConnection()) {
            conn.setAutoCommit(false);

            System.out.println("=== Limpeza inicial (sobras de execução abortada) ===");
            limparFaixaDeTeste(conn);

            try {
                // Até o segundo: o "date" do Telegram não tem fração, e assim a
                // comparação com o que volta do TIMESTAMP é exata.
                LocalDateTime envio = LocalDateTime.now(SAO_PAULO).withNano(0);
                dao(conn, envio);
                service(conn, envio);
            } finally {
                // Roda com sucesso ou com exceção no meio dos cenários.
                System.out.println();
                System.out.println("=== Limpeza final ===");
                limparFaixaDeTeste(conn);
                conferirFaixaVazia(conn);
            }

            System.out.println();
            System.out.println("RESULTADO: " + ok + " ok, " + falhas + " falhas");

        } catch (SQLException e) {
            // Mesmo formato do RegistroSpike. A mensagem do Oracle pode citar o
            // esquema; aqui é o terminal do Vitor, rodando um teste.
            System.err.println("Erro no banco: " + e.getMessage());
            System.err.println("Código Oracle: " + e.getErrorCode());
            System.err.println("SQLState: " + e.getSQLState());
        }
    }

    // --- UsuarioDAO ------------------------------------------------------------------------

    private static void dao(Connection conn, LocalDateTime envio) throws SQLException {
        System.out.println();
        System.out.println("=== D. UsuarioDAO: inserirPendente -> buscarPorId -> atualizarStatus ===");
        String nome = "Teste Cadastro DAO";
        long idUsuario = usuarioDAO.inserirPendente(conn, ID_TELEGRAM_DAO, nome, envio);
        conferir("D: inserirPendente devolveu um ID_USUARIO (> 0)", idUsuario > 0);

        Optional<Usuario> lido = usuarioDAO.buscarPorId(conn, idUsuario);
        conferir("D: buscarPorId achou o usuário", lido.isPresent());
        conferir("D: ID_USUARIO e ID_TELEGRAM certos (não trocados)", lido.isPresent()
                && lido.get().idUsuario() == idUsuario && lido.get().idTelegram() == ID_TELEGRAM_DAO);
        conferir("D: NOME como foi gravado", lido.isPresent() && nome.equals(lido.get().nome()));
        conferir("D: STATUS PENDENTE", lido.isPresent() && "PENDENTE".equals(lido.get().status()));
        conferir("D: DATA_CADASTRO = data de envio, até o segundo",
                lido.isPresent() && envio.equals(lido.get().dataCadastro()));

        usuarioDAO.atualizarStatus(conn, idUsuario, "ATIVO");
        Optional<Usuario> depois = usuarioDAO.buscarPorId(conn, idUsuario);
        conferir("D: atualizarStatus -> STATUS ATIVO", depois.isPresent() && "ATIVO".equals(depois.get().status()));
        conferir("D: o resto da linha não mudou", depois.isPresent() && nome.equals(depois.get().nome())
                && depois.get().idTelegram() == ID_TELEGRAM_DAO && envio.equals(depois.get().dataCadastro()));

        conferir("D: buscarPorId de id inexistente -> vazio",
                usuarioDAO.buscarPorId(conn, ID_USUARIO_INEXISTENTE).isEmpty());

        // Comita: as conferências da faixa no fim contam com esta linha.
        conn.commit();
    }

    // --- CadastroService --------------------------------------------------------------------

    private static void service(Connection conn, LocalDateTime envio) throws SQLException {
        CadastroService servico = new CadastroService();

        // --- S1: cadastro comitado ----------------------------------------------------
        System.out.println();
        System.out.println("=== S1. cadastrarPendente (conexão do service; a do spike lê depois) ===");
        String nome = "Teste Cadastro Ação";
        long idUsuario = servico.cadastrarPendente(ID_TELEGRAM_SERVICE, nome, envio);
        // Lido pela conexão do SPIKE: só aparece se o service comitou.
        Optional<Usuario> lido = usuarioDAO.buscarPorIdTelegram(conn, ID_TELEGRAM_SERVICE);
        conferir("S1: comitado (outra conexão enxerga a linha)", lido.isPresent());
        conferir("S1: mesmo ID_USUARIO devolvido pelo service",
                lido.isPresent() && lido.get().idUsuario() == idUsuario);
        conferir("S1: PENDENTE, NOME com acento intacto, DATA_CADASTRO = envio", lido.isPresent()
                && "PENDENTE".equals(lido.get().status()) && nome.equals(lido.get().nome())
                && envio.equals(lido.get().dataCadastro()));

        // --- S2: limite do NOME --------------------------------------------------------
        // O loop aceita até 100 caracteres (codePointCount). Confere que o banco
        // aceita os mesmos 100, mesmo com caractere de 4 bytes: o VARCHAR2(100
        // CHAR) conta caracteres, não bytes.
        System.out.println();
        System.out.println("=== S2. NOME com 100 emojis (o limite que o loop aceita) ===");
        String nome100 = "💪".repeat(100);
        long idEmoji = servico.cadastrarPendente(ID_TELEGRAM_EMOJI, nome100, envio);
        Optional<Usuario> emoji = usuarioDAO.buscarPorId(conn, idEmoji);
        conferir("S2: aceito e devolvido igual (sem truncar, sem '?')",
                emoji.isPresent() && nome100.equals(emoji.get().nome()));

        // --- S3: segundo cadastro do mesmo from.id ------------------------------------
        System.out.println();
        System.out.println("=== S3. cadastrarPendente de novo com o mesmo ID_TELEGRAM ===");
        SQLException recusa = null;
        try {
            servico.cadastrarPendente(ID_TELEGRAM_SERVICE, "Teste Cadastro Repetido", envio);
        } catch (SQLException e) {
            recusa = e;
        }
        // O loop trata SQLIntegrityConstraintViolationException como recusa
        // (avança): é por esta classe que ele decide, não pelo código.
        conferir("S3: recusado com SQLIntegrityConstraintViolationException",
                recusa instanceof SQLIntegrityConstraintViolationException);
        conferir("S3: ORA-00001 (UQ_TR_USUARIO_ID_TELEGRAM)", recusa != null && recusa.getErrorCode() == ORA_UNIQUE);
        conferir("S3: continua 1 linha com esse ID_TELEGRAM (rollback)",
                contar(conn, SQL_USUARIOS_COM_ID_TELEGRAM, ID_TELEGRAM_SERVICE) == 1);

        // --- S4: aprovar PENDENTE ------------------------------------------------------
        System.out.println();
        System.out.println("=== S4. aprovar(PENDENTE) ===");
        ResultadoAprovacao aprovado = servico.aprovar(idUsuario);
        System.out.println("        " + aprovado);
        conferir("S4: APROVADO", aprovado.situacao() == Situacao.APROVADO);
        conferir("S4: devolve o NOME e o ID_TELEGRAM do aprovado (para o aviso)",
                nome.equals(aprovado.nome()) && Long.valueOf(ID_TELEGRAM_SERVICE).equals(aprovado.idTelegram()));
        Optional<Usuario> ativo = usuarioDAO.buscarPorId(conn, idUsuario);
        conferir("S4: ATIVO no banco (comitado: lido pela conexão do spike)",
                ativo.isPresent() && "ATIVO".equals(ativo.get().status()));

        // --- S5: aprovar de novo ------------------------------------------------------
        System.out.println();
        System.out.println("=== S5. aprovar(o mesmo) de novo ===");
        ResultadoAprovacao deNovo = servico.aprovar(idUsuario);
        System.out.println("        " + deNovo);
        conferir("S5: JA_ATIVO", deNovo.situacao() == Situacao.JA_ATIVO);
        conferir("S5: continua ATIVO", "ATIVO".equals(statusNoBanco(conn, idUsuario)));

        // --- S6: aprovar BLOQUEADO -----------------------------------------------------
        System.out.println();
        System.out.println("=== S6. aprovar(BLOQUEADO) ===");
        long idBloqueado = usuarioDAO.inserirPendente(conn, ID_TELEGRAM_BLOQUEADO, "Teste Cadastro Bloqueado", envio);
        usuarioDAO.atualizarStatus(conn, idBloqueado, "BLOQUEADO");
        // Commit obrigatório: o service usa OUTRA conexão, e um usuário não
        // comitado é invisível para ela (o aprovar diria INEXISTENTE).
        conn.commit();
        ResultadoAprovacao bloqueado = servico.aprovar(idBloqueado);
        System.out.println("        " + bloqueado);
        conferir("S6: BLOQUEADO", bloqueado.situacao() == Situacao.BLOQUEADO);
        conferir("S6: continua BLOQUEADO (sem UPDATE)", "BLOQUEADO".equals(statusNoBanco(conn, idBloqueado)));

        // --- S7: aprovar inexistente --------------------------------------------------
        System.out.println();
        System.out.println("=== S7. aprovar(id inexistente) ===");
        ResultadoAprovacao inexistente = servico.aprovar(ID_USUARIO_INEXISTENTE);
        System.out.println("        " + inexistente);
        conferir("S7: INEXISTENTE", inexistente.situacao() == Situacao.INEXISTENTE);
    }

    // --- Preparação e limpeza (como no RegistroSpike) ------------------------------------------

    private static void limparFaixaDeTeste(Connection conn) throws SQLException {
        boolean comitou = false;
        try {
            for (TabelaDeTeste tabela : TABELAS_DE_TESTE) {
                // Nome e filtro vêm das constantes deste spike, nunca de entrada
                // externa: por isso podem ir concatenados. Valores, sempre por "?".
                String sql = "DELETE FROM " + tabela.nome() + " WHERE " + tabela.filtro();
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setLong(1, FAIXA_TESTE_INICIO);
                    ps.setLong(2, FAIXA_TESTE_FIM);
                    System.out.println("  " + tabela.nome() + ": " + ps.executeUpdate() + " linha(s) apagada(s)");
                }
            }
            // Tudo ou nada: se um DELETE falhar, nenhum vale.
            conn.commit();
            comitou = true;
        } finally {
            // O driver da Oracle comita ao fechar: rollback explícito.
            if (!comitou) {
                conn.rollback();
            }
        }
    }

    private static void conferirFaixaVazia(Connection conn) throws SQLException {
        System.out.println();
        System.out.println("=== Faixa de teste depois da limpeza ===");
        for (TabelaDeTeste tabela : TABELAS_DE_TESTE) {
            String sql = "SELECT COUNT(*) FROM " + tabela.nome() + " WHERE " + tabela.filtro();
            long linhas = contar(conn, sql, FAIXA_TESTE_INICIO, FAIXA_TESTE_FIM);
            conferir(tabela.nome() + " na faixa de teste = 0 (veio " + linhas + ")", linhas == 0);
        }
    }

    // --- Ajudantes -------------------------------------------------------------------------------

    // null = usuário não existe.
    private static String statusNoBanco(Connection conn, long idUsuario) throws SQLException {
        return usuarioDAO.buscarPorId(conn, idUsuario).map(Usuario::status).orElse(null);
    }

    private static void conferir(String cenario, boolean passou) {
        if (passou) {
            ok++;
            System.out.println("[ok]    " + cenario);
        } else {
            falhas++;
            System.out.println("[FALHA] " + cenario);
        }
    }

    private static long contar(Connection conn, String sql, Object... parametros) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < parametros.length; i++) {
                ps.setObject(i + 1, parametros[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("COUNT(*) sem linha.");
                }
                return rs.getLong(1);
            }
        }
    }
}
