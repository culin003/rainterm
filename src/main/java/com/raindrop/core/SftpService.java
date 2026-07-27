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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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

    /** Batch progress callback: overall + per-file progress. */
    @FunctionalInterface
    public interface BatchProgressCallback {
        void onProgress(String currentFileName,
                        long currentFileTransferred, long currentFileTotal,
                        long totalTransferred, long totalSize,
                        int completedFiles, int totalFiles);
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

    /** Create directories recursively. */
    public CompletableFuture<Void> mkdirs(SshSession session, String remotePath) {
        return CompletableFuture.runAsync(() -> {
            try {
                SFTPClient sftp = session.getSftpClient();
                String[] parts = remotePath.split("/");
                StringBuilder current = new StringBuilder();
                for (String part : parts) {
                    if (part.isEmpty()) continue;
                    current.append("/").append(part);
                    try {
                        sftp.stat(current.toString());
                    } catch (IOException e) {
                        sftp.mkdir(current.toString());
                    }
                }
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
     * Calculate total size of files/folders recursively (local).
     */
    public static long calculateTotalSize(List<File> items) throws IOException {
        long total = 0;
        for (File item : items) {
            if (item.isFile()) {
                total += item.length();
            } else if (item.isDirectory()) {
                File[] children = item.listFiles();
                if (children != null) {
                    total += calculateTotalSize(List.of(children));
                }
            }
        }
        return total;
    }

    /**
     * Count total number of files recursively (local).
     */
    public static int countFiles(List<File> items) {
        int count = 0;
        for (File item : items) {
            if (item.isFile()) {
                count++;
            } else if (item.isDirectory()) {
                File[] children = item.listFiles();
                if (children != null) {
                    count += countFiles(List.of(children));
                }
            }
        }
        return count;
    }

    /**
     * Calculate total size of remote files/folders recursively.
     */
    public CompletableFuture<Long> calculateRemoteTotalSize(SshSession session, List<String> paths) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                long total = 0;
                SFTPClient sftp = session.getSftpClient();
                for (String path : paths) {
                    net.schmizz.sshj.sftp.FileAttributes attrs = sftp.stat(path);
                    if (attrs.getType() == net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY) {
                        total += calculateRemoteDirectorySize(sftp, path);
                    } else {
                        total += attrs.getSize();
                    }
                }
                return total;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, TaskExecutor.getExecutor());
    }

    private long calculateRemoteDirectorySize(SFTPClient sftp, String path) throws IOException {
        long total = 0;
        List<RemoteResourceInfo> entries = sftp.ls(path);
        for (RemoteResourceInfo entry : entries) {
            String name = entry.getName();
            if (".".equals(name) || "..".equals(name)) continue;
            String fullPath = path.endsWith("/") ? path + name : path + "/" + name;
            if (entry.isDirectory()) {
                total += calculateRemoteDirectorySize(sftp, fullPath);
            } else {
                total += entry.getAttributes().getSize();
            }
        }
        return total;
    }

    /**
     * Batch upload with folders support and dual-progress tracking.
     */
    public CompletableFuture<Void> batchUploadWithFolders(
            SshSession session, List<File> items, String remoteDir,
            long totalSize, BatchProgressCallback progress) {
        AtomicLong totalTransferred = new AtomicLong(0);
        AtomicInteger completedFiles = new AtomicInteger(0);
        int totalFiles = countFiles(items);

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (File item : items) {
            if (item.isFile()) {
                futures.add(uploadFileWithProgress(session, item, remoteDir, progress,
                    totalTransferred, completedFiles, totalFiles, totalSize));
            } else if (item.isDirectory()) {
                futures.add(uploadDirectoryRecursive(session, item, remoteDir, progress,
                    totalTransferred, completedFiles, totalFiles, totalSize));
            }
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    private CompletableFuture<Void> uploadFileWithProgress(
            SshSession session, File file, String remoteDir,
            BatchProgressCallback progress, AtomicLong totalTransferred,
            AtomicInteger completedFiles, int totalFiles, long totalSize) {
        return CompletableFuture.runAsync(() -> {
            try {
                SFTPFileTransfer ft = session.getSftpClient().getFileTransfer();
                String remote = remoteDir.endsWith("/") ? remoteDir + file.getName() : remoteDir + "/" + file.getName();
                if (progress != null) {
                    ft.setTransferListener(new BatchProgressTransferListener("", progress,
                        totalTransferred, completedFiles, totalFiles, totalSize));
                }
                ft.upload(new FileSystemFile(file), remote);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, TaskExecutor.getExecutor());
    }

    private CompletableFuture<Void> uploadDirectoryRecursive(
            SshSession session, File dir, String remoteDir,
            BatchProgressCallback progress, AtomicLong totalTransferred,
            AtomicInteger completedFiles, int totalFiles, long totalSize) {
        return CompletableFuture.runAsync(() -> {
            try {
                String newRemoteDir = remoteDir.endsWith("/") ? remoteDir + dir.getName() : remoteDir + "/" + dir.getName();
                mkdirs(session, newRemoteDir).join();

                File[] children = dir.listFiles();
                if (children != null) {
                    List<CompletableFuture<Void>> futures = new ArrayList<>();
                    for (File child : children) {
                        if (child.isFile()) {
                            futures.add(uploadFileWithProgress(session, child, newRemoteDir, progress,
                                totalTransferred, completedFiles, totalFiles, totalSize));
                        } else if (child.isDirectory()) {
                            futures.add(uploadDirectoryRecursive(session, child, newRemoteDir, progress,
                                totalTransferred, completedFiles, totalFiles, totalSize));
                        }
                    }
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, TaskExecutor.getExecutor());
    }

    /**
     * Batch download with folders support and dual-progress tracking.
     */
    public CompletableFuture<Void> batchDownloadWithFolders(
            SshSession session, List<String> remotePaths, File localDir,
            long totalSize, BatchProgressCallback progress) {
        AtomicLong totalTransferred = new AtomicLong(0);
        AtomicInteger completedFiles = new AtomicInteger(0);

        return CompletableFuture.supplyAsync(() -> {
            try {
                SFTPClient sftp = session.getSftpClient();
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                List<String> allFiles = new ArrayList<>();

                for (String path : remotePaths) {
                    net.schmizz.sshj.sftp.FileAttributes attrs = sftp.stat(path);
                    if (attrs.getType() == net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY) {
                        collectRemoteFilesRecursive(sftp, path, allFiles);
                    } else {
                        allFiles.add(path);
                    }
                }

                int totalFiles = allFiles.size();
                for (String remotePath : allFiles) {
                    String relativePath = remotePath;
                    for (String base : remotePaths) {
                        if (remotePath.startsWith(base) && !remotePath.equals(base)) {
                            relativePath = remotePath.substring(base.lastIndexOf('/') + 1);
                            break;
                        }
                    }
                    if (relativePath.startsWith("/")) relativePath = relativePath.substring(1);

                    File localFile = new File(localDir, relativePath);
                    File parentDir = localFile.getParentFile();
                    if (parentDir != null && !parentDir.exists()) {
                        parentDir.mkdirs();
                    }

                    futures.add(downloadFileWithProgress(session, remotePath, localFile, progress,
                        totalTransferred, completedFiles, totalFiles, totalSize));
                }

                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                return null;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, TaskExecutor.getExecutor());
    }

    private void collectRemoteFilesRecursive(SFTPClient sftp, String path, List<String> result) throws IOException {
        List<RemoteResourceInfo> entries = sftp.ls(path);
        for (RemoteResourceInfo entry : entries) {
            if (".".equals(entry.getName()) || "..".equals(entry.getName())) continue;
            String fullPath = path.endsWith("/") ? path + entry.getName() : path + "/" + entry.getName();
            if (entry.isDirectory()) {
                collectRemoteFilesRecursive(sftp, fullPath, result);
            } else {
                result.add(fullPath);
            }
        }
    }

    private CompletableFuture<Void> downloadFileWithProgress(
            SshSession session, String remotePath, File localFile,
            BatchProgressCallback progress, AtomicLong totalTransferred,
            AtomicInteger completedFiles, int totalFiles, long totalSize) {
        return CompletableFuture.runAsync(() -> {
            try {
                SFTPFileTransfer ft = session.getSftpClient().getFileTransfer();
                if (progress != null) {
                    ft.setTransferListener(new BatchProgressTransferListener("", progress,
                        totalTransferred, completedFiles, totalFiles, totalSize));
                }
                ft.download(remotePath, new FileSystemFile(localFile));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, TaskExecutor.getExecutor());
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

    /**
     * Adapter for batch progress with dual-progress tracking.
     */
    private static final class BatchProgressTransferListener implements TransferListener {
        private final String parentPath;
        private final BatchProgressCallback callback;
        private final AtomicLong totalTransferred;
        private final AtomicInteger completedFiles;
        private final int totalFiles;
        private final long totalSize;
        private long lastTransferred = 0;

        BatchProgressTransferListener(String parentPath, BatchProgressCallback callback,
                                      AtomicLong totalTransferred, AtomicInteger completedFiles,
                                      int totalFiles, long totalSize) {
            this.parentPath = parentPath;
            this.callback = callback;
            this.totalTransferred = totalTransferred;
            this.completedFiles = completedFiles;
            this.totalFiles = totalFiles;
            this.totalSize = totalSize;
        }

        @Override
        public TransferListener directory(String name) {
            String path = parentPath.isEmpty() ? name : parentPath + "/" + name;
            return new BatchProgressTransferListener(path, callback,
                totalTransferred, completedFiles, totalFiles, totalSize);
        }

        @Override
        public StreamCopier.Listener file(String name, long size) {
            String display = parentPath.isEmpty() ? name : parentPath + "/" + name;
            lastTransferred = 0;
            return transferred -> {
                long delta = transferred - lastTransferred;
                lastTransferred = transferred;
                long currentTotal = totalTransferred.addAndGet(delta);
                int completed = completedFiles.get();
                callback.onProgress(display, transferred, size,
                    currentTotal, totalSize, completed, totalFiles);
                if (transferred >= size && size > 0) {
                    completedFiles.incrementAndGet();
                }
            };
        }
    }
}
