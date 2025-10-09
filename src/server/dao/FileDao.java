package server.dao;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Data Access Object for handling file & audio metadata in the 'files' table.
 * Linked to the messages table by message_id.
 */
public class FileDao {
    private final Connection conn;

    public FileDao(Connection conn) {
        this.conn = conn;
    }

    /* =====================================================
       =============== INSERT OPERATIONS ====================
       ===================================================== */

    /** Save normal file metadata */
    public long saveFile(long messageId, String fileName, String filePath,
                         String mimeType, long fileSize) throws SQLException {
        String sql = """
            INSERT INTO files (message_id, file_name, file_path, mime_type, file_size, file_type)
            VALUES (?, ?, ?, ?, ?, 'file')
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, messageId);
            ps.setString(2, fileName);
            ps.setString(3, filePath);
            ps.setString(4, mimeType);
            ps.setLong(5, fileSize);
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return 0L;
    }

    /** Save audio metadata (duration, type=audio) */
    public void saveAudio(long msgId, String fileName, String filePath,
                          String mime, long bytes, int durationSec) throws SQLException {
        String sql = """
            INSERT INTO files (message_id, file_name, file_path, mime_type, file_size, duration_sec, file_type)
            VALUES (?, ?, ?, ?, ?, ?, 'audio')
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, msgId);
            ps.setString(2, fileName);
            ps.setString(3, filePath);
            ps.setString(4, mime);
            ps.setLong(5, bytes);
            ps.setInt(6, durationSec);
            ps.executeUpdate();
        }
    }

    /* =====================================================
       =============== SELECT OPERATIONS ====================
       ===================================================== */

    /** Get file metadata by message_id */
    public FileRecord getFileByMessageId(long messageId) throws SQLException {
        String sql = "SELECT * FROM files WHERE message_id = ? AND file_type = 'file' LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        }
        return null;
    }

    /** Get audio metadata by message_id */
    public FileRecord getAudioByMessageId(long messageId) throws SQLException {
        String sql = "SELECT * FROM files WHERE message_id = ? AND file_type = 'audio' LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        }
        return null;
    }

    /** List normal file history for a user (paginated) */
    public List<FileRecord> listFilesByUserPaged(String username, int limit, int offset) throws SQLException {
        String sql = """
            SELECT f.*
            FROM files f
            JOIN messages m ON f.message_id = m.id
            WHERE f.file_type = 'file' AND (m.sender = ? OR m.recipient = ?)
            ORDER BY f.uploaded_at DESC
            LIMIT ? OFFSET ?
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, username);
            ps.setInt(3, Math.max(1, limit));
            ps.setInt(4, Math.max(0, offset));
            try (ResultSet rs = ps.executeQuery()) {
                List<FileRecord> list = new ArrayList<>();
                while (rs.next()) list.add(mapRow(rs));
                return list;
            }
        }
    }

    /** List audio history for a user (paginated) */
    public List<FileRecord> listAudiosByUserPaged(String username, int limit, int offset) throws SQLException {
        String sql = """
            SELECT f.*
            FROM files f
            JOIN messages m ON f.message_id = m.id
            WHERE f.file_type = 'audio' AND (m.sender = ? OR m.recipient = ?)
            ORDER BY f.uploaded_at DESC
            LIMIT ? OFFSET ?
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, username);
            ps.setInt(3, Math.max(1, limit));
            ps.setInt(4, Math.max(0, offset));
            try (ResultSet rs = ps.executeQuery()) {
                List<FileRecord> list = new ArrayList<>();
                while (rs.next()) list.add(mapRow(rs));
                return list;
            }
        }
    }

    /* =====================================================
       =============== DELETE OPERATIONS ====================
       ===================================================== */

    /** Delete normal file by message_id */
    public boolean deleteFileByMessageId(long messageId) throws SQLException {
        String sql = "DELETE FROM files WHERE message_id = ? AND file_type = 'file'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, messageId);
            return ps.executeUpdate() > 0;
        }
    }

    /** Delete audio file by message_id */
    public boolean deleteAudioByMessageId(long messageId) throws SQLException {
        String sql = "DELETE FROM files WHERE message_id = ? AND file_type = 'audio'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, messageId);
            return ps.executeUpdate() > 0;
        }
    }

    /* =====================================================
       =============== INTERNAL UTILITIES ===================
       ===================================================== */

    /** Convert SQL row → FileRecord */
    private FileRecord mapRow(ResultSet rs) throws SQLException {
        FileRecord r = new FileRecord();
        r.id = rs.getLong("id");
        r.messageId = rs.getLong("message_id");
        r.fileName = rs.getString("file_name");
        r.filePath = rs.getString("file_path");
        r.mimeType = rs.getString("mime_type");
        r.fileSize = rs.getLong("file_size");
        r.uploadedAt = rs.getTimestamp("uploaded_at");
        int dur = rs.getInt("duration_sec");
        r.durationSec = rs.wasNull() ? null : dur;
        r.fileType = rs.getString("file_type");
        return r;
    }

    /* =====================================================
       =============== MODEL CLASS ==========================
       ===================================================== */

    public static class FileRecord {
        public long id;
        public long messageId;
        public String fileName;
        public String filePath;
        public String mimeType;
        public long fileSize;
        public Timestamp uploadedAt;
        public Integer durationSec;  // for audio
        public String fileType;      // 'file' or 'audio'

        @Override
        public String toString() {
            return String.format(
                "FileRecord{id=%d, msgId=%d, name='%s', type='%s', size=%dB, dur=%s, path='%s'}",
                id, messageId, fileName, fileType, fileSize,
                (durationSec != null ? durationSec + "s" : "n/a"), filePath
            );
        }
    }
}
