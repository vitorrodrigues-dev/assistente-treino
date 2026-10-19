package dev.vitorrodrigues.treino.bot;

/**
 * Todas as frases que o usuário lê no chat, num lugar só (fatias 6a, 6b, 6c e 7).
 *
 * Todas PROVISÓRIAS: o Vitor reescreve antes do teste com amigos (o texto que
 * vai para os amigos não se delega). Com elas aqui, reescrever é editar um
 * arquivo, sem caçar String pelo código.
 *
 * A ESTRUTURA da confirmação (data explícita, séries agrupadas, "~", "—",
 * ordinal, bloco de dúvidas) é decisão tomada e mora no Respostas. Aqui ficam só
 * as palavras.
 *
 * Texto puro: nada de Markdown. O bot não usa parse_mode (ver ClienteTelegramHttp).
 *
 * Público só para o TelegramSpike montar as respostas esperadas com as mesmas
 * constantes (assim o spike continua valendo depois que as palavras mudarem).
 */
public final class Textos {

    private Textos() {
    }

    // --- Filtros (seção 1) ---------------------------------------------------------

    // PROVISÓRIO. Desde a 6c, orienta para o /desfazer: uma mensagem nova com a
    // correção ACRESCENTARIA séries, não corrigiria.
    public static final String EDICAO_IGNORADA =
            "Edições não mudam o registro. Para corrigir, mande /desfazer e envie o relato de novo.";

    // PROVISÓRIO
    public static final String SO_ENTENDO_TEXTO = "Por enquanto só entendo texto.";

    // PROVISÓRIO
    public static final String COMANDO_NAO_RECONHECIDO = "Comando não reconhecido.";

    // --- Erros (seções 2 e 3) --------------------------------------------------------

    // PROVISÓRIO. O banco recusou a mensagem (repetir não muda nada).
    public static final String NAO_CONSEGUI_SALVAR = "Não consegui salvar essa mensagem.";

    // PROVISÓRIO. O banco ficou fora do ar nas 10 tentativas.
    public static final String NAO_CONSEGUI_SALVAR_DEPOIS_DE_TENTAR =
            "Não consegui salvar essa mensagem agora. Tente reenviar mais tarde.";

    // PROVISÓRIO
    public static final String ERRO_INESPERADO =
            "Tive um erro com essa mensagem. Se ela não aparecer no seu registro, reenvie.";

    // --- Situações do registro (seção 5) ----------------------------------------------

    // PROVISÓRIO
    public static final String SEM_SERIES =
            "Não encontrei séries nessa mensagem. Se era uma pergunta, as consultas chegam em breve.";

    // PROVISÓRIO
    public static final String NAO_REGISTRADO =
            "Seu texto ficou salvo, mas não consegui registrar. Reenvie com outras palavras.";

    // PROVISÓRIO
    public static final String OBSERVACAO_CHEIA =
            "A observação de hoje já atingiu o limite. Mande só as séries, sem observação.";

    // --- Confirmação do REGISTRADO (seção 4) --------------------------------------------

    // PROVISÓRIO. %s = data dd/MM/yyyy.
    public static final String REGISTRADO_EM = "Registrado em %s:";

    // PROVISÓRIO. %s = exercicio_relatado.
    public static final String NAO_RECONHECI = "Não reconheci: \"%s\"";

    // PROVISÓRIO
    public static final String OBSERVACAO_ANOTADA = "Anotei a observação do dia.";

    // PROVISÓRIO
    public static final String PONTOS_NAO_CLAROS = "Pontos que não ficaram claros:";

    // PROVISÓRIO
    public static final String FALHA = "(falha)";

    // PROVISÓRIO
    public static final String SEM_FALHA = "(sem falha)";

    // PROVISÓRIO. Última linha da confirmação, só no REGISTRADO (fatia 6c): é
    // onde o usuário confere o que o bot entendeu e descobre como corrigir.
    public static final String DICA_DESFAZER = "Algo errado? Mande /desfazer e envie de novo.";

    // --- Cadastro (fatia 6b) -------------------------------------------------------------

    // PROVISÓRIO. Resposta ao /start de quem ainda não está cadastrado.
    // OBRIGATÓRIO, mesmo reescrita (é a condição para usar o banco da FIAP): os
    // quatro pontos do escopo. (1) o bot grava o texto das mensagens e os treinos
    // extraídos; (2) os dados ficam num banco de estudo da faculdade; (3) dá para
    // pedir para apagar tudo a qualquer momento, falando com o administrador;
    // (4) o acesso depende de aprovação.
    public static final String BOAS_VINDAS = String.join("\n",
            "Olá! Eu registro treinos a partir do que você me conta por mensagem.",
            "",
            "Antes de começar, saiba que:",
            "- eu gravo o texto das suas mensagens e os treinos que extraio delas;",
            "- esses dados ficam num banco de dados de estudo da faculdade;",
            "- você pode pedir para apagar tudo a qualquer momento, é só falar com o administrador;",
            "- o acesso depende de aprovação do administrador.",
            "",
            "Seu pedido foi enviado. Eu aviso aqui quando o acesso for liberado.");

    // PROVISÓRIO. Qualquer mensagem de quem está PENDENTE (inclusive um novo /start).
    public static final String AGUARDANDO_APROVACAO =
            "Seu pedido de acesso está aguardando aprovação. Eu aviso aqui quando for liberado.";

    // PROVISÓRIO. /start de quem já está ATIVO.
    public static final String JA_CADASTRADO = "Você já está cadastrado. Pode mandar seu treino.";

    // PROVISÓRIO. Vai só para o chat do administrador. %1$s = first_name,
    // %2$d = ID_USUARIO interno (nunca o from.id).
    public static final String AVISO_ADMIN = "Novo pedido de acesso: %1$s (id %2$d).\nPara aprovar: /aprovar %2$d";

    // --- /aprovar (fatia 6b; só o administrador lê) ---------------------------------------

    // PROVISÓRIO
    public static final String USO_APROVAR = "Uso: /aprovar <id>";

    // PROVISÓRIO
    public static final String ID_INEXISTENTE = "Não existe usuário com esse id.";

    // PROVISÓRIO
    public static final String JA_ATIVO = "Esse usuário já está ativo.";

    // PROVISÓRIO
    public static final String USUARIO_BLOQUEADO = "Esse usuário está bloqueado; desbloqueie pelo banco.";

    // PROVISÓRIO. %s = nome gravado em TR_USUARIO.
    public static final String APROVADO = "Aprovado: %s.";

    // PROVISÓRIO. Vai para o chat de quem foi aprovado.
    public static final String ACESSO_LIBERADO = "Seu acesso foi liberado. Pode mandar seu treino.";

    // --- /desfazer (fatia 6c) -------------------------------------------------------------

    // PROVISÓRIO. Cabeçalho da resposta. %s = data da SESSÃO da mensagem desfeita (dd/MM/yyyy).
    public static final String DESFEITO_REGISTRO_DE = "Desfeito: registro de %s";

    // PROVISÓRIO. Exercício fora do catálogo no resumo. %s = exercicio_relatado.
    public static final String DESFEITO_NAO_RECONHECIDO = "não reconhecido: \"%s\"";

    // PROVISÓRIO. Contagem de um exercício que teve uma série só.
    public static final String UMA_SERIE = "(1 série)";

    // PROVISÓRIO. Contagem de um exercício com 2 séries ou mais. %d = número de séries.
    public static final String VARIAS_SERIES = "(%d séries)";

    // PROVISÓRIO. A mensagem desfeita só tinha observação do dia.
    public static final String DESFEITO_SEM_SERIES = "(sem séries)";

    // PROVISÓRIO. Sai sempre que a sessão tiver observação: ela fica.
    public static final String OBSERVACAO_NAO_DESFEITA = "A observação do dia não é desfeita.";

    // PROVISÓRIO
    public static final String NADA_PARA_DESFAZER = "Nada para desfazer nas últimas 24 h.";

    // PROVISÓRIO. Recusa do banco ou desistência (teto de tentativas) no
    // /desfazer. Nos dois casos o service não comitou nada: o registro continua
    // como estava.
    public static final String NAO_CONSEGUI_DESFAZER =
            "Não consegui desfazer agora; seu registro continua como estava. Tente /desfazer de novo mais tarde.";

    // --- /carga (fatia 7) -----------------------------------------------------------------

    // PROVISÓRIO. /carga sem argumento.
    public static final String CARGA_AJUDA = String.join("\n",
            "Para ver as séries do último treino de um exercício, mande /carga e o nome dele.",
            "Exemplo: /carga remada unilateral máquina");

    // PROVISÓRIO. Cabeçalho da resposta. %1$s = nome oficial, %2$s = CARGA_POR_LADO
    // ou CARGA_TOTAL, %3$s = data do treino (dd/MM/yyyy).
    public static final String CARGA_ULTIMO_TREINO = "%1$s (%2$s), último treino em %3$s:";

    // PROVISÓRIO. Forma da carga no cabeçalho (TR_EXERCICIO.FORMA_CARGA = 'POR_LADO').
    public static final String CARGA_POR_LADO = "carga por lado";

    // PROVISÓRIO. Forma da carga no cabeçalho (TR_EXERCICIO.FORMA_CARGA = 'TOTAL').
    public static final String CARGA_TOTAL = "carga total";

    // PROVISÓRIO. %s = nome oficial.
    public static final String CARGA_SEM_REGISTRO = "Ainda não há registro de %s.";

    // PROVISÓRIO
    public static final String CARGA_NAO_ENCONTREI = "Não encontrei esse exercício. Confira o nome e tente de novo.";

    // PROVISÓRIO. Antes da lista de candidatos (nenhum é escolhido).
    public static final String CARGA_QUAL_DELES = "Encontrei mais de um exercício. Mande /carga com o nome de um deles:";

    // PROVISÓRIO. Depois da lista, quando os 10 listados não são todos.
    public static final String CARGA_SEJA_MAIS_ESPECIFICO = "Há outros com esse nome; seja mais específico.";

    // PROVISÓRIO. O banco falhou depois de a mensagem estar salva (ERRO).
    public static final String NAO_CONSEGUI_CONSULTAR = "Não consegui consultar agora. Tente de novo.";
}
