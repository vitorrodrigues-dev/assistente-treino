package dev.vitorrodrigues.treino.bot;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * O Telegram respondeu com status HTTP diferente de 200.
 *
 * Vão na exceção só o status e, no 429, o retry_after. A "description" do corpo
 * fica de fora: não é necessária para decidir nada, e assim nenhum texto vindo
 * de fora chega ao log.
 */
public class ErroTelegram extends Exception {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final int status;
    private final Integer retryAfter;

    public ErroTelegram(int status) {
        this(status, null);
    }

    /**
     * @param retryAfter segundos que o Telegram pediu para esperar (429); null = não pediu
     */
    public ErroTelegram(int status, Integer retryAfter) {
        super("O Telegram respondeu HTTP " + status + ".");
        this.status = status;
        this.retryAfter = retryAfter;
    }

    /**
     * Monta o erro a partir da resposta HTTP. Corpo de erro da Bot API:
     * {"ok":false,"error_code":429,"description":"...","parameters":{"retry_after":7}}
     *
     * Público para o TelegramSpike conferir a leitura com corpos reais, sem rede.
     */
    public static ErroTelegram deResposta(int status, String corpo) {
        return new ErroTelegram(status, retryAfter(corpo));
    }

    // Regra do projeto para JSON: opcional ausente ou null vira null, nunca 0.
    // Aqui tudo é opcional (o campo só vem no 429), e qualquer coisa fora do
    // esperado (corpo que não é JSON, texto no lugar de número, 0 ou negativo)
    // também vira null: quem chama usa a espera padrão de 5 s. Um erro ao ler o
    // corpo de um erro não pode virar outro erro.
    private static Integer retryAfter(String corpo) {
        JsonNode raiz;
        try {
            raiz = MAPPER.readTree(corpo);
        } catch (JacksonException e) {
            return null;
        }
        JsonNode parametros = raiz.get("parameters");
        if (parametros == null || !parametros.isObject()) {
            return null;
        }
        JsonNode segundos = parametros.get("retry_after");
        if (segundos == null || !segundos.isIntegralNumber() || !segundos.canConvertToInt()
                || segundos.intValue() <= 0) {
            return null;
        }
        return segundos.intValue();
    }

    public int status() {
        return status;
    }

    /** Segundos pedidos pelo Telegram no 429; null = não pediu. */
    public Integer retryAfter() {
        return retryAfter;
    }
}
