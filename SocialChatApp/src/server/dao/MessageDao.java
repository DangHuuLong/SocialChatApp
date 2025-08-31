package server.dao;

import common.Frame;
import java.sql.*;
import java.util.ArrayList;
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
}
