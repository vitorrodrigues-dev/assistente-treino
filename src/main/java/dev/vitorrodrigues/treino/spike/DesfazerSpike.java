package dev.vitorrodrigues.treino.spike;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import dev.vitorrodrigues.treino.dao.MensagemDAO;
import dev.vitorrodrigues.treino.dao.SerieDAO;
import dev.vitorrodrigues.treino.dao.SessaoDAO;
import dev.vitorrodrigues.treino.dao.UsuarioDAO;
import dev.vitorrodrigues.treino.extrator.SerieExtraida;
import dev.vitorrodrigues.treino.factory.ConnectionFactory;
import dev.vitorrodrigues.treino.modelo.ResultadoDesfazer;
import dev.vitorrodrigues.treino.modelo.ResultadoDesfazer.Situacao;
import dev.vitorrodrigues.treino.modelo.ResultadoInsercao;
import dev.vitorrodrigues.treino.modelo.SerieResumida;
import dev.vitorrodrigues.treino.service.CorrecaoService;

/**
 * Aceite da fatia 6c no banco real: CorrecaoService.desfazerUltima, os métodos
 * novos do MensagemDAO (buscarUltimaParaDesfazer, jaDesfeitaPeloComando,
 * marcarDesfeita) e do SerieDAO (listarResumoDaMensagem), a
 * CK_TR_MENSAGEM_DESFEITA e o índice UQ_TR_MENSAGEM_DESFEITA_POR.
 *
 * Mesmo esquema do CadastroSpike: o CorrecaoService abre e comita as PRÓPRIAS
 * conexões (é o que está sendo testado), então o spike grava de verdade e APAGA
 * depois, só a faixa de teste (TR_USUARIO.ID_TELEGRAM 999000500 a 999000599,
 * nunca o Vitor). A limpeza roda também no INÍCIO, para tirar sobras de uma
 * execução abortada, e no fim confere que a faixa ficou com 0 linhas.
 *
 * Os cenários são montados direto pelos DAOs da fatia 4 (usuário, sessão,
 * mensagem, vínculo, séries), SEM chamar a API da Anthropic nem a do Telegram.
 * Um usuário de teste por cenário: o /desfazer de um cenário nunca enxerga as
 * mensagens de outro (o próprio isolamento por usuário é um dos cenários).
 *
 * Datas fixas: o service usa a data de envio que recebe, nunca o relógio, então
 * o resultado não depende do dia em que o spike roda. Cada /desfazer tem o seu
 * message_id (o comando); a reentrega usa o MESMO de propósito.
 *
 * Toda verificação lê o banco pela conexão do SPIKE (só enxerga o que o service
 * comitou). Nada de texto, nome ou id é impresso: só o que foi conferido.
 *
 * Rodar (da raiz do projeto), DEPOIS do db/05-desfazer.sql:
 * ./mvnw -q compile exec:java "-Dexec.mainClass=dev.vitorrodrigues.treino.spike.DesfazerSpike"
 * Conferir a última linha: "RESULTADO: N ok, 0 falhas".
 */
public class DesfazerSpike {

    private static final long FAIXA_TESTE_INICIO = 999000500L;
    private static final long FAIXA_TESTE_FIM = 999000599L;
    // Um usuário por cenário (todos dentro da faixa).
    private static final long ID_TELEGRAM_PILHA = 999000501L;
    private static final long ID_TELEGRAM_JANELA = 999000502L;
    private static final long ID_TELEGRAM_SEM_SESSAO = 999000503L;
    private static final long ID_TELEGRAM_DONO = 999000504L;
    private static final long ID_TELEGRAM_OUTRO = 999000505L;
    private static final long ID_TELEGRAM_SO_OBSERVACAO = 999000506L;
    private static final long ID_TELEGRAM_REENTREGA = 999000507L;
    private static final long ID_TELEGRAM_ORDEM = 999000508L;
    private static final long ID_TELEGRAM_CONSTRAINT = 999000509L;

    // O "agora" de cada /desfazer: o campo date do comando, já em São Paulo.
    // Valores diferentes, para a conferência saber QUAL /desfazer marcou cada mensagem.
    private static final LocalDateTime DESFAZER_1 = LocalDateTime.of(2026, 10, 12, 12, 0, 0);
    private static final LocalDateTime DESFAZER_2 = LocalDateTime.of(2026, 10, 12, 12, 0, 30);
    private static final LocalDateTime DESFAZER_3 = LocalDateTime.of(2026, 10, 12, 12, 1, 0);
    private static final LocalDateTime DESFAZER_4 = LocalDateTime.of(2026, 10, 12, 12, 1, 30);
    private static final LocalDate ONZE = LocalDate.of(2026, 10, 11);
    private static final LocalDate DOZE = LocalDate.of(2026, 10, 12);
    private static final LocalDateTime CADASTRO = LocalDateTime.of(2026, 10, 10, 9, 0, 0);

    // Do catálogo (03-plano.sql). O id é buscado pelo nome, nunca fixo.
    private static final String REMADA = "Remada cavalinho";
    // O que o modelo "devolveu" no campo exercicio: TR_SERIE não guarda este nome,
    // e o resumo tem que mostrar o do catálogo.
    private static final String NOME_DO_MODELO = "TESTE nome que o modelo devolveu";
    private static final String OBSERVACAO = "TESTE academia lotada";

    private static final int ORA_UNIQUE = 1;
    private static final int ORA_CHECK = 2290;
    private static final int ORA_COLUNA_INEXISTENTE = 904;
    private static final String CK_DESFEITA = "CK_TR_MENSAGEM_DESFEITA";
    private static final String UQ_DESFEITA_POR = "UQ_TR_MENSAGEM_DESFEITA_POR";

    // Faixa de teste: mesma lista e mesma ordem do CadastroSpike (filhos antes dos
    // pais, sem ON DELETE CASCADE): séries -> mensagens -> sessões -> apelidos ->
    // usuários.
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

    /** O que as duas colunas da 6c dizem de uma mensagem. VALIDA = as duas nulas. */
    private record Marca(LocalDateTime quando, Long porComando) {
    }

    private static final Marca VALIDA = new Marca(null, null);

    private static final String SQL_ID_EXERCICIO = "SELECT ID_EXERCICIO FROM TR_EXERCICIO WHERE NOME = ?";
    private static final String SQL_MARCA =
            "SELECT DATA_HORA_DESFEITA, DESFEITA_POR_ID_TELEGRAM FROM TR_MENSAGEM WHERE ID_MENSAGEM = ?";
    private static final String SQL_SESSAO_DA_MENSAGEM = "SELECT ID_SESSAO FROM TR_MENSAGEM WHERE ID_MENSAGEM = ?";
    private static final String SQL_OBSERVACAO = "SELECT OBSERVACAO FROM TR_SESSAO WHERE ID_SESSAO = ?";
    private static final String SQL_SERIES_DA_MENSAGEM = "SELECT COUNT(*) FROM TR_SERIE WHERE ID_MENSAGEM = ?";
    private static final String SQL_SESSOES_DO_USUARIO = "SELECT COUNT(*) FROM TR_SESSAO WHERE ID_USUARIO = ?";
    // A métrica do escopo ("extrações que precisaram de correção"), restrita a um
    // usuário ou à faixa de teste: no banco de verdade é só o WHERE da coluna.
    private static final String SQL_DESFEITAS_DO_USUARIO =
            "SELECT COUNT(*) FROM TR_MENSAGEM WHERE DATA_HORA_DESFEITA IS NOT NULL AND ID_USUARIO = ?";
    private static final String SQL_DESFEITAS_NA_FAIXA =
            "SELECT COUNT(*) FROM TR_MENSAGEM WHERE DATA_HORA_DESFEITA IS NOT NULL AND ID_USUARIO IN ("
                    + USUARIOS_DE_TESTE + ")";
    // O UPDATE "à mão" dos cenários da constraint e do índice (sem as guardas do DAO).
    private static final String SQL_UPDATE_DIRETO =
            "UPDATE TR_MENSAGEM SET DATA_HORA_DESFEITA = ?, DESFEITA_POR_ID_TELEGRAM = ? WHERE ID_MENSAGEM = ?";

    private static final UsuarioDAO usuarioDAO = new UsuarioDAO();
    private static final MensagemDAO mensagemDAO = new MensagemDAO();
    private static final SessaoDAO sessaoDAO = new SessaoDAO();
    private static final SerieDAO serieDAO = new SerieDAO();
    private static final CorrecaoService servico = new CorrecaoService();

    private static int ok = 0;
    private static int falhas = 0;

    public static void main(String[] args) {
        try (Connection conn = ConnectionFactory.getConnection()) {
            conn.setAutoCommit(false);

            System.out.println("=== Limpeza inicial (sobras de execução abortada) ===");
            limparFaixaDeTeste(conn);

            try {
                long idRemada = idExercicio(conn, REMADA);
                pilha(conn, idRemada);
                reentrega(conn);
                ordemPeloEnvio(conn);
                janela(conn);
                semSessao(conn);
                constraintEIndice(conn);
                outroUsuario(conn);
                soObservacao(conn);
                metricaNaFaixa(conn);
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
            // Mesmo formato dos outros spikes de banco.
            System.err.println("Erro no banco: " + e.getMessage());
            System.err.println("Código Oracle: " + e.getErrorCode());
            System.err.println("SQLState: " + e.getSQLState());
            if (e.getErrorCode() == ORA_COLUNA_INEXISTENTE) {
                System.err.println("ORA-00904: as colunas da 6c existem? Rode o db/05-desfazer.sql antes.");
            }
        }
    }

    // --- P. Pilha: duas mensagens registradas, três /desfazer --------------------------------

    private static void pilha(Connection conn, long idRemada) throws SQLException {
        System.out.println();
        System.out.println("=== P. Pilha: M1 (11/10) e M2 (12/10); /desfazer três vezes ===");
        long usuario = usuarioDeTeste(conn, ID_TELEGRAM_PILHA);
        // M1: ontem à noite, sessão SEM observação, 1 série de remada.
        long sessao1 = sessaoDAO.inserir(conn, usuario, ONZE, null);
        long m1 = mensagemComSessao(conn, usuario, 1L, LocalDateTime.of(2026, 10, 11, 20, 0, 0), sessao1);
        serieDAO.inserir(conn, m1, serie("cavalinho", REMADA, 1), idRemada);
        // M2: hoje cedo, sessão COM observação. As séries do exemplo da 6c: remada
        // (com o nome errado no campo do modelo) x2, puxada alta (fora do catálogo) x3.
        long sessao2 = sessaoDAO.inserir(conn, usuario, DOZE, OBSERVACAO);
        long m2 = mensagemComSessao(conn, usuario, 2L, LocalDateTime.of(2026, 10, 12, 8, 0, 0), sessao2);
        serieDAO.inserir(conn, m2, serie("cavalinho", NOME_DO_MODELO, 1), idRemada);
        serieDAO.inserir(conn, m2, serie("cavalinho", NOME_DO_MODELO, 2), idRemada);
        for (int numero = 1; numero <= 3; numero++) {
            serieDAO.inserir(conn, m2, serie("puxada alta", null, numero), null);
        }
        // Commit obrigatório: o service usa OUTRA conexão, e o que não foi comitado
        // é invisível para ela.
        conn.commit();

        // 1º /desfazer (comando 101): a mais nova.
        ResultadoDesfazer primeiro = servico.desfazerUltima(usuario, 101L, DESFAZER_1);
        System.out.println("        1º: " + primeiro);
        conferir("P1: DESFEITO", primeiro.situacao() == Situacao.DESFEITO);
        conferir("P1: marcou a MAIS NOVA (M2) com a data e o message_id do 1º /desfazer (comitado: lido pela "
                + "conexão do spike)", new Marca(DESFAZER_1, 101L).equals(marca(conn, m2)));
        conferir("P1: a anterior (M1) continua válida (as duas colunas nulas)", VALIDA.equals(marca(conn, m1)));
        conferir("P1: cabeçalho com a data da SESSÃO da mensagem desfeita (12/10)", DOZE.equals(primeiro.dataSessao()));
        SerieResumida remada = new SerieResumida(idRemada, REMADA, "cavalinho");
        SerieResumida puxada = new SerieResumida(null, null, "puxada alta");
        conferir("P1: séries na ordem de gravação, com o nome do CATÁLOGO (não o do modelo)",
                primeiro.series().equals(List.of(remada, remada, puxada, puxada, puxada)));
        conferir("P1: sessão com observação -> o aviso sai", primeiro.sessaoComObservacao());

        // 2º /desfazer (comando 102): a anterior, que ainda está nas 24 h.
        ResultadoDesfazer segundo = servico.desfazerUltima(usuario, 102L, DESFAZER_2);
        System.out.println("        2º: " + segundo);
        conferir("P2: DESFEITO", segundo.situacao() == Situacao.DESFEITO);
        conferir("P2: marcou a anterior (M1) com a data e o message_id do 2º /desfazer",
                new Marca(DESFAZER_2, 102L).equals(marca(conn, m1)));
        conferir("P2: M2 continua com a marca do 1º (não foi sobrescrita)",
                new Marca(DESFAZER_1, 101L).equals(marca(conn, m2)));
        conferir("P2: data da sessão de M1 (11/10), 1 série de remada, sessão sem observação",
                ONZE.equals(segundo.dataSessao()) && segundo.series().equals(List.of(remada))
                        && !segundo.sessaoComObservacao());

        // 3º /desfazer (comando 103): não sobrou nada.
        ResultadoDesfazer terceiro = servico.desfazerUltima(usuario, 103L, DESFAZER_3);
        System.out.println("        3º: " + terceiro);
        conferir("P3: NADA_PARA_DESFAZER", terceiro.situacao() == Situacao.NADA_PARA_DESFAZER);
        conferir("P3: as marcas das duas não mudaram", new Marca(DESFAZER_2, 102L).equals(marca(conn, m1))
                && new Marca(DESFAZER_1, 101L).equals(marca(conn, m2)));

        // Exclusão lógica: nada foi apagado nem mexido fora das colunas novas.
        conferir("P: séries continuam em TR_SERIE (M1: 1, M2: 5)",
                contar(conn, SQL_SERIES_DA_MENSAGEM, m1) == 1 && contar(conn, SQL_SERIES_DA_MENSAGEM, m2) == 5);
        conferir("P: as duas sessões continuam existindo (mesmo com todas as mensagens desfeitas)",
                contar(conn, SQL_SESSOES_DO_USUARIO, usuario) == 2);
        conferir("P: cada mensagem continua ligada à sua sessão",
                lerLong(conn, SQL_SESSAO_DA_MENSAGEM, m1) == sessao1
                        && lerLong(conn, SQL_SESSAO_DA_MENSAGEM, m2) == sessao2);
        conferir("P: observação da sessão intacta", OBSERVACAO.equals(observacao(conn, sessao2))
                && observacao(conn, sessao1) == null);
        conferir("P: métrica, COUNT(*) de desfeitas do usuário = 2",
                contar(conn, SQL_DESFEITAS_DO_USUARIO, usuario) == 2);

        // MensagemDAO.marcarDesfeita direto: 0 linhas não é exceção.
        conferir("P: marcarDesfeita numa mensagem já desfeita -> false",
                !mensagemDAO.marcarDesfeita(conn, m2, usuario, DESFAZER_3, 104L));
        conferir("P: ... e a marca do 1º /desfazer não foi sobrescrita",
                new Marca(DESFAZER_1, 101L).equals(marca(conn, m2)));
        conn.rollback();
    }

    // --- R. Reentrega do mesmo /desfazer (idempotência) -----------------------------------------

    private static void reentrega(Connection conn) throws SQLException {
        System.out.println();
        System.out.println("=== R. Reentrega: o mesmo /desfazer (mesmo message_id) duas vezes ===");
        long usuario = usuarioDeTeste(conn, ID_TELEGRAM_REENTREGA);
        long sessao = sessaoDAO.inserir(conn, usuario, DOZE, null);
        long m1 = mensagemComSessao(conn, usuario, 1L, LocalDateTime.of(2026, 10, 12, 9, 0, 0), sessao);
        long m2 = mensagemComSessao(conn, usuario, 2L, LocalDateTime.of(2026, 10, 12, 10, 0, 0), sessao);
        conn.commit();

        ResultadoDesfazer primeiro = servico.desfazerUltima(usuario, 201L, DESFAZER_1);
        conferir("R1: 1ª entrega do comando 201 -> DESFEITO, M2 marcada por 201",
                primeiro.situacao() == Situacao.DESFEITO && new Marca(DESFAZER_1, 201L).equals(marca(conn, m2)));

        // O Telegram entrega o MESMO update de novo: mesmo message_id, mesma data.
        ResultadoDesfazer reentregue = servico.desfazerUltima(usuario, 201L, DESFAZER_1);
        System.out.println("        reentrega: " + reentregue);
        conferir("R2: 2ª entrega do comando 201 -> JA_APLICADO", reentregue.situacao() == Situacao.JA_APLICADO);
        conferir("R2: nenhuma OUTRA mensagem foi desfeita (M1 continua válida)", VALIDA.equals(marca(conn, m1)));
        conferir("R2: a marca de M2 não mudou", new Marca(DESFAZER_1, 201L).equals(marca(conn, m2)));
        conferir("R2: 1 desfeita no usuário", contar(conn, SQL_DESFEITAS_DO_USUARIO, usuario) == 1);

        // Um /desfazer NOVO (outro message_id) continua funcionando.
        ResultadoDesfazer novo = servico.desfazerUltima(usuario, 202L, DESFAZER_2);
        conferir("R3: comando novo (202) -> DESFEITO, M1 marcada por 202",
                novo.situacao() == Situacao.DESFEITO && new Marca(DESFAZER_2, 202L).equals(marca(conn, m1)));
    }

    // --- E. Ordem do alvo: data de envio, desempate pelo id -----------------------------------------

    private static void ordemPeloEnvio(Connection conn) throws SQLException {
        System.out.println();
        System.out.println("=== E. Ordem: mais nova pela data de ENVIO; mesmo segundo -> maior id ===");
        long usuario = usuarioDeTeste(conn, ID_TELEGRAM_ORDEM);
        long sessao = sessaoDAO.inserir(conn, usuario, DOZE, null);
        // Gravadas nesta ordem DE PROPÓSITO: a de envio mais ANTIGO (mc) fica com o
        // MAIOR id, como poderia acontecer num RAC. Pelo id sozinho, ela seria a 1ª.
        long ma = mensagemComSessao(conn, usuario, 1L, LocalDateTime.of(2026, 10, 12, 11, 0, 0), sessao);
        long mb = mensagemComSessao(conn, usuario, 2L, LocalDateTime.of(2026, 10, 12, 11, 0, 0), sessao);
        long mc = mensagemComSessao(conn, usuario, 3L, LocalDateTime.of(2026, 10, 12, 10, 0, 0), sessao);
        conn.commit();

        servico.desfazerUltima(usuario, 301L, DESFAZER_1);
        conferir("E1: envio mais recente (11:00), empate no segundo -> a de maior id (mb)",
                new Marca(DESFAZER_1, 301L).equals(marca(conn, mb))
                        && VALIDA.equals(marca(conn, ma)) && VALIDA.equals(marca(conn, mc)));
        servico.desfazerUltima(usuario, 302L, DESFAZER_2);
        conferir("E2: depois, a outra das 11:00 (ma); mc (maior id, envio mais antigo) ainda não",
                new Marca(DESFAZER_2, 302L).equals(marca(conn, ma)) && VALIDA.equals(marca(conn, mc)));
        servico.desfazerUltima(usuario, 303L, DESFAZER_3);
        conferir("E3: por último, a das 10:00 (mc)", new Marca(DESFAZER_3, 303L).equals(marca(conn, mc)));
        conferir("E4: e então NADA_PARA_DESFAZER",
                servico.desfazerUltima(usuario, 304L, DESFAZER_4).situacao() == Situacao.NADA_PARA_DESFAZER);
    }

    // --- J. Janela de 24 h ------------------------------------------------------------------------

    private static void janela(Connection conn) throws SQLException {
        System.out.println();
        System.out.println("=== J. Janela: exatamente 24 h antes, 1 s a mais que 24 h, 1 s depois do /desfazer ===");
        long usuario = usuarioDeTeste(conn, ID_TELEGRAM_JANELA);
        long sessaoOnze = sessaoDAO.inserir(conn, usuario, ONZE, null);
        long sessaoDoze = sessaoDAO.inserir(conn, usuario, DOZE, null);
        long noLimite = mensagemComSessao(conn, usuario, 1L, DESFAZER_1.minusHours(24), sessaoOnze);
        long foraDaJanela = mensagemComSessao(conn, usuario, 2L, DESFAZER_1.minusHours(24).minusSeconds(1),
                sessaoOnze);
        // A de envio mais recente: seria a 1ª se a ponta de cima fosse ignorada.
        long depoisDoComando = mensagemComSessao(conn, usuario, 3L, DESFAZER_1.plusSeconds(1), sessaoDoze);
        conn.commit();

        ResultadoDesfazer primeiro = servico.desfazerUltima(usuario, 401L, DESFAZER_1);
        conferir("J1: enviada exatamente 24 h antes é alvo (ponta incluída)",
                primeiro.situacao() == Situacao.DESFEITO && new Marca(DESFAZER_1, 401L).equals(marca(conn, noLimite)));
        conferir("J1: enviada depois do /desfazer NÃO é alvo, mesmo sendo a mais recente",
                VALIDA.equals(marca(conn, depoisDoComando)));
        conferir("J1: enviada há mais de 24 h (24 h + 1 s) continua válida", VALIDA.equals(marca(conn, foraDaJanela)));

        // Se a janela fosse ignorada, a fora da janela seria o alvo agora.
        ResultadoDesfazer segundo = servico.desfazerUltima(usuario, 402L, DESFAZER_1);
        conferir("J2: de novo -> NADA_PARA_DESFAZER (as duas restantes estão fora da janela)",
                segundo.situacao() == Situacao.NADA_PARA_DESFAZER);
        conferir("J2: as duas fora da janela continuam válidas",
                VALIDA.equals(marca(conn, foraDaJanela)) && VALIDA.equals(marca(conn, depoisDoComando)));
    }

    // --- S. Mensagem sem sessão é pulada ------------------------------------------------------------

    private static void semSessao(Connection conn) throws SQLException {
        System.out.println();
        System.out.println("=== S. Mensagem sem sessão (SEM_SERIES / NAO_REGISTRADO) é pulada ===");
        long usuario = usuarioDeTeste(conn, ID_TELEGRAM_SEM_SESSAO);
        long sessao = sessaoDAO.inserir(conn, usuario, DOZE, null);
        long comSessao = mensagemComSessao(conn, usuario, 1L, LocalDateTime.of(2026, 10, 12, 9, 0, 0), sessao);
        // A mais nova, mas sem sessão: como um SEM_SERIES ou um NAO_REGISTRADO.
        long semSessao = mensagemSemSessao(conn, usuario, 2L, LocalDateTime.of(2026, 10, 12, 10, 0, 0));
        conn.commit();

        ResultadoDesfazer resultado = servico.desfazerUltima(usuario, 501L, DESFAZER_1);
        conferir("S: DESFEITO, e o alvo foi a anterior COM sessão", resultado.situacao() == Situacao.DESFEITO
                && new Marca(DESFAZER_1, 501L).equals(marca(conn, comSessao)));
        conferir("S: a mensagem sem sessão continua sem marca", VALIDA.equals(marca(conn, semSessao)));
        conferir("S: mensagem desfeita sem séries -> lista vazia", resultado.series().isEmpty());
    }

    // --- C. CK_TR_MENSAGEM_DESFEITA e UQ_TR_MENSAGEM_DESFEITA_POR, por UPDATE direto ----------------

    private static void constraintEIndice(Connection conn) throws SQLException {
        System.out.println();
        System.out.println("=== C. Constraint e índice: UPDATE direto, sem as guardas do DAO ===");
        long usuario = usuarioDeTeste(conn, ID_TELEGRAM_CONSTRAINT);
        long sessao = sessaoDAO.inserir(conn, usuario, DOZE, null);
        long comSessao1 = mensagemComSessao(conn, usuario, 1L, LocalDateTime.of(2026, 10, 12, 9, 0, 0), sessao);
        long comSessao2 = mensagemComSessao(conn, usuario, 2L, LocalDateTime.of(2026, 10, 12, 10, 0, 0), sessao);
        long semSessao = mensagemSemSessao(conn, usuario, 3L, LocalDateTime.of(2026, 10, 12, 11, 0, 0));
        conn.commit();

        // C1: as duas colunas preenchidas, mas a mensagem não tem sessão.
        SQLException semSessaoRecusa = updateDireto(conn, semSessao, DESFAZER_1, 901L);
        conferirRecusa("C1: sem sessão", semSessaoRecusa, ORA_CHECK, CK_DESFEITA);

        // C2: data sem DESFEITA_POR_ID_TELEGRAM (a reentrega não enxergaria).
        SQLException semComando = updateDireto(conn, comSessao1, DESFAZER_1, null);
        conferirRecusa("C2: data sem DESFEITA_POR_ID_TELEGRAM", semComando, ORA_CHECK, CK_DESFEITA);

        // C3: o outro lado: comando sem data.
        SQLException semData = updateDireto(conn, comSessao1, null, 901L);
        conferirRecusa("C3: DESFEITA_POR_ID_TELEGRAM sem data", semData, ORA_CHECK, CK_DESFEITA);

        // C4: o mesmo comando (901) desfazendo DUAS mensagens do mesmo usuário. A 1ª
        // passa (fica pendente na conexão do spike); a 2ª, o índice recusa.
        SQLException primeira = updateDireto(conn, comSessao1, DESFAZER_1, 901L);
        conferir("C4: 1ª mensagem desfeita pelo comando 901 -> aceita", primeira == null);
        SQLException segunda = updateDireto(conn, comSessao2, DESFAZER_1, 901L);
        conferirRecusa("C4: 2ª mensagem pelo MESMO comando", segunda, ORA_UNIQUE, UQ_DESFEITA_POR);
        conn.rollback();

        conferir("C: depois do rollback, as três continuam válidas", VALIDA.equals(marca(conn, comSessao1))
                && VALIDA.equals(marca(conn, comSessao2)) && VALIDA.equals(marca(conn, semSessao)));
    }

    // --- U. Mensagem de outro usuário nunca é alvo; o comando é por usuário ------------------------

    private static void outroUsuario(Connection conn) throws SQLException {
        System.out.println();
        System.out.println("=== U. Outro usuário: a mensagem dele nunca é alvo; o mesmo message_id é outro comando ===");
        long dono = usuarioDeTeste(conn, ID_TELEGRAM_DONO);
        long outro = usuarioDeTeste(conn, ID_TELEGRAM_OUTRO);
        long sessaoDono = sessaoDAO.inserir(conn, dono, DOZE, null);
        long sessaoOutro = sessaoDAO.inserir(conn, outro, DOZE, null);
        long doDono = mensagemComSessao(conn, dono, 1L, LocalDateTime.of(2026, 10, 12, 9, 0, 0), sessaoDono);
        // A do outro é a mais nova e está na janela.
        long doOutro = mensagemComSessao(conn, outro, 1L, LocalDateTime.of(2026, 10, 12, 10, 0, 0), sessaoOutro);
        conn.commit();

        ResultadoDesfazer primeiro = servico.desfazerUltima(dono, 601L, DESFAZER_1);
        conferir("U1: desfez a do PRÓPRIO usuário", primeiro.situacao() == Situacao.DESFEITO
                && new Marca(DESFAZER_1, 601L).equals(marca(conn, doDono)));
        conferir("U1: a do outro (mais nova) continua válida", VALIDA.equals(marca(conn, doOutro)));

        ResultadoDesfazer segundo = servico.desfazerUltima(dono, 602L, DESFAZER_2);
        conferir("U2: de novo -> NADA_PARA_DESFAZER (a do outro nunca entra)",
                segundo.situacao() == Situacao.NADA_PARA_DESFAZER && VALIDA.equals(marca(conn, doOutro)));

        // MensagemDAO.marcarDesfeita direto, com a mensagem do outro e o usuário
        // errado: o WHERE ID_USUARIO não acha a linha.
        conferir("U3: marcarDesfeita(mensagem do outro, usuário dono) -> false",
                !mensagemDAO.marcarDesfeita(conn, doOutro, dono, DESFAZER_3, 603L));
        conn.rollback();
        conferir("U3: ... e a do outro continua válida", VALIDA.equals(marca(conn, doOutro)));

        // O message_id só é único dentro do chat: o /desfazer 601 do OUTRO é outro
        // comando. Nem a checagem de reentrega nem o índice podem confundi-lo com o
        // 601 do dono.
        ResultadoDesfazer doMesmoNumero = servico.desfazerUltima(outro, 601L, DESFAZER_3);
        conferir("U4: o /desfazer 601 do outro -> DESFEITO (não JA_APLICADO), marcado por 601",
                doMesmoNumero.situacao() == Situacao.DESFEITO
                        && new Marca(DESFAZER_3, 601L).equals(marca(conn, doOutro)));
        conferir("U4: a do dono continua com a marca dela", new Marca(DESFAZER_1, 601L).equals(marca(conn, doDono)));
    }

    // --- B. Mensagem que só trouxe observação --------------------------------------------------------

    private static void soObservacao(Connection conn) throws SQLException {
        System.out.println();
        System.out.println("=== B. Mensagem só com observação (sessão sem séries) ===");
        long usuario = usuarioDeTeste(conn, ID_TELEGRAM_SO_OBSERVACAO);
        long sessao = sessaoDAO.inserir(conn, usuario, DOZE, OBSERVACAO);
        long mensagem = mensagemComSessao(conn, usuario, 1L, LocalDateTime.of(2026, 10, 12, 7, 0, 0), sessao);
        conn.commit();

        ResultadoDesfazer resultado = servico.desfazerUltima(usuario, 701L, DESFAZER_1);
        conferir("B: DESFEITO, sem séries, com o aviso da observação",
                resultado.situacao() == Situacao.DESFEITO && resultado.series().isEmpty()
                        && resultado.sessaoComObservacao() && DOZE.equals(resultado.dataSessao()));
        conferir("B: marcada", new Marca(DESFAZER_1, 701L).equals(marca(conn, mensagem)));
        conferir("B: a sessão continua, com a observação intacta",
                contar(conn, SQL_SESSOES_DO_USUARIO, usuario) == 1 && OBSERVACAO.equals(observacao(conn, sessao)));
    }

    // --- M. A métrica na faixa de teste ----------------------------------------------------------------

    private static void metricaNaFaixa(Connection conn) throws SQLException {
        System.out.println();
        System.out.println("=== M. Métrica: COUNT(*) de mensagens desfeitas na faixa de teste ===");
        // P: 2; R: 2; E: 3; J: 1; S: 1; C: 0 (rollback); U: 2 (dono 1, outro 1); B: 1.
        long desfeitas = contar(conn, SQL_DESFEITAS_NA_FAIXA, FAIXA_TESTE_INICIO, FAIXA_TESTE_FIM);
        conferir("M: 12 mensagens desfeitas na faixa (veio " + desfeitas + ")", desfeitas == 12);
    }

    // --- Montagem dos cenários (só DAOs da fatia 4) -----------------------------------------------

    // ATIVO, como quem manda /desfazer de verdade (o service não confere o status:
    // quem filtra é o loop).
    private static long usuarioDeTeste(Connection conn, long idTelegram) throws SQLException {
        long idUsuario = usuarioDAO.inserirPendente(conn, idTelegram, "Teste Desfazer", CADASTRO);
        usuarioDAO.atualizarStatus(conn, idUsuario, "ATIVO");
        return idUsuario;
    }

    // O que o RegistroService faz na T1: a mensagem crua, ainda sem sessão.
    private static long mensagemSemSessao(Connection conn, long idUsuario, long messageId, LocalDateTime envio)
            throws SQLException {
        ResultadoInsercao gravada = mensagemDAO.inserir(conn, idUsuario, messageId, envio, "TEXTO",
                "TESTE relato do DesfazerSpike");
        if (gravada.jaGravada()) {
            throw new IllegalStateException("Mensagem de teste já estava gravada: a limpeza inicial falhou.");
        }
        return gravada.idMensagem();
    }

    // O que o RegistroService faz na T2 com uma mensagem que gerou registro.
    private static long mensagemComSessao(Connection conn, long idUsuario, long messageId, LocalDateTime envio,
            long idSessao) throws SQLException {
        long idMensagem = mensagemSemSessao(conn, idUsuario, messageId, envio);
        mensagemDAO.vincularSessao(conn, idMensagem, idSessao, idUsuario);
        return idMensagem;
    }

    // Carga e repetições quaisquer: o resumo do /desfazer não as usa.
    private static SerieExtraida serie(String relatado, String nomeDoModelo, int numero) {
        return new SerieExtraida(relatado, nomeDoModelo, numero, 20.0, false, 10, false, null, null);
    }

    private static long idExercicio(Connection conn, String nome) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_ID_EXERCICIO)) {
            ps.setString(1, nome);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("Exercício do 03-plano.sql não encontrado no catálogo: " + nome);
                }
                return rs.getLong(1);
            }
        }
    }

    // UPDATE "à mão", na conexão do spike (não comita). null = coluna NULL.
    // @return a exceção do banco, ou null se o UPDATE passou
    private static SQLException updateDireto(Connection conn, long idMensagem, LocalDateTime quando,
            Long porComando) {
        try (PreparedStatement ps = conn.prepareStatement(SQL_UPDATE_DIRETO)) {
            if (quando == null) {
                ps.setNull(1, Types.DATE);
            } else {
                ps.setObject(1, quando);
            }
            if (porComando == null) {
                ps.setNull(2, Types.NUMERIC);
            } else {
                ps.setLong(2, porComando);
            }
            ps.setLong(3, idMensagem);
            ps.executeUpdate();
            return null;
        } catch (SQLException e) {
            return e;
        }
    }

    // Recusa esperada: o código ORA, o nome do objeto (só procurado na mensagem;
    // não é impresso, ela cita o esquema) e a CLASSE da exceção, que é por onde o
    // loop decide "recusa -> avança".
    private static void conferirRecusa(String cenario, SQLException recusa, int codigo, String objeto) {
        conferir(cenario + ": recusado com ORA-" + String.format("%05d", codigo),
                recusa != null && recusa.getErrorCode() == codigo);
        conferir(cenario + ": em " + objeto,
                recusa != null && recusa.getMessage() != null && recusa.getMessage().contains(objeto));
        conferir(cenario + ": chega como SQLIntegrityConstraintViolationException (o loop trata como recusa)",
                recusa instanceof SQLIntegrityConstraintViolationException);
    }

    // --- Preparação e limpeza (como no CadastroSpike) ---------------------------------------------

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

    // getObject devolve null para NULL: é assim que "válida" aparece (VALIDA).
    private static Marca marca(Connection conn, long idMensagem) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_MARCA)) {
            ps.setLong(1, idMensagem);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Mensagem de teste não encontrada.");
                }
                return new Marca(rs.getObject("DATA_HORA_DESFEITA", LocalDateTime.class),
                        rs.getObject("DESFEITA_POR_ID_TELEGRAM", Long.class));
            }
        }
    }

    private static String observacao(Connection conn, long idSessao) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_OBSERVACAO)) {
            ps.setLong(1, idSessao);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Sessão de teste não encontrada.");
                }
                return rs.getString(1);
            }
        }
    }

    // -1 = sem linha ou NULL.
    private static long lerLong(Connection conn, String sql, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return -1;
                }
                long valor = rs.getLong(1);
                return rs.wasNull() ? -1 : valor;
            }
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
