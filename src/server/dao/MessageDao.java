package server.dao;

import common.Frame;
import common.MessageType;

import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MessageDao {
    private final Connection conn;

    public MessageDao(Connection conn) {
        this.conn = conn;
    }

    public void saveQueued(Frame f) throws SQLException {
        String sql = "INSERT INTO messages(sender, recipient, body, status) VALUES(?,?,?, 'queued')";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, f.sender);
            ps.setString(2, f.recipient);
            ps.setString(3, f.body);
            ps.executeUpdate();
        }
    }

    public void saveSent(Frame f) throws SQLException {
        String sql = "INSERT INTO messages(sender, recipient, body, status) VALUES(?,?,?, 'delivered')";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, f.sender);
            ps.setString(2, f.recipient);
            ps.setString(3, f.body);
            ps.executeUpdate();
        }
    }

    public void saveFileEvent(Frame f) throws SQLException {
        String sql = "INSERT INTO messages(sender, recipient, body, status, type) VALUES(?,?,?, 'delivered', ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, f.sender);
            ps.setString(2, f.recipient);
            ps.setString(3, f.body);
            ps.setString(4, f.type == MessageType.FILE_EVT ? "file" : "audio");
            ps.executeUpdate();
        }
    }

    public List<Frame> loadQueued(String recipient) throws SQLException {
        String sql = "SELECT id, sender, body FROM messages WHERE recipient=? AND status='queued' ORDER BY id";
        List<Frame> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, recipient);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String sender = rs.getString("sender");
                String body = rs.getString("body");
                Frame f = new Frame(MessageType.DM, sender, recipient, body);
                out.add(f);
                markDelivered(rs.getLong("id"));
            }
        }
        return out;
    }

    private void markDelivered(long id) throws SQLException {
        String sql = "UPDATE messages SET status='delivered', delivered_at=NOW() WHERE id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    public static class HistoryRow {
        public final String sender, recipient, body, type;
        public final Timestamp createdAt;
        public HistoryRow(String s, String r, String b, String t, Timestamp c) {
            this.sender = s;
            this.recipient = r;
            this.body = b;
            this.type = (t != null) ? t : "text"; // Mặc định là "text" nếu type null
            this.createdAt = c;
        }
    }

    public List<HistoryRow> loadConversation(String a, String b, int limit) throws SQLException {
        String sql = """
            SELECT sender, recipient, body, COALESCE(type, 'text') AS type, created_at
            FROM messages
            WHERE (sender=? AND recipient=?) OR (sender=? AND recipient=?)
            ORDER BY id DESC
            LIMIT ?
        """;
        List<HistoryRow> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, a);
            ps.setString(2, b);
            ps.setString(3, b);
            ps.setString(4, a);
            ps.setInt(5, Math.max(1, limit));
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                out.add(new HistoryRow(
                    rs.getString("sender"),
                    rs.getString("recipient"),
                    rs.getString("body"),
                    rs.getString("type"), // Sử dụng type đã được COALESCE
                    rs.getTimestamp("created_at")
                ));
            }
        }
        Collections.reverse(out);
        return out;
    }
}