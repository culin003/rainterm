package com.raindrop.core;

import net.schmizz.sshj.common.StreamCopier;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.sftp.SFTPFileTransfer;
import net.schmizz.sshj.xfer.FileSystemFile;
import net.schmizz.sshj.xfer.TransferListener;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * SFTP operations backed by a virtual-thread executor.
 *
 * <p>Every public method returns a {@link CompletableFuture} scheduled on
 * {@link TaskExecutor#getExecutor()}. Callers pass an {@link SshSession},
 * NOT a raw {@code SSHClient}: the session caches a single reusable
 * {@link SFTPClient} instance for its lifetime, which avoids opening a
 * fresh SFTP subsystem channel on every call (that channel-open is what
 * makes a naive file-browser feel sluggish).
 */
public class SftpService {

    /** Progress callback for a single file (running on the virtual thread, dispatch to FX yourself). */
    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(String fileName, long transferred, long total);
    }

    public CompletableFuture<List<RemoteResourceInfo>> listDirectory(SshSession session, String path) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return session.getSftpClient().ls(path);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, TaskExecutor.getExecutor());
    }

    public CompletableFuture<Void> mkdir(SshSession session, String remotePath) {
        return CompletableFuture.runAsync(() -> {
            try {
                session.getSftpClient().mkdir(remotePath);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, TaskExecutor.getExecutor());
    }

    public CompletableFuture<Void> remove(SshSession session, String remotePath, boolean directory) {
        return CompletableFuture.runAsync(() -> {
            try {
                SFTPClient sftp = session.getSftpClient();
                if (directory) sftp.rmdir(remotePath); else sftp.rm(remotePath);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, TaskExecutor.getExecutor());
    }

    public CompletableFuture<Void> rename(SshSession session, String oldPath, String newPath) {
        return CompletableFuture.runAsync(() -> {
            try {
                session.getSftpClient().rename(oldPath, newPath);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, TaskExecutor.getExecutor());
    }

    /** Upload a single file with progress reporting. */
    public CompletableFuture<Void> upload(SshSession session, File local, String remotePath, ProgressCallback progress) {
        return CompletableFuture.runAsync(() -> {
            try {
                SFTPFileTransfer ft = session.getSftpClient().getFileTransfer();
                if (progress != null) ft.setTransferListener(new ProgressTransferListener("", progress));
                ft.upload(new FileSystemFile(local), remotePath);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, TaskExecutor.getExecutor());
    }

    /** Download a single file with progress reporting. */
    public CompletableFuture<Void> download(SshSession session, String remotePath, File local, ProgressCallback progress) {
        return CompletableFuture.runAsync(() -> {
            try {
                SFTPFileTransfer ft = session.getSftpClient().getFileTransfer();
                if (progress != null) ft.setTransferListener(new ProgressTransferListener("", progress));
                ft.download(remotePath, new FileSystemFile(local));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, TaskExecutor.getExecutor());
    }

    /** Backwards-compatible convenience overloads without progress. */
    public CompletableFuture<Void> upload(SshSession session, File local, String remotePath) {
        return upload(session, local, remotePath, null);
    }

    public CompletableFuture<Void> download(SshSession session, String remotePath, File local) {
        return download(session, remotePath, local, null);
    }

    /**
     * Batch upload: each file is transferred in its own virtual thread.
     * The returned future completes when all files finish (or the first fails).
     */
    public CompletableFuture<Void> batchUpload(SshSession session, List<File> files, String remoteDir, ProgressCallback progress) {
        List<CompletableFuture<Void>> futures = new ArrayList<>(files.size());
        for (File f : files) {
            String remote = remoteDir.endsWith("/") ? remoteDir + f.getName() : remoteDir + "/" + f.getName();
            futures.add(upload(session, f, remote, progress));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    /**
     * Batch download: each file is transferred in its own virtual thread.
     */
    public CompletableFuture<Void> batchDownload(SshSession session, List<String> remoteFiles, File localDir, ProgressCallback progress) {
        List<CompletableFuture<Void>> futures = new ArrayList<>(remoteFiles.size());
        for (String remote : remoteFiles) {
            String name = remote.substring(remote.lastIndexOf('/') + 1);
            File out = new File(localDir, name);
            futures.add(download(session, remote, out, progress));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    /**
     * Adapter converting SSHJ's hierarchical TransferListener into a single {@link ProgressCallback}.
     * SSHJ's contract: {@code file(name, size)} is called at the start of each file transfer and
     * returns a {@link StreamCopier.Listener} that receives cumulative-bytes-transferred callbacks.
     */
    private static final class ProgressTransferListener implements TransferListener {
        private final String parentPath;
        private final ProgressCallback callback;

        ProgressTransferListener(String parentPath, ProgressCallback callback) {
            this.parentPath = parentPath;
            this.callback = callback;
        }

        @Override
        public TransferListener directory(String name) {
            String path = parentPath.isEmpty() ? name : parentPath + "/" + name;
            return new ProgressTransferListener(path, callback);
        }

        @Override
        public StreamCopier.Listener file(String name, long size) {
            String display = parentPath.isEmpty() ? name : parentPath + "/" + name;
            return transferred -> callback.onProgress(display, transferred, size);
        }
    }
}
