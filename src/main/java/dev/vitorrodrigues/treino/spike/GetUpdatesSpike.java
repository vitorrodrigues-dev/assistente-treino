package dev.vitorrodrigues.treino.spike;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class GetUpdatesSpike {

    public static void main(String[] args) throws IOException, InterruptedException {
        String token = System.getenv("TELEGRAM_BOT_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Variável de ambiente TELEGRAM_BOT_TOKEN não definida ou vazia.");
        }

        URI uri;
        try {
            uri = URI.create("https://api.telegram.org/bot" + token + "/getUpdates");
        } catch (IllegalArgumentException e) {
            // A mensagem original contém a URL (com o token), por isso não é repassada.
            throw new IllegalStateException("TELEGRAM_BOT_TOKEN tem caracteres inválidos para uma URL.");
        }

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(uri).GET().build();

        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            // Idem: não repassa a mensagem nem a causa, que podem conter a URL.
            throw new IOException("Falha na chamada ao getUpdates (" + e.getClass().getSimpleName() + ").");
        }

        System.out.println("statusCode: " + response.statusCode());
        System.out.println("body: " + response.body());

        ObjectMapper mapper = new ObjectMapper();
        JsonNode raiz = mapper.readTree(response.body());
        JsonNode result = raiz.required("result");

        if (result.isEmpty()) {
            System.out.println("Aviso: result está vazio. Mande uma mensagem ao bot e rode de novo.");
            return;
        }

        long chatId = result.get(0).required("message").required("chat").required("id").asLong();
        System.out.println("chat id da primeira mensagem: " + chatId);
    }
}
