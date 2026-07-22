package com.raindrop.ui;

import com.raindrop.core.SftpService;
import com.raindrop.core.SshSession;
import com.raindrop.core.TaskExecutor;
import com.raindrop.util.I18nManager;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * SFTP dual-pane browser. Supports local navigation, remote navigation,
 * multi-selection upload/download, drag-and-drop, and per-file progress.
 */
public class SftpBrowserController {

    private static final String DIR_PREFIX = "[DIR] ";
    private static final String PARENT_ENTRY = ".. (up)";  // Internal identifier for logic

    private static String getParentEntryLabel() {
        return I18nManager.t("sftp.parent_dir");
    }

    @FXML private TextField localPathField;
    @FXML private TextField remotePathField;
    @FXML private ListView<String> localFileList;
    @FXML private ListView<String> remoteFileList;
    @FXML private ProgressBar progressBar;
    @FXML private Label statusLabel;

    @FXML private Label localLabel;
    @FXML private Label remoteLabel;
    @FXML private Label localFilesLabel;
    @FXML private Label remoteFilesLabel;
    @FXML private Button localGoButton;
    @FXML private Button remoteGoButton;
    @FXML private Button uploadButton;
    @FXML private Button downloadButton;
    @FXML private Button newFolderButton;
    @FXML private Button renameButton;
    @FXML private Button deleteButton;
    @FXML private Button refreshButton;

    private SshSession session;
    private final SftpService sftpService = new SftpService();
    private File currentLocalDir = new File(System.getProperty("user.home"));
    private final List<RemoteResourceInfo> remoteEntries = new ArrayList<>();

    @FXML
    public void initialize() {
        // Set all labels and buttons with i18n
        localLabel.setText(I18nManager.t("sftp.local"));
        remoteLabel.setText(I18nManager.t("sftp.remote"));
        localFilesLabel.setText(I18nManager.t("sftp.local_files"));
        remoteFilesLabel.setText(I18nManager.t("sftp.remote_files"));
        localGoButton.setText(I18nManager.t("common.go"));
        remoteGoButton.setText(I18nManager.t("common.go"));
        uploadButton.setText(I18nManager.t("sftp.upload_button"));
        downloadButton.setText(I18nManager.t("sftp.download_button"));
        newFolderButton.setText(I18nManager.t("sftp.mkdir"));
        renameButton.setText(I18nManager.t("sftp.rename"));
        deleteButton.setText(I18nManager.t("sftp.delete"));
        refreshButton.setText(I18nManager.t("sftp.refresh"));
        statusLabel.setText(I18nManager.t("sftp.status_ready"));

        localFileList.setCellFactory(lv -> new EntryCell());
        remoteFileList.setCellFactory(lv -> new EntryCell());
        localFileList.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                onLocalDoubleClick();
            }
        });
        remoteFileList.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                onRemoteDoubleClick();
            }
        });
        setupDragAndDrop();
        setupRemoteContextMenu();
    }

    private void setupRemoteContextMenu() {
        MenuItem newFolder = new MenuItem(I18nManager.t("sftp.new_folder"));
        newFolder.setOnAction(e -> onNewFolder());
        MenuItem rename = new MenuItem(I18nManager.t("sftp.rename"));
        rename.setOnAction(e -> onRename());
        MenuItem delete = new MenuItem(I18nManager.t("sftp.delete"));
        delete.setOnAction(e -> onDelete());
        MenuItem refresh = new MenuItem(I18nManager.t("sftp.refresh"));
        refresh.setOnAction(e -> onRefresh());
        remoteFileList.setContextMenu(new ContextMenu(newFolder, rename, delete, refresh));
    }

    /**
     * ListView cell that renders our internal entry strings with an Ikonli icon:
     * "[DIR] foo" → folder icon + "foo", ".. (up)" → up-arrow, regular files → file icon.
     * The internal item string is left unchanged so callers keep using the
     * "[DIR] " marker to distinguish directories.
     */
    private static final class EntryCell extends ListCell<String> {
        private static final int ICON_SIZE = 14;
        private static final String DIR_COLOR = "#e8b84c";   // amber folder
        private static final String UP_COLOR = "#4ec9b0";    // accent (matches dark theme)
        private static final String FILE_COLOR = "#a0a0a0";  // muted grey

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            FontIcon icon;
            String display;
            if (PARENT_ENTRY.equals(item)) {
                icon = new FontIcon(FontAwesomeSolid.LEVEL_UP_ALT);
                icon.setIconColor(javafx.scene.paint.Color.web(UP_COLOR));
                display = getParentEntryLabel();
            } else if (item.startsWith(DIR_PREFIX)) {
                icon = new FontIcon(FontAwesomeSolid.FOLDER);
                icon.setIconColor(javafx.scene.paint.Color.web(DIR_COLOR));
                display = item.substring(DIR_PREFIX.length());
            } else {
                icon = new FontIcon(FontAwesomeSolid.FILE);
                icon.setIconColor(javafx.scene.paint.Color.web(FILE_COLOR));
                display = item;
            }
            icon.setIconSize(ICON_SIZE);
            setText(display);
            setGraphic(icon);
        }
    }

    public void setSession(SshSession session) {
        this.session = session;
        localPathField.setText(currentLocalDir.getAbsolutePath());
        loadLocalFiles();
        loadRemoteFiles("/");
    }

    // ---------- Local navigation ----------

    private void loadLocalFiles() {
        localPathField.setText(currentLocalDir.getAbsolutePath());
        File[] files = currentLocalDir.listFiles();
        List<String> items = new ArrayList<>();
        if (currentLocalDir.getParentFile() != null) items.add(PARENT_ENTRY);
        if (files != null) {
            java.util.Arrays.stream(files)
                .sorted(Comparator.comparing((File f) -> !f.isDirectory()).thenComparing(File::getName))
                .map(f -> f.isDirectory() ? DIR_PREFIX + f.getName() : f.getName())
                .forEach(items::add);
        }
        localFileList.setItems(FXCollections.observableArrayList(items));
    }

    private void onLocalDoubleClick() {
        String sel = localFileList.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        if (PARENT_ENTRY.equals(sel)) {
            File parent = currentLocalDir.getParentFile();
            if (parent != null) { currentLocalDir = parent; loadLocalFiles(); }
        } else if (sel.startsWith(DIR_PREFIX)) {
            File next = new File(currentLocalDir, sel.substring(DIR_PREFIX.length()));
            if (next.isDirectory()) { currentLocalDir = next; loadLocalFiles(); }
        }
    }

    @FXML
    private void onLocalGo() {
        String path = localPathField.getText();
        if (path == null || path.isEmpty()) return;
        File dir = new File(path);
        if (dir.isDirectory()) {
            currentLocalDir = dir;
            loadLocalFiles();
        } else {
            statusLabel.setText(I18nManager.t("sftp.not_a_directory", "path", path));
        }
    }

    // ---------- Remote navigation ----------

    private void loadRemoteFiles(String path) {
        if (session == null || !session.isConnected()) {
            statusLabel.setText(I18nManager.t("sftp.not_connected"));
            return;
        }
        remotePathField.setText(path);
        statusLabel.setText(I18nManager.t("sftp.loading", "path", path));
        sftpService.listDirectory(session, path)
            .thenAccept(entries -> Platform.runLater(() -> {
                remoteEntries.clear();
                remoteEntries.addAll(entries);
                List<String> items = new ArrayList<>();
                if (!"/".equals(path)) items.add(PARENT_ENTRY);
                entries.stream()
                    .sorted(Comparator.comparing((RemoteResourceInfo r) -> !r.isDirectory()).thenComparing(RemoteResourceInfo::getName))
                    .map(e -> e.isDirectory() ? DIR_PREFIX + e.getName() : e.getName())
                    .forEach(items::add);
                remoteFileList.setItems(FXCollections.observableArrayList(items));
                statusLabel.setText(I18nManager.t("sftp.loaded_items", "count", String.valueOf(entries.size())));
            }))
            .exceptionally(e -> {
                Platform.runLater(() -> statusLabel.setText(I18nManager.t("sftp.error", "message", rootMsg(e))));
                return null;
            });
    }

    private void onRemoteDoubleClick() {
        String sel = remoteFileList.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        String current = remotePathField.getText();
        if (PARENT_ENTRY.equals(sel)) {
            loadRemoteFiles(parentPath(current));
        } else if (sel.startsWith(DIR_PREFIX)) {
            String name = sel.substring(DIR_PREFIX.length());
            loadRemoteFiles(joinRemote(current, name));
        }
    }

    @FXML
    private void onGo() {
        String path = remotePathField.getText();
        if (path != null && !path.isEmpty()) loadRemoteFiles(path);
    }

    // ---------- Transfer actions ----------

    @FXML
    private void onUpload() {
        FileChooser fc = new FileChooser();
        fc.setTitle(I18nManager.t("sftp.upload_select"));
        fc.setInitialDirectory(currentLocalDir);
        List<File> files = fc.showOpenMultipleDialog(localFileList.getScene().getWindow());
        if (files == null || files.isEmpty()) return;
        doUpload(files);
    }

    @FXML
    private void onDownload() {
        List<String> selection = new ArrayList<>(remoteFileList.getSelectionModel().getSelectedItems());
        if (selection.isEmpty() || session == null) return;

        List<String> remotePaths = new ArrayList<>();
        String remoteDir = remotePathField.getText();
        for (String item : selection) {
            if (PARENT_ENTRY.equals(item) || item.startsWith(DIR_PREFIX)) continue;
            remotePaths.add(joinRemote(remoteDir, item));
        }
        if (remotePaths.isEmpty()) {
            statusLabel.setText(I18nManager.t("sftp.select_regular_files"));
            return;
        }

        DirectoryChooser dc = new DirectoryChooser();
        dc.setInitialDirectory(currentLocalDir);
        dc.setTitle(I18nManager.t("sftp.select_target_dir"));
        File target = dc.showDialog(remoteFileList.getScene().getWindow());
        if (target == null) return;

        progressBar.setProgress(-1);
        statusLabel.setText(I18nManager.t("sftp.downloading", "count", String.valueOf(remotePaths.size())));
        sftpService.batchDownload(session, remotePaths, target, this::onProgress)
            .whenComplete((v, err) -> Platform.runLater(() -> {
                if (err != null) {
                    statusLabel.setText(I18nManager.t("sftp.download_failed", "message", rootMsg(err)));
                    progressBar.setProgress(0);
                } else {
                    statusLabel.setText(I18nManager.t("sftp.download_complete", "count", String.valueOf(remotePaths.size())));
                    progressBar.setProgress(1);
                    if (target.equals(currentLocalDir) || target.getAbsolutePath().equals(currentLocalDir.getAbsolutePath())) {
                        loadLocalFiles();
                    }
                }
            }));
    }

    @FXML
    private void onRefresh() {
        loadLocalFiles();
        loadRemoteFiles(remotePathField.getText());
    }

    @FXML
    private void onNewFolder() {
        if (session == null || !session.isConnected()) return;
        TextInputDialog dlg = new TextInputDialog("new-folder");
        dlg.setTitle(I18nManager.t("sftp.mkdir"));
        dlg.setHeaderText(null);
        dlg.setContentText(I18nManager.t("sftp.folder_name"));
        com.raindrop.util.ThemeManager.apply(dlg.getDialogPane());
        dlg.showAndWait().ifPresent(name -> {
            String trimmed = name == null ? "" : name.trim();
            if (trimmed.isEmpty()) return;
            String remoteDir = remotePathField.getText();
            String target = joinRemote(remoteDir, trimmed);
            statusLabel.setText(I18nManager.t("sftp.creating", "path", target));
            sftpService.mkdir(session, target)
                .whenComplete((v, err) -> Platform.runLater(() -> {
                    if (err != null) {
                        statusLabel.setText(I18nManager.t("sftp.mkdir_failed", "message", rootMsg(err)));
                    } else {
                        statusLabel.setText(I18nManager.t("sftp.created", "path", target));
                        loadRemoteFiles(remoteDir);
                    }
                }));
        });
    }

    @FXML
    private void onRename() {
        if (session == null || !session.isConnected()) return;
        String sel = remoteFileList.getSelectionModel().getSelectedItem();
        if (sel == null || PARENT_ENTRY.equals(sel)) return;
        boolean isDir = sel.startsWith(DIR_PREFIX);
        String oldName = isDir ? sel.substring(DIR_PREFIX.length()) : sel;

        TextInputDialog dlg = new TextInputDialog(oldName);
        dlg.setTitle(I18nManager.t("sftp.rename"));
        dlg.setHeaderText(null);
        dlg.setContentText(I18nManager.t("sftp.rename_to"));
        com.raindrop.util.ThemeManager.apply(dlg.getDialogPane());
        dlg.showAndWait().ifPresent(name -> {
            String trimmed = name == null ? "" : name.trim();
            if (trimmed.isEmpty() || trimmed.equals(oldName)) return;
            String remoteDir = remotePathField.getText();
            String oldPath = joinRemote(remoteDir, oldName);
            String newPath = joinRemote(remoteDir, trimmed);
            statusLabel.setText(I18nManager.t("sftp.renaming", "old", oldName, "new", trimmed));
            sftpService.rename(session, oldPath, newPath)
                .whenComplete((v, err) -> Platform.runLater(() -> {
                    if (err != null) {
                        statusLabel.setText(I18nManager.t("sftp.rename_failed", "message", rootMsg(err)));
                    } else {
                        statusLabel.setText(I18nManager.t("sftp.renamed"));
                        loadRemoteFiles(remoteDir);
                    }
                }));
        });
    }

    @FXML
    private void onDelete() {
        if (session == null || !session.isConnected()) return;
        List<String> selection = new ArrayList<>(remoteFileList.getSelectionModel().getSelectedItems());
        selection.removeIf(s -> s == null || PARENT_ENTRY.equals(s));
        if (selection.isEmpty()) return;

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(I18nManager.t("sftp.delete"));
        confirm.setHeaderText(null);
        confirm.setContentText(I18nManager.t("sftp.delete_confirm", "count", String.valueOf(selection.size())));
        com.raindrop.util.ThemeManager.apply(confirm);
        confirm.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            String remoteDir = remotePathField.getText();
            List<CompletableFutureItem> jobs = new ArrayList<>();
            for (String item : selection) {
                boolean isDir = item.startsWith(DIR_PREFIX);
                String name = isDir ? item.substring(DIR_PREFIX.length()) : item;
                String path = joinRemote(remoteDir, name);
                jobs.add(new CompletableFutureItem(name,
                    sftpService.remove(session, path, isDir)));
            }
            statusLabel.setText(I18nManager.t("sftp.deleting", "count", String.valueOf(jobs.size())));
            java.util.concurrent.CompletableFuture.allOf(
                    jobs.stream().map(j -> j.future).toArray(java.util.concurrent.CompletableFuture[]::new))
                .whenComplete((v, err) -> Platform.runLater(() -> {
                    if (err != null) {
                        statusLabel.setText(I18nManager.t("sftp.delete_with_errors", "message", rootMsg(err)));
                    } else {
                        statusLabel.setText(I18nManager.t("sftp.deleted", "count", String.valueOf(jobs.size())));
                    }
                    loadRemoteFiles(remoteDir);
                }));
        });
    }

    private record CompletableFutureItem(String name, java.util.concurrent.CompletableFuture<Void> future) {}

    private void doUpload(List<File> files) {
        String remoteDir = remotePathField.getText();
        progressBar.setProgress(-1);
        statusLabel.setText(I18nManager.t("sftp.uploading", "count", String.valueOf(files.size())));
        sftpService.batchUpload(session, files, remoteDir, this::onProgress)
            .whenComplete((v, err) -> Platform.runLater(() -> {
                if (err != null) {
                    statusLabel.setText(I18nManager.t("sftp.upload_failed", "message", rootMsg(err)));
                    progressBar.setProgress(0);
                } else {
                    statusLabel.setText(I18nManager.t("sftp.upload_complete", "count", String.valueOf(files.size())));
                    progressBar.setProgress(1);
                    loadRemoteFiles(remoteDir);
                }
            }));
    }

    /** Runs on virtual thread — dispatch back to FX. */
    private void onProgress(String file, long transferred, long total) {
        double pct = total > 0 ? (double) transferred / total : -1;
        Platform.runLater(() -> {
            progressBar.setProgress(pct);
            statusLabel.setText(String.format("%s  %s / %s",
                file, humanBytes(transferred), total > 0 ? humanBytes(total) : "?"));
        });
    }

    // ---------- Drag and drop ----------

    private void setupDragAndDrop() {
        // Drag from local list → files
        localFileList.setOnDragDetected(e -> {
            String sel = localFileList.getSelectionModel().getSelectedItem();
            if (sel == null || PARENT_ENTRY.equals(sel) || sel.startsWith(DIR_PREFIX)) return;
            Dragboard db = localFileList.startDragAndDrop(TransferMode.COPY);
            ClipboardContent cc = new ClipboardContent();
            List<File> files = localFileList.getSelectionModel().getSelectedItems().stream()
                .filter(s -> s != null && !PARENT_ENTRY.equals(s) && !s.startsWith(DIR_PREFIX))
                .map(s -> new File(currentLocalDir, s))
                .filter(File::isFile)
                .collect(Collectors.toList());
            if (files.isEmpty()) return;
            cc.putFiles(files);
            db.setContent(cc);
            e.consume();
        });

        // Drop onto remote list → upload
        remoteFileList.setOnDragOver(e -> {
            if (e.getGestureSource() != remoteFileList && e.getDragboard().hasFiles()) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        remoteFileList.setOnDragDropped(e -> {
            Dragboard db = e.getDragboard();
            boolean ok = false;
            if (db.hasFiles()) {
                List<File> files = db.getFiles().stream().filter(File::isFile).collect(Collectors.toList());
                if (!files.isEmpty()) { doUpload(files); ok = true; }
            }
            e.setDropCompleted(ok);
            e.consume();
        });

        // Drop from OS → local upload target (also allows OS-native drags to remote)
        localFileList.setOnDragOver(e -> {
            if (e.getGestureSource() != localFileList && e.getDragboard().hasFiles()) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
    }

    // ---------- Helpers ----------

    private static String joinRemote(String base, String name) {
        if (base.endsWith("/")) return base + name;
        return base + "/" + name;
    }

    private static String parentPath(String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) return "/";
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int idx = trimmed.lastIndexOf('/');
        if (idx <= 0) return "/";
        return trimmed.substring(0, idx);
    }

    private static String rootMsg(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) cur = cur.getCause();
        return cur.getMessage() != null ? cur.getMessage() : cur.toString();
    }

    private static String humanBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024L * 1024) return String.format("%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format("%.1f MB", b / (1024.0 * 1024));
        return String.format("%.2f GB", b / (1024.0 * 1024 * 1024));
    }
}
