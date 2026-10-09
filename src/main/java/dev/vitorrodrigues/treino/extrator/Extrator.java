package dev.vitorrodrigues.treino.extrator;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

import dev.vitorrodrigues.treino.modelo.ExercicioComApelidos;

/**
 * Relato em texto -> RelatoExtraido. O ExtratorCliente é a implementação de verdade.
 *
 * A interface existe por dois motivos (fatia 5):
 * - o RegistroService recebe o extrator PRONTO pelo construtor. Quem cria o
 *   ExtratorCliente é quem sobe o bot, então a chave ausente falha na hora de
 *   subir, e não na primeira mensagem de um amigo;
 * - o RegistroSpike troca o extrator de verdade por um falso, que devolve um
 *   RelatoExtraido montado à mão. Assim os casos de falha (texto grande demais,
 *   carga com 3 casas) são testados sem gastar crédito e sem depender do que o
 *   modelo resolve responder.
 */
public interface Extrator {

    /**
     * @param relato     texto da mensagem (ou transcrição do áudio)
     * @param dataHoje   data de envio da mensagem, já no fuso do usuário
     * @param exercicios catálogo com os apelidos do usuário (ExercicioDAO.listarComApelidos)
     */
    RelatoExtraido extrair(String relato, LocalDate dataHoje, List<ExercicioComApelidos> exercicios)
            throws IOException, InterruptedException, ExtracaoException;
}
