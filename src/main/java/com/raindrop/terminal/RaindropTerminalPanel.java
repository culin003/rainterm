package com.raindrop.terminal;

import com.raindrop.util.I18nManager;
import com.techsenger.jeditermfx.core.model.StyleState;
import com.techsenger.jeditermfx.core.model.TerminalTextBuffer;
import com.techsenger.jeditermfx.ui.TerminalAction;
import com.techsenger.jeditermfx.ui.TerminalActionProvider;
import com.techsenger.jeditermfx.ui.TerminalPanel;
import com.techsenger.jeditermfx.ui.settings.SettingsProvider;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;

import java.util.HashMap;
import java.util.Map;

/**
 * 标准菜单项文本的映射表。注意：jeditermfx 使用下划线作为加速键前缀，
 * 如 "_Copy" 表示 Alt-C 是快捷键。
 */

/**
 * TerminalPanel with a cached context menu. JediTermFX's default creates a fresh
 * ContextMenu on every right-click; on Linux the popup peer + CSS pass takes ~300ms.
 * Reusing one instance keeps the native peer alive so subsequent shows are near-instant.
 */
public class RaindropTerminalPanel extends TerminalPanel {
    private ContextMenu cachedPopup;

    private static final Map<String, String> menuTextMap = new HashMap<>();
    static {
        // 标准菜单项
        menuTextMap.put("Copy", "terminal.copy");
        menuTextMap.put("Paste", "terminal.paste");
        menuTextMap.put("Select All", "terminal.select_all");
        menuTextMap.put("Find...", "terminal.find");
        menuTextMap.put("Find", "terminal.find");
        menuTextMap.put("Clear Buffer", "terminal.clear_buffer");
        menuTextMap.put("Reset", "terminal.reset");
        menuTextMap.put("Reset and Clear", "terminal.reset_and_clear");
        menuTextMap.put("Reset and clear", "terminal.reset_and_clear");
        menuTextMap.put("Reset And Clear", "terminal.reset_and_clear");
        menuTextMap.put("Dump Screen", "terminal.dump_screen");
        menuTextMap.put("Dump screen", "terminal.dump_screen");
        menuTextMap.put("Close", "terminal.close");

        // jeditermfx 其他菜单项 - 多种大小写变体
        menuTextMap.put("Page Setup...", "terminal.page_setup");
        menuTextMap.put("Page Setup", "terminal.page_setup");
        menuTextMap.put("Print...", "terminal.print");
        menuTextMap.put("Print", "terminal.print");
        menuTextMap.put("Show History...", "terminal.show_history");
        menuTextMap.put("Show History", "terminal.show_history");
        menuTextMap.put("Scroll to Bottom", "terminal.scroll_to_bottom");
        menuTextMap.put("Scroll to bottom", "terminal.scroll_to_bottom");
        menuTextMap.put("Find Next", "terminal.find_next");
        menuTextMap.put("Find next", "terminal.find_next");
        menuTextMap.put("Find Previous", "terminal.find_previous");
        menuTextMap.put("Find previous", "terminal.find_previous");

        // 分隔符和其他可能的菜单项
        menuTextMap.put("Separator", "terminal.separator");
        menuTextMap.put("separator", "terminal.separator");
    }

    public RaindropTerminalPanel(SettingsProvider settings, TerminalTextBuffer buffer, StyleState style) {
        super(settings, buffer, style);
    }

    @Override
    protected ContextMenu createPopupMenu(TerminalActionProvider provider) {
        if (cachedPopup == null) {
            cachedPopup = new ContextMenu();
            // 在每次显示前翻译（支持语言切换、动态更新）
            cachedPopup.setOnShowing(e -> translateMenuItems(cachedPopup));
        } else {
            if (cachedPopup.isShowing()) cachedPopup.hide();
            cachedPopup.getItems().clear();
        }
        TerminalAction.fillMenu(cachedPopup, provider);
        return cachedPopup;
    }

    /**
     * 翻译所有菜单项。在菜单显示前调用。
     * 注意：jeditermfx 的菜单项文本带下划线前缀（如 "_Copy"），
     * 下划线表示该菜单项的加速键（mnemonic）。
     */
    private void translateMenuItems(ContextMenu menu) {
        for (MenuItem item : menu.getItems()) {
            String text = item.getText();
            if (text == null || text.isEmpty()) continue;

            // 移除下划线加速键前缀
            String cleaned = text.replace("_", "").trim();

            // 1. 精确匹配
            if (menuTextMap.containsKey(cleaned)) {
                item.setText(I18nManager.t(menuTextMap.get(cleaned)));
                continue;
            }

            // 2. 去掉 "..." 后再匹配
            if (cleaned.endsWith("...")) {
                String base = cleaned.substring(0, cleaned.length() - 3).trim();
                if (menuTextMap.containsKey(base)) {
                    item.setText(I18nManager.t(menuTextMap.get(base)));
                    continue;
                }
            }

            // 3. 不区分大小写的匹配（防御性编程）
            for (Map.Entry<String, String> entry : menuTextMap.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(cleaned)) {
                    item.setText(I18nManager.t(entry.getValue()));
                    break;
                }
            }

            // 4. 尝试匹配去掉省略号和点号后的变体
            String noDots = cleaned.replaceAll("\\.\\.\\.", "").trim();
            if (menuTextMap.containsKey(noDots)) {
                item.setText(I18nManager.t(menuTextMap.get(noDots)));
                continue;
            }

            // 5. 输出未翻译的菜单项（调试用）
            System.out.println("[I18n] Untranslated menu item: '" + cleaned + "'");
        }
    }

    /**
     * JediTermFX's default {@code clearBuffer()} calls {@code clearBuffer(true)}, which
     * tries to preserve the current cursor line by calling {@code textBuffer.getLine(y-1)}
     * <i>after</i> {@code clearHistory()} has already emptied the history — at y=0 that
     * becomes {@code getLine(-1)} and logs an ERROR. Falling back to
     * {@code clearBuffer(false)} avoided the log spam but also wiped the visible screen,
     * so the shell prompt disappeared until the user hit Enter.
     *
     * <p>What "Clear Buffer" should do is drop the scrollback history and keep the
     * current screen intact — the standard {@code Ctrl+L} / xterm behavior. Call
     * {@code clearHistory()} on the buffer directly and repaint; no screen wipe, no
     * out-of-bounds read.
     */
    @Override
    public void clearBuffer() {
        var buffer = getTerminalTextBuffer();
        if (buffer == null) return;
        if (buffer.isUsingAlternateBuffer()) return;
        buffer.clearHistory();
        var sb = getScrollBar();
        if (sb != null) sb.setValue(sb.getMin());
        repaint();
    }

    public ContextMenu getCachedPopup() {
        return cachedPopup;
    }

    /**
     * Force early creation of the popup native peer so the first user right-click
     * doesn't pay the cold-start cost. Must run on the FX thread after this panel
     * is attached to a Scene.
     */
    public void prewarmPopup(TerminalActionProvider provider) {
        if (cachedPopup != null) return;
        var menu = createPopupMenu(provider);
        if (getCanvas() != null && getCanvas().getScene() != null) {
            menu.show(getCanvas(), -100000, -100000);
            menu.hide();
        }
    }
}
