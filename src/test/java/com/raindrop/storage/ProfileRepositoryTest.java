package com.raindrop.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ProfileRepositoryTest {
    private ProfileRepository repository;

    @BeforeEach
    public void setUp() {
        repository = new ProfileRepository();
    }

    @Test
    public void testSaveAndFindById() throws Exception {
        ConnectionProfile profile = new ConnectionProfile("Test Server", "192.168.1.100", 22, "admin");
        profile.setPassword("encrypted-password");

        long id = repository.save(profile);
        assertTrue(id > 0);

        ConnectionProfile found = repository.findById(id);
        assertNotNull(found);
        assertEquals("Test Server", found.getName());
        assertEquals("192.168.1.100", found.getHost());
        assertEquals(22, found.getPort());
        assertEquals("admin", found.getUsername());
    }

    @Test
    public void testFindAll() throws Exception {
        ConnectionProfile p1 = new ConnectionProfile("Server 1", "10.0.0.1", 22, "user1");
        ConnectionProfile p2 = new ConnectionProfile("Server 2", "10.0.0.2", 22, "user2");

        repository.save(p1);
        repository.save(p2);

        List<ConnectionProfile> all = repository.findAll();
        assertTrue(all.size() >= 2);
    }

    @Test
    public void testUpdate() throws Exception {
        ConnectionProfile profile = new ConnectionProfile("Old Name", "10.0.0.1", 22, "user");
        long id = repository.save(profile);
        profile.setId(id);

        profile.setName("New Name");
        repository.update(profile);

        ConnectionProfile found = repository.findById(id);
        assertEquals("New Name", found.getName());
    }

    @Test
    public void testDelete() throws Exception {
        ConnectionProfile profile = new ConnectionProfile("To Delete", "10.0.0.1", 22, "user");
        long id = repository.save(profile);

        repository.delete(id);

        ConnectionProfile found = repository.findById(id);
        assertNull(found);
    }
}
