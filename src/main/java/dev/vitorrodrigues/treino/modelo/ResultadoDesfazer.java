package dev.vitorrodrigues.treino.modelo;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * O que o /desfazer fez (fatia 6c). O loop do Telegram monta a resposta por aqui.
 *
 * situacao:
 * - DESFEITO: a última mensagem registrada do usuário nas 24 h foi marcada como
 *   desfeita (DATA_HORA_DESFEITA preenchida, UPDATE comitado). Nada foi apagado.
 * - NADA_PARA_DESFAZER: não há mensagem do usuário com registro, ainda válida,
 *   nas 24 h anteriores ao /desfazer. Nada mudou.
 * - JA_APLICADO: este mesmo /desfazer (mesmo message_id) já desfez uma mensagem.
 *   É uma reentrega do Telegram: o bot caiu depois do commit e antes de
 *   confirmar o offset. Nada mudou, e o bot fica em silêncio (como no
 *   JA_GRAVADA do registro).
 *
 * Só no DESFEITO:
 * - dataSessao: a data da sessão da mensagem desfeita (cabeçalho da resposta).
 * - series: as séries da mensagem desfeita, na ordem de gravação. Vazia = a
 *   mensagem só trouxe observação do dia.
 * - sessaoComObservacao: a sessão tem observação. Ela NÃO é desfeita (é
 *   concatenada na sessão, e não dá para saber que parte veio desta mensagem); a
 *   resposta avisa.
 *
 * Criado só pelas fábricas abaixo (mesma ideia do ResultadoRegistro); o
 * construtor confere as mesmas regras.
 */
public record ResultadoDesfazer(
        Situacao situacao,
        LocalDate dataSessao,
        List<SerieResumida> series,
        boolean sessaoComObservacao) {

    public enum Situacao {
        DESFEITO,
        NADA_PARA_DESFAZER,
        JA_APLICADO
    }

    public ResultadoDesfazer {
        Objects.requireNonNull(situacao, "situacao");
        // Cópia que ninguém altera depois; também recusa null dentro da lista.
        series = List.copyOf(series);

        boolean desfeito = situacao == Situacao.DESFEITO;
        if (desfeito != (dataSessao != null)) {
            throw new IllegalArgumentException("dataSessao existe só em DESFEITO.");
        }
        if (!desfeito && (!series.isEmpty() || sessaoComObservacao)) {
            throw new IllegalArgumentException("Séries e observação existem só em DESFEITO.");
        }
    }

    public static ResultadoDesfazer desfeito(LocalDate dataSessao, List<SerieResumida> series,
            boolean sessaoComObservacao) {
        return new ResultadoDesfazer(Situacao.DESFEITO, Objects.requireNonNull(dataSessao, "dataSessao"), series,
                sessaoComObservacao);
    }

    public static ResultadoDesfazer nadaParaDesfazer() {
        return new ResultadoDesfazer(Situacao.NADA_PARA_DESFAZER, null, List.of(), false);
    }

    public static ResultadoDesfazer jaAplicado() {
        return new ResultadoDesfazer(Situacao.JA_APLICADO, null, List.of(), false);
    }

    /**
     * Só a situação. O toString() gerado pelo record traria os nomes de exercício e
     * o texto relatado, que nunca podem ir para log (regra 5 da 6c).
     */
    @Override
    public String toString() {
        return "ResultadoDesfazer[" + situacao + "]";
    }
}
