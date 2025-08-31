package server.dao;

import java.sql.*;
import java.time.Instant;
import java.util.*;

import org.mindrot.jbcrypt.BCrypt;
import client.model.*;

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
    
    public static List<User> listOthers(int excludeUserId) throws SQLException {
        String sql = "SELECT id, username FROM users WHERE id <> ? ORDER BY username";
        try (Connection c = DBConnection.get();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, excludeUserId);
            try (ResultSet rs = ps.executeQuery()) {
                List<User> list = new ArrayList<>();
                while (rs.next()) {
                    User u = new User();
                    u.setId(rs.getInt("id"));
                    u.setUsername(rs.getString("username"));
                    list.add(u);
                }
                return list;
            }
        }
    }
    
    public static User findByUsername(String username) throws SQLException {
        String sql = "SELECT id, username, password FROM users WHERE username = ?";
        try (Connection c = DBConnection.get();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    User u = new User();
                    u.setId(rs.getInt("id"));
                    u.setUsername(rs.getString("username"));
                    u.setPassword(rs.getString("password"));
                    return u;
                }
                return null;
            }
        }
    }
    
    public static void setOnline(int userId, boolean online) throws SQLException {
        String sql = "UPDATE users SET online=?, last_seen=? WHERE id=?";
        try (Connection c = DBConnection.get();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, online ? 1 : 0);
            ps.setString(2, online ? null : Instant.now().toString());
            ps.setInt(3, userId);
            ps.executeUpdate();
        }
    }

    public static Map<Integer, Presence> getPresenceOfAll() throws SQLException {
        String sql = "SELECT id, online, last_seen FROM users";
        Map<Integer, Presence> map = new HashMap<>();
        try (Connection c = DBConnection.get();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                map.put(rs.getInt("id"),
                        new Presence(rs.getInt("online") == 1, rs.getString("last_seen")));
            }
        }
        return map;
    }

    public static class Presence {
        public final boolean online;
        public final String lastSeenIso; // có thể null
        public Presence(boolean o, String t) { online=o; lastSeenIso=t; }
    }

}
