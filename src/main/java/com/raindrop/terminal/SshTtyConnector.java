package com.raindrop.terminal;

import com.techsenger.jeditermfx.core.TtyConnector;
import com.techsenger.jeditermfx.core.util.TermSize;
import net.schmizz.sshj.connection.channel.direct.Session;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.concurrent.CountDownLatch;

/**
 * Adapts an SSHJ {@link Session.Shell} to JediTermFX's {@link TtyConnector} interface.
 * Read/write are blocking; wire this connector from a virtual thread when integrating.
 */
public class SshTtyConnector implements TtyConnector {
    private final Session.Shell shell;
    private final InputStreamReader reader;
    private final OutputStream out;
    private final String name;
    private final CountDownLatch closeLatch = new CountDownLatch(1);
    private volatile boolean closed = false;
    private volatile Runnable onDisconnect;

    public SshTtyConnector(@NotNull Session.Shell shell, @NotNull Charset charset, @NotNull String name) {
        this.shell = shell;
        this.reader = new InputStreamReader(shell.getInputStream(), charset);
        this.out = shell.getOutputStream();
        this.name = name;
    }

    /** Invoked exactly once when the remote side EOFs or read throws. */
    public void setOnDisconnect(Runnable callback) {
        this.onDisconnect = callback;
    }

    @Override
    public int read(char[] buf, int offset, int length) throws IOException {
        try {
            int n = reader.read(buf, offset, length);
            if (n == -1) fireDisconnect();
            return n;
        } catch (IOException e) {
            fireDisconnect();
            throw e;
        }
    }

    private void fireDisconnect() {
        Runnable cb = this.onDisconnect;
        this.onDisconnect = null;
        if (cb != null) cb.run();
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        out.write(bytes);
        out.flush();
    }

    @Override
    public void write(String string) throws IOException {
        write(string.getBytes(reader.getEncoding() == null ? "UTF-8" : reader.getEncoding()));
    }

    @Override
    public boolean isConnected() {
        return !closed && shell.isOpen();
    }

    @Override
    public void resize(@NotNull TermSize termSize) {
        try {
            shell.changeWindowDimensions(termSize.getColumns(), termSize.getRows(), 0, 0);
        } catch (IOException ignored) {
            // If the channel is already closed the size change is a no-op.
        }
    }

    @Override
    public int waitFor() throws InterruptedException {
        closeLatch.await();
        return 0;
    }

    @Override
    public boolean ready() throws IOException {
        return reader.ready();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try { shell.close(); } catch (Exception ignored) {}
        try { reader.close(); } catch (Exception ignored) {}
        try { out.close(); } catch (Exception ignored) {}
        closeLatch.countDown();
    }
}
