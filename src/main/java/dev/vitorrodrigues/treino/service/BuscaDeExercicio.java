package dev.vitorrodrigues.treino.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import dev.vitorrodrigues.treino.modelo.ExercicioComApelidos;

/**
 * Argumento do /carga -> exercício do catálogo (fatia 7). Java puro: nada de banco,
 * nada de SQL. Recebe a lista que o ExercicioDAO.listarComApelidos já devolve
 * (nomes oficiais + apelidos DO USUÁRIO), a mesma que vai para o prompt.
 *
 * Separado do ConsultaService para ser testado sem banco (TesteBuscaDeExercicio).
 *
 * Normalização (normalizar), a MESMA em todos os passos: argumento, nomes
 * oficiais e apelidos, nas duas igualdades e nas palavras. Minúsculas, sem
 * diacríticos, e o que não for letra nem dígito ("°", vírgula, hífen, ponto) vira
 * espaço. "Puxada romana MÉDIA" e "puxada romana media" viram a mesma coisa;
 * "Rosca inclinada halter 45°" vira "rosca inclinada halter 45"; "remada," vira
 * "remada".
 *
 * Precedência, parando no primeiro passo que acertar:
 *   a) argumento == apelido do usuário;
 *   b) argumento == nome oficial;
 *   c) nomes oficiais que contêm TODAS as palavras do argumento, em qualquer ordem.
 * Em qualquer passo: 1 exercício -> esse; mais de 1 -> AMBIGUO (nenhum é
 * escolhido); no passo c, nenhum -> NAO_ENCONTRADO. O bot nunca chuta.
 *
 * Por que a igualdade vem antes das palavras: "puxada aberta" é o nome oficial
 * de um exercício e também está contido em "Puxada romana aberta". Só pelas
 * palavras, o nome exato ficaria ambíguo para sempre.
 */
public final class BuscaDeExercicio {

    /**
     * Maior argumento que vale a pena buscar: TR_EXERCICIO.NOME e
     * TR_APELIDO.APELIDO são VARCHAR2(100 CHAR).
     */
    public static final int MAX_CARACTERES = 100;

    /** Quantos candidatos o AMBIGUO lista, no máximo. */
    public static final int MAX_CANDIDATOS = 10;

    // Categoria M do Unicode: as marcas que o NFD separa da letra (acento agudo,
    // til, cedilha...). "é" vira "e" + U+0301; a regex tira o U+0301.
    private static final Pattern DIACRITICOS = Pattern.compile("\\p{M}");
    // Uma sequência de qualquer coisa que não seja letra (\p{L}) nem dígito decimal
    // (\p{Nd}): espaço, tab, "°", vírgula, hífen, ponto, emoji. Vira UM espaço, e
    // por isso os espaços repetidos já saem colapsados.
    private static final Pattern NAO_LETRA_NEM_DIGITO = Pattern.compile("[^\\p{L}\\p{Nd}]+");
    // Na forma normalizada as palavras são separadas por exatamente um espaço.
    private static final String ESPACO = " ";

    private BuscaDeExercicio() {
    }

    /** O que a busca decidiu. */
    public enum Situacao {
        ENCONTRADO,
        AMBIGUO,
        NAO_ENCONTRADO
    }

    /**
     * exercicio: só em ENCONTRADO.
     * candidatos: nomes oficiais, só em AMBIGUO, na ordem do catálogo, no máximo
     * MAX_CANDIDATOS. haMaisCandidatos: havia mais do que os listados.
     */
    public record Resultado(Situacao situacao, ExercicioComApelidos exercicio, List<String> candidatos,
            boolean haMaisCandidatos) {

        public Resultado {
            Objects.requireNonNull(situacao, "situacao");
            candidatos = List.copyOf(candidatos);
            if ((situacao == Situacao.ENCONTRADO) != (exercicio != null)) {
                throw new IllegalArgumentException("exercicio existe só em ENCONTRADO.");
            }
            if ((situacao == Situacao.AMBIGUO) == candidatos.isEmpty()) {
                throw new IllegalArgumentException("candidatos existem só em AMBIGUO.");
            }
        }
    }

    /**
     * Mais de MAX_CARACTERES: nenhum nome nem apelido do banco é tão longo. Quem
     * chama aplica à forma NORMALIZADA e responde "não encontrei" sem ler o
     * catálogo.
     * codePointCount e não length(): o VARCHAR2(n CHAR) conta um caractere fora do
     * plano básico (2 unidades UTF-16) como 1.
     */
    public static boolean longoDemais(String termo) {
        return termo.codePointCount(0, termo.length()) > MAX_CARACTERES;
    }

    /**
     * A forma normalizada, nesta ordem:
     * 1. minúsculas (Locale.ROOT: não dependem do idioma do computador; no turco,
     *    "I" viraria "ı", sem ponto);
     * 2. NFD sem os diacríticos ("é" -> "e"). ANTES do passo 3: a marca solta
     *    também não é letra, e viraria espaço no meio da palavra ("m dia");
     * 3. cada sequência do que não é letra nem dígito vira um espaço ("45°" ->
     *    "45 ", "remada," -> "remada ", "  " -> " ");
     * 4. sem espaço nas pontas.
     *
     * Vazio = não sobrou letra nem dígito (/carga sem argumento, ou só "???").
     * Normalizar de novo não muda nada: a forma normalizada é estável.
     */
    public static String normalizar(String texto) {
        String minusculas = texto.toLowerCase(Locale.ROOT);
        String decomposto = Normalizer.normalize(minusculas, Normalizer.Form.NFD);
        String semDiacriticos = DIACRITICOS.matcher(decomposto).replaceAll("");
        return NAO_LETRA_NEM_DIGITO.matcher(semDiacriticos).replaceAll(ESPACO).strip();
    }

    /**
     * Os passos a, b e c da classe.
     *
     * @param argumento o argumento do /carga, em qualquer forma: é normalizado aqui
     *                  (já normalizado, não muda). A forma normalizada não pode ser
     *                  vazia nem longoDemais: quem chama confere antes, para não ler
     *                  o catálogo à toa.
     * @param catalogo  ExercicioDAO.listarComApelidos(idUsuario)
     */
    public static Resultado buscar(String argumento, List<ExercicioComApelidos> catalogo) {
        String procurado = normalizar(argumento);
        // Vazio "conteria" todas as palavras (nenhuma) de todos os nomes.
        if (procurado.isEmpty() || longoDemais(procurado)) {
            throw new IllegalArgumentException("Argumento vazio ou longo demais: quem chama confere antes.");
        }

        // a) Apelido do usuário. Os apelidos já são gravados em minúsculas
        //    (CK_TR_APELIDO_APELIDO), mas o acento e a pontuação continuam:
        //    "francês" no banco, "frances" digitado. Por isso normaliza também.
        List<ExercicioComApelidos> porApelido = new ArrayList<>();
        for (ExercicioComApelidos exercicio : catalogo) {
            for (String apelido : exercicio.apelidos()) {
                if (normalizar(apelido).equals(procurado)) {
                    porApelido.add(exercicio);
                    break;
                }
            }
        }
        if (!porApelido.isEmpty()) {
            return decidir(porApelido);
        }

        // b) Nome oficial.
        List<ExercicioComApelidos> porNome = new ArrayList<>();
        for (ExercicioComApelidos exercicio : catalogo) {
            if (normalizar(exercicio.nome()).equals(procurado)) {
                porNome.add(exercicio);
            }
        }
        if (!porNome.isEmpty()) {
            return decidir(porNome);
        }

        // c) Palavras do argumento contidas no nome oficial. Só nomes oficiais:
        //    apelido vale só por igualdade (passo a).
        List<String> palavras = List.of(procurado.split(ESPACO));
        List<ExercicioComApelidos> porPalavras = new ArrayList<>();
        for (ExercicioComApelidos exercicio : catalogo) {
            if (contemTodasAsPalavras(normalizar(exercicio.nome()), palavras)) {
                porPalavras.add(exercicio);
            }
        }
        if (porPalavras.isEmpty()) {
            return new Resultado(Situacao.NAO_ENCONTRADO, null, List.of(), false);
        }
        return decidir(porPalavras);
    }

    // 1 -> esse exercício; mais de 1 -> AMBIGUO com os nomes, na ordem do catálogo
    // (grupamento, nome: a ordem do ExercicioDAO). Vale também para os passos a e b:
    // os UNIQUE do banco comparam o texto cru, então dois nomes (ou dois apelidos)
    // diferentes só no acento, "Tríceps corda" e "Triceps corda", ficam iguais
    // depois de normalizados. Nesse caso o bot pergunta em vez de escolher um.
    private static Resultado decidir(List<ExercicioComApelidos> achados) {
        if (achados.size() == 1) {
            return new Resultado(Situacao.ENCONTRADO, achados.get(0), List.of(), false);
        }
        List<String> nomes = new ArrayList<>();
        for (ExercicioComApelidos exercicio : achados.subList(0, Math.min(achados.size(), MAX_CANDIDATOS))) {
            nomes.add(exercicio.nome());
        }
        return new Resultado(Situacao.AMBIGUO, null, nomes, achados.size() > MAX_CANDIDATOS);
    }

    // DECISÃO DO VITOR (fatia 7): todas as palavras, em QUALQUER ordem. Se um dia
    // virar "trecho em sequência", a troca é só neste método.
    //
    // "Contém a palavra" = é uma das palavras do nome normalizado, inteira. Não é
    // pedaço nem prefixo: com pedaço, "pulldown w" casaria também com "Pulldown
    // corda" (o "w" de "pulldown") e ficaria ambíguo. O custo: palavra pela metade
    // ("rem", "4") não casa com nada.
    private static boolean contemTodasAsPalavras(String nomeNormalizado, List<String> palavras) {
        // HashSet, e não Set.of: Set.of recusa elemento repetido, e um nome pode
        // repetir uma palavra.
        Set<String> palavrasDoNome = new HashSet<>(List.of(nomeNormalizado.split(ESPACO)));
        return palavrasDoNome.containsAll(palavras);
    }
}
