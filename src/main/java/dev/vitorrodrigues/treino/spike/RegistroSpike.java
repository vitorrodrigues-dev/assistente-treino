package dev.vitorrodrigues.treino.spike;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

import dev.vitorrodrigues.treino.dao.UsuarioDAO;
import dev.vitorrodrigues.treino.extrator.ExtracaoException;
import dev.vitorrodrigues.treino.extrator.Extrator;
import dev.vitorrodrigues.treino.extrator.ExtratorCliente;
import dev.vitorrodrigues.treino.extrator.RelatoExtraido;
import dev.vitorrodrigues.treino.extrator.SerieExtraida;
import dev.vitorrodrigues.treino.factory.ConnectionFactory;
import dev.vitorrodrigues.treino.modelo.ExercicioComApelidos;
import dev.vitorrodrigues.treino.modelo.ResultadoRegistro;
import dev.vitorrodrigues.treino.modelo.ResultadoRegistro.SerieGravada;
import dev.vitorrodrigues.treino.modelo.ResultadoRegistro.Situacao;
import dev.vitorrodrigues.treino.service.RegistroService;

/**
 * Aceite da fatia 5: RegistroService no banco real.
 *
 * Diferente do PersistenciaSpike, aqui não dá para fazer tudo numa transação e
 * desfazer no fim: o service abre e comita as PRÓPRIAS conexões (é justamente o
 * que está sendo testado). Então o spike grava de verdade e APAGA depois, só a
 * faixa de teste (TR_USUARIO.ID_TELEGRAM 999000300 a 999000399, nunca o Vitor).
 * A limpeza roda também no INÍCIO, para tirar sobras de uma execução abortada.
 *
 * Toda verificação lê o banco com SELECT (não só o objeto Java).
 * Nada de texto de relato, dúvida ou resposta do modelo é impresso: só situação,
 * números, ids e o motivo (que, por contrato, não tem texto do usuário).
 *
 * Modos:
 * - sem argumento (ANTHROPIC_API_KEY válida): B, C, D, E, E2, F, G, H1 e H2.
 *   1 chamada paga à API (B; o C prova que não houve a segunda).
 * - "chave-errada" (rodar com ANTHROPIC_API_KEY inválida): A.
 *
 * Rodar (da raiz do projeto):
 * ./mvnw -q compile exec:java "-Dexec.mainClass=dev.vitorrodrigues.treino.spike.RegistroSpike"
 * ./mvnw -q compile exec:java "-Dexec.mainClass=dev.vitorrodrigues.treino.spike.RegistroSpike" "-Dexec.args=chave-errada"
 */
public class RegistroSpike {

    private static final long FAIXA_TESTE_INICIO = 999000300L;
    private static final long FAIXA_TESTE_FIM = 999000399L;
    private static final long ID_TELEGRAM_USUARIO_TESTE = 999000301L;

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

    // message_id de cada cenário. Só precisam ser únicos dentro do usuário de
    // teste (UQ_TR_MENSAGEM_USU_ID_TELEGRAM). O C reusa o do B de propósito.
    private static final long MSG_B = 1L;
    private static final long MSG_D = 2L;
    private static final long MSG_E = 3L;
    private static final long MSG_E2 = 4L;
    private static final long MSG_F = 5L;
    private static final long MSG_G = 6L;
    private static final long MSG_A = 7L;
    private static final long MSG_H1 = 8L;
    private static final long MSG_H2 = 9L;

    // Relato real (escrito para o teste). "academia lotada, dormi mal" é a
    // observação do dia que o D espera encontrar; "rosca martelo" não está no
    // catálogo (mesmo caso 2 do ExtratorSpike).
    private static final String RELATO_REAL = "academia lotada hoje, dormi mal. supino inclinado halter "
            + "3x10 com 24kg, depois rosca martelo 3x10 com 14kg";
    private static final String EXERCICIO_OFICIAL_DO_RELATO = "Supino inclinado halter";

    // Para os extratores falsos: o texto não é lido por eles, mas a mensagem
    // crua precisa de um (TEXTO_BRUTO NOT NULL).
    private static final String RELATO_FICTICIO = "relato de teste (extrator falso)";

    // Faixa de teste: o mesmo filtro serve para apagar e para conferir que
    // sobrou 0. Ordem de filhos para pais, porque não há ON DELETE CASCADE:
    // série -> mensagem (a série aponta para ela) -> sessão (a mensagem aponta
    // para ela) -> apelido -> usuário.
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

    // Séries de UMA mensagem (usuário + message_id). As variações acrescentam
    // uma condição no fim.
    private static final String SQL_SERIES_DA_MENSAGEM = """
            SELECT COUNT(*)
              FROM TR_SERIE S
              JOIN TR_MENSAGEM M ON M.ID_MENSAGEM = S.ID_MENSAGEM
             WHERE M.ID_USUARIO = ?
               AND M.ID_TELEGRAM = ?
            """;
    private static final String SQL_SERIES_COM_EXERCICIO = SQL_SERIES_DA_MENSAGEM + " AND S.ID_EXERCICIO = ?";
    private static final String SQL_SERIES_SEM_EXERCICIO = SQL_SERIES_DA_MENSAGEM + " AND S.ID_EXERCICIO IS NULL";
    // NULL <> ? não é verdadeiro: conta só os ids preenchidos e diferentes.
    private static final String SQL_SERIES_COM_OUTRO_EXERCICIO = SQL_SERIES_DA_MENSAGEM + " AND S.ID_EXERCICIO <> ?";
    private static final String SQL_SERIES_SEM_PERCEPCAO = SQL_SERIES_DA_MENSAGEM + " AND S.PERCEPCAO IS NULL";

    private static final String SQL_MENSAGEM_EXISTE =
            "SELECT COUNT(*) FROM TR_MENSAGEM WHERE ID_USUARIO = ? AND ID_TELEGRAM = ?";
    private static final String SQL_SESSOES_NA_DATA =
            "SELECT COUNT(*) FROM TR_SESSAO WHERE ID_USUARIO = ? AND DATA_SESSAO = ?";
    private static final String SQL_MENSAGENS_DO_USUARIO =
            "SELECT COUNT(*) FROM TR_MENSAGEM WHERE ID_USUARIO = ?";
    private static final String SQL_SESSOES_DO_USUARIO =
            "SELECT COUNT(*) FROM TR_SESSAO WHERE ID_USUARIO = ?";
    private static final String SQL_SERIES_DO_USUARIO = """
            SELECT COUNT(*)
              FROM TR_SERIE S
              JOIN TR_MENSAGEM M ON M.ID_MENSAGEM = S.ID_MENSAGEM
             WHERE M.ID_USUARIO = ?
            """;

    private static final UsuarioDAO usuarioDAO = new UsuarioDAO();

    private static int ok = 0;
    private static int falhas = 0;

    public static void main(String[] args) throws InterruptedException {
        boolean chaveErrada;
        if (args.length == 0) {
            chaveErrada = false;
        } else if (args.length == 1 && args[0].equals("chave-errada")) {
            chaveErrada = true;
        } else {
            System.out.println("Uso: RegistroSpike [chave-errada]");
            return;
        }

        // Antes de tocar no banco: sem ANTHROPIC_API_KEY, falha aqui, na subida
        // (é o comportamento que o construtor do RegistroService pede).
        ExtratorContador extratorReal = new ExtratorContador(new ExtratorCliente());

        try (Connection conn = ConnectionFactory.getConnection()) {
            conn.setAutoCommit(false);

            System.out.println("=== Limpeza inicial (sobras de execução abortada) ===");
            limparFaixaDeTeste(conn);

            try {
                long idUsuario = criarUsuarioDeTeste(conn);
                // Hora de envio no fuso do usuário, até o segundo (DATE não guarda fração).
                LocalDateTime agora = LocalDateTime.now(SAO_PAULO).withNano(0);

                if (chaveErrada) {
                    cenarioA(conn, idUsuario, agora, extratorReal);
                } else {
                    cenariosComChaveValida(conn, idUsuario, agora, extratorReal);
                }
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
            // Mesmo formato do PersistenciaSpike.
            System.err.println("Erro no banco: " + e.getMessage());
            System.err.println("Código Oracle: " + e.getErrorCode());
            System.err.println("SQLState: " + e.getSQLState());
        }
    }

    // --- Modo padrão ----------------------------------------------------------------

    private static void cenariosComChaveValida(Connection conn, long idUsuario, LocalDateTime agora,
            ExtratorContador extratorReal) throws SQLException, InterruptedException {
        RegistroService servicoReal = new RegistroService(extratorReal);
        LocalDate hoje = agora.toLocalDate();

        // --- B ------------------------------------------------------------------
        System.out.println();
        System.out.println("=== B. Relato real: 1 exercício oficial + 1 fora da lista ===");
        ResultadoRegistro b = servicoReal.registrar(idUsuario, MSG_B, agora, "TEXTO", RELATO_REAL);
        imprimir(b);
        conferir("B: situação REGISTRADO", b.situacao() == Situacao.REGISTRADO);
        conferir("B: API chamada 1 vez", extratorReal.chamadas() == 1);
        conferir("B: dataSessao do resultado = data do envio", hoje.equals(b.dataSessao()));

        Long sessaoB = idSessaoDaMensagem(conn, idUsuario, MSG_B);
        conferir("B: mensagem vinculada a uma sessão (ID_SESSAO preenchido)", sessaoB != null);
        conferir("B: DATA_SESSAO no banco = data do envio",
                sessaoB != null && hoje.equals(dataDaSessao(conn, sessaoB)));

        long seriesB = contar(conn, SQL_SERIES_DA_MENSAGEM, idUsuario, MSG_B);
        conferir("B: séries no banco (" + seriesB + ") = séries do resultado (" + b.series().size() + ")",
                seriesB > 0 && seriesB == b.series().size());

        long idOficial = idDoExercicio(conn, EXERCICIO_OFICIAL_DO_RELATO);
        conferir("B: série do oficial com ID_EXERCICIO preenchido",
                contar(conn, SQL_SERIES_COM_EXERCICIO, idUsuario, MSG_B, idOficial) > 0);
        conferir("B: série fora da lista com ID_EXERCICIO NULL",
                contar(conn, SQL_SERIES_SEM_EXERCICIO, idUsuario, MSG_B) > 0);
        conferir("B: nenhum ID_EXERCICIO apontando para outro exercício",
                contar(conn, SQL_SERIES_COM_OUTRO_EXERCICIO, idUsuario, MSG_B, idOficial) == 0);
        conferir("B: resultado traz o id do catálogo no oficial",
                b.series().stream().anyMatch(s -> Long.valueOf(idOficial).equals(s.idExercicio())));
        conferir("B: resultado traz dúvida (" + b.duvidas().size() + ")", !b.duvidas().isEmpty());

        // Pré-condição do D: quem decide se "academia lotada, dormi mal" vira
        // observação é o modelo, não o service.
        String observacaoB = sessaoB == null ? null : observacaoDaSessao(conn, sessaoB);
        conferir("B: sessão com observação do dia (pré-condição do D; depende do modelo)", observacaoB != null);

        // --- C ------------------------------------------------------------------
        System.out.println();
        System.out.println("=== C. Mesma mensagem de novo (reentrega do Telegram) ===");
        long[] antesC = contagensDoUsuario(conn, idUsuario);
        ResultadoRegistro c = servicoReal.registrar(idUsuario, MSG_B, agora, "TEXTO", RELATO_REAL);
        imprimir(c);
        conferir("C: situação JA_GRAVADA", c.situacao() == Situacao.JA_GRAVADA);
        conferir("C: API não foi chamada de novo (contador continua em 1, veio " + extratorReal.chamadas() + ")",
                extratorReal.chamadas() == 1);
        long[] depoisC = contagensDoUsuario(conn, idUsuario);
        conferir("C: nenhuma linha nova (mensagens/sessões/séries " + Arrays.toString(antesC) + " -> "
                + Arrays.toString(depoisC) + ")", Arrays.equals(antesC, depoisC));

        // --- D ------------------------------------------------------------------
        System.out.println();
        System.out.println("=== D. Segunda mensagem na mesma data (extrator falso, só observação) ===");
        String observacaoD = "segunda mensagem do dia (teste D)";
        ResultadoRegistro d = registrarComFalso(idUsuario, MSG_D, agora,
                new RelatoExtraido(observacaoD, List.of(), null));
        imprimir(d);
        conferir("D: situação REGISTRADO, lista de séries vazia",
                d.situacao() == Situacao.REGISTRADO && d.series().isEmpty());
        Long sessaoD = idSessaoDaMensagem(conn, idUsuario, MSG_D);
        conferir("D: mensagem na MESMA ID_SESSAO do B", sessaoB != null && sessaoB.equals(sessaoD));
        conferir("D: continua 1 sessão só nessa data", contar(conn, SQL_SESSOES_NA_DATA, idUsuario, hoje) == 1);
        if (observacaoB != null) {
            conferir("D: OBSERVACAO = primeira + quebra de linha + segunda",
                    (observacaoB + "\n" + observacaoD).equals(observacaoDaSessao(conn, sessaoB)));
        } else {
            // Sem a primeira, o caminho testado é o outro do CASE.
            conferir("D: OBSERVACAO = só a segunda (a do B veio NULL), sem quebra de linha no começo",
                    sessaoB != null && observacaoD.equals(observacaoDaSessao(conn, sessaoB)));
        }

        // --- E ------------------------------------------------------------------
        System.out.println();
        System.out.println("=== E. exercicio_relatado com 150 caracteres (extrator falso, data nova) ===");
        LocalDateTime envioE = agora.minusDays(1);
        String nomeLongo = "x".repeat(150);
        ResultadoRegistro e = registrarComFalso(idUsuario, MSG_E, envioE,
                new RelatoExtraido(null, List.of(serie(nomeLongo, null, 1, 20.0)), null));
        imprimir(e);
        conferir("E: situação NAO_REGISTRADO", e.situacao() == Situacao.NAO_REGISTRADO);
        conferir("E: motivo cita o campo exercicio_relatado",
                e.motivo() != null && e.motivo().contains("exercicio_relatado"));
        conferir("E: motivo não repete o texto da série", e.motivo() != null && !e.motivo().contains("xxxxx"));
        conferirSoMensagemCrua("E", conn, idUsuario, MSG_E, envioE.toLocalDate());

        // --- E2 -----------------------------------------------------------------
        System.out.println();
        System.out.println("=== E2. carga 22.333 (extrator falso, data nova) ===");
        LocalDateTime envioE2 = agora.minusDays(2);
        ResultadoRegistro e2 = registrarComFalso(idUsuario, MSG_E2, envioE2,
                new RelatoExtraido(null, List.of(serie("supino", "Supino reto barra", 1, 22.333)), null));
        imprimir(e2);
        conferir("E2: situação NAO_REGISTRADO", e2.situacao() == Situacao.NAO_REGISTRADO);
        conferir("E2: motivo cita o campo carga_kg", e2.motivo() != null && e2.motivo().contains("carga_kg"));
        conferirSoMensagemCrua("E2", conn, idUsuario, MSG_E2, envioE2.toLocalDate());

        // --- F ------------------------------------------------------------------
        System.out.println();
        System.out.println("=== F. Sem séries e sem observação (extrator falso, data nova) ===");
        LocalDateTime envioF = agora.minusDays(3);
        ResultadoRegistro f = registrarComFalso(idUsuario, MSG_F, envioF,
                new RelatoExtraido(null, List.of(), null));
        imprimir(f);
        conferir("F: situação SEM_SERIES", f.situacao() == Situacao.SEM_SERIES);
        conferir("F: duvidas null do modelo -> lista vazia, nada inventado", f.duvidas().isEmpty());
        conferirSoMensagemCrua("F", conn, idUsuario, MSG_F, envioF.toLocalDate());

        // --- G (extra: dúvida acrescentada pelo service) --------------------------
        // O B real dificilmente cai aqui: o modelo costuma devolver "exercicio"
        // null para o que não está na lista. Este caso força um nome "oficial"
        // que não existe no catálogo.
        System.out.println();
        System.out.println("=== G. Nome oficial que não existe no catálogo (extrator falso, data nova) ===");
        LocalDateTime envioG = agora.minusDays(4);
        String inexistente = "Exercício inexistente (teste G)";
        ResultadoRegistro g = registrarComFalso(idUsuario, MSG_G, envioG, new RelatoExtraido(null, List.of(
                serie("supino reto", "Supino reto barra", 1, 22.5),
                serie("exercício do teste", inexistente, 1, 10.0),
                serie("exercício do teste", inexistente, 2, 10.0)), null));
        imprimir(g);
        conferir("G: situação REGISTRADO", g.situacao() == Situacao.REGISTRADO);
        long idSupinoReto = idDoExercicio(conn, "Supino reto barra");
        conferir("G: oficial com ID_EXERCICIO preenchido",
                contar(conn, SQL_SERIES_COM_EXERCICIO, idUsuario, MSG_G, idSupinoReto) == 1);
        conferir("G: nome fora do catálogo com ID_EXERCICIO NULL (as 2 séries gravadas)",
                contar(conn, SQL_SERIES_SEM_EXERCICIO, idUsuario, MSG_G) == 2);
        conferir("G: exatamente 1 dúvida (1 por nome, e nenhuma inventada pelo duvidas null)",
                g.duvidas().equals(List.of("exercício '" + inexistente + "' não existe no catálogo")));

        // --- H (texto em branco = null) -------------------------------------------
        System.out.println();
        System.out.println("=== H1. Observação \"   \" e zero séries (extrator falso, data nova) ===");
        LocalDateTime envioH1 = agora.minusDays(5);
        ResultadoRegistro h1 = registrarComFalso(idUsuario, MSG_H1, envioH1,
                new RelatoExtraido("   ", List.of(), null));
        imprimir(h1);
        conferir("H1: situação SEM_SERIES (observação em branco conta como null)",
                h1.situacao() == Situacao.SEM_SERIES);
        conferirSoMensagemCrua("H1", conn, idUsuario, MSG_H1, envioH1.toLocalDate());

        // Na sessão do B, que depois do D tem observação com certeza (a do D, no
        // mínimo). Com uma série, para a T2 rodar e passar pelo ramo "sessão já
        // existe": sem a normalização, acrescentar "" gravaria OBSERVACAO + uma
        // quebra de linha solta. A percepção "   " confere a mesma regra na série.
        System.out.println();
        System.out.println("=== H2. Observação \"\" numa sessão que já tem observação (extrator falso) ===");
        String observacaoAntesH2 = sessaoB == null ? null : observacaoDaSessao(conn, sessaoB);
        SerieExtraida comPercepcaoEmBranco = new SerieExtraida(
                "supino reto", "Supino reto barra", 1, 22.5, false, 10, false, null, "   ");
        ResultadoRegistro h2 = registrarComFalso(idUsuario, MSG_H2, agora,
                new RelatoExtraido("", List.of(comPercepcaoEmBranco), null));
        imprimir(h2);
        conferir("H2: situação REGISTRADO", h2.situacao() == Situacao.REGISTRADO);
        conferir("H2: mensagem na mesma ID_SESSAO do B",
                sessaoB != null && sessaoB.equals(idSessaoDaMensagem(conn, idUsuario, MSG_H2)));
        conferir("H2: OBSERVACAO inalterada no banco",
                observacaoAntesH2 != null && observacaoAntesH2.equals(observacaoDaSessao(conn, sessaoB)));
        conferir("H2: percepção \"   \" gravada como NULL",
                contar(conn, SQL_SERIES_SEM_PERCEPCAO, idUsuario, MSG_H2) == 1);
    }

    // --- Modo chave-errada --------------------------------------------------------------

    private static void cenarioA(Connection conn, long idUsuario, LocalDateTime agora,
            ExtratorContador extratorReal) throws SQLException, InterruptedException {
        System.out.println();
        System.out.println("=== A. Relato real com ANTHROPIC_API_KEY inválida ===");
        ResultadoRegistro a = new RegistroService(extratorReal)
                .registrar(idUsuario, MSG_A, agora, "TEXTO", RELATO_REAL);
        imprimir(a);
        conferir("A: situação NAO_REGISTRADO", a.situacao() == Situacao.NAO_REGISTRADO);
        conferir("A: a API foi chamada (1 vez) e recusou", extratorReal.chamadas() == 1);
        conferir("A: 1 mensagem do usuário de teste", contar(conn, SQL_MENSAGENS_DO_USUARIO, idUsuario) == 1);
        conferir("A: mensagem com ID_SESSAO NULL",
                contar(conn, SQL_MENSAGEM_EXISTE, idUsuario, MSG_A) == 1
                        && idSessaoDaMensagem(conn, idUsuario, MSG_A) == null);
        conferir("A: 0 sessões", contar(conn, SQL_SESSOES_DO_USUARIO, idUsuario) == 0);
        conferir("A: 0 séries", contar(conn, SQL_SERIES_DO_USUARIO, idUsuario) == 0);
    }

    // --- Preparação e limpeza ------------------------------------------------------------

    private static long criarUsuarioDeTeste(Connection conn) throws SQLException {
        long idUsuario = usuarioDAO.inserirPendente(conn, ID_TELEGRAM_USUARIO_TESTE, "Teste Registro",
                LocalDateTime.now(SAO_PAULO));
        usuarioDAO.atualizarStatus(conn, idUsuario, "ATIVO");
        // Commit obrigatório: o service usa OUTRAS conexões, e um usuário não
        // comitado é invisível para elas (o INSERT da mensagem daria ORA-02291).
        conn.commit();
        System.out.println("Usuário de teste criado, ATIVO.");
        return idUsuario;
    }

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

    // --- Ajudantes dos cenários --------------------------------------------------------

    private static ResultadoRegistro registrarComFalso(long idUsuario, long idTelegram, LocalDateTime envio,
            RelatoExtraido resposta) throws SQLException, InterruptedException {
        return new RegistroService(new ExtratorFalso(resposta))
                .registrar(idUsuario, idTelegram, envio, "TEXTO", RELATO_FICTICIO);
    }

    // O que E, E2 e F têm em comum: só a mensagem crua ficou.
    private static void conferirSoMensagemCrua(String cenario, Connection conn, long idUsuario, long idTelegram,
            LocalDate data) throws SQLException {
        conferir(cenario + ": mensagem crua gravada", contar(conn, SQL_MENSAGEM_EXISTE, idUsuario, idTelegram) == 1);
        conferir(cenario + ": mensagem com ID_SESSAO NULL", idSessaoDaMensagem(conn, idUsuario, idTelegram) == null);
        conferir(cenario + ": 0 séries", contar(conn, SQL_SERIES_DA_MENSAGEM, idUsuario, idTelegram) == 0);
        conferir(cenario + ": 0 sessões nessa data", contar(conn, SQL_SESSOES_NA_DATA, idUsuario, data) == 0);
    }

    // Série válida; o cenário só varia o que interessa a ele.
    private static SerieExtraida serie(String relatado, String exercicio, int numero, Double carga) {
        return new SerieExtraida(relatado, exercicio, numero, carga, false, 10, false, null, null);
    }

    // Sem texto: nem exercicio_relatado, nem percepção, nem dúvidas (só quantas).
    private static void imprimir(ResultadoRegistro r) {
        System.out.println("        situação=" + r.situacao() + " data=" + r.dataSessao()
                + " séries=" + r.series().size() + " dúvidas=" + r.duvidas().size()
                + (r.motivo() == null ? "" : " motivo=" + r.motivo()));
        for (SerieGravada s : r.series()) {
            System.out.println("          série " + s.serie().numeroSerie() + ": carga=" + s.serie().cargaKg()
                    + " reps=" + s.serie().repeticoes()
                    + " idExercicio=" + (s.idExercicio() == null ? "NULL" : s.idExercicio()));
        }
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

    // --- Consultas ----------------------------------------------------------------------

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

    // null = ID_SESSAO NULL ou mensagem inexistente (conferir a existência à parte).
    private static Long idSessaoDaMensagem(Connection conn, long idUsuario, long idTelegram) throws SQLException {
        String sql = "SELECT ID_SESSAO FROM TR_MENSAGEM WHERE ID_USUARIO = ? AND ID_TELEGRAM = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, idUsuario);
            ps.setLong(2, idTelegram);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                // getObject(..., Long.class): NULL vira null (getLong daria 0).
                return rs.getObject("ID_SESSAO", Long.class);
            }
        }
    }

    private static LocalDate dataDaSessao(Connection conn, long idSessao) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT DATA_SESSAO FROM TR_SESSAO WHERE ID_SESSAO = ?")) {
            ps.setLong(1, idSessao);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject("DATA_SESSAO", LocalDate.class) : null;
            }
        }
    }

    private static String observacaoDaSessao(Connection conn, long idSessao) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT OBSERVACAO FROM TR_SESSAO WHERE ID_SESSAO = ?")) {
            ps.setLong(1, idSessao);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("OBSERVACAO") : null;
            }
        }
    }

    // -1 = não achou (o IDENTITY começa em 1): as conferências com ele falham.
    private static long idDoExercicio(Connection conn, String nome) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT ID_EXERCICIO FROM TR_EXERCICIO WHERE NOME = ?")) {
            ps.setString(1, nome);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("ID_EXERCICIO") : -1L;
            }
        }
    }

    // {mensagens, sessões, séries} do usuário de teste.
    private static long[] contagensDoUsuario(Connection conn, long idUsuario) throws SQLException {
        return new long[] {
                contar(conn, SQL_MENSAGENS_DO_USUARIO, idUsuario),
                contar(conn, SQL_SESSOES_DO_USUARIO, idUsuario),
                contar(conn, SQL_SERIES_DO_USUARIO, idUsuario)};
    }

    // --- Extratores do spike ---------------------------------------------------------------

    /**
     * Devolve sempre o mesmo RelatoExtraido, montado à mão. Não chama a API nem lê
     * o relato: serve para provocar exatamente o caso que o cenário quer testar.
     */
    private record ExtratorFalso(RelatoExtraido resposta) implements Extrator {
        @Override
        public RelatoExtraido extrair(String relato, LocalDate dataHoje, List<ExercicioComApelidos> exercicios) {
            return resposta;
        }
    }

    /**
     * Embrulha qualquer Extrator e conta as chamadas. É a prova de que o
     * JA_GRAVADA não chamou a API (não gastou crédito).
     */
    private static final class ExtratorContador implements Extrator {
        private final Extrator embrulhado;
        private int chamadas = 0;

        ExtratorContador(Extrator embrulhado) {
            this.embrulhado = embrulhado;
        }

        @Override
        public RelatoExtraido extrair(String relato, LocalDate dataHoje, List<ExercicioComApelidos> exercicios)
                throws IOException, InterruptedException, ExtracaoException {
            // Conta antes de chamar: a chamada que falha (cenário A) também conta.
            chamadas++;
            return embrulhado.extrair(relato, dataHoje, exercicios);
        }

        int chamadas() {
            return chamadas;
        }
    }
}
