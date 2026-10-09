package dev.vitorrodrigues.treino.modelo;

import java.util.List;

/**
 * Exercício do catálogo junto com os apelidos de UM usuário.
 * É o que vai para a lista de exercícios do prompt do extrator.
 *
 * repsMin/repsMax são Integer (não int) porque a faixa alvo é opcional:
 * null = sem faixa, nunca 0.
 * apelidos nunca é null: sem apelido = lista vazia. O DAO preenche essa lista
 * enquanto lê as linhas da consulta.
 */
public record ExercicioComApelidos(
        String nome,
        String grupamento,
        String formaCarga,
        Integer repsMin,
        Integer repsMax,
        List<String> apelidos) {

    public ExercicioComApelidos {
        // Mesma regra da CK_TR_EXERCICIO_FAIXA: as duas ou nenhuma.
        // Garante que a linha do prompt nunca saia como "faixa alvo: 8-null".
        if ((repsMin == null) != (repsMax == null)) {
            throw new IllegalArgumentException("Faixa alvo incompleta no exercício " + nome
                    + ": informe mínimo e máximo, ou nenhum dos dois.");
        }
    }

    /**
     * Linha no formato da lista do prompt, por exemplo:
     * - Remada unilateral máquina | grupamento: costas | carga: por lado | faixa alvo: 8-12 | apelidos: x, y
     * Sem faixa alvo, o trecho "faixa alvo" some; sem apelidos, o trecho "apelidos" some.
     */
    public String linhaDoPrompt() {
        StringBuilder linha = new StringBuilder();
        linha.append("- ").append(nome)
                .append(" | grupamento: ").append(grupamento)
                .append(" | carga: ").append(formaCargaPorExtenso());

        if (repsMin != null) {
            linha.append(" | faixa alvo: ").append(repsMin).append('-').append(repsMax);
        }
        if (!apelidos.isEmpty()) {
            linha.append(" | apelidos: ").append(String.join(", ", apelidos));
        }
        return linha.toString();
    }

    // No banco o valor é um código (CK_TR_EXERCICIO_FORMA_CARGA); no prompt, texto corrido.
    // Valor fora da lista falha alto em vez de ir para o prompt de qualquer jeito.
    private String formaCargaPorExtenso() {
        return switch (formaCarga) {
            case "POR_LADO" -> "por lado";
            case "TOTAL" -> "total";
            default -> throw new IllegalStateException("Forma de carga desconhecida: " + formaCarga);
        };
    }
}
