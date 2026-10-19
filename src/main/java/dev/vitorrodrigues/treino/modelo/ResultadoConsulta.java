package dev.vitorrodrigues.treino.modelo;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * O que a consulta /carga encontrou (fatia 7). O loop do Telegram monta a resposta
 * por aqui, e a situação vai para o log.
 *
 * situacao:
 * - JA_GRAVADA: a mesma mensagem /carga já estava em TR_MENSAGEM (reentrega do
 *   Telegram). Nada foi consultado; o bot fica em silêncio, como no registro.
 * - AJUDA: /carga sem argumento.
 * - NAO_ENCONTRADO: nenhum exercício casou com o argumento (ou ele passa de 100
 *   caracteres).
 * - AMBIGUO: mais de um exercício casou. Nenhum foi escolhido.
 * - SEM_REGISTRO: o exercício foi resolvido, mas o usuário não tem série válida dele.
 * - ENCONTRADO: as séries do último treino do exercício.
 * - ERRO: o banco falhou DEPOIS de a mensagem estar gravada (catálogo ou leitura
 *   das séries). Não é exceção pelo mesmo motivo do NAO_REGISTRADO do registro: a
 *   reentrega cairia em JA_GRAVADA e o usuário ficaria sem resposta.
 *
 * nomeOficial: TR_EXERCICIO.NOME, só em ENCONTRADO e SEM_REGISTRO.
 * formaCarga: o código do catálogo ('POR_LADO' ou 'TOTAL'), só em ENCONTRADO.
 * dataTreino e series: só em ENCONTRADO (lá, ao menos uma série).
 * candidatos: nomes oficiais, só em AMBIGUO (de 2 a 10).
 * haMaisCandidatos: só em AMBIGUO, quando os 10 candidatos não são todos.
 * motivo: só em ERRO, e só com etapa e código ORA (vai para o log).
 *
 * Criado só pelas fábricas abaixo (mesma ideia do ResultadoRegistro); o
 * construtor confere as mesmas regras.
 */
public record ResultadoConsulta(
        Situacao situacao,
        String nomeOficial,
        String formaCarga,
        LocalDate dataTreino,
        List<SerieConsultada> series,
        List<String> candidatos,
        boolean haMaisCandidatos,
        String motivo) {

    public enum Situacao {
        JA_GRAVADA,
        AJUDA,
        NAO_ENCONTRADO,
        AMBIGUO,
        SEM_REGISTRO,
        ENCONTRADO,
        ERRO
    }

    public ResultadoConsulta {
        Objects.requireNonNull(situacao, "situacao");
        // Cópias que ninguém altera depois; também recusam null dentro da lista.
        series = List.copyOf(series);
        candidatos = List.copyOf(candidatos);

        boolean encontrado = situacao == Situacao.ENCONTRADO;
        boolean comNome = encontrado || situacao == Situacao.SEM_REGISTRO;
        if (comNome != (nomeOficial != null)) {
            throw new IllegalArgumentException("nomeOficial existe só em ENCONTRADO e SEM_REGISTRO.");
        }
        if (encontrado != (formaCarga != null) || encontrado != (dataTreino != null)
                || encontrado == series.isEmpty()) {
            throw new IllegalArgumentException("formaCarga, dataTreino e séries existem só em ENCONTRADO.");
        }
        boolean ambiguo = situacao == Situacao.AMBIGUO;
        if (ambiguo && candidatos.size() < 2) {
            throw new IllegalArgumentException("AMBIGUO precisa de 2 candidatos ou mais.");
        }
        if (!ambiguo && (!candidatos.isEmpty() || haMaisCandidatos)) {
            throw new IllegalArgumentException("Candidatos existem só em AMBIGUO.");
        }
        if ((situacao == Situacao.ERRO) != (motivo != null)) {
            throw new IllegalArgumentException("motivo existe só em ERRO.");
        }
    }

    public static ResultadoConsulta jaGravada() {
        return semDados(Situacao.JA_GRAVADA);
    }

    public static ResultadoConsulta ajuda() {
        return semDados(Situacao.AJUDA);
    }

    public static ResultadoConsulta naoEncontrado() {
        return semDados(Situacao.NAO_ENCONTRADO);
    }

    public static ResultadoConsulta ambiguo(List<String> candidatos, boolean haMaisCandidatos) {
        return new ResultadoConsulta(Situacao.AMBIGUO, null, null, null, List.of(), candidatos, haMaisCandidatos,
                null);
    }

    public static ResultadoConsulta semRegistro(String nomeOficial) {
        return new ResultadoConsulta(Situacao.SEM_REGISTRO, Objects.requireNonNull(nomeOficial, "nomeOficial"),
                null, null, List.of(), List.of(), false, null);
    }

    public static ResultadoConsulta encontrado(String nomeOficial, String formaCarga, UltimoTreino treino) {
        return new ResultadoConsulta(Situacao.ENCONTRADO, Objects.requireNonNull(nomeOficial, "nomeOficial"),
                Objects.requireNonNull(formaCarga, "formaCarga"), treino.dataSessao(), treino.series(), List.of(),
                false, null);
    }

    /**
     * @param motivo só etapa e código ORA, nunca texto do usuário (vai para o log)
     */
    public static ResultadoConsulta erro(String motivo) {
        return new ResultadoConsulta(Situacao.ERRO, null, null, null, List.of(), List.of(), false,
                Objects.requireNonNull(motivo, "motivo"));
    }

    private static ResultadoConsulta semDados(Situacao situacao) {
        return new ResultadoConsulta(situacao, null, null, null, List.of(), List.of(), false, null);
    }

    /**
     * Só a situação. O toString() gerado pelo record traria nomes de exercício, que
     * nunca podem ir para log (regra 8 da fatia 7); mesma ideia do ResultadoDesfazer.
     */
    @Override
    public String toString() {
        return "ResultadoConsulta[" + situacao + "]";
    }
}
