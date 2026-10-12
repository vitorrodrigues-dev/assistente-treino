package dev.vitorrodrigues.treino.service;

import java.sql.SQLException;
import java.time.LocalDateTime;

import dev.vitorrodrigues.treino.modelo.ResultadoAprovacao;

/**
 * As duas operações de cadastro do bot (fatia 6b), sozinhas.
 *
 * Mesmo motivo do Registrador: o loop do Telegram recebe o cadastro por
 * interface. No bot de verdade é o CadastroService; no TelegramSpike é um falso
 * em memória que também lança SQLException de conexão e de recusa, sem banco.
 *
 * Contrato das exceções: SQLException = nada foi comitado (o service já fez o
 * rollback). O loop decide pela decisão 2 da 6a: recusa avança; conexão repete
 * com espera; teto de 10 tentativas.
 */
public interface Cadastrador {

    /**
     * Cadastra quem mandou /start, com status PENDENTE, e comita.
     *
     * @param idTelegram   from.id de quem mandou o /start
     * @param nome         first_name do Telegram, já validado pelo loop (1 a 100 caracteres)
     * @param dataCadastro campo "date" do /start, já em America/Sao_Paulo
     * @return ID_USUARIO gerado pelo banco
     */
    long cadastrarPendente(long idTelegram, String nome, LocalDateTime dataCadastro) throws SQLException;

    /**
     * /aprovar: PENDENTE vira ATIVO. Qualquer outro status não é alterado.
     *
     * @param idUsuario ID_USUARIO interno (o número que o aviso ao administrador mostra)
     */
    ResultadoAprovacao aprovar(long idUsuario) throws SQLException;
}
