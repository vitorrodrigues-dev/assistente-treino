package dev.vitorrodrigues.treino.bot;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.vitorrodrigues.treino.dao.UsuarioDAO;
import dev.vitorrodrigues.treino.extrator.ExtratorCliente;
import dev.vitorrodrigues.treino.factory.ConnectionFactory;
import dev.vitorrodrigues.treino.modelo.Usuario;
import dev.vitorrodrigues.treino.service.RegistroService;

/**
 * O bot de verdade, rodando no notebook (fatia 6a).
 *
 * Subida: confere tudo o que dá para conferir ANTES do loop e falha alto, com
 * mensagem clara, se algo faltar. Melhor não subir do que subir e descobrir o
 * problema na primeira mensagem de um amigo. Não chama a API da Anthropic nem a
 * do Telegram só para testar (custaria crédito / consumiria updates).
 *
 * Rodar (da raiz do projeto):
 * ./mvnw -q compile exec:java "-Dexec.mainClass=dev.vitorrodrigues.treino.bot.Bot"
 * Parar: Ctrl+C.
 */
public class Bot {

    // As seis do CLAUDE.md. TELEGRAM_CHAT_ID_PERMITIDO = from.id do administrador.
    private static final List<String> VARIAVEIS = List.of(
            "TELEGRAM_BOT_TOKEN",
            "TELEGRAM_CHAT_ID_PERMITIDO",
            "ORACLE_URL",
            "ORACLE_USER",
            "ORACLE_PASSWORD",
            "ANTHROPIC_API_KEY");

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

    public static void main(String[] args) {
        LoopTelegram loop;
        try {
            loop = subir();
        } catch (IllegalStateException e) {
            // As mensagens de subida (daqui, da ConnectionFactory, do ExtratorCliente
            // e do ClienteTelegramHttp) citam o NOME da variável, nunca o valor.
            System.err.println("O bot não subiu: " + e.getMessage());
            System.exit(1);
            return;
        }

        System.out.println("Bot no ar. Ctrl+C para parar.");
        LoopTelegram.Encerramento fim = loop.rodar();
        switch (fim) {
            case INTERROMPIDO -> System.out.println("Bot encerrado.");
            case OUTRA_INSTANCIA -> {
                System.err.println("Bot encerrado: outra instância já está rodando com o mesmo token.");
                System.exit(1);
            }
            case TOKEN_INVALIDO -> {
                System.err.println("Bot encerrado: TELEGRAM_BOT_TOKEN inválido ou revogado (HTTP 401).");
                System.exit(1);
            }
            case TOKEN_MALFORMADO -> {
                // Só o nome da variável: nunca o token nem a URL.
                System.err.println("Bot encerrado: TELEGRAM_BOT_TOKEN com formato inválido (HTTP 404).");
                System.exit(1);
            }
        }
    }

    // Seção 7, nesta ordem: variáveis -> banco -> administrador ATIVO -> peças.
    private static LoopTelegram subir() {
        // 1. Todas as variáveis de uma vez: quem esqueceu duas descobre as duas
        //    numa execução só. Só os nomes na mensagem.
        List<String> faltando = new ArrayList<>();
        for (String nome : VARIAVEIS) {
            String valor = System.getenv(nome);
            if (valor == null || valor.isBlank()) {
                faltando.add(nome);
            }
        }
        if (!faltando.isEmpty()) {
            throw new IllegalStateException("variável de ambiente ausente ou vazia: "
                    + String.join(", ", faltando) + ".");
        }
        long idAdministrador = lerId("TELEGRAM_CHAT_ID_PERMITIDO");

        // 2. O banco conecta? Só o código ORA na mensagem: a do Oracle pode trazer
        //    o host da URL e o usuário do banco.
        UsuarioDAO usuarioDAO = new UsuarioDAO();
        Connection conn;
        try {
            conn = ConnectionFactory.getConnection();
        } catch (SQLException e) {
            throw new IllegalStateException("o banco não conectou (" + LoopTelegram.codigoOra(e) + ").");
        }

        // 3. O administrador existe e está ATIVO? Sem ele, ninguém aprova ninguém
        //    (6b) e, na 6a, ninguém usa o bot. Mesma conexão, fechada em seguida.
        try (conn) {
            Optional<Usuario> administrador = usuarioDAO.buscarPorIdTelegram(conn, idAdministrador);
            if (administrador.isEmpty() || !administrador.get().ativo()) {
                throw new IllegalStateException("o id em TELEGRAM_CHAT_ID_PERMITIDO não existe em TR_USUARIO "
                        + "com status ATIVO.");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("erro ao consultar TR_USUARIO (" + LoopTelegram.codigoOra(e) + ").");
        }

        // 4. As peças. O ExtratorCliente é criado AQUI, como o javadoc do
        //    RegistroService pede: chave ausente falha na subida, não na primeira
        //    mensagem.
        RegistroService registro = new RegistroService(new ExtratorCliente());
        ClienteTelegram telegram = new ClienteTelegramHttp(System.getenv("TELEGRAM_BOT_TOKEN"));

        // Busca do remetente: uma conexão por busca (sem pool, regra do projeto).
        // Só leitura: autoCommit fica como está, não há o que comitar nem desfazer.
        BuscaUsuario usuarios = idTelegram -> {
            try (Connection c = ConnectionFactory.getConnection()) {
                return usuarioDAO.buscarPorIdTelegram(c, idTelegram);
            }
        };

        return new LoopTelegram(telegram, registro, usuarios, Thread::sleep, Clock.system(SAO_PAULO),
                System.out::println);
    }

    private static long lerId(String nome) {
        try {
            return Long.parseLong(System.getenv(nome).strip());
        } catch (NumberFormatException e) {
            // A mensagem da NumberFormatException contém o valor: não é repassada.
            throw new IllegalStateException("variável de ambiente " + nome + " não é um número inteiro.");
        }
    }
}
