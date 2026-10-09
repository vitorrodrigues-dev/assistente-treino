-- =============================================================================
-- Assistente de Treino IA - Fatia 1: catálogo de exercícios (ficha de treino)
-- Alvo: Oracle 19c (FIAP). Só sintaxe compatível com a 19c.
-- Fonte: docs/plano-treino.md (46 exercícios, ficha confirmada em 07/10/2026).
--
-- ORDEM DE EXECUÇÃO (no SQL Developer, como script: F5):
--   1. 03-plano.sql    (este arquivo)
--   2. 04-usuario.sql
--
-- Só exercícios. Os apelidos dependem do usuário e ficam no 04-usuario.sql.
-- Faixa alvo NULL/NULL em todos: a ficha não define meta (troca de treino
-- frequente; o sistema não inventa faixa).
-- Itens com observação na ficha ("a confirmar", "assumido" etc.) entram como
-- TOTAL; a observação fica num comentário acima do INSERT.
--
-- Rodar de novo não duplica: cada INSERT repetido dá ORA-00001
-- (UQ_TR_EXERCICIO_NOME) e só os que faltam entram.
-- Conferência: SELECT COUNT(*) FROM TR_EXERCICIO;  -- esperado: 46
--
-- ENCODING: este arquivo é UTF-8 (acentos e o "°" de "45°"). Confira no
-- SQL Developer: Ferramentas > Preferências > Ambiente > Codificação = UTF-8
-- ANTES de abrir o arquivo. Senão "máquina" é gravado como "mÃ¡quina".
-- =============================================================================

-- Peito
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Supino inclinado halter', 'Peito', 'POR_LADO', NULL, NULL);
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Supino inclinado Smith', 'Peito', 'TOTAL', NULL, NULL);
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Supino inclinado máquina unilateral', 'Peito', 'POR_LADO', NULL, NULL);
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Supino reto barra', 'Peito', 'TOTAL', NULL, NULL);
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Crucifixo máquina', 'Peito', 'TOTAL', NULL, NULL);
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Crossover polia alta', 'Peito', 'POR_LADO', NULL, NULL);

-- Ombro
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Elevação lateral halter', 'Ombro', 'POR_LADO', NULL, NULL);
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Elevação lateral polia', 'Ombro', 'POR_LADO', NULL, NULL);
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Desenvolvimento Smith', 'Ombro', 'TOTAL', NULL, NULL);
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Desenvolvimento halter', 'Ombro', 'POR_LADO', NULL, NULL);
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Elevação frontal halter', 'Ombro', 'POR_LADO', NULL, NULL);
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Elevação frontal anilha', 'Ombro', 'TOTAL', NULL, NULL);
-- Na ficha: "TOTAL (a confirmar)". Entra como TOTAL.
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Elevação frontal polia', 'Ombro', 'TOTAL', NULL, NULL);
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Crucifixo inverso máquina', 'Ombro', 'TOTAL', NULL, NULL);
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Posterior de ombro polia', 'Ombro', 'TOTAL', NULL, NULL);

-- Tríceps
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Tríceps francês', 'Tríceps', 'TOTAL', NULL, NULL);
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Tríceps corda', 'Tríceps', 'TOTAL', NULL, NULL);
-- Na ficha: "TOTAL (assumido: em pé, na polia)". Entra como TOTAL.
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Tríceps barra W polia', 'Tríceps', 'TOTAL', NULL, NULL);
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Tríceps testa barra', 'Tríceps', 'TOTAL', NULL, NULL);
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Tríceps testa polia', 'Tríceps', 'TOTAL', NULL, NULL);

-- Costas
-- Na ficha: "TOTAL (lastro; sem peso = 0 quando dito)". Entra como TOTAL.
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Barra fixa', 'Costas', 'TOTAL', NULL, NULL);
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Puxada aberta', 'Costas', 'TOTAL', NULL, NULL);
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Puxada romana fechada', 'Costas', 'TOTAL', NULL, NULL);
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Puxada romana média', 'Costas', 'TOTAL', NULL, NULL);
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Puxada romana aberta', 'Costas', 'TOTAL', NULL, NULL);
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Puxada triângulo', 'Costas', 'TOTAL', NULL, NULL);
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Puxada unilateral polia', 'Costas', 'POR_LADO', NULL, NULL);
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Remada cavalinho', 'Costas', 'TOTAL', NULL, NULL);
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Remada curvada barra', 'Costas', 'TOTAL', NULL, NULL);
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Remada unilateral máquina', 'Costas', 'POR_LADO', NULL, NULL);
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Pulldown barra W', 'Costas', 'TOTAL', NULL, NULL);
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Pulldown corda', 'Costas', 'TOTAL', NULL, NULL);

-- Bíceps
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Rosca inclinada halter 45°', 'Bíceps', 'POR_LADO', NULL, NULL);
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Rosca unilateral polia', 'Bíceps', 'POR_LADO', NULL, NULL);
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Rosca Scott máquina', 'Bíceps', 'TOTAL', NULL, NULL);
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Rosca Scott barra', 'Bíceps', 'TOTAL', NULL, NULL);

-- Pernas
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Agachamento livre', 'Pernas', 'TOTAL', NULL, NULL);
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Agachamento Smith', 'Pernas', 'TOTAL', NULL, NULL);
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Agachamento hack', 'Pernas', 'TOTAL', NULL, NULL);
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Stiff', 'Pernas', 'TOTAL', NULL, NULL);
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Cadeira flexora', 'Pernas', 'TOTAL', NULL, NULL);
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Mesa flexora', 'Pernas', 'TOTAL', NULL, NULL);
-- Na ficha: "TOTAL (a confirmar qual máquina)". Entra como TOTAL.
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Panturrilha', 'Pernas', 'TOTAL', NULL, NULL);
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Elevação pélvica', 'Pernas', 'TOTAL', NULL, NULL);

-- Abdômen
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Abdominal na corda', 'Abdômen', 'TOTAL', NULL, NULL);

-- Trapézio
INSERT INTO TR_EXERCICIO (NOME, GRUPAMENTO, FORMA_CARGA, REPS_MIN, REPS_MAX)
VALUES ('Encolhimento com anilhas', 'Trapézio', 'POR_LADO', NULL, NULL);


COMMIT;
