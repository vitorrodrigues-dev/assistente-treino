package dev.vitorrodrigues.treino.service;

import java.sql.SQLException;
import java.time.LocalDateTime;

import dev.vitorrodrigues.treino.modelo.ResultadoRegistro;

/**
 * A assinatura do RegistroService.registrar, sozinha (fatia 6a).
 *
 * Existe para o loop do Telegram receber o registro por interface: no bot de
 * verdade é o RegistroService; no TelegramSpike é um falso que devolve cada
 * situação do ResultadoRegistro (ou lança SQLException, NullPointerException,
 * InterruptedException) sem banco e sem API. É o mesmo motivo da interface
 * Extrator na fatia 5.
 *
 * O contrato (o que cada exceção significa) está no javadoc do
 * RegistroService.registrar.
 */
@FunctionalInterface
public interface Registrador {

    ResultadoRegistro registrar(long idUsuario, long idTelegram, LocalDateTime dataHoraEnvio,
            String origem, String texto) throws SQLException, InterruptedException;
}
