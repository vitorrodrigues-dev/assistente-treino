package dev.vitorrodrigues.treino.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import dev.vitorrodrigues.treino.dao.ConsultaDAO;
import dev.vitorrodrigues.treino.dao.ExercicioDAO;
import dev.vitorrodrigues.treino.dao.MensagemDAO;
import dev.vitorrodrigues.treino.factory.ConnectionFactory;
import dev.vitorrodrigues.treino.modelo.ExercicioComApelidos;
import dev.vitorrodrigues.treino.modelo.ResultadoConsulta;
import dev.vitorrodrigues.treino.modelo.ResultadoInsercao;
import dev.vitorrodrigues.treino.modelo.UltimoTreino;

/**
 * Consulta por chat (fatia 7): /carga <exercício> responde com as séries do último
 * treino daquele exercício. SEM LLM: nenhuma chamada à API da Anthropic aqui (este
 * service nem recebe um Extrator).
 *
 * Etapas, nesta ordem:
 * 1. T1, transação curta: grava a mensagem /carga crua, sem sessão, e COMITA antes
 *    de tudo. "Toda mensagem é gravada crua" (escopo): a consulta também fica no
 *    banco. Reentrega do Telegram (já gravada) -> JA_GRAVADA, nada mais é feito.
 * 2. Argumento, em Java puro (BuscaDeExercicio), já normalizado: vazio (inclusive
 *    só pontuação) -> AJUDA; mais de 100 caracteres -> NAO_ENCONTRADO, sem ler o
 *    catálogo.
 * 3. Catálogo (ExercicioDAO.listarComApelidos, a mesma lista do prompt) e o
 *    casamento do nome, em Java puro. Nenhum SQL novo para casar nomes.
 * 4. Só com um exercício resolvido: o último treino (ConsultaDAO), só leitura.
 *
 * Separado do RegistroService de propósito: não usa extrator nem grava sessão, e
 * misturar os dois faria o registro carregar um caminho que não é dele.
 *
 * Falhas: antes do commit da T1 -> SQLException (nada foi salvo; o loop decide).
 * Depois -> ResultadoConsulta.erro, nunca exceção (mesma lógica do NAO_REGISTRADO:
 * a reentrega cairia em JA_GRAVADA e o usuário ficaria sem resposta).
 *
 * Nada aqui é impresso nem vai para mensagem de exceção: nem o texto, nem o
 * argumento, nem nome de exercício, nem id.
 */
public class ConsultaService implements Consultor {

    // Comando só chega por texto (áudio não é comando).
    private static final String ORIGEM = "TEXTO";

    private final MensagemDAO mensagemDAO = new MensagemDAO();
    private final ExercicioDAO exercicioDAO = new ExercicioDAO();
    private final ConsultaDAO consultaDAO = new ConsultaDAO();

    @Override
    public ResultadoConsulta consultarCarga(long idUsuario, long idTelegram, LocalDateTime dataHoraEnvio,
            String texto, String argumento) throws SQLException {

        // --- 1. T1: mensagem crua, ID_SESSAO nulo -----------------------------------
        ResultadoInsercao insercao = gravarMensagemCrua(idUsuario, idTelegram, dataHoraEnvio, texto);
        if (insercao.jaGravada()) {
            // Reentrega: a resposta desta mesma mensagem já foi enviada (ou se
            // perdeu na queda). Não consulta de novo; o loop fica em silêncio.
            return ResultadoConsulta.jaGravada();
        }

        // --- 2. Argumento (Java puro, antes de qualquer leitura) ----------------------
        // Vazio e limite valem sobre a forma NORMALIZADA (a mesma que a busca usa):
        // um argumento só de pontuação ("???") não tem o que buscar e vira ajuda.
        String termo = BuscaDeExercicio.normalizar(argumento);
        if (termo.isEmpty()) {
            return ResultadoConsulta.ajuda();
        }
        if (BuscaDeExercicio.longoDemais(termo)) {
            return ResultadoConsulta.naoEncontrado();
        }

        // --- 3. Catálogo + casamento do nome -------------------------------------------
        // Daqui em diante, falha de banco vira ERRO (a mensagem crua já está salva).
        List<ExercicioComApelidos> catalogo;
        try {
            // Exceção histórica do ExercicioDAO: abre a própria conexão (só leitura).
            catalogo = exercicioDAO.listarComApelidos(idUsuario);
        } catch (SQLException e) {
            return ResultadoConsulta.erro("catálogo: erro no banco (" + codigoOra(e) + ")");
        }

        BuscaDeExercicio.Resultado busca = BuscaDeExercicio.buscar(termo, catalogo);
        switch (busca.situacao()) {
            case NAO_ENCONTRADO -> {
                return ResultadoConsulta.naoEncontrado();
            }
            case AMBIGUO -> {
                return ResultadoConsulta.ambiguo(busca.candidatos(), busca.haMaisCandidatos());
            }
            case ENCONTRADO -> {
                // Segue para a leitura das séries.
            }
        }
        ExercicioComApelidos exercicio = busca.exercicio();

        // --- 4. Último treino (só leitura) ----------------------------------------------
        Optional<UltimoTreino> treino;
        // Conexão só para esta leitura, fechada antes de o loop responder. Uma
        // consulta só, sem nada a comitar nem desfazer: o autoCommit fica como está
        // (como na busca do remetente, no Bot).
        try (Connection conn = ConnectionFactory.getConnection()) {
            treino = consultaDAO.buscarUltimoTreino(conn, idUsuario, exercicio.idExercicio());
        } catch (SQLException e) {
            return ResultadoConsulta.erro("séries: erro no banco (" + codigoOra(e) + ")");
        }

        // Nome oficial e forma da carga vêm do catálogo, nunca do que o usuário digitou.
        if (treino.isEmpty()) {
            return ResultadoConsulta.semRegistro(exercicio.nome());
        }
        return ResultadoConsulta.encontrado(exercicio.nome(), exercicio.formaCarga(), treino.get());
    }

    // T1. Mesmo padrão da T1 do RegistroService (intocado nesta fatia): conexão
    // própria, comita e fecha. SQLException aqui sobe para quem chamou (nada foi
    // salvo). O MensagemDAO.inserir grava sem ID_SESSAO: fica NULL, e a pilha do
    // /desfazer pula mensagem sem sessão.
    private ResultadoInsercao gravarMensagemCrua(long idUsuario, long idTelegram, LocalDateTime dataHoraEnvio,
            String texto) throws SQLException {
        try (Connection conn = ConnectionFactory.getConnection()) {
            conn.setAutoCommit(false);
            boolean comitou = false;
            Throwable original = null;
            try {
                ResultadoInsercao insercao = mensagemDAO.inserir(conn, idUsuario, idTelegram, dataHoraEnvio, ORIGEM,
                        texto);
                // No "já gravada" o INSERT falhou e o Oracle já desfez só aquele
                // comando: o commit não tem o que gravar. Fica igual nos dois casos.
                conn.commit();
                comitou = true;
                return insercao;
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

    // Igual ao do RegistroService e do CadastroService (intocados nesta fatia):
    // rollback que não esconde a exceção original. Exceção lançada dentro de um
    // finally SUBSTITUI a que estava subindo; sem este try/catch, uma falha no
    // rollback (a conexão caiu) apagaria a causa de verdade. A do rollback vai junto
    // como "suppressed".
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

    // Só o código: a mensagem do Oracle pode citar o esquema (o usuário do banco) e
    // valores. Mesmo formato do RegistroService.
    private static String codigoOra(SQLException e) {
        return e.getErrorCode() > 0 ? String.format("ORA-%05d", e.getErrorCode()) : "sem código ORA";
    }
}
