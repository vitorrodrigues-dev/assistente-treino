package dev.vitorrodrigues.treino.extrator;

/**
 * Uma série como o extrator entendeu (formato do prompt v3).
 *
 * Nunca null (o LeitorRelatoJson falha se faltarem): exercicioRelatado,
 * numeroSerie, cargaAproximada e repeticoesAproximadas.
 * Os demais podem ser null = não foi dito (ou o modelo não informou).
 * Por isso os wrappers Integer, Double e Boolean: o primitivo não aceita null
 * e viraria 0 ou false em silêncio.
 * falha é Boolean de três estados: true, false ou null (não foi dito).
 */
public record SerieExtraida(
        String exercicioRelatado,
        String exercicio,
        Integer numeroSerie,
        Double cargaKg,
        Boolean cargaAproximada,
        Integer repeticoes,
        Boolean repeticoesAproximadas,
        Boolean falha,
        String percepcao) {
}
