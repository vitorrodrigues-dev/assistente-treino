package dev.vitorrodrigues.treino.spike;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLRecoverableException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import dev.vitorrodrigues.treino.bot.ClienteTelegram;
import dev.vitorrodrigues.treino.bot.ErroTelegram;
import dev.vitorrodrigues.treino.bot.LoopTelegram;
import dev.vitorrodrigues.treino.bot.LoopTelegram.Encerramento;
import dev.vitorrodrigues.treino.bot.Textos;
import dev.vitorrodrigues.treino.dao.ConsultaDAO;
import dev.vitorrodrigues.treino.dao.ExercicioDAO;
import dev.vitorrodrigues.treino.dao.MensagemDAO;
import dev.vitorrodrigues.treino.dao.SerieDAO;
import dev.vitorrodrigues.treino.dao.SessaoDAO;
import dev.vitorrodrigues.treino.dao.UsuarioDAO;
import dev.vitorrodrigues.treino.extrator.Extrator;
import dev.vitorrodrigues.treino.extrator.RelatoExtraido;
import dev.vitorrodrigues.treino.extrator.SerieExtraida;
import dev.vitorrodrigues.treino.factory.ConnectionFactory;
import dev.vitorrodrigues.treino.modelo.ExercicioComApelidos;
import dev.vitorrodrigues.treino.modelo.ResultadoConsulta;
import dev.vitorrodrigues.treino.modelo.ResultadoInsercao;
import dev.vitorrodrigues.treino.modelo.SerieConsultada;
import dev.vitorrodrigues.treino.modelo.UltimoTreino;
import dev.vitorrodrigues.treino.modelo.Usuario;
import dev.vitorrodrigues.treino.service.CadastroService;
import dev.vitorrodrigues.treino.service.ConsultaService;
import dev.vitorrodrigues.treino.service.Consultor;
import dev.vitorrodrigues.treino.service.CorrecaoService;
import dev.vitorrodrigues.treino.service.RegistroService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Aceite da fatia 7 no banco real: o /carga pelo LoopTelegram inteiro, com o
 * ConsultaService, o ConsultaDAO e a BuscaDeExercicio de verdade (e o
 * CorrecaoService de verdade nos cenários com /desfazer). Falsos só no lugar do
 * Telegram, da espera, do relógio e do extrator.
 *
 * O extrator é um falso que só CONTA (e falha alto se chamado): o critério de
 * aceite exige zero chamadas. A API da Anthropic nunca é chamada: este spike não
 * cria um ExtratorCliente, e o ConsultaService nem recebe extrator.
 *
 * Mesmo esquema do DesfazerSpike: os services abrem e comitam as PRÓPRIAS conexões,
 * então o spike grava de verdade e APAGA depois, só a faixa de teste
 * (TR_USUARIO.ID_TELEGRAM 999000600 a 999000699, nunca o Vitor). A limpeza roda
 * também no INÍCIO, para tirar sobras de uma execução abortada, e no fim confere que
 * a faixa ficou com 0 linhas.
 *
 * Os treinos (sessões, mensagens, séries, apelidos) são montados direto pelos DAOs
 * da fatia 4, SEM o extrator. Só o /carga, o /desfazer e os filtros passam pelo
 * loop. Um usuário de teste por cenário que usa /desfazer (a pilha é por usuário).
 *
 * Datas fixas: os services usam a data de envio do update, nunca o relógio.
 *
 * Toda verificação lê o banco pela conexão do SPIKE (só enxerga o que os services
 * comitaram). Nada de nome, id ou texto de usuário é impresso; as respostas
 * impressas são dados de teste.
 *
 * Rodar (da raiz do projeto), DEPOIS do db/05-desfazer.sql:
 * ./mvnw -q compile exec:java "-Dexec.mainClass=dev.vitorrodrigues.treino.spike.ConsultaSpike"
 * Conferir a última linha: "RESULTADO: N ok, 0 falhas".
 */
public class ConsultaSpike {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Locale PT_BR = Locale.of("pt", "BR");

    private static final long FAIXA_TESTE_INICIO = 999000600L;
    private static final long FAIXA_TESTE_FIM = 999000699L;
    // Um usuário por papel (todos dentro da faixa).
    private static final long ID_TELEGRAM_A = 999000601L;          // critérios 1-5, 10-12
    private static final long ID_TELEGRAM_B = 999000602L;          // critério 9
    private static final long ID_TELEGRAM_PENDENTE = 999000603L;
    private static final long ID_TELEGRAM_BLOQUEADO = 999000604L;
    private static final long ID_TELEGRAM_DESCONHECIDO = 999000605L; // nunca cadastrado
    private static final long ID_TELEGRAM_DESFAZER = 999000606L;   // critério 6
    private static final long ID_TELEGRAM_DUAS = 999000607L;       // critério 7
    private static final long ID_TELEGRAM_FORMATO = 999000608L;    // critério 8
    private static final long ID_TELEGRAM_REENTREGA = 999000609L;  // critério 10
    private static final long ID_TELEGRAM_FALSO = 999000610L;      // ERRO e T1, com o Consultor falso
    private static final long ID_TELEGRAM_ORDEM = 999000611L;      // ajustes: ordem e pontuação
    // O loop precisa de um administrador; nenhum cenário daqui usa /aprovar. Não existe.
    private static final long ID_ADMIN_DO_LOOP = 999000699L;
    private static final long ID_GRUPO = -1009990006L;

    private static final String NOME_A = "Teste Consulta Fulano";
    private static final String NOME_B = "Teste Consulta Beltrano";
    private static final String NOME_PENDENTE = "Teste Consulta Pendente";
    private static final String NOME_BLOQUEADO = "Teste Consulta Bloqueado";
    private static final String NOME_DESCONHECIDO = "Teste Consulta Desconhecido";
    private static final String NOME_OUTROS = "Teste Consulta Sicrano";

    // Do catálogo (03-plano.sql). O id é buscado pelo nome, nunca fixo.
    private static final String REMADA_UNILATERAL = "Remada unilateral máquina";   // POR_LADO
    private static final String REMADA_CAVALINHO = "Remada cavalinho";             // TOTAL
    private static final String REMADA_CURVADA = "Remada curvada barra";
    private static final String PUXADA_ROMANA_MEDIA = "Puxada romana média";
    private static final String PUXADA_ABERTA = "Puxada aberta";
    private static final String PUXADA_ROMANA_ABERTA = "Puxada romana aberta";
    private static final String AGACHAMENTO_HACK = "Agachamento hack";
    private static final String STIFF = "Stiff";
    private static final String BARRA_FIXA = "Barra fixa";

    private static final LocalDate D10 = LocalDate.of(2026, 10, 10);
    private static final LocalDate D13 = LocalDate.of(2026, 10, 13);
    private static final LocalDate D14 = LocalDate.of(2026, 10, 14);
    private static final LocalDate D15 = LocalDate.of(2026, 10, 15);
    private static final LocalDate D16 = LocalDate.of(2026, 10, 16);
    private static final LocalDate D17 = LocalDate.of(2026, 10, 17);
    private static final LocalDateTime CADASTRO = LocalDateTime.of(2026, 10, 9, 9, 0, 0);
    // Relógio parado: a linha de log sai sempre igual ("18/10 09:00:00 ... 0,0 s").
    private static final Instant AGORA = Instant.parse("2026-10-18T12:00:00Z");
    private static final String HORA_NO_LOG = "18/10 09:00:00";

    private static final String OBSERVACAO = "TESTE observação do dia: academia lotada";
    private static final String PERCEPCAO = "TESTE percepção: pesado demais";

    // 6 * 15 + 14 espaços = 104 caracteres, só a palavra "remada". Sem o limite de
    // 100, as palavras dariam as três remadas (AMBIGUO), não "não encontrei".
    private static final String ARGUMENTO_LONGO = "remada ".repeat(15).strip();

    // Na lista de situações esperadas do usuário principal, marca o update que não é
    // /carga (o /xyz). Constante com nome, e não null: List.of recusa null
    // (NullPointerException na hora de montar a lista).
    private static final String NAO_E_CONSULTA = "não é consulta";

    // ORA-00904 (coluna inexistente): o db/05-desfazer.sql não rodou.
    private static final int ORA_COLUNA_INEXISTENTE = 904;

    private static final String USUARIOS_DE_TESTE =
            "SELECT ID_USUARIO FROM TR_USUARIO WHERE ID_TELEGRAM BETWEEN ? AND ?";

    private record TabelaDeTeste(String nome, String filtro) {
    }

    // Mesma lista e mesma ordem do DesfazerSpike (filhos antes dos pais, sem ON
    // DELETE CASCADE). A mensagem do A ligada à sessão do B (critério 9) sai junto:
    // as duas são da faixa, e TR_MENSAGEM é apagada antes de TR_SESSAO.
    private static final List<TabelaDeTeste> TABELAS_DE_TESTE = List.of(
            new TabelaDeTeste("TR_SERIE",
                    "ID_MENSAGEM IN (SELECT ID_MENSAGEM FROM TR_MENSAGEM WHERE ID_USUARIO IN ("
                            + USUARIOS_DE_TESTE + "))"),
            new TabelaDeTeste("TR_MENSAGEM", "ID_USUARIO IN (" + USUARIOS_DE_TESTE + ")"),
            new TabelaDeTeste("TR_SESSAO", "ID_USUARIO IN (" + USUARIOS_DE_TESTE + ")"),
            new TabelaDeTeste("TR_APELIDO", "ID_USUARIO IN (" + USUARIOS_DE_TESTE + ")"),
            new TabelaDeTeste("TR_USUARIO", "ID_TELEGRAM BETWEEN ? AND ?"));

    private static final String SQL_ID_EXERCICIO = "SELECT ID_EXERCICIO FROM TR_EXERCICIO WHERE NOME = ?";
    private static final String SQL_INSERIR_APELIDO =
            "INSERT INTO TR_APELIDO (APELIDO, ID_EXERCICIO, ID_USUARIO) VALUES (?, ?, ?)";
    // O UPDATE que o MensagemDAO.vincularSessao recusaria (sessão de outro usuário):
    // é a "lacuna conhecida" do banco, montada à mão.
    private static final String SQL_LIGAR_A_SESSAO_DE_OUTRO = "UPDATE TR_MENSAGEM SET ID_SESSAO = ? WHERE ID_MENSAGEM = ?";
    private static final String SQL_MENSAGEM = """
            SELECT ID_SESSAO, DATA_HORA_DESFEITA, ORIGEM, DATA_HORA_ENVIO, TEXTO_BRUTO
              FROM TR_MENSAGEM
             WHERE ID_USUARIO = ?
               AND ID_TELEGRAM = ?
            """;
    private static final String SQL_MARCA =
            "SELECT DATA_HORA_DESFEITA, DESFEITA_POR_ID_TELEGRAM FROM TR_MENSAGEM WHERE ID_MENSAGEM = ?";
    private static final String SQL_SERIE_GRAVADA = """
            SELECT CARGA_KG, CARGA_APROX, REPETICOES, REPETICOES_APROX
              FROM TR_SERIE
             WHERE ID_MENSAGEM = ?
               AND NUMERO_SERIE = ?
            """;
    private static final String SQL_MENSAGENS_DO_USUARIO = "SELECT COUNT(*) FROM TR_MENSAGEM WHERE ID_USUARIO = ?";
    private static final String SQL_MENSAGENS_SEM_SESSAO =
            "SELECT COUNT(*) FROM TR_MENSAGEM WHERE ID_USUARIO = ? AND ID_SESSAO IS NULL";

    private static final UsuarioDAO usuarioDAO = new UsuarioDAO();
    private static final MensagemDAO mensagemDAO = new MensagemDAO();
    private static final SessaoDAO sessaoDAO = new SessaoDAO();
    private static final SerieDAO serieDAO = new SerieDAO();
    private static final ConsultaDAO consultaDAO = new ConsultaDAO();

    // As peças de verdade que o loop recebe. O RegistroService leva o extrator que
    // só conta: se um /carga chegasse ao registro, o contador mostraria.
    private static final ExtratorContador EXTRATOR = new ExtratorContador();
    private static final RegistroService REGISTRO = new RegistroService(EXTRATOR);
    private static final CadastroService CADASTRO_SERVICE = new CadastroService();
    private static final CorrecaoService CORRECAO = new CorrecaoService();
    private static final ConsultorContador CONSULTA = new ConsultorContador(new ConsultaService());

    // Tudo o que nunca pode aparecer no log: textos enviados, nomes, nomes de
    // exercício e apelidos (via segredo()); ids numéricos à parte (idsProibidos).
    private static final List<String> PROIBIDOS_NO_LOG = new ArrayList<>(List.of(
            NOME_A, NOME_B, NOME_PENDENTE, NOME_BLOQUEADO, NOME_DESCONHECIDO, NOME_OUTROS, OBSERVACAO, PERCEPCAO));
    private static final List<Long> IDS_PROIBIDOS_NO_LOG = new ArrayList<>(List.of(
            ID_TELEGRAM_A, ID_TELEGRAM_B, ID_TELEGRAM_PENDENTE, ID_TELEGRAM_BLOQUEADO, ID_TELEGRAM_DESCONHECIDO,
            ID_TELEGRAM_DESFAZER, ID_TELEGRAM_DUAS, ID_TELEGRAM_FORMATO, ID_TELEGRAM_REENTREGA, ID_TELEGRAM_FALSO,
            ID_TELEGRAM_ORDEM));
    private static final List<String> LOG_GERAL = new ArrayList<>();

    // ID_USUARIO de cada usuário de teste (gerados pelo banco).
    private static long usuarioA;
    private static long usuarioB;
    private static long usuarioPendente;
    private static long usuarioBloqueado;
    private static long usuarioDesfazer;
    private static long usuarioDuas;
    private static long usuarioFormato;
    private static long usuarioReentrega;
    private static long usuarioFalso;
    private static long usuarioOrdem;

    private static int ok = 0;
    private static int falhas = 0;

    // A linha RESULTADO sai SEMPRE, também quando uma exceção interrompe um cenário:
    // a exceção conta como falha, os cenários seguintes não rodam, a limpeza final
    // roda (finally) e o processo termina com saída 1 se houver qualquer falha.
    public static void main(String[] args) {
        try (Connection conn = ConnectionFactory.getConnection()) {
            conn.setAutoCommit(false);

            System.out.println("=== Limpeza inicial (sobras de execução abortada) ===");
            limparFaixaDeTeste(conn);

            try {
                criarUsuarios(conn);
                proibirNomesDoCatalogo();

                usuarioPrincipal(conn);           // critérios 1, 2, 3, 4, 5, 10, 12
                outroUsuario(conn);               // critério 9
                statusDoRemetente(conn);          // filtros e política por status
                desfazerDevolveOAnterior(conn);   // critério 6
                duasMensagensNaSessao(conn);      // critério 7
                ordemDasSeries(conn);             // ajustes da 7 (ordem e pontuação)
                formatoDaResposta(conn);          // critério 8
                reentrega(conn);                  // critério 10
                errosNoBanco();                   // regra 2 (T1 e depois da T1)
                higieneDoLog();                   // critério 11
                semExtrator();                    // critério 10
            } catch (SQLException | InterruptedException | RuntimeException e) {
                // Um cenário quebrou no meio. InterruptedException não restaura a flag
                // de interrupção: a thread é a do main, que termina logo depois, e a
                // limpeza abaixo precisa rodar.
                falhaPorExcecao("cenário interrompido", e);
            } finally {
                // Roda com sucesso ou com exceção no meio dos cenários.
                System.out.println();
                System.out.println("=== Limpeza final ===");
                limparFaixaDeTeste(conn);
                conferirFaixaVazia(conn);
            }
        } catch (SQLException | RuntimeException e) {
            // Conexão, limpeza inicial ou limpeza final.
            falhaPorExcecao("banco (conexão ou limpeza)", e);
        }

        System.out.println();
        System.out.println("RESULTADO: " + ok + " ok, " + falhas + " falhas");
        if (falhas > 0) {
            System.exit(1);
        }
    }

    // Exceção que interrompeu o spike: conta como UMA falha. Imprime só a classe, o
    // código ORA e as linhas do código do projeto por onde ela passou; nunca a
    // mensagem, que pode ter id (a IllegalStateException do vincularSessao tem o
    // ID_USUARIO) ou o esquema do banco (a do Oracle).
    private static void falhaPorExcecao(String onde, Exception e) {
        falhas++;
        String codigo = e instanceof SQLException sql ? " (" + codigoOra(sql) + ")" : "";
        System.out.println("[FALHA] " + onde + ": " + e.getClass().getSimpleName() + codigo);
        for (StackTraceElement linha : e.getStackTrace()) {
            if (linha.getClassName().startsWith("dev.vitorrodrigues.treino.")) {
                System.out.println("        em " + linha);
            }
        }
        if (e instanceof SQLException sql && sql.getErrorCode() == ORA_COLUNA_INEXISTENTE) {
            System.out.println("        ORA-00904: as colunas da 6c existem? Rode o db/05-desfazer.sql antes.");
        }
    }

    private static String codigoOra(SQLException e) {
        return e.getErrorCode() > 0 ? String.format("ORA-%05d", e.getErrorCode()) : "sem código ORA";
    }

    // --- Usuários -----------------------------------------------------------------------------

    private static void criarUsuarios(Connection conn) throws SQLException {
        usuarioA = usuarioDeTeste(conn, ID_TELEGRAM_A, NOME_A, "ATIVO");
        usuarioB = usuarioDeTeste(conn, ID_TELEGRAM_B, NOME_B, "ATIVO");
        usuarioPendente = usuarioDeTeste(conn, ID_TELEGRAM_PENDENTE, NOME_PENDENTE, "PENDENTE");
        usuarioBloqueado = usuarioDeTeste(conn, ID_TELEGRAM_BLOQUEADO, NOME_BLOQUEADO, "BLOQUEADO");
        usuarioDesfazer = usuarioDeTeste(conn, ID_TELEGRAM_DESFAZER, NOME_OUTROS, "ATIVO");
        usuarioDuas = usuarioDeTeste(conn, ID_TELEGRAM_DUAS, NOME_OUTROS, "ATIVO");
        usuarioFormato = usuarioDeTeste(conn, ID_TELEGRAM_FORMATO, NOME_OUTROS, "ATIVO");
        usuarioReentrega = usuarioDeTeste(conn, ID_TELEGRAM_REENTREGA, NOME_OUTROS, "ATIVO");
        usuarioFalso = usuarioDeTeste(conn, ID_TELEGRAM_FALSO, NOME_OUTROS, "ATIVO");
        usuarioOrdem = usuarioDeTeste(conn, ID_TELEGRAM_ORDEM, NOME_OUTROS, "ATIVO");
        IDS_PROIBIDOS_NO_LOG.addAll(List.of(usuarioA, usuarioB, usuarioPendente, usuarioBloqueado, usuarioDesfazer,
                usuarioDuas, usuarioFormato, usuarioReentrega, usuarioFalso, usuarioOrdem));
        // Commit obrigatório: os services e a busca do remetente usam OUTRAS conexões.
        conn.commit();
    }

    // Nenhum nome de exercício pode ir para o log: os 46 do catálogo de verdade.
    private static void proibirNomesDoCatalogo() throws SQLException {
        for (ExercicioComApelidos exercicio : new ExercicioDAO().listarComApelidos(usuarioA)) {
            segredo(exercicio.nome());
        }
    }

    // --- Critérios 1, 2, 3, 4, 5, 10 e 12: o usuário principal --------------------------------

    private static void usuarioPrincipal(Connection conn) throws SQLException, InterruptedException {
        titulo("Usuário principal: resolução do nome, respostas, gravação crua (critérios 1-5, 10, 12)");
        long idRemada = idExercicio(conn, REMADA_UNILATERAL);
        long idCavalinho = idExercicio(conn, REMADA_CAVALINHO);
        long idRomanaMedia = idExercicio(conn, PUXADA_ROMANA_MEDIA);
        long idPuxadaAberta = idExercicio(conn, PUXADA_ABERTA);
        long idRomanaAberta = idExercicio(conn, PUXADA_ROMANA_ABERTA);

        // Apelidos do A, como os do Vitor no 04-usuario.sql.
        apelido(conn, segredo("cavalinho"), idCavalinho, usuarioA);
        apelido(conn, segredo("hack"), idExercicio(conn, AGACHAMENTO_HACK), usuarioA);

        // Treino antigo (10/10): remada unilateral, valores que NÃO podem aparecer.
        long sessao10 = sessaoDAO.inserir(conn, usuarioA, D10, null);
        long m10 = mensagemComSessao(conn, usuarioA, 1L, D10.atTime(19, 0), sessao10);
        serieDAO.inserir(conn, m10, serie(1, 35.0, false, 10, false, null), idRemada);
        serieDAO.inserir(conn, m10, serie(2, 35.0, false, 9, false, null), idRemada);

        // Último treino (14/10), com observação na sessão.
        long sessao14 = sessaoDAO.inserir(conn, usuarioA, D14, OBSERVACAO);
        long m14 = mensagemComSessao(conn, usuarioA, 2L, D14.atTime(19, 0), sessao14);
        serieDAO.inserir(conn, m14, serie(1, 40.0, false, 10, false, null), idRemada);
        serieDAO.inserir(conn, m14, serie(2, 42.5, false, 8, false, null), idRemada);
        serieDAO.inserir(conn, m14, serie(3, 42.5, false, 7, true, true), idRemada);
        serieDAO.inserir(conn, m14, serie(1, 50.0, false, 12, false, null), idPuxadaAberta);
        serieDAO.inserir(conn, m14, serie(1, 45.0, false, 10, false, null), idRomanaAberta);
        serieDAO.inserir(conn, m14, serie(1, 60.0, false, 10, false, null), idCavalinho);
        serieDAO.inserir(conn, m14, serie(1, 55.0, false, 10, false, null), idRomanaMedia);
        // Exercício não reconhecido (ID_EXERCICIO NULL), relatado como "remada
        // unilateral": nunca entra na consulta.
        serieDAO.inserir(conn, m14, serie(4, 999.0, false, 1, false, null), null);
        conn.commit();

        Cenario c = new Cenario(CONSULTA);
        LocalDateTime hora = LocalDateTime.of(2026, 10, 18, 8, 0, 0);
        List<String> textos = List.of(
                "/carga remada unilateral máquina",              // 71001: critério 1
                "/carga Cavalinho",                              // 71002: critério 2 (apelido, maiúscula)
                "/carga puxada romana media",                    // 71003: critério 2 (sem o acento)
                "/carga puxada aberta",                          // 71004: critério 3 (igualdade)
                "/carga hack",                                   // 71005: critério 3 (apelido)
                "/carga remada",                                 // 71006: critério 4 (ambíguo)
                "/carga supino declinado",                       // 71007: critério 5 (não encontrei)
                "/carga stiff",                                  // 71008: critério 5 (sem registro)
                "/carga",                                        // 71009: critério 5 (ajuda)
                "/carga    ",                                    // 71010: só espaços -> ajuda
                "/carga " + ARGUMENTO_LONGO,                     // 71011: mais de 100 -> não encontrei
                "/carga@TreinoBot remada unilateral máquina",    // 71012: critério 12
                "/carga   REMADA    unilateral   MAQUINA  ",     // 71013: espaços, caixa, acento
                "/carga máquina remada unilateral",              // 71014: ordem das palavras
                "/xyz",                                          // 71015: critério 12
                "/carga@TreinoBot");                             // 71016: ajuda
        for (int i = 0; i < textos.size(); i++) {
            c.telegram.updates.add(texto(71001 + i, ID_TELEGRAM_A, NOME_A, textos.get(i), hora.plusMinutes(i)));
        }
        int chamadasAntes = CONSULTA.chamadas;
        conferir("termina sem encerrar o bot", c.rodarAteEsvaziar() == null);
        conferir("offset avançou depois de todos (71017)", c.loop.offset() == 71017);
        conferir("16 respostas, todas no chat do A",
                c.telegram.enviadas.size() == 16 && c.telegram.destinos.stream().allMatch(d -> d == ID_TELEGRAM_A));

        String remada = resposta(cabecalho(REMADA_UNILATERAL, Textos.CARGA_POR_LADO, D14),
                "1ª: 40 kg × 10", "2ª: 42,5 kg × 8", "3ª: 42,5 kg × ~7 " + Textos.FALHA);
        System.out.println("        resposta do critério 1:");
        System.out.println(recuar(c.resposta(0)));
        conferir("1: /carga remada unilateral máquina -> data, forma da carga e as 3 séries do último treino (14/10)",
                remada.equals(c.resposta(0)));
        conferir("1: nada do treino antigo (35 kg) nem da série sem exercício (999 kg)",
                !c.resposta(0).contains("35 kg") && !c.resposta(0).contains("999"));
        conferir("2: /carga Cavalinho (apelido, maiúscula) -> nome oficial Remada cavalinho",
                resposta(cabecalho(REMADA_CAVALINHO, Textos.CARGA_TOTAL, D14), "1ª: 60 kg × 10").equals(c.resposta(1)));
        conferir("2: /carga puxada romana media (sem o acento) -> nome oficial Puxada romana média",
                resposta(cabecalho(PUXADA_ROMANA_MEDIA, Textos.CARGA_TOTAL, D14), "1ª: 55 kg × 10")
                        .equals(c.resposta(2)));
        conferir("3: /carga puxada aberta -> Puxada aberta por igualdade (50 kg), sem lista e sem a romana (45 kg)",
                resposta(cabecalho(PUXADA_ABERTA, Textos.CARGA_TOTAL, D14), "1ª: 50 kg × 12").equals(c.resposta(3)));
        conferir("3: /carga hack -> apelido do A: Agachamento hack (sem registro dele)",
                Textos.CARGA_SEM_REGISTRO.formatted(AGACHAMENTO_HACK).equals(c.resposta(4)));
        conferirAmbiguo("4: /carga remada -> lista as três remadas e não escolhe", c.resposta(5),
                Set.of(REMADA_CAVALINHO, REMADA_CURVADA, REMADA_UNILATERAL));
        conferir("5: /carga supino declinado -> 'não encontrei'", Textos.CARGA_NAO_ENCONTREI.equals(c.resposta(6)));
        conferir("5: /carga stiff -> 'sem registro' com o nome oficial",
                Textos.CARGA_SEM_REGISTRO.formatted(STIFF).equals(c.resposta(7)));
        conferir("5: /carga sem argumento -> ajuda, com exemplo",
                Textos.CARGA_AJUDA.equals(c.resposta(8)) && Textos.CARGA_AJUDA.contains("Exemplo: /carga "));
        conferir("/carga só com espaços -> ajuda", Textos.CARGA_AJUDA.equals(c.resposta(9)));
        conferir("argumento com mais de 100 caracteres (só 'remada') -> 'não encontrei', não a lista das remadas",
                ARGUMENTO_LONGO.length() > 100 && Textos.CARGA_NAO_ENCONTREI.equals(c.resposta(10)));
        conferir("12: /carga@TreinoBot remada unilateral máquina -> mesma resposta do critério 1",
                remada.equals(c.resposta(11)));
        conferir("espaços repetidos, MAIÚSCULAS e sem acento -> mesma resposta do critério 1",
                remada.equals(c.resposta(12)));
        conferir("palavras em outra ordem (máquina remada unilateral) -> mesma resposta do critério 1",
                remada.equals(c.resposta(13)));
        conferir("12: /xyz continua 'comando não reconhecido'", Textos.COMANDO_NAO_RECONHECIDO.equals(c.resposta(14)));
        conferir("12: /carga@TreinoBot sem argumento -> ajuda", Textos.CARGA_AJUDA.equals(c.resposta(15)));
        conferir("o consultor foi chamado nos 15 /carga, não no /xyz", CONSULTA.chamadas - chamadasAntes == 15);

        // Critério 10: cada /carga gravado cru, sem sessão; o /xyz não.
        boolean todasCruas = true;
        for (int i = 0; i < textos.size(); i++) {
            if (i == 14) {
                continue;
            }
            Optional<MensagemGravada> gravada = mensagem(conn, usuarioA, 76001 + i);
            todasCruas &= gravada.isPresent() && gravada.get().idSessao() == null
                    && gravada.get().desfeita() == null && "TEXTO".equals(gravada.get().origem())
                    && hora.plusMinutes(i).equals(gravada.get().envio()) && textos.get(i).equals(gravada.get().texto());
        }
        conferir("10: os 15 /carga estão em TR_MENSAGEM: texto inteiro, ORIGEM TEXTO, data de envio, ID_SESSAO NULL",
                todasCruas);
        conferir("10: mensagens do A sem sessão = exatamente os 15 /carga",
                contar(conn, SQL_MENSAGENS_SEM_SESSAO, usuarioA) == 15);
        conferir("12: /xyz não foi gravado", mensagem(conn, usuarioA, 76015).isEmpty());

        // Critério 11: uma linha por update, só com tipo e situação.
        List<String> situacoes = List.of("ENCONTRADO", "ENCONTRADO", "ENCONTRADO", "ENCONTRADO", "SEM_REGISTRO",
                "AMBIGUO", "NAO_ENCONTRADO", "SEM_REGISTRO", "AJUDA", "AJUDA", "NAO_ENCONTRADO", "ENCONTRADO",
                "ENCONTRADO", "ENCONTRADO", NAO_E_CONSULTA, "AJUDA");
        boolean linhasCertas = true;
        for (int i = 0; i < situacoes.size(); i++) {
            String esperada = NAO_E_CONSULTA.equals(situacoes.get(i))
                    ? linhaDeLog(71001 + i, "comando", "ignorado: comando não reconhecido")
                    : linhaDeLog(71001 + i, "consulta", situacoes.get(i));
            linhasCertas &= c.linhasDoUpdate(71001 + i).equals(List.of(esperada));
        }
        conferir("11: exatamente uma linha por update, e ela é só '<hora> | update N | consulta | SITUAÇÃO | 0,0 s'",
                linhasCertas);
        System.out.println("        exemplo: " + linhaDeLog(71001, "consulta", "ENCONTRADO"));

        // O mesmo pelo DAO, sem o loop: a data e as séries do último treino.
        Optional<UltimoTreino> treino = consultaDAO.buscarUltimoTreino(conn, usuarioA, idRemada);
        conferir("DAO: último treino do A na remada unilateral = 14/10, 3 séries, números 1, 2, 3",
                treino.isPresent() && D14.equals(treino.get().dataSessao())
                        && treino.get().series().stream().map(SerieConsultada::numeroSerie).toList()
                                .equals(List.of(1, 2, 3)));
        conferir("DAO: exercício sem série do usuário -> vazio",
                consultaDAO.buscarUltimoTreino(conn, usuarioA, idExercicio(conn, STIFF)).isEmpty());
    }

    // --- Critério 9: outro usuário, mesma remada; a sessão de outro não vaza ------------------

    private static void outroUsuario(Connection conn) throws SQLException, InterruptedException {
        titulo("Outro usuário: o B não vê o A, o A não vê o B, nem pela mensagem ligada à sessão do outro (9)");
        long idRemada = idExercicio(conn, REMADA_UNILATERAL);

        // O B treinou a mesma remada DEPOIS do A (16/10): se o filtro por usuário
        // falhasse, o A veria o 16/10.
        long sessaoB16 = sessaoDAO.inserir(conn, usuarioB, D16, null);
        long b16 = mensagemComSessao(conn, usuarioB, 1L, D16.atTime(19, 0), sessaoB16);
        serieDAO.inserir(conn, b16, serie(1, 70.0, false, 6, false, null), idRemada);
        serieDAO.inserir(conn, b16, serie(2, 70.0, false, 5, false, null), idRemada);
        // Sessão do B no dia 17, com uma mensagem dele (barra fixa)...
        long sessaoB17 = sessaoDAO.inserir(conn, usuarioB, D17, null);
        long b17 = mensagemComSessao(conn, usuarioB, 2L, D17.atTime(18, 0), sessaoB17);
        serieDAO.inserir(conn, b17, serie(1, 0.0, false, 10, false, null), idExercicio(conn, BARRA_FIXA));
        // ... e uma mensagem do A apontando para ESSA sessão do B, com 99 kg de remada.
        // O banco aceita (lacuna conhecida); o vincularSessao recusaria, então é à mão.
        long doANaSessaoDoB = mensagemSemSessao(conn, usuarioA, 3L, D17.atTime(19, 0));
        ligarASessaoDeOutro(conn, doANaSessaoDoB, sessaoB17);
        serieDAO.inserir(conn, doANaSessaoDoB, serie(1, 99.0, false, 1, false, null), idRemada);
        // Apelido do B: "hack" é OUTRO exercício para ele (o apelido é por usuário).
        apelido(conn, "hack", idExercicio(conn, STIFF), usuarioB);
        conn.commit();

        // O A, de novo, depois de tudo isso.
        Cenario doA = new Cenario(CONSULTA);
        doA.telegram.updates.add(texto(72001, ID_TELEGRAM_A, NOME_A, "/carga remada unilateral máquina",
                LocalDateTime.of(2026, 10, 18, 9, 0)));
        doA.rodarAteEsvaziar();
        String doAEsperada = resposta(cabecalho(REMADA_UNILATERAL, Textos.CARGA_POR_LADO, D14),
                "1ª: 40 kg × 10", "2ª: 42,5 kg × 8", "3ª: 42,5 kg × ~7 " + Textos.FALHA);
        conferir("9: o A continua vendo o 14/10 dele (nem o 16/10 do B, nem a sessão 17/10 do B com a mensagem dele)",
                doAEsperada.equals(doA.resposta(0)));

        Cenario doB = new Cenario(CONSULTA);
        doB.telegram.updates.addAll(List.of(
                texto(72011, ID_TELEGRAM_B, NOME_B, "/carga remada unilateral máquina",
                        LocalDateTime.of(2026, 10, 18, 9, 10)),
                texto(72012, ID_TELEGRAM_B, NOME_B, "/carga hack", LocalDateTime.of(2026, 10, 18, 9, 11)),
                texto(72013, ID_TELEGRAM_B, NOME_B, "/carga remada cavalinho", LocalDateTime.of(2026, 10, 18, 9, 12))));
        doB.rodarAteEsvaziar();
        conferir("9: o B vê o 16/10 dele (70 kg); nada do A (40 kg) nem os 99 kg da mensagem do A na sessão dele",
                resposta(cabecalho(REMADA_UNILATERAL, Textos.CARGA_POR_LADO, D16), "1ª: 70 kg × 6", "2ª: 70 kg × 5")
                        .equals(doB.resposta(0)));
        conferir("9: as respostas do B foram para o chat do B",
                doB.telegram.destinos.size() == 3 && doB.telegram.destinos.stream().allMatch(d -> d == ID_TELEGRAM_B));
        conferir("apelido é do usuário: /carga hack do B -> Stiff (o apelido dele), não Agachamento hack",
                Textos.CARGA_SEM_REGISTRO.formatted(STIFF).equals(doB.resposta(1)));
        conferir("9: /carga remada cavalinho do B -> 'sem registro' (só o A treinou)",
                Textos.CARGA_SEM_REGISTRO.formatted(REMADA_CAVALINHO).equals(doB.resposta(2)));

        Optional<UltimoTreino> doBNoDao = consultaDAO.buscarUltimoTreino(conn, usuarioB, idRemada);
        conferir("9 (DAO): o B na remada -> 16/10, não 17/10 (a sessão dele só tem remada da mensagem do A)",
                doBNoDao.isPresent() && D16.equals(doBNoDao.get().dataSessao()));
        Optional<UltimoTreino> doANoDao = consultaDAO.buscarUltimoTreino(conn, usuarioA, idRemada);
        conferir("9 (DAO): o A na remada -> 14/10, não 17/10 (a mensagem é dele, mas a sessão é do B)",
                doANoDao.isPresent() && D14.equals(doANoDao.get().dataSessao()));
    }

    // --- Filtros e política por status: não mudam ----------------------------------------------

    private static void statusDoRemetente(Connection conn) throws SQLException, InterruptedException {
        titulo("Filtros e política por status: /carga só para ATIVO");
        long mensagensDoA = contar(conn, SQL_MENSAGENS_DO_USUARIO, usuarioA);
        LocalDateTime hora = LocalDateTime.of(2026, 10, 18, 9, 30);
        Cenario c = new Cenario(CONSULTA);
        c.telegram.updates.addAll(List.of(
                texto(73001, ID_TELEGRAM_PENDENTE, NOME_PENDENTE, "/carga remada unilateral máquina", hora),
                texto(73002, ID_TELEGRAM_BLOQUEADO, NOME_BLOQUEADO, "/carga remada unilateral máquina", hora),
                texto(73003, ID_TELEGRAM_DESCONHECIDO, NOME_DESCONHECIDO, "/carga remada unilateral máquina", hora),
                mensagem("message", 73004, ID_TELEGRAM_A, NOME_A, "group", ID_GRUPO, hora,
                        "\"text\": " + aspas(segredo("/carga remada no grupo"))),
                mensagem("edited_message", 73005, ID_TELEGRAM_A, NOME_A, "private", ID_TELEGRAM_A, hora,
                        "\"edit_date\": " + epoch(hora) + ", \"text\": " + aspas(segredo("/carga remada editada")))));
        int chamadasAntes = CONSULTA.chamadas;
        c.rodarAteEsvaziar();
        conferir("PENDENTE: 'aguardando aprovação'; edição do ATIVO: o aviso de edição; o resto, silêncio",
                c.telegram.enviadas.equals(List.of(Textos.AGUARDANDO_APROVACAO, Textos.EDICAO_IGNORADA))
                        && c.telegram.destinos.equals(List.of(ID_TELEGRAM_PENDENTE, ID_TELEGRAM_A)));
        conferir("nenhum dos cinco chegou ao consultor", CONSULTA.chamadas == chamadasAntes);
        conferir("nada gravado: PENDENTE e BLOQUEADO sem mensagem; o A sem mensagem nova (grupo e edição)",
                contar(conn, SQL_MENSAGENS_DO_USUARIO, usuarioPendente) == 0
                        && contar(conn, SQL_MENSAGENS_DO_USUARIO, usuarioBloqueado) == 0
                        && contar(conn, SQL_MENSAGENS_DO_USUARIO, usuarioA) == mensagensDoA);
        conferir("offset avançou depois dos cinco (73006)", c.loop.offset() == 73006);
        conferirLinha(c, 73001, linhaDeLog(73001, "comando", "ignorado: remetente PENDENTE"));
        conferirLinha(c, 73002, linhaDeLog(73002, "comando", "ignorado: remetente BLOQUEADO"));
        conferirLinha(c, 73003, linhaDeLog(73003, "comando", "ignorado: remetente não cadastrado"));
        conferirLinha(c, 73004, linhaDeLog(73004, "texto", "ignorado: não é chat privado"));
        conferirLinha(c, 73005, linhaDeLog(73005, "edição", "ignorado: edição"));
    }

    // --- Critério 6: treino A, treino B, /desfazer -> a consulta devolve A ----------------------

    private static void desfazerDevolveOAnterior(Connection conn) throws SQLException, InterruptedException {
        titulo("Treino A (13/10), treino B (15/10), /desfazer -> a consulta devolve A (6)");
        long idRemada = idExercicio(conn, REMADA_UNILATERAL);
        long sessaoA = sessaoDAO.inserir(conn, usuarioDesfazer, D13, null);
        long treinoA = mensagemComSessao(conn, usuarioDesfazer, 1L, D13.atTime(19, 0), sessaoA);
        serieDAO.inserir(conn, treinoA, serie(1, 30.0, false, 12, false, null), idRemada);
        serieDAO.inserir(conn, treinoA, serie(2, 30.0, false, 11, false, null), idRemada);
        long sessaoB = sessaoDAO.inserir(conn, usuarioDesfazer, D15, null);
        long treinoB = mensagemComSessao(conn, usuarioDesfazer, 2L, D15.atTime(19, 0), sessaoB);
        serieDAO.inserir(conn, treinoB, serie(1, 32.5, false, 10, false, null), idRemada);
        serieDAO.inserir(conn, treinoB, serie(2, 32.5, false, 9, false, null), idRemada);
        conn.commit();

        // O /carga do meio fica gravado SEM sessão, e é a mensagem mais nova quando o
        // /desfazer chega: a pilha tem que pulá-lo e desfazer o treino B.
        Cenario c = new Cenario(CONSULTA);
        c.telegram.updates.addAll(List.of(
                texto(74001, ID_TELEGRAM_DESFAZER, NOME_OUTROS, "/carga remada unilateral máquina", D15.atTime(20, 0)),
                texto(74002, ID_TELEGRAM_DESFAZER, NOME_OUTROS, "/desfazer", D15.atTime(20, 5)),
                texto(74003, ID_TELEGRAM_DESFAZER, NOME_OUTROS, "/carga remada unilateral máquina", D15.atTime(20, 10))));
        c.rodarAteEsvaziar();
        conferir("antes do /desfazer: o treino B (15/10)", resposta(cabecalho(REMADA_UNILATERAL, Textos.CARGA_POR_LADO,
                D15), "1ª: 32,5 kg × 10", "2ª: 32,5 kg × 9").equals(c.resposta(0)));
        conferir("/desfazer desfez o treino B (a resposta da 6c, com a data dele)",
                (Textos.DESFEITO_REGISTRO_DE.formatted("15/10/2026") + "\n" + REMADA_UNILATERAL + " "
                        + Textos.VARIAS_SERIES.formatted(2)).equals(c.resposta(1)));
        conferir("no banco: o treino B marcado pelo /desfazer (79002); o treino A continua válido",
                new Marca(D15.atTime(20, 5), 79002L).equals(marca(conn, treinoB))
                        && new Marca(null, null).equals(marca(conn, treinoA)));
        Optional<MensagemGravada> primeiroCarga = mensagem(conn, usuarioDesfazer, 79001);
        conferir("a pilha pulou o /carga (sem sessão): ele continua sem sessão e sem marca",
                primeiroCarga.isPresent() && primeiroCarga.get().idSessao() == null
                        && primeiroCarga.get().desfeita() == null);
        conferir("6: depois do /desfazer, a consulta devolve o treino A (13/10)",
                resposta(cabecalho(REMADA_UNILATERAL, Textos.CARGA_POR_LADO, D13), "1ª: 30 kg × 12", "2ª: 30 kg × 11")
                        .equals(c.resposta(2)));
        conferirLinha(c, 74002, linhaDeLog(74002, "comando", "desfazer: desfeito"));
        conferirLinha(c, 74003, linhaDeLog(74003, "consulta", "ENCONTRADO"));
    }

    // --- Critério 7: duas mensagens na mesma sessão, a segunda desfeita ------------------------

    private static void duasMensagensNaSessao(Connection conn) throws SQLException, InterruptedException {
        titulo("Duas mensagens na mesma sessão, a segunda desfeita -> só as séries da primeira (7)");
        long idRemada = idExercicio(conn, REMADA_UNILATERAL);
        long sessao = sessaoDAO.inserir(conn, usuarioDuas, D15, null);
        long primeira = mensagemComSessao(conn, usuarioDuas, 1L, D15.atTime(18, 0), sessao);
        long segunda = mensagemComSessao(conn, usuarioDuas, 2L, D15.atTime(18, 30), sessao);
        // As séries da SEGUNDA são gravadas ANTES (ID_SERIE menor): pelo ID_SERIE, as
        // dela viriam primeiro. A ordem de envio da mensagem vem antes do id.
        serieDAO.inserir(conn, segunda, serie(1, 45.0, false, 8, false, null), idRemada);
        serieDAO.inserir(conn, segunda, serie(2, 45.0, false, 7, false, null), idRemada);
        serieDAO.inserir(conn, primeira, serie(1, 40.0, false, 10, false, null), idRemada);
        serieDAO.inserir(conn, primeira, serie(2, 40.0, false, 9, false, null), idRemada);
        conn.commit();

        Cenario c = new Cenario(CONSULTA);
        c.telegram.updates.addAll(List.of(
                texto(75001, ID_TELEGRAM_DUAS, NOME_OUTROS, "/carga remada unilateral máquina", D15.atTime(18, 40)),
                texto(75002, ID_TELEGRAM_DUAS, NOME_OUTROS, "/desfazer", D15.atTime(18, 45)),
                texto(75003, ID_TELEGRAM_DUAS, NOME_OUTROS, "/carga remada unilateral máquina", D15.atTime(18, 50))));
        c.rodarAteEsvaziar();
        // Ajuste 2 da fatia 7: mensagem por mensagem (m1s1, m1s2, m2s1, m2s2), nunca
        // intercalado (1, 1, 2, 2).
        conferir("antes: as duas mensagens, uma depois da outra pela ordem de envio, sem intercalar",
                resposta(cabecalho(REMADA_UNILATERAL, Textos.CARGA_POR_LADO, D15), "1ª: 40 kg × 10", "2ª: 40 kg × 9",
                        "1ª: 45 kg × 8", "2ª: 45 kg × 7").equals(c.resposta(0)));
        conferir("o /desfazer marcou a segunda; a primeira continua válida",
                new Marca(D15.atTime(18, 45), 80002L).equals(marca(conn, segunda))
                        && new Marca(null, null).equals(marca(conn, primeira)));
        conferir("7: depois, só as séries da primeira, na mesma sessão (15/10)",
                resposta(cabecalho(REMADA_UNILATERAL, Textos.CARGA_POR_LADO, D15), "1ª: 40 kg × 10", "2ª: 40 kg × 9")
                        .equals(c.resposta(2)));
    }

    // --- Ajustes da 7: ordem mensagem por mensagem; argumento só de pontuação ----------------

    private static void ordemDasSeries(Connection conn) throws SQLException, InterruptedException {
        titulo("Ordem na sessão: mensagem por mensagem (ajuste 2); '/carga ???' -> ajuda (ajuste 1)");
        long idCavalinho = idExercicio(conn, REMADA_CAVALINHO);
        long idPuxadaAberta = idExercicio(conn, PUXADA_ABERTA);
        long sessao = sessaoDAO.inserir(conn, usuarioOrdem, D15, null);

        // (a) Duas mensagens no MESMO segundo, cada uma com 1ª e 2ª: quem decide é o
        // ID_MENSAGEM (na mesma conexão, o IDENTITY sai crescente: m1 < m2). As
        // séries da m2 são gravadas ANTES (ID_SERIE menor): nem o número nem o
        // ID_SERIE podem decidir.
        LocalDateTime mesmoSegundo = D15.atTime(18, 0, 0);
        long m1 = mensagemComSessao(conn, usuarioOrdem, 1L, mesmoSegundo, sessao);
        long m2 = mensagemComSessao(conn, usuarioOrdem, 2L, mesmoSegundo, sessao);
        serieDAO.inserir(conn, m2, serie(1, 65.0, false, 8, false, null), idCavalinho);
        serieDAO.inserir(conn, m2, serie(2, 65.0, false, 7, false, null), idCavalinho);
        serieDAO.inserir(conn, m1, serie(1, 60.0, false, 10, false, null), idCavalinho);
        serieDAO.inserir(conn, m1, serie(2, 60.0, false, 9, false, null), idCavalinho);

        // (b) A primeira mensagem tem 1ª, 2ª e 3ª; uma posterior manda só uma "2ª"
        // (correção ou complemento). Pelo número primeiro, a 2ª nova cairia antes da 3ª.
        long primeira = mensagemComSessao(conn, usuarioOrdem, 3L, D15.atTime(19, 0), sessao);
        serieDAO.inserir(conn, primeira, serie(1, 50.0, false, 12, false, null), idPuxadaAberta);
        serieDAO.inserir(conn, primeira, serie(2, 50.0, false, 11, false, null), idPuxadaAberta);
        serieDAO.inserir(conn, primeira, serie(3, 50.0, false, 10, false, null), idPuxadaAberta);
        long posterior = mensagemComSessao(conn, usuarioOrdem, 4L, D15.atTime(19, 20), sessao);
        serieDAO.inserir(conn, posterior, serie(2, 52.5, false, 10, false, null), idPuxadaAberta);
        conn.commit();

        Cenario c = new Cenario(CONSULTA);
        c.telegram.updates.addAll(List.of(
                texto(79501, ID_TELEGRAM_ORDEM, NOME_OUTROS, "/carga remada cavalinho", D15.atTime(20, 0)),
                texto(79502, ID_TELEGRAM_ORDEM, NOME_OUTROS, "/carga puxada aberta", D15.atTime(20, 1)),
                texto(79503, ID_TELEGRAM_ORDEM, NOME_OUTROS, "/carga ???", D15.atTime(20, 2))));
        c.rodarAteEsvaziar();
        conferir("(a) mesmo segundo: m1s1, m1s2, m2s1, m2s2 (desempate pelo ID_MENSAGEM, sem intercalar)",
                resposta(cabecalho(REMADA_CAVALINHO, Textos.CARGA_TOTAL, D15), "1ª: 60 kg × 10", "2ª: 60 kg × 9",
                        "1ª: 65 kg × 8", "2ª: 65 kg × 7").equals(c.resposta(0)));
        conferir("(b) a 2ª da mensagem posterior vem depois das três da primeira, com o número dela",
                resposta(cabecalho(PUXADA_ABERTA, Textos.CARGA_TOTAL, D15), "1ª: 50 kg × 12", "2ª: 50 kg × 11",
                        "3ª: 50 kg × 10", "2ª: 52,5 kg × 10").equals(c.resposta(1)));
        Optional<UltimoTreino> treino = consultaDAO.buscarUltimoTreino(conn, usuarioOrdem, idPuxadaAberta);
        conferir("(b) DAO: números na ordem 1, 2, 3, 2", treino.isPresent() && treino.get().series().stream()
                .map(SerieConsultada::numeroSerie).toList().equals(List.of(1, 2, 3, 2)));
        conferir("'/carga ???' -> ajuda (vazio depois de normalizar)", Textos.CARGA_AJUDA.equals(c.resposta(2)));
        conferirLinha(c, 79503, linhaDeLog(79503, "consulta", "AJUDA"));
    }

    // --- Critério 8: NULL, "~", falha, carga 0, percepção e observação --------------------------

    private static void formatoDaResposta(Connection conn) throws SQLException, InterruptedException {
        titulo("Formato: NULL -> '—', '~' só quando dito, falha só quando dita, sem percepção e observação (8)");
        long idBarra = idExercicio(conn, BARRA_FIXA);
        long sessao = sessaoDAO.inserir(conn, usuarioFormato, D15, OBSERVACAO);
        long mensagem = mensagemComSessao(conn, usuarioFormato, 1L, D15.atTime(19, 0), sessao);
        // 1: carga 0 dita (barra sem peso), "sem falha" dito, com percepção.
        serieDAO.inserir(conn, mensagem, new SerieExtraida("barra", null, 1, 0.0, false, 8, false, false, PERCEPCAO),
                idBarra);
        // 2: carga NULL com CARGA_APROX 'S' no banco (o DDL deixa): o 'S' não pode virar "~".
        serieDAO.inserir(conn, mensagem, serie(2, null, true, 6, true, true), idBarra);
        // 3: repetições NULL com REPETICOES_APROX 'S'; falha NULL (não dita).
        serieDAO.inserir(conn, mensagem, serie(3, 10.0, true, null, true, null), idBarra);
        serieDAO.inserir(conn, mensagem, serie(4, 22.5, false, 10, false, null), idBarra);
        serieDAO.inserir(conn, mensagem, serie(5, 100.0, false, 5, false, null), idBarra);
        conn.commit();

        Cenario c = new Cenario(CONSULTA);
        c.telegram.updates.add(texto(76001, ID_TELEGRAM_FORMATO, NOME_OUTROS, "/carga Barra Fixa", D15.atTime(20, 0)));
        c.rodarAteEsvaziar();
        String esperada = resposta(cabecalho(BARRA_FIXA, Textos.CARGA_TOTAL, D15),
                "1ª: 0 kg × 8 " + Textos.SEM_FALHA,
                "2ª: — × ~6 " + Textos.FALHA,
                "3ª: ~10 kg × —",
                "4ª: 22,5 kg × 10",
                "5ª: 100 kg × 5");
        System.out.println("        resposta do critério 8:");
        System.out.println(recuar(c.resposta(0)));
        conferir("8: resposta caractere por caractere (0 kg, —, ~, (falha), (sem falha), 22,5, 100)",
                esperada.equals(c.resposta(0)));
        conferir("8: carga NULL -> '—', sem '~' e sem 0; repetições NULL -> '—', sem '~'",
                c.resposta(0).contains("2ª: — × ~6") && c.resposta(0).contains("3ª: ~10 kg × —")
                        && !c.resposta(0).contains("~—"));
        conferir("8: percepção e observação do dia não aparecem",
                !c.resposta(0).contains(PERCEPCAO) && !c.resposta(0).contains("pesado")
                        && !c.resposta(0).contains(OBSERVACAO) && !c.resposta(0).contains("lotada"));

        // No banco: o 'S' existe nas colunas de "aproximado" das séries 2 e 3.
        SerieGravada segundaNoBanco = serieGravada(conn, mensagem, 2);
        SerieGravada terceiraNoBanco = serieGravada(conn, mensagem, 3);
        conferir("no banco: série 2 com CARGA_KG NULL (não 0) e CARGA_APROX 'S'",
                segundaNoBanco.cargaNula() && "S".equals(segundaNoBanco.cargaAprox()));
        conferir("no banco: série 3 com REPETICOES NULL (não 0) e REPETICOES_APROX 'S'",
                terceiraNoBanco.repeticoesNulas() && "S".equals(terceiraNoBanco.repeticoesAprox()));

        // No DAO: o 'S' não foi lido (o campo ficou null), e NULL continua null.
        Optional<UltimoTreino> treino = consultaDAO.buscarUltimoTreino(conn, usuarioFormato, idBarra);
        List<SerieConsultada> series = treino.isPresent() ? treino.get().series() : List.of();
        conferir("DAO: 5 séries, na ordem do número", series.size() == 5 && series.stream()
                .map(SerieConsultada::numeroSerie).toList().equals(List.of(1, 2, 3, 4, 5)));
        conferir("8 (DAO): carga NULL -> cargaKg null E cargaAproximada null (CARGA_APROX não foi lida)",
                series.size() == 5 && series.get(1).cargaKg() == null && series.get(1).cargaAproximada() == null);
        conferir("8 (DAO): repetições NULL -> repeticoes null E repeticoesAproximadas null",
                series.size() == 5 && series.get(2).repeticoes() == null
                        && series.get(2).repeticoesAproximadas() == null);
        conferir("DAO: carga 0 dita -> 0.0 (não null), aproximada false",
                series.size() == 5 && Double.valueOf(0.0).equals(series.get(0).cargaKg())
                        && Boolean.FALSE.equals(series.get(0).cargaAproximada()));
        conferir("DAO: falha 'N' -> false, 'S' -> true, NULL -> null",
                series.size() == 5 && Boolean.FALSE.equals(series.get(0).falha())
                        && Boolean.TRUE.equals(series.get(1).falha()) && series.get(2).falha() == null);
    }

    // --- Critério 10: reentrega -> silêncio ------------------------------------------------------

    private static void reentrega(Connection conn) throws SQLException, InterruptedException {
        titulo("Reentrega: o mesmo /carga de novo -> silêncio, sem linha nova (10)");
        long sessao = sessaoDAO.inserir(conn, usuarioReentrega, D15, null);
        long treino = mensagemComSessao(conn, usuarioReentrega, 1L, D15.atTime(19, 0), sessao);
        serieDAO.inserir(conn, treino, serie(1, 50.0, false, 10, false, null), idExercicio(conn, REMADA_UNILATERAL));
        conn.commit();

        Cenario c = new Cenario(CONSULTA);
        c.telegram.updates.addAll(List.of(
                texto(77001, ID_TELEGRAM_REENTREGA, NOME_OUTROS, "/carga remada unilateral máquina", D15.atTime(20, 0)),
                texto(77002, ID_TELEGRAM_REENTREGA, NOME_OUTROS, "/carga", D15.atTime(20, 1))));
        c.rodarAteEsvaziar();
        conferir("1ª entrega: duas respostas (encontrado e ajuda)", c.telegram.enviadas.size() == 2);
        long mensagensDepoisDaPrimeira = contar(conn, SQL_MENSAGENS_DO_USUARIO, usuarioReentrega);
        conferir("1ª entrega: as duas gravadas (1 treino + 2 /carga)", mensagensDepoisDaPrimeira == 3);

        // O bot caiu antes de confirmar o offset: loop novo (offset 0), mesmos updates.
        c.reiniciar();
        int chamadasAntes = CONSULTA.chamadas;
        c.rodarAteEsvaziar();
        conferir("reentrega: o consultor foi chamado de novo (2 vezes)", CONSULTA.chamadas - chamadasAntes == 2);
        conferir("reentrega: silêncio (nenhum envio novo, nem tentativa)",
                c.telegram.enviadas.size() == 2 && c.telegram.tentativasDeEnvio == 2);
        conferir("reentrega: nenhuma linha nova em TR_MENSAGEM",
                contar(conn, SQL_MENSAGENS_DO_USUARIO, usuarioReentrega) == mensagensDepoisDaPrimeira);
        conferir("reentrega: avançou (77003)", c.loop.offset() == 77003);
        conferirLinha(c, 77001, linhaDeLog(77001, "consulta", "JA_GRAVADA"));
        conferirLinha(c, 77002, linhaDeLog(77002, "consulta", "JA_GRAVADA"));
    }

    // --- Regra 2: erro na T1 (decisão de offset da 6a) e depois da T1 (ERRO) --------------------

    private static void errosNoBanco() throws InterruptedException {
        titulo("Erros: depois da T1 -> ERRO genérico e avança; na T1 -> decisão de offset da 6a (regra 2)");
        LocalDateTime hora = LocalDateTime.of(2026, 10, 18, 10, 0);

        // Depois da T1 (catálogo ou séries): o service devolve ERRO, sem exceção.
        ConsultorFalso erro = new ConsultorFalso();
        erro.respostas.add(() -> ResultadoConsulta.erro("séries: erro no banco (ORA-17002)"));
        Cenario depois = new Cenario(erro);
        String textoComArroba = "/carga@TreinoBot   remada  unilateral   ";
        depois.telegram.updates.add(texto(78001, ID_TELEGRAM_FALSO, NOME_OUTROS, textoComArroba, hora));
        depois.rodarAteEsvaziar();
        conferir("ERRO: 'não consegui consultar, tente de novo'", depois.telegram.enviadas
                .equals(List.of(Textos.NAO_CONSEGUI_CONSULTAR)));
        conferir("ERRO: avançou (78002) sem esperar", depois.loop.offset() == 78002 && depois.esperas.isEmpty());
        conferirLinha(depois, 78001, linhaDeLog(78001, "consulta", "ERRO: séries: erro no banco (ORA-17002)"));
        // O que o loop entrega ao service.
        conferir("loop -> consultor: ID_USUARIO interno (não o from.id), message_id, envio em São Paulo",
                erro.idUsuario == usuarioFalso && erro.idTelegram == 83001 && hora.equals(erro.envio));
        conferir("loop -> consultor: o texto inteiro, como veio (TEXTO_BRUTO)", textoComArroba.equals(erro.texto));
        conferir("loop -> consultor: o argumento sem '@TreinoBot' e sem as pontas (o service normaliza o resto)",
                "remada  unilateral".equals(erro.argumento));

        // Na T1, conexão caída: não avança, espera, não responde; na volta, responde uma vez.
        ConsultorFalso conexao = new ConsultorFalso();
        conexao.respostas.add(() -> {
            throw new SQLRecoverableException("IO Error", "08006", 17002);
        });
        conexao.respostas.add(ResultadoConsulta::naoEncontrado);
        Cenario t1Conexao = new Cenario(conexao);
        t1Conexao.telegram.updates.add(texto(78011, ID_TELEGRAM_FALSO, NOME_OUTROS, "/carga supino declinado", hora));
        t1Conexao.loop.umaVolta();
        conferir("T1 com conexão caída: não avançou, espera 5 s, sem resposta", t1Conexao.loop.offset() == 0
                && t1Conexao.esperas.equals(List.of(Duration.ofSeconds(5))) && t1Conexao.telegram.tentativasDeEnvio == 0);
        conferirLinha(t1Conexao, 78011, linhaDeLog(78011, "consulta",
                "erro no banco ao gravar a consulta (ORA-17002), tentativa 1 de 10; repete em 5 s"));
        t1Conexao.rodarAteEsvaziar();
        conferir("T1 com conexão caída: na 2ª tentativa respondeu uma vez e avançou", conexao.chamadas == 2
                && t1Conexao.telegram.enviadas.equals(List.of(Textos.CARGA_NAO_ENCONTREI))
                && t1Conexao.loop.offset() == 78012);

        // Na T1, recusa do banco: avança sem esperar e avisa com o texto da 6a.
        ConsultorFalso recusa = new ConsultorFalso();
        recusa.respostas.add(() -> {
            throw new SQLIntegrityConstraintViolationException("ORA-02291: integrity constraint", "23000", 2291);
        });
        Cenario t1Recusa = new Cenario(recusa);
        t1Recusa.telegram.updates.add(texto(78021, ID_TELEGRAM_FALSO, NOME_OUTROS, "/carga supino declinado", hora));
        t1Recusa.rodarAteEsvaziar();
        conferir("T1 recusada: avançou sem esperar, 'não consegui salvar' (texto da 6a)",
                t1Recusa.loop.offset() == 78022 && t1Recusa.esperas.isEmpty()
                        && t1Recusa.telegram.enviadas.equals(List.of(Textos.NAO_CONSEGUI_SALVAR)));
        conferirLinha(t1Recusa, 78021, linhaDeLog(78021, "consulta",
                "recusa do banco ao gravar a consulta (ORA-02291); avançou"));
    }

    // --- Critério 11: nada sensível no log -------------------------------------------------------

    private static void higieneDoLog() {
        titulo("Log sem dado de usuário (11)");
        conferir("houve log para conferir (" + LOG_GERAL.size() + " linhas)", LOG_GERAL.size() > 30);
        int vazamentos = 0;
        for (String linha : LOG_GERAL) {
            // A hora do começo da linha é o relógio parado do spike: fica de fora da
            // busca por números (o dia 18 ou o mês 10 confundiriam um id pequeno).
            String semHora = linha.substring(linha.indexOf(" | ") + 3);
            for (String proibido : PROIBIDOS_NO_LOG) {
                if (linha.contains(proibido)) {
                    vazamentos++;
                    System.out.println("        vazou em: " + semHora.substring(0, Math.min(semHora.length(), 20)));
                }
            }
            for (long id : IDS_PROIBIDOS_NO_LOG) {
                // Número inteiro, não pedaço de outro (o id 71 não "vaza" no update 71001).
                if (Pattern.compile("(?<![0-9])" + id + "(?![0-9])").matcher(semHora).find()) {
                    vazamentos++;
                    System.out.println("        vazou em: " + semHora.substring(0, Math.min(semHora.length(), 20)));
                }
            }
        }
        conferir("nenhuma linha contém texto, argumento, nome, nome de exercício, apelido, from.id ou ID_USUARIO ("
                + PROIBIDOS_NO_LOG.size() + " textos e " + IDS_PROIBIDOS_NO_LOG.size() + " ids conferidos)",
                vazamentos == 0);
    }

    private static void semExtrator() {
        titulo("Sem LLM (10)");
        conferir("o extrator foi chamado 0 vezes em todo o spike (veio " + EXTRATOR.chamadas + ")",
                EXTRATOR.chamadas == 0);
    }

    // --- Montagem dos cenários (só DAOs da fatia 4) -----------------------------------------------

    private static long usuarioDeTeste(Connection conn, long idTelegram, String nome, String status)
            throws SQLException {
        long idUsuario = usuarioDAO.inserirPendente(conn, idTelegram, nome, CADASTRO);
        if (!"PENDENTE".equals(status)) {
            usuarioDAO.atualizarStatus(conn, idUsuario, status);
        }
        return idUsuario;
    }

    private static void apelido(Connection conn, String apelido, long idExercicio, long idUsuario)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_INSERIR_APELIDO)) {
            ps.setString(1, apelido);
            ps.setLong(2, idExercicio);
            ps.setLong(3, idUsuario);
            ps.executeUpdate();
        }
    }

    // O que o RegistroService faz na T1: a mensagem crua, ainda sem sessão.
    private static long mensagemSemSessao(Connection conn, long idUsuario, long messageId, LocalDateTime envio)
            throws SQLException {
        ResultadoInsercao gravada = mensagemDAO.inserir(conn, idUsuario, messageId, envio, "TEXTO",
                "TESTE relato do ConsultaSpike");
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

    private static void ligarASessaoDeOutro(Connection conn, long idMensagem, long idSessao) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_LIGAR_A_SESSAO_DE_OUTRO)) {
            ps.setLong(1, idSessao);
            ps.setLong(2, idMensagem);
            if (ps.executeUpdate() != 1) {
                throw new IllegalStateException("Mensagem de teste não encontrada.");
            }
        }
    }

    // O exercicio_relatado e o nome do modelo não importam para a consulta (ela usa o
    // ID_EXERCICIO); percepção nula.
    private static SerieExtraida serie(int numero, Double carga, boolean cargaAproximada, Integer repeticoes,
            boolean repeticoesAproximadas, Boolean falha) {
        return new SerieExtraida("TESTE relatado", null, numero, carga, cargaAproximada, repeticoes,
                repeticoesAproximadas, falha, null);
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

    // --- Respostas e linhas esperadas -------------------------------------------------------------

    private static String cabecalho(String nome, String formaCarga, LocalDate data) {
        return Textos.CARGA_ULTIMO_TREINO.formatted(nome, formaCarga, data.format(DATA));
    }

    // Cabeçalho + uma linha por série, com o recuo da confirmação.
    private static String resposta(String cabecalho, String... linhas) {
        StringBuilder texto = new StringBuilder(cabecalho);
        for (String linha : linhas) {
            texto.append("\n  ").append(linha);
        }
        return texto.toString();
    }

    // A ordem dos candidatos é a do catálogo (ORDER BY do ExercicioDAO), que depende
    // da ordenação da sessão no Oracle: confere o conjunto, não a ordem.
    private static void conferirAmbiguo(String cenario, String resposta, Set<String> candidatos) {
        List<String> linhas = List.of(resposta.split("\n"));
        Set<String> listados = new HashSet<>();
        for (String linha : linhas.subList(Math.min(1, linhas.size()), linhas.size())) {
            listados.add(linha.startsWith("- ") ? linha.substring(2) : linha);
        }
        conferir(cenario, !linhas.isEmpty() && Textos.CARGA_QUAL_DELES.equals(linhas.get(0))
                && linhas.size() == candidatos.size() + 1 && listados.equals(candidatos));
    }

    private static String linhaDeLog(long updateId, String tipo, String resultado) {
        return HORA_NO_LOG + " | update " + updateId + " | " + tipo + " | " + resultado + " | "
                + String.format(PT_BR, "%.1f s", 0.0);
    }

    // --- Leituras do banco ------------------------------------------------------------------------

    private record MensagemGravada(Long idSessao, LocalDateTime desfeita, String origem, LocalDateTime envio,
            String texto) {
    }

    // TEXTO_BRUTO é CLOB: getString lê o texto (curto) inteiro.
    private static Optional<MensagemGravada> mensagem(Connection conn, long idUsuario, long messageId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_MENSAGEM)) {
            ps.setLong(1, idUsuario);
            ps.setLong(2, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new MensagemGravada(rs.getObject("ID_SESSAO", Long.class),
                        rs.getObject("DATA_HORA_DESFEITA", LocalDateTime.class), rs.getString("ORIGEM"),
                        rs.getObject("DATA_HORA_ENVIO", LocalDateTime.class), rs.getString("TEXTO_BRUTO")));
            }
        }
    }

    /** As duas colunas da 6c. (null, null) = válida. */
    private record Marca(LocalDateTime quando, Long porComando) {
    }

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

    private record SerieGravada(boolean cargaNula, String cargaAprox, boolean repeticoesNulas,
            String repeticoesAprox) {
    }

    private static SerieGravada serieGravada(Connection conn, long idMensagem, int numero) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_SERIE_GRAVADA)) {
            ps.setLong(1, idMensagem);
            ps.setInt(2, numero);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Série de teste não encontrada.");
                }
                return new SerieGravada(rs.getBigDecimal("CARGA_KG") == null, rs.getString("CARGA_APROX"),
                        rs.getObject("REPETICOES", Integer.class) == null, rs.getString("REPETICOES_APROX"));
            }
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

    // --- Preparação e limpeza (como no DesfazerSpike) ---------------------------------------------

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

    // --- Updates em JSON ----------------------------------------------------------------------------

    // Texto que faz papel de dado de usuário: entra na lista do que não pode ir para o log.
    private static String segredo(String texto) {
        PROIBIDOS_NO_LOG.add(texto);
        return texto;
    }

    private static JsonNode texto(long updateId, long fromId, String nome, String texto, LocalDateTime envio) {
        return mensagem("message", updateId, fromId, nome, "private", fromId, envio,
                "\"text\": " + aspas(segredo(texto)));
    }

    // message_id = update_id + 5000, para os dois não se confundirem nas conferências.
    private static JsonNode mensagem(String campo, long updateId, long fromId, String nome, String tipoChat,
            long chatId, LocalDateTime envio, String conteudo) {
        return MAPPER.readTree("""
                {"update_id": %d,
                 "%s": {"message_id": %d, "date": %d,
                        "chat": {"id": %d, "type": "%s"},
                        "from": {"id": %d, "is_bot": false, "first_name": %s},
                        %s}}
                """.formatted(updateId, campo, updateId + 5000, epoch(envio), chatId, tipoChat, fromId, aspas(nome),
                conteudo));
    }

    // "date" do Telegram: epoch em segundos (UTC) do horário de São Paulo dado.
    private static long epoch(LocalDateTime envio) {
        return envio.atZone(SAO_PAULO).toEpochSecond();
    }

    private static String aspas(String texto) {
        return MAPPER.writeValueAsString(texto);
    }

    // --- Conferências -----------------------------------------------------------------------------

    private static void titulo(String texto) {
        System.out.println();
        System.out.println("=== " + texto + " ===");
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

    // A linha de log do update é exatamente a esperada, e é a última dele.
    private static void conferirLinha(Cenario c, long updateId, String esperada) {
        List<String> linhas = c.linhasDoUpdate(updateId);
        conferir("log do update " + updateId + ": \"" + esperada.substring(HORA_NO_LOG.length() + 3) + "\"",
                !linhas.isEmpty() && esperada.equals(linhas.get(linhas.size() - 1)));
    }

    private static String recuar(String texto) {
        return texto.isEmpty() ? "          (nada enviado)" : "          " + texto.replace("\n", "\n          ");
    }

    // --- Os falsos e o cenário ----------------------------------------------------------------------

    /** Um loop novo, com Telegram falso novo, sobre o banco de verdade. */
    private static final class Cenario {
        final TelegramFalso telegram = new TelegramFalso();
        final List<Duration> esperas = new ArrayList<>();
        final List<String> log = new ArrayList<>();
        final Consultor consultor;
        // Não é final só por causa do reiniciar().
        LoopTelegram loop;

        Cenario(Consultor consultor) {
            this.consultor = consultor;
            loop = novoLoop();
        }

        private LoopTelegram novoLoop() {
            return new LoopTelegram(telegram, REGISTRO, ConsultaSpike::buscarUsuario, CADASTRO_SERVICE, CORRECAO,
                    consultor, ID_ADMIN_DO_LOOP, esperas::add, Clock.fixed(AGORA, SAO_PAULO), linha -> {
                        log.add(linha);
                        LOG_GERAL.add(linha);
                        System.out.println("        log: " + linha);
                    });
        }

        // O bot caiu e subiu de novo: loop novo, offset de volta a 0, mesmos updates
        // no Telegram falso e o mesmo banco.
        void reiniciar() {
            loop = novoLoop();
        }

        // Volta a volta até o Telegram falso não ter mais nada para entregar.
        // null = esvaziou sem encerrar o bot.
        Encerramento rodarAteEsvaziar() throws InterruptedException {
            for (int volta = 0; volta < 50; volta++) {
                if (!telegram.temPendentes(loop.offset())) {
                    return null;
                }
                Encerramento fim = loop.umaVolta();
                if (fim != null) {
                    return fim;
                }
            }
            throw new IllegalStateException("O loop não esvaziou a fila em 50 voltas.");
        }

        // A resposta de número i (0 = a primeira), ou "" se não houve: nenhuma
        // resposta esperada é vazia, e as conferências com contains não quebram.
        String resposta(int i) {
            return i < telegram.enviadas.size() ? telegram.enviadas.get(i) : "";
        }

        List<String> linhasDoUpdate(long updateId) {
            String marca = "| update " + updateId + " |";
            return log.stream().filter(l -> l.contains(marca)).toList();
        }
    }

    // Busca do remetente de verdade, como no Bot: uma conexão por busca.
    private static Optional<Usuario> buscarUsuario(long idTelegram) throws SQLException {
        try (Connection c = ConnectionFactory.getConnection()) {
            return usuarioDAO.buscarPorIdTelegram(c, idTelegram);
        }
    }

    /**
     * Telegram em memória (como no TelegramSpike): entrega os updates com update_id
     * >= offset e guarda o que seria enviado.
     */
    private static final class TelegramFalso implements ClienteTelegram {
        final List<JsonNode> updates = new ArrayList<>();
        final List<String> enviadas = new ArrayList<>();
        final List<Long> destinos = new ArrayList<>();
        int tentativasDeEnvio = 0;

        @Override
        public List<JsonNode> buscarUpdates(long offset) throws IOException, ErroTelegram {
            List<JsonNode> pendentes = new ArrayList<>();
            for (JsonNode update : updates) {
                if (update.get("update_id").longValue() >= offset) {
                    pendentes.add(update);
                }
            }
            return pendentes;
        }

        @Override
        public void enviarMensagem(long chatId, String texto) {
            tentativasDeEnvio++;
            enviadas.add(texto);
            destinos.add(chatId);
        }

        boolean temPendentes(long offset) {
            return updates.stream().anyMatch(u -> u.get("update_id").longValue() >= offset);
        }
    }

    /** O ConsultaService de verdade, contando as chamadas. */
    private static final class ConsultorContador implements Consultor {
        private final Consultor real;
        int chamadas = 0;

        ConsultorContador(Consultor real) {
            this.real = real;
        }

        @Override
        public ResultadoConsulta consultarCarga(long idUsuario, long idTelegram, LocalDateTime dataHoraEnvio,
                String texto, String argumento) throws SQLException {
            chamadas++;
            return real.consultarCarga(idUsuario, idTelegram, dataHoraEnvio, texto, argumento);
        }
    }

    /**
     * Consultor em memória: guarda os argumentos e devolve (ou lança) o que o cenário
     * mandou. Sem resposta programada, falha alto.
     */
    private static final class ConsultorFalso implements Consultor {

        @FunctionalInterface
        interface Resposta {
            ResultadoConsulta dar() throws SQLException;
        }

        final Deque<Resposta> respostas = new ArrayDeque<>();
        int chamadas = 0;
        long idUsuario;
        long idTelegram;
        LocalDateTime envio;
        String texto;
        String argumento;

        @Override
        public ResultadoConsulta consultarCarga(long idUsuario, long idTelegram, LocalDateTime dataHoraEnvio,
                String texto, String argumento) throws SQLException {
            chamadas++;
            this.idUsuario = idUsuario;
            this.idTelegram = idTelegram;
            this.envio = dataHoraEnvio;
            this.texto = texto;
            this.argumento = argumento;
            Resposta resposta = respostas.poll();
            if (resposta == null) {
                throw new IllegalStateException("consultarCarga chamado sem resposta programada");
            }
            return resposta.dar();
        }
    }

    /** Extrator que só conta. Chamado = um /carga chegou ao registro: falha alto. */
    private static final class ExtratorContador implements Extrator {
        int chamadas = 0;

        @Override
        public RelatoExtraido extrair(String relato, LocalDate dataHoje, List<ExercicioComApelidos> exercicios) {
            chamadas++;
            throw new IllegalStateException("o extrator não deveria ser chamado no ConsultaSpike");
        }
    }
}
