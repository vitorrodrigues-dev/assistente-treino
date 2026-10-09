package dev.vitorrodrigues.treino.extrator;

import java.util.List;

/**
 * Tudo o que o extrator tirou de um relato.
 *
 * observacaoDia: null quando o relato não traz contexto do dia.
 * series: nunca null ("series" é obrigatório no JSON); pode ser vazia.
 * duvidas: null quando o modelo não mandou o campo; lista vazia quando mandou []
 * (os dois casos são diferentes: "não respondeu" x "não teve dúvida").
 */
public record RelatoExtraido(
        String observacaoDia,
        List<SerieExtraida> series,
        List<String> duvidas) {
}
