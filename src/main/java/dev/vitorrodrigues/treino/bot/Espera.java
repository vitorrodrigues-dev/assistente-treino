package dev.vitorrodrigues.treino.bot;

import java.time.Duration;

/**
 * A pausa entre tentativas (fatia 6a). No bot é Thread.sleep; no TelegramSpike
 * não espera nada e só anota a duração pedida. Assim o spike confere a sequência
 * 5, 10, 20, 40, 60 s sem levar 6 minutos para rodar.
 */
@FunctionalInterface
public interface Espera {

    void esperar(Duration duracao) throws InterruptedException;
}
