package dev.vitorrodrigues.treino.bot;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Bot API do Telegram por java.net.http, como no EcoSpike.
 *
 * O token vai na URL (é assim que a Bot API funciona). Por isso as duas URIs são
 * montadas uma vez só, no construtor, e nenhuma mensagem de exceção que sai
 * daqui leva a URL, a mensagem original ou a causa de uma IOException: qualquer
 * uma delas pode conter o token.
 */
public class ClienteTelegramHttp implements ClienteTelegram {

    private static final String API_BASE = "https://api.telegram.org/bot";

    // Long polling: o Telegram segura o getUpdates por até 30 s esperando
    // mensagem. O timeout do HTTP tem que ser MAIOR que isso (descoberta do
    // spike do Telegram); senão todo getUpdates sem mensagem viraria erro.
    private static final int TIMEOUT_POLLING_SEGUNDOS = 30;
    private static final Duration TIMEOUT_HTTP = Duration.ofSeconds(40);

    private static final int HTTP_OK = 200;

    private final HttpClient cliente = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final URI uriGetUpdates;
    private final URI uriSendMessage;

    public ClienteTelegramHttp(String token) {
        this.uriGetUpdates = montarUri(token, "getUpdates");
        this.uriSendMessage = montarUri(token, "sendMessage");
    }

    @Override
    public List<JsonNode> buscarUpdates(long offset) throws IOException, InterruptedException, ErroTelegram {
        // POST com corpo JSON (a Bot API aceita): offset e timeout vão no corpo,
        // e a URI com o token não precisa ser remontada a cada chamada.
        ObjectNode corpo = mapper.createObjectNode();
        corpo.put("offset", offset);
        corpo.put("timeout", TIMEOUT_POLLING_SEGUNDOS);
        String resposta = post(uriGetUpdates, corpo);

        // Corpo ilegível num 200 é tratado como falha de rede (resposta corrompida
        // no caminho): o loop espera e tenta de novo. A mensagem do Jackson cita o
        // trecho inválido, que pode ser texto de mensagem: só a classe.
        JsonNode result;
        try {
            result = mapper.readTree(resposta).required("result");
        } catch (JacksonException e) {
            throw new FalhaDeRede(e.getClass().getSimpleName());
        }
        if (!result.isArray()) {
            throw new FalhaDeRede("resposta sem result");
        }
        List<JsonNode> updates = new ArrayList<>();
        for (JsonNode update : result) {
            updates.add(update);
        }
        return updates;
    }

    @Override
    public void enviarMensagem(long chatId, String texto) throws IOException, InterruptedException, ErroTelegram {
        // Sem parse_mode, de propósito: texto puro. Com Markdown, um "*" ou "_"
        // vindo do usuário (no nome de um exercício não reconhecido, numa dúvida)
        // quebraria a formatação, e o Telegram recusaria a mensagem inteira (400).
        ObjectNode corpo = mapper.createObjectNode();
        corpo.put("chat_id", chatId);
        corpo.put("text", texto);
        post(uriSendMessage, corpo);
    }

    // ObjectNode e não concatenação de String: o Jackson escapa aspas, quebras de
    // linha e acentos do texto (mesma ideia do ExtratorCliente).
    private String post(URI uri, ObjectNode corpo) throws IOException, InterruptedException, ErroTelegram {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT_HTTP)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(corpo)))
                .build();

        HttpResponse<String> response;
        try {
            response = cliente.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            // Nova exceção sem a mensagem nem a causa originais (podem conter a
            // URL, com o token). Só o nome da classe original, que basta para o log.
            throw new FalhaDeRede(e.getClass().getSimpleName());
        }

        if (response.statusCode() != HTTP_OK) {
            // O corpo do erro só é lido para tirar o retry_after (429).
            throw ErroTelegram.deResposta(response.statusCode(), response.body());
        }
        return response.body();
    }

    private static URI montarUri(String token, String metodo) {
        try {
            return URI.create(API_BASE + token + "/" + metodo);
        } catch (IllegalArgumentException e) {
            // A mensagem original contém a URL (com o token), por isso não é repassada.
            throw new IllegalStateException("TELEGRAM_BOT_TOKEN tem caracteres inválidos para uma URL.");
        }
    }
}
