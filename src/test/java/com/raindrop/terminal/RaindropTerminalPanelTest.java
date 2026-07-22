package com.raindrop.terminal;

import com.raindrop.util.I18nManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RaindropTerminalPanelTest {

    @Test
    void testMenuTextMapping() throws Exception {
        Field field = RaindropTerminalPanel.class.getDeclaredField("menuTextMap");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> map = (Map<String, String>) field.get(null);

        // 验证所有 10 个菜单项映射存在
        assertEquals(10, map.size(), "Should have exactly 10 menu items");
        assertTrue(map.containsKey("Open as URL"));
        assertTrue(map.containsKey("Copy"));
        assertTrue(map.containsKey("Paste"));
        assertTrue(map.containsKey("Select All"));
        assertTrue(map.containsKey("Find"));
        assertTrue(map.containsKey("Clear Buffer"));
        assertTrue(map.containsKey("Page Up"));
        assertTrue(map.containsKey("Page Down"));
        assertTrue(map.containsKey("Line Up"));
        assertTrue(map.containsKey("Line Down"));
    }

    @Test
    void testUnderlineRemovalAndTranslation() throws Exception {
        // 模拟带下划线的菜单项文本清理后翻译
        String[] input = {"_Copy", "_Paste", "_Select All", "_Clear Buffer", "_Line Up"};
        String[] expectedKeys = {
            "terminal.copy",
            "terminal.paste",
            "terminal.select_all",
            "terminal.clear_buffer",
            "terminal.line_up"
        };

        Field field = RaindropTerminalPanel.class.getDeclaredField("menuTextMap");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> map = (Map<String, String>) field.get(null);

        for (int i = 0; i < input.length; i++) {
            String cleaned = input[i].replace("_", "").trim();
            String key = map.get(cleaned);
            assertEquals(expectedKeys[i], key, "Key mismatch for: " + input[i]);
            assertNotNull(I18nManager.t(key), "Should get translation for: " + key);
        }
    }
}
