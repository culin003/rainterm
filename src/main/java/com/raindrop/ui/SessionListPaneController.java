package com.raindrop.ui;

import com.raindrop.storage.ConnectionProfile;
import com.raindrop.storage.ProfileRepository;
import com.raindrop.util.I18nManager;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.sql.SQLException;
import java.util.*;

public class SessionListPaneController {
    @FXML private TreeView<Object> sessionTree;
    @FXML private TextField filterField;

    private MainController mainController;
    private final ProfileRepository repository = new ProfileRepository();
    private List<ConnectionProfile> allProfiles = List.of();

    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    @FXML
    public void initialize() {
        TreeItem<Object> root = new TreeItem<>("root");
        sessionTree.setRoot(root);
        sessionTree.setCellFactory(tv -> new SessionCell());

        sessionTree.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                openSelected();
            }
        });

        MenuItem connect = new MenuItem(I18nManager.t("session_list.connect"));
        connect.setOnAction(e -> openSelected());
        MenuItem edit = new MenuItem(I18nManager.t("session_list.edit"));
        edit.setOnAction(e -> editSelected());
        MenuItem delete = new MenuItem(I18nManager.t("session_list.delete"));
        delete.setOnAction(e -> deleteSelected());
        sessionTree.setContextMenu(new ContextMenu(connect, edit, delete));

        filterField.textProperty().addListener((obs, o, n) -> renderTree(n));

        refresh();
    }

    @FXML
    private void onNew() {
        if (mainController != null) {
            mainController.openNewConnectionDialog(null);
        }
    }

    @FXML
    private void onRefresh() {
        refresh();
    }

    public void refresh() {
        try {
            allProfiles = repository.findAll();
        } catch (SQLException e) {
            allProfiles = List.of();
            if (mainController != null) {
                mainController.showError(I18nManager.t("session_list.load_failed", "message", e.getMessage()));
            }
        }
        renderTree(filterField == null ? "" : filterField.getText());
    }

    private void renderTree(String filter) {
        String needle = filter == null ? "" : filter.trim().toLowerCase();
        Map<String, TreeItem<Object>> groups = new LinkedHashMap<>();
        for (ConnectionProfile p : allProfiles) {
            if (!needle.isEmpty()) {
                String hay = (p.getName() + " " + p.getHost() + " " + p.getUsername()).toLowerCase();
                if (!hay.contains(needle)) continue;
            }
            String group = p.getGroupName() == null || p.getGroupName().isEmpty()
                ? I18nManager.t("session_list.default_group") : p.getGroupName();
            TreeItem<Object> groupItem = groups.computeIfAbsent(group, g -> {
                TreeItem<Object> item = new TreeItem<>(g);
                item.setExpanded(true);
                return item;
            });
            groupItem.getChildren().add(new TreeItem<>(p));
        }
        TreeItem<Object> root = sessionTree.getRoot();
        root.getChildren().setAll(groups.values());
    }

    private ConnectionProfile getSelectedProfile() {
        TreeItem<Object> selected = sessionTree.getSelectionModel().getSelectedItem();
        if (selected != null && selected.getValue() instanceof ConnectionProfile p) {
            return p;
        }
        return null;
    }

    private void openSelected() {
        ConnectionProfile p = getSelectedProfile();
        if (p != null && mainController != null) {
            mainController.openConnection(p);
        }
    }

    private void editSelected() {
        ConnectionProfile p = getSelectedProfile();
        if (p != null && mainController != null) {
            mainController.openNewConnectionDialog(p);
        }
    }

    private void deleteSelected() {
        ConnectionProfile p = getSelectedProfile();
        if (p == null) return;
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(I18nManager.t("session_list.delete"));
        alert.setHeaderText(null);
        alert.setContentText(I18nManager.t("session_list.delete_confirm", "name", p.getName()));
        com.raindrop.util.DialogUtil.showBlockingDialog(alert).ifPresent(bt -> {
            if (bt == ButtonType.OK) {
                try {
                    repository.delete(p.getId());
                    refresh();
                    if (mainController != null) {
                        mainController.updateStatus(I18nManager.t("session_list.delete_success", "name", p.getName()));
                    }
                } catch (SQLException e) {
                    if (mainController != null) {
                        mainController.showError(I18nManager.t("session_list.delete_failed", "message", e.getMessage()));
                    }
                }
            }
        });
    }

    private static class SessionCell extends TreeCell<Object> {
        @Override
        protected void updateItem(Object item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
            } else if (item instanceof ConnectionProfile p) {
                setText(p.getName() + "  (" + p.getUsername() + "@" + p.getHost() + ":" + p.getPort() + ")");
            } else {
                setText("▸ " + item);
            }
        }
    }
}
