package dev.vitorrodrigues.treino.bot;

import java.io.IOException;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLSyntaxErrorException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import dev.vitorrodrigues.treino.modelo.ResultadoRegistro;
import dev.vitorrodrigues.treino.modelo.Usuario;
import dev.vitorrodrigues.treino.service.Registrador;
import tools.jackson.databind.JsonNode;

/**
 * O loop do bot (fatia 6a): getUpdates -> filtros -> RegistroService -> resposta.
 *
 * REGRA CENTRAL DO OFFSET. O offset diz ao Telegram "até aqui já processei"; o
 * que fica antes dele o Telegram apaga. Então o offset só avança quando a
 * mensagem já está salva no banco, ou quando insistir não adianta (recusa do
 * banco, erro inesperado, teto de tentativas). Enquanto o banco não salvou, a
 * única cópia da mensagem é a do Telegram.
 *
 * Um update por vez, em sequência: é a premissa do RegistroService (com dois em
 * paralelo, a mesma data poderia ganhar duas sessões).
 *
 * Todas as dependências chegam por interface (ClienteTelegram, Registrador,
 * BuscaUsuario, Espera), mais o relógio e a saída do log. Assim o TelegramSpike
 * exercita o loop inteiro sem celular, sem banco e sem rede.
 *
 * LOG: uma linha por update. Nunca vai para o log: texto da mensagem, nome,
 * from.id, ID_USUARIO, dúvidas, token. Só tipo, situação, motivo (que, por
 * contrato, não tem texto do usuário), código ORA, status HTTP e nome de classe.
 */
public class LoopTelegram {

    /** Por que o loop parou. */
    public enum Encerramento {
        /** Pedido de parada (thread interrompida). */
        INTERROMPIDO,
        /** HTTP 409 no getUpdates: outra instância com o mesmo token. */
        OUTRA_INSTANCIA,
        /** HTTP 401 no getUpdates: token inválido ou revogado. */
        TOKEN_INVALIDO,
        /** HTTP 404 no getUpdates: token com formato inválido. */
        TOKEN_MALFORMADO
    }

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter FORMATO_LOG =
            DateTimeFormatter.ofPattern("dd/MM HH:mm:ss").withZone(SAO_PAULO);
    // Vírgula decimal na duração ("2,4 s"), como o resto do que o Vitor lê.
    private static final Locale PT_BR = Locale.of("pt", "BR");

    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_FORBIDDEN = 403;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_CONFLICT = 409;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    // Seção 6: falha de rede, 5xx, outros status e 429 sem retry_after: espera
    // 5 s e tenta de novo, sem teto (como o EcoSpike).
    private static final Duration ESPERA_GET_UPDATES = Duration.ofSeconds(5);

    // Seção 10: uma nova tentativa de envio, 2 s depois.
    private static final Duration ESPERA_REENVIO = Duration.ofSeconds(2);
    private static final int TENTATIVAS_DE_ENVIO = 2;

    // Seção 2: espera antes da 2ª, 3ª, 4ª, 5ª tentativa; da 6ª em diante, 60 s.
    // Dobra no começo (o banco costuma voltar logo) e para em 60 s.
    private static final List<Duration> ESPERAS_BANCO = List.of(
            Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(20),
            Duration.ofSeconds(40), Duration.ofSeconds(60));
    private static final int MAX_TENTATIVAS_BANCO = 10;

    // Rótulo do tipo no log. Ordem importa: numa animação o Telegram manda
    // "animation" E "document", então "animation" vem antes.
    private static final List<Map.Entry<String, String>> TIPOS_DE_MENSAGEM = List.of(
            Map.entry("text", "texto"),
            Map.entry("sticker", "figurinha"),
            Map.entry("photo", "foto"),
            Map.entry("voice", "voz"),
            Map.entry("audio", "áudio"),
            Map.entry("video_note", "vídeo redondo"),
            Map.entry("video", "vídeo"),
            Map.entry("animation", "gif"),
            Map.entry("document", "documento"),
            Map.entry("location", "localização"),
            Map.entry("contact", "contato"),
            Map.entry("poll", "enquete"));

    private final ClienteTelegram telegram;
    private final Registrador registrador;
    private final BuscaUsuario usuarios;
    private final Espera espera;
    private final Clock relogio;
    private final Consumer<String> log;

    // Começa em 0: na subida, o getUpdates devolve tudo o que ficou pendente
    // (o que o notebook desligado guardou). Nada é descartado. Fica só em
    // memória (persistir o offset está fora da 6a): numa nova subida, o que já
    // foi gravado volta como JA_GRAVADA e é respondido com silêncio.
    private long offset = 0;

    // Seção 2: tentativas do update que está falhando no banco. Só o primeiro
    // update não processado pode estar falhando (os outros esperam atrás dele,
    // porque o offset não avança), então um par de campos basta.
    private long updateComFalha = -1;
    private int tentativasNoBanco = 0;

    public LoopTelegram(ClienteTelegram telegram, Registrador registrador, BuscaUsuario usuarios,
            Espera espera, Clock relogio, Consumer<String> log) {
        this.telegram = Objects.requireNonNull(telegram, "telegram");
        this.registrador = Objects.requireNonNull(registrador, "registrador");
        this.usuarios = Objects.requireNonNull(usuarios, "usuarios");
        this.espera = Objects.requireNonNull(espera, "espera");
        this.relogio = Objects.requireNonNull(relogio, "relogio");
        this.log = Objects.requireNonNull(log, "log");
    }

    /**
     * Roda até um motivo de parada (409, 401, 404 ou interrupção).
     */
    public Encerramento rodar() {
        try {
            while (true) {
                Encerramento fim = umaVolta();
                if (fim != null) {
                    return fim;
                }
            }
        } catch (InterruptedException e) {
            // Seção 3: interrupção é pedido de parada, não erro. Restaura a flag
            // (quem chamou pode precisar saber) e encerra.
            Thread.currentThread().interrupt();
            logar("loop | pedido de parada; encerrando");
            return Encerramento.INTERROMPIDO;
        }
    }

    /**
     * Uma volta: um getUpdates e o processamento do que veio.
     * Público para o TelegramSpike controlar volta a volta.
     *
     * @return null = continuar; senão, por que parar
     */
    public Encerramento umaVolta() throws InterruptedException {
        // Uma flag de interrupção esquecida por alguém não faz o loop seguir.
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }

        List<JsonNode> updates;
        try {
            updates = telegram.buscarUpdates(offset);
        } catch (ErroTelegram e) {
            // Seção 6: 409 e 401 encerram. Insistir não resolve nenhum dos dois: o
            // 409 continua enquanto a outra instância existir (e as duas ficariam
            // disputando as mensagens), o 401 até alguém trocar o token.
            if (e.status() == HTTP_CONFLICT) {
                logar("getUpdates | HTTP 409 | outra instância já está rodando; encerrando");
                return Encerramento.OUTRA_INSTANCIA;
            }
            if (e.status() == HTTP_UNAUTHORIZED) {
                logar("getUpdates | HTTP 401 | token inválido ou revogado; encerrando");
                return Encerramento.TOKEN_INVALIDO;
            }
            // 404: a URL do método não existe. Os métodos são fixos no código,
            // então o que está errado é o token que vai na URL (formato inválido).
            // Também não se resolve sozinho. Nem o token nem a URL vão para o log.
            if (e.status() == HTTP_NOT_FOUND) {
                logar("getUpdates | HTTP 404 | token com formato inválido; encerrando");
                return Encerramento.TOKEN_MALFORMADO;
            }
            // 429: o Telegram diz quanto esperar (parameters.retry_after). Tentar
            // antes disso só renova o bloqueio. Sem o campo, a espera padrão.
            // Passa pela Espera, como todas: no TelegramSpike não espera de verdade.
            if (e.status() == HTTP_TOO_MANY_REQUESTS) {
                Duration aguardar = e.retryAfter() != null
                        ? Duration.ofSeconds(e.retryAfter())
                        : ESPERA_GET_UPDATES;
                logar("getUpdates | HTTP 429 | nova tentativa em " + segundos(aguardar));
                espera.esperar(aguardar);
                return null;
            }
            // 5xx e qualquer outro status: problema do lado do Telegram ou
            // passageiro. Espera e tenta de novo, sem teto.
            logar("getUpdates | HTTP " + e.status() + " | nova tentativa em " + segundos(ESPERA_GET_UPDATES));
            espera.esperar(ESPERA_GET_UPDATES);
            return null;
        } catch (IOException e) {
            // Rede caiu: espera e tenta de novo, sem teto. Nada se perde: o
            // Telegram guarda os updates por 24 h. Toda falha do getUpdates (aqui e
            // no catch acima) deixa uma linha no log, com o status ou a classe e a
            // espera aplicada: sem teto, a linha é o único sinal de que o bot
            // está parado esperando.
            logar("getUpdates | " + classeDaFalha(e) + " | nova tentativa em " + segundos(ESPERA_GET_UPDATES));
            espera.esperar(ESPERA_GET_UPDATES);
            return null;
        }

        for (JsonNode update : updates) {
            // Sem update_id não há como avançar o offset. O Telegram sempre manda;
            // se um dia não mandar, a exceção sobe e derruba o bot (falha alta),
            // em vez de pular a mensagem ou repeti-la para sempre.
            long updateId = inteiro(update, "update_id");
            Duration esperaAntesDeRepetir = processar(update, updateId);
            if (esperaAntesDeRepetir != null) {
                // NÃO avança: o próximo getUpdates, com o mesmo offset, traz este
                // update de novo, e os que vieram atrás dele, na mesma ordem.
                espera.esperar(esperaAntesDeRepetir);
                return null;
            }
            offset = updateId + 1;
        }
        return null;
    }

    /** update_id do próximo update a pedir ao Telegram. Para o TelegramSpike. */
    public long offset() {
        return offset;
    }

    // --- Um update ----------------------------------------------------------------------

    private static final String SEM_RESULTADO = "sem resultado";

    // O que se sabe do update enquanto ele é processado: usado pelos catch e pela
    // linha de log.
    private static final class Atendimento {
        String tipo = "desconhecido";
        String resultado = SEM_RESULTADO;
        // Preenchido só depois de confirmar que o remetente está ATIVO. null = o
        // bot NÃO responde (ver responder).
        Long chatId;
        String falhaNoEnvio;
    }

    /**
     * @return null = avança o offset; senão, quanto esperar antes de repetir este update
     */
    private Duration processar(JsonNode update, long updateId) throws InterruptedException {
        Instant inicio = relogio.instant();
        Atendimento atendimento = new Atendimento();
        Duration esperaAntesDeRepetir = null;
        try {
            atender(update, atendimento);
        } catch (SQLException e) {
            esperaAntesDeRepetir = tratarErroNoBanco(e, updateId, atendimento);
        } catch (RuntimeException e) {
            // Seção 3: erro inesperado (bug, JSON fora do esperado). Capturado POR
            // UPDATE: um update ruim não derruba o bot nem trava a fila. Avança,
            // porque repetir daria o mesmo erro. No log, só a classe: a mensagem
            // de uma exceção qualquer pode carregar texto do usuário.
            // Se o erro veio depois do registro (ex.: ao montar a confirmação), o
            // log mantém a situação que o service devolveu.
            String antes = SEM_RESULTADO.equals(atendimento.resultado) ? "" : atendimento.resultado + "; ";
            atendimento.resultado = antes + "erro inesperado (" + e.getClass().getSimpleName() + "); avançou";
            responder(atendimento, Textos.ERRO_INESPERADO);
        } catch (InterruptedException e) {
            atendimento.resultado = "interrompido (pedido de parada)";
            throw e;
        } finally {
            logarUpdate(inicio, updateId, atendimento);
        }
        if (esperaAntesDeRepetir == null && updateId == updateComFalha) {
            // O update que vinha falhando foi resolvido (ou abandonado): zera.
            updateComFalha = -1;
            tentativasNoBanco = 0;
        }
        return esperaAntesDeRepetir;
    }

    // Seção 1: os filtros, NESTA ordem. Cada "return" é um update encerrado (o
    // offset avança). SQLException sobe para tratarErroNoBanco.
    private void atender(JsonNode update, Atendimento atendimento) throws SQLException, InterruptedException {
        // 1. Só mensagem nova ou editada interessa (o resto: entrada em grupo,
        //    bloqueio do bot etc.). O tipo vai para o log.
        JsonNode nova = opcional(update, "message");
        JsonNode editada = opcional(update, "edited_message");
        if (nova == null && editada == null) {
            atendimento.tipo = tipoDoUpdate(update);
            atendimento.resultado = "ignorado: não é mensagem";
            return;
        }
        boolean edicao = nova == null;
        JsonNode mensagem = edicao ? editada : nova;
        atendimento.tipo = edicao ? "edição" : tipoDaMensagem(mensagem);

        // 2. Só conversa privada. Em silêncio: o bot não entra em grupos, e
        //    responder lá exporia o bot (e o que ele diz) a quem não se cadastrou.
        JsonNode chat = mensagem.required("chat");
        if (!"private".equals(texto(chat, "type"))) {
            atendimento.resultado = "ignorado: não é chat privado";
            return;
        }

        // 3. Sem remetente não há como saber se é ATIVO.
        JsonNode from = opcional(mensagem, "from");
        if (from == null) {
            atendimento.resultado = "ignorado: sem remetente";
            return;
        }

        // 4. Só quem está ATIVO em TR_USUARIO. Em silêncio e sem gravar nada em
        //    TR_MENSAGEM: o bot não conversa com desconhecidos nem guarda texto
        //    de quem não aceitou o aviso de boas-vindas. (A 6b muda isso para
        //    quem mandar /start.)
        Optional<Usuario> remetente = usuarios.buscarPorIdTelegram(inteiro(from, "id"));
        if (remetente.isEmpty() || !remetente.get().ativo()) {
            atendimento.resultado = "ignorado: remetente não está ATIVO";
            return;
        }
        Usuario usuario = remetente.get();
        // A partir daqui o bot pode responder. chat.id e não from.id: é o
        // endereço da conversa (no privado os dois coincidem).
        atendimento.chatId = inteiro(chat, "id");

        // 5. Edição não muda o registro: correção é sempre mensagem nova
        //    (escopo). Não chama o service.
        if (edicao) {
            atendimento.resultado = "ignorado: edição";
            responder(atendimento, Textos.EDICAO_IGNORADA);
            return;
        }

        // 6. Figurinha, foto, voz, documento ou texto em branco. Sem este filtro,
        //    "" chegaria ao service, viraria NULL no Oracle, a T1 daria ORA-01400
        //    e a fila de todos travaria nessa mensagem.
        String texto = textoOpcional(mensagem, "text");
        if (texto == null || texto.isBlank()) {
            atendimento.resultado = "ignorado: sem texto";
            responder(atendimento, Textos.SO_ENTENDO_TEXTO);
            return;
        }

        // 7. Comando nunca vai para o extrator: não é relato de treino, e
        //    gastaria crédito à toa.
        if (texto.startsWith("/")) {
            tratarComando(atendimento);
            return;
        }

        // 8. Relato. Data de ENVIO (campo date: epoch em segundos, UTC)
        //    convertida para São Paulo, nunca a hora em que o Java leu: com o bot
        //    parado, o treino de ontem chega hoje e tem que ir para ontem.
        LocalDateTime envio = LocalDateTime.ofInstant(Instant.ofEpochSecond(inteiro(mensagem, "date")), SAO_PAULO);
        ResultadoRegistro resultado = registrador.registrar(usuario.idUsuario(), inteiro(mensagem, "message_id"),
                envio, "TEXTO", texto);

        // O motivo pode ir para o log: por contrato, não tem texto do usuário.
        // As dúvidas, não: ficam só na resposta.
        atendimento.resultado = resultado.situacao()
                + (resultado.motivo() == null ? "" : ": " + resultado.motivo());
        String resposta = Respostas.para(resultado);
        if (resposta != null) {
            responder(atendimento, resposta);
        }
    }

    // Ponto único de roteamento de comandos. Na 6a não existe comando conhecido:
    // tudo é "não reconhecido". A 6b acrescenta /start e /aprovar AQUI.
    private void tratarComando(Atendimento atendimento) throws InterruptedException {
        atendimento.resultado = "ignorado: comando não reconhecido";
        responder(atendimento, Textos.COMANDO_NAO_RECONHECIDO);
    }

    // --- Seção 2: SQLException antes de a mensagem estar salva ------------------------

    // Vale para a busca do remetente e para a T1 do registrar (as únicas que
    // sobem SQLException: depois da T1, o service devolve NAO_REGISTRADO).
    private Duration tratarErroNoBanco(SQLException e, long updateId, Atendimento atendimento)
            throws InterruptedException {
        String onde = atendimento.chatId == null ? "ao buscar o remetente" : "ao gravar a mensagem";
        String codigo = codigoOra(e);

        if (recusaDoBanco(e)) {
            atendimento.resultado = "recusa do banco " + onde + " (" + codigo + "); avançou";
            responder(atendimento, Textos.NAO_CONSEGUI_SALVAR);
            return null;
        }

        int tentativa = contarTentativa(updateId);
        if (tentativa >= MAX_TENTATIVAS_BANCO) {
            // Teto: a classificação acima pode errar, e um erro permanente sem teto
            // congelaria a fila de TODOS os usuários atrás desta mensagem.
            atendimento.resultado = "erro no banco " + onde + " (" + codigo + "), tentativa " + tentativa
                    + " de " + MAX_TENTATIVAS_BANCO + "; desistiu e avançou";
            responder(atendimento, Textos.NAO_CONSEGUI_SALVAR_DEPOIS_DE_TENTAR);
            return null;
        }

        // Não responde a cada tentativa: o usuário receberia 9 avisos. Só no fim.
        Duration esperaAntesDeRepetir = ESPERAS_BANCO.get(Math.min(tentativa, ESPERAS_BANCO.size()) - 1);
        atendimento.resultado = "erro no banco " + onde + " (" + codigo + "), tentativa " + tentativa
                + " de " + MAX_TENTATIVAS_BANCO + "; repete em " + segundos(esperaAntesDeRepetir);
        return esperaAntesDeRepetir;
    }

    // Recusa = o banco entendeu o pedido e recusou o DADO ou o comando (constraint,
    // tipo, SQL inválido). Repetir manda o mesmo dado e recebe a mesma recusa: só
    // travaria a fila. Qualquer outra SQLException (conexão, rede, banco fora do
    // ar) pode passar sozinha: aí vale esperar e repetir.
    // ATENÇÃO (relatório da 6a): o driver lança ORA-12899 (texto grande demais)
    // como SQLException genérica, fora desta lista. Hoje ele não chega aqui (a T1
    // não grava coluna limitada com texto do usuário); se chegar, cai no teto.
    private static boolean recusaDoBanco(SQLException e) {
        return e instanceof SQLIntegrityConstraintViolationException
                || e instanceof SQLDataException
                || e instanceof SQLSyntaxErrorException;
    }

    private int contarTentativa(long updateId) {
        if (updateId != updateComFalha) {
            updateComFalha = updateId;
            tentativasNoBanco = 0;
        }
        return ++tentativasNoBanco;
    }

    // --- Seção 10: envio --------------------------------------------------------------

    // Uma nova tentativa, 2 s depois, e desiste: o dado já está no banco, então
    // travar a fila por causa da resposta não vale a pena. 403 = o usuário
    // bloqueou o bot: tentar de novo dá 403 de novo.
    //
    // Nunca lança RuntimeException: o envio é o último passo. Uma falha aqui,
    // de qualquer tipo, vira "resposta não enviada" no log. Se escapasse, cairia
    // no catch de erro inesperado, que tentaria responder de novo, e um erro
    // dentro de um catch derrubaria o loop.
    private void responder(Atendimento atendimento, String texto) throws InterruptedException {
        // Só responde a remetente confirmado ATIVO. Erro antes dessa confirmação
        // (banco fora do ar na busca, JSON estranho) fica sem resposta: o bot não
        // fala com quem não sabe se pode.
        if (atendimento.chatId == null) {
            return;
        }
        for (int tentativa = 1; ; tentativa++) {
            String falha;
            boolean valeRepetir = true;
            try {
                telegram.enviarMensagem(atendimento.chatId, texto);
                return;
            } catch (ErroTelegram e) {
                falha = "HTTP " + e.status();
                valeRepetir = e.status() != HTTP_FORBIDDEN;
            } catch (IOException e) {
                falha = classeDaFalha(e);
            } catch (RuntimeException e) {
                falha = e.getClass().getSimpleName();
            }
            if (!valeRepetir || tentativa >= TENTATIVAS_DE_ENVIO) {
                atendimento.falhaNoEnvio = falha;
                return;
            }
            espera.esperar(ESPERA_REENVIO);
        }
    }

    // --- Leitura do JSON do update ------------------------------------------------------
    // Regra do projeto: obrigatório com required() + checagem de tipo; opcional
    // (ausente ou null) vira null. Nunca path().asXxx(): devolveria 0 ou "" em
    // silêncio. Falha = RuntimeException, que cai na seção 3. As mensagens citam
    // só o nome do campo.

    private static JsonNode opcional(JsonNode objeto, String campo) {
        JsonNode valor = objeto.get(campo);
        return valor == null || valor.isNull() ? null : valor;
    }

    private static long inteiro(JsonNode objeto, String campo) {
        JsonNode valor = objeto.required(campo);
        if (!valor.isIntegralNumber() || !valor.canConvertToLong()) {
            throw new IllegalArgumentException("Campo \"" + campo + "\" do update não é um inteiro.");
        }
        return valor.longValue();
    }

    private static String texto(JsonNode objeto, String campo) {
        JsonNode valor = objeto.required(campo);
        if (!valor.isString()) {
            throw new IllegalArgumentException("Campo \"" + campo + "\" do update não é texto.");
        }
        return valor.stringValue();
    }

    private static String textoOpcional(JsonNode objeto, String campo) {
        JsonNode valor = opcional(objeto, campo);
        if (valor == null) {
            return null;
        }
        if (!valor.isString()) {
            throw new IllegalArgumentException("Campo \"" + campo + "\" do update não é texto.");
        }
        return valor.stringValue();
    }

    // Nome do campo que o Telegram mandou (my_chat_member, callback_query...).
    // É nome de campo da API, não conteúdo.
    private static String tipoDoUpdate(JsonNode update) {
        for (String nome : update.propertyNames()) {
            if (!nome.equals("update_id")) {
                return nome;
            }
        }
        return "desconhecido";
    }

    private static String tipoDaMensagem(JsonNode mensagem) {
        for (Map.Entry<String, String> tipo : TIPOS_DE_MENSAGEM) {
            if (mensagem.has(tipo.getKey())) {
                return tipo.getValue();
            }
        }
        return "outro";
    }

    // --- Log --------------------------------------------------------------------------

    // "09/10 10:02:15 | update 418230551 | texto | REGISTRADO | 2,4 s"
    private void logarUpdate(Instant inicio, long updateId, Atendimento atendimento) {
        String resultado = atendimento.resultado
                + (atendimento.falhaNoEnvio == null ? "" : "; resposta não enviada (" + atendimento.falhaNoEnvio + ")");
        double segundos = Duration.between(inicio, relogio.instant()).toMillis() / 1000.0;
        log.accept(FORMATO_LOG.format(inicio) + " | update " + updateId + " | " + atendimento.tipo
                + " | " + resultado + " | " + String.format(PT_BR, "%.1f s", segundos));
    }

    // Linhas que não são de um update (getUpdates, parada).
    private void logar(String texto) {
        log.accept(FORMATO_LOG.format(relogio.instant()) + " | " + texto);
    }

    private static String segundos(Duration duracao) {
        return duracao.toSeconds() + " s";
    }

    // Falha de rede no log = só o nome da classe, nunca a mensagem: a mensagem de
    // uma IOException do java.net.http pode trazer a URL, e a URL tem o token. A
    // FalhaDeRede (lançada pelo ClienteTelegramHttp) já chega sem a original e
    // guarda o nome da classe dela.
    private static String classeDaFalha(IOException e) {
        return e instanceof FalhaDeRede falha ? falha.classeOriginal() : e.getClass().getSimpleName();
    }

    // Só o código: a mensagem do Oracle pode citar o esquema (o usuário do banco)
    // e valores. Mesmo formato do RegistroService.
    static String codigoOra(SQLException e) {
        return e.getErrorCode() > 0 ? String.format("ORA-%05d", e.getErrorCode()) : "sem código ORA";
    }
}
