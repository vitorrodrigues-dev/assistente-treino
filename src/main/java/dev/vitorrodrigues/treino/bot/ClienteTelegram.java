package dev.vitorrodrigues.treino.bot;

import java.io.IOException;
import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * As duas chamadas da Bot API que o loop usa (fatia 6a).
 *
 * Interface para o loop ser testável sem celular e sem rede: o bot usa o
 * ClienteTelegramHttp; o TelegramSpike usa um falso que entrega updates em JSON
 * prontos e guarda o que seria enviado.
 *
 * Contrato das exceções:
 * - ErroTelegram: o Telegram respondeu, com status diferente de 200 (409, 401,
 *   403...). O loop decide pelo status.
 * - IOException: falha de rede, ou resposta que não dá para ler. O loop nunca
 *   imprime a mensagem: só o nome da classe. O ClienteTelegramHttp lança
 *   FalhaDeRede, que guarda o nome da classe original sem a mensagem nem a causa
 *   (que podem ter a URL, com o token).
 */
public interface ClienteTelegram {

    /**
     * getUpdates com long polling.
     *
     * @param offset update_id do primeiro update ainda não processado. 0 = todos os
     *               pendentes (é o que o bot usa na subida).
     * @return os updates na ordem do Telegram, como vieram (JSON). Vazia = o long
     *         polling terminou sem mensagem nova.
     */
    List<JsonNode> buscarUpdates(long offset) throws IOException, InterruptedException, ErroTelegram;

    /**
     * sendMessage em texto puro.
     */
    void enviarMensagem(long chatId, String texto) throws IOException, InterruptedException, ErroTelegram;
}
