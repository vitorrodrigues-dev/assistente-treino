package dev.vitorrodrigues.treino.spike;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import dev.vitorrodrigues.treino.extrator.ExtracaoException;
import dev.vitorrodrigues.treino.extrator.ExtratorCliente;
import dev.vitorrodrigues.treino.extrator.RelatoExtraido;
import dev.vitorrodrigues.treino.extrator.SerieExtraida;
import dev.vitorrodrigues.treino.modelo.ExercicioComApelidos;

/**
 * Regressão do prompt v3: os 3 casos do spike do extrator (escopo-mvp.md).
 * Sem argumento roda os 3 casos (3 chamadas pagas à API). Com o argumento
 * 1, 2 ou 3 roda só aquele caso (1 chamada).
 */
public class ExtratorSpike {

    private static final List<String> RELATOS = List.of(
            // 1. Estilo bloco de notas
            "supino inclinado halter 3x10 com 24kg, falhei na última. puxada romana aberta 60kg 8, 8, 7 a 8",
            // 2. Exercício fora da lista ("rosca martelo") + ambíguo ("pull down": barra W ou corda?)
            "fiz pull down 4 séries de 12 com 30, depois rosca martelo 3x10 14kg",
            // 3. Ditado fora de ordem e com lacunas
            "hoje academia cheia, dormi mal. remada cavalinho última série deu 9, primeira 12 com 50kg, segunda não lembro");

    public static void main(String[] args) throws InterruptedException {
        // O argumento é validado antes de criar o cliente: um erro de digitação
        // não chega a gastar chamada.
        int primeiroCaso = 1;
        int ultimoCaso = RELATOS.size();
        if (args.length > 0) {
            Integer escolhido = args.length == 1 ? numeroDoCaso(args[0]) : null;
            if (escolhido == null) {
                System.out.println("Uso: ExtratorSpike [1-" + RELATOS.size() + "]. Sem argumento, roda todos os casos.");
                return;
            }
            primeiroCaso = escolhido;
            ultimoCaso = escolhido;
        }

        ExtratorCliente extrator = new ExtratorCliente();
        List<ExercicioComApelidos> exercicios = listaFixa();

        // "Hoje" no fuso do usuário: o relógio da máquina pode estar em outro fuso.
        LocalDate hoje = LocalDate.now(ZoneId.of("America/Sao_Paulo"));

        for (int caso = primeiroCaso; caso <= ultimoCaso; caso++) {
            String relato = RELATOS.get(caso - 1);
            System.out.println("=== Caso " + caso + " ===");
            System.out.println("Relato: " + relato);
            // Um caso que falha não impede os outros de rodar.
            try {
                imprimir(extrator.extrair(relato, hoje, exercicios));
            } catch (ExtracaoException e) {
                System.out.println("FALHOU: " + e.getMessage());
                // Só aqui o texto do modelo é impresso: os relatos do spike são do
                // Vitor. No bot, com relatos de amigos, ele não vai para log.
                if (e.getRespostaBruta() != null) {
                    // Marcadores colados no texto: espaço ou quebra de linha no
                    // começo ou no fim da resposta ficam visíveis.
                    System.out.println("Resposta bruta do modelo:");
                    System.out.println(">>>" + e.getRespostaBruta() + "<<<");
                }
            } catch (IOException e) {
                System.out.println("FALHOU: " + e.getMessage());
            }
            System.out.println();
        }
    }

    // null = argumento que não é um caso válido (não é número ou está fora de 1..N).
    private static Integer numeroDoCaso(String argumento) {
        try {
            int numero = Integer.parseInt(argumento.trim());
            return numero >= 1 && numero <= RELATOS.size() ? numero : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void imprimir(RelatoExtraido relato) {
        System.out.println("observacaoDia: " + relato.observacaoDia());
        System.out.println("series (" + relato.series().size() + "):");
        for (SerieExtraida serie : relato.series()) {
            System.out.println("  " + serie);
        }
        System.out.println("duvidas: " + relato.duvidas());
    }

    // Ficha de docs/plano-treino.md com os apelidos do Vitor, na ordem em que o
    // ExercicioDAO devolve (grupamento, nome). Fixa aqui porque esta fatia ainda
    // não usa o banco.
    private static List<ExercicioComApelidos> listaFixa() {
        return List.of(
                exercicio("Abdômen", "Abdominal na corda", "TOTAL", "crunch"),
                exercicio("Bíceps", "Rosca Scott barra", "TOTAL"),
                exercicio("Bíceps", "Rosca Scott máquina", "TOTAL"),
                exercicio("Bíceps", "Rosca inclinada halter 45°", "POR_LADO"),
                exercicio("Bíceps", "Rosca unilateral polia", "POR_LADO"),
                exercicio("Costas", "Barra fixa", "TOTAL"),
                exercicio("Costas", "Pulldown barra W", "TOTAL"),
                exercicio("Costas", "Pulldown corda", "TOTAL"),
                exercicio("Costas", "Puxada aberta", "TOTAL"),
                exercicio("Costas", "Puxada romana aberta", "TOTAL"),
                exercicio("Costas", "Puxada romana fechada", "TOTAL"),
                exercicio("Costas", "Puxada romana média", "TOTAL"),
                exercicio("Costas", "Puxada triângulo", "TOTAL"),
                exercicio("Costas", "Puxada unilateral polia", "POR_LADO"),
                exercicio("Costas", "Remada cavalinho", "TOTAL", "cavalinho"),
                exercicio("Costas", "Remada curvada barra", "TOTAL"),
                exercicio("Costas", "Remada unilateral máquina", "POR_LADO"),
                exercicio("Ombro", "Crucifixo inverso máquina", "TOTAL", "voador posterior"),
                exercicio("Ombro", "Desenvolvimento Smith", "TOTAL"),
                exercicio("Ombro", "Desenvolvimento halter", "POR_LADO"),
                exercicio("Ombro", "Elevação frontal anilha", "TOTAL"),
                exercicio("Ombro", "Elevação frontal halter", "POR_LADO"),
                exercicio("Ombro", "Elevação frontal polia", "TOTAL"),
                exercicio("Ombro", "Elevação lateral halter", "POR_LADO"),
                exercicio("Ombro", "Elevação lateral polia", "POR_LADO"),
                exercicio("Ombro", "Posterior de ombro polia", "TOTAL"),
                exercicio("Peito", "Crossover polia alta", "POR_LADO", "peito inferior"),
                exercicio("Peito", "Crucifixo máquina", "TOTAL", "voador", "peck deck"),
                exercicio("Peito", "Supino inclinado Smith", "TOTAL"),
                exercicio("Peito", "Supino inclinado halter", "POR_LADO"),
                exercicio("Peito", "Supino inclinado máquina unilateral", "POR_LADO"),
                exercicio("Peito", "Supino reto barra", "TOTAL"),
                exercicio("Pernas", "Agachamento Smith", "TOTAL"),
                exercicio("Pernas", "Agachamento hack", "TOTAL", "hack"),
                exercicio("Pernas", "Agachamento livre", "TOTAL"),
                exercicio("Pernas", "Cadeira flexora", "TOTAL"),
                exercicio("Pernas", "Elevação pélvica", "TOTAL"),
                exercicio("Pernas", "Mesa flexora", "TOTAL"),
                exercicio("Pernas", "Panturrilha", "TOTAL"),
                exercicio("Pernas", "Stiff", "TOTAL"),
                exercicio("Trapézio", "Encolhimento com anilhas", "POR_LADO"),
                exercicio("Tríceps", "Tríceps barra W polia", "TOTAL"),
                exercicio("Tríceps", "Tríceps corda", "TOTAL"),
                exercicio("Tríceps", "Tríceps francês", "TOTAL", "francês"),
                exercicio("Tríceps", "Tríceps testa barra", "TOTAL", "testa"),
                exercicio("Tríceps", "Tríceps testa polia", "TOTAL")
        );
    }

    private static ExercicioComApelidos exercicio(String grupamento, String nome, String formaCarga,
                                                  String... apelidos) {
        // Id fictício (-1 não existe: o IDENTITY começa em 1). A lista não vem do
        // banco, o id não entra no prompt e este spike não grava nada.
        return new ExercicioComApelidos(-1L, nome, grupamento, formaCarga, null, null, List.of(apelidos));
    }
}
