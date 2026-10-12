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
import dev.vitorrodrigues.treino.modelo.ResultadoRegistro;
import dev.vitorrodrigues.treino.modelo.ResultadoRegistro.SerieGravada;
import dev.vitorrodrigues.treino.modelo.ResultadoRegistro.TipoFalha;
import dev.vitorrodrigues.treino.modelo.Usuario;
import dev.vitorrodrigues.treino.service.Registrador;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Aceite da fatia 6a sem celular: o LoopTelegram inteiro, com falsos no lugar do
 * Telegram, do banco, da espera e do relógio. Sem banco, sem API, sem rede: não
 * custa nada rodar.
 *
 * Cada cenário entrega updates em JSON (no formato da Bot API) a um Telegram
 * falso e confere a resposta enviada, o avanço do offset, as esperas pedidas e
 * a linha de log. No fim, confere que NENHUMA linha de log de nenhum cenário
 * contém texto, nome, from.id ou ID_USUARIO dos updates de teste.
 *
 * "Fulano" e "Beltrano" fazem o papel de outras pessoas.
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

    // Tudo o que nunca pode aparecer no log. Os textos das mensagens e as
    // dúvidas entram aqui conforme os cenários os criam (ver segredo()).
    private static final List<String> PROIBIDOS_NO_LOG = new ArrayList<>(List.of(
            NOME_ATIVO, NOME_PENDENTE,
            String.valueOf(ID_TELEGRAM_ATIVO), String.valueOf(ID_TELEGRAM_PENDENTE),
            String.valueOf(ID_TELEGRAM_DESCONHECIDO), String.valueOf(ID_USUARIO_ATIVO)));

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
        conferir("respostas, na ordem: figurinha, branco, edição, comando",
                c.telegram.enviadas.equals(List.of(Textos.SO_ENTENDO_TEXTO, Textos.SO_ENTENDO_TEXTO,
                        Textos.EDICAO_IGNORADA, Textos.COMANDO_NAO_RECONHECIDO)));
        conferir("todas as respostas foram para o chat de quem está ATIVO",
                c.telegram.destinos.stream().allMatch(id -> id == ID_TELEGRAM_ATIVO));
        conferirLinha(c, 100, "| my_chat_member | ignorado: não é mensagem |");
        conferirLinha(c, 101, "| texto | ignorado: não é chat privado |");
        conferirLinha(c, 102, "| texto | ignorado: sem remetente |");
        conferirLinha(c, 103, "| texto | ignorado: remetente não está ATIVO |");
        conferirLinha(c, 104, "| texto | ignorado: remetente não está ATIVO |");
        conferirLinha(c, 105, "| edição | ignorado: remetente não está ATIVO |");
        conferirLinha(c, 106, "| figurinha | ignorado: sem texto |");
        conferirLinha(c, 107, "| texto | ignorado: sem texto |");
        conferirLinha(c, 108, "| edição | ignorado: edição |");
        conferirLinha(c, 109, "| texto | ignorado: comando não reconhecido |");
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

    private static String recuar(String texto) {
        return texto == null ? "        (nada enviado)" : "          " + texto.replace("\n", "\n          ");
    }

    // --- Os falsos ---------------------------------------------------------------------------

    /** Um loop novo, com falsos novos: nenhum cenário enxerga o estado de outro. */
    private static final class Cenario {
        final TelegramFalso telegram = new TelegramFalso();
        final RegistradorFalso registrador = new RegistradorFalso();
        final Map<Long, Usuario> usuarios = new HashMap<>();
        final List<Duration> esperas = new ArrayList<>();
        final List<String> log = new ArrayList<>();
        SQLException erroNaBusca;
        final LoopTelegram loop;

        Cenario() {
            LocalDateTime cadastro = LocalDateTime.of(2026, 10, 8, 20, 0);
            usuarios.put(ID_TELEGRAM_ATIVO, new Usuario(ID_USUARIO_ATIVO, ID_TELEGRAM_ATIVO, NOME_ATIVO, "ATIVO",
                    cadastro));
            usuarios.put(ID_TELEGRAM_PENDENTE, new Usuario(888456L, ID_TELEGRAM_PENDENTE, NOME_PENDENTE, "PENDENTE",
                    cadastro));
            loop = new LoopTelegram(telegram, registrador, this::buscar, esperas::add,
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
}
