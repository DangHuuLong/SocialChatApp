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


    public void saveQueued(Frame f) throws SQLException {
        String sql = "INSERT INTO messages(sender, recipient, body, status) VALUES(?,?,?, 'queued')";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, f.sender);
            ps.setString(2, f.recipient);
            ps.setString(3, f.body);
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
                String body   = rs.getString("body");
                Frame f = new Frame(common.MessageType.DM, sender, recipient, body);
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
    
 // Thêm: lưu tin đã gửi (khi người nhận đang online)
    public void saveSent(Frame f) throws SQLException {
        String sql = "INSERT INTO messages(sender, recipient, body, status) VALUES(?,?,?, 'delivered')";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, f.sender);
            ps.setString(2, f.recipient);
            ps.setString(3, f.body);
            ps.executeUpdate();
        }
    }

    // Row lịch sử
    public static class HistoryRow {
        public final String sender, recipient, body;
        public final Timestamp createdAt;
        public HistoryRow(String s, String r, String b, Timestamp t) {
            this.sender = s; this.recipient = r; this.body = b; this.createdAt = t;
        }
    }

    // Lấy hội thoại 2 chiều giữa a và b (mới nhất trước, rồi đảo lại cho đúng thứ tự)
    public List<HistoryRow> loadConversation(String a, String b, int limit) throws SQLException {
        String sql = """
            SELECT sender, recipient, body, created_at
            FROM messages
            WHERE (sender=? AND recipient=?) OR (sender=? AND recipient=?)
            ORDER BY id DESC
            LIMIT ?
        """;
        List<HistoryRow> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, a); ps.setString(2, b);
            ps.setString(3, b); ps.setString(4, a);
            ps.setInt(5, Math.max(1, limit));
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                out.add(new HistoryRow(
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
 // Trong server.dao.MessageDao
    public void saveRoomMessage(long roomId, String sender, String text) throws SQLException {
        // ví dụ: insert vào bảng messages với room_id, recipient = NULL
        try (var ps = conn.prepareStatement(
                "INSERT INTO messages(room_id, sender, recipient, body, created_at) VALUES(?, ?, NULL, ?, NOW())")) {
            ps.setLong(1, roomId);
            ps.setString(2, sender);
            ps.setString(3, text);
            ps.executeUpdate();
        }
    }

}