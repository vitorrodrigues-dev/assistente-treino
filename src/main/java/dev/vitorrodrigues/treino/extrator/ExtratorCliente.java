package dev.vitorrodrigues.treino.extrator;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import dev.vitorrodrigues.treino.modelo.ExercicioComApelidos;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Relato em texto -> prompt v3 -> API da Anthropic -> RelatoExtraido.
 */
public class ExtratorCliente {

    private static final URI URI_MESSAGES = URI.create("https://api.anthropic.com/v1/messages");
    private static final String VERSAO_API = "2023-06-01";

    // Alias do Haiku 4.5 (modelo testado no spike do prompt v3).
    private static final String MODELO = "claude-haiku-4-5";

    // Teto de tokens da RESPOSTA (não do prompt). Um treino inteiro em JSON fica
    // bem abaixo disso. Se estourar, a resposta vem cortada (stop_reason
    // "max_tokens") e a extração falha alto em vez de tentar ler JSON pela metade.
    private static final int MAX_TOKENS = 4096;

    private static final Duration TIMEOUT_HTTP = Duration.ofSeconds(60);

    private static final DateTimeFormatter FORMATO_DATA_HOJE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    // Prompt v3, copiado sem alteração de docs/prompt-extrator.md.
    // Mudou aqui = nova versão do prompt: rodar a regressão (os 3 casos do
    // ExtratorSpike) e registrar no docs/prompt-extrator.md.
    private static final String PROMPT_V3 = """
            Você extrai dados de relatos de treino de musculação.
            Retorne APENAS um JSON válido, sem texto antes ou depois.

            Hoje é {DATA_HOJE}. A data do treino é a de hoje, a menos que o relato diga outra.

            Exercícios oficiais do usuário (use SOMENTE estes nomes):
            {LISTA_EXERCICIOS}

            Formato:
            {
              "observacao_dia": texto ou null,
              "series": [
                {
                  "exercicio_relatado": nome exatamente como o usuário disse,
                  "exercicio": nome oficial da lista, ou null se não casar,
                  "numero_serie": número,
                  "carga_kg": número ou null,
                  "carga_aproximada": true/false,
                  "repeticoes": número ou null,
                  "repeticoes_aproximadas": true/false,
                  "falha": true/false/null,
                  "percepcao": texto ou null
                }
              ],
              "duvidas": [lista do que ficou ambíguo]
            }

            Regras:
            - Uma entrada por série realizada.
            - NUNCA invente ou estime. Informação ausente = null.
            - Faixa de repetições ("7 a 8"): use o menor valor e marque aproximado.
            - Exercício que não casa com a lista: "exercicio" = null, preencha "exercicio_relatado", extraia todo o resto normalmente e registre em "duvidas". Nunca descarte uma série.
            - "falha" só é true ou false se o relato disser explicitamente. Caso contrário, null.
            - "observacao_dia" é só para contexto da sessão (academia, tempo, alimentação, troca de treino). Copie o que foi dito, sem interpretar. Não repita a percepção das séries.

            Relato:
            {RELATO}
            """;

    private final HttpClient cliente = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String chaveApi;

    public ExtratorCliente() {
        this.chaveApi = lerVariavel("ANTHROPIC_API_KEY");
    }

    /**
     * @param relato     texto da mensagem (ou transcrição do áudio)
     * @param dataHoje   data de envio da mensagem, já no fuso do usuário
     * @param exercicios catálogo com os apelidos do usuário (ExercicioDAO.listarComApelidos)
     */
    public RelatoExtraido extrair(String relato, LocalDate dataHoje, List<ExercicioComApelidos> exercicios)
            throws IOException, InterruptedException, ExtracaoException {
        String prompt = montarPrompt(relato, dataHoje, exercicios);
        String textoDoModelo = chamarApi(prompt);
        return LeitorRelatoJson.ler(textoDoModelo);
    }

    // Template fixo + parâmetros: mesma ideia do PreparedStatement.
    static String montarPrompt(String relato, LocalDate dataHoje, List<ExercicioComApelidos> exercicios) {
        StringBuilder lista = new StringBuilder();
        for (ExercicioComApelidos exercicio : exercicios) {
            if (!lista.isEmpty()) {
                lista.append('\n');
            }
            lista.append(exercicio.linhaDoPrompt());
        }

        // {RELATO} por último: o texto do usuário entra depois de todas as
        // trocas, então nada que ele escreva (ex.: "{DATA_HOJE}") vira marcador.
        return PROMPT_V3
                .replace("{DATA_HOJE}", dataHoje.format(FORMATO_DATA_HOJE))
                .replace("{LISTA_EXERCICIOS}", lista)
                .replace("{RELATO}", relato);
    }

    private String chamarApi(String prompt) throws IOException, InterruptedException, ExtracaoException {
        // Corpo montado com ObjectNode: o Jackson escapa aspas, quebras de linha
        // e acentos do relato. Concatenar String quebraria o JSON.
        ObjectNode corpo = mapper.createObjectNode();
        corpo.put("model", MODELO);
        corpo.put("max_tokens", MAX_TOKENS);
        ArrayNode mensagens = corpo.putArray("messages");
        ObjectNode mensagem = mensagens.addObject();
        mensagem.put("role", "user");
        mensagem.put("content", prompt);

        HttpRequest request = HttpRequest.newBuilder(URI_MESSAGES)
                .timeout(TIMEOUT_HTTP)
                .header("x-api-key", chaveApi)
                .header("anthropic-version", VERSAO_API)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(corpo)))
                .build();

        HttpResponse<String> response = cliente.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new ExtracaoException("A API da Anthropic respondeu HTTP " + response.statusCode()
                    + " (" + tipoDoErro(response.body()) + ").");
        }
        return textoDaResposta(response.body());
    }

    // Corpo de erro da API: {"type":"error","error":{"type":"...","message":"..."}}.
    // Só o "type" (ex.: authentication_error, not_found_error) vai para a exceção.
    private String tipoDoErro(String corpoResposta) {
        try {
            // at() devolve MissingNode se o caminho não existe; o isString() abaixo
            // decide, então não há valor padrão silencioso.
            JsonNode tipo = mapper.readTree(corpoResposta).at("/error/type");
            return tipo.isString() ? tipo.stringValue() : "corpo sem error.type";
        } catch (JacksonException e) {
            return "corpo não é JSON";
        }
    }

    // Corpo de sucesso: {"content":[{"type":"text","text":"..."}],"stop_reason":"end_turn",...}.
    private String textoDaResposta(String corpoResposta) throws ExtracaoException {
        JsonNode raiz;
        try {
            raiz = mapper.readTree(corpoResposta);
        } catch (JacksonException e) {
            throw new ExtracaoException("A API respondeu 200, mas o corpo não é JSON ("
                    + e.getClass().getSimpleName() + ").");
        }

        // Só "end_turn" garante que o modelo terminou a resposta. "max_tokens" =
        // JSON cortado no meio; "refusal" = o modelo se recusou a responder.
        JsonNode stopReason = raiz.at("/stop_reason");
        if (!stopReason.isString() || !stopReason.stringValue().equals("end_turn")) {
            String motivo = stopReason.isString() ? stopReason.stringValue() : "ausente";
            throw new ExtracaoException("A resposta do modelo não terminou normalmente (stop_reason: "
                    + motivo + ").");
        }

        JsonNode texto = raiz.at("/content/0/text");
        if (!texto.isString()) {
            throw new ExtracaoException("A resposta da API não tem texto em content[0].");
        }
        return texto.stringValue();
    }

    private static String lerVariavel(String nome) {
        String valor = System.getenv(nome);
        if (valor == null || valor.isBlank()) {
            throw new IllegalStateException("Variável de ambiente " + nome + " não definida ou vazia.");
        }
        return valor;
    }
}
