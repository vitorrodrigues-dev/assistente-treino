package dev.vitorrodrigues.treino.modelo;

/**
 * Uma série como a consulta /carga a lê do banco (fatia 7). Só o que a resposta
 * mostra: número, carga, repetições e falha. Percepção não aparece na resposta,
 * então nem é lida.
 *
 * Wrappers, nunca primitivos, onde o banco aceita NULL (= não foi dito):
 * cargaKg, repeticoes e falha. falha tem três estados: true, false e null.
 *
 * cargaAproximada só existe com carga: com CARGA_KG NULL, o ConsultaDAO NÃO lê
 * CARGA_APROX (um "~" sem número não diz nada) e o campo fica null. Mesma regra
 * para repeticoesAproximadas. O construtor recusa a combinação errada, para a
 * regra valer também para quem montar o record à mão.
 *
 * cargaKg é Double, como no SerieExtraida: é o tipo que a formatação da
 * confirmação da 6a recebe (Respostas), reaproveitada aqui.
 */
public record SerieConsultada(
        int numeroSerie,
        Double cargaKg,
        Boolean cargaAproximada,
        Integer repeticoes,
        Boolean repeticoesAproximadas,
        Boolean falha) {

    public SerieConsultada {
        if (cargaKg == null && cargaAproximada != null) {
            throw new IllegalArgumentException("cargaAproximada existe só com cargaKg.");
        }
        if (repeticoes == null && repeticoesAproximadas != null) {
            throw new IllegalArgumentException("repeticoesAproximadas existe só com repeticoes.");
        }
    }
}
