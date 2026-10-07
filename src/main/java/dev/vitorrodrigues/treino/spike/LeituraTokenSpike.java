package dev.vitorrodrigues.treino.spike;

public class LeituraTokenSpike {
    public static void main(String[] args) {
        String token = System.getenv("TELEGRAM_BOT_TOKEN");
        System.out.println(token == null ? "NÃO encontrou a variável" : "OK, token lido (" + token.length() + " caracteres)");
    }
}
