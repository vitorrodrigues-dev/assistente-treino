package dev.vitorrodrigues.treino.spike;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import dev.vitorrodrigues.treino.extrator.ExtracaoException;
import dev.vitorrodrigues.treino.extrator.ExtratorCliente;
import dev.vitorrodrigues.treino.extrator.LeitorRelatoJson;
import dev.vitorrodrigues.treino.extrator.RelatoExtraido;
import dev.vitorrodrigues.treino.extrator.SerieExtraida;
import dev.vitorrodrigues.treino.modelo.ExercicioComApelidos;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Testes offline do extrator: fidelidade do prompt v3 (ExtratorCliente x
 * docs/prompt-extrator.md) e LeitorRelatoJson. Sem API e sem banco, não custa
 * nada rodar. Rodar a partir da raiz do projeto (lê o doc do prompt).
 * Conferir a última linha: "RESULTADO: N ok, 0 falhas".
 *
 * "Fulano" marca texto que faz o papel de relato de outra pessoa: nenhuma
 * mensagem de exceção (nem a da causa) pode contê-lo.
 */
public class TesteLeitorRelatoJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Path DOC_DO_PROMPT = Path.of("docs", "prompt-extrator.md");

    private static final List<String> CAMPOS_OBRIGATORIOS_DA_SERIE = List.of(
            "exercicio_relatado", "numero_serie", "carga_aproximada", "repeticoes_aproximadas");

    // Resposta real do Haiku 4.5 (prompt v3) ao caso 1 do ExtratorSpike, relato do
    // Vitor, 08/10/2026: o JSON certo, dentro de uma cerca markdown.
    // Text block: o compilador grava as quebras de linha como \n mesmo que o
    // arquivo .java esteja com \r\n no Windows.
    private static final String RESPOSTA_REAL_CASO_1 = """
            ```json
            {
              "observacao_dia": null,
              "series": [
                {
                  "exercicio_relatado": "supino inclinado halter 3x10 com 24kg",
                  "exercicio": "Supino inclinado halter",
                  "numero_serie": 1,
                  "carga_kg": 24,
                  "carga_aproximada": false,
                  "repeticoes": 10,
                  "repeticoes_aproximadas": false,
                  "falha": null,
                  "percepcao": null
                },
                {
                  "exercicio_relatado": "supino inclinado halter 3x10 com 24kg",
                  "exercicio": "Supino inclinado halter",
                  "numero_serie": 2,
                  "carga_kg": 24,
                  "carga_aproximada": false,
                  "repeticoes": 10,
                  "repeticoes_aproximadas": false,
                  "falha": null,
                  "percepcao": null
                },
                {
                  "exercicio_relatado": "supino inclinado halter 3x10 com 24kg, falhei na última",
                  "exercicio": "Supino inclinado halter",
                  "numero_serie": 3,
                  "carga_kg": 24,
                  "carga_aproximada": false,
                  "repeticoes": 10,
                  "repeticoes_aproximadas": false,
                  "falha": true,
                  "percepcao": null
                },
                {
                  "exercicio_relatado": "puxada romana aberta 60kg 8",
                  "exercicio": "Puxada romana aberta",
                  "numero_serie": 1,
                  "carga_kg": 60,
                  "carga_aproximada": false,
                  "repeticoes": 8,
                  "repeticoes_aproximadas": false,
                  "falha": null,
                  "percepcao": null
                },
                {
                  "exercicio_relatado": "puxada romana aberta 60kg 8",
                  "exercicio": "Puxada romana aberta",
                  "numero_serie": 2,
                  "carga_kg": 60,
                  "carga_aproximada": false,
                  "repeticoes": 8,
                  "repeticoes_aproximadas": false,
                  "falha": null,
                  "percepcao": null
                },
                {
                  "exercicio_relatado": "puxada romana aberta 60kg 7 a 8",
                  "exercicio": "Puxada romana aberta",
                  "numero_serie": 3,
                  "carga_kg": 60,
                  "carga_aproximada": false,
                  "repeticoes": 7,
                  "repeticoes_aproximadas": true,
                  "falha": null,
                  "percepcao": null
                }
              ],
              "duvidas": []
            }
            ```""";

    private static int ok = 0;
    private static int falhas = 0;

    public static void main(String[] args) {
        fidelidadeDoPrompt();
        respostaReal();
        cercaAceita();
        bordasDaCerca();
        camposObrigatorios();
        parseCompleto();
        ausenteViraNull();
        falhasSemVazarTexto();

        System.out.println();
        System.out.println("RESULTADO: " + ok + " ok, " + falhas + " falhas");
        if (falhas > 0) {
            System.exit(1);
        }
    }

    // --- Fidelidade do prompt v3 ------------------------------------------------

    // O doc é onde o prompt é versionado e discutido; a constante do Java é o que
    // vai para a API. Os dois têm que ser iguais caractere por caractere: se um
    // mudar sozinho, a regressão estaria testando um prompt e o bot usando outro.
    private static void fidelidadeDoPrompt() {
        String doc;
        try {
            // \r\n -> \n: se o Git do Windows converter o .md para \r\n, muda a
            // quebra de linha, não o texto. O text block do Java sempre usa \n.
            doc = Files.readString(DOC_DO_PROMPT).replace("\r\n", "\n");
        } catch (IOException e) {
            falhas++;
            System.out.println("FALHA: não consegui ler " + DOC_DO_PROMPT
                    + " (rodar a partir da raiz do projeto): " + e.getClass().getSimpleName());
            return;
        }

        // Primeiro bloco ``` do doc: da linha seguinte à abertura até a quebra de
        // linha antes do fechamento (inclusive), igual ao fim do text block do Java.
        int abertura = doc.indexOf("```\n");
        int fechamento = abertura < 0 ? -1 : doc.indexOf("\n```", abertura + 4);
        if (fechamento < 0) {
            falhas++;
            System.out.println("FALHA: bloco ``` do prompt não encontrado em " + DOC_DO_PROMPT);
            return;
        }
        int inicioDoBloco = abertura + 4;
        String bloco = doc.substring(inicioDoBloco, fechamento + 1);
        int linhaDoBlocoNoDoc = 1 + (int) doc.substring(0, inicioDoBloco).chars().filter(c -> c == '\n').count();

        boolean constanteIgual = ExtratorCliente.PROMPT_V3.equals(bloco);
        confere("PROMPT_V3 == bloco do doc, caractere por caractere" + (constanteIgual ? ""
                        : " (1ª diferença: " + primeiraDiferenca(bloco, ExtratorCliente.PROMPT_V3, linhaDoBlocoNoDoc) + ")"),
                constanteIgual);

        // Os 3 testes que estavam no TesteExtratorOffline (spike da fatia 3).
        confere("bloco do doc tem {DATA_HOJE}, {LISTA_EXERCICIOS} e {RELATO} 1x cada",
                ocorrencias(bloco, "{DATA_HOJE}") == 1
                        && ocorrencias(bloco, "{LISTA_EXERCICIOS}") == 1
                        && ocorrencias(bloco, "{RELATO}") == 1);

        List<ExercicioComApelidos> exercicios = List.of(
                new ExercicioComApelidos(1L, "Crucifixo máquina", "Peito", "TOTAL", null, null, List.of("voador", "peck deck")),
                new ExercicioComApelidos(2L, "Remada unilateral máquina", "Costas", "POR_LADO", 8, 12, List.of()));
        String linhasDaLista = "- Crucifixo máquina | grupamento: Peito | carga: total | apelidos: voador, peck deck\n"
                + "- Remada unilateral máquina | grupamento: Costas | carga: por lado | faixa alvo: 8-12";
        String relato = "relato com {DATA_HOJE} e {LISTA_EXERCICIOS} dentro";

        String esperado = bloco.replace("{DATA_HOJE}", "08/10/2026")
                .replace("{LISTA_EXERCICIOS}", linhasDaLista)
                .replace("{RELATO}", relato);
        String montado = ExtratorCliente.montarPrompt(relato, LocalDate.of(2026, 10, 8), exercicios);
        confere("prompt montado == bloco do doc com as trocas", montado.equals(esperado));
        confere("marcadores dentro do relato ficam literais", montado.endsWith("Relato:\n" + relato + "\n"));
    }

    private static int ocorrencias(String texto, String trecho) {
        int total = 0;
        for (int i = texto.indexOf(trecho); i >= 0; i = texto.indexOf(trecho, i + trecho.length())) {
            total++;
        }
        return total;
    }

    // "linha L do doc, coluna C: doc tem 'x' (U+0078), Java tem 'y' (U+0079)".
    // O código U+ mostra diferenças invisíveis: espaço comum x espaço não
    // separável, acento composto x pré-composto, \n a mais ou a menos.
    private static String primeiraDiferenca(String doc, String java, int primeiraLinha) {
        int i = 0;
        while (i < doc.length() && i < java.length() && doc.charAt(i) == java.charAt(i)) {
            i++;
        }
        int linha = primeiraLinha;
        int coluna = 1;
        for (int j = 0; j < i; j++) {
            if (doc.charAt(j) == '\n') {
                linha++;
                coluna = 1;
            } else {
                coluna++;
            }
        }
        return "linha " + linha + " do doc, coluna " + coluna
                + ": doc tem " + descrever(doc, i) + ", Java tem " + descrever(java, i);
    }

    private static String descrever(String texto, int posicao) {
        if (posicao >= texto.length()) {
            return "o fim do texto";
        }
        char c = texto.charAt(posicao);
        String visivel = c == '\n' ? "\\n" : String.valueOf(c);
        return "'" + visivel + "' (U+" + String.format("%04X", (int) c) + ")";
    }

    // --- Resposta real do modelo -------------------------------------------------

    private static void respostaReal() {
        RelatoExtraido relato = deveLer("resposta real do caso 1", RESPOSTA_REAL_CASO_1);
        if (relato == null) {
            return;
        }
        List<SerieExtraida> series = relato.series();
        confere("caso 1: 6 séries", series.size() == 6);
        confere("caso 1: observacaoDia null", relato.observacaoDia() == null);
        confere("caso 1: duvidas vazia (veio [])", List.of().equals(relato.duvidas()));

        // falha true só na 3ª do supino; null (não false) nas outras 5.
        int terceirasDoSupino = 0;
        for (SerieExtraida serie : series) {
            if (eh(serie, "Supino inclinado halter", 3)) {
                terceirasDoSupino++;
                confere("caso 1: falha true na 3ª do supino", Boolean.TRUE.equals(serie.falha()));
            } else {
                confere("caso 1: falha null em " + serie.exercicio() + " série " + serie.numeroSerie(),
                        serie.falha() == null);
            }
        }
        confere("caso 1: existe uma única 3ª série do supino", terceirasDoSupino == 1);

        // "7 a 8" -> menor valor, marcado como aproximado.
        SerieExtraida terceiraDaPuxada = series.stream()
                .filter(serie -> eh(serie, "Puxada romana aberta", 3))
                .findFirst()
                .orElse(null);
        confere("caso 1: existe a 3ª série da puxada", terceiraDaPuxada != null);
        if (terceiraDaPuxada != null) {
            confere("caso 1: puxada série 3 com repeticoes 7",
                    Integer.valueOf(7).equals(terceiraDaPuxada.repeticoes()));
            confere("caso 1: puxada série 3 com repeticoes_aproximadas true",
                    Boolean.TRUE.equals(terceiraDaPuxada.repeticoesAproximadas()));
        }
    }

    private static boolean eh(SerieExtraida serie, String exercicio, int numeroSerie) {
        return exercicio.equals(serie.exercicio()) && Integer.valueOf(numeroSerie).equals(serie.numeroSerie());
    }

    // --- Cerca markdown ----------------------------------------------------------

    private static void cercaAceita() {
        String json = relatoCom(serieMinima());

        RelatoExtraido semRotulo = deveLer("cerca sem rótulo", "```\n" + json + "\n```");
        if (semRotulo != null) {
            confere("cerca sem rótulo: 1 série lida", semRotulo.series().size() == 1);
        }

        // strip() antes de procurar a cerca: espaços e quebras de linha em volta não atrapalham.
        RelatoExtraido comEspacos = deveLer("cerca com espaços em volta", "\n  ```json\n" + json + "\n```  \n");
        if (comEspacos != null) {
            confere("cerca com espaços em volta: 1 série lida", comEspacos.series().size() == 1);
        }

        // Entrada do teste antigo "cerca markdown", que antes tinha que falhar.
        RelatoExtraido antigo = deveLer("cerca do teste antigo", "```json\n{\"series\":[]}\n```");
        if (antigo != null) {
            confere("cerca do teste antigo: series vazia", antigo.series().isEmpty());
        }
    }

    // Só o par exato (abertura no começo, fechamento no fim) é removido. O resto
    // vai para o parse como veio e falha lá.
    private static void bordasDaCerca() {
        String json = relatoCom(serieMinima());
        String cercado = "```json\n" + json + "\n```";

        deveFalharNoParse("só abertura", "```json\n" + json);
        deveFalharNoParse("só fechamento", json + "\n```");
        deveFalharNoParse("duas cercas em sequência", cercado + "\n" + cercado);
        deveFalharNoParse("duas cercas, uma dentro da outra", "```json\n" + cercado + "\n```");
        deveFalharNoParse("texto antes da cerca", "Aqui está o JSON, Fulano:\n" + cercado);
        deveFalharNoParse("texto depois da cerca", cercado + "\nEspero ter ajudado, Fulano.");
        deveFalharNoParse("abertura sem quebra de linha", "```json " + json + "\n```");
    }

    // --- Campos obrigatórios da série --------------------------------------------

    private static void camposObrigatorios() {
        for (String campo : CAMPOS_OBRIGATORIOS_DA_SERIE) {
            ObjectNode semCampo = serieMinima();
            semCampo.remove(campo);
            deveFalhar(campo + " ausente", relatoCom(semCampo),
                    "campo obrigatório \"series[0]." + campo + "\"");

            ObjectNode campoNull = serieMinima();
            campoNull.putNull(campo);
            deveFalhar(campo + " null", relatoCom(campoNull), "\"series[0]." + campo + "\" veio null");
        }

        ObjectNode relatadoNumero = serieMinima();
        relatadoNumero.put("exercicio_relatado", 5);
        deveFalhar("exercicio_relatado como número", relatoCom(relatadoNumero), "series[0].exercicio_relatado");

        ObjectNode numeroTexto = serieMinima();
        numeroTexto.put("numero_serie", "1");
        deveFalhar("numero_serie como texto", relatoCom(numeroTexto), "series[0].numero_serie");

        ObjectNode aproxTexto = serieMinima();
        aproxTexto.put("repeticoes_aproximadas", "false");
        deveFalhar("repeticoes_aproximadas como texto 'false'", relatoCom(aproxTexto),
                "series[0].repeticoes_aproximadas");
    }

    // --- Testes antigos (spike da fatia 3), adaptados aos campos obrigatórios ------

    private static void parseCompleto() {
        RelatoExtraido relato = deveLer("caso completo", """
                {"observacao_dia":"academia cheia","series":[
                  {"exercicio_relatado":"pull down","exercicio":null,"numero_serie":1,"carga_kg":30,"carga_aproximada":false,
                   "repeticoes":12,"repeticoes_aproximadas":false,"falha":null,"percepcao":null},
                  {"exercicio_relatado":"puxada romana aberta","exercicio":"Puxada romana aberta","numero_serie":3,"carga_kg":22.5,
                   "carga_aproximada":true,"repeticoes":7,"repeticoes_aproximadas":true,"falha":true,"percepcao":"pesado"}],
                 "duvidas":["pull down: barra W ou corda?"]}""");
        if (relato == null) {
            return;
        }
        SerieExtraida s0 = relato.series().get(0);
        SerieExtraida s1 = relato.series().get(1);
        confere("observacao", "academia cheia".equals(relato.observacaoDia()));
        confere("exercicio null", s0.exercicio() == null);
        confere("falha null (não false)", s0.falha() == null);
        confere("carga 30 -> 30.0", Double.valueOf(30.0).equals(s0.cargaKg()));
        confere("carga 22.5", Double.valueOf(22.5).equals(s1.cargaKg()));
        confere("falha true", Boolean.TRUE.equals(s1.falha()));
        confere("duvidas", List.of("pull down: barra W ou corda?").equals(relato.duvidas()));
    }

    private static void ausenteViraNull() {
        // Série só com os 4 obrigatórios: os opcionais ausentes viram null (nunca 0/false).
        RelatoExtraido relato = deveLer("só os obrigatórios", relatoCom(serieMinima()));
        if (relato != null) {
            SerieExtraida serie = relato.series().get(0);
            confere("observacao ausente -> null", relato.observacaoDia() == null);
            confere("duvidas ausente -> null", relato.duvidas() == null);
            confere("opcionais ausentes -> null", serie.exercicio() == null && serie.cargaKg() == null
                    && serie.repeticoes() == null && serie.falha() == null && serie.percepcao() == null);
            confere("obrigatórios lidos", "supino do Fulano".equals(serie.exercicioRelatado())
                    && Integer.valueOf(1).equals(serie.numeroSerie())
                    && Boolean.FALSE.equals(serie.cargaAproximada())
                    && Boolean.FALSE.equals(serie.repeticoesAproximadas()));
        }

        RelatoExtraido duvidasVazia = deveLer("duvidas []", "{\"series\":[],\"duvidas\":[]}");
        if (duvidasVazia != null) {
            confere("duvidas [] -> lista vazia", duvidasVazia.duvidas().isEmpty());
        }
        RelatoExtraido seriesVazia = deveLer("series []", "{\"series\":[]}");
        if (seriesVazia != null) {
            confere("series [] -> lista vazia", seriesVazia.series().isEmpty());
        }

        ObjectNode cargaZero = serieMinima();
        cargaZero.put("carga_kg", 0);
        RelatoExtraido barraFixa = deveLer("carga 0 dita", relatoCom(cargaZero));
        if (barraFixa != null) {
            confere("carga 0 dita -> 0.0 (barra fixa sem peso)",
                    Double.valueOf(0.0).equals(barraFixa.series().get(0).cargaKg()));
        }
    }

    private static void falhasSemVazarTexto() {
        deveFalhar("series ausente", "{\"observacao_dia\":\"Fulano\"}", "series");
        deveFalhar("series null", "{\"series\":null}", "series");
        deveFalhar("series não-lista", "{\"series\":\"Fulano\"}", "series");
        deveFalhar("prosa", "Claro, Fulano! Aqui está: {\"series\":[]}", "não é um JSON válido");
        deveFalhar("texto depois do JSON", "{\"series\":[]} Espero ter ajudado, Fulano", "não é um JSON válido");
        deveFalhar("vazio", "", "não é um objeto JSON");
        deveFalhar("número solto", "123", "não é um objeto JSON");
        deveFalhar("JSON cortado", "{\"series\":[{\"exercicio_relatado\":\"supino do Fulano\",", "não é um JSON válido");

        ObjectNode cargaTexto = serieMinima();
        cargaTexto.put("carga_kg", "24kg do Fulano");
        deveFalhar("carga texto", relatoCom(cargaTexto), "series[0].carga_kg");

        ObjectNode repsFracionada = serieMinima();
        repsFracionada.put("repeticoes", 7.5);
        deveFalhar("reps fracionada", relatoCom(repsFracionada), "series[0].repeticoes");

        ObjectNode repsGigante = serieMinima();
        repsGigante.put("repeticoes", 99999999999L);
        deveFalhar("reps gigante", relatoCom(repsGigante), "series[0].repeticoes");

        ObjectNode falhaTexto = serieMinima();
        falhaTexto.put("falha", "Fulano disse que sim");
        deveFalhar("falha como texto", relatoCom(falhaTexto), "series[0].falha");

        ObjectNode aproxTexto = serieMinima();
        aproxTexto.put("carga_aproximada", "true");
        deveFalhar("aprox como texto 'true'", relatoCom(aproxTexto), "carga_aproximada");

        deveFalhar("série não-objeto", "{\"series\":[\"Fulano\"]}", "series[0]");
        deveFalhar("dúvida como objeto", "{\"series\":[],\"duvidas\":[{\"x\":\"Fulano\"}]}", "duvidas[0]");
        deveFalhar("campo renomeado", "{\"series\":[{\"carga\":24}]}", "series[0].carga");
        deveFalhar("campo extra na raiz", "{\"series\":[],\"data_treino\":\"hoje\"}", "data_treino");
    }

    // --- Montagem das entradas ---------------------------------------------------

    // Série mínima válida no formato v3: só os 4 campos obrigatórios. Cada teste
    // muda um campo, e assim a falha esperada é sempre a do campo mudado.
    private static ObjectNode serieMinima() {
        ObjectNode serie = MAPPER.createObjectNode();
        serie.put("exercicio_relatado", "supino do Fulano");
        serie.put("numero_serie", 1);
        serie.put("carga_aproximada", false);
        serie.put("repeticoes_aproximadas", false);
        return serie;
    }

    private static String relatoCom(ObjectNode serie) {
        return "{\"series\":[" + serie + "]}";
    }

    // --- Conferências ------------------------------------------------------------

    private static void confere(String nome, boolean condicao) {
        if (condicao) {
            ok++;
        } else {
            falhas++;
            System.out.println("FALHA: " + nome);
        }
    }

    // Caso que tem que ser lido. Se lançar, conta como falha e devolve null.
    private static RelatoExtraido deveLer(String nome, String texto) {
        try {
            return LeitorRelatoJson.ler(texto);
        } catch (ExtracaoException e) {
            falhas++;
            System.out.println("FALHA (recusou): " + nome + " -> " + e.getMessage());
            return null;
        }
    }

    private static void deveFalhar(String nome, String texto, String trechoEsperado) {
        try {
            LeitorRelatoJson.ler(texto);
            falhas++;
            System.out.println("FALHA (aceitou): " + nome);
        } catch (ExtracaoException e) {
            confere(nome + ": mensagem cita '" + trechoEsperado + "' [" + e.getMessage() + "]",
                    e.getMessage().contains(trechoEsperado));
            confere(nome + ": não vaza texto do relato", !vazaTexto(e));
            System.out.println("  ok " + nome + " -> " + e.getMessage());
        }
    }

    // JSON inválido: mensagem com linha e coluna, texto inteiro só em getRespostaBruta().
    private static void deveFalharNoParse(String nome, String texto) {
        try {
            LeitorRelatoJson.ler(texto);
            falhas++;
            System.out.println("FALHA (aceitou): " + nome);
        } catch (ExtracaoException e) {
            confere(nome + ": não é JSON válido [" + e.getMessage() + "]",
                    e.getMessage().contains("não é um JSON válido"));
            confere(nome + ": mensagem com linha e coluna", e.getMessage().matches(".*linha \\d+, coluna \\d+.*"));
            confere(nome + ": respostaBruta = texto original", texto.equals(e.getRespostaBruta()));
            confere(nome + ": não vaza texto do relato", !vazaTexto(e));
            System.out.println("  ok " + nome + " -> " + e.getMessage());
        }
    }

    private static boolean vazaTexto(ExtracaoException e) {
        return e.getMessage().contains("Fulano")
                || (e.getCause() != null && String.valueOf(e.getCause().getMessage()).contains("Fulano"));
    }
}
