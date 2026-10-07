package dev.vitorrodrigues.treino.spike;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class TesteJackson {

    public static void main(String[] args) {
        String json = """
                {"ok":true,"result":[{"message":{"chat":{"id":123},"text":"OI"}}]}
                """;

        ObjectMapper mapper = new ObjectMapper();
        JsonNode raiz = mapper.readTree(json);

        JsonNode message = raiz.path("result").path(0).path("message");
        long chatId = message.path("chat").path("id").asLong();
        String text = message.path("text").asString();

        System.out.println("chat id: " + chatId);
        System.out.println("text: " + text);
    }
}
