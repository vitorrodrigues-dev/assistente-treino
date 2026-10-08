package dev.vitorrodrigues.treino.spike;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import dev.vitorrodrigues.treino.factory.ConnectionFactory;

public class ConexaoSpike {
    public static void main(String[] args) {
        try (Connection con = ConnectionFactory.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM TR_EXERCICIO");
             ResultSet rs = ps.executeQuery()) {

            rs.next();
            long total = rs.getLong(1);
            System.out.println("Total de exercícios: " + total);

        } catch (SQLException e) {
            System.err.println("Erro no banco: " + e.getMessage());
            System.err.println("Código Oracle: " + e.getErrorCode());
            System.err.println("SQLState: " + e.getSQLState());
        }
    }
}