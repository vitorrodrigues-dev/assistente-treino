package dev.vitorrodrigues.treino.factory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.DriverManager;

public class ConnectionFactory {

    public static Connection getConnection() throws SQLException {
        String url = lerVariavel("ORACLE_URL");
        String user = lerVariavel("ORACLE_USER");
        String password = lerVariavel("ORACLE_PASSWORD");

        return DriverManager.getConnection(url, user, password);

    }

    private static String lerVariavel(String nome) {
        String valor = System.getenv(nome);
        if (valor == null || valor.isBlank()) {
            throw new IllegalStateException("Variável de ambiente " + nome + " não definida ou vazia.");
        }
        return valor;
    }
}