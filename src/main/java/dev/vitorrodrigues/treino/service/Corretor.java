package dev.vitorrodrigues.treino.service;

import java.sql.SQLException;
import java.time.LocalDateTime;

import dev.vitorrodrigues.treino.modelo.ResultadoDesfazer;

/**
 * O /desfazer (fatia 6c), sozinho.
 *
 * Mesmo motivo do Registrador e do Cadastrador: o loop do Telegram recebe a
 * correção por interface. No bot de verdade é o CorrecaoService; no TelegramSpike
 * é um falso em memória que devolve as duas situações e também lança SQLException
 * de conexão e de recusa, sem banco.
 *
 * Contrato das exceções: SQLException = nada foi comitado (o service já fez o
 * rollback). O loop decide pela decisão 2 da 6a: recusa avança; conexão repete
 * com espera; teto de 10 tentativas.
 */
@FunctionalInterface
public interface Corretor {

    /**
     * Marca como desfeita a última mensagem registrada do usuário nas 24 h
     * anteriores ao /desfazer, e comita. Nada é apagado.
     *
     * Idempotente: o mesmo comando (mesmo idTelegramDoComando) entregue de novo
     * devolve JA_APLICADO, sem desfazer mais nada.
     *
     * @param idUsuario           ID_USUARIO interno de quem mandou o /desfazer (não o from.id)
     * @param idTelegramDoComando message_id do /desfazer: identifica o comando
     *                            (gravado em DESFEITA_POR_ID_TELEGRAM)
     * @param envioDoComando      campo "date" do /desfazer, já em America/Sao_Paulo:
     *                            é a referência da janela de 24 h e o valor gravado
     *                            em DATA_HORA_DESFEITA
     */
    ResultadoDesfazer desfazerUltima(long idUsuario, long idTelegramDoComando, LocalDateTime envioDoComando)
            throws SQLException;
}
