package server.dao;

import common.Frame;
import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MessageDao {
    private final Connection conn;

    public MessageDao(Connection conn) {
        this.conn = conn;
    }

    public long saveQueuedReturnId(Frame f) throws SQLException {
        String sql = "INSERT INTO messages(sender, recipient, body, status) VALUES(?,?,?, 'queued')";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, f.sender);
            ps.setString(2, f.recipient);
            ps.setString(3, f.body);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return 0L;
    }

    public long saveSentReturnId(Frame f) throws SQLException {
        String sql = "INSERT INTO messages(sender, recipient, body, status) VALUES(?,?,?, 'delivered')";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, f.sender);
            ps.setString(2, f.recipient);
            ps.setString(3, f.body);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return 0L;
    }

    public void saveQueued(Frame f) throws SQLException {
        saveQueuedReturnId(f);
    }

    public void saveSent(Frame f) throws SQLException {
        saveSentReturnId(f);
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
                Frame f = new Frame(common.MessageType.DM, sender, recipient, body);
                f.transferId = String.valueOf(rs.getLong("id"));
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
        public final long id;
        public final String sender, recipient, body;
        public final Timestamp createdAt;
        public HistoryRow(long id, String s, String r, String b, Timestamp c) {
            this.id = id;
            this.sender = s;
            this.recipient = r;
            this.body = b;
            this.createdAt = c;
        }
    }

    public List<HistoryRow> loadConversation(String a, String b, int limit) throws SQLException {
        String sql = """
            SELECT id, sender, recipient, body, created_at
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
                    rs.getLong("id"),
                    rs.getString("sender"),
                    rs.getString("recipient"),
                    rs.getString("body"),
                    rs.getTimestamp("created_at")
                ));
            }
        }
        Collections.reverse(out);
        return out;
    }

    public boolean deleteById(long id, String requester) throws SQLException {
        String checkSql = "SELECT sender FROM messages WHERE id=?";
        String sender = null;
        try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
            ps.setLong(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) sender = rs.getString("sender");
        }
        if (sender == null || !sender.equals(requester)) return false;

        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM messages WHERE id=?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    public String deleteByIdReturningPeer(long id, String requester) throws SQLException {
        String sqlSel = "SELECT sender, recipient FROM messages WHERE id=?";
        String sender = null, recipient = null;
        try (PreparedStatement ps = conn.prepareStatement(sqlSel)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    sender = rs.getString("sender");
                    recipient = rs.getString("recipient");
                }
            }
        }
        if (sender == null || !sender.equals(requester)) return null;

        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM messages WHERE id=?")) {
            ps.setLong(1, id);
            int n = ps.executeUpdate();
            return (n > 0) ? recipient : null;
        }
    }
    
    public String updateByIdReturningPeer(long id, String requester, String newBody) throws SQLException {
        String sel = "SELECT sender, recipient FROM messages WHERE id=?";
        String sender = null, recipient = null;
        try (PreparedStatement ps = conn.prepareStatement(sel)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    sender = rs.getString("sender");
                    recipient = rs.getString("recipient");
                }
            }
        }
        if (sender == null || !sender.equals(requester)) return null;

        String upd = "UPDATE messages SET body=?, updated_at=NOW() WHERE id=?";
        try (PreparedStatement ps = conn.prepareStatement(upd)) {
            ps.setString(1, newBody);
            ps.setLong(2, id);
            int n = ps.executeUpdate();
            return (n > 0) ? recipient : null;
        }
    }
}
