package dev.vitorrodrigues.treino.modelo;

import java.time.LocalDate;
import java.util.List;

import dev.vitorrodrigues.treino.extrator.SerieExtraida;

/**
 * O que o RegistroService fez com uma mensagem. A fatia 6 usa isto para montar
 * a confirmação no chat.
 *
 * situacao:
 * - JA_GRAVADA: a mesma mensagem já estava no banco (o bot caiu antes de avançar
 *   o offset). Nada foi feito de novo, nem a chamada à API.
 * - REGISTRADO: sessão e séries gravadas (séries pode ser vazia quando a mensagem
 *   só trouxe observação do dia).
 * - SEM_SERIES: o relato não tinha série nem observação. Só a mensagem crua ficou.
 * - NAO_REGISTRADO: algo falhou (extração, validação ou banco). Só a mensagem crua
 *   ficou, sem sessão, para reprocessar depois.
 *
 * dataSessao: nunca null. A data que a sessão tem (REGISTRADO) ou teria (demais
 * casos). A confirmação mostra a data porque "ontem" ainda grava como hoje
 * (prompt v3 não tem campo de data; ver escopo).
 *
 * series: nunca null; vazia fora do REGISTRADO.
 *
 * duvidas: nunca null. As do modelo + as que o service acrescenta. Vazia em
 * JA_GRAVADA e NAO_REGISTRADO (não há o que confirmar). Quando o modelo não
 * mandou "duvidas" (null), o service não inventa uma dúvida por isso: fica só o
 * que o service acrescentou, ou lista vazia. Pode conter texto do usuário (o
 * modelo cita o relato): serve para o chat da própria pessoa, nunca para log.
 *
 * motivo: só em NAO_REGISTRADO, e NUNCA com texto do usuário nem resposta do
 * modelo: só tipo de erro, nome de campo, limite e código ORA. Por isso pode ir
 * para log.
 *
 * Criado só pelas fábricas abaixo (mesma ideia do ResultadoInsercao): elas
 * impedem estados sem sentido, como "registrado" com motivo de erro.
 */
public record ResultadoRegistro(
        Situacao situacao,
        LocalDate dataSessao,
        List<SerieGravada> series,
        List<String> duvidas,
        String motivo) {

    public enum Situacao {
        JA_GRAVADA,
        REGISTRADO,
        SEM_SERIES,
        NAO_REGISTRADO
    }

    /**
     * Uma série gravada, com o ID_EXERCICIO que o service resolveu.
     * O SerieExtraida não tem esse campo (é o formato do prompt v3), por isso o par.
     *
     * @param idExercicio null = o modelo não casou com a lista, ou devolveu um
     *        nome que não existe no catálogo (o exercício fica para confirmar)
     */
    public record SerieGravada(SerieExtraida serie, Long idExercicio) {
    }

    public ResultadoRegistro {
        // List.copyOf: cópia que ninguém altera depois (nem o service, que montou
        // a lista original). Também recusa null dentro da lista.
        series = List.copyOf(series);
        duvidas = List.copyOf(duvidas);
    }

    public static ResultadoRegistro jaGravada(LocalDate dataSessao) {
        return new ResultadoRegistro(Situacao.JA_GRAVADA, dataSessao, List.of(), List.of(), null);
    }

    public static ResultadoRegistro registrado(LocalDate dataSessao, List<SerieGravada> series,
            List<String> duvidas) {
        return new ResultadoRegistro(Situacao.REGISTRADO, dataSessao, series, duvidas, null);
    }

    public static ResultadoRegistro semSeries(LocalDate dataSessao, List<String> duvidas) {
        return new ResultadoRegistro(Situacao.SEM_SERIES, dataSessao, List.of(), duvidas, null);
    }

    /**
     * @param motivo sem texto do usuário (ver o javadoc da classe)
     */
    public static ResultadoRegistro naoRegistrado(LocalDate dataSessao, String motivo) {
        return new ResultadoRegistro(Situacao.NAO_REGISTRADO, dataSessao, List.of(), List.of(), motivo);
    }
}
