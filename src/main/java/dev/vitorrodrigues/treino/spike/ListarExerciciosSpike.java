package dev.vitorrodrigues.treino.spike;

import java.sql.SQLException;
import java.util.List;

import dev.vitorrodrigues.treino.dao.ExercicioDAO;
import dev.vitorrodrigues.treino.modelo.ExercicioComApelidos;

public class ListarExerciciosSpike {

    // Argumento: o ID_USUARIO de TR_USUARIO (não o id do Telegram).
    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Uso: ListarExerciciosSpike <ID_USUARIO>");
            return;
        }

        long idUsuario;
        try {
            idUsuario = Long.parseLong(args[0].strip());
        } catch (NumberFormatException e) {
            System.err.println("O ID_USUARIO precisa ser um número inteiro.");
            return;
        }

        try {
            List<ExercicioComApelidos> exercicios = new ExercicioDAO().listarComApelidos(idUsuario);

            int totalApelidos = 0;
            for (ExercicioComApelidos exercicio : exercicios) {
                System.out.println(exercicio.linhaDoPrompt());
                totalApelidos += exercicio.apelidos().size();
            }

            System.out.println();
            System.out.println("Total de exercícios: " + exercicios.size());
            System.out.println("Total de apelidos do usuário: " + totalApelidos);

        } catch (SQLException e) {
            System.err.println("Erro no banco: " + e.getMessage());
            System.err.println("Código Oracle: " + e.getErrorCode());
            System.err.println("SQLState: " + e.getSQLState());
        }
    }
}
