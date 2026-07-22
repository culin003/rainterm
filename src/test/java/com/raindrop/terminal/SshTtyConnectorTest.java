package com.raindrop.terminal;

import net.schmizz.sshj.connection.channel.direct.Session;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SshTtyConnector wraps a Session.Shell whose only interfaces we touch are
 * getInputStream / getOutputStream / isOpen / changeWindowDimensions / close.
 * We construct the connector via reflection on a bare instance so we can
 * inject a fake stream pair without a live SSH channel.
 */
public class SshTtyConnectorTest {

    /**
     * Build a connector whose reader/writer are backed by the given streams.
     * The Session.Shell reference is left null — we never call methods that
     * dereference it in these tests.
     */
    private static SshTtyConnector newConnector(InputStream in, OutputStream out) throws Exception {
        Constructor<SshTtyConnector> ctor = SshTtyConnector.class.getDeclaredConstructors().length > 0
            ? (Constructor<SshTtyConnector>) SshTtyConnector.class.getDeclaredConstructors()[0]
            : null;
        assertNotNull(ctor);
        // Instead of calling the public ctor (which touches shell.getInputStream), allocate
        // and patch fields directly. Uses sun.misc.Unsafe via reflection would be overkill;
        // this is easier via Objenesis-like allocateInstance.
        sun.misc.Unsafe unsafe = getUnsafe();
        SshTtyConnector inst = (SshTtyConnector) unsafe.allocateInstance(SshTtyConnector.class);
        setField(inst, "reader", new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        setField(inst, "out", out);
        setField(inst, "name", "test");
        setField(inst, "closeLatch", new java.util.concurrent.CountDownLatch(1));
        return inst;
    }

    private static sun.misc.Unsafe getUnsafe() throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (sun.misc.Unsafe) f.get(null);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    public void testWriteBytesFlushesToUnderlyingStream() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SshTtyConnector c = newConnector(new ByteArrayInputStream(new byte[0]), out);
        c.write("hello\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("hello\n", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    public void testWriteStringUsesConfiguredCharset() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SshTtyConnector c = newConnector(new ByteArrayInputStream(new byte[0]), out);
        c.write("你好");
        // The connector was built with UTF-8, so this must round-trip.
        assertEquals("你好", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    public void testReadReturnsBytesFromInputStream() throws Exception {
        SshTtyConnector c = newConnector(
            new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)),
            new ByteArrayOutputStream());
        char[] buf = new char[8];
        int n = c.read(buf, 0, buf.length);
        assertEquals(3, n);
        assertEquals("abc", new String(buf, 0, 3));
    }

    @Test
    public void testReadEofFiresDisconnectCallback() throws Exception {
        SshTtyConnector c = newConnector(
            new ByteArrayInputStream(new byte[0]),
            new ByteArrayOutputStream());
        boolean[] fired = {false};
        c.setOnDisconnect(() -> fired[0] = true);
        char[] buf = new char[4];
        int n = c.read(buf, 0, buf.length);
        assertEquals(-1, n);
        assertTrue(fired[0], "onDisconnect must fire on EOF");
    }

    @Test
    public void testGetNameReturnsConfiguredName() throws Exception {
        SshTtyConnector c = newConnector(
            new ByteArrayInputStream(new byte[0]),
            new ByteArrayOutputStream());
        assertEquals("test", c.getName());
    }
}
