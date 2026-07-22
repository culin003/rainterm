package com.raindrop.terminal;

import com.raindrop.util.I18nManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RaindropTerminalPanelTest {

    @Test
    void testMenuTextMapping() throws Exception {
        // 测试下划线清理和映射
        Field field = RaindropTerminalPanel.class.getDeclaredField("menuTextMap");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> map = (Map<String, String>) field.get(null);

        // 验证映射存在
        assertTrue(map.containsKey("Copy"));
        assertTrue(map.containsKey("Paste"));
        assertTrue(map.containsKey("Select All"));
        assertTrue(map.containsKey("Find..."));
        assertTrue(map.containsKey("Clear Buffer"));
        assertTrue(map.containsKey("Reset"));
        assertTrue(map.containsKey("Reset and Clear"));
        assertTrue(map.containsKey("Dump Screen"));
        assertTrue(map.containsKey("Close"));
    }

    @Test
    void testUnderlineRemovalAndTranslation() throws Exception {
        // 模拟带下划线的菜单项文本清理后翻译
        String[] input = {"_Copy", "_Paste", "_Select All", "_Find...", "_Reset and Clear"};
        String[] expectedKeys = {
            "terminal.copy",
            "terminal.paste",
            "terminal.select_all",
            "terminal.find",
            "terminal.reset_and_clear"
        };

        Field field = RaindropTerminalPanel.class.getDeclaredField("menuTextMap");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> map = (Map<String, String>) field.get(null);

        for (int i = 0; i < input.length; i++) {
            String cleaned = input[i].replace("_", "").trim();
            String key = map.get(cleaned);
            // 如果精确匹配不到，尝试去掉省略号
            if (key == null && cleaned.endsWith("...")) {
                String base = cleaned.substring(0, cleaned.length() - 3).trim();
                key = map.get(base);
            }
            assertEquals(expectedKeys[i], key, "Key mismatch for: " + input[i]);
            // 验证能获取到翻译文本
            assertNotNull(I18nManager.t(key), "Should get translation for: " + key);
        }
    }
}
