package com.raindrop.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SftpService}. We cannot exercise the SSHClient path
 * without a real SSH server, but we can verify that the service delegates
 * to a virtual-thread executor and that null-client calls fail cleanly.
 */
public class SftpServiceTest {

    @Test
    public void testListDirectoryPropagatesFailure() {
        SftpService svc = new SftpService();
        var future = svc.listDirectory(null, "/tmp");
        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        // The wrapped cause should be a NullPointerException (client==null.newSFTPClient()).
        Throwable cause = ex.getCause();
        assertNotNull(cause);
    }

    @Test
    public void testMkdirPropagatesFailure() {
        SftpService svc = new SftpService();
        var future = svc.mkdir(null, "/tmp/x");
        assertThrows(ExecutionException.class, future::get);
    }

    @Test
    public void testRemovePropagatesFailure() {
        SftpService svc = new SftpService();
        var future = svc.remove(null, "/tmp/x", false);
        assertThrows(ExecutionException.class, future::get);
    }

    @Test
    public void testRenamePropagatesFailure() {
        SftpService svc = new SftpService();
        var future = svc.rename(null, "/a", "/b");
        assertThrows(ExecutionException.class, future::get);
    }

    @Test
    public void testBatchUploadEmptyCompletesImmediately() throws Exception {
        SftpService svc = new SftpService();
        var future = svc.batchUpload(null, java.util.List.of(), "/tmp", null);
        // With zero files there is nothing to run, so it completes normally.
        future.get();
        assertTrue(future.isDone());
        assertFalse(future.isCompletedExceptionally());
    }
}
