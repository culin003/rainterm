# Raindrop SSH Manager - 国际化功能实现总结

## 实现日期
2025年7月20日

## 实现范围

### 1. 核心架构

**I18nManager 类** (`src/main/java/com/raindrop/util/I18nManager.java`)
- 单例模式管理全局多语言资源
- 支持三种语言：简体中文（zh_CN）、繁体中文（zh_TW）、英文（en_US）
- 支持占位符参数替换（`{key}` 格式）
- 语言设置持久化到数据库（ConfigManager）
- 支持动态获取支持的语言列表

### 2. 多语言资源文件

**位置**: `src/main/resources/i18n/`

| 文件 | 说明 | 键数量 |
|------|------|--------|
| `messages_zh_CN.json` | 简体中文 | ~120 |
| `messages_zh_TW.json` | 繁体中文 | ~120 |
| `messages_en_US.json` | 英文 | ~120 |

**资源分类**:
- `common.*` - 通用按钮/操作（确定、取消、删除等）
- `main.*` - 主界面（状态、侧边栏、内存显示等）
- `connection_dialog.*` - 连接对话框（标签、验证消息等）
- `session_list.*` - 会话列表（右键菜单、确认对话框等）
- `credential_dialog.*` - 凭证管理器（类型、提示等）
- `settings.*` - 设置界面（语言选择、主题等）
- `errors.*` - 错误消息
- `status.*` - 状态消息
- `lock.*` - 锁定界面
- `sftp.*` - SFTP 浏览器（上传/下载、状态消息等）

### 3. 已完成国际化的 UI 控制器

| 控制器 | 文件 | 说明 |
|--------|------|------|
| MainController | `ui/MainController.java` | 状态消息、工具提示、对话框标题 |
| SessionListPaneController | `ui/SessionListPaneController.java` | 右键菜单、确认对话框、错误消息 |
| ConnectionDialogController | `ui/ConnectionDialogController.java` | 所有标签、按钮、验证消息 |
| CredentialDialogController | `ui/credential/CredentialDialogController.java` | 类型选择、错误消息、确认对话框 |
| SettingsViewController | `ui/SettingsViewController.java` | 语言选择下拉框、设置标签、按钮文本 |
| SftpBrowserController | `ui/SftpBrowserController.java` | 右键菜单、上传/下载状态、确认对话框 |
| LockController | `ui/security/LockController.java` | 解锁界面、错误提示、按钮文本 |

### 4. 功能特性

#### 语言切换
- 在 **设置** 对话框中提供语言选择下拉框
- 切换后立即保存到数据库配置
- 下次启动应用时生效（当前版本重启生效）
- 默认跟随系统语言（中文系统→简体中文，其他→英文）

#### 占位符支持
```java
// 单个参数
I18nManager.t("session_list.delete_confirm", "name", "prod-server");
// 输出：确定要删除 'prod-server' 吗？

// 多个参数
I18nManager.t("sftp.downloading", "count", "5");
// 输出：正在下载 5 个文件...
```

#### 静态快捷方法
```java
// 推荐使用静态导入
import static com.raindrop.util.I18nManager.t;

// 简单使用
t("common.ok");        // "确定" / "確定" / "OK"

// 带占位符
t("status.memory", "used", "100", "max", "500");
```

## 5. 测试覆盖

**I18nManagerTest.java** (`src/test/java/com/raindrop/util/`)

测试用例：
1. `getInstance()` - 单例模式验证
2. 简单键值获取 - 验证基础翻译
3. 占位符替换 - 验证参数替换
4. 语言切换 - 验证三种语言切换正常
5. 支持语言列表 - 验证列表获取
6. 未知键回退 - 找不到键时返回键本身
7. 无效语言异常 - 验证非法语言代码抛出异常

所有测试通过 ✅

## 6. 设计文档更新

`doc/DESIGN.md` 添加了第 13 章 "国际化（i18n）"，包含：
- 目标说明
- 技术选型
- 文件结构
- API 示例
- 翻译规范
- 启动流程
- 性能考虑
- 回退机制

## 7. 使用指南

### 添加新翻译键
1. 在三个 JSON 文件中同步添加新键
2. 确保键名使用点号分隔的层级结构
3. 中文翻译优先，然后是英文和繁体中文

### 在代码中使用国际化
```java
// 1. 导入 I18nManager
import com.raindrop.util.I18nManager;

// 2. 简单使用
button.setText(I18nManager.t("common.ok"));

// 3. 带占位符
statusLabel.setText(I18nManager.t("sftp.downloading",
    "count", String.valueOf(fileCount)));

// 4. 推荐使用静态导入（更简洁）
import static com.raindrop.util.I18nManager.t;
label.setText(t("connection_dialog.host"));
```

### 添加新的支持语言
1. 创建新的 JSON 文件 `messages_{lang}.json`
2. 在 I18nManager 中添加新语言常量
3. 更新 `isValidLanguage()` 方法
4. 更新 `getSupportedLanguages()` 方法

## 8. 已知限制与未来改进

### 当前限制
- 语言切换后需要重启应用才能完全生效
- FXML 文件中的静态文本（如 Label）需要在 Controller 的 `initialize()` 中通过代码设置

### 未来改进方向
1. **监听器模式** - 实现语言变化监听器，支持动态刷新所有 UI 而无需重启
2. **FXML 注解支持** - 通过注解自动绑定 FXML 中的国际化文本
3. **构建时验证** - 添加 Gradle 任务验证所有翻译键的完整性
4. **更多语言** - 支持日文、韩文等其他语言
5. **语言导出/导入** - 支持翻译文件的导出和导入，方便社区贡献

## 9. 文件变更清单

### 新增文件
- `src/main/java/com/raindrop/util/I18nManager.java`
- `src/main/resources/i18n/messages_zh_CN.json`
- `src/main/resources/i18n/messages_zh_TW.json`
- `src/main/resources/i18n/messages_en_US.json`
- `src/test/java/com/raindrop/util/I18nManagerTest.java`
- `doc/I18N_IMPLEMENTATION.md`（本文件）

### 修改文件
- `build.gradle.kts` - 添加 Jackson 依赖
- `doc/DESIGN.md` - 添加国际化章节
- `src/main/java/com/raindrop/ui/MainController.java`
- `src/main/java/com/raindrop/ui/SessionListPaneController.java`
- `src/main/java/com/raindrop/ui/ConnectionDialogController.java`
- `src/main/java/com/raindrop/ui/CredentialDialogController.java`
- `src/main/java/com/raindrop/ui/SettingsViewController.java`
- `src/main/java/com/raindrop/ui/SftpBrowserController.java`
- `src/main/java/com/raindrop/ui/security/LockController.java`

## 10. 依赖变更

**新增依赖**:
```kotlin
// JSON parsing for i18n
implementation("com.fasterxml.jackson.core:jackson-databind:2.16.1")
```

## 11. 验收标准 ✅

- [x] 支持简体中文、繁体中文、英文三种语言
- [x] 语言设置持久化到数据库
- [x] 所有主要 UI 界面文本国际化
- [x] 支持占位符参数替换
- [x] 编译无错误
- [x] 所有单元测试通过
- [x] 设计文档更新
- [x] 应用程序可正常启动运行
