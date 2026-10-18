package dev.vitorrodrigues.treino.modelo;

import java.util.Objects;

/**
 * Uma série reduzida ao que identifica o exercício (fatia 6c). O resumo do
 * /desfazer conta séries por exercício e não usa carga nem repetições.
 *
 * idExercicio: null = exercício não reconhecido (fora do catálogo).
 *
 * nomeOficial: TR_EXERCICIO.NOME, lido do catálogo pelo ID_EXERCICIO, nunca o
 * nome que o modelo devolveu. Existe exatamente quando idExercicio existe (a FK
 * FK_TR_SERIE_EXERCICIO garante que o id aponta para um exercício do catálogo).
 *
 * exercicioRelatado: como o usuário escreveu (NOT NULL no banco). É texto do
 * usuário: vai só para o chat dele, nunca para log.
 */
public record SerieResumida(Long idExercicio, String nomeOficial, String exercicioRelatado) {

    public SerieResumida {
        Objects.requireNonNull(exercicioRelatado, "exercicioRelatado");
        if ((idExercicio == null) != (nomeOficial == null)) {
            throw new IllegalArgumentException("nomeOficial existe só com idExercicio.");
        }
    }

    /**
     * Sem os nomes. O toString() gerado pelo record traria o exercicio_relatado
     * (texto do usuário); mesma ideia do ResultadoAprovacao.
     */
    @Override
    public String toString() {
        return "SerieResumida[reconhecida=" + (idExercicio != null) + "]";
    }
}
