# Raindrop SSH Manager - 代码生成约束文档

> 本文档是 MiMoCode 的代码生成约束，所有生成的代码必须遵守本文档规范。

## 0. 代码检索规则（最高优先级）

**检索本项目代码时，必须优先使用 CodeGraph MCP（服务名 `codegraph-raindrop`），而不是直接 Grep / Glob / Read。**

- 项目已 `codegraph init`，索引位于 `.codegraph/`，覆盖全部 Java / Kotlin / properties 文件（816 nodes / 1570 edges）
- MCP 服务在全局配置 `~/.config/mimocode/mimocode.jsonc` 的 `mcp.codegraph-raindrop`
- **查找符号 / 引用 / 调用关系** → 用 codegraph 的 `query` / `explore` / `refs` 等工具
- **仅在 codegraph 覆盖不到时才回落**到 Grep / Glob / Read：
  - 非源码文本（`doc/*.md` / `*.fxml` / `*.gradle.kts` / SQL 里的关键字）
  - 需要按行号精确读原文
  - codegraph 未索引的语言（本项目仅 Java/Kotlin/properties）
- 编辑代码前若刚做过结构性改动，先 `codegraph sync /home/cooper/raindrop` 再检索，避免用旧索引推理

## 1. 技术栈

| 层 | 选型 | 版本 | 说明 |
|---|---|---|---|
| 语言 | Java | 21 LTS | 必须使用虚拟线程、record、sealed、pattern matching 等新特性 |
| 并发 | 虚拟线程 | - | I/O 操作必须在虚拟线程中执行，禁止使用固定线程池 |
| GUI | JavaFX + FXML | 21.0.2 | 布局用 FXML，逻辑在 Controller 中 |
| SSH/SFTP | SSHJ | 0.40.0 | groupId 必须是 `com.hierynomus`；Java 包名仍是 `net.schmizz.sshj.*`（上游未改包名），直接 import 无碍 |
| 数据库 | SQLite | 3.42.0.0 | 嵌入式，零配置 |
| 加密 | Jasypt | 1.9.3 | 密码/密钥加密存储 |
| 构建 | Gradle | 8.5 | Kotlin DSL |
| 测试 | JUnit 5 | 5.10.1 | 必须覆盖核心业务流程 |

## 2. 功能模块

```
com.raindrop/
├── core/           # 连接核心
│   ├── TaskExecutor.java      # 虚拟线程执行器（全局单例）
│   ├── SshSession.java        # SSH 会话封装
│   ├── ConnectionManager.java # 多会话管理
│   └── SftpService.java       # SFTP 操作
├── terminal/       # 终端模拟
│   ├── TerminalEmulator.java      # 接口
│   ├── BasicTerminalEmulator.java # 基础实现
│   └── TerminalBuffer.java        # 屏幕缓冲区
├── credential/     # 凭证管理
│   ├── CredentialManager.java # CRUD
│   └── CredentialEntry.java   # POJO
├── storage/        # 持久化
│   ├── DatabaseManager.java   # 连接管理
│   ├── ProfileRepository.java # 连接配置 CRUD
│   └── ConnectionProfile.java # POJO
├── ui/             # 界面
│   ├── MainController.java / MainView.fxml
│   ├── TerminalTab.java
│   ├── ConnectionDialogController.java / ConnectionDialog.fxml
│   ├── QuickConnectBarController.java / QuickConnectBar.fxml
│   └── ...
└── util/           # 工具
    ├── CryptoUtil.java        # 加解密
    └── ConfigManager.java     # 配置管理
```

## 3. 数据库设计

```sql
-- 连接配置
CREATE TABLE connection_profile (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    name          TEXT NOT NULL,
    host          TEXT NOT NULL,
    port          INTEGER DEFAULT 22,
    auth_type     TEXT DEFAULT 'credential',  -- 'credential' | 'key_inline' | 'password_inline'
    credential_id INTEGER,
    username      TEXT,
    password      TEXT,                       -- 加密后的密码
    key_path      TEXT,
    key_pass      TEXT,                       -- 加密后的密钥口令
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
    key_path    TEXT,
    key_pass    TEXT,
    created_at  TEXT,
    updated_at  TEXT
);

-- 应用设置
CREATE TABLE app_setting (
    key   TEXT PRIMARY KEY,
    value TEXT
);
```

## 3. 主要业务流程

### 3.1 SSH 连接流程

```
用户点击连接
  │
  ├─→ MainController.openConnection(profile)
  │     │
  │     ├─→ 创建 TerminalTab（包含 TextArea + BasicTerminalEmulator）
  │     │
  │     └─→ TaskExecutor.submit(() -> {          ← 虚拟线程
  │           │
  │           ├─→ SshSession.connect(profile)
  │           │     ├─→ SSHClient.connect(host, port)
  │           │     ├─→ authPassword / authPublickey
  │           │     ├─→ session.allocateDefaultPTY()
  │           │     └─→ session.startShell()
  │           │
  │           ├─→ SshSession.startReading(emulator)  ← 虚拟线程持续读取
  │           │     └─→ shell.getInputStream().read()  → emulator.processInput()
  │           │
  │           └─→ Platform.runLater(() -> tab.setConnected(true))
  │         })
  │
  └─→ 用户输入
        └─→ TaskExecutor.submit(() -> session.write(char))  ← 虚拟线程
```

### 3.2 连接配置保存流程

```
用户填写连接信息
  │
  ├─→ ConnectionDialogController.onSave()
  │     ├─→ 验证必填字段（name, host, username）
  │     ├─→ 创建 ConnectionProfile 对象
  │     ├─→ CryptoUtil.encrypt(password)  ← 加密密码
  │     └─→ ProfileRepository.save(profile)  ← 写入 SQLite
  │
  └─→ MainController.updateStatus("Connection saved")
```

### 3.3 凭证管理流程

```
用户打开凭证管理
  │
  ├─→ 新建凭证
  │     ├─→ 选择类型（password / key）
  │     ├─→ 输入用户名 + 密码/选择密钥文件
  │     ├─→ CryptoUtil.encrypt() 加密
  │     └─→ CredentialManager.save(entry)
  │
  ├─→ 编辑凭证
  │     ├─→ CredentialManager.findById(id)
  │     ├─→ 修改字段
  │     └─→ CredentialManager.update(entry)
  │
  └─→ 删除凭证
        ├─→ 检查是否有连接在引用
        └─→ CredentialManager.delete(id)
```

### 3.4 SFTP 文件传输流程

```
用户打开 SFTP 浏览器
  │
  ├─→ SftpService.listDirectory(client, path)
  │     └─→ CompletableFuture.supplyAsync(() -> {
  │           SFTPClient sftp = client.newSFTPClient();
  │           return sftp.ls(path);
  │         }, TaskExecutor.getExecutor())
  │
  └─→ 用户上传文件
        └─→ SftpService.upload(client, localFile, remotePath)
              └─→ CompletableFuture.runAsync(() -> {
                    SFTPClient sftp = client.newSFTPClient();
                    sftp.put(localFile, remotePath);
                  }, TaskExecutor.getExecutor())
```

## 4. 测试要求

### 4.1 测试覆盖要求

| 模块 | 必须测试的类 | 测试用例数 |
|---|---|---|
| core | TaskExecutor | ≥3（submit Runnable/Callable、并发任务） |
| terminal | TerminalBuffer | ≥5（写入、换行、清屏、光标、退格） |
| terminal | BasicTerminalEmulator | ≥4（处理输入、清屏、获取内容、获取行） |
| util | CryptoUtil | ≥5（加密解密、null/empty、中文、不同密文） |
| storage | DatabaseManager | ≥2（获取连接、表存在） |
| storage | ProfileRepository | ≥4（CRUD 全流程） |
| credential | CredentialManager | ≥5（CRUD + 密钥凭证） |

### 4.2 测试规范

```java
// 1. 类名：{被测类}Test
// 2. 方法名：test{方法名}{场景}
// 3. 必须使用 @BeforeEach 清理状态
// 4. 断言必须明确，禁止无意义断言

@Test
public void testEncryptDecrypt() {
    String plainText = "my-secret-password";
    String encrypted = CryptoUtil.encrypt(plainText);
    String decrypted = CryptoUtil.decrypt(encrypted);

    assertNotEquals(plainText, encrypted);  // 加密后不等于原文
    assertEquals(plainText, decrypted);     // 解密后等于原文
}
```

### 4.3 运行测试

```bash
export JAVA_HOME=/home/cooper/MySoft/jdk-21.0.11+10
/home/cooper/MySoft/gradle-8.5/bin/gradle test
```

**所有测试必须通过，禁止跳过任何测试。**

## 5. 代码规范

### 5.1 命名规范

| 类型 | 规范 | 示例 |
|---|---|---|
| 类 | PascalCase，名词 | `SshSession`, `TerminalBuffer` |
| 方法 | camelCase，动词 | `connect()`, `processInput()` |
| 常量 | UPPER_SNAKE_CASE | `MAX_BUFFER_SIZE` |
| 变量 | camelCase | `connectionProfile`, `sshClient` |
| 包 | 全小写 | `com.raindrop.core` |
| FXML | PascalCase | `MainView.fxml`, `ConnectionDialog.fxml` |

### 5.2 方法规范

```java
// 1. 方法长度不超过 30 行
// 2. 单一职责，一个方法只做一件事
// 3. 参数超过 3 个用对象封装
// 4. 返回值明确，禁止返回 null（用 Optional）

// 好
public Optional<ConnectionProfile> findById(long id) throws SQLException {
    // ...
}

// 禁止
public ConnectionProfile findById(long id) throws SQLException {
    // ... return null;
}
```

### 5.3 异常处理

```java
// 1. I/O 操作必须处理异常
// 2. 禁止空 catch 块
// 3. 虚拟线程中的异常必须通知 UI

try {
    session.connect(profile);
} catch (IOException e) {
    TaskExecutor.runOnFx(() -> {
        showError("Connection failed: " + e.getMessage());
    });
}
```

### 5.4 虚拟线程规范

```java
// 1. 所有 I/O 操作必须在虚拟线程中执行
// 2. 禁止使用 Executors.newFixedThreadPool()
// 3. UI 更新必须走 Platform.runLater()

// 正确
TaskExecutor.submit(() -> {
    session.connect(profile);  // 阻塞 I/O
    TaskExecutor.runOnFx(() -> tab.setConnected(true));
});

// 禁止
new Thread(() -> session.connect(profile)).start();
```

### 5.5 FXML 规范

```xml
<!-- 1. Controller 命名：{ViewName}Controller -->
<!-- 2. fx:id 命名：camelCase -->
<!-- 3. 事件处理：on{Event} -->

<TextField fx:id="hostField" promptText="hostname"/>
<Button text="Connect" onAction="#onConnect"/>
```

### 5.6 文件组织

```
src/main/java/com/raindrop/    # 源码
src/main/resources/fxml/        # FXML 布局
src/main/resources/css/         # 样式
src/main/resources/icons/       # 图标
src/test/java/com/raindrop/    # 测试
doc/                            # 设计文档
CLAUDE.md                       # 本文件
```

## 6. 构建与运行

```bash
# 设置 JDK 21
export JAVA_HOME=/home/cooper/MySoft/jdk-21.0.11+10

# 构建
/home/cooper/MySoft/gradle-8.5/bin/gradle build

# 运行测试
/home/cooper/MySoft/gradle-8.5/bin/gradle test

# 运行应用
/home/cooper/MySoft/gradle-8.5/bin/gradle run
```

## 7. 禁止事项

1. **禁止** 依赖旧版 SSHJ artifact（Maven 坐标必须是 `com.hierynomus:sshj:0.40.0`；SSHJ 上游未改 Java 包名，源码里 `import net.schmizz.sshj.*` 属正常，请勿因此重构）
2. **禁止** 使用固定线程池（newFixedThreadPool）
3. **禁止** 在 JavaFX Application Thread 中执行 I/O
4. **禁止** 返回 null（用 Optional）
5. **禁止** 空 catch 块
6. **禁止** 跳过测试
7. **禁止** 方法超过 30 行
8. **禁止** 使用 `Thread.sleep()` 等待（用 CountDownLatch 或 CompletableFuture）

## 8. 错误处理模式

### 8.1 SSH 连接断开处理

```java
// SshSession.startReading() 中检测断开
try {
    while (connected) {
        int n = in.read(buf);
        if (n == -1) break;  // 远端关闭
    }
} catch (IOException e) {
    connected = false;
    TaskExecutor.runOnFx(() -> {
        // 通知 UI 显示断开提示
        tab.showDisconnected("Connection lost: " + e.getMessage());
    });
}
```

### 8.2 重连机制

```java
// 用户点击重连时
public void reconnect(ConnectionProfile profile, TerminalTab tab) {
    TaskExecutor.submit(() -> {
        try {
            SshSession newSession = new SshSession(profile);
            newSession.connect();
            // 替换旧会话
            tab.setSession(newSession);
            newSession.startReading(tab.getEmulator());
            TaskExecutor.runOnFx(() -> tab.setConnected(true));
        } catch (IOException e) {
            TaskExecutor.runOnFx(() -> tab.showError("Reconnect failed: " + e.getMessage()));
        }
    });
}
```

## 9. 安全规范

### 9.1 密码存储

```java
// 加密存储
String encrypted = CryptoUtil.encrypt(rawPassword);
profile.setPassword(encrypted);

// 使用时解密
String rawPassword = CryptoUtil.decrypt(profile.getPassword());
```

### 9.2 密钥存储

```java
// 私钥内容加密后存入数据库
String keyContent = Files.readString(Path.of(keyPath));
String encryptedKey = CryptoUtil.encrypt(keyContent);
credential.setKeyData(encryptedKey);

// 使用时解密
String decryptedKey = CryptoUtil.decrypt(credential.getKeyData());
```

### 9.3 禁止事项

- **禁止** 将密码明文写入日志
- **禁止** 在 UI 中明文显示密码（用 PasswordField）
- **禁止** 将密钥文件内容直接存储（必须加密）
- **禁止** 在异常信息中暴露密码

## 10. 编码规范

### 10.1 终端编码

```java
// SSH 连接时指定编码
client.connect(host, port);
// SSHJ 默认使用 UTF-8，如需其他编码：
// client.setCharset(Charset.forName("GBK"));
```

### 10.2 FXML 编码

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- 所有 FXML 文件必须声明 UTF-8 编码 -->
```

## 11. JavaFX 样式规范

### 11.1 CSS 类命名

```css
/* 组件类名：.terminal-pane, .connection-dialog, .sftp-panel */
/* 状态类名：.connected, .disconnected, .error */
/* 主题变量：-fx-primary-color, -fx-background-color */
```

### 11.2 暗色主题（推荐）

```css
.terminal-pane {
    -fx-background-color: #1e1e1e;
    -fx-text-fill: #cccccc;
    -fx-font-family: 'Monospace';
    -fx-font-size: 14px;
}
```

## 12. 测试数据管理

### 12.1 测试数据库隔离

```java
@BeforeEach
void setUp() {
    // 每个测试使用独立的内存数据库
    // 或在测试后清理数据
}

@AfterEach
void tearDown() {
    // 清理测试数据
}
```

### 12.2 测试数据工厂

```java
// 创建测试用 ConnectionProfile
private ConnectionProfile createTestProfile() {
    ConnectionProfile profile = new ConnectionProfile("Test", "localhost", 22, "user");
    profile.setPassword(CryptoUtil.encrypt("test-password"));
    return profile;
}
```

### 12.3 生产数据库隔离（LANDED 2026-07-20）

**测试不得写入生产数据库 `~/.raindrop/raindrop.db`。** `DatabaseManager.getConnection()` 已支持通过系统属性 `raindrop.db.url` 覆盖 JDBC URL。`build.gradle.kts` 的 `test` 任务已设置：

```kotlin
tasks.test {
    val testDb = layout.buildDirectory.file("test-tmp/raindrop-test.db").get().asFile
    val sidecars = listOf(File(...,"-shm"), File(...,"-wal"), File(...,"-journal"))
    doFirst { testDb.delete(); sidecars.forEach { it.delete() } }
    systemProperty("raindrop.db.url", "jdbc:sqlite:${testDb.absolutePath}")
    doLast { testDb.delete(); sidecars.forEach { it.delete() } }
}
```

- **禁止** 在测试里用 `DriverManager.getConnection("jdbc:sqlite:...")` 直连（会绕过 override 污染生产库）
- **禁止** 用 `:memory:` / `file::memory:?cache=shared`：业务代码用 try-with-resources 关连接，`:memory:` 会随最后一个连接消失，导致测试互相影响
- 所有 DB 操作必须走 `DatabaseManager.getConnection()`

## 13. 性能与稳定性规范（LANDED 2026-07-20）

### 13.1 数据库连接管理

- **禁止** 让 `DatabaseManager` 持有共享 Connection 单例。每次 `getConnection()` 必须返回**新** connection，由调用方 try-with-resources 管理。
  - 原因：多线程共享单 conn → SQLite `SQLITE_BUSY` + 事务交错；调用方 close 后单例失效，下次要重跑 `initSchema()` 浪费 CPU。
- **必须** 在每个新 connection 上应用 PRAGMA：
  ```java
  stmt.execute("PRAGMA journal_mode=WAL");    // reader/writer 不互锁
  stmt.execute("PRAGMA busy_timeout=5000");   // 5s 等待锁而非立刻抛
  ```
- **必须** 用 `volatile boolean` + double-checked locking 保证 `initSchema()` 全 JVM 只跑一次。

### 13.2 并发缓存

- **禁止** 用 `HashMap` 做被多线程访问的缓存（`ConfigManager.cache` 是典型例子）。UI 线程 + 虚拟线程同时读写 `HashMap` 可能触发 `ConcurrentModificationException` 或数据损坏。
- **必须** 用 `ConcurrentHashMap`。注意 `ConcurrentHashMap` 不允许 null value —— 需要缓存"SQL NULL"时用 sentinel 字符串（例：`ConfigManager.NULL_SENTINEL`）而不是跳过缓存（跳过缓存会让 null 值每次都触发 DB 查询）。

### 13.3 SSHJ 资源复用

- **必须** 让 `SshSession` 缓存 `SFTPClient`（lazy 创建，double-checked locking），`disconnect()` 时关闭。
- **禁止** 在 `SftpService` 每个方法里 `client.newSFTPClient()`（每次都开新 SFTP subsystem channel，SFTP 浏览器点击时会有明显延迟）。
- `SftpService` 方法签名必须接收 `SshSession`，不是 `SSHClient`——签名边界强制走缓存。

### 13.4 文件读取

- **禁止** 用 `Files.readAllLines(path)` 只为拿第一行/若干行（用户误选 GB 级文件会 OOM）。
- **必须** 用 `BufferedReader.readLine()` 按需读取。参考 `KeyLoader.firstNonBlankLine()`。

### 13.5 定期 UI 刷新（Timeline / KeyFrame）

- **必须** 缓存上次显示值，只在实际变化时调 `setText/setStyle/setProgress`。JavaFX 对 `setText` 即使值相同也会触发 CSS pass，长时间运行下累积开销明显。参考 `MainController.updateMemoryStats`。
- **建议** 定期刷新间隔 ≥ 3 秒（除非用户明确关注实时性）。
- **CSS 档位切换**：不要每帧构造 style string，先分类到"档位"（例：`red / orange / green`），只在档位变化时 setStyle。

### 13.6 模态对话框（JavaFX + Linux WM）

- **禁止** 对 modal Stage 用 `initOwner(mainStage)` + `initModality(APPLICATION_MODAL / WINDOW_MODAL)` —— KDE/KWin (X11) 会因此撤掉主窗口的 `_NET_WM_STATE_MAXIMIZED_*`，导致主窗口从最大化状态跳出。
- **必须** 独立顶层窗口 + `StageStyle.UTILITY` + `setAlwaysOnTop(true)` + `mainRoot.setDisable(true)`（`setOnHidden` 里恢复）模拟 modal 语义。参考 `MainController.showAsModalDialog`。
- **禁止** 用 `ChangeListener<Boolean>` 监听 `Stage.maximizedProperty` 并在 handler 里 `setMaximized(false→true)` —— 会形成无限反弹环路把最大化搞坏。
- `Alert` 需要 owner 时同样注意此陷阱；一般用 `setAlwaysOnTop(true)` + 手动居中足够。

