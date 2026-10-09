package dev.vitorrodrigues.treino.extrator;

/**
 * A chamada ao extrator não produziu um relato utilizável: a API respondeu com
 * erro, a resposta veio cortada, o texto do modelo não é JSON, falta campo
 * obrigatório, campo com tipo errado...
 *
 * Checked de propósito: quem chama o extrator é obrigado a decidir o que fazer
 * (no MVP: a mensagem crua continua salva e o bot avisa que não entendeu).
 *
 * A mensagem nunca leva a chave da API, headers ou texto do relato. Quando a
 * falha é ao ler o texto do modelo, esse texto vai à parte, em getRespostaBruta().
 */
public class ExtracaoException extends Exception {

    // Fora da mensagem de propósito: getMessage(), toString() e o stack trace vão
    // parar em log, e o texto do modelo pode repetir o relato de outra pessoa.
    // Quem chama getRespostaBruta() decide conscientemente mostrar (hoje, só o
    // ExtratorSpike, com relatos do Vitor). null = a falha não tem texto do modelo
    // (erro HTTP, stop_reason...).
    private final String respostaBruta;

    public ExtracaoException(String mensagem) {
        this(mensagem, null, null);
    }

    public ExtracaoException(String mensagem, Throwable causa) {
        this(mensagem, causa, null);
    }

    // Fábrica com nome em vez de um construtor (String, String): na chamada fica
    // claro que o segundo texto é a resposta do modelo, não outra mensagem.
    public static ExtracaoException comRespostaBruta(String mensagem, String respostaBruta) {
        return new ExtracaoException(mensagem, null, respostaBruta);
    }

    private ExtracaoException(String mensagem, Throwable causa, String respostaBruta) {
        super(mensagem, causa);
        this.respostaBruta = respostaBruta;
    }

    public String getRespostaBruta() {
        return respostaBruta;
    }
}
