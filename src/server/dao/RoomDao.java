package server.dao;

import java.sql.*;
import java.util.*;

public class RoomDao {
    private final Connection conn;
    public RoomDao(Connection conn){ this.conn = conn; }

    /** Nếu đã tồn tại tên phòng, trả về ID; nếu chưa, tạo mới và trả ID */
    public long ensureRoom(String name, String owner) throws SQLException {
        Long id = findRoomIdByName(name);
        if (id != null) return id;
        try (var ps = conn.prepareStatement(
                "INSERT INTO rooms(name, owner) VALUES(?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, owner);
            ps.executeUpdate();
            try (var rs = ps.getGeneratedKeys()) { rs.next(); return rs.getLong(1); }
        }
    }

    /** Tạo phòng MỚI kèm danh sách thành viên ban đầu; yêu cầu >= 3 người duy nhất (owner + A + thêm ≥1) */
    public long createRoomWithMembers(String name, String owner, Collection<String> members) throws SQLException {
        LinkedHashSet<String> unique = new LinkedHashSet<>(members);
        unique.add(owner);
        if (unique.size() < 3) throw new IllegalArgumentException("ROOM_NEEDS_3");

        boolean oldAuto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            Long existed = findRoomIdByName(name);
            if (existed != null) throw new SQLException("ROOM_EXISTS");

            long roomId;
            try (var ps = conn.prepareStatement(
                    "INSERT INTO rooms(name, owner) VALUES(?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name);
                ps.setString(2, owner);
                ps.executeUpdate();
                try (var rs = ps.getGeneratedKeys()) { rs.next(); roomId = rs.getLong(1); }
            }

            try (var ps = conn.prepareStatement(
                    "INSERT IGNORE INTO room_members(room_id, user) VALUES(?, ?)")) {
                for (String u : unique) {
                    ps.setLong(1, roomId);
                    ps.setString(2, u);
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            conn.commit();
            return roomId;
        } catch (Exception e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(oldAuto);
        }
    }

    public Long findRoomIdByName(String name) throws SQLException {
        try (var ps = conn.prepareStatement("SELECT id FROM rooms WHERE name=?")) {
            ps.setString(1, name);
            try (var rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : null; }
        }
    }

    public boolean isOwner(long roomId, String user) throws SQLException {
        try (var ps = conn.prepareStatement("SELECT 1 FROM rooms WHERE id=? AND owner=?")) {
            ps.setLong(1, roomId); ps.setString(2, user);
            try (var rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    public void addMember(long roomId, String user) throws SQLException {
        try (var ps = conn.prepareStatement(
                "INSERT IGNORE INTO room_members(room_id, user) VALUES(?, ?)")) {
            ps.setLong(1, roomId); ps.setString(2, user); ps.executeUpdate();
        }
    }

    public void removeMember(long roomId, String user) throws SQLException {
        try (var ps = conn.prepareStatement(
                "DELETE FROM room_members WHERE room_id=? AND user=?")) {
            ps.setLong(1, roomId); ps.setString(2, user); ps.executeUpdate();
        }
    }

    public boolean isMember(long roomId, String user) throws SQLException {
        try (var ps = conn.prepareStatement(
                "SELECT 1 FROM room_members WHERE room_id=? AND user=?")) {
            ps.setLong(1, roomId); ps.setString(2, user);
            try (var rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    public List<String> listMembers(long roomId) throws SQLException {
        try (var ps = conn.prepareStatement(
                "SELECT user FROM room_members WHERE room_id=? ORDER BY user")) {
            ps.setLong(1, roomId);
            try (var rs = ps.executeQuery()) {
                var out = new ArrayList<String>();
                while (rs.next()) out.add(rs.getString(1));
                return out;
            }
        }
    }
    public List<Long> listRoomIdsOfUser(String user) throws SQLException {
        try (var ps = conn.prepareStatement(
            "SELECT room_id FROM room_members WHERE user=?")) {
            ps.setString(1, user);
            try (var rs = ps.executeQuery()) {
                var out = new ArrayList<Long>();
                while (rs.next()) out.add(rs.getLong(1));
                return out;
            }
        }
    }

}
