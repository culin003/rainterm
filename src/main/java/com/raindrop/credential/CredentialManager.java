package com.raindrop.credential;

import com.raindrop.storage.DatabaseManager;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class CredentialManager {

    public List<CredentialEntry> findAll() throws SQLException {
        List<CredentialEntry> list = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM credential ORDER BY name");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        }
        return list;
    }

    public CredentialEntry findById(long id) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM credential WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRow(rs);
                }
            }
        }
        return null;
    }

    public long save(CredentialEntry entry) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO credential (name, type, username, password, key_data, key_path, key_pass, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, datetime('now'), datetime('now'))",
                 Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, entry.getName());
            ps.setString(2, entry.getType());
            ps.setString(3, entry.getUsername());
            ps.setString(4, entry.getPassword());
            ps.setString(5, entry.getKeyData());
            ps.setString(6, entry.getKeyPath());
            ps.setString(7, entry.getKeyPass());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        return -1;
    }

    public void update(CredentialEntry entry) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE credential SET name=?, type=?, username=?, password=?, key_data=?, key_path=?, key_pass=?, updated_at=datetime('now') WHERE id=?")) {
            ps.setString(1, entry.getName());
            ps.setString(2, entry.getType());
            ps.setString(3, entry.getUsername());
            ps.setString(4, entry.getPassword());
            ps.setString(5, entry.getKeyData());
            ps.setString(6, entry.getKeyPath());
            ps.setString(7, entry.getKeyPass());
            ps.setLong(8, entry.getId());
            ps.executeUpdate();
        }
    }

    public void delete(long id) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM credential WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    private CredentialEntry mapRow(ResultSet rs) throws SQLException {
        CredentialEntry e = new CredentialEntry();
        e.setId(rs.getLong("id"));
        e.setName(rs.getString("name"));
        e.setType(rs.getString("type"));
        e.setUsername(rs.getString("username"));
        e.setPassword(rs.getString("password"));
        e.setKeyData(rs.getString("key_data"));
        e.setKeyPath(rs.getString("key_path"));
        e.setKeyPass(rs.getString("key_pass"));
        e.setCreatedAt(rs.getString("created_at"));
        e.setUpdatedAt(rs.getString("updated_at"));
        return e;
    }
}
