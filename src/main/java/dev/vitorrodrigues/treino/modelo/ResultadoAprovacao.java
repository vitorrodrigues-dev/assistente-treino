package dev.vitorrodrigues.treino.modelo;

import java.util.Objects;

/**
 * O que o /aprovar fez (fatia 6b). O loop do Telegram escolhe a resposta ao
 * administrador por aqui.
 *
 * situacao:
 * - APROVADO: estava PENDENTE e passou a ATIVO (UPDATE comitado).
 * - JA_ATIVO: já estava ATIVO. Nada mudou.
 * - BLOQUEADO: está BLOQUEADO. Nada mudou: desbloquear é ato deliberado, pelo
 *   banco, nunca efeito colateral de um /aprovar.
 * - INEXISTENTE: não há usuário com esse ID_USUARIO.
 *
 * nome e idTelegram: só no APROVADO. O nome vai na resposta ao administrador
 * ("aprovado: <nome>"); o idTelegram é o endereço do aviso ao aprovado (no chat
 * privado, chat id = from.id = ID_TELEGRAM).
 *
 * Criado só pelas fábricas abaixo (mesma ideia do ResultadoRegistro); o
 * construtor confere as mesmas regras.
 */
public record ResultadoAprovacao(Situacao situacao, String nome, Long idTelegram) {

    public enum Situacao {
        APROVADO,
        JA_ATIVO,
        BLOQUEADO,
        INEXISTENTE
    }

    public ResultadoAprovacao {
        Objects.requireNonNull(situacao, "situacao");
        boolean aprovado = situacao == Situacao.APROVADO;
        if (aprovado != (nome != null) || aprovado != (idTelegram != null)) {
            throw new IllegalArgumentException("nome e idTelegram existem só em APROVADO.");
        }
    }

    public static ResultadoAprovacao aprovado(String nome, long idTelegram) {
        return new ResultadoAprovacao(Situacao.APROVADO, Objects.requireNonNull(nome, "nome"), idTelegram);
    }

    public static ResultadoAprovacao jaAtivo() {
        return new ResultadoAprovacao(Situacao.JA_ATIVO, null, null);
    }

    public static ResultadoAprovacao bloqueado() {
        return new ResultadoAprovacao(Situacao.BLOQUEADO, null, null);
    }

    public static ResultadoAprovacao inexistente() {
        return new ResultadoAprovacao(Situacao.INEXISTENTE, null, null);
    }

    /**
     * Só a situação. O toString() gerado pelo record traria o nome e o id do
     * Telegram, que nunca podem ir para log nem para mensagem de exceção. Aqui,
     * diferente do Usuario, dá para fechar a porta no próprio record.
     */
    @Override
    public String toString() {
        return "ResultadoAprovacao[" + situacao + "]";
    }
}
