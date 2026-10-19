package dev.vitorrodrigues.treino.modelo;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * O último treino de um exercício, como o ConsultaDAO o encontrou (fatia 7).
 *
 * dataSessao: TR_SESSAO.DATA_SESSAO da sessão mais recente do usuário que tem ao
 * menos uma série válida (de mensagem não desfeita) do exercício.
 *
 * series: as séries válidas do exercício nessa sessão, na ordem da resposta.
 * Nunca vazia: sem série, não há "último treino" (o DAO devolve vazio).
 */
public record UltimoTreino(LocalDate dataSessao, List<SerieConsultada> series) {

    public UltimoTreino {
        Objects.requireNonNull(dataSessao, "dataSessao");
        // Cópia que ninguém altera depois; também recusa null dentro da lista.
        series = List.copyOf(series);
        if (series.isEmpty()) {
            throw new IllegalArgumentException("Último treino sem séries.");
        }
    }
}
