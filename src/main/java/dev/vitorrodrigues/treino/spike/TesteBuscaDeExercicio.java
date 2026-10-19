package dev.vitorrodrigues.treino.spike;

import java.util.ArrayList;
import java.util.List;

import dev.vitorrodrigues.treino.modelo.ExercicioComApelidos;
import dev.vitorrodrigues.treino.service.BuscaDeExercicio;
import dev.vitorrodrigues.treino.service.BuscaDeExercicio.Resultado;
import dev.vitorrodrigues.treino.service.BuscaDeExercicio.Situacao;

/**
 * Teste offline do casamento do /carga (fatia 7): BuscaDeExercicio, sem banco e sem
 * API. Não custa nada rodar.
 *
 * O catálogo é montado à mão: os 46 nomes do db/03-plano.sql (na ordem que o
 * ExercicioDAO devolve: grupamento, nome) com os apelidos do Vitor do
 * db/04-usuario.sql, mais catálogos pequenos inventados para os casos que o
 * catálogo real não tem (apelido que perde para nada, mais de 10 candidatos,
 * nomes iguais depois de tirar o acento). Se o 03-plano.sql mudar, este catálogo
 * não muda junto: o ConsultaSpike confere os critérios de aceite contra o
 * catálogo de verdade, no banco.
 *
 * Rodar (da raiz do projeto):
 * ./mvnw -q compile exec:java "-Dexec.mainClass=dev.vitorrodrigues.treino.spike.TesteBuscaDeExercicio"
 * Conferir a última linha: "RESULTADO: N ok, 0 falhas".
 */
public class TesteBuscaDeExercicio {

    private static final List<ExercicioComApelidos> CATALOGO = List.of(
            ex(1, "Abdominal na corda", "Abdômen", "TOTAL", "crunch"),
            ex(2, "Rosca Scott barra", "Bíceps", "TOTAL"),
            ex(3, "Rosca Scott máquina", "Bíceps", "TOTAL"),
            ex(4, "Rosca inclinada halter 45°", "Bíceps", "POR_LADO"),
            ex(5, "Rosca unilateral polia", "Bíceps", "POR_LADO"),
            ex(6, "Barra fixa", "Costas", "TOTAL"),
            ex(7, "Pulldown barra W", "Costas", "TOTAL"),
            ex(8, "Pulldown corda", "Costas", "TOTAL"),
            ex(9, "Puxada aberta", "Costas", "TOTAL"),
            ex(10, "Puxada romana aberta", "Costas", "TOTAL"),
            ex(11, "Puxada romana fechada", "Costas", "TOTAL"),
            ex(12, "Puxada romana média", "Costas", "TOTAL"),
            ex(13, "Puxada triângulo", "Costas", "TOTAL"),
            ex(14, "Puxada unilateral polia", "Costas", "POR_LADO"),
            ex(15, "Remada cavalinho", "Costas", "TOTAL", "cavalinho"),
            ex(16, "Remada curvada barra", "Costas", "TOTAL"),
            ex(17, "Remada unilateral máquina", "Costas", "POR_LADO"),
            ex(18, "Crossover polia alta", "Peito", "POR_LADO", "peito inferior"),
            ex(19, "Crucifixo máquina", "Peito", "TOTAL", "voador", "peck deck"),
            ex(20, "Supino inclinado Smith", "Peito", "TOTAL"),
            ex(21, "Supino inclinado halter", "Peito", "POR_LADO"),
            ex(22, "Supino inclinado máquina unilateral", "Peito", "POR_LADO"),
            ex(23, "Supino reto barra", "Peito", "TOTAL"),
            ex(24, "Crucifixo inverso máquina", "Ombro", "TOTAL", "voador posterior"),
            ex(25, "Desenvolvimento Smith", "Ombro", "TOTAL"),
            ex(26, "Desenvolvimento halter", "Ombro", "POR_LADO"),
            ex(27, "Elevação frontal anilha", "Ombro", "TOTAL"),
            ex(28, "Elevação frontal halter", "Ombro", "POR_LADO"),
            ex(29, "Elevação frontal polia", "Ombro", "TOTAL"),
            ex(30, "Elevação lateral halter", "Ombro", "POR_LADO"),
            ex(31, "Elevação lateral polia", "Ombro", "POR_LADO"),
            ex(32, "Posterior de ombro polia", "Ombro", "TOTAL"),
            ex(33, "Agachamento Smith", "Pernas", "TOTAL"),
            ex(34, "Agachamento hack", "Pernas", "TOTAL", "hack"),
            ex(35, "Agachamento livre", "Pernas", "TOTAL"),
            ex(36, "Cadeira flexora", "Pernas", "TOTAL"),
            ex(37, "Elevação pélvica", "Pernas", "TOTAL"),
            ex(38, "Mesa flexora", "Pernas", "TOTAL"),
            ex(39, "Panturrilha", "Pernas", "TOTAL"),
            ex(40, "Stiff", "Pernas", "TOTAL"),
            ex(41, "Encolhimento com anilhas", "Trapézio", "POR_LADO"),
            ex(42, "Tríceps barra W polia", "Tríceps", "TOTAL"),
            ex(43, "Tríceps corda", "Tríceps", "TOTAL"),
            ex(44, "Tríceps francês", "Tríceps", "TOTAL", "francês"),
            ex(45, "Tríceps testa barra", "Tríceps", "TOTAL", "testa"),
            ex(46, "Tríceps testa polia", "Tríceps", "TOTAL"));

    private static int ok = 0;
    private static int falhas = 0;

    public static void main(String[] args) {
        argumento();
        normalizacao();
        apelidoENomeOficial();
        palavras();
        ambiguos();
        maisDeDezCandidatos();
        contrato();

        System.out.println();
        System.out.println("RESULTADO: " + ok + " ok, " + falhas + " falhas");
        if (falhas > 0) {
            System.exit(1);
        }
    }

    // --- Argumento: pontas, espaços internos, vazio, mais de 100 caracteres ---------------

    private static void argumento() {
        titulo("Argumento (regra 3, sobre a forma normalizada)");
        conferir("vazio -> vazio (ajuda)", BuscaDeExercicio.normalizar("").isEmpty());
        conferir("só espaços, tab e quebra de linha -> vazio (ajuda)",
                BuscaDeExercicio.normalizar("  \t \n ").isEmpty());
        conferir("pontas removidas e espaços internos colapsados (e o acento sai junto)",
                "remada unilateral maquina".equals(BuscaDeExercicio.normalizar("\t remada \n  unilateral   máquina  ")));
        conferir("100 caracteres: não é longo demais", !BuscaDeExercicio.longoDemais("a".repeat(100)));
        conferir("101 caracteres: longo demais", BuscaDeExercicio.longoDemais("a".repeat(101)));
        // 100 emojis = 200 unidades UTF-16: o limite é em caracteres, como o VARCHAR2(n CHAR).
        conferir("100 emojis (200 unidades UTF-16): não é longo demais",
                !BuscaDeExercicio.longoDemais("💪".repeat(100)));
        conferir("101 emojis: longo demais", BuscaDeExercicio.longoDemais("💪".repeat(101)));
        conferir("o argumento normalizado é que conta: 'a' + 200 espaços + 'b' -> 'a b', não é longo demais",
                !BuscaDeExercicio.longoDemais(BuscaDeExercicio.normalizar("a" + " ".repeat(200) + "b")));
        // Ajuste da fatia 7: vazio e limite também sobre a forma normalizada.
        conferir("'???' -> vazio depois de normalizar (o service responde ajuda)",
                BuscaDeExercicio.normalizar("???").isEmpty());
        conferir("só pontuação, símbolo e emoji ('-- °, 💪 ...') -> vazio (ajuda)",
                BuscaDeExercicio.normalizar("-- °, 💪 ...").isEmpty());
        String comVirgulas = BuscaDeExercicio.normalizar("a" + ",".repeat(200) + "b");
        conferir("pontuação conta como espaço no limite: 'a' + 200 vírgulas + 'b' -> 'a b', não é longo demais",
                "a b".equals(comVirgulas) && !BuscaDeExercicio.longoDemais(comVirgulas));
    }

    // --- Normalização ---------------------------------------------------------------------

    private static void normalizacao() {
        titulo("Normalização (regra 4)");
        conferir("maiúsculas e acentos: 'Tríceps Francês' -> 'triceps frances'",
                "triceps frances".equals(BuscaDeExercicio.normalizar("Tríceps Francês")));
        conferir("'PUXADA ROMANA MÉDIA' -> 'puxada romana media'",
                "puxada romana media".equals(BuscaDeExercicio.normalizar("PUXADA ROMANA MÉDIA")));
        conferir("ç, ã, é: 'Elevação pélvica' -> 'elevacao pelvica'",
                "elevacao pelvica".equals(BuscaDeExercicio.normalizar("Elevação pélvica")));
        // Mudou no ajuste da fatia 7 (antes, o "°" ficava): não é letra nem dígito.
        conferir("'°' vira espaço e sai na ponta: 'Rosca inclinada halter 45°' -> 'rosca inclinada halter 45'",
                "rosca inclinada halter 45".equals(BuscaDeExercicio.normalizar("Rosca inclinada halter 45°")));
        conferir("vírgula, hífen e ponto viram espaço: 'Remada, curvada-barra.' -> 'remada curvada barra'",
                "remada curvada barra".equals(BuscaDeExercicio.normalizar("Remada, curvada-barra.")));
        boolean estavel = true;
        for (String texto : List.of("Tríceps Francês", "Rosca inclinada halter 45°", "  Remada,  curvada-barra. ",
                "PUXADA ROMANA MÉDIA", "???")) {
            String umaVez = BuscaDeExercicio.normalizar(texto);
            estavel &= umaVez.equals(BuscaDeExercicio.normalizar(umaVez));
        }
        conferir("estável: normalizar o que já foi normalizado não muda nada", estavel);
        // "é" já composto (U+00E9) e "e" + acento solto (U+0065 U+0301): o NFD deixa os
        // dois iguais antes de tirar o acento.
        conferir("'é' composto e 'e' + acento combinante normalizam igual",
                BuscaDeExercicio.normalizar("média").equals(BuscaDeExercicio.normalizar("média")));
    }

    // --- Passos a e b: igualdade com apelido e com nome oficial ----------------------------

    private static void apelidoENomeOficial() {
        titulo("Igualdade: apelido (a) e nome oficial (b)");
        conferirEncontrado("nome oficial com maiúsculas: 'Remada Unilateral MÁQUINA'", "Remada Unilateral MÁQUINA",
                CATALOGO, "Remada unilateral máquina");
        conferirEncontrado("nome oficial sem o acento: 'puxada romana media'", "puxada romana media", CATALOGO,
                "Puxada romana média");
        conferirEncontrado("nome oficial com espaços extras: '  Remada   UNILATERAL maquina '",
                "  Remada   UNILATERAL maquina ", CATALOGO, "Remada unilateral máquina");
        // Ajuste da fatia 7: a igualdade também usa a forma normalizada, sem o "°".
        conferirEncontrado("igualdade sem o '°': 'rosca inclinada halter 45' -> Rosca inclinada halter 45°",
                "rosca inclinada halter 45", CATALOGO, "Rosca inclinada halter 45°");
        List<ExercicioComApelidos> roscas45 = List.of(
                ex(1, "Rosca inclinada halter 45°", "Bíceps", "POR_LADO"),
                ex(2, "Rosca inclinada halter 45° unilateral", "Bíceps", "POR_LADO"));
        conferirEncontrado("... é a igualdade (passo b) que acha: as palavras dariam as duas roscas",
                "rosca inclinada halter 45", roscas45, "Rosca inclinada halter 45°");
        List<ExercicioComApelidos> apelidoComHifen = List.of(
                ex(1, "Crucifixo máquina", "Peito", "TOTAL", "peck-deck"));
        conferirEncontrado("igualdade de apelido também normalizada: apelido 'peck-deck', digitado 'peck deck'",
                "peck deck", apelidoComHifen, "Crucifixo máquina");
        conferirEncontrado("apelido com maiúscula: 'Cavalinho' -> Remada cavalinho", "Cavalinho", CATALOGO,
                "Remada cavalinho");
        conferirEncontrado("apelido gravado com acento, digitado sem: 'frances' -> Tríceps francês", "frances",
                CATALOGO, "Tríceps francês");
        conferirEncontrado("apelido que é pedaço de outro apelido: 'voador' -> Crucifixo máquina (não o posterior)",
                "voador", CATALOGO, "Crucifixo máquina");
        conferirEncontrado("apelido de duas palavras: 'voador posterior' -> Crucifixo inverso máquina",
                "voador posterior", CATALOGO, "Crucifixo inverso máquina");

        // Nome oficial que também está contido em outro: só pelas palavras, "puxada
        // aberta" casaria com "Puxada aberta" e "Puxada romana aberta".
        conferirEncontrado("nome oficial contido em outro: 'puxada aberta' -> Puxada aberta, sem lista",
                "puxada aberta", CATALOGO, "Puxada aberta");
        conferirAmbiguo("... a prova: na ordem trocada ('aberta puxada') não há igualdade, e as palavras dão as duas",
                "aberta puxada", CATALOGO, List.of("Puxada aberta", "Puxada romana aberta"), false);

        // Apelido que também está contido num nome oficial ("hack"). No catálogo real
        // as palavras dariam o mesmo exercício; aqui, um catálogo em que as palavras
        // dariam dois, para provar que o apelido vem antes.
        conferirEncontrado("catálogo real: 'hack' -> Agachamento hack", "hack", CATALOGO, "Agachamento hack");
        List<ExercicioComApelidos> comHack = List.of(
                ex(1, "Agachamento hack", "Pernas", "TOTAL", "hack"),
                ex(2, "Panturrilha hack", "Pernas", "TOTAL"));
        conferirEncontrado("apelido antes das palavras: 'hack' -> Agachamento hack, mesmo com 'Panturrilha hack'",
                "hack", comHack, "Agachamento hack");
        List<ExercicioComApelidos> semApelido = List.of(
                ex(1, "Agachamento hack", "Pernas", "TOTAL"),
                ex(2, "Panturrilha hack", "Pernas", "TOTAL"));
        conferirAmbiguo("... sem o apelido (outro usuário), as palavras dão as duas", "hack", semApelido,
                List.of("Agachamento hack", "Panturrilha hack"), false);

        // Apelido antes do nome oficial: o vocabulário do usuário manda.
        List<ExercicioComApelidos> apelidoIgualANome = List.of(
                ex(1, "Stiff", "Pernas", "TOTAL"),
                ex(2, "Mesa flexora", "Pernas", "TOTAL", "stiff"));
        conferirEncontrado("apelido antes do nome oficial: apelido 'stiff' da Mesa flexora ganha do nome 'Stiff'",
                "Stiff", apelidoIgualANome, "Mesa flexora");
    }

    // --- Passo c: todas as palavras, em qualquer ordem -------------------------------------

    private static void palavras() {
        titulo("Palavras (c): todas, em qualquer ordem, palavra inteira");
        conferirEncontrado("ordem trocada: 'maquina unilateral remada' -> Remada unilateral máquina",
                "maquina unilateral remada", CATALOGO, "Remada unilateral máquina");
        conferirEncontrado("parte das palavras: 'remada maquina' -> Remada unilateral máquina", "remada maquina",
                CATALOGO, "Remada unilateral máquina");
        conferirEncontrado("uma palavra só, num nome só: 'triangulo' -> Puxada triângulo", "triangulo", CATALOGO,
                "Puxada triângulo");
        conferirNaoEncontrado("sem candidato: 'supino declinado'", "supino declinado", CATALOGO);
        conferirNaoEncontrado("palavra inteira, não pedaço: 'rem' não casa com 'remada'", "rem", CATALOGO);
        // Com pedaço de palavra, o "w" casaria também com o "w" de "pulldown" e
        // "Pulldown corda" entraria na lista.
        conferirEncontrado("palavra inteira: 'pulldown w' -> só Pulldown barra W (não Pulldown corda)", "pulldown w",
                CATALOGO, "Pulldown barra W");
        conferirNaoEncontrado("apelido não vale por palavra: 'deck' (de 'peck deck') não casa", "deck", CATALOGO);
        // Ajuste da fatia 7: "45°" vira a palavra "45".
        conferirEncontrado("'rosca inclinada 45' -> 1 candidato: Rosca inclinada halter 45°", "rosca inclinada 45",
                CATALOGO, "Rosca inclinada halter 45°");
        conferirEncontrado("'45' sozinho -> Rosca inclinada halter 45° (o único nome com a palavra 45)", "45",
                CATALOGO, "Rosca inclinada halter 45°");
        conferirNaoEncontrado("palavra inteira, não prefixo: 'rosca 4' não casa com '45'", "rosca 4", CATALOGO);
    }

    // --- Ambíguos -------------------------------------------------------------------------

    private static void ambiguos() {
        titulo("Ambíguo: lista os candidatos e não escolhe");
        conferirAmbiguo("'remada' -> as três remadas, na ordem do catálogo", "remada", CATALOGO,
                List.of("Remada cavalinho", "Remada curvada barra", "Remada unilateral máquina"), false);
        conferirAmbiguo("'remada,' -> a mesma lista de 'remada' (a vírgula sai na normalização)", "remada,",
                CATALOGO, List.of("Remada cavalinho", "Remada curvada barra", "Remada unilateral máquina"), false);
        conferirAmbiguo("'Unilateral' -> os quatro unilaterais, na ordem do catálogo", "Unilateral", CATALOGO,
                List.of("Rosca unilateral polia", "Puxada unilateral polia", "Remada unilateral máquina",
                        "Supino inclinado máquina unilateral"), false);

        // Os UNIQUE do banco comparam o texto cru: dois nomes (ou apelidos) que só
        // diferem no acento ficam iguais depois de normalizados. O bot pergunta.
        List<ExercicioComApelidos> nomesQuaseIguais = List.of(
                ex(1, "Tríceps corda", "Tríceps", "TOTAL"),
                ex(2, "Triceps corda", "Tríceps", "TOTAL"));
        conferirAmbiguo("dois nomes oficiais iguais depois de normalizar: pergunta, não escolhe", "triceps corda",
                nomesQuaseIguais, List.of("Tríceps corda", "Triceps corda"), false);
        List<ExercicioComApelidos> apelidosQuaseIguais = List.of(
                ex(1, "Tríceps francês", "Tríceps", "TOTAL", "francês"),
                ex(2, "Tríceps testa barra", "Tríceps", "TOTAL", "frances"));
        conferirAmbiguo("dois apelidos iguais depois de normalizar, exercícios diferentes: pergunta", "Francês",
                apelidosQuaseIguais, List.of("Tríceps francês", "Tríceps testa barra"), false);
        List<ExercicioComApelidos> doisApelidosMesmoExercicio = List.of(
                ex(1, "Tríceps francês", "Tríceps", "TOTAL", "francês", "frances"));
        conferirEncontrado("dois apelidos iguais depois de normalizar, MESMO exercício: encontrado", "frances",
                doisApelidosMesmoExercicio, "Tríceps francês");
    }

    private static void maisDeDezCandidatos() {
        titulo("Mais de 10 candidatos");
        List<String> doze = new ArrayList<>();
        List<ExercicioComApelidos> catalogoDoze = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            String nome = "Exercício teste " + i;
            doze.add(nome);
            catalogoDoze.add(ex(i, nome, "Teste", "TOTAL"));
        }
        conferirAmbiguo("12 candidatos: os 10 primeiros, na ordem do catálogo, e 'há mais'", "teste", catalogoDoze,
                doze.subList(0, 10), true);
        conferirAmbiguo("11 candidatos: 10 e 'há mais'", "teste", catalogoDoze.subList(0, 11), doze.subList(0, 10),
                true);
        conferirAmbiguo("exatamente 10: os 10, sem 'há mais'", "teste", catalogoDoze.subList(0, 10),
                doze.subList(0, 10), false);
    }

    // --- Contrato: quem chama confere vazio e longo demais antes ---------------------------

    private static void contrato() {
        titulo("Contrato de buscar (vazio e longo demais são conferidos antes, sem catálogo)");
        conferir("buscar(\"\") -> IllegalArgumentException (vazio 'conteria' todas as palavras de todos)",
                lancaIllegalArgument(""));
        conferir("buscar(101 caracteres) -> IllegalArgumentException", lancaIllegalArgument("a".repeat(101)));
        conferir("buscar(\"???\") -> IllegalArgumentException (vazio depois de normalizar)",
                lancaIllegalArgument("???"));
    }

    // --- Ajudantes ------------------------------------------------------------------------

    private static ExercicioComApelidos ex(long id, String nome, String grupamento, String formaCarga,
            String... apelidos) {
        return new ExercicioComApelidos(id, nome, grupamento, formaCarga, null, null, new ArrayList<>(List.of(apelidos)));
    }

    private static void conferirEncontrado(String cenario, String termo, List<ExercicioComApelidos> catalogo,
            String nomeEsperado) {
        Resultado resultado = BuscaDeExercicio.buscar(termo, catalogo);
        conferir(cenario, resultado.situacao() == Situacao.ENCONTRADO
                && nomeEsperado.equals(resultado.exercicio().nome()));
    }

    private static void conferirAmbiguo(String cenario, String termo, List<ExercicioComApelidos> catalogo,
            List<String> candidatosEsperados, boolean haMais) {
        Resultado resultado = BuscaDeExercicio.buscar(termo, catalogo);
        conferir(cenario, resultado.situacao() == Situacao.AMBIGUO && resultado.exercicio() == null
                && candidatosEsperados.equals(resultado.candidatos()) && resultado.haMaisCandidatos() == haMais);
    }

    private static void conferirNaoEncontrado(String cenario, String termo, List<ExercicioComApelidos> catalogo) {
        Resultado resultado = BuscaDeExercicio.buscar(termo, catalogo);
        conferir(cenario, resultado.situacao() == Situacao.NAO_ENCONTRADO && resultado.candidatos().isEmpty());
    }

    private static boolean lancaIllegalArgument(String termo) {
        try {
            BuscaDeExercicio.buscar(termo, CATALOGO);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static void titulo(String texto) {
        System.out.println();
        System.out.println("=== " + texto + " ===");
    }

    private static void conferir(String cenario, boolean passou) {
        if (passou) {
            ok++;
            System.out.println("[ok]    " + cenario);
        } else {
            falhas++;
            System.out.println("[FALHA] " + cenario);
        }
    }
}
