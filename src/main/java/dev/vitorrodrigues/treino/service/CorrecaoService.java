package dev.vitorrodrigues.treino.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import dev.vitorrodrigues.treino.dao.MensagemDAO;
import dev.vitorrodrigues.treino.dao.SerieDAO;
import dev.vitorrodrigues.treino.factory.ConnectionFactory;
import dev.vitorrodrigues.treino.modelo.MensagemParaDesfazer;
import dev.vitorrodrigues.treino.modelo.ResultadoDesfazer;
import dev.vitorrodrigues.treino.modelo.SerieResumida;

/**
 * Correção pelo bot (fatia 6c): /desfazer marca a última mensagem registrada do
 * usuário como desfeita (exclusão lógica). Nada é apagado: as séries continuam em
 * TR_SERIE; a sessão e a observação continuam em TR_SESSAO. A próxima mensagem do
 * mesmo dia entra na mesma sessão, normalmente (o RegistroService não muda).
 *
 * Mesmo padrão de transação do CadastroService: este service abre a conexão,
 * comita e faz rollback; os DAOs só recebem a Connection (regra da fatia 4).
 * Buscar o alvo, ler as séries dele e marcar acontecem na MESMA conexão, e o
 * commit acontece ANTES de o loop responder: o bot nunca diz "desfeito" sobre
 * algo que não ficou gravado.
 *
 * Nada aqui é impresso nem vai para mensagem de exceção: nem ID_MENSAGEM, nem
 * ID_USUARIO, nem nome de exercício.
 */
public class CorrecaoService implements Corretor {

    // Regra 2 da 6c: só mensagens enviadas nas 24 h anteriores ao /desfazer.
    private static final Duration JANELA = Duration.ofHours(24);

    private final MensagemDAO mensagemDAO = new MensagemDAO();
    private final SerieDAO serieDAO = new SerieDAO();

    @Override
    public ResultadoDesfazer desfazerUltima(long idUsuario, long idTelegramDoComando, LocalDateTime envioDoComando)
            throws SQLException {
        // A janela é no horário de São Paulo (LocalDateTime), como DATA_HORA_ENVIO.
        // Tirar 24 h do relógio local é o mesmo que 24 h de verdade porque
        // America/Sao_Paulo não tem horário de verão desde 2019.
        LocalDateTime desde = envioDoComando.minus(JANELA);

        try (Connection conn = ConnectionFactory.getConnection()) {
            conn.setAutoCommit(false);
            boolean comitou = false;
            Throwable original = null;
            try {
                // Idempotência, ANTES de buscar o alvo: se o bot caiu depois do
                // commit e antes de confirmar o offset, o Telegram entrega o MESMO
                // /desfazer de novo. Ele já foi aplicado (há uma mensagem marcada
                // com este message_id): nada de buscar alvo nem UPDATE, senão a
                // reentrega desfaria MAIS uma mensagem. Mesma ideia do JA_GRAVADA
                // do registro. Se esta checagem um dia falhar, o índice
                // UQ_TR_MENSAGEM_DESFEITA_POR recusa o UPDATE (ORA-00001).
                if (mensagemDAO.jaDesfeitaPeloComando(conn, idUsuario, idTelegramDoComando)) {
                    return ResultadoDesfazer.jaAplicado();
                }

                Optional<MensagemParaDesfazer> encontrada =
                        mensagemDAO.buscarUltimaParaDesfazer(conn, idUsuario, desde, envioDoComando);
                if (encontrada.isEmpty()) {
                    return ResultadoDesfazer.nadaParaDesfazer();
                }
                MensagemParaDesfazer alvo = encontrada.get();

                // Lidas ANTES do UPDATE, na mesma transação: são as séries da
                // mensagem que vai ser marcada, para a resposta dizer o que saiu.
                List<SerieResumida> series = serieDAO.listarResumoDaMensagem(conn, alvo.idMensagem());

                // Grava quando (a data de ENVIO do /desfazer, não a hora em que o
                // Java leu: a mesma referência da janela) e por qual comando (o
                // message_id, para a checagem de reentrega acima).
                boolean marcou = mensagemDAO.marcarDesfeita(conn, alvo.idMensagem(), idUsuario, envioDoComando,
                        idTelegramDoComando);
                if (!marcou) {
                    // 0 linhas: entre a busca e o UPDATE a mensagem foi desfeita por
                    // outro caminho (um UPDATE no SQL Developer). Não sobrou nada para
                    // ESTE /desfazer marcar (regra 2 da 6c). Sem commit: o finally faz
                    // o rollback.
                    return ResultadoDesfazer.nadaParaDesfazer();
                }
                conn.commit();
                comitou = true;
                return ResultadoDesfazer.desfeito(alvo.dataSessao(), series, alvo.sessaoComObservacao());
            } catch (SQLException | RuntimeException | Error e) {
                // Ver o mesmo catch no RegistroService: só guarda a original para o
                // finally pendurar nela um erro do rollback, e relança a mesma.
                original = e;
                throw e;
            } finally {
                // O driver da Oracle COMITA ao fechar a conexão sem commit/rollback:
                // o rollback é explícito. Também no "nada para desfazer" e no "já
                // aplicado": não há o que desfazer, mas a regra fica uma só, "não
                // comitou -> rollback".
                if (!comitou) {
                    reverter(conn, original);
                }
            }
        }
    }

    // Igual ao desfazer() do CadastroService e do RegistroService (intocados nesta
    // fatia); outro nome aqui porque "desfazer" é o comando desta fatia. Rollback
    // que não esconde a exceção original: exceção lançada dentro de um finally
    // SUBSTITUI a que estava subindo; sem este try/catch, uma falha no rollback (a
    // conexão caiu) apagaria a causa de verdade. A do rollback vai junto como
    // "suppressed".
    private static void reverter(Connection conn, Throwable original) throws SQLException {
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
