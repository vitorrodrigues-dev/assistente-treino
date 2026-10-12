package dev.vitorrodrigues.treino.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;

import dev.vitorrodrigues.treino.dao.UsuarioDAO;
import dev.vitorrodrigues.treino.factory.ConnectionFactory;
import dev.vitorrodrigues.treino.modelo.ResultadoAprovacao;
import dev.vitorrodrigues.treino.modelo.Usuario;

/**
 * Cadastro pelo bot (fatia 6b): /start grava PENDENTE; /aprovar passa a ATIVO.
 *
 * Mesmo padrão de transação do RegistroService: este service abre a conexão,
 * comita e faz rollback; o UsuarioDAO só recebe a Connection (regra da fatia 4).
 * Uma conexão por operação, fechada antes de o loop responder: o commit acontece
 * ANTES de qualquer mensagem sair. Assim o bot nunca diz "pedido recebido" ou
 * "aprovado" sobre algo que não ficou gravado.
 *
 * Nada aqui é impresso nem vai para mensagem de exceção: nem nome, nem id do
 * Telegram, nem ID_USUARIO.
 */
public class CadastroService implements Cadastrador {

    private static final String PENDENTE = "PENDENTE";
    private static final String ATIVO = "ATIVO";
    private static final String BLOQUEADO = "BLOQUEADO";

    private final UsuarioDAO usuarioDAO = new UsuarioDAO();

    @Override
    public long cadastrarPendente(long idTelegram, String nome, LocalDateTime dataCadastro) throws SQLException {
        try (Connection conn = ConnectionFactory.getConnection()) {
            conn.setAutoCommit(false);
            boolean comitou = false;
            Throwable original = null;
            try {
                // UQ_TR_USUARIO_ID_TELEGRAM recusa um segundo cadastro do mesmo
                // from.id (SQLIntegrityConstraintViolationException). Com o loop
                // processando um update por vez, isso não deveria acontecer: quem já
                // está cadastrado nem chega aqui. Se acontecer, sobe como recusa e o
                // loop avança (regra 3 da 6b).
                long idUsuario = usuarioDAO.inserirPendente(conn, idTelegram, nome, dataCadastro);
                conn.commit();
                comitou = true;
                return idUsuario;
            } catch (SQLException | RuntimeException | Error e) {
                // Ver o mesmo catch no RegistroService: só guarda a original para o
                // finally pendurar nela um erro do rollback, e relança a mesma.
                original = e;
                throw e;
            } finally {
                // O driver da Oracle COMITA ao fechar a conexão sem commit/rollback:
                // o rollback é explícito.
                if (!comitou) {
                    desfazer(conn, original);
                }
            }
        }
    }

    // Busca e UPDATE na MESMA transação (regra 5 da 6b): a decisão (PENDENTE ->
    // ATIVO) e a gravação usam a mesma conexão, e o commit só acontece se o UPDATE
    // passou.
    @Override
    public ResultadoAprovacao aprovar(long idUsuario) throws SQLException {
        try (Connection conn = ConnectionFactory.getConnection()) {
            conn.setAutoCommit(false);
            boolean comitou = false;
            Throwable original = null;
            try {
                Optional<Usuario> encontrado = usuarioDAO.buscarPorId(conn, idUsuario);
                if (encontrado.isEmpty()) {
                    return ResultadoAprovacao.inexistente();
                }
                Usuario usuario = encontrado.get();

                // Só PENDENTE vira ATIVO. Os outros dois não sofrem UPDATE: ATIVO já
                // está como deveria; BLOQUEADO só sai pelo banco, de propósito (um
                // /aprovar digitado com o id errado não pode desfazer um bloqueio).
                switch (usuario.status()) {
                    case ATIVO -> {
                        return ResultadoAprovacao.jaAtivo();
                    }
                    case BLOQUEADO -> {
                        return ResultadoAprovacao.bloqueado();
                    }
                    case PENDENTE -> {
                        // Segue para o UPDATE.
                    }
                    // O CK_TR_USUARIO_STATUS só deixa os três. Outro valor = o banco
                    // mudou sem o código acompanhar: falha alto, sem UPDATE. Mensagem
                    // sem o valor nem o id.
                    default -> throw new IllegalStateException("STATUS fora do CK_TR_USUARIO_STATUS.");
                }

                try {
                    usuarioDAO.atualizarStatus(conn, idUsuario, ATIVO);
                } catch (IllegalStateException e) {
                    // 0 linhas: a linha sumiu entre a busca e o UPDATE (alguém
                    // apagou pelo SQL Developer). Para o administrador, é o mesmo que
                    // não existir (regra 5 da 6b). A mensagem da exceção tem o
                    // ID_USUARIO: fica de fora. Sem commit: o finally faz o rollback.
                    return ResultadoAprovacao.inexistente();
                }
                conn.commit();
                comitou = true;
                return ResultadoAprovacao.aprovado(usuario.nome(), usuario.idTelegram());
            } catch (SQLException | RuntimeException | Error e) {
                original = e;
                throw e;
            } finally {
                // Também nos retornos sem UPDATE (inexistente, já ativo, bloqueado):
                // não há o que desfazer, mas a regra fica uma só, "não comitou ->
                // rollback", sem caso especial para lembrar.
                if (!comitou) {
                    desfazer(conn, original);
                }
            }
        }
    }

    // Igual ao do RegistroService (que fica intocado nesta fatia): rollback que não
    // esconde a exceção original. Exceção lançada dentro de um finally SUBSTITUI a
    // que estava subindo; sem este try/catch, uma falha no rollback (a conexão
    // caiu) apagaria a causa de verdade. A do rollback vai junto como "suppressed".
    private static void desfazer(Connection conn, Throwable original) throws SQLException {
        try {
            conn.rollback();
        } catch (SQLException erroNoRollback) {
            if (original == null) {
                // Não havia exceção subindo: a do rollback é a única notícia.
                throw erroNoRollback;
            }
            original.addSuppressed(erroNoRollback);
        }
    }
}
