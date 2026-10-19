package dev.vitorrodrigues.treino.service;

import java.sql.SQLException;
import java.time.LocalDateTime;

import dev.vitorrodrigues.treino.modelo.ResultadoConsulta;

/**
 * O /carga (fatia 7), sozinho.
 *
 * Mesmo motivo do Registrador, do Cadastrador e do Corretor: o loop do Telegram
 * recebe a consulta por interface. No bot de verdade é o ConsultaService; no
 * ConsultaSpike também, embrulhado num contador, e em alguns cenários um falso
 * que devolve ERRO ou lança SQLException sem banco.
 *
 * Contrato das exceções: SQLException = a mensagem crua NÃO foi gravada (T1);
 * nada foi salvo. O loop decide pela decisão 2 da 6a: recusa avança; conexão
 * repete com espera; teto de 10 tentativas. Depois da T1, falha de banco não é
 * exceção: vira ResultadoConsulta.erro.
 */
@FunctionalInterface
public interface Consultor {

    /**
     * Grava a mensagem crua (sem sessão) e responde com as séries do último treino
     * do exercício pedido.
     *
     * @param idUsuario     ID_USUARIO interno de quem mandou (não o from.id)
     * @param idTelegram    message_id do /carga
     * @param dataHoraEnvio campo "date" do /carga, já em America/Sao_Paulo
     * @param texto         o texto inteiro da mensagem, como veio (TEXTO_BRUTO)
     * @param argumento     o que vem depois de "/carga" (ou "/carga@bot"), sem
     *                      espaços nas pontas; "" se não houver
     */
    ResultadoConsulta consultarCarga(long idUsuario, long idTelegram, LocalDateTime dataHoraEnvio, String texto,
            String argumento) throws SQLException;
}
