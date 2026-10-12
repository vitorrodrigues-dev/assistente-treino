package dev.vitorrodrigues.treino.bot;

/**
 * Todas as frases que o usuário lê no chat, num lugar só (fatia 6a).
 *
 * Todas PROVISÓRIAS: o Vitor reescreve antes do teste com amigos (o texto que
 * vai para os amigos não se delega). Com elas aqui, reescrever é editar um
 * arquivo, sem caçar String pelo código.
 *
 * A ESTRUTURA da confirmação (data explícita, séries agrupadas, "~", "—",
 * ordinal, bloco de dúvidas) é decisão tomada e mora no Respostas. Aqui ficam só
 * as palavras.
 *
 * Texto puro: nada de Markdown. O bot não usa parse_mode (ver ClienteTelegramHttp).
 *
 * Público só para o TelegramSpike montar as respostas esperadas com as mesmas
 * constantes (assim o spike continua valendo depois que as palavras mudarem).
 */
public final class Textos {

    private Textos() {
    }

    // --- Filtros (seção 1) ---------------------------------------------------------

    // PROVISÓRIO
    public static final String EDICAO_IGNORADA =
            "Edições não mudam o registro. Mande uma mensagem nova com a correção.";

    // PROVISÓRIO
    public static final String SO_ENTENDO_TEXTO = "Por enquanto só entendo texto.";

    // PROVISÓRIO
    public static final String COMANDO_NAO_RECONHECIDO = "Comando não reconhecido.";

    // --- Erros (seções 2 e 3) --------------------------------------------------------

    // PROVISÓRIO. O banco recusou a mensagem (repetir não muda nada).
    public static final String NAO_CONSEGUI_SALVAR = "Não consegui salvar essa mensagem.";

    // PROVISÓRIO. O banco ficou fora do ar nas 10 tentativas.
    public static final String NAO_CONSEGUI_SALVAR_DEPOIS_DE_TENTAR =
            "Não consegui salvar essa mensagem agora. Tente reenviar mais tarde.";

    // PROVISÓRIO
    public static final String ERRO_INESPERADO =
            "Tive um erro com essa mensagem. Se ela não aparecer no seu registro, reenvie.";

    // --- Situações do registro (seção 5) ----------------------------------------------

    // PROVISÓRIO
    public static final String SEM_SERIES =
            "Não encontrei séries nessa mensagem. Se era uma pergunta, as consultas chegam em breve.";

    // PROVISÓRIO
    public static final String NAO_REGISTRADO =
            "Seu texto ficou salvo, mas não consegui registrar. Reenvie com outras palavras.";

    // PROVISÓRIO
    public static final String OBSERVACAO_CHEIA =
            "A observação de hoje já atingiu o limite. Mande só as séries, sem observação.";

    // --- Confirmação do REGISTRADO (seção 4) --------------------------------------------

    // PROVISÓRIO. %s = data dd/MM/yyyy.
    public static final String REGISTRADO_EM = "Registrado em %s:";

    // PROVISÓRIO. %s = exercicio_relatado.
    public static final String NAO_RECONHECI = "Não reconheci: \"%s\"";

    // PROVISÓRIO
    public static final String OBSERVACAO_ANOTADA = "Anotei a observação do dia.";

    // PROVISÓRIO
    public static final String PONTOS_NAO_CLAROS = "Pontos que não ficaram claros:";

    // PROVISÓRIO
    public static final String FALHA = "(falha)";

    // PROVISÓRIO
    public static final String SEM_FALHA = "(sem falha)";
}
