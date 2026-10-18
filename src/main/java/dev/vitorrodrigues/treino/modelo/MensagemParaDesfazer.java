package dev.vitorrodrigues.treino.modelo;

import java.time.LocalDate;
import java.util.Objects;

/**
 * O alvo do /desfazer (fatia 6c), como o MensagemDAO o encontrou: a mensagem e o
 * que a resposta precisa saber da sessão dela.
 *
 * dataSessao: TR_SESSAO.DATA_SESSAO da sessão da mensagem (cabeçalho da resposta).
 *
 * sessaoComObservacao: a sessão tem OBSERVACAO não nula. Só o sim/não: o texto da
 * observação é do usuário, e a resposta não o repete.
 *
 * idMensagem é interno: nunca vai para log nem para mensagem de exceção (por isso
 * ninguém imprime este record).
 */
public record MensagemParaDesfazer(long idMensagem, LocalDate dataSessao, boolean sessaoComObservacao) {

    public MensagemParaDesfazer {
        Objects.requireNonNull(dataSessao, "dataSessao");
    }
}
