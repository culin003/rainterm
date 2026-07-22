package com.raindrop.credential;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class CredentialManagerTest {
    private CredentialManager manager;

    @BeforeEach
    public void setUp() {
        manager = new CredentialManager();
    }

    @Test
    public void testSaveAndFindById() throws Exception {
        CredentialEntry entry = new CredentialEntry("Test Credential", "password", "admin");
        entry.setPassword("encrypted-password");

        long id = manager.save(entry);
        assertTrue(id > 0);

        CredentialEntry found = manager.findById(id);
        assertNotNull(found);
        assertEquals("Test Credential", found.getName());
        assertEquals("password", found.getType());
        assertEquals("admin", found.getUsername());
    }

    @Test
    public void testFindAll() throws Exception {
        CredentialEntry e1 = new CredentialEntry("Credential 1", "password", "user1");
        CredentialEntry e2 = new CredentialEntry("Credential 2", "key", "user2");

        manager.save(e1);
        manager.save(e2);

        List<CredentialEntry> all = manager.findAll();
        assertTrue(all.size() >= 2);
    }

    @Test
    public void testUpdate() throws Exception {
        CredentialEntry entry = new CredentialEntry("Old Name", "password", "user");
        long id = manager.save(entry);
        entry.setId(id);

        entry.setName("New Name");
        manager.update(entry);

        CredentialEntry found = manager.findById(id);
        assertEquals("New Name", found.getName());
    }

    @Test
    public void testDelete() throws Exception {
        CredentialEntry entry = new CredentialEntry("To Delete", "password", "user");
        long id = manager.save(entry);

        manager.delete(id);

        CredentialEntry found = manager.findById(id);
        assertNull(found);
    }

    @Test
    public void testKeyCredential() throws Exception {
        CredentialEntry entry = new CredentialEntry("SSH Key", "key", "admin");
        entry.setKeyData("encrypted-key-data");
        entry.setKeyPath("/home/user/.ssh/id_rsa");

        long id = manager.save(entry);

        CredentialEntry found = manager.findById(id);
        assertEquals("key", found.getType());
        assertEquals("encrypted-key-data", found.getKeyData());
        assertEquals("/home/user/.ssh/id_rsa", found.getKeyPath());
    }
}
