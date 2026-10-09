package dev.vitorrodrigues.treino.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import dev.vitorrodrigues.treino.dao.ExercicioDAO;
import dev.vitorrodrigues.treino.dao.MensagemDAO;
import dev.vitorrodrigues.treino.dao.SerieDAO;
import dev.vitorrodrigues.treino.dao.SessaoDAO;
import dev.vitorrodrigues.treino.extrator.ExtracaoException;
import dev.vitorrodrigues.treino.extrator.Extrator;
import dev.vitorrodrigues.treino.extrator.RelatoExtraido;
import dev.vitorrodrigues.treino.extrator.SerieExtraida;
import dev.vitorrodrigues.treino.factory.ConnectionFactory;
import dev.vitorrodrigues.treino.modelo.ExercicioComApelidos;
import dev.vitorrodrigues.treino.modelo.ResultadoInsercao;
import dev.vitorrodrigues.treino.modelo.ResultadoRegistro;
import dev.vitorrodrigues.treino.modelo.ResultadoRegistro.SerieGravada;

/**
 * Um relato em texto vira linhas no banco:
 * mensagem crua -> extrator -> resolução e validação -> sessão + séries.
 *
 * Três etapas separadas de propósito:
 * 1. T1, transação curta: grava a mensagem crua e COMITA antes de tudo. Se a API
 *    ou o banco falharem depois, o texto continua salvo para reprocessar.
 * 2. Catálogo + API, sem transação e sem conexão aberta pelo service: a API pode
 *    levar até 60 s, e segurar uma conexão (e uma transação) esse tempo todo só
 *    prenderia recurso do banco à espera da rede.
 * 3. Resolução e validação em Java puro e, só se tudo passar, T2: sessão + séries
 *    + vínculo da mensagem numa transação só (tudo ou nada).
 *
 * Quem abre, comita e faz rollback é este service; os DAOs só recebem a Connection
 * (regra da fatia 4). O ExercicioDAO é a exceção histórica: abre a própria conexão.
 *
 * PREMISSA: uma mensagem por vez. A T2 faz "buscar sessão do dia; se não houver,
 * inserir". Com duas mensagens do mesmo usuário processadas em paralelo, as duas
 * buscas podiam não achar nada e cada uma inserir a sua: duas sessões no mesmo
 * dia. O banco permite isso (não há UNIQUE em data), então nada falharia; o erro
 * seria silencioso. O loop do Telegram (fatia 6) processa os updates em sequência.
 *
 * Nada aqui é impresso nem vai para mensagem de exceção: nem o texto, nem o id do
 * Telegram, nem a resposta do modelo.
 */
public class RegistroService {

    // Limites espelhados do DDL (01-ddl.sql). Conferir aqui, antes da T2, dá um
    // motivo legível ("series[0].carga_kg com mais de 2 casas decimais") em vez de
    // um código ORA, e não abre transação só para o banco recusar.
    private static final int MAX_EXERCICIO_RELATADO = 100;   // TR_SERIE.EXERCICIO_RELATADO VARCHAR2(100 CHAR)
    private static final int MAX_PERCEPCAO = 500;            // TR_SERIE.PERCEPCAO VARCHAR2(500 CHAR)
    private static final int MAX_OBSERVACAO = 1000;          // TR_SESSAO.OBSERVACAO VARCHAR2(1000 CHAR)
    private static final int MAX_NUMERO_SERIE = 999;         // NUMBER(3) + CK_TR_SERIE_NUMERO_SERIE (>= 1)
    private static final int MAX_REPETICOES = 999;           // NUMBER(3) + CK_TR_SERIE_REPETICOES (>= 0)
    private static final BigDecimal MAX_CARGA_KG = new BigDecimal("9999.99"); // NUMBER(6,2) + CK (>= 0)
    private static final int CASAS_CARGA_KG = 2;             // o ",2" do NUMBER(6,2)

    private final Extrator extrator;
    private final ExercicioDAO exercicioDAO = new ExercicioDAO();
    private final MensagemDAO mensagemDAO = new MensagemDAO();
    private final SessaoDAO sessaoDAO = new SessaoDAO();
    private final SerieDAO serieDAO = new SerieDAO();

    /**
     * @param extrator pronto para uso. Quem sobe o bot cria o ExtratorCliente, e
     *        a chave ausente falha ali, na subida, e não na primeira mensagem.
     */
    public RegistroService(Extrator extrator) {
        this.extrator = Objects.requireNonNull(extrator, "extrator");
    }

    /**
     * Registra uma mensagem de treino.
     *
     * Uma mensagem por vez (ver a premissa no javadoc da classe).
     *
     * LIMITAÇÃO CONHECIDA (decisão de 09/10/2026): observação acumulada acima de
     * 1000 caracteres. Cada mensagem do dia passa na validação sozinha, mas a T2
     * junta a nova observação à da sessão (acrescentarObservacao), e a soma pode
     * passar do VARCHAR2(1000 CHAR). Aí o Oracle recusa (ORA-12899), a T2 desfaz
     * tudo e a mensagem inteira vira NAO_REGISTRADO, séries inclusive; só a
     * mensagem crua fica. O texto do usuário não é cortado em lugar nenhum.
     *
     * @param idUsuario     ID_USUARIO interno (não o from.id). Filtrar quem está
     *                      ATIVO é papel da fatia 6, antes de chamar este método.
     * @param idTelegram    message_id do Telegram
     * @param dataHoraEnvio campo "date" do Telegram, já em America/Sao_Paulo
     * @param origem        'TEXTO' ou 'AUDIO'
     * @param texto         texto da mensagem (ou transcrição do áudio)
     * @throws SQLException se a mensagem crua não foi gravada (T1). Nada foi salvo:
     *         a fatia 6 não avança o offset e o Telegram entrega de novo.
     * @throws InterruptedException se a thread foi interrompida durante a chamada
     *         à API. A mensagem crua já está gravada, sem sessão.
     */
    public ResultadoRegistro registrar(long idUsuario, long idTelegram, LocalDateTime dataHoraEnvio,
            String origem, String texto) throws SQLException, InterruptedException {

        // Data da sessão = data de ENVIO (escopo), nunca a hora em que o Java leu.
        // É também o "Hoje é ..." do prompt.
        LocalDate data = dataHoraEnvio.toLocalDate();

        // --- 1. T1: mensagem crua -------------------------------------------------
        ResultadoInsercao insercao = gravarMensagemCrua(idUsuario, idTelegram, dataHoraEnvio, origem, texto);
        if (insercao.jaGravada()) {
            // Retorna ANTES do catálogo e da API: reentrega do Telegram não gasta
            // crédito nem grava nada duas vezes.
            return ResultadoRegistro.jaGravada(data);
        }
        long idMensagem = insercao.idMensagem();

        // --- 2. Catálogo + extrator (sem transação) --------------------------------
        // Daqui em diante, toda falha esperada vira NAO_REGISTRADO em vez de
        // exceção: a mensagem crua já está salva, então para a fatia 6 o trabalho
        // com esta mensagem acabou (avança o offset e avisa o usuário).
        List<ExercicioComApelidos> catalogo;
        try {
            catalogo = exercicioDAO.listarComApelidos(idUsuario);
        } catch (SQLException e) {
            return ResultadoRegistro.naoRegistrado(data, "catálogo: erro no banco (" + codigoOra(e) + ")");
        }

        RelatoExtraido extraido;
        try {
            extraido = extrator.extrair(texto, data, catalogo);
        } catch (ExtracaoException e) {
            // A mensagem da ExtracaoException, por contrato, só tem status HTTP,
            // tipo de erro, nome e tipo de campo, nunca o relato. A resposta do
            // modelo (getRespostaBruta) fica de fora de propósito.
            return ResultadoRegistro.naoRegistrado(data, "extração: " + e.getMessage());
        } catch (IOException e) {
            // Só a classe (HttpTimeoutException, ConnectException...): a mensagem
            // de uma IOException não tem formato garantido.
            return ResultadoRegistro.naoRegistrado(data,
                    "extração: falha de rede (" + e.getClass().getSimpleName() + ")");
        } catch (InterruptedException e) {
            // Interrupção = alguém pediu para a thread parar (ex.: desligar o bot).
            // Engolir apagaria esse pedido. Restaura a flag E propaga: o loop da
            // fatia 6 recebe a exceção e também enxerga isInterrupted().
            Thread.currentThread().interrupt();
            throw e;
        }

        // --- 3a. Validação + resolução (Java puro, nada no banco) -------------------
        // Primeiro o branco vira null; daqui para baixo (validação, regra do
        // SEM_SERIES, T2 e o resultado) todo mundo usa só o relato normalizado.
        RelatoExtraido relato = brancoViraNull(extraido);

        String problema = problemaDeValidacao(relato);
        if (problema != null) {
            // Tudo ou nada: uma série inválida derruba o relato inteiro. Gravar só
            // as válidas mostraria um treino incompleto como se fosse o certo.
            return ResultadoRegistro.naoRegistrado(data, "validação: " + problema);
        }

        List<String> duvidas = new ArrayList<>();
        // null = o modelo não mandou "duvidas". Não vira dúvida inventada.
        if (relato.duvidas() != null) {
            duvidas.addAll(relato.duvidas());
        }
        List<SerieGravada> series = resolverExercicios(relato.series(), catalogo, duvidas);

        if (series.isEmpty() && relato.observacaoDia() == null) {
            // Nada para gravar (ex.: uma pergunta). Sem T2: não cria sessão vazia.
            // Observação em branco já virou null acima, então também cai aqui.
            return ResultadoRegistro.semSeries(data, duvidas);
        }

        // --- 3b. T2: sessão + séries + vínculo -------------------------------------
        try {
            gravarSessaoESeries(idUsuario, idMensagem, data, relato.observacaoDia(), series);
        } catch (SQLException e) {
            // Só o código: a mensagem do Oracle pode citar o nome do esquema
            // (o usuário do banco) e valores.
            return ResultadoRegistro.naoRegistrado(data, "gravação: erro no banco (" + codigoOra(e) + ")");
        } catch (IllegalStateException e) {
            // vincularSessao / acrescentarObservacao não acharam a linha do usuário.
            // A mensagem da exceção tem ids internos: fica de fora, texto fixo.
            return ResultadoRegistro.naoRegistrado(data, "gravação: sessão ou mensagem não pertence ao usuário");
        }

        // Sem séries e com observação também cai aqui: REGISTRADO com lista vazia.
        return ResultadoRegistro.registrado(data, series, duvidas);
    }

    // T1. Conexão própria, só para isto: comita e fecha antes da API.
    // SQLException aqui sobe para quem chamou (nada foi salvo).
    private ResultadoInsercao gravarMensagemCrua(long idUsuario, long idTelegram, LocalDateTime dataHoraEnvio,
            String origem, String texto) throws SQLException {
        try (Connection conn = ConnectionFactory.getConnection()) {
            conn.setAutoCommit(false);
            boolean comitou = false;
            Throwable original = null;
            try {
                ResultadoInsercao insercao = mensagemDAO.inserir(conn, idUsuario, idTelegram, dataHoraEnvio,
                        origem, texto);
                // No "já gravada" o INSERT falhou e o Oracle já desfez só aquele
                // comando: o commit não tem o que gravar. Fica igual nos dois casos.
                conn.commit();
                comitou = true;
                return insercao;
            } catch (SQLException | RuntimeException | Error e) {
                // Ver o mesmo catch na T2.
                original = e;
                throw e;
            } finally {
                // Mesmo motivo da T2: o driver da Oracle comita ao fechar a conexão.
                if (!comitou) {
                    desfazer(conn, original);
                }
            }
        }
    }

    // T2. Conexão nova (a da T1 já foi fechada antes da API).
    private void gravarSessaoESeries(long idUsuario, long idMensagem, LocalDate data, String observacao,
            List<SerieGravada> series) throws SQLException {
        try (Connection conn = ConnectionFactory.getConnection()) {
            conn.setAutoCommit(false);
            boolean comitou = false;
            Throwable original = null;
            try {
                // MVP: uma sessão por usuário por data. Segunda mensagem do dia
                // entra na sessão que já existe.
                Optional<Long> existente = sessaoDAO.buscarPorUsuarioEData(conn, idUsuario, data);
                long idSessao;
                if (existente.isEmpty()) {
                    idSessao = sessaoDAO.inserir(conn, idUsuario, data, observacao);
                } else {
                    idSessao = existente.get();
                    if (observacao != null) {
                        sessaoDAO.acrescentarObservacao(conn, idSessao, idUsuario, observacao);
                    }
                }

                for (SerieGravada serie : series) {
                    serieDAO.inserir(conn, idMensagem, serie.serie(), serie.idExercicio());
                }
                mensagemDAO.vincularSessao(conn, idMensagem, idSessao, idUsuario);

                conn.commit();
                comitou = true;
            } catch (SQLException | RuntimeException | Error e) {
                // Não trata nada: só guarda qual é a exceção original, para o
                // finally poder pendurar nela um erro do rollback, e relança a
                // mesma. A lista cobre tudo o que este bloco pode lançar (a única
                // checked é SQLException); Error entra para que nem um
                // OutOfMemoryError seja trocado pelo erro do rollback.
                original = e;
                throw e;
            } finally {
                // Rollback explícito sempre que o commit não aconteceu (SQLException,
                // IllegalStateException ou qualquer outro erro). Não dá para confiar
                // no fechamento: o driver da Oracle COMITA o que estiver pendente ao
                // fechar a conexão, e a sessão sairia gravada sem metade das séries.
                if (!comitou) {
                    desfazer(conn, original);
                }
            }
        }
    }

    // Rollback que não esconde a exceção original. Exceção lançada dentro de um
    // finally SUBSTITUI a que estava subindo: sem este try/catch, uma falha no
    // rollback (ex.: a conexão caiu) apagaria a causa de verdade (ex.: o
    // ORA-12899 que fez a T2 falhar). A original é o diagnóstico, e é o código
    // dela que vai para o motivo do NAO_REGISTRADO; a do rollback vai junto
    // como "suppressed" (aparece no stack trace, embaixo da original).
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

    // Texto em branco = não foi dito. "" no Oracle já é NULL, e "   " seria gravado
    // como espaços, um texto que ninguém disse. Pior: numa sessão que já tem
    // observação, acrescentar "" gravaria uma quebra de linha solta no fim, e
    // observação "   " sem séries criaria uma sessão vazia. Por isso observacao_dia
    // e percepcao em branco viram null ANTES da validação. Texto não branco
    // passa como veio (sem strip): o service não mexe no que o usuário disse.
    // exercicio_relatado fica de fora de propósito: é obrigatório, e em branco
    // ele é recusado na validação (não vira null).
    private static RelatoExtraido brancoViraNull(RelatoExtraido relato) {
        List<SerieExtraida> series = new ArrayList<>();
        for (SerieExtraida s : relato.series()) {
            series.add(new SerieExtraida(s.exercicioRelatado(), s.exercicio(), s.numeroSerie(), s.cargaKg(),
                    s.cargaAproximada(), s.repeticoes(), s.repeticoesAproximadas(), s.falha(),
                    nullSeBranco(s.percepcao())));
        }
        return new RelatoExtraido(nullSeBranco(relato.observacaoDia()), series, relato.duvidas());
    }

    private static String nullSeBranco(String texto) {
        return texto == null || texto.isBlank() ? null : texto;
    }

    // Nome oficial devolvido pelo modelo -> ID_EXERCICIO, pelo catálogo que foi
    // para o prompt (mesma lista, sem segunda consulta). equals exato: o prompt
    // manda usar SOMENTE os nomes da lista, então qualquer diferença (caixa,
    // acento) é o modelo saindo da lista, não um apelido.
    private static List<SerieGravada> resolverExercicios(List<SerieExtraida> extraidas,
            List<ExercicioComApelidos> catalogo, List<String> duvidas) {
        Map<String, Long> idPorNome = new HashMap<>();
        for (ExercicioComApelidos exercicio : catalogo) {
            idPorNome.put(exercicio.nome(), exercicio.idExercicio());
        }

        List<SerieGravada> series = new ArrayList<>();
        for (SerieExtraida serie : extraidas) {
            Long idExercicio = null;
            // exercicio null: o modelo não casou com a lista e já registrou a
            // dúvida dele. Fica null, para confirmar depois.
            if (serie.exercicio() != null) {
                idExercicio = idPorNome.get(serie.exercicio());
                if (idExercicio == null) {
                    // O modelo devolveu como "oficial" um nome que não está no
                    // catálogo. A série é gravada mesmo assim (nunca descartar
                    // série), sem id. A dúvida cita o "exercicio" (o nome que o
                    // modelo pôs no campo de nome oficial), nunca o
                    // exercicio_relatado. Uma dúvida por nome, não uma por série.
                    String duvida = "exercício '" + serie.exercicio() + "' não existe no catálogo";
                    if (!duvidas.contains(duvida)) {
                        duvidas.add(duvida);
                    }
                }
            }
            series.add(new SerieGravada(serie, idExercicio));
        }
        return series;
    }

    // null = tudo válido; senão, o primeiro problema. O texto diz só o campo e o
    // limite, nunca o valor: o motivo pode ir para log.
    private static String problemaDeValidacao(RelatoExtraido relato) {
        String observacao = relato.observacaoDia();
        if (observacao != null && caracteres(observacao) > MAX_OBSERVACAO) {
            return "observacao_dia passa de " + MAX_OBSERVACAO + " caracteres";
        }

        List<SerieExtraida> series = relato.series();
        for (int i = 0; i < series.size(); i++) {
            String problema = problemaNaSerie(series.get(i));
            if (problema != null) {
                // Mesmo formato de caminho do LeitorRelatoJson: series[0].campo.
                return "series[" + i + "]." + problema;
            }
        }
        return null;
    }

    private static String problemaNaSerie(SerieExtraida serie) {
        // isBlank e não só isEmpty: "   " passaria pelo NOT NULL, mas é um nome
        // vazio do mesmo jeito. E '' no Oracle é NULL: ORA-01400 na T2.
        String relatado = serie.exercicioRelatado();
        if (relatado == null || relatado.isBlank()) {
            return "exercicio_relatado vazio";
        }
        if (caracteres(relatado) > MAX_EXERCICIO_RELATADO) {
            return "exercicio_relatado passa de " + MAX_EXERCICIO_RELATADO + " caracteres";
        }

        Integer numero = serie.numeroSerie();
        if (numero == null || numero < 1 || numero > MAX_NUMERO_SERIE) {
            return "numero_serie fora de 1.." + MAX_NUMERO_SERIE;
        }

        Integer repeticoes = serie.repeticoes();
        if (repeticoes != null && (repeticoes < 0 || repeticoes > MAX_REPETICOES)) {
            return "repeticoes fora de 0.." + MAX_REPETICOES;
        }

        Double carga = serie.cargaKg();
        if (carga != null) {
            // NaN e infinito não existem em JSON, mas BigDecimal.valueOf lançaria
            // NumberFormatException com eles: barrados antes.
            if (!Double.isFinite(carga)) {
                return "carga_kg não é um número finito";
            }
            // valueOf usa o texto curto do double ("22.333"), não o binário
            // (22.33299999...): é o mesmo número que o JdbcUtil manda ao banco.
            BigDecimal kg = BigDecimal.valueOf(carga);
            if (kg.signum() < 0 || kg.compareTo(MAX_CARGA_KG) > 0) {
                return "carga_kg fora de 0.." + MAX_CARGA_KG;
            }
            // NUMBER(6,2) ARREDONDA em silêncio: 22.333 viraria 22.33. Isso seria
            // gravar um número que ninguém disse. Recusa em vez de arredondar.
            // stripTrailingZeros: 22.50 tem 2 casas de verdade, 1 significativa.
            if (kg.stripTrailingZeros().scale() > CASAS_CARGA_KG) {
                return "carga_kg com mais de " + CASAS_CARGA_KG + " casas decimais";
            }
        }

        // O LeitorRelatoJson já garante os dois; conferir aqui cobre um Extrator
        // que não passe por ele (ex.: o falso do spike). Espelha o NOT NULL.
        if (serie.cargaAproximada() == null) {
            return "carga_aproximada ausente";
        }
        if (serie.repeticoesAproximadas() == null) {
            return "repeticoes_aproximadas ausente";
        }

        String percepcao = serie.percepcao();
        if (percepcao != null && caracteres(percepcao) > MAX_PERCEPCAO) {
            return "percepcao passa de " + MAX_PERCEPCAO + " caracteres";
        }
        return null;
    }

    // Conta caracteres, não unidades UTF-16. length() conta um emoji como 2 (ele
    // ocupa um "par substituto" em UTF-16); o VARCHAR2(n CHAR) conta 1. Com
    // length(), um texto que cabe na coluna seria recusado.
    private static int caracteres(String texto) {
        return texto.codePointCount(0, texto.length());
    }

    // Formato que o Oracle usa: ORA- e 5 dígitos (ORA-12899, ORA-00001).
    private static String codigoOra(SQLException e) {
        return e.getErrorCode() > 0
                ? String.format("ORA-%05d", e.getErrorCode())
                : "sem código ORA";
    }
}
