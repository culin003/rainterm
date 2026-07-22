package com.raindrop.storage;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class ProfileRepository {

    public List<ConnectionProfile> findAll() throws SQLException {
        List<ConnectionProfile> list = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM connection_profile ORDER BY group_name, name");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        }
        return list;
    }

    public ConnectionProfile findById(long id) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM connection_profile WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRow(rs);
                }
            }
        }
        return null;
    }

    public long save(ConnectionProfile profile) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO connection_profile (name, host, port, auth_type, credential_id, username, password, key_path, key_pass, group_name, encoding, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, datetime('now'), datetime('now'))",
                 Statement.RETURN_GENERATED_KEYS)) {
            setParams(ps, profile);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        return -1;
    }

    public void update(ConnectionProfile profile) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE connection_profile SET name=?, host=?, port=?, auth_type=?, credential_id=?, username=?, password=?, key_path=?, key_pass=?, group_name=?, encoding=?, updated_at=datetime('now') WHERE id=?")) {
            setParams(ps, profile);
            ps.setLong(12, profile.getId());
            ps.executeUpdate();
        }
    }

    public void delete(long id) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM connection_profile WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    private void setParams(PreparedStatement ps, ConnectionProfile p) throws SQLException {
        ps.setString(1, p.getName());
        ps.setString(2, p.getHost());
        ps.setInt(3, p.getPort());
        ps.setString(4, p.getAuthType());
        if (p.getCredentialId() != null) {
            ps.setLong(5, p.getCredentialId());
        } else {
            ps.setNull(5, Types.INTEGER);
        }
        ps.setString(6, p.getUsername());
        ps.setString(7, p.getPassword());
        ps.setString(8, p.getKeyPath());
        ps.setString(9, p.getKeyPass());
        ps.setString(10, p.getGroupName());
        ps.setString(11, p.getEncoding());
    }

    private ConnectionProfile mapRow(ResultSet rs) throws SQLException {
        ConnectionProfile p = new ConnectionProfile();
        p.setId(rs.getLong("id"));
        p.setName(rs.getString("name"));
        p.setHost(rs.getString("host"));
        p.setPort(rs.getInt("port"));
        p.setAuthType(rs.getString("auth_type"));
        long credId = rs.getLong("credential_id");
        if (!rs.wasNull()) {
            p.setCredentialId(credId);
        }
        p.setUsername(rs.getString("username"));
        p.setPassword(rs.getString("password"));
        p.setKeyPath(rs.getString("key_path"));
        p.setKeyPass(rs.getString("key_pass"));
        p.setGroupName(rs.getString("group_name"));
        p.setEncoding(rs.getString("encoding"));
        p.setCreatedAt(rs.getString("created_at"));
        p.setUpdatedAt(rs.getString("updated_at"));
        return p;
    }
}
