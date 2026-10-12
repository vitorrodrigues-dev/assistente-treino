package dev.vitorrodrigues.treino.bot;

import java.sql.SQLException;
import java.util.Optional;

import dev.vitorrodrigues.treino.modelo.Usuario;

/**
 * Busca do remetente pelo from.id (fatia 6a).
 *
 * Interface para o loop não depender do banco: no bot, abre uma conexão e chama o
 * UsuarioDAO (ver Bot); no TelegramSpike, um mapa em memória.
 */
@FunctionalInterface
public interface BuscaUsuario {

    /**
     * @return vazio = a pessoa nunca se cadastrou
     */
    Optional<Usuario> buscarPorIdTelegram(long idTelegram) throws SQLException;
}
