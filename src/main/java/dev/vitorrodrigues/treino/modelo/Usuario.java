package dev.vitorrodrigues.treino.modelo;

import java.time.LocalDateTime;

/**
 * Uma linha de TR_USUARIO.
 *
 * idUsuario: o id interno (PK). idTelegram: o from.id da pessoa no Telegram.
 * Os dois são números e é fácil trocar um pelo outro; os nomes longos são de propósito.
 *
 * Cuidado: o toString() gerado pelo record inclui o nome e o id do Telegram.
 * Nunca imprima um Usuario inteiro em log ou mensagem de exceção.
 */
public record Usuario(
        long idUsuario,
        long idTelegram,
        String nome,
        String status,
        LocalDateTime dataCadastro) {

    public boolean ativo() {
        return "ATIVO".equals(status);
    }
}
