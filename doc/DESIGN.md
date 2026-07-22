# Raindrop SSH Manager - 概要设计（虚拟线程架构）

## 1. 项目定位

Java 桌面端 SSH 管理工具，功能对标 XShell/MobaXterm：
- 保存和管理 SSH 连接信息（主机、端口、用户名、密码/密钥）
- 多标签页并发 SSH 终端
- SFTP 双面板文件浏览器 + 拖拽传输
- 跨平台（Windows / macOS / Linux）

## 2. 技术栈

| 层 | 选型 | 理由 |
|---|---|---|
| 语言 | Java 21 LTS | 虚拟线程 GA，现代语法（record、sealed、pattern matching） |
| 并发 | 虚拟线程 (Project Loom) | I/O 密集场景杀手锏，万级并发无压力 |
| GUI | JavaFX 21 + FXML | 现代 Java UI，FXML 布局分离，支持 CSS 主题 |
| SSH/SFTP | SSHJ 0.37 | Apache 许可，纯 Java，API 友好，主动维护 |
| 终端模拟 | Techsenger JediTermFX | JediTerm 的 JavaFX 移植，工业级 xterm 兼容、24-bit 色、Canvas 渲染 |
| 数据库 | SQLite (sqlite-jdbc) | 嵌入式，零配置，存连接配置 |
| 密码加密 | Jasypt | 加密保存的密码和密钥口令 |
| 构建 | Gradle 8 + Kotlin DSL | 灵活，增量构建快 |
| 打包 | jpackage | 生成各平台原生安装包（.msi/.dmg/.deb） |

### 为什么选 Java 21 + 虚拟线程

SSH 管理工具的典型特征：**大量长连接 + 频繁 I/O 阻塞**。虚拟线程完美匹配：

| 场景 | 传统线程池 | 虚拟线程 |
|---|---|---|
| 50 个 SSH 会话同时在线 | 50 个平台线程，栈内存 ~50MB | 50 个虚拟线程，栈内存 ~几 MB |
| SFTP 批量传输 100 个文件 | 线程池排队，或手动 NIO | 每个传输一个虚拟线程，自然并发 |
| 终端读取阻塞 | 需要 NIO/异步改造 | 直接 `inputStream.read()`，虚拟线程自动挂起 |
| 等待 SSH 握手/认证 | 占用平台线程 | 虚拟线程挂起，不占 OS 线程 |

**核心收益：代码可以用同步阻塞风格写（简单易维护），性能却是异步的。**

## 3. 架构设计

### 3.1 整体架构图

```
┌─────────────────────────────────────────────────────────┐
│                    JavaFX Application Thread             │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌────────┐ │
│  │ MainView │  │ TabPane  │  │ SftpUI   │  │ Dialog │ │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬───┘ │
│       │              │              │              │      │
│       └──────────────┼──────────────┼──────────────┘      │
│                      │  Platform.runLater()              │
└──────────────────────┼───────────────────────────────────┘
                       │
         ┌─────────────┼─────────────────────┐
         │     TaskExecutor (虚拟线程池)       │
         │  Executors.newVirtualThreadPerTaskExecutor()    │
         └─────────────┼─────────────────────┘
                       │
    ┌──────────────────┼──────────────────────┐
    │                  │                      │
┌───┴───┐        ┌────┴────┐           ┌─────┴─────┐
│SSH I/O│        │SFTP I/O │           │  DB I/O   │
│虚拟线程│        │虚拟线程  │           │  虚拟线程  │
│read() │        │upload() │           │  query()  │
│write()│        │download()│          │           │
└───┬───┘        └────┬────┘           └─────┬─────┘
    │                 │                      │
    ▼                 ▼                      ▼
 SSHJ API          SSHJ SFTP              JDBC API
 (blocking)        (blocking)            (blocking)
```

### 3.2 线程模型

```
┌─────────────────────────────────────────────────────┐
│              线程模型总览                              │
├─────────────────────────────────────────────────────┤
│                                                     │
│  JavaFX Application Thread (1个)                     │
│  ├── 所有 UI 渲染和事件处理                            │
│  ├── 不做任何 I/O 操作                                │
│  └── 通过 Platform.runLater() 接收后台更新            │
│                                                     │
│  Virtual Thread Executor (全局1个)                    │
│  ├── Executors.newVirtualThreadPerTaskExecutor()     │
│  ├── SSH 连接/断开/重连                               │
│  ├── 终端 I/O 读写循环                                │
│  ├── SFTP 上传/下载/列目录                             │
│  └── 数据库读写                                      │
│                                                     │
│  Platform Thread Pool (1个, 固定大小)                 │
│  └── 桥接层：虚拟线程 → UI 线程 (Platform.runLater)   │
│                                                     │
└─────────────────────────────────────────────────────┘
```

**关键原则**：
1. 虚拟线程做所有 I/O，UI 线程只做渲染
2. 虚拟线程不需要池化（创建成本极低，用完即销毁）
3. 从虚拟线程更新 UI 必须走 `Platform.runLater()`

### 3.3 模块划分

```
com.raindrop/
├── RaindropApp.java                  # JavaFX Application 入口
├── core/                             # 连接核心
│   ├── SshSession.java               # 单个 SSH 会话（在虚拟线程中运行）
│   ├── SftpService.java              # SFTP 操作（每个操作一个虚拟线程）
│   ├── ConnectionManager.java        # 管理所有活跃会话
│   ├── KeyLoader.java                # 加载私钥文件
│   └── TaskExecutor.java             # 虚拟线程执行器（全局单例）
├── terminal/                         # 终端（唯一 backend: JediTermFX）
│   ├── SshTtyConnector.java          # SSHJ Shell → JediTermFX TtyConnector 适配
│   ├── RaindropSettingsProvider.java # 主题/字体注入 JediTermFX 默认设置
│   ├── RaindropJediTermFxWidget.java # 项目自有 JediTermFxWidget 子类（右键菜单预热等）
│   ├── RaindropTerminalPanel.java    # 项目自有 TerminalPanel 子类
│   └── TerminalTheme.java            # 主题定义
├── credential/                       # 凭证管理
│   ├── CredentialManager.java        # 统一凭证存储/读取/加密/删除
│   ├── CredentialEntry.java          # 凭证 POJO
│   └── KeyImporter.java             # 密钥文件导入、解析、存储
├── storage/                          # 持久化
│   ├── DatabaseManager.java          # SQLite 初始化、迁移
│   ├── ConnectionProfile.java        # 连接配置 POJO
│   └── ProfileRepository.java        # CRUD 操作
├── ui/                               # 界面（FXML + Controller）
│   ├── MainView.java / MainController.java
│   ├── SessionListPane.fxml / SessionListPaneController.java   # 左侧已保存连接列表
│   ├── TabManager.java
│   ├── ConnectionDialog.fxml / ConnectionDialogController.java
│   ├── CredentialDialog.fxml / CredentialDialogController.java
│   ├── SftpBrowser.fxml / SftpBrowserController.java
│   ├── QuickConnectBar.fxml / QuickConnectBarController.java
│   └── SettingsView.fxml / SettingsViewController.java
└── util/
    ├── CryptoUtil.java
    └── ConfigManager.java
```

## 4. 虚拟线程核心实现模式

### 4.1 TaskExecutor — 虚拟线程执行器

```java
public class TaskExecutor {
    private static final ExecutorService executor =
        Executors.newVirtualThreadPerTaskExecutor();

    // 提交 SSH 连接任务（虚拟线程，阻塞 I/O 无压力）
    public static void submit(Runnable task) {
        executor.submit(task);
    }

    // 提交有返回值的任务
    public static <T> Future<T> submit(Callable<T> task) {
        return executor.submit(task);
    }

    // 从虚拟线程安全地更新 UI
    public static void runOnFx(Runnable fxTask) {
        Platform.runLater(fxTask);
    }
}
```

### 4.2 SSH 会话 — 虚拟线程中的阻塞读写

```java
public class SshSession {
    private SSHClient client;
    private Session channel;
    private volatile boolean connected = false;

    // 连接 — 在虚拟线程中执行
    public CompletableFuture<Void> connect(ConnectionProfile profile) {
        return CompletableFuture.supplyAsync(() -> {
            client = new SSHClient();
            client.addHostKeyVerifier(new PromiscuousVerifier());
            client.connect(profile.getHost(), profile.getPort());
            // 认证...
            client.authPassword(profile.getUsername(), decrypt(profile.getPassword()));
            channel = client.startShell();
            connected = true;
            return null;
        }, TaskExecutor.get());
    }

    // 终端读取循环 — 虚拟线程天然适合
    public void startReading(TerminalEmulator emulator) {
        TaskExecutor.submit(() -> {
            InputStream in = channel.getInputStream();
            byte[] buf = new byte[4096];
            while (connected) {
                int n = in.read(buf);  // 阻塞 — 虚拟线程自动挂起，零开销
                if (n == -1) break;
                byte[] data = Arrays.copyOf(buf, n);
                TaskExecutor.runOnFx(() -> emulator.processInput(data));  // 更新 UI
            }
        });
    }

    // 终端写入 — 同样在虚拟线程
    public void write(String command) {
        TaskExecutor.submit(() -> {
            OutputStream out = channel.getOutputStream();
            out.write(command.getBytes(StandardCharsets.UTF_8));
            out.flush();
        });
    }
}
```

### 4.3 SFTP — 每个传输独立虚拟线程

```java
public class SftpService {
    // 列目录 — 一个虚拟线程
    public CompletableFuture<List<SftpEntry>> listDirectory(SshSession session, String path) {
        return CompletableFuture.supplyAsync(() -> {
            SFTPClient sftp = session.newSFTPClient();
            try {
                return sftp.ls(path).stream()
                    .map(attrs -> new SftpEntry(attrs))
                    .collect(Collectors.toList());
            } finally {
                sftp.close();
            }
        }, TaskExecutor.get());
    }

    // 上传文件 — 一个虚拟线程 + 进度回调
    public CompletableFuture<Void> upload(SshSession session,
                                          File local, String remotePath,
                                          ProgressListener progress) {
        return CompletableFuture.runAsync(() -> {
            SFTPClient sftp = session.newSFTPClient();
            try {
                sftp.put(new LocalSourceFile(local), remotePath,
                    new TransferMonitor() {
                        public void reportProgress(long transferred, long total) {
                            TaskExecutor.runOnFx(() ->
                                progress.onProgress(transferred, total));
                        }
                    });
            } finally {
                sftp.close();
            }
        }, TaskExecutor.get());
    }

    // 批量下载 — 每个文件一个虚拟线程，自然并发
    public void batchDownload(SshSession session, List<String> remoteFiles,
                              File localDir, ProgressListener progress) {
        List<CompletableFuture<Void>> futures = remoteFiles.stream()
            .map(remote -> download(session, remote, localDir, progress))
            .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenRunAsync(() ->
                TaskExecutor.runOnFx(() -> progress.onAllComplete()), TaskExecutor.get());
    }
}
```

### 4.4 数据库 — 同样在虚拟线程中

```java
public class ProfileRepository {
    public CompletableFuture<List<ConnectionProfile>> findAll() {
        return CompletableFuture.supplyAsync(() -> {
            // JDBC 是阻塞的 — 在虚拟线程中完全没问题
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT * FROM connection_profile");
                 ResultSet rs = ps.executeQuery()) {
                List<ConnectionProfile> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
                return list;
            }
        }, TaskExecutor.get());
    }
}
```

## 5. 数据库设计

```sql
-- 连接配置
CREATE TABLE connection_profile (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    name          TEXT NOT NULL,
    host          TEXT NOT NULL,
    port          INTEGER DEFAULT 22,
    auth_type     TEXT DEFAULT 'credential',
    credential_id INTEGER,
    username      TEXT,
    password      TEXT,
    key_path      TEXT,
    key_pass      TEXT,
    group_name    TEXT DEFAULT '默认',
    encoding      TEXT DEFAULT 'UTF-8',
    created_at    TEXT,
    updated_at    TEXT,
    FOREIGN KEY (credential_id) REFERENCES credential(id)
);

-- 统一凭证表
CREATE TABLE credential (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT NOT NULL,
    type        TEXT NOT NULL,              -- 'password' | 'key'
    username    TEXT NOT NULL,
    password    TEXT,                       -- 加密后的密码
    key_data    TEXT,                       -- 私钥内容（加密存储）
    key_path    TEXT,                       -- 原始密钥路径（仅供参考）
    key_pass    TEXT,                       -- 加密后的密钥口令
    created_at  TEXT,
    updated_at  TEXT
);

-- 应用设置
CREATE TABLE app_setting (
    key   TEXT PRIMARY KEY,
    value TEXT
);
```

## 6. 核心流程

### 6.0 主界面布局（含会话列表侧边栏）

```
┌──────────────────────────────────────────────────────────────┐
│ ToolBar: [Quick Connect] [New] [Credentials] [Disconnect All]│
├──────────────┬───────────────────────────────────────────────┤
│ Session      │                                               │
│ Sidebar      │              TabPane (终端)                    │
│ (TreeView)   │                                               │
│              │   Tab1  Tab2  Tab3  ...                       │
│ ▼ 默认       │                                               │
│   • prod-01  │   (当前 Tab 内容：TerminalPane)                │
│   • test-02  │                                               │
│ ▼ 生产        │                                               │
│   • db-master│                                               │
├──────────────┴───────────────────────────────────────────────┤
│ Status: Ready — 3 active connections                         │
└──────────────────────────────────────────────────────────────┘
```

**Session Sidebar 交互**：
- **双击条目** → 打开新终端 Tab 连接该会话（对应 `MainController.openConnection(profile)`）
- **右键条目 → Connect**：新开 Tab 连接
- **右键条目 → Edit**：打开 ConnectionDialog 编辑，保存后自动刷新列表
- **右键条目 → Delete**：删除（需确认）
- **顶部小按钮 → Refresh**：手动刷新（`onSave` 后也会自动触发）
- **按 group_name 分组**：TreeView 顶层节点是分组名，叶子节点是 ConnectionProfile
- **启动时**：`MainController.initialize()` → `SessionListPaneController.refresh()` → `ProfileRepository.findAll()` → 填充 TreeView

### 6.1 SSH 连接流程（虚拟线程版本）

```
用户点击连接
  → TabManager 创建空标签页 + TerminalPane
  → TaskExecutor.submit(() -> {                          ← 虚拟线程
      SshSession session = new SshSession();
      session.connect(profile);                           ← 阻塞 I/O，虚拟线程挂起
      session.startReading(emulator);                     ← 另一个虚拟线程持续读取
      TaskExecutor.runOnFx(() -> {                        ← 回到 UI 线程
          tab.setConnected(true);
      });
    });
```

### 6.2 SFTP 文件浏览

```
用户打开 SFTP
  → TaskExecutor.submit(() -> {                          ← 虚拟线程
      List<SftpEntry> files = sftpService.listDirectory(session, "/");  ← 阻塞
      TaskExecutor.runOnFx(() -> {                        ← 回到 UI 线程
          remotePanel.setItems(files);
      });
    });
```

### 6.3 批量文件传输

```
用户选择 50 个文件拖拽上传
  → 每个文件一个虚拟线程，自然并发
  → TransferMonitor 回调 → Platform.runLater() 更新进度条
  → 所有完成后通知 UI
```

## 7. 技术难点与方案

| 难点 | 方案 |
|---|---|
| 虚拟线程 + UI 线程交互 | 统一 `TaskExecutor.runOnFx()` 桥接，所有 UI 更新走 `Platform.runLater()` |
| 终端转义序列解析 | VT100 状态机在虚拟线程中处理，结果推送到 UI |
| SSHJ 是否兼容虚拟线程 | SSHJ 用阻塞 I/O，天然兼容。注意：SSHJ 内部可能用线程池做心跳，需测试 |
| 多标签页隔离 | 每个 Tab 独立 TerminalPane + SshSession + 虚拟线程，互不干扰 |
| 连接断开重连 | 虚拟线程检测到 IOException → 通知 UI → 弹出重连对话框 |
| 密码安全存储 | Jasypt + 随机 salt 加密 |
| 中文/UTF-8 编码 | SSHJ `StandardCodecs.UTF8()` |
| 大文件传输 | 虚拟线程中阻塞式传输，TransferMonitor 回调更新 UI |
| 跨平台打包 | jpackage 生成 .msi/.dmg/.deb |

## 8. 项目结构（Gradle）

```
raindrop/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/
├── src/
│   └── main/
│       ├── java/com/raindrop/
│       │   ├── RaindropApp.java
│       │   ├── core/
│       │   ├── terminal/
│       │   ├── credential/
│       │   ├── storage/
│       │   ├── ui/
│       │   └── util/
│       ├── resources/
│       │   ├── fxml/
│       │   ├── css/
│       │   └── icons/
│       └── module-info.java
└── src/test/
```

## 9. 分阶段实施

| 阶段 | 内容 | 产出 |
|---|---|---|
| P1 | 项目骨架 + 虚拟线程基础设施 + 单 SSH 连接 + 基础终端 | 能连上一台机器打命令 |
| P2 | 多标签页 + 连接配置保存/编辑 | 多个终端并发 |
| P3 | 完善终端（颜色、resize、复制粘贴） | 体验接近 XShell |
| P4 | SFTP 双面板 + 批量传输 | 文件管理能力 |
| P5 | 主题/设置/快捷键/密钥管理 | 完整产品 |
| P6 | 打包分发 + 打磨 | 可安装使用 |

## 10. 打包方案

使用 `org.beryx.runtime` Gradle 插件（1.13.1），封装 JDK 21 的 `jlink` + `jpackage`，无需 `module-info.java`（automatic module 模式）。

关键 task：
- `gradle runtime` — 生成裁剪后的运行时镜像到 `build/image/`（含 `bin/raindrop` 启动脚本）
- `gradle jpackageImage` — 生成免安装 app image 到 `build/jpackage/Raindrop/`
- `gradle jpackage` — 按当前 OS 生成安装包：
  - Linux → `.deb` 到 `build/jpackage/*.deb`
  - macOS → `.dmg`
  - Windows → `.msi`

选中的 JDK 模块见 `build.gradle.kts` 的 `runtime.modules`。

## 11. 终端组件（JediTermFX 单一 backend）

**决策**：终端 backend 只保留 [Techsenger JediTermFX](https://github.com/techsenger/jeditermfx)（JediTerm 的 JavaFX 移植版），获得工业级的 xterm 兼容、24-bit/256 色、Canvas 渲染、真选区、光标定位、resize、vim/htop 全屏 TUI 支持。旧的 TextFlow 手写 backend（`TerminalPane`/`BasicTerminalEmulator`/`TerminalBuffer`/`TerminalAttr`/`TerminalEmulator`）已删除。

**坐标 / 仓库**：
- `com.techsenger.jeditermfx:jeditermfx-ui:1.1.0`（Maven Central 发布）
- 传递依赖 kotlin-stdlib、pty4j、annotations、slf4j-api、javafx-{controls,base,graphics}
- 需要 `maven { url = uri("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies") }` 才能解析 `org.jetbrains.pty4j:*`

**集成点**：
- `SshTtyConnector implements com.techsenger.jeditermfx.core.TtyConnector`：
  - `read(char[], off, len)` 走 `InputStreamReader(shell.getInputStream(), charset)`
  - `write(byte[])` / `write(String)` 走 `shell.getOutputStream()`
  - `resize(TermSize)` 调 SSHJ `Session.Shell.changeWindowDimensions(cols, rows, 0, 0)`
  - `waitFor()` 阻塞在 `CountDownLatch`，`close()` 释放
- `RaindropSettingsProvider extends DefaultSettingsProvider`：override `getDefaultForeground/Background`（从 TerminalTheme 取），`getTerminalFont` / `getTerminalFontSize`（从 ConfigManager 取）
- `TabManager.openTab`：
  1. 主线程 new `JediTermFxWidget(120, 40, provider)`，先塞占位 Label
  2. 虚拟线程 `SshSession.connect()` 起 shell
  3. 回 UI 线程 `widget.setTtyConnector(new SshTtyConnector(...))` → `widget.start()` → `tab.setContent(widget.getPane())`
- Tab 关闭时先 `widget.close()`，再 `session.disconnect()`

**废弃**：TextFlow backend 及其手写 VT100/CSI 状态机全部删除，不再保留切换开关。`ConfigManager` 已移除 `KEY_TERMINAL_BACKEND` / `BACKEND_JEDITERMFX` / `BACKEND_TEXTFLOW`。

## 12. 启动时读取 Settings

- `RaindropApp.start` — 从 `ConfigManager` 读取 `WINDOW_WIDTH / WINDOW_HEIGHT`（默认 1200×800）作为 Scene 尺寸，主窗口 `onCloseRequest` 时把当前窗口尺寸回写数据库。
- `TabManager.openTabJediTermFx` — 从 `ConfigManager` 读取 `TERMINAL_THEME` / `FONT_SIZE`；编码优先取 `profile.encoding`，未设置时回退到 `ConfigManager.KEY_DEFAULT_ENCODING`（默认 UTF-8）。主题字符串 → `TerminalTheme` 枚举映射。
- 修改设置后需要打开新终端 Tab 才会应用（现有 Tab 保留旧值，符合 SettingsView 的提示语）。

## 13. 国际化（i18n）

### 13.1 目标

支持三种语言：
- 简体中文（zh-CN）
- 繁体中文（zh-TW）
- 英文（en-US）

### 13.2 技术选型

| 组件 | 选型 | 理由 |
|------|------|------|
| 资源格式 | JSON | 原生支持 Unicode，层级结构易维护 |
| 解析库 | Jackson | 项目已有依赖，性能稳定 |
| 管理器 | 自定义 I18nManager | 单例模式，支持占位符替换、监听器模式 |

### 13.3 文件结构

```
src/main/resources/i18n/
├── messages_zh_CN.json    # 简体中文
├── messages_zh_TW.json    # 繁体中文
└── messages_en_US.json    # 英文
```

JSON 文件采用层级结构：
```json
{
  "common": {
    "ok": "确定",
    "cancel": "取消",
    "save": "保存"
  },
  "main": {
    "title": "Raindrop SSH Manager",
    "status_ready": "就绪 - {count} 个活跃连接"
  }
}
```

### 13.4 I18nManager API

**位置**：`com.raindrop.util.I18nManager`

```java
// 静态快捷方法（最常用）
I18nManager.t("common.ok");                    // "确定"
I18nManager.t("main.status_ready",             // "就绪 - 3 个活跃连接"
    "count", "3");

// 完整 API
I18nManager.getInstance().setLanguage("en_US");
String lang = I18nManager.getInstance().getLanguage();
Map<String, String> langs = I18nManager.getInstance().getSupportedLanguages();
```

### 13.5 语言切换策略

- 语言选择在 **设置面板** 中提供
- 选择后持久化到 `app_setting` 表（key: `language`）
- **当前策略**：切换语言后提示用户重启应用生效
- **优化方向**：后续可实现监听器模式，动态刷新已打开的界面

### 13.6 翻译规范

**键命名**：`模块.具体项`，全小写下划线分隔

| 模块 | 前缀 | 示例 |
|------|------|------|
| 通用 | `common.` | `common.ok`, `common.delete` |
| 主窗口 | `main.` | `main.title`, `main.status_ready` |
| 连接对话框 | `connection_dialog.` | `connection_dialog.name` |
| 会话列表 | `session_list.` | `session_list.delete_confirm` |
| 凭证管理 | `credential_dialog.` | `credential_dialog.title` |
| 设置面板 | `settings.` | `settings.language` |
| 错误消息 | `errors.` | `errors.connection_failed` |
| 状态消息 | `status.` | `status.connected` |
| 锁定界面 | `lock.` | `lock.title` |
| SFTP 浏览器 | `sftp.` | `sftp.title` |

**占位符**：使用 `{name}` 格式，支持运行时替换：
```java
I18nManager.t("session_list.delete_confirm", "name", "prod-01");
// 输出: "确定要删除 'prod-01' 吗？"
```

**术语一致性**：

| 英文术语 | 简体中文 | 繁体中文 |
|----------|----------|----------|
| Connection | 连接 | 連接 |
| Session | 会话 | 會話 |
| Credential | 凭证 | 憑證 |
| Profile | 配置 | 設定檔 |
| Encoding | 编码 | 編碼 |
| Host | 主机 | 主機 |
| Port | 端口 | 連接埠 |

### 13.7 启动流程

```
RaindropApp.start()
  ↓
I18nManager.getInstance() 初始化
  ├─ 从 ConfigManager 读取 language 设置
  ├─ 若无设置 → 检测系统语言：
  │   ├─ zh_CN / zh_TW / zh_HK → 对应中文
  │   └─ 其他 → 英文
  └─ 加载对应的 JSON 资源文件到内存缓存
```

### 13.8 性能考虑

- 语言文件按需加载，缓存到 `ConcurrentHashMap`
- 切换语言时只重新加载目标语言文件
- 单例模式，全局共享，无重复初始化开销

### 13.9 回退机制

- key 不存在时返回 key 本身（如 `unknown.key` → 显示 `unknown.key`）
- 无效语言代码抛出 IllegalArgumentException
- JSON 文件读取失败使用 RuntimeException 包装（启动时快速失败）
