package dev.vitorrodrigues.treino.modelo;

/**
 * O que aconteceu ao gravar uma mensagem em TR_MENSAGEM.
 *
 * Gravada: idMensagem tem o id gerado pelo banco.
 * Já gravada: a mesma mensagem (mesmo usuário + mesmo message_id) já estava no banco,
 * por exemplo porque o bot caiu antes de avançar o offset. Não é erro: o chamador
 * simplesmente não processa de novo. Nesse caso idMensagem é null.
 *
 * As duas fábricas abaixo existem para que ninguém monte um estado sem sentido
 * (por exemplo "já gravada" com id, ou "gravada" sem id). A segunda não pode se
 * chamar jaGravada(): o record já gera um método com esse nome para ler o campo.
 */
public record ResultadoInsercao(boolean jaGravada, Long idMensagem) {

    public static ResultadoInsercao gravada(long idMensagem) {
        return new ResultadoInsercao(false, idMensagem);
    }

    public static ResultadoInsercao jaEstavaGravada() {
        return new ResultadoInsercao(true, null);
    }
}
