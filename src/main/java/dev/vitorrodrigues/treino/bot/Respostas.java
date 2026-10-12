package dev.vitorrodrigues.treino.bot;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dev.vitorrodrigues.treino.extrator.SerieExtraida;
import dev.vitorrodrigues.treino.modelo.ResultadoRegistro;
import dev.vitorrodrigues.treino.modelo.ResultadoRegistro.SerieGravada;

/**
 * ResultadoRegistro -> texto da resposta no chat (seções 4 e 5 da fatia 6a).
 *
 * A confirmação é montada SÓ com os campos estruturados (números, booleanos, nome
 * do catálogo), nunca com o texto que o usuário mandou. Exceções: o
 * exercicio_relatado de um exercício não reconhecido (é o que ele precisa ver
 * para corrigir) e as dúvidas. Tudo isso vai só para o chat da própria pessoa.
 *
 * As palavras vêm de Textos (provisórias). Os símbolos abaixo são a estrutura
 * decidida da confirmação, não provisória.
 */
final class Respostas {

    private static final DateTimeFormatter FORMATO_DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final String AUSENTE = "—";       // não foi dito: nunca 0
    private static final String APROXIMADO = "~";
    private static final String ORDINAL = "ª";       // 1ª, 2ª (série é feminino)
    private static final String VEZES = " × ";
    private static final String KG = " kg";
    private static final String RECUO = "  ";
    private static final String MARCADOR_DUVIDA = "- ";

    private Respostas() {
    }

    /**
     * @return o texto da resposta, ou null quando o bot não deve responder
     */
    static String para(ResultadoRegistro resultado) {
        return switch (resultado.situacao()) {
            // Silêncio: no caso comum o bot reiniciou e a confirmação desta mesma
            // mensagem já tinha sido enviada. Responder de novo duplicaria.
            case JA_GRAVADA -> null;
            case REGISTRADO -> confirmacao(resultado);
            case SEM_SERIES -> comDuvidas(Textos.SEM_SERIES, resultado.duvidas());
            // Decide pelo tipoFalha (campo tipado), nunca pelo texto do motivo.
            case NAO_REGISTRADO -> switch (resultado.tipoFalha()) {
                case OBSERVACAO_CHEIA -> Textos.OBSERVACAO_CHEIA;
                case OUTRA -> Textos.NAO_REGISTRADO;
            };
        };
    }

    // Blocos separados por uma linha em branco:
    // data / séries / "anotei a observação" / dúvidas. Bloco vazio não aparece.
    private static String confirmacao(ResultadoRegistro resultado) {
        List<String> blocos = new ArrayList<>();
        // Data sempre explícita: "ontem" ainda grava com a data de envio (o prompt
        // v3 não tem campo de data), e é aqui que o usuário percebe.
        blocos.add(Textos.REGISTRADO_EM.formatted(resultado.dataSessao().format(FORMATO_DATA)));
        if (!resultado.series().isEmpty()) {
            blocos.add(series(resultado.series()));
        }
        // Só avisa que anotou, sem repetir o texto: a observação pode ser longa,
        // e repeti-la não ajuda a conferir números.
        if (resultado.observacaoAnotada()) {
            blocos.add(Textos.OBSERVACAO_ANOTADA);
        }
        if (!resultado.duvidas().isEmpty()) {
            blocos.add(duvidas(resultado.duvidas()));
        }
        return String.join("\n\n", blocos);
    }

    // Séries CONSECUTIVAS do mesmo exercício ficam sob um cabeçalho só, na ordem
    // do relato. Não reordena nem junta grupos separados: se o usuário voltou ao
    // exercício depois de outro, a confirmação mostra isso.
    private static String series(List<SerieGravada> series) {
        StringBuilder texto = new StringBuilder();
        SerieGravada anterior = null;
        for (SerieGravada atual : series) {
            if (anterior == null || !mesmoExercicio(anterior, atual)) {
                if (anterior != null) {
                    texto.append('\n');
                }
                texto.append(cabecalho(atual));
            }
            texto.append('\n').append(RECUO).append(linha(atual.serie()));
            anterior = atual;
        }
        return texto.toString();
    }

    // Com id: mesmo exercício = mesmo id do catálogo. Sem id dos dois lados: o
    // único nome que existe é o exercicio_relatado. Um com id e outro sem são
    // exercícios diferentes.
    private static boolean mesmoExercicio(SerieGravada a, SerieGravada b) {
        if (a.idExercicio() != null || b.idExercicio() != null) {
            return Objects.equals(a.idExercicio(), b.idExercicio());
        }
        return a.serie().exercicioRelatado().equals(b.serie().exercicioRelatado());
    }

    private static String cabecalho(SerieGravada serie) {
        if (serie.idExercicio() != null) {
            // Com id, serie().exercicio() É o nome do catálogo: o service só
            // resolve o id quando esse nome é igual (equals) a um nome do catálogo.
            return serie.serie().exercicio();
        }
        return Textos.NAO_RECONHECI.formatted(serie.serie().exercicioRelatado());
    }

    // "1ª: ~22,5 kg × 10 (falha)". Percepção não aparece (seção 4).
    private static String linha(SerieExtraida serie) {
        StringBuilder linha = new StringBuilder();
        linha.append(serie.numeroSerie()).append(ORDINAL).append(": ")
                .append(carga(serie.cargaKg(), serie.cargaAproximada()))
                .append(VEZES)
                .append(repeticoes(serie.repeticoes(), serie.repeticoesAproximadas()));
        // Três estados: true, false e null (não foi dito). null não mostra nada:
        // mostrar "(sem falha)" seria afirmar o que o usuário não disse.
        if (Boolean.TRUE.equals(serie.falha())) {
            linha.append(' ').append(Textos.FALHA);
        } else if (Boolean.FALSE.equals(serie.falha())) {
            linha.append(' ').append(Textos.SEM_FALHA);
        }
        return linha.toString();
    }

    // Ausente = "—", nunca 0: 0 kg é um valor de verdade (barra fixa sem peso).
    // O "~" só existe junto de um número.
    private static String carga(Double kg, Boolean aproximada) {
        if (kg == null) {
            return AUSENTE;
        }
        return aproximado(aproximada) + semArredondar(kg) + KG;
    }

    private static String repeticoes(Integer repeticoes, Boolean aproximadas) {
        if (repeticoes == null) {
            return AUSENTE;
        }
        return aproximado(aproximadas) + repeticoes;
    }

    private static String aproximado(Boolean aproximado) {
        return Boolean.TRUE.equals(aproximado) ? APROXIMADO : "";
    }

    // Vírgula decimal e sem arredondar: 22.5 -> "22,5", 40.0 -> "40", 100.0 -> "100".
    // BigDecimal.valueOf usa o texto curto do double, o mesmo número que foi
    // gravado (JdbcUtil). stripTrailingZeros tira o ".0"; toPlainString evita o
    // "1E+2" que o stripTrailingZeros produz em 100. Nada de String.format("%.2f"):
    // escreveria "22,50" e arredondaria.
    private static String semArredondar(double kg) {
        return BigDecimal.valueOf(kg).stripTrailingZeros().toPlainString().replace('.', ',');
    }

    private static String comDuvidas(String texto, List<String> duvidas) {
        return duvidas.isEmpty() ? texto : texto + "\n\n" + duvidas(duvidas);
    }

    // Só aparece com a lista não vazia (quem chama confere).
    private static String duvidas(List<String> duvidas) {
        StringBuilder texto = new StringBuilder(Textos.PONTOS_NAO_CLAROS);
        for (String duvida : duvidas) {
            texto.append('\n').append(MARCADOR_DUVIDA).append(duvida);
        }
        return texto.toString();
    }
}
