package com.raindrop.storage;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

public class DatabaseManagerTest {

    @Test
    public void testGetConnection() throws SQLException {
        Connection conn = DatabaseManager.getConnection();
        assertNotNull(conn);
        assertFalse(conn.isClosed());
    }

    @Test
    public void testTablesExist() throws SQLException {
        Connection conn = DatabaseManager.getConnection();
        var meta = conn.getMetaData();

        // Check connection_profile table
        var rs = meta.getTables(null, null, "connection_profile", new String[]{"TABLE"});
        assertTrue(rs.next());

        // Check credential table
        rs = meta.getTables(null, null, "credential", new String[]{"TABLE"});
        assertTrue(rs.next());

        // Check app_setting table
        rs = meta.getTables(null, null, "app_setting", new String[]{"TABLE"});
        assertTrue(rs.next());
    }
}
