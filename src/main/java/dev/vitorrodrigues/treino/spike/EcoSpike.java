package dev.vitorrodrigues.treino.spike;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

public class EcoSpike {

    private static final String API_BASE = "https://api.telegram.org/bot";
    private static final int TIMEOUT_POLLING_SEGUNDOS = 30;
    private static final Duration TIMEOUT_HTTP = Duration.ofSeconds(40);
    private static final Duration ESPERA_APOS_ERRO = Duration.ofSeconds(5);

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) {
        String token = lerVariavel("TELEGRAM_BOT_TOKEN");
        long idPermitido = lerId("TELEGRAM_CHAT_ID_PERMITIDO");
        URI sendMessageUri = montarUri(token, "sendMessage");

        long offset = 0;
        System.out.println("EcoSpike iniciado. Aguardando mensagens...");

        while (true) {
            try {
                try {
                    URI getUpdatesUri = montarUri(token,
                            "getUpdates?timeout=" + TIMEOUT_POLLING_SEGUNDOS + "&offset=" + offset);
                    HttpRequest request = HttpRequest.newBuilder(getUpdatesUri)
                            .timeout(TIMEOUT_HTTP)
                            .GET()
                            .build();
                    HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                    JsonNode corpo = MAPPER.readTree(response.body());

                    if (response.statusCode() != 200) {
                        System.out.println("getUpdates falhou: status " + response.statusCode()
                                + " - " + corpo.path("description").asString());
                        Thread.sleep(ESPERA_APOS_ERRO);
                        continue;
                    }

                    for (JsonNode update : corpo.required("result")) {
                        long updateId = update.required("update_id").asLong();
                        try {
                            processarUpdate(update, idPermitido, sendMessageUri);
                        } catch (JacksonException e) {
                            System.out.println("update inválido (" + e.getClass().getSimpleName() + ")");
                        }
                        offset = updateId + 1;
                    }
                } catch (IOException e) {
                    System.out.println("Erro de rede: " + e.getClass().getSimpleName()
                            + ": " + String.valueOf(e.getMessage()).replace(token, "***"));
                    Thread.sleep(ESPERA_APOS_ERRO);
                } catch (JacksonException e) {
                    System.out.println("Resposta do getUpdates ilegível (" + e.getClass().getSimpleName() + ")");
                    Thread.sleep(ESPERA_APOS_ERRO);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        System.out.println("EcoSpike encerrado (thread interrompida).");
    }

    private static void processarUpdate(JsonNode update, long idPermitido, URI sendMessageUri)
            throws IOException, InterruptedException {
        JsonNode message = update.get("message");
        if (message == null) {
            System.out.println("update ignorado (tipo " + tipoDoUpdate(update) + ")");
            return;
        }
        if (!message.has("text")) {
            System.out.println("update ignorado (tipo message sem text)");
            return;
        }

        long remetente = message.required("from").required("id").asLong();
        if (remetente != idPermitido) {
            System.out.println("mensagem ignorada: remetente não autorizado");
            return;
        }

        long chatId = message.required("chat").required("id").asLong();
        String texto = message.required("text").asString();

        ObjectNode corpo = MAPPER.createObjectNode();
        corpo.put("chat_id", chatId);
        corpo.put("text", "Eco: " + texto);

        HttpRequest request = HttpRequest.newBuilder(sendMessageUri)
                .timeout(TIMEOUT_HTTP)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(corpo)))
                .build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            String description = MAPPER.readTree(response.body()).path("description").asString();
            System.out.println("sendMessage falhou: status " + response.statusCode() + " - " + description);
        } else {
            System.out.println("eco enviado");
        }
    }

    private static String tipoDoUpdate(JsonNode update) {
        return update.propertyNames().stream()
                .filter(nome -> !nome.equals("update_id"))
                .findFirst()
                .orElse("desconhecido");
    }

    private static String lerVariavel(String nome) {
        String valor = System.getenv(nome);
        if (valor == null || valor.isBlank()) {
            throw new IllegalStateException("Variável de ambiente " + nome + " não definida ou vazia.");
        }
        return valor;
    }

    private static long lerId(String nome) {
        String valor = lerVariavel(nome);
        try {
            return Long.parseLong(valor.strip());
        } catch (NumberFormatException e) {
            // A mensagem da NumberFormatException contém o valor, por isso não é repassada.
            throw new IllegalStateException("Variável de ambiente " + nome + " não é um número inteiro válido.");
        }
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
