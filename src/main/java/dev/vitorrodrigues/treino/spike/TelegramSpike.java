package dev.vitorrodrigues.treino.spike;

import java.io.IOException;
import java.net.ConnectException;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLRecoverableException;
import java.sql.SQLSyntaxErrorException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.vitorrodrigues.treino.bot.ClienteTelegram;
import dev.vitorrodrigues.treino.bot.ErroTelegram;
import dev.vitorrodrigues.treino.bot.FalhaDeRede;
import dev.vitorrodrigues.treino.bot.LoopTelegram;
import dev.vitorrodrigues.treino.bot.LoopTelegram.Encerramento;
import dev.vitorrodrigues.treino.bot.Textos;
import dev.vitorrodrigues.treino.extrator.SerieExtraida;
import dev.vitorrodrigues.treino.modelo.ResultadoAprovacao;
import dev.vitorrodrigues.treino.modelo.ResultadoRegistro;
import dev.vitorrodrigues.treino.modelo.ResultadoRegistro.SerieGravada;
import dev.vitorrodrigues.treino.modelo.ResultadoRegistro.TipoFalha;
import dev.vitorrodrigues.treino.modelo.Usuario;
import dev.vitorrodrigues.treino.service.Cadastrador;
import dev.vitorrodrigues.treino.service.Registrador;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Aceite das fatias 6a e 6b sem celular: o LoopTelegram inteiro, com falsos no
 * lugar do Telegram, do banco, da espera e do relógio. Sem banco, sem API, sem
 * rede: não custa nada rodar.
 *
 * Cada cenário entrega updates em JSON (no formato da Bot API) a um Telegram
 * falso e confere a resposta enviada (e para qual chat), o avanço do offset, as
 * esperas pedidas e a linha de log. No fim, confere que NENHUMA linha de log de
 * nenhum cenário contém texto, nome, from.id, ID_USUARIO ou argumento do
 * /aprovar dos updates de teste.
 *
 * "Fulano", "Beltrano", "Sicrana" e "Deltrano" fazem o papel de outras pessoas;
 * "Ciclano" é o administrador.
 *
 * Rodar (da raiz do projeto):
 * ./mvnw -q compile exec:java "-Dexec.mainClass=dev.vitorrodrigues.treino.spike.TelegramSpike"
 * Conferir a última linha: "RESULTADO: N ok, 0 falhas".
 */
public class TelegramSpike {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

    // Relógio parado: a linha de log sai sempre igual ("09/10 10:02:15 ... 0,0 s").
    // 13:02:15 UTC = 10:02:15 em São Paulo.
    private static final Instant AGORA = Instant.parse("2026-10-09T13:02:15Z");
    // "date" dos updates: o mesmo instante em epoch (segundos, UTC), como o Telegram manda.
    private static final long DATA_ENVIO = AGORA.getEpochSecond();

    private static final long ID_TELEGRAM_ATIVO = 731958246L;
    private static final long ID_TELEGRAM_PENDENTE = 640275913L;
    private static final long ID_TELEGRAM_DESCONHECIDO = 588146307L;
    private static final long ID_USUARIO_ATIVO = 777123L;
    private static final String NOME_ATIVO = "Fulano Ativo";
    private static final String NOME_PENDENTE = "Beltrano Pendente";
    private static final long ID_GRUPO = -1001234567L;

    // 6b. O administrador (TELEGRAM_CHAT_ID_PERMITIDO), também ATIVO; um
    // BLOQUEADO; e os ids internos. Todos com vários dígitos, para a conferência
    // "não aparece no log" valer alguma coisa (um id 3 apareceria em qualquer linha).
    private static final long ID_TELEGRAM_ADMIN = 712604398L;
    private static final long ID_USUARIO_ADMIN = 777001L;
    private static final String NOME_ADMIN = "Ciclano Admin";
    private static final long ID_TELEGRAM_BLOQUEADO = 695310827L;
    private static final long ID_USUARIO_BLOQUEADO = 888702L;
    private static final String NOME_BLOQUEADO = "Deltrano Bloqueado";
    private static final long ID_USUARIO_PENDENTE = 888456L;
    // Quem manda /start nos cenários da 6b (o ID_TELEGRAM_DESCONHECIDO).
    private static final String NOME_NOVO = "Sicrana Nova";
    // Primeiro ID_USUARIO que o cadastro falso gera (cada cenário começa nele).
    private static final long PRIMEIRO_ID_GERADO = 905001L;
    // Não existe em nenhum cenário.
    private static final long ID_USUARIO_INEXISTENTE = 99999L;

    // Tudo o que nunca pode aparecer no log. Os textos das mensagens, as
    // dúvidas e os ids gerados entram aqui conforme os cenários os criam (ver
    // segredo()).
    private static final List<String> PROIBIDOS_NO_LOG = new ArrayList<>(List.of(
            NOME_ATIVO, NOME_PENDENTE, NOME_ADMIN, NOME_BLOQUEADO, NOME_NOVO,
            String.valueOf(ID_TELEGRAM_ATIVO), String.valueOf(ID_TELEGRAM_PENDENTE),
            String.valueOf(ID_TELEGRAM_DESCONHECIDO), String.valueOf(ID_TELEGRAM_ADMIN),
            String.valueOf(ID_TELEGRAM_BLOQUEADO),
            String.valueOf(ID_USUARIO_ATIVO), String.valueOf(ID_USUARIO_PENDENTE),
            String.valueOf(ID_USUARIO_ADMIN), String.valueOf(ID_USUARIO_BLOQUEADO),
            String.valueOf(ID_USUARIO_INEXISTENTE),
            // Argumento não numérico do /aprovar (os numéricos são os ids acima).
            "abc"));

    // Todas as linhas de log de todos os cenários, para a conferência final.
    private static final List<String> LOG_GERAL = new ArrayList<>();

    private static int ok = 0;
    private static int falhas = 0;

    public static void main(String[] args) throws InterruptedException {
        filtros();
        registroEData();
        confirmacoes();
        situacoes();
        errosNoBanco();
        errosInesperados();
        getUpdates();
        envio();
        statusDoRemetente();
        cadastroPeloStart();
        aprovar();
        fluxoCompleto();
        higieneDoLog();

        System.out.println();
        System.out.println("RESULTADO: " + ok + " ok, " + falhas + " falhas");
    }

    // --- Seções 1 e 8: filtros, na ordem --------------------------------------------------

    private static void filtros() throws InterruptedException {
        titulo("Filtros (seções 1 e 8)");
        Cenario c = new Cenario();
        c.telegram.updates.addAll(List.of(
                // 100: não é mensagem
                json("""
                        {"update_id": 100, "my_chat_member": {"chat": {"id": %d, "type": "private"},
                         "from": {"id": %d, "first_name": %s}, "date": %d}}
                        """.formatted(ID_TELEGRAM_ATIVO, ID_TELEGRAM_ATIVO, aspas(NOME_ATIVO), DATA_ENVIO)),
                // 101: grupo (mesmo de quem está ATIVO)
                mensagem("message", 101, ID_TELEGRAM_ATIVO, NOME_ATIVO, "group", ID_GRUPO, DATA_ENVIO,
                        "\"text\": " + aspas(segredo("supino no grupo do Fulano"))),
                // 102: sem from
                json("""
                        {"update_id": 102, "message": {"message_id": 5102, "date": %d,
                         "chat": {"id": %d, "type": "private"}, "text": %s}}
                        """.formatted(DATA_ENVIO, ID_TELEGRAM_ATIVO, aspas(segredo("mensagem sem from")))),
                // 103: PENDENTE; 104: desconhecido; 105: edição de PENDENTE
                texto(103, ID_TELEGRAM_PENDENTE, NOME_PENDENTE, "treino do Beltrano pendente"),
                texto(104, ID_TELEGRAM_DESCONHECIDO, "Sicrano", "oi bot, sou desconhecido"),
                mensagem("edited_message", 105, ID_TELEGRAM_PENDENTE, NOME_PENDENTE, "private",
                        ID_TELEGRAM_PENDENTE, DATA_ENVIO, "\"edit_date\": " + DATA_ENVIO + ", \"text\": "
                                + aspas(segredo("edição do Beltrano"))),
                // 106: figurinha; 107: texto em branco; 108: edição; 109: comando
                mensagem("message", 106, ID_TELEGRAM_ATIVO, NOME_ATIVO, "private", ID_TELEGRAM_ATIVO, DATA_ENVIO,
                        "\"sticker\": {\"file_id\": \"abc123\", \"emoji\": \"💪\"}"),
                texto(107, ID_TELEGRAM_ATIVO, NOME_ATIVO, "   "),
                mensagem("edited_message", 108, ID_TELEGRAM_ATIVO, NOME_ATIVO, "private", ID_TELEGRAM_ATIVO,
                        DATA_ENVIO, "\"edit_date\": " + DATA_ENVIO + ", \"text\": "
                                + aspas(segredo("supino 3x10 corrigido pelo Fulano"))),
                texto(109, ID_TELEGRAM_ATIVO, NOME_ATIVO, "/xyz apaga tudo do Fulano")));
        PROIBIDOS_NO_LOG.add("Sicrano");

        conferir("termina sem encerrar o bot", c.rodarAteEsvaziar() == null);
        conferir("offset avançou depois de todos (110)", c.loop.offset() == 110);
        conferir("registrar nunca foi chamado nos filtros", c.registrador.chamadas == 0);
        // 6b: o texto do PENDENTE (103) passou a receber "aguardando aprovação".
        conferir("respostas, na ordem: PENDENTE (6b), figurinha, branco, edição, comando",
                c.telegram.enviadas.equals(List.of(Textos.AGUARDANDO_APROVACAO, Textos.SO_ENTENDO_TEXTO,
                        Textos.SO_ENTENDO_TEXTO, Textos.EDICAO_IGNORADA, Textos.COMANDO_NAO_RECONHECIDO)));
        conferir("a do PENDENTE foi para o chat dele; as outras, para o de quem está ATIVO",
                c.telegram.destinos.equals(List.of(ID_TELEGRAM_PENDENTE, ID_TELEGRAM_ATIVO, ID_TELEGRAM_ATIVO,
                        ID_TELEGRAM_ATIVO, ID_TELEGRAM_ATIVO)));
        conferirLinha(c, 100, "| my_chat_member | ignorado: não é mensagem |");
        conferirLinha(c, 101, "| texto | ignorado: não é chat privado |");
        conferirLinha(c, 102, "| texto | ignorado: sem remetente |");
        // 103 a 105 e 109: linhas da 6b (status do remetente; comando como tipo).
        conferirLinha(c, 103, "| texto | ignorado: remetente PENDENTE |");
        conferirLinha(c, 104, "| texto | ignorado: remetente não cadastrado |");
        conferirLinha(c, 105, "| edição | ignorado: edição de remetente PENDENTE |");
        conferirLinha(c, 106, "| figurinha | ignorado: sem texto |");
        conferirLinha(c, 107, "| texto | ignorado: sem texto |");
        conferirLinha(c, 108, "| edição | ignorado: edição |");
        conferirLinha(c, 109, "| comando | ignorado: comando não reconhecido |");
    }

    // --- Seção 1.8: o que chega ao registrar ----------------------------------------------

    private static void registroEData() throws InterruptedException {
        titulo("Registro: argumentos e data de envio (seção 1.8)");
        Cenario c = new Cenario();
        String relato = "supino 3x10 com 22,5 do Fulano";
        c.telegram.updates.add(texto(200, ID_TELEGRAM_ATIVO, NOME_ATIVO, relato));
        c.registrador.respostas.add(() -> ResultadoRegistro.jaGravada(LocalDate.of(2026, 10, 9)));
        c.rodarAteEsvaziar();
        conferir("registrar chamado 1 vez", c.registrador.chamadas == 1);
        conferir("idUsuario = ID_USUARIO interno (não o from.id)", c.registrador.idUsuario == ID_USUARIO_ATIVO);
        conferir("idTelegram = message_id", c.registrador.idTelegram == 5200);
        conferir("origem TEXTO e texto como veio",
                "TEXTO".equals(c.registrador.origem) && relato.equals(c.registrador.texto));
        conferir("date (UTC) -> 10:02:15 em São Paulo",
                LocalDateTime.of(2026, 10, 9, 10, 2, 15).equals(c.registrador.dataHoraEnvio));
        // Formato completo da linha, com o relógio parado.
        conferir("linha de log no formato da seção 8",
                c.log.contains("09/10 10:02:15 | update 200 | texto | JA_GRAVADA | 0,0 s"));

        // Virada do dia: 02:30 UTC do dia 10 ainda é dia 9 em São Paulo.
        Cenario virada = new Cenario();
        long madrugadaUtc = Instant.parse("2026-10-10T02:30:00Z").getEpochSecond();
        virada.telegram.updates.add(mensagem("message", 201, ID_TELEGRAM_ATIVO, NOME_ATIVO, "private",
                ID_TELEGRAM_ATIVO, madrugadaUtc, "\"text\": " + aspas(segredo("treino tarde da noite"))));
        virada.registrador.respostas.add(() -> ResultadoRegistro.jaGravada(LocalDate.of(2026, 10, 9)));
        virada.rodarAteEsvaziar();
        conferir("02:30 UTC do dia 10 -> 23:30 do dia 9 em São Paulo",
                LocalDateTime.of(2026, 10, 9, 23, 30).equals(virada.registrador.dataHoraEnvio));
    }

    // --- Seção 4: confirmação do REGISTRADO -----------------------------------------------

    private static void confirmacoes() throws InterruptedException {
        titulo("Confirmação do REGISTRADO (seção 4)");

        // O exemplo da seção 4.
        String duvida = segredo("não ficou claro se foram 3 ou 4 séries de supino");
        ResultadoRegistro exemplo = ResultadoRegistro.registrado(LocalDate.of(2026, 10, 9), List.of(
                gravada("remada cavalinho", "Remada cavalinho", 15L, 1, 40.0, false, 10, false, null),
                gravada("remada cavalinho", "Remada cavalinho", 15L, 2, 40.0, false, 8, true, true),
                gravada("pulldown", null, null, 1, 25.0, false, 12, false, null)),
                List.of(duvida), true);
        String esperado = String.join("\n",
                Textos.REGISTRADO_EM.formatted("09/10/2026"),
                "",
                "Remada cavalinho",
                "  1ª: 40 kg × 10",
                "  2ª: 40 kg × ~8 " + Textos.FALHA,
                Textos.NAO_RECONHECI.formatted("pulldown"),
                "  1ª: 25 kg × 12",
                "",
                Textos.OBSERVACAO_ANOTADA,
                "",
                Textos.PONTOS_NAO_CLAROS,
                "- " + duvida);
        String enviada = responder(300, exemplo);
        System.out.println("        confirmação montada (exemplo da seção 4):");
        System.out.println(recuar(enviada));
        conferir("exemplo da seção 4, caractere por caractere", esperado.equals(enviada));

        // O resto da seção 4: "—" (carga e repetições), "~" na carga, 22,5,
        // "(sem falha)", 0 kg, 100 sem "1E+2", grupo que volta depois de outro,
        // agrupamento pelo exercicio_relatado quando não há id, percepção oculta.
        ResultadoRegistro detalhes = ResultadoRegistro.registrado(LocalDate.of(2026, 10, 9), List.of(
                gravada("supino", "Supino reto barra", 7L, 1, 22.5, true, null, false, false, "pesado demais"),
                gravada("supino", "Supino reto barra", 7L, 2, null, false, 10, false, null),
                gravada("barra", "Barra fixa", 3L, 1, 0.0, false, 8, true, null),
                gravada("supino", "Supino reto barra", 7L, 3, 100.0, false, 5, false, true),
                gravada("pull down", null, null, 1, 30.0, false, 12, false, null),
                gravada("pull down", null, null, 2, 30.0, false, 12, false, null),
                gravada("pulldown", null, null, 1, 25.0, false, 12, false, null)),
                List.of(), false);
        String esperadoDetalhes = String.join("\n",
                Textos.REGISTRADO_EM.formatted("09/10/2026"),
                "",
                "Supino reto barra",
                "  1ª: ~22,5 kg × — " + Textos.SEM_FALHA,
                "  2ª: — × 10",
                "Barra fixa",
                "  1ª: 0 kg × ~8",
                "Supino reto barra",
                "  3ª: 100 kg × 5 " + Textos.FALHA,
                Textos.NAO_RECONHECI.formatted("pull down"),
                "  1ª: 30 kg × 12",
                "  2ª: 30 kg × 12",
                Textos.NAO_RECONHECI.formatted("pulldown"),
                "  1ª: 25 kg × 12");
        String enviadaDetalhes = responder(301, detalhes);
        System.out.println("        confirmação montada (—, ~, 22,5, sem falha, grupos):");
        System.out.println(recuar(enviadaDetalhes));
        conferir("—, ~, 22,5, (sem falha), 0 kg, 100 kg, grupos", esperadoDetalhes.equals(enviadaDetalhes));
        conferir("percepção não aparece", enviadaDetalhes != null && !enviadaDetalhes.contains("pesado"));
        conferir("sem dúvidas: sem o bloco de dúvidas",
                enviadaDetalhes != null && !enviadaDetalhes.contains(Textos.PONTOS_NAO_CLAROS));
        conferir("sem observação: sem 'anotei a observação'",
                enviadaDetalhes != null && !enviadaDetalhes.contains(Textos.OBSERVACAO_ANOTADA));

        // Mensagem só com observação: REGISTRADO com lista vazia.
        String soObservacao = responder(302, ResultadoRegistro.registrado(LocalDate.of(2026, 10, 9),
                List.of(), List.of(), true));
        conferir("só observação: data + 'anotei a observação'", (Textos.REGISTRADO_EM.formatted("09/10/2026")
                + "\n\n" + Textos.OBSERVACAO_ANOTADA).equals(soObservacao));
    }

    // --- Seção 5: demais situações -------------------------------------------------------

    private static void situacoes() throws InterruptedException {
        titulo("Demais situações (seção 5)");
        LocalDate dia = LocalDate.of(2026, 10, 9);

        Cenario jaGravada = cenarioComResposta(400, ResultadoRegistro.jaGravada(dia));
        conferir("JA_GRAVADA: silêncio", jaGravada.telegram.tentativasDeEnvio == 0);
        conferir("JA_GRAVADA: avançou", jaGravada.loop.offset() == 401);
        conferirLinha(jaGravada, 400, "| texto | JA_GRAVADA |");

        String duvida = segredo("você perguntou da remada do Fulano?");
        conferir("SEM_SERIES com dúvidas: frase + bloco de dúvidas",
                (Textos.SEM_SERIES + "\n\n" + Textos.PONTOS_NAO_CLAROS + "\n- " + duvida)
                        .equals(responder(401, ResultadoRegistro.semSeries(dia, List.of(duvida)))));
        conferir("SEM_SERIES sem dúvidas: só a frase",
                Textos.SEM_SERIES.equals(responder(402, ResultadoRegistro.semSeries(dia, List.of()))));

        Cenario outra = cenarioComResposta(403, ResultadoRegistro.naoRegistrado(dia, TipoFalha.OUTRA,
                "extração: A API da Anthropic respondeu HTTP 401 (authentication_error)."));
        conferir("NAO_REGISTRADO OUTRA: 'reenvie com outras palavras'",
                List.of(Textos.NAO_REGISTRADO).equals(outra.telegram.enviadas));
        conferirLinha(outra, 403, "| NAO_REGISTRADO: extração: A API da Anthropic respondeu HTTP 401");

        conferir("NAO_REGISTRADO OBSERVACAO_CHEIA: mensagem própria",
                Textos.OBSERVACAO_CHEIA.equals(responder(404, ResultadoRegistro.naoRegistrado(dia,
                        TipoFalha.OBSERVACAO_CHEIA, "gravação: observação do dia acumulada (ORA-12899)"))));
    }

    // --- Seção 2: SQLException antes de a mensagem estar salva ----------------------------

    private static void errosNoBanco() throws InterruptedException {
        titulo("Erros no banco (seção 2)");

        // Recusa no registrar: avança e responde, sem esperar.
        Cenario recusa = new Cenario();
        recusa.telegram.updates.add(texto(500, ID_TELEGRAM_ATIVO, NOME_ATIVO, "relato recusado do Fulano"));
        recusa.registrador.respostas.add(() -> {
            throw new SQLIntegrityConstraintViolationException("ORA-02291: integrity constraint", "23000", 2291);
        });
        recusa.rodarAteEsvaziar();
        conferir("recusa: avançou", recusa.loop.offset() == 501);
        conferir("recusa: tentou 1 vez, sem esperar",
                recusa.registrador.chamadas == 1 && recusa.esperas.isEmpty());
        conferir("recusa: 'não consegui salvar'", List.of(Textos.NAO_CONSEGUI_SALVAR).equals(recusa.telegram.enviadas));
        conferirLinha(recusa, 500, "recusa do banco ao gravar a mensagem (ORA-02291); avançou");

        // Recusa na busca do remetente: avança, mas NÃO responde (ver relatório:
        // ainda não se sabe se o remetente está ATIVO).
        Cenario recusaNaBusca = new Cenario();
        recusaNaBusca.erroNaBusca = new SQLSyntaxErrorException("ORA-00942: table or view does not exist",
                "42000", 942);
        recusaNaBusca.telegram.updates.add(texto(510, ID_TELEGRAM_ATIVO, NOME_ATIVO, "relato do Fulano 510"));
        recusaNaBusca.rodarAteEsvaziar();
        conferir("recusa na busca: avançou, sem chamar o registrar",
                recusaNaBusca.loop.offset() == 511 && recusaNaBusca.registrador.chamadas == 0);
        conferir("recusa na busca: sem resposta (remetente não confirmado)",
                recusaNaBusca.telegram.tentativasDeEnvio == 0);
        conferirLinha(recusaNaBusca, 510, "recusa do banco ao buscar o remetente (ORA-00942); avançou");

        // Conexão caída o tempo todo: 10 tentativas, esperas 5-10-20-40-60..., desiste.
        Cenario conexao = new Cenario();
        conexao.telegram.updates.add(texto(520, ID_TELEGRAM_ATIVO, NOME_ATIVO, "relato do Fulano sem banco"));
        conexao.registrador.padrao = () -> {
            throw new SQLRecoverableException("IO Error", "08006", 17002);
        };
        conexao.loop.umaVolta();
        conferir("conexão: 1ª falha não avança o offset", conexao.loop.offset() == 0);
        conferir("conexão: 1ª falha espera 5 s", List.of(Duration.ofSeconds(5)).equals(conexao.esperas));
        conferir("conexão: sem resposta enquanto tenta", conexao.telegram.tentativasDeEnvio == 0);
        conexao.rodarAteEsvaziar();
        conferir("conexão: 10 tentativas no total", conexao.registrador.chamadas == 10);
        conferir("conexão: esperas 5, 10, 20, 40, 60, 60, 60, 60, 60 s", segundos(conexao.esperas)
                .equals(List.of(5L, 10L, 20L, 40L, 60L, 60L, 60L, 60L, 60L)));
        conferir("conexão: na 10ª, avançou", conexao.loop.offset() == 521);
        conferir("conexão: na 10ª, respondeu uma vez",
                List.of(Textos.NAO_CONSEGUI_SALVAR_DEPOIS_DE_TENTAR).equals(conexao.telegram.enviadas));
        conferir("conexão: 10 linhas de log para o update", contarLinhas(conexao, 520) == 10);
        conferirLinha(conexao, 520, "(ORA-17002), tentativa 10 de 10; desistiu e avançou");

        // Banco volta na 3ª tentativa; o update de trás espera o da frente; o
        // contador zera para o próximo update que falhar.
        Cenario volta = new Cenario();
        volta.telegram.updates.add(texto(530, ID_TELEGRAM_ATIVO, NOME_ATIVO, "relato do Fulano 530"));
        volta.telegram.updates.add(texto(531, ID_TELEGRAM_ATIVO, NOME_ATIVO, "relato do Fulano 531"));
        LocalDate dia = LocalDate.of(2026, 10, 9);
        volta.registrador.respostas.add(() -> {
            throw new SQLRecoverableException("IO Error", "08006", 17002);
        });
        volta.registrador.respostas.add(() -> {
            throw new SQLRecoverableException("IO Error", "08006", 17002);
        });
        volta.registrador.respostas.add(() -> ResultadoRegistro.jaGravada(dia));
        volta.registrador.respostas.add(() -> {
            throw new SQLRecoverableException("IO Error", "08006", 17002);
        });
        volta.registrador.respostas.add(() -> ResultadoRegistro.jaGravada(dia));
        volta.rodarAteEsvaziar();
        conferir("banco volta: ordem 530, 530, 530, 531, 531 (o de trás espera)",
                volta.registrador.mensagens.equals(List.of(5530L, 5530L, 5530L, 5531L, 5531L)));
        conferir("banco volta: esperas 5, 10 e, no update seguinte, 5 de novo (contador zerou)",
                segundos(volta.esperas).equals(List.of(5L, 10L, 5L)));
        conferir("banco volta: avançou depois dos dois", volta.loop.offset() == 532);
    }

    // --- Seção 3: erro inesperado e interrupção --------------------------------------------

    private static void errosInesperados() throws InterruptedException {
        titulo("Erro inesperado e interrupção (seção 3)");

        Cenario npe = new Cenario();
        String segredoNaExcecao = segredo("texto do Fulano dentro da exceção");
        npe.telegram.updates.add(texto(600, ID_TELEGRAM_ATIVO, NOME_ATIVO, "relato do Fulano 600"));
        npe.registrador.respostas.add(() -> {
            throw new NullPointerException(segredoNaExcecao);
        });
        npe.rodarAteEsvaziar();
        conferir("NullPointerException: avançou", npe.loop.offset() == 601);
        conferir("NullPointerException: respondeu 'tive um erro'",
                List.of(Textos.ERRO_INESPERADO).equals(npe.telegram.enviadas));
        conferirLinha(npe, 600, "| erro inesperado (NullPointerException); avançou |");

        // JSON fora do esperado (mensagem sem "chat"): erro antes de saber quem é
        // o remetente -> avança, sem resposta.
        Cenario semChat = new Cenario();
        semChat.telegram.updates.add(json("""
                {"update_id": 610, "message": {"message_id": 5610, "date": %d,
                 "from": {"id": %d, "first_name": %s}, "text": %s}}
                """.formatted(DATA_ENVIO, ID_TELEGRAM_ATIVO, aspas(NOME_ATIVO),
                aspas(segredo("mensagem sem chat do Fulano")))));
        semChat.rodarAteEsvaziar();
        conferir("mensagem sem chat: avançou, sem resposta",
                semChat.loop.offset() == 611 && semChat.telegram.tentativasDeEnvio == 0);
        conferirLinha(semChat, 610, "erro inesperado (");

        // Interrupção: rodar() encerra, restaura a flag, não avança.
        Cenario parada = new Cenario();
        parada.telegram.updates.add(texto(620, ID_TELEGRAM_ATIVO, NOME_ATIVO, "relato do Fulano 620"));
        parada.registrador.respostas.add(() -> {
            throw new InterruptedException();
        });
        Encerramento fim = parada.loop.rodar();
        // Thread.interrupted() lê E limpa a flag, para não contaminar os próximos cenários.
        boolean flagRestaurada = Thread.interrupted();
        conferir("InterruptedException: rodar() encerra com INTERROMPIDO", fim == Encerramento.INTERROMPIDO);
        conferir("InterruptedException: flag de interrupção restaurada", flagRestaurada);
        conferir("InterruptedException: não avançou", parada.loop.offset() == 0);
        conferir("InterruptedException: sem resposta", parada.telegram.tentativasDeEnvio == 0);
    }

    // --- Seção 6: getUpdates ---------------------------------------------------------------

    private static void getUpdates() throws InterruptedException {
        titulo("getUpdates (seção 6 e ajuste de 09/10: 404, 429 e outros status)");

        Cenario conflito = new Cenario();
        conflito.telegram.falhasNoGetUpdates.add(new ErroTelegram(409));
        conferir("409: encerra com OUTRA_INSTANCIA", conflito.loop.rodar() == Encerramento.OUTRA_INSTANCIA);
        conferir("409: log diz que outra instância já está rodando",
                conflito.log.stream().anyMatch(l -> l.contains("outra instância já está rodando")));

        Cenario token = new Cenario();
        token.telegram.falhasNoGetUpdates.add(new ErroTelegram(401));
        conferir("401: encerra com TOKEN_INVALIDO", token.loop.rodar() == Encerramento.TOKEN_INVALIDO);

        Cenario rede = new Cenario();
        rede.telegram.falhasNoGetUpdates.add(new FalhaDeRede("ConnectException"));
        rede.telegram.falhasNoGetUpdates.add(new ErroTelegram(502));
        rede.telegram.updates.add(texto(700, ID_TELEGRAM_ATIVO, NOME_ATIVO, "relato do Fulano 700"));
        rede.registrador.respostas.add(() -> ResultadoRegistro.jaGravada(LocalDate.of(2026, 10, 9)));
        conferir("rede e 502: não encerra", rede.rodarAteEsvaziar() == null);
        conferir("rede e 502: espera 5 s cada", segundos(rede.esperas).equals(List.of(5L, 5L)));
        conferir("rede e 502: depois processa o update", rede.loop.offset() == 701);
        conferir("rede: linha de log com a classe da falha e a espera",
                rede.log.stream().anyMatch(l -> l.contains("| getUpdates | ConnectException | nova tentativa em 5 s")));

        Cenario malformado = new Cenario();
        malformado.telegram.falhasNoGetUpdates.add(new ErroTelegram(404));
        conferir("404: encerra com TOKEN_MALFORMADO", malformado.loop.rodar() == Encerramento.TOKEN_MALFORMADO);
        conferir("404: log diz 'token com formato inválido'",
                malformado.log.stream().anyMatch(l -> l.contains("token com formato inválido")));

        // 429 com e sem retry_after, um status qualquer (418) e um 5xx (503).
        Cenario esperas = new Cenario();
        esperas.telegram.falhasNoGetUpdates.add(new ErroTelegram(429, 7));
        esperas.telegram.falhasNoGetUpdates.add(new ErroTelegram(429, null));
        esperas.telegram.falhasNoGetUpdates.add(new ErroTelegram(418));
        esperas.telegram.falhasNoGetUpdates.add(new ErroTelegram(503));
        esperas.telegram.updates.add(texto(710, ID_TELEGRAM_ATIVO, NOME_ATIVO, "relato do Fulano 710"));
        esperas.registrador.respostas.add(() -> ResultadoRegistro.jaGravada(LocalDate.of(2026, 10, 9)));
        conferir("429/418/503: não encerra", esperas.rodarAteEsvaziar() == null);
        conferir("esperas: 429 com retry_after 7 -> 7 s; 429 sem o campo, 418 e 503 -> 5 s",
                segundos(esperas.esperas).equals(List.of(7L, 5L, 5L, 5L)));
        conferir("depois das esperas, processa o update", esperas.loop.offset() == 711);
        conferir("429: log com a espera pedida",
                esperas.log.stream().anyMatch(l -> l.contains("| getUpdates | HTTP 429 | nova tentativa em 7 s")));

        // Ajuste de 09/10: toda falha do getUpdates vira uma linha de log, com data
        // e hora, o status (ou a classe) e a espera; nunca a URL nem o token. A
        // falha de rede aqui vem "suja", com a URL e um token falso na mensagem,
        // como uma exceção do java.net.http poderia vir: o log só pode ter a classe.
        String tokenFalso = segredo("123456789:AAToken-falso-de-teste");
        Cenario repetido = new Cenario();
        repetido.telegram.falhasNoGetUpdates.addAll(List.of(
                new ErroTelegram(503), new ErroTelegram(503), new ErroTelegram(503),
                new ConnectException("Connection refused: https://api.telegram.org/bot" + tokenFalso + "/getUpdates")));
        repetido.telegram.updates.add(texto(720, ID_TELEGRAM_ATIVO, NOME_ATIVO, "relato do Fulano 720"));
        repetido.registrador.respostas.add(() -> ResultadoRegistro.jaGravada(LocalDate.of(2026, 10, 9)));
        conferir("503 x3 e rede: não encerra e depois processa o update",
                repetido.rodarAteEsvaziar() == null && repetido.loop.offset() == 721);
        conferir("503 x3: três linhas de log com data, hora, status e espera", repetido.log.stream()
                .filter(l -> l.equals("09/10 10:02:15 | getUpdates | HTTP 503 | nova tentativa em 5 s")).count() == 3);
        conferir("rede com mensagem suja: a linha traz só a classe e a espera", repetido.log.stream()
                .anyMatch(l -> l.equals("09/10 10:02:15 | getUpdates | ConnectException | nova tentativa em 5 s")));
        conferir("503 x3 e rede: 5 s em cada uma das 4 falhas",
                segundos(repetido.esperas).equals(List.of(5L, 5L, 5L, 5L)));
        conferir("503 x3 e rede: nenhuma linha contém a URL ou o token", repetido.log.stream()
                .noneMatch(l -> l.contains("api.telegram.org") || l.contains(tokenFalso)));

        // Leitura do retry_after em corpos no formato da Bot API (sem rede).
        conferir("corpo do 429 com parameters.retry_after 7 -> 7", Integer.valueOf(7).equals(
                ErroTelegram.deResposta(429, """
                        {"ok":false,"error_code":429,"description":"Too Many Requests: retry after 7",
                         "parameters":{"retry_after":7}}
                        """).retryAfter()));
        conferir("corpo do 429 sem parameters -> null (usa 5 s)", ErroTelegram.deResposta(429,
                "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests\"}").retryAfter() == null);
        conferir("corpo que não é JSON, ou vazio -> null",
                ErroTelegram.deResposta(502, "<html>Bad Gateway</html>").retryAfter() == null
                        && ErroTelegram.deResposta(502, "").retryAfter() == null);
        conferir("retry_after como texto (\"7\") -> null, não 7 nem 0", ErroTelegram.deResposta(429,
                "{\"ok\":false,\"parameters\":{\"retry_after\":\"7\"}}").retryAfter() == null);
        conferir("retry_after 0 ou negativo -> null",
                ErroTelegram.deResposta(429, "{\"parameters\":{\"retry_after\":0}}").retryAfter() == null
                        && ErroTelegram.deResposta(429, "{\"parameters\":{\"retry_after\":-3}}").retryAfter() == null);
    }

    // --- Seção 10: envio da resposta -------------------------------------------------------

    private static void envio() throws InterruptedException {
        titulo("Envio da resposta (seção 10)");
        LocalDate dia = LocalDate.of(2026, 10, 9);
        ResultadoRegistro naoRegistrado = ResultadoRegistro.naoRegistrado(dia, TipoFalha.OUTRA, "validação: x");

        Cenario duasFalhas = cenarioComFalhasNoEnvio(800, naoRegistrado, new ErroTelegram(500), new ErroTelegram(500));
        conferir("sendMessage falha 2x: 2 tentativas, 2 s entre elas",
                duasFalhas.telegram.tentativasDeEnvio == 2 && segundos(duasFalhas.esperas).equals(List.of(2L)));
        conferir("sendMessage falha 2x: avançou mesmo assim", duasFalhas.loop.offset() == 801);
        conferirLinha(duasFalhas, 800, "; resposta não enviada (HTTP 500) |");

        Cenario bloqueado = cenarioComFalhasNoEnvio(810, naoRegistrado, new ErroTelegram(403));
        conferir("403: sem nova tentativa, sem espera",
                bloqueado.telegram.tentativasDeEnvio == 1 && bloqueado.esperas.isEmpty());
        conferir("403: avançou", bloqueado.loop.offset() == 811);
        conferirLinha(bloqueado, 810, "; resposta não enviada (HTTP 403) |");

        Cenario umaFalha = cenarioComFalhasNoEnvio(820, naoRegistrado,
                new FalhaDeRede("HttpTimeoutException"));
        conferir("falha 1x e depois envia: chegou na 2ª tentativa",
                umaFalha.telegram.tentativasDeEnvio == 2 && umaFalha.telegram.enviadas.size() == 1);
        conferir("falha 1x e depois envia: log sem 'não enviada'",
                !linha(umaFalha, 820).contains("não enviada"));
    }

    // --- 6b, regra 2: comportamento por status do remetente --------------------------------

    private static void statusDoRemetente() throws InterruptedException {
        titulo("6b: comportamento por status do remetente (regra 2)");

        // Desconhecido sem /start: silêncio, nada gravado.
        Cenario desconhecido = new Cenario();
        desconhecido.telegram.updates.addAll(List.of(
                texto(1100, ID_TELEGRAM_DESCONHECIDO, NOME_NOVO, "oi, tudo bem? quero testar o bot"),
                figurinha(1101, ID_TELEGRAM_DESCONHECIDO, NOME_NOVO),
                edicao(1102, ID_TELEGRAM_DESCONHECIDO, NOME_NOVO, "/start"),
                texto(1103, ID_TELEGRAM_DESCONHECIDO, NOME_NOVO, "/aprovar " + ID_USUARIO_PENDENTE)));
        desconhecido.rodarAteEsvaziar();
        conferir("desconhecido (texto, figurinha, edição de /start, /aprovar): silêncio",
                desconhecido.telegram.tentativasDeEnvio == 0);
        conferir("desconhecido: nada cadastrado, registrado nem aprovado; TR_USUARIO não muda",
                desconhecido.nadaGravado() && desconhecido.status(ID_TELEGRAM_DESCONHECIDO) == null);
        conferir("desconhecido: avançou depois dos 4 (1104)", desconhecido.loop.offset() == 1104);
        conferirLinha(desconhecido, 1100, "| texto | ignorado: remetente não cadastrado |");
        conferirLinha(desconhecido, 1101, "| figurinha | ignorado: remetente não cadastrado |");
        conferirLinha(desconhecido, 1102, "| edição | ignorado: remetente não cadastrado |");
        conferirLinha(desconhecido, 1103, "| comando | ignorado: remetente não cadastrado |");

        // BLOQUEADO: silêncio total, inclusive no /start.
        Cenario bloqueado = new Cenario();
        bloqueado.telegram.updates.addAll(List.of(
                texto(1110, ID_TELEGRAM_BLOQUEADO, NOME_BLOQUEADO, "/start"),
                texto(1111, ID_TELEGRAM_BLOQUEADO, NOME_BLOQUEADO, "supino 3x10 do Deltrano bloqueado"),
                figurinha(1112, ID_TELEGRAM_BLOQUEADO, NOME_BLOQUEADO),
                edicao(1113, ID_TELEGRAM_BLOQUEADO, NOME_BLOQUEADO, "supino corrigido do Deltrano"),
                texto(1114, ID_TELEGRAM_BLOQUEADO, NOME_BLOQUEADO, "/aprovar " + ID_USUARIO_BLOQUEADO)));
        bloqueado.rodarAteEsvaziar();
        conferir("BLOQUEADO (/start, texto, figurinha, edição, /aprovar): silêncio total",
                bloqueado.telegram.tentativasDeEnvio == 0);
        conferir("BLOQUEADO: nada cadastrado, registrado nem aprovado; continua BLOQUEADO",
                bloqueado.nadaGravado() && "BLOQUEADO".equals(bloqueado.status(ID_TELEGRAM_BLOQUEADO)));
        conferir("BLOQUEADO: avançou depois dos 5 (1115)", bloqueado.loop.offset() == 1115);
        conferirLinha(bloqueado, 1110, "| comando | ignorado: remetente BLOQUEADO |");
        conferirLinha(bloqueado, 1111, "| texto | ignorado: remetente BLOQUEADO |");
        conferirLinha(bloqueado, 1113, "| edição | ignorado: remetente BLOQUEADO |");

        // PENDENTE: "aguardando aprovação" para tudo, menos edição (silêncio).
        Cenario pendente = new Cenario();
        pendente.telegram.updates.addAll(List.of(
                texto(1120, ID_TELEGRAM_PENDENTE, NOME_PENDENTE, "supino 3x10 com 20 do Beltrano"),
                figurinha(1121, ID_TELEGRAM_PENDENTE, NOME_PENDENTE),
                texto(1122, ID_TELEGRAM_PENDENTE, NOME_PENDENTE, "/aprovar " + ID_USUARIO_PENDENTE),
                edicao(1123, ID_TELEGRAM_PENDENTE, NOME_PENDENTE, "supino corrigido do Beltrano"),
                texto(1124, ID_TELEGRAM_PENDENTE, NOME_PENDENTE, "/xyz do Beltrano")));
        pendente.rodarAteEsvaziar();
        conferir("PENDENTE: 'aguardando aprovação' para texto, figurinha, /aprovar e /xyz; edição em silêncio",
                pendente.telegram.enviadas.equals(Collections.nCopies(4, Textos.AGUARDANDO_APROVACAO)));
        conferir("PENDENTE: todas as respostas no chat dele", todosPara(pendente, ID_TELEGRAM_PENDENTE));
        conferir("PENDENTE: nada registrado (TR_MENSAGEM não muda) nem aprovado; continua PENDENTE",
                pendente.nadaGravado() && "PENDENTE".equals(pendente.status(ID_TELEGRAM_PENDENTE)));
        conferirLinha(pendente, 1120, "| texto | ignorado: remetente PENDENTE |");
        conferirLinha(pendente, 1121, "| figurinha | ignorado: remetente PENDENTE |");
        conferirLinha(pendente, 1122, "| comando | ignorado: remetente PENDENTE |");
        conferirLinha(pendente, 1123, "| edição | ignorado: edição de remetente PENDENTE |");

        // PENDENTE manda /start de novo: "aguardando" e o admin é REAVISADO.
        Cenario reaviso = new Cenario();
        reaviso.telegram.updates.add(texto(1130, ID_TELEGRAM_PENDENTE, NOME_PENDENTE, "/start"));
        reaviso.rodarAteEsvaziar();
        conferir("PENDENTE /start: 'aguardando' para ele e aviso (nome + ID_USUARIO) para o admin",
                reaviso.telegram.enviadas.equals(List.of(Textos.AGUARDANDO_APROVACAO,
                        Textos.AVISO_ADMIN.formatted(NOME_PENDENTE, ID_USUARIO_PENDENTE))));
        conferir("PENDENTE /start: destinos = chat dele, depois chat do admin",
                reaviso.telegram.destinos.equals(List.of(ID_TELEGRAM_PENDENTE, ID_TELEGRAM_ADMIN)));
        conferir("PENDENTE /start: não cadastra de novo", reaviso.nadaGravado());
        conferirLinha(reaviso, 1130, "| comando | start: já PENDENTE, admin reavisado |");

        // ATIVO manda /start (com e sem @Bot; o admin também é ATIVO).
        Cenario ativo = new Cenario();
        ativo.telegram.updates.addAll(List.of(
                texto(1140, ID_TELEGRAM_ATIVO, NOME_ATIVO, "/start"),
                texto(1141, ID_TELEGRAM_ATIVO, NOME_ATIVO, "/start@TreinoBot"),
                texto(1142, ID_TELEGRAM_ADMIN, NOME_ADMIN, "/start")));
        ativo.rodarAteEsvaziar();
        conferir("ATIVO /start, /start@TreinoBot e o admin: 'você já está cadastrado'",
                ativo.telegram.enviadas.equals(Collections.nCopies(3, Textos.JA_CADASTRADO)));
        conferir("ATIVO /start: cada resposta no chat de quem mandou", ativo.telegram.destinos
                .equals(List.of(ID_TELEGRAM_ATIVO, ID_TELEGRAM_ATIVO, ID_TELEGRAM_ADMIN)));
        conferir("ATIVO /start: nada cadastrado nem registrado", ativo.nadaGravado());
        conferirLinha(ativo, 1141, "| comando | start: já cadastrado |");
    }

    // --- 6b, regras 1, 3 e 4: /start de quem não está cadastrado ------------------------------

    private static void cadastroPeloStart() throws InterruptedException {
        titulo("6b: /start de desconhecido (regras 1, 3 e 4)");

        Cenario c = new Cenario();
        c.telegram.updates.add(texto(1200, ID_TELEGRAM_DESCONHECIDO, NOME_NOVO, "/start"));
        c.rodarAteEsvaziar();
        conferir("cadastrar chamado 1 vez, com o from.id",
                c.cadastro.cadastros == 1 && c.cadastro.idTelegram == ID_TELEGRAM_DESCONHECIDO);
        conferir("NOME = first_name, como veio", NOME_NOVO.equals(c.cadastro.nome));
        conferir("DATA_CADASTRO = date de envio em São Paulo (09/10 10:02:15)",
                LocalDateTime.of(2026, 10, 9, 10, 2, 15).equals(c.cadastro.dataCadastro));
        conferir("ficou PENDENTE", "PENDENTE".equals(c.status(ID_TELEGRAM_DESCONHECIDO)));
        String aviso = Textos.AVISO_ADMIN.formatted(NOME_NOVO, PRIMEIRO_ID_GERADO);
        conferir("enviou boas-vindas e aviso ao admin, nessa ordem",
                c.telegram.enviadas.equals(List.of(Textos.BOAS_VINDAS, aviso)));
        conferir("boas-vindas no chat de quem pediu; aviso no chat do admin",
                c.telegram.destinos.equals(List.of(ID_TELEGRAM_DESCONHECIDO, ID_TELEGRAM_ADMIN)));
        conferir("aviso leva o ID_USUARIO gerado, nunca o from.id",
                aviso.contains("/aprovar " + PRIMEIRO_ID_GERADO)
                        && !aviso.contains(String.valueOf(ID_TELEGRAM_DESCONHECIDO)));
        conferir("/start não vai para o registrar (TR_MENSAGEM não muda)", c.registrador.chamadas == 0);
        conferir("avançou (1201)", c.loop.offset() == 1201);
        conferirLinha(c, 1200, "| comando | start: cadastrado PENDENTE |");
        System.out.println("        boas-vindas (conferir os 4 pontos obrigatórios):");
        System.out.println(recuar(Textos.BOAS_VINDAS));

        // Comando pelo primeiro token: "@nome_do_bot" tirado; parâmetro do link
        // t.me/bot?start=site ignorado.
        Cenario sufixo = new Cenario();
        sufixo.telegram.updates.add(texto(1210, ID_TELEGRAM_DESCONHECIDO, NOME_NOVO, "/start@TreinoBot"));
        sufixo.rodarAteEsvaziar();
        conferir("/start@TreinoBot: cadastra",
                sufixo.cadastro.cadastros == 1 && "PENDENTE".equals(sufixo.status(ID_TELEGRAM_DESCONHECIDO)));
        Cenario parametro = new Cenario();
        parametro.telegram.updates.add(texto(1211, ID_TELEGRAM_DESCONHECIDO, NOME_NOVO, "/start site"));
        parametro.rodarAteEsvaziar();
        conferir("/start site (link do site estático): cadastra",
                parametro.cadastro.cadastros == 1 && "PENDENTE".equals(parametro.status(ID_TELEGRAM_DESCONHECIDO)));

        // Limite do NOME em caracteres, não em unidades UTF-16: 100 emojis (200
        // unidades) cabem no VARCHAR2(100 CHAR); 101 não.
        String nome100 = segredo("💪".repeat(100));
        Cenario cem = new Cenario();
        cem.telegram.updates.add(texto(1220, ID_TELEGRAM_DESCONHECIDO, nome100, "/start"));
        cem.rodarAteEsvaziar();
        conferir("first_name com 100 emojis: cadastrado inteiro (sem truncar)",
                cem.cadastro.cadastros == 1 && nome100.equals(cem.cadastro.nome));

        // first_name fora do contrato: erro inesperado, avança, SEM resposta, nada cadastrado.
        String nome101 = segredo("💪".repeat(101));
        Cenario invalidos = new Cenario();
        invalidos.telegram.updates.addAll(List.of(
                startComFrom(1230, ""),
                startComFrom(1231, ", \"first_name\": \"   \""),
                startComFrom(1232, ", \"first_name\": \"\""),
                startComFrom(1233, ", \"first_name\": null"),
                startComFrom(1234, ", \"first_name\": 42"),
                texto(1235, ID_TELEGRAM_DESCONHECIDO, nome101, "/start")));
        invalidos.rodarAteEsvaziar();
        conferir("first_name ausente, branco, vazio, null, número ou com 101 caracteres: avançou (1236)",
                invalidos.loop.offset() == 1236);
        conferir("first_name inválido: sem resposta (nem a quem pediu, nem ao admin)",
                invalidos.telegram.tentativasDeEnvio == 0);
        conferir("first_name inválido: nada cadastrado (nunca truncado)", invalidos.cadastro.cadastros == 0);
        conferirLinha(invalidos, 1230, "| comando | erro inesperado (");
        for (long updateId = 1231; updateId <= 1235; updateId++) {
            conferirLinha(invalidos, updateId, "| comando | erro inesperado (IllegalArgumentException); avançou |");
        }

        // Conexão caída no cadastro: não avança, espera, e na volta do banco cadastra.
        Cenario conexao = new Cenario();
        conexao.cadastro.falhasNoCadastro.add(new SQLRecoverableException("IO Error", "08006", 17002));
        conexao.telegram.updates.add(texto(1240, ID_TELEGRAM_DESCONHECIDO, NOME_NOVO, "/start"));
        conexao.loop.umaVolta();
        conferir("conexão no cadastro: não avançou", conexao.loop.offset() == 0);
        conferir("conexão no cadastro: espera 5 s", segundos(conexao.esperas).equals(List.of(5L)));
        conferir("conexão no cadastro: sem resposta nem aviso enquanto tenta",
                conexao.telegram.tentativasDeEnvio == 0);
        conferirLinha(conexao, 1240, "erro no banco ao cadastrar (ORA-17002), tentativa 1 de 10; repete em 5 s");
        conexao.rodarAteEsvaziar();
        conferir("conexão no cadastro: na 2ª tentativa cadastrou e avançou", conexao.cadastro.cadastros == 2
                && "PENDENTE".equals(conexao.status(ID_TELEGRAM_DESCONHECIDO)) && conexao.loop.offset() == 1241);
        conferir("conexão no cadastro: boas-vindas e aviso uma vez só", conexao.telegram.enviadas.equals(
                List.of(Textos.BOAS_VINDAS, Textos.AVISO_ADMIN.formatted(NOME_NOVO, PRIMEIRO_ID_GERADO))));

        // Recusa (ex.: UQ_TR_USUARIO_ID_TELEGRAM): avança. É o caminho do /start,
        // então quem pediu recebe o aviso de erro; o admin, nada (não houve cadastro).
        Cenario recusa = new Cenario();
        recusa.cadastro.falhasNoCadastro.add(
                new SQLIntegrityConstraintViolationException("ORA-00001: unique constraint", "23000", 1));
        recusa.telegram.updates.add(texto(1250, ID_TELEGRAM_DESCONHECIDO, NOME_NOVO, "/start"));
        recusa.rodarAteEsvaziar();
        conferir("recusa no cadastro: avançou sem esperar", recusa.loop.offset() == 1251
                && recusa.esperas.isEmpty() && recusa.cadastro.cadastros == 1);
        conferir("recusa no cadastro: 'não consegui salvar' para quem pediu; nada para o admin",
                recusa.telegram.enviadas.equals(List.of(Textos.NAO_CONSEGUI_SALVAR))
                        && recusa.telegram.destinos.equals(List.of(ID_TELEGRAM_DESCONHECIDO)));
        conferir("recusa no cadastro: ninguém cadastrado", recusa.status(ID_TELEGRAM_DESCONHECIDO) == null);
        conferirLinha(recusa, 1250, "recusa do banco ao cadastrar (ORA-00001); avançou");

        // Aviso ao admin falha: regra de envio da 6a (2 tentativas, 2 s entre elas),
        // depois desiste e avança; a linha de log registra.
        Cenario avisoFalha = new Cenario();
        avisoFalha.telegram.chatsComFalha.put(ID_TELEGRAM_ADMIN, new ErroTelegram(500));
        avisoFalha.telegram.updates.add(texto(1260, ID_TELEGRAM_DESCONHECIDO, NOME_NOVO, "/start"));
        avisoFalha.rodarAteEsvaziar();
        conferir("aviso ao admin falha: boas-vindas chegou; 1 + 2 tentativas; espera 2 s",
                avisoFalha.telegram.enviadas.equals(List.of(Textos.BOAS_VINDAS))
                        && avisoFalha.telegram.tentativasDeEnvio == 3
                        && segundos(avisoFalha.esperas).equals(List.of(2L)));
        conferir("aviso ao admin falha: cadastrado mesmo assim, e avançou",
                "PENDENTE".equals(avisoFalha.status(ID_TELEGRAM_DESCONHECIDO)) && avisoFalha.loop.offset() == 1261);
        conferirLinha(avisoFalha, 1260,
                "| comando | start: cadastrado PENDENTE; aviso ao admin não enviado (HTTP 500) |");
    }

    // --- 6b, regra 5: /aprovar ----------------------------------------------------------------

    private static void aprovar() throws InterruptedException {
        titulo("6b: /aprovar (regra 5)");

        // De quem não é admin (ATIVO): a mesma resposta de um comando que não existe.
        Cenario naoAdmin = new Cenario();
        naoAdmin.telegram.updates.addAll(List.of(
                texto(1300, ID_TELEGRAM_ATIVO, NOME_ATIVO, "/aprovar " + ID_USUARIO_PENDENTE),
                texto(1301, ID_TELEGRAM_ATIVO, NOME_ATIVO, "/aprovar@TreinoBot " + ID_USUARIO_PENDENTE)));
        naoAdmin.rodarAteEsvaziar();
        conferir("não admin: 'comando não reconhecido', igual a um comando que não existe",
                naoAdmin.telegram.enviadas.equals(Collections.nCopies(2, Textos.COMANDO_NAO_RECONHECIDO)));
        conferir("não admin: nada aprovado; continua PENDENTE",
                naoAdmin.cadastro.aprovacoes == 0 && "PENDENTE".equals(naoAdmin.status(ID_TELEGRAM_PENDENTE)));
        conferirLinha(naoAdmin, 1300, "| comando | aprovar: remetente não é o admin |");

        // Argumento inválido: uso, sem ir ao banco.
        List<String> invalidos = List.of("/aprovar", "/aprovar abc", "/aprovar 0", "/aprovar -5",
                "/aprovar 12 34", "/aprovar 3.0", "/aprovar 1234567890123456789");
        Cenario uso = new Cenario();
        for (int i = 0; i < invalidos.size(); i++) {
            uso.telegram.updates.add(texto(1310 + i, ID_TELEGRAM_ADMIN, NOME_ADMIN, invalidos.get(i)));
        }
        uso.rodarAteEsvaziar();
        conferir("admin, /aprovar sem argumento, abc, 0, -5, '12 34', 3.0, 19 dígitos: 'uso: /aprovar <id>'",
                uso.telegram.enviadas.equals(Collections.nCopies(invalidos.size(), Textos.USO_APROVAR)));
        conferir("uso: respostas no chat do admin", todosPara(uso, ID_TELEGRAM_ADMIN));
        conferir("uso: o service não foi chamado", uso.cadastro.aprovacoes == 0);
        conferirLinha(uso, 1311, "| comando | aprovar: uso |");

        // Inexistente, já ativo, bloqueado: resposta própria, nenhum status muda.
        Cenario respostas = new Cenario();
        respostas.telegram.updates.addAll(List.of(
                texto(1320, ID_TELEGRAM_ADMIN, NOME_ADMIN, "/aprovar " + ID_USUARIO_INEXISTENTE),
                texto(1321, ID_TELEGRAM_ADMIN, NOME_ADMIN, "/aprovar " + ID_USUARIO_ATIVO),
                texto(1322, ID_TELEGRAM_ADMIN, NOME_ADMIN, "/aprovar " + ID_USUARIO_BLOQUEADO)));
        respostas.rodarAteEsvaziar();
        conferir("inexistente, já ativo, bloqueado: as três respostas, na ordem", respostas.telegram.enviadas
                .equals(List.of(Textos.ID_INEXISTENTE, Textos.JA_ATIVO, Textos.USUARIO_BLOQUEADO)));
        conferir("o service recebeu o ID_USUARIO do argumento", respostas.cadastro.idsAprovados
                .equals(List.of(ID_USUARIO_INEXISTENTE, ID_USUARIO_ATIVO, ID_USUARIO_BLOQUEADO)));
        conferir("ATIVO continua ATIVO; BLOQUEADO continua BLOQUEADO (sem UPDATE)",
                "ATIVO".equals(respostas.status(ID_TELEGRAM_ATIVO))
                        && "BLOQUEADO".equals(respostas.status(ID_TELEGRAM_BLOQUEADO)));
        conferirLinha(respostas, 1320, "| comando | aprovar: id inexistente |");
        conferirLinha(respostas, 1321, "| comando | aprovar: já ativo |");
        conferirLinha(respostas, 1322, "| comando | aprovar: bloqueado |");

        // PENDENTE -> ATIVO (com @Bot); o aprovado recebe o aviso; de novo -> já ativo.
        Cenario aprovacao = new Cenario();
        aprovacao.telegram.updates.addAll(List.of(
                texto(1330, ID_TELEGRAM_ADMIN, NOME_ADMIN, "/aprovar@TreinoBot " + ID_USUARIO_PENDENTE),
                texto(1331, ID_TELEGRAM_ADMIN, NOME_ADMIN, "/aprovar " + ID_USUARIO_PENDENTE)));
        aprovacao.rodarAteEsvaziar();
        conferir("/aprovar@TreinoBot <id do PENDENTE>: ficou ATIVO",
                "ATIVO".equals(aprovacao.status(ID_TELEGRAM_PENDENTE)));
        conferir("'aprovado: <nome>' ao admin, 'acesso liberado' ao aprovado; de novo: 'já ativo'",
                aprovacao.telegram.enviadas.equals(List.of(Textos.APROVADO.formatted(NOME_PENDENTE),
                        Textos.ACESSO_LIBERADO, Textos.JA_ATIVO)));
        conferir("destinos: admin, aprovado, admin", aprovacao.telegram.destinos
                .equals(List.of(ID_TELEGRAM_ADMIN, ID_TELEGRAM_PENDENTE, ID_TELEGRAM_ADMIN)));
        conferirLinha(aprovacao, 1330, "| comando | aprovar: aprovado |");
        conferirLinha(aprovacao, 1331, "| comando | aprovar: já ativo |");

        // O aprovado bloqueou o bot (403): o admin já recebeu "aprovado"; o log registra.
        Cenario bloqueouBot = new Cenario();
        bloqueouBot.telegram.chatsComFalha.put(ID_TELEGRAM_PENDENTE, new ErroTelegram(403));
        bloqueouBot.telegram.updates.add(texto(1340, ID_TELEGRAM_ADMIN, NOME_ADMIN, "/aprovar " + ID_USUARIO_PENDENTE));
        bloqueouBot.rodarAteEsvaziar();
        conferir("aviso ao aprovado com 403: admin recebeu 'aprovado'; 403 sem nova tentativa",
                bloqueouBot.telegram.enviadas.equals(List.of(Textos.APROVADO.formatted(NOME_PENDENTE)))
                        && bloqueouBot.telegram.tentativasDeEnvio == 2 && bloqueouBot.esperas.isEmpty());
        conferir("aviso ao aprovado com 403: aprovado mesmo assim, e avançou",
                "ATIVO".equals(bloqueouBot.status(ID_TELEGRAM_PENDENTE)) && bloqueouBot.loop.offset() == 1341);
        conferirLinha(bloqueouBot, 1340, "| aprovar: aprovado; aviso ao aprovado não enviado (HTTP 403) |");

        // Conexão caída no /aprovar: não avança, espera; na volta, aprova.
        Cenario conexao = new Cenario();
        conexao.cadastro.falhasNaAprovacao.add(new SQLRecoverableException("IO Error", "08006", 17002));
        conexao.telegram.updates.add(texto(1350, ID_TELEGRAM_ADMIN, NOME_ADMIN, "/aprovar " + ID_USUARIO_PENDENTE));
        conexao.loop.umaVolta();
        conferir("conexão no /aprovar: não avançou, espera 5 s, sem resposta", conexao.loop.offset() == 0
                && segundos(conexao.esperas).equals(List.of(5L)) && conexao.telegram.tentativasDeEnvio == 0);
        conferirLinha(conexao, 1350, "erro no banco ao aprovar (ORA-17002), tentativa 1 de 10; repete em 5 s");
        conexao.rodarAteEsvaziar();
        conferir("conexão no /aprovar: na 2ª tentativa aprovou e avançou", conexao.cadastro.aprovacoes == 2
                && "ATIVO".equals(conexao.status(ID_TELEGRAM_PENDENTE)) && conexao.loop.offset() == 1351
                && conexao.telegram.enviadas.equals(List.of(Textos.APROVADO.formatted(NOME_PENDENTE),
                        Textos.ACESSO_LIBERADO)));

        // Recusa no /aprovar: avança e avisa o admin; nada mudou.
        Cenario recusa = new Cenario();
        recusa.cadastro.falhasNaAprovacao.add(
                new SQLSyntaxErrorException("ORA-00942: table or view does not exist", "42000", 942));
        recusa.telegram.updates.add(texto(1360, ID_TELEGRAM_ADMIN, NOME_ADMIN, "/aprovar " + ID_USUARIO_PENDENTE));
        recusa.rodarAteEsvaziar();
        conferir("recusa no /aprovar: avançou sem esperar; 'não consegui salvar' ao admin; continua PENDENTE",
                recusa.loop.offset() == 1361 && recusa.esperas.isEmpty()
                        && recusa.telegram.enviadas.equals(List.of(Textos.NAO_CONSEGUI_SALVAR))
                        && todosPara(recusa, ID_TELEGRAM_ADMIN)
                        && "PENDENTE".equals(recusa.status(ID_TELEGRAM_PENDENTE)));
        conferirLinha(recusa, 1360, "recusa do banco ao aprovar (ORA-00942); avançou");
    }

    // --- 6b: o fluxo do critério de aceite 2, sem celular ----------------------------------

    private static void fluxoCompleto() throws InterruptedException {
        titulo("6b: fluxo completo (critério de aceite 2a a 2h, com falsos)");
        Cenario c = new Cenario();
        long idDela = PRIMEIRO_ID_GERADO;
        String treino = "supino 3x10 com 20 da Sicrana";
        c.registrador.respostas.add(() -> ResultadoRegistro.registrado(LocalDate.of(2026, 10, 9),
                List.of(gravada("supino", "Supino reto barra", 7L, 1, 20.0, false, 10, false, null)), List.of(),
                false));
        c.telegram.updates.addAll(List.of(
                texto(1400, ID_TELEGRAM_DESCONHECIDO, NOME_NOVO, "oi, posso usar o bot?"),     // a
                texto(1401, ID_TELEGRAM_DESCONHECIDO, NOME_NOVO, "/start"),                     // b
                texto(1402, ID_TELEGRAM_DESCONHECIDO, NOME_NOVO, treino),                       // c
                texto(1403, ID_TELEGRAM_DESCONHECIDO, NOME_NOVO, "/aprovar 1"),                 // d (ainda PENDENTE)
                texto(1404, ID_TELEGRAM_ADMIN, NOME_ADMIN, "/aprovar abc"),                     // e
                texto(1405, ID_TELEGRAM_ADMIN, NOME_ADMIN, "/aprovar " + ID_USUARIO_INEXISTENTE), // e
                texto(1406, ID_TELEGRAM_ADMIN, NOME_ADMIN, "/aprovar " + idDela),               // f
                texto(1407, ID_TELEGRAM_ADMIN, NOME_ADMIN, "/aprovar " + idDela),               // f, de novo
                texto(1408, ID_TELEGRAM_DESCONHECIDO, NOME_NOVO, "/aprovar 1"),                 // d, já ATIVA
                texto(1409, ID_TELEGRAM_DESCONHECIDO, NOME_NOVO, treino + " (de novo)")));      // g
        c.rodarAteEsvaziar();

        List<String> esperadas = List.of(
                Textos.BOAS_VINDAS, Textos.AVISO_ADMIN.formatted(NOME_NOVO, idDela),            // b
                Textos.AGUARDANDO_APROVACAO,                                                   // c
                Textos.AGUARDANDO_APROVACAO,                                                   // d
                Textos.USO_APROVAR, Textos.ID_INEXISTENTE,                                     // e
                Textos.APROVADO.formatted(NOME_NOVO), Textos.ACESSO_LIBERADO, Textos.JA_ATIVO, // f
                Textos.COMANDO_NAO_RECONHECIDO);                                               // d, já ATIVA
        List<Long> destinos = List.of(ID_TELEGRAM_DESCONHECIDO, ID_TELEGRAM_ADMIN, ID_TELEGRAM_DESCONHECIDO,
                ID_TELEGRAM_DESCONHECIDO, ID_TELEGRAM_ADMIN, ID_TELEGRAM_ADMIN, ID_TELEGRAM_ADMIN,
                ID_TELEGRAM_DESCONHECIDO, ID_TELEGRAM_ADMIN, ID_TELEGRAM_DESCONHECIDO, ID_TELEGRAM_DESCONHECIDO);
        List<String> enviadas = c.telegram.enviadas;
        conferir("a-f: respostas e avisos na ordem (11 envios)",
                enviadas.size() == 11 && enviadas.subList(0, 10).equals(esperadas));
        conferir("a-g: cada envio no chat certo (dela ou do admin)", c.telegram.destinos.equals(destinos));
        conferir("a: 'oi' antes do /start não cadastrou (1 cadastro só, no b)", c.cadastro.cadastros == 1);
        conferir("c: o treino de quem está PENDENTE não foi registrado (registrar só no g)",
                c.registrador.chamadas == 1);
        conferir("g: confirmação com a data para ela",
                enviadas.size() == 11 && enviadas.get(10).startsWith(Textos.REGISTRADO_EM.formatted("09/10/2026")));
        conferir("g: registrado com o ID_USUARIO DELA, não o do admin",
                c.registrador.idUsuario == idDela && (treino + " (de novo)").equals(c.registrador.texto));

        // h: o Vitor bloqueia pelo SQL Developer (aqui, direto no mapa) -> silêncio.
        Usuario dela = c.usuarios.get(ID_TELEGRAM_DESCONHECIDO);
        c.usuarios.put(ID_TELEGRAM_DESCONHECIDO, new Usuario(dela.idUsuario(), dela.idTelegram(), dela.nome(),
                "BLOQUEADO", dela.dataCadastro()));
        int enviosAntes = c.telegram.tentativasDeEnvio;
        c.telegram.updates.addAll(List.of(
                texto(1410, ID_TELEGRAM_DESCONHECIDO, NOME_NOVO, "outro treino da Sicrana"),
                texto(1411, ID_TELEGRAM_DESCONHECIDO, NOME_NOVO, "/start")));
        c.rodarAteEsvaziar();
        conferir("h: BLOQUEADA -> silêncio; nada registrado nem cadastrado",
                c.telegram.tentativasDeEnvio == enviosAntes && c.registrador.chamadas == 1
                        && c.cadastro.cadastros == 1 && c.loop.offset() == 1412);
    }

    // --- Seção 8: nada sensível no log ----------------------------------------------------

    private static void higieneDoLog() {
        titulo("Log sem dado de usuário (seção 8)");
        conferir("houve log para conferir (" + LOG_GERAL.size() + " linhas)", LOG_GERAL.size() > 40);
        List<String> vazamentos = new ArrayList<>();
        for (String linha : LOG_GERAL) {
            for (String proibido : PROIBIDOS_NO_LOG) {
                if (!proibido.isBlank() && linha.contains(proibido)) {
                    // Imprime só o update da linha, não o trecho vazado.
                    vazamentos.add(linha.substring(0, Math.min(linha.length(), 30)));
                }
            }
        }
        conferir("nenhuma linha contém texto, nome, from.id, ID_USUARIO ou dúvida dos updates ("
                + PROIBIDOS_NO_LOG.size() + " valores conferidos)", vazamentos.isEmpty());
        // O falso não tem token; isto confere que o loop não monta URL nenhuma.
        conferir("nenhuma linha contém a URL da API (onde iria o token)",
                LOG_GERAL.stream().noneMatch(l -> l.contains("api.telegram.org") || l.contains("/bot")));
        for (String vazamento : vazamentos) {
            System.out.println("        vazou em: " + vazamento + "...");
        }
    }

    // --- Ajudantes dos cenários -------------------------------------------------------------

    // Um texto de ATIVO, o registrar devolve o resultado dado; devolve o que foi enviado.
    private static String responder(long updateId, ResultadoRegistro resultado) throws InterruptedException {
        Cenario c = cenarioComResposta(updateId, resultado);
        return c.telegram.enviadas.isEmpty() ? null : c.telegram.enviadas.get(0);
    }

    private static Cenario cenarioComResposta(long updateId, ResultadoRegistro resultado) throws InterruptedException {
        Cenario c = new Cenario();
        c.telegram.updates.add(texto(updateId, ID_TELEGRAM_ATIVO, NOME_ATIVO, "relato do Fulano " + updateId));
        c.registrador.respostas.add(() -> resultado);
        c.rodarAteEsvaziar();
        return c;
    }

    private static Cenario cenarioComFalhasNoEnvio(long updateId, ResultadoRegistro resultado, Exception... falhas)
            throws InterruptedException {
        Cenario c = new Cenario();
        c.telegram.falhasNoEnvio.addAll(List.of(falhas));
        c.telegram.updates.add(texto(updateId, ID_TELEGRAM_ATIVO, NOME_ATIVO, "relato do Fulano " + updateId));
        c.registrador.respostas.add(() -> resultado);
        c.rodarAteEsvaziar();
        return c;
    }

    private static SerieGravada gravada(String relatado, String exercicio, Long idExercicio, int numero,
            Double carga, boolean cargaAproximada, Integer repeticoes, boolean repeticoesAproximadas,
            Boolean falha) {
        return gravada(relatado, exercicio, idExercicio, numero, carga, cargaAproximada, repeticoes,
                repeticoesAproximadas, falha, null);
    }

    private static SerieGravada gravada(String relatado, String exercicio, Long idExercicio, int numero,
            Double carga, boolean cargaAproximada, Integer repeticoes, boolean repeticoesAproximadas,
            Boolean falha, String percepcao) {
        return new SerieGravada(new SerieExtraida(relatado, exercicio, numero, carga, cargaAproximada,
                repeticoes, repeticoesAproximadas, falha, percepcao), idExercicio);
    }

    // --- Updates em JSON ----------------------------------------------------------------------

    // Texto que faz papel de dado de usuário: entra na lista do que não pode ir para o log.
    private static String segredo(String texto) {
        PROIBIDOS_NO_LOG.add(texto);
        return texto;
    }

    private static JsonNode texto(long updateId, long fromId, String nome, String texto) {
        return mensagem("message", updateId, fromId, nome, "private", fromId, DATA_ENVIO,
                "\"text\": " + aspas(segredo(texto)));
    }

    private static JsonNode figurinha(long updateId, long fromId, String nome) {
        return mensagem("message", updateId, fromId, nome, "private", fromId, DATA_ENVIO,
                "\"sticker\": {\"file_id\": \"abc123\", \"emoji\": \"💪\"}");
    }

    private static JsonNode edicao(long updateId, long fromId, String nome, String texto) {
        return mensagem("edited_message", updateId, fromId, nome, "private", fromId, DATA_ENVIO,
                "\"edit_date\": " + DATA_ENVIO + ", \"text\": " + aspas(segredo(texto)));
    }

    // /start de quem não está cadastrado, com o first_name montado à mão: o resto
    // do objeto "from" vai em "campos" (vazio = sem first_name).
    private static JsonNode startComFrom(long updateId, String campos) {
        return json("""
                {"update_id": %d, "message": {"message_id": %d, "date": %d,
                 "chat": {"id": %d, "type": "private"},
                 "from": {"id": %d, "is_bot": false%s},
                 "text": "/start"}}
                """.formatted(updateId, updateId + 5000, DATA_ENVIO, ID_TELEGRAM_DESCONHECIDO,
                ID_TELEGRAM_DESCONHECIDO, campos));
    }

    // message_id = update_id + 5000, para os dois não se confundirem nas conferências.
    private static JsonNode mensagem(String campo, long updateId, long fromId, String nome, String tipoChat,
            long chatId, long data, String conteudo) {
        return json("""
                {"update_id": %d,
                 "%s": {"message_id": %d, "date": %d,
                        "chat": {"id": %d, "type": "%s"},
                        "from": {"id": %d, "is_bot": false, "first_name": %s},
                        %s}}
                """.formatted(updateId, campo, updateId + 5000, data, chatId, tipoChat, fromId, aspas(nome),
                conteudo));
    }

    private static JsonNode json(String texto) {
        return MAPPER.readTree(texto);
    }

    // String Java -> string JSON com aspas e escapes (o Jackson faz o escape).
    private static String aspas(String texto) {
        return MAPPER.writeValueAsString(texto);
    }

    // --- Conferências -----------------------------------------------------------------------

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

    private static void conferirLinha(Cenario c, long updateId, String trecho) {
        conferir("log do update " + updateId + " contém \"" + trecho + "\"", linha(c, updateId).contains(trecho));
    }

    // A última linha de log do update ("" se não houver).
    private static String linha(Cenario c, long updateId) {
        String marca = "| update " + updateId + " |";
        String achada = "";
        for (String linha : c.log) {
            if (linha.contains(marca)) {
                achada = linha;
            }
        }
        return achada;
    }

    private static long contarLinhas(Cenario c, long updateId) {
        String marca = "| update " + updateId + " |";
        return c.log.stream().filter(l -> l.contains(marca)).count();
    }

    private static List<Long> segundos(List<Duration> esperas) {
        return esperas.stream().map(Duration::toSeconds).toList();
    }

    // Houve envio, e todos foram para o mesmo chat.
    private static boolean todosPara(Cenario c, long chatId) {
        return !c.telegram.destinos.isEmpty() && c.telegram.destinos.stream().allMatch(id -> id == chatId);
    }

    private static String recuar(String texto) {
        return texto == null ? "        (nada enviado)" : "          " + texto.replace("\n", "\n          ");
    }

    // --- Os falsos ---------------------------------------------------------------------------

    /** Um loop novo, com falsos novos: nenhum cenário enxerga o estado de outro. */
    private static final class Cenario {
        final TelegramFalso telegram = new TelegramFalso();
        final RegistradorFalso registrador = new RegistradorFalso();
        // O "TR_USUARIO" do cenário. Declarado antes do cadastro: o cadastro falso
        // grava e aprova neste mesmo mapa, que a busca do remetente lê.
        final Map<Long, Usuario> usuarios = new HashMap<>();
        final CadastroFalso cadastro = new CadastroFalso(usuarios);
        final List<Duration> esperas = new ArrayList<>();
        final List<String> log = new ArrayList<>();
        SQLException erroNaBusca;
        final LoopTelegram loop;

        Cenario() {
            LocalDateTime dataCadastro = LocalDateTime.of(2026, 10, 8, 20, 0);
            usuarios.put(ID_TELEGRAM_ATIVO, new Usuario(ID_USUARIO_ATIVO, ID_TELEGRAM_ATIVO, NOME_ATIVO, "ATIVO",
                    dataCadastro));
            usuarios.put(ID_TELEGRAM_PENDENTE, new Usuario(ID_USUARIO_PENDENTE, ID_TELEGRAM_PENDENTE, NOME_PENDENTE,
                    "PENDENTE", dataCadastro));
            usuarios.put(ID_TELEGRAM_ADMIN, new Usuario(ID_USUARIO_ADMIN, ID_TELEGRAM_ADMIN, NOME_ADMIN, "ATIVO",
                    dataCadastro));
            usuarios.put(ID_TELEGRAM_BLOQUEADO, new Usuario(ID_USUARIO_BLOQUEADO, ID_TELEGRAM_BLOQUEADO,
                    NOME_BLOQUEADO, "BLOQUEADO", dataCadastro));
            loop = new LoopTelegram(telegram, registrador, this::buscar, cadastro, ID_TELEGRAM_ADMIN, esperas::add,
                    Clock.fixed(AGORA, SAO_PAULO), linha -> {
                        log.add(linha);
                        LOG_GERAL.add(linha);
                        System.out.println("        log: " + linha);
                    });
        }

        Optional<Usuario> buscar(long idTelegram) throws SQLException {
            if (erroNaBusca != null) {
                throw erroNaBusca;
            }
            return Optional.ofNullable(usuarios.get(idTelegram));
        }

        // Status atual no "banco" do cenário; null = não cadastrado.
        String status(long idTelegram) {
            Usuario usuario = usuarios.get(idTelegram);
            return usuario == null ? null : usuario.status();
        }

        // Nem cadastro, nem aprovação, nem registro (TR_MENSAGEM) foram chamados.
        boolean nadaGravado() {
            return cadastro.cadastros == 0 && cadastro.aprovacoes == 0 && registrador.chamadas == 0;
        }

        // Volta a volta até o Telegram falso não ter mais nada para entregar.
        // null = esvaziou sem encerrar o bot.
        Encerramento rodarAteEsvaziar() throws InterruptedException {
            for (int volta = 0; volta < 50; volta++) {
                if (!telegram.temPendentes(loop.offset()) && telegram.falhasNoGetUpdates.isEmpty()) {
                    return null;
                }
                Encerramento fim = loop.umaVolta();
                if (fim != null) {
                    return fim;
                }
            }
            throw new IllegalStateException("O loop não esvaziou a fila em 50 voltas.");
        }
    }

    /**
     * Telegram em memória. Entrega os updates com update_id >= offset, como o de
     * verdade (é o que faz o update "voltar" quando o offset não avança).
     */
    private static final class TelegramFalso implements ClienteTelegram {
        final List<JsonNode> updates = new ArrayList<>();
        // Consumidas uma por chamada, antes de entregar/enviar. Vazia = sucesso.
        final Deque<Exception> falhasNoGetUpdates = new ArrayDeque<>();
        final Deque<Exception> falhasNoEnvio = new ArrayDeque<>();
        // 6b: todo envio para este chat falha com esta exceção (o aviso ao admin
        // falhando depois da boas-vindas ter chegado, o aprovado que bloqueou o bot).
        final Map<Long, Exception> chatsComFalha = new HashMap<>();
        final List<String> enviadas = new ArrayList<>();
        final List<Long> destinos = new ArrayList<>();
        int tentativasDeEnvio = 0;

        @Override
        public List<JsonNode> buscarUpdates(long offset) throws IOException, ErroTelegram {
            lancar(falhasNoGetUpdates.poll());
            List<JsonNode> pendentes = new ArrayList<>();
            for (JsonNode update : updates) {
                if (update.get("update_id").longValue() >= offset) {
                    pendentes.add(update);
                }
            }
            return pendentes;
        }

        @Override
        public void enviarMensagem(long chatId, String texto) throws IOException, ErroTelegram {
            tentativasDeEnvio++;
            lancar(falhasNoEnvio.poll());
            lancar(chatsComFalha.get(chatId));
            enviadas.add(texto);
            destinos.add(chatId);
        }

        boolean temPendentes(long offset) {
            return updates.stream().anyMatch(u -> u.get("update_id").longValue() >= offset);
        }

        private static void lancar(Exception falha) throws IOException, ErroTelegram {
            if (falha instanceof IOException e) {
                throw e;
            }
            if (falha instanceof ErroTelegram e) {
                throw e;
            }
            if (falha != null) {
                throw new IllegalArgumentException("Falha de teste de tipo não suportado.");
            }
        }
    }

    /**
     * Registrador em memória: guarda os argumentos e devolve (ou lança) o que o
     * cenário mandou. Sem resposta programada, usa o padrão; sem padrão, falha
     * alto (o cenário não esperava que ele fosse chamado).
     */
    private static final class RegistradorFalso implements Registrador {

        @FunctionalInterface
        interface Resposta {
            ResultadoRegistro dar() throws SQLException, InterruptedException;
        }

        final Deque<Resposta> respostas = new ArrayDeque<>();
        Resposta padrao = () -> {
            throw new IllegalStateException("registrar chamado sem resposta programada");
        };
        int chamadas = 0;
        final List<Long> mensagens = new ArrayList<>();
        long idUsuario;
        long idTelegram;
        LocalDateTime dataHoraEnvio;
        String origem;
        String texto;

        @Override
        public ResultadoRegistro registrar(long idUsuario, long idTelegram, LocalDateTime dataHoraEnvio,
                String origem, String texto) throws SQLException, InterruptedException {
            chamadas++;
            mensagens.add(idTelegram);
            this.idUsuario = idUsuario;
            this.idTelegram = idTelegram;
            this.dataHoraEnvio = dataHoraEnvio;
            this.origem = origem;
            this.texto = texto;
            Resposta resposta = respostas.poll();
            return (resposta != null ? resposta : padrao).dar();
        }
    }

    /**
     * Cadastro em memória (6b), sobre o MESMO mapa que a busca do remetente lê:
     * depois de um /start, o próximo update do mesmo from.id já encontra o
     * PENDENTE, como no banco. Decide o /aprovar pelas mesmas regras do
     * CadastroService (o CadastroSpike confere o service de verdade, no banco).
     * Falha programada (SQLException) é lançada antes de mexer no mapa: como um
     * rollback, nada fica "gravado".
     */
    private static final class CadastroFalso implements Cadastrador {
        private final Map<Long, Usuario> usuarios;
        private long proximoId = PRIMEIRO_ID_GERADO;
        final Deque<SQLException> falhasNoCadastro = new ArrayDeque<>();
        final Deque<SQLException> falhasNaAprovacao = new ArrayDeque<>();
        int cadastros = 0;
        int aprovacoes = 0;
        long idTelegram;
        String nome;
        LocalDateTime dataCadastro;
        final List<Long> idsAprovados = new ArrayList<>();

        CadastroFalso(Map<Long, Usuario> usuarios) {
            this.usuarios = usuarios;
        }

        @Override
        public long cadastrarPendente(long idTelegram, String nome, LocalDateTime dataCadastro) throws SQLException {
            cadastros++;
            this.idTelegram = idTelegram;
            this.nome = nome;
            this.dataCadastro = dataCadastro;
            SQLException falha = falhasNoCadastro.poll();
            if (falha != null) {
                throw falha;
            }
            long idUsuario = proximoId++;
            // O id gerado também é um ID_USUARIO: entra na lista do que não pode ir para o log.
            segredo(String.valueOf(idUsuario));
            usuarios.put(idTelegram, new Usuario(idUsuario, idTelegram, nome, "PENDENTE", dataCadastro));
            return idUsuario;
        }

        @Override
        public ResultadoAprovacao aprovar(long idUsuario) throws SQLException {
            aprovacoes++;
            idsAprovados.add(idUsuario);
            SQLException falha = falhasNaAprovacao.poll();
            if (falha != null) {
                throw falha;
            }
            Usuario usuario = usuarios.values().stream()
                    .filter(u -> u.idUsuario() == idUsuario)
                    .findFirst()
                    .orElse(null);
            if (usuario == null) {
                return ResultadoAprovacao.inexistente();
            }
            if ("ATIVO".equals(usuario.status())) {
                return ResultadoAprovacao.jaAtivo();
            }
            if ("BLOQUEADO".equals(usuario.status())) {
                return ResultadoAprovacao.bloqueado();
            }
            usuarios.put(usuario.idTelegram(), new Usuario(usuario.idUsuario(), usuario.idTelegram(), usuario.nome(),
                    "ATIVO", usuario.dataCadastro()));
            return ResultadoAprovacao.aprovado(usuario.nome(), usuario.idTelegram());
        }
    }
}
