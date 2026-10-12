package dev.vitorrodrigues.treino.bot;

import java.io.IOException;

/**
 * Falha de rede (ou resposta ilegível) ao falar com o Telegram, já "limpa".
 *
 * A IOException original do java.net.http pode ter a URL na mensagem ou na
 * causa, e a URL da Bot API tem o token. Por isso o ClienteTelegramHttp não a
 * repassa: lança esta, que guarda só o NOME DA CLASSE original
 * (ConnectException, HttpTimeoutException...). É esse nome que vai para o log.
 * Quando não houve exceção original (resposta 200 sem a lista "result"), vai
 * uma descrição curta e fixa no lugar do nome.
 */
public class FalhaDeRede extends IOException {

    private final String classeOriginal;

    public FalhaDeRede(String classeOriginal) {
        super("Falha de rede no Telegram (" + classeOriginal + ").");
        this.classeOriginal = classeOriginal;
    }

    public String classeOriginal() {
        return classeOriginal;
    }
}
