package dev.vitorrodrigues.treino.bot;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import dev.vitorrodrigues.treino.extrator.SerieExtraida;
import dev.vitorrodrigues.treino.modelo.ResultadoConsulta;
import dev.vitorrodrigues.treino.modelo.ResultadoDesfazer;
import dev.vitorrodrigues.treino.modelo.ResultadoRegistro;
import dev.vitorrodrigues.treino.modelo.ResultadoRegistro.SerieGravada;
import dev.vitorrodrigues.treino.modelo.SerieConsultada;
import dev.vitorrodrigues.treino.modelo.SerieResumida;

/**
 * ResultadoRegistro -> texto da resposta no chat (seções 4 e 5 da fatia 6a).
 * Fatia 6c: ResultadoDesfazer -> resposta do /desfazer.
 * Fatia 7: ResultadoConsulta -> resposta do /carga.
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
    private static final String MARCADOR_CANDIDATO = "- ";

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
    // data / séries / "anotei a observação" / dúvidas / como corrigir (6c).
    // Bloco vazio não aparece.
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
        // Sempre por último, e só aqui (REGISTRADO): é a única situação em que há
        // registro para desfazer. Bloco próprio, para não parecer mais uma dúvida.
        blocos.add(Textos.DICA_DESFAZER);
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
        return linha(serie.numeroSerie(), serie.cargaKg(), serie.cargaAproximada(), serie.repeticoes(),
                serie.repeticoesAproximadas(), serie.falha());
    }

    // A linha de uma série, campo a campo: a confirmação do registro (6a) e a
    // resposta do /carga (fatia 7) usam esta mesma, para o número sair igual nas
    // duas ("22,5 kg", "—", "~").
    private static String linha(Integer numeroSerie, Double kg, Boolean cargaAproximada, Integer repeticoes,
            Boolean repeticoesAproximadas, Boolean falha) {
        StringBuilder linha = new StringBuilder();
        linha.append(numeroSerie).append(ORDINAL).append(": ")
                .append(carga(kg, cargaAproximada))
                .append(VEZES)
                .append(repeticoes(repeticoes, repeticoesAproximadas));
        // Três estados: true, false e null (não foi dito). null não mostra nada:
        // mostrar "(sem falha)" seria afirmar o que o usuário não disse.
        if (Boolean.TRUE.equals(falha)) {
            linha.append(' ').append(Textos.FALHA);
        } else if (Boolean.FALSE.equals(falha)) {
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

    // --- /desfazer (fatia 6c) ------------------------------------------------------------

    /**
     * Resposta do /desfazer.
     *
     * @return o texto, ou null (silêncio) só no JA_APLICADO
     */
    static String para(ResultadoDesfazer resultado) {
        return switch (resultado.situacao()) {
            case DESFEITO -> desfeito(resultado);
            case NADA_PARA_DESFAZER -> Textos.NADA_PARA_DESFAZER;
            // Silêncio, como no JA_GRAVADA: é a reentrega de um comando já
            // aplicado. A resposta original já foi enviada, ou se perdeu na queda;
            // responder de novo (com o resumo de quê?) só confundiria.
            case JA_APLICADO -> null;
        };
    }

    // Uma linha embaixo da outra, sem linha em branco (o exemplo da 6c): cabeçalho
    // com a data / um resumo por exercício, ou "sem séries" / aviso da observação.
    // Só exercício e contagem, sem carga nem repetições: basta para o usuário
    // reconhecer QUAL registro saiu.
    private static String desfeito(ResultadoDesfazer resultado) {
        List<String> linhas = new ArrayList<>();
        // A data da SESSÃO da mensagem desfeita, não a de hoje: com dois treinos nas
        // 24 h (ontem à noite e hoje cedo), é ela que diz qual dos dois saiu.
        linhas.add(Textos.DESFEITO_REGISTRO_DE.formatted(resultado.dataSessao().format(FORMATO_DATA)));
        if (resultado.series().isEmpty()) {
            linhas.add(Textos.DESFEITO_SEM_SERIES);
        } else {
            linhas.addAll(resumoPorExercicio(resultado.series()));
        }
        // A observação é concatenada na sessão, então não dá para saber se o texto
        // veio desta mensagem: o aviso sai sempre que a sessão tiver observação.
        if (resultado.sessaoComObservacao()) {
            linhas.add(Textos.OBSERVACAO_NAO_DESFEITA);
        }
        return String.join("\n", linhas);
    }

    // "Um resumo por exercício, na ordem das séries" (regra 3 da 6c): UMA linha por
    // exercício, na ordem em que ele aparece pela primeira vez; a contagem soma
    // todas as séries dele, mesmo que o usuário tenha voltado ao exercício depois
    // de outro. (A confirmação do registro, acima, mostra grupos consecutivos: lá
    // se confere série a série; aqui só se reconhece o registro.)
    private static List<String> resumoPorExercicio(List<SerieResumida> series) {
        // LinkedHashMap: guarda a ordem da primeira aparição.
        Map<ChaveExercicio, String> nomes = new LinkedHashMap<>();
        Map<ChaveExercicio, Integer> quantidades = new HashMap<>();
        for (SerieResumida serie : series) {
            ChaveExercicio chave = ChaveExercicio.de(serie);
            nomes.putIfAbsent(chave, nomeNoResumo(serie));
            quantidades.merge(chave, 1, Integer::sum);
        }
        List<String> linhas = new ArrayList<>();
        for (Map.Entry<ChaveExercicio, String> exercicio : nomes.entrySet()) {
            linhas.add(exercicio.getValue() + " " + quantidade(quantidades.get(exercicio.getKey())));
        }
        return linhas;
    }

    // O que é "o mesmo exercício": o mesmo critério do mesmoExercicio da
    // confirmação. Com id, o id do catálogo (dois relatos diferentes, "cavalinho"
    // e "remada cavalinho", são o mesmo exercício); sem id, o exercicio_relatado.
    // Um com id e outro sem são exercícios diferentes: o record compara os dois
    // campos, e o relatado só é preenchido quando não há id.
    private record ChaveExercicio(Long idExercicio, String relatado) {

        static ChaveExercicio de(SerieResumida serie) {
            return serie.idExercicio() != null
                    ? new ChaveExercicio(serie.idExercicio(), null)
                    : new ChaveExercicio(null, serie.exercicioRelatado());
        }
    }

    // Com id, o nome do CATÁLOGO (lido de TR_EXERCICIO), nunca o que o modelo
    // devolveu. Sem id, o que o usuário escreveu: é o que ele reconhece.
    private static String nomeNoResumo(SerieResumida serie) {
        return serie.idExercicio() != null
                ? serie.nomeOficial()
                : Textos.DESFEITO_NAO_RECONHECIDO.formatted(serie.exercicioRelatado());
    }

    private static String quantidade(int series) {
        return series == 1 ? Textos.UMA_SERIE : Textos.VARIAS_SERIES.formatted(series);
    }

    // --- /carga (fatia 7) ----------------------------------------------------------------

    /**
     * Resposta do /carga.
     *
     * @return o texto, ou null (silêncio) só no JA_GRAVADA
     */
    static String para(ResultadoConsulta resultado) {
        return switch (resultado.situacao()) {
            // Silêncio, como no JA_GRAVADA do registro: é a reentrega de uma
            // consulta já respondida (ou cuja resposta se perdeu na queda).
            case JA_GRAVADA -> null;
            case AJUDA -> Textos.CARGA_AJUDA;
            case NAO_ENCONTRADO -> Textos.CARGA_NAO_ENCONTREI;
            case AMBIGUO -> candidatos(resultado);
            case SEM_REGISTRO -> Textos.CARGA_SEM_REGISTRO.formatted(resultado.nomeOficial());
            case ENCONTRADO -> ultimoTreino(resultado);
            case ERRO -> Textos.NAO_CONSEGUI_CONSULTAR;
        };
    }

    // Cabeçalho (nome oficial, forma da carga, data) e uma linha por série, com o
    // mesmo recuo e a mesma linha da confirmação. Data sempre explícita: "último
    // treino" sem data não diz se foi ontem ou mês passado. Percepção e observação
    // do dia não aparecem (nem são lidas).
    private static String ultimoTreino(ResultadoConsulta resultado) {
        List<String> linhas = new ArrayList<>();
        linhas.add(Textos.CARGA_ULTIMO_TREINO.formatted(resultado.nomeOficial(),
                formaCarga(resultado.formaCarga()), resultado.dataTreino().format(FORMATO_DATA)));
        for (SerieConsultada serie : resultado.series()) {
            linhas.add(RECUO + linha(serie.numeroSerie(), serie.cargaKg(), serie.cargaAproximada(),
                    serie.repeticoes(), serie.repeticoesAproximadas(), serie.falha()));
        }
        return String.join("\n", linhas);
    }

    // Código do catálogo -> texto do cabeçalho. Valor fora do
    // CK_TR_EXERCICIO_FORMA_CARGA falha alto (cai no erro inesperado do loop) em vez
    // de sair um cabeçalho sem a forma da carga.
    private static String formaCarga(String codigo) {
        return switch (codigo) {
            case "POR_LADO" -> Textos.CARGA_POR_LADO;
            case "TOTAL" -> Textos.CARGA_TOTAL;
            default -> throw new IllegalStateException("FORMA_CARGA fora do CK_TR_EXERCICIO_FORMA_CARGA.");
        };
    }

    // Os nomes oficiais, um por linha, na ordem do catálogo; o aviso de "seja mais
    // específico" só quando os listados não são todos.
    private static String candidatos(ResultadoConsulta resultado) {
        List<String> linhas = new ArrayList<>();
        linhas.add(Textos.CARGA_QUAL_DELES);
        for (String nome : resultado.candidatos()) {
            linhas.add(MARCADOR_CANDIDATO + nome);
        }
        if (resultado.haMaisCandidatos()) {
            linhas.add(Textos.CARGA_SEJA_MAIS_ESPECIFICO);
        }
        return String.join("\n", linhas);
    }
}
