package dev.vitorrodrigues.treino.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import dev.vitorrodrigues.treino.modelo.MensagemParaDesfazer;
import dev.vitorrodrigues.treino.modelo.ResultadoInsercao;

/**
 * Acesso a TR_MENSAGEM. Recebe a Connection e não comita (ver UsuarioDAO).
 */
public class MensagemDAO {

    // Erro Oracle ORA-00001: violação de UNIQUE.
    private static final int ORA_UNIQUE = 1;

    private static final String UQ_MENSAGEM = "UQ_TR_MENSAGEM_USU_ID_TELEGRAM";

    // ID_SESSAO fica de fora: a mensagem é gravada crua ANTES do extrator e só
    // ganha sessão depois (vincularSessao). Coluna omitida no INSERT = NULL.
    private static final String SQL_INSERIR = """
            INSERT INTO TR_MENSAGEM (ID_USUARIO, ID_TELEGRAM, DATA_HORA_ENVIO, ORIGEM, TEXTO_BRUTO)
            VALUES (?, ?, ?, ?, ?)
            """;

    // As duas condições de usuário fecham a lacuna do banco: nenhuma constraint
    // impede a mensagem do usuário A de apontar para a sessão do B (o escopo
    // registra isso em "Lacuna conhecida"). Se a mensagem não for do usuário, OU
    // a sessão não for dele, o WHERE não acha linha e nada é atualizado.
    private static final String SQL_VINCULAR_SESSAO = """
            UPDATE TR_MENSAGEM
               SET ID_SESSAO = ?
             WHERE ID_MENSAGEM = ?
               AND ID_USUARIO = ?
               AND EXISTS (SELECT 1
                             FROM TR_SESSAO S
                            WHERE S.ID_SESSAO = ?
                              AND S.ID_USUARIO = ?)
            """;

    // Alvo do /desfazer (regra 2 da 6c), numa consulta só:
    // - M.ID_USUARIO = ?: só mensagem do PRÓPRIO usuário.
    // - M.ID_SESSAO IS NOT NULL: só mensagem que gerou registro. SEM_SERIES,
    //   NAO_REGISTRADO e consultas ficam sem sessão e são puladas (o alvo passa a
    //   ser a anterior com sessão). O JOIN abaixo já exigiria isso; a condição
    //   fica escrita para a regra aparecer no SQL, e continua valendo se um dia o
    //   JOIN virar LEFT JOIN.
    // - M.DATA_HORA_DESFEITA IS NULL: só mensagem ainda válida. É o que faz a
    //   "pilha": cada /desfazer pula as já desfeitas e pega a próxima.
    // - DATA_HORA_ENVIO de (envio do /desfazer - 24 h) até o envio do /desfazer,
    //   as duas pontas incluídas ("as 24 h ANTERIORES ao /desfazer").
    // - ORDER BY DATA_HORA_ENVIO DESC, ID_MENSAGEM DESC: a mais nova pela data de
    //   ENVIO, que vem do Telegram. Não pelo ID_MENSAGEM sozinho: se o Oracle da
    //   FIAP for RAC (vários nós), o IDENTITY com cache entrega a cada nó um lote
    //   de números, e uma mensagem gravada depois pode ganhar um id MENOR. A data
    //   de envio não depende disso. O id só desempata dentro do mesmo segundo (a
    //   data não tem fração de segundo).
    // - JOIN com TR_SESSAO, também pelo usuário: traz a data e o "tem observação"
    //   que a resposta mostra, e deixa de fora uma mensagem que aponte para a
    //   sessão de outro usuário (a lacuna conhecida do banco; o vincularSessao já
    //   impede na gravação, aqui é a mesma regra na leitura).
    // - CASE em vez de ler OBSERVACAO: a resposta só precisa do sim/não, e o texto
    //   da observação é do usuário. Não trafega o que não é usado.
    // FETCH FIRST existe desde a 12c, então vale na 19c da FIAP.
    private static final String SQL_BUSCAR_ULTIMA_PARA_DESFAZER = """
            SELECT M.ID_MENSAGEM, S.DATA_SESSAO,
                   CASE WHEN S.OBSERVACAO IS NULL THEN 'N' ELSE 'S' END AS SESSAO_COM_OBSERVACAO
              FROM TR_MENSAGEM M
              JOIN TR_SESSAO S
                ON S.ID_SESSAO = M.ID_SESSAO
               AND S.ID_USUARIO = M.ID_USUARIO
             WHERE M.ID_USUARIO = ?
               AND M.ID_SESSAO IS NOT NULL
               AND M.DATA_HORA_DESFEITA IS NULL
               AND M.DATA_HORA_ENVIO >= ?
               AND M.DATA_HORA_ENVIO <= ?
             ORDER BY M.DATA_HORA_ENVIO DESC, M.ID_MENSAGEM DESC
             FETCH FIRST 1 ROW ONLY
            """;

    // Idempotência do /desfazer (6c): ESTE comando (o message_id dele) já desfez
    // alguma mensagem deste usuário? Por usuário porque o message_id só é único
    // dentro do chat. O UQ_TR_MENSAGEM_DESFEITA_POR garante no máximo uma linha.
    private static final String SQL_JA_DESFEITA_PELO_COMANDO = """
            SELECT 1
              FROM TR_MENSAGEM
             WHERE ID_USUARIO = ?
               AND DESFEITA_POR_ID_TELEGRAM = ?
            """;

    // /desfazer MARCA, nunca apaga (exclusão lógica, regra 2 da 6c).
    // As duas colunas no mesmo UPDATE: a CK_TR_MENSAGEM_DESFEITA exige as duas
    // juntas (quando e por qual comando).
    // AND ID_USUARIO = ?: ninguém desfaz mensagem de outro usuário, nem com um id
    // errado chegando aqui.
    // AND DATA_HORA_DESFEITA IS NULL: só marca o que ainda é válido. Faz o papel de
    // trava sem SELECT ... FOR UPDATE: se entre a busca e este UPDATE a mensagem já
    // foi desfeita (por um UPDATE no SQL Developer), o WHERE não acha a linha e a
    // marca do primeiro /desfazer não é sobrescrita.
    // O mesmo comando numa segunda mensagem do usuário é recusado pelo índice
    // UQ_TR_MENSAGEM_DESFEITA_POR (ORA-00001).
    private static final String SQL_MARCAR_DESFEITA = """
            UPDATE TR_MENSAGEM
               SET DATA_HORA_DESFEITA = ?,
                   DESFEITA_POR_ID_TELEGRAM = ?
             WHERE ID_MENSAGEM = ?
               AND ID_USUARIO = ?
               AND DATA_HORA_DESFEITA IS NULL
            """;

    /**
     * Grava a mensagem crua.
     *
     * @param idUsuario     ID_USUARIO de TR_USUARIO (não o from.id)
     * @param idTelegram    message_id do Telegram (único só dentro do chat do usuário)
     * @param dataHoraEnvio campo "date" do Telegram já convertido para America/Sao_Paulo
     * @param origem        'TEXTO' ou 'AUDIO' (CK_TR_MENSAGEM_ORIGEM)
     * @return gravada (com id) ou já gravada (a mesma mensagem já estava no banco)
     */
    public ResultadoInsercao inserir(Connection conn, long idUsuario, long idTelegram,
            LocalDateTime dataHoraEnvio, String origem, String textoBruto) throws SQLException {

        try (PreparedStatement ps = conn.prepareStatement(SQL_INSERIR, new String[] {"ID_MENSAGEM"})) {
            ps.setLong(1, idUsuario);
            ps.setLong(2, idTelegram);
            // DATA_HORA_ENVIO é DATE: guarda data e hora até o segundo.
            ps.setObject(3, dataHoraEnvio);
            ps.setString(4, origem);
            // TEXTO_BRUTO é CLOB. setString funciona para o tamanho de uma
            // mensagem do Telegram (até 4096 caracteres).
            ps.setString(5, textoBruto);
            ps.executeUpdate();

            return ResultadoInsercao.gravada(JdbcUtil.lerIdGerado(ps));

        } catch (SQLIntegrityConstraintViolationException e) {
            // SQLIntegrityConstraintViolationException cobre QUALQUER violação:
            // UNIQUE (ORA-00001), FK (ORA-02291), NOT NULL (ORA-01400)...
            // Só a UNIQUE desta tabela significa "já gravada". Tratar uma FK como
            // "já gravada" faria a mensagem sumir em silêncio.
            if (e.getErrorCode() == ORA_UNIQUE && e.getMessage() != null
                    && e.getMessage().contains(UQ_MENSAGEM)) {
                return ResultadoInsercao.jaEstavaGravada();
            }
            throw e;
        }
    }

    /**
     * Liga a mensagem à sessão, só se as duas forem do mesmo usuário.
     *
     * @throws IllegalStateException se nada foi atualizado: a mensagem não existe,
     *         não é do usuário, ou a sessão não existe / não é do usuário
     */
    public void vincularSessao(Connection conn, long idMensagem, long idSessao, long idUsuario)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_VINCULAR_SESSAO)) {
            ps.setLong(1, idSessao);
            ps.setLong(2, idMensagem);
            ps.setLong(3, idUsuario);
            ps.setLong(4, idSessao);
            ps.setLong(5, idUsuario);

            int linhas = ps.executeUpdate();
            if (linhas == 0) {
                // Só ids internos na mensagem: nada de texto, nome ou id do Telegram.
                throw new IllegalStateException("Mensagem " + idMensagem + " e sessão " + idSessao
                        + " não pertencem ambas ao usuário " + idUsuario + ".");
            }
        }
    }

    /**
     * Alvo do /desfazer (fatia 6c): a última mensagem do usuário que gerou
     * registro, ainda válida, enviada de desde até ate (as duas pontas incluídas).
     *
     * @param desde envio do /desfazer menos 24 h (America/Sao_Paulo)
     * @param ate   envio do /desfazer (America/Sao_Paulo)
     * @return vazio = nada para desfazer
     */
    public Optional<MensagemParaDesfazer> buscarUltimaParaDesfazer(Connection conn, long idUsuario,
            LocalDateTime desde, LocalDateTime ate) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_BUSCAR_ULTIMA_PARA_DESFAZER)) {
            ps.setLong(1, idUsuario);
            // Mesma conversão do inserir: LocalDateTime de São Paulo, até o
            // segundo, contra o DATE de DATA_HORA_ENVIO.
            ps.setObject(2, desde);
            ps.setObject(3, ate);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new MensagemParaDesfazer(
                        rs.getLong("ID_MENSAGEM"),
                        // DATA_SESSAO é DATE sem hora (CK_TR_SESSAO_DATA_SESSAO).
                        rs.getObject("DATA_SESSAO", LocalDate.class),
                        "S".equals(rs.getString("SESSAO_COM_OBSERVACAO"))));
            }
        }
    }

    /**
     * Idempotência do /desfazer (fatia 6c): este comando já desfez uma mensagem do
     * usuário? true = é uma reentrega do Telegram (o bot caiu depois do commit e
     * antes de confirmar o offset).
     *
     * @param idTelegramDoComando message_id do /desfazer
     */
    public boolean jaDesfeitaPeloComando(Connection conn, long idUsuario, long idTelegramDoComando)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_JA_DESFEITA_PELO_COMANDO)) {
            ps.setLong(1, idUsuario);
            ps.setLong(2, idTelegramDoComando);

            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Marca a mensagem como desfeita (fatia 6c): quando e por qual comando. Nada é
     * apagado: as séries continuam em TR_SERIE e o texto bruto continua aqui.
     *
     * Diferente do vincularSessao, 0 linhas NÃO é exceção: é um resultado possível
     * (a mensagem foi desfeita por outro caminho entre a busca e este UPDATE), e
     * quem chama responde "nada para desfazer".
     *
     * O banco recusa: mensagem sem sessão (ORA-02290, CK_TR_MENSAGEM_DESFEITA) e o
     * mesmo comando numa segunda mensagem do usuário (ORA-00001,
     * UQ_TR_MENSAGEM_DESFEITA_POR).
     *
     * @param quando              data de envio do /desfazer, em America/Sao_Paulo
     * @param idTelegramDoComando message_id do /desfazer (DESFEITA_POR_ID_TELEGRAM)
     * @return true = marcada agora; false = nada atualizado (a mensagem não existe,
     *         não é do usuário ou já estava desfeita)
     */
    public boolean marcarDesfeita(Connection conn, long idMensagem, long idUsuario, LocalDateTime quando,
            long idTelegramDoComando) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_MARCAR_DESFEITA)) {
            // DATA_HORA_DESFEITA é DATE, como DATA_HORA_ENVIO: mesma conversão.
            ps.setObject(1, quando);
            ps.setLong(2, idTelegramDoComando);
            ps.setLong(3, idMensagem);
            ps.setLong(4, idUsuario);

            // PK_TR_MENSAGEM: no máximo 1 linha.
            return ps.executeUpdate() > 0;
        }
    }
}
