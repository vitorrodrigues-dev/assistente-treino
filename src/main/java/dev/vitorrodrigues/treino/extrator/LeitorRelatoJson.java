package dev.vitorrodrigues.treino.extrator;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import tools.jackson.core.JacksonException;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.exc.JsonNodeException;

/**
 * Converte o texto devolvido pelo modelo (JSON no formato do prompt v3) em RelatoExtraido.
 *
 * Regras:
 * - uma cerca markdown em volta do JSON inteiro (```json ... ```) é removida;
 *   fora isso, o texto do modelo nunca é "consertado": texto que não é JSON falha;
 * - "series" é obrigatório: ausente ou null falha alto;
 * - em cada série, exercicio_relatado, numero_serie, carga_aproximada e
 *   repeticoes_aproximadas são obrigatórios (ver lerSerie);
 * - nos demais campos, ausente OU null no JSON -> null em Java (nunca 0 nem false);
 * - tipo errado (ex.: "carga_kg": "24kg") falha, em vez de virar null ou 0;
 * - campo que não existe no prompt v3 falha (ver CAMPOS_RELATO e CAMPOS_SERIE).
 *
 * As mensagens de erro citam o NOME e o TIPO do campo, nunca o valor: o valor
 * pode ser texto do relato de outra pessoa (regra de segurança do projeto).
 */
public class LeitorRelatoJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Campos do formato do prompt v3. Um campo fora desta lista quer dizer que o
    // modelo saiu do formato, ou que o prompt mudou e este leitor não. Sem esta
    // checagem, um campo renomeado (ex.: "carga" no lugar de "carga_kg") viraria
    // null em silêncio, igual a "não foi dito".
    private static final Set<String> CAMPOS_RELATO = Set.of("observacao_dia", "series", "duvidas");

    private static final Set<String> CAMPOS_SERIE = Set.of(
            "exercicio_relatado", "exercicio", "numero_serie",
            "carga_kg", "carga_aproximada",
            "repeticoes", "repeticoes_aproximadas",
            "falha", "percepcao");

    // O Haiku 4.5 devolve o JSON dentro de uma cerca markdown, apesar do "Retorne
    // APENAS um JSON" do prompt v3. Aberturas aceitas: com o rótulo json ou sem
    // rótulo, sempre seguidas de quebra de linha.
    private static final String CERCA = "```";
    private static final List<String> ABERTURAS_DA_CERCA = List.of("```json\n", "```\n");

    public static RelatoExtraido ler(String textoDoModelo) throws ExtracaoException {
        String semEspacos = textoDoModelo.strip();
        String abertura = aberturaDaCerca(semEspacos);
        // Sem cerca, o texto vai para o parse exatamente como veio (a linha e a
        // coluna de um erro batem com a resposta bruta).
        String json = abertura == null
                ? textoDoModelo
                : semEspacos.substring(abertura.length(), semEspacos.length() - CERCA.length());

        JsonNode raiz;
        try {
            raiz = MAPPER.readTree(json);
        } catch (JacksonException e) {
            // A mensagem do Jackson cita o trecho inválido (que pode ser do relato),
            // por isso nem ela nem a causa são repassadas: só a posição do erro.
            // O texto inteiro vai à parte, fora da mensagem (getRespostaBruta()).
            String depoisDaCerca = abertura == null ? "" : ", contando depois de tirar a cerca markdown";
            throw ExtracaoException.comRespostaBruta("A resposta do modelo não é um JSON válido ("
                    + e.getClass().getSimpleName() + ", " + posicaoDoErro(e) + depoisDaCerca + ").",
                    textoDoModelo);
        }

        // readTree("") devolve MissingNode sem lançar exceção, e "123" é um JSON
        // válido: os dois param aqui.
        if (!raiz.isObject()) {
            throw new ExtracaoException("A resposta do modelo não é um objeto JSON (veio "
                    + raiz.getNodeType() + ").");
        }
        recusarCamposDesconhecidos(raiz, CAMPOS_RELATO, "");

        JsonNode series;
        try {
            series = raiz.required("series");
        } catch (JsonNodeException e) {
            // A mensagem do required() só cita o nome do campo: a causa pode ir junto.
            throw new ExtracaoException("O JSON do modelo não tem o campo obrigatório \"series\".", e);
        }
        // required() aceita "series": null (devolve um NullNode). A lista é obrigatória.
        if (!series.isArray()) {
            throw tipoErrado("series", "uma lista", series);
        }

        List<SerieExtraida> seriesExtraidas = new ArrayList<>();
        for (int i = 0; i < series.size(); i++) {
            seriesExtraidas.add(lerSerie(series.get(i), "series[" + i + "]"));
        }

        return new RelatoExtraido(
                textoOuNull(raiz, "observacao_dia", ""),
                seriesExtraidas,
                listaDeTextosOuNull(raiz, "duvidas"));
    }

    // Devolve a abertura ("```json\n" ou "```\n") quando o texto começa com ela E
    // termina com ```; null quando não é exatamente esse par. Nada além disso: sem
    // procurar "{" no meio do texto, sem tirar texto antes ou depois. Cerca de um
    // lado só, texto em volta ou duas cercas seguem para o parse e falham lá:
    // aceitar isso esconderia que o modelo saiu do formato.
    private static String aberturaDaCerca(String texto) {
        for (String abertura : ABERTURAS_DA_CERCA) {
            // O tamanho mínimo impede que abertura e fechamento usem as mesmas crases.
            if (texto.startsWith(abertura) && texto.endsWith(CERCA)
                    && texto.length() >= abertura.length() + CERCA.length()) {
                return abertura;
            }
        }
        return null;
    }

    // Obrigatórios: o formato v3 sempre traz exercicio_relatado, numero_serie,
    // carga_aproximada e repeticoes_aproximadas (os dois últimos são true/false,
    // sem null). Se um deles falta, o modelo quebrou o formato: é falha, não
    // "informação ausente". Os outros campos podem ser null = não foi dito.
    private static SerieExtraida lerSerie(JsonNode serie, String caminho) throws ExtracaoException {
        if (!serie.isObject()) {
            throw tipoErrado(caminho, "um objeto", serie);
        }
        String prefixo = caminho + ".";
        recusarCamposDesconhecidos(serie, CAMPOS_SERIE, prefixo);

        return new SerieExtraida(
                textoObrigatorio(serie, "exercicio_relatado", prefixo),
                textoOuNull(serie, "exercicio", prefixo),
                inteiroObrigatorio(serie, "numero_serie", prefixo),
                numeroOuNull(serie, "carga_kg", prefixo),
                booleanoObrigatorio(serie, "carga_aproximada", prefixo),
                inteiroOuNull(serie, "repeticoes", prefixo),
                booleanoObrigatorio(serie, "repeticoes_aproximadas", prefixo),
                booleanoOuNull(serie, "falha", prefixo),
                textoOuNull(serie, "percepcao", prefixo));
    }

    // get() devolve null (do Java) quando o campo não existe; isNull() pega o null
    // do JSON. Os dois casos viram null. Nada de path().asXxx(): devolveria 0,
    // false ou "" em silêncio.
    private static JsonNode valorOuNull(JsonNode objeto, String campo) {
        JsonNode valor = objeto.get(campo);
        if (valor == null || valor.isNull()) {
            return null;
        }
        return valor;
    }

    // required() falha quando o campo não existe, mas aceita "campo": null
    // (devolve um NullNode). Por isso o isNull() à parte: null também é falha.
    private static JsonNode valorObrigatorio(JsonNode objeto, String campo, String prefixo)
            throws ExtracaoException {
        JsonNode valor;
        try {
            valor = objeto.required(campo);
        } catch (JsonNodeException e) {
            // A mensagem do required() só cita o nome do campo: a causa pode ir junto.
            throw new ExtracaoException("O JSON do modelo não tem o campo obrigatório \""
                    + prefixo + campo + "\".", e);
        }
        if (valor.isNull()) {
            throw new ExtracaoException("O campo obrigatório \"" + prefixo + campo + "\" veio null.");
        }
        return valor;
    }

    private static String textoOuNull(JsonNode objeto, String campo, String prefixo) throws ExtracaoException {
        JsonNode valor = valorOuNull(objeto, campo);
        return valor == null ? null : comoTexto(valor, prefixo + campo);
    }

    private static String textoObrigatorio(JsonNode objeto, String campo, String prefixo) throws ExtracaoException {
        return comoTexto(valorObrigatorio(objeto, campo, prefixo), prefixo + campo);
    }

    private static Integer inteiroOuNull(JsonNode objeto, String campo, String prefixo) throws ExtracaoException {
        JsonNode valor = valorOuNull(objeto, campo);
        return valor == null ? null : comoInteiro(valor, prefixo + campo);
    }

    private static Integer inteiroObrigatorio(JsonNode objeto, String campo, String prefixo) throws ExtracaoException {
        return comoInteiro(valorObrigatorio(objeto, campo, prefixo), prefixo + campo);
    }

    private static Double numeroOuNull(JsonNode objeto, String campo, String prefixo) throws ExtracaoException {
        JsonNode valor = valorOuNull(objeto, campo);
        return valor == null ? null : comoNumero(valor, prefixo + campo);
    }

    private static Boolean booleanoOuNull(JsonNode objeto, String campo, String prefixo) throws ExtracaoException {
        JsonNode valor = valorOuNull(objeto, campo);
        return valor == null ? null : comoBooleano(valor, prefixo + campo);
    }

    private static Boolean booleanoObrigatorio(JsonNode objeto, String campo, String prefixo) throws ExtracaoException {
        return comoBooleano(valorObrigatorio(objeto, campo, prefixo), prefixo + campo);
    }

    // Em cada conversão, o tipo é checado ANTES de chamar xxxValue(): o
    // xxxValue() do Jackson também falha com tipo errado, mas a mensagem dele
    // traz o valor (texto do relato).

    private static String comoTexto(JsonNode valor, String caminho) throws ExtracaoException {
        if (!valor.isString()) {
            throw tipoErrado(caminho, "texto", valor);
        }
        return valor.stringValue();
    }

    private static Integer comoInteiro(JsonNode valor, String caminho) throws ExtracaoException {
        // isIntegralNumber recusa 7.5; canConvertToInt recusa inteiro que não cabe em int.
        if (!valor.isIntegralNumber() || !valor.canConvertToInt()) {
            throw tipoErrado(caminho, "um número inteiro", valor);
        }
        return valor.intValue();
    }

    private static Double comoNumero(JsonNode valor, String caminho) throws ExtracaoException {
        // "24kg" (texto) é recusado: quem tira a unidade é o modelo, não o Java.
        if (!valor.isNumber()) {
            throw tipoErrado(caminho, "um número", valor);
        }
        return valor.doubleValue();
    }

    private static Boolean comoBooleano(JsonNode valor, String caminho) throws ExtracaoException {
        // "true" entre aspas (texto) é recusado: só o booleano do JSON vale.
        if (!valor.isBoolean()) {
            throw tipoErrado(caminho, "true ou false", valor);
        }
        return valor.booleanValue();
    }

    private static List<String> listaDeTextosOuNull(JsonNode objeto, String campo) throws ExtracaoException {
        JsonNode valor = valorOuNull(objeto, campo);
        if (valor == null) {
            return null;
        }
        if (!valor.isArray()) {
            throw tipoErrado(campo, "uma lista", valor);
        }
        List<String> textos = new ArrayList<>();
        for (int i = 0; i < valor.size(); i++) {
            textos.add(comoTexto(valor.get(i), campo + "[" + i + "]"));
        }
        return textos;
    }

    private static void recusarCamposDesconhecidos(JsonNode objeto, Set<String> conhecidos, String prefixo)
            throws ExtracaoException {
        for (String nome : objeto.propertyNames()) {
            if (!conhecidos.contains(nome)) {
                throw new ExtracaoException("O campo \"" + prefixo + nome
                        + "\" não existe no formato do prompt v3.");
            }
        }
    }

    // Linha e coluna (contadas a partir de 1) onde o Jackson parou de ler. Só os
    // números: getOriginalMessage() também cita o caractere inválido.
    private static String posicaoDoErro(JacksonException e) {
        TokenStreamLocation local = e.getLocation();
        // getLocation() pode ser null, e o Jackson usa -1 para linha/coluna desconhecida.
        if (local == null || local.getLineNr() < 1 || local.getColumnNr() < 1) {
            return "posição desconhecida";
        }
        return "linha " + local.getLineNr() + ", coluna " + local.getColumnNr();
    }

    // Só o TIPO que veio (STRING, NUMBER, BOOLEAN...), nunca o valor.
    private static ExtracaoException tipoErrado(String caminho, String esperado, JsonNode valor) {
        return new ExtracaoException("O campo \"" + caminho + "\" deveria ser " + esperado
                + ", mas veio " + valor.getNodeType() + ".");
    }
}
