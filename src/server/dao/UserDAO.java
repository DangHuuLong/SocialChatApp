package server.dao;

import java.sql.*;
import org.mindrot.jbcrypt.BCrypt;

public class UserDAO {

    public boolean usernameExists(String username) throws SQLException {
        String sql = "SELECT 1 FROM users WHERE username = ?";
        try (Connection c = DBConnection.get();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Đăng ký: hash mật khẩu rồi lưu */
    public boolean register(String username, String plainPassword) throws SQLException {
        if (usernameExists(username)) return false;
        String hashed = BCrypt.hashpw(plainPassword, BCrypt.gensalt(12)); // cost=12
        String sql = "INSERT INTO users(username, password) VALUES(?, ?)";
        try (Connection c = DBConnection.get();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, hashed);
            return ps.executeUpdate() == 1;
        }
    }

    /** Đăng nhập: lấy hash trong DB rồi check */
    public boolean login(String username, String plainPassword) throws SQLException {
        String sql = "SELECT password FROM users WHERE username = ?";
        try (Connection c = DBConnection.get();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                String storedHash = rs.getString(1);
                return BCrypt.checkpw(plainPassword, storedHash);
            }
        }
    }
}
