# Release Notes — Raindrop v1.1.0

Raindrop 是一款基于 JavaFX 的跨平台 SSH/SFTP 桌面管理工具，对标 XShell / MobaXterm。

---

## 本次更新 (v1.1.0)

### 🐛 修复：远程 vim 键位映射错误

**问题**：SSH 连接到远程服务器后运行 `vim`，键位行为完全错乱：

- **Delete 键变成切换大小写**（而不是删除字符）
- **方向键 ↑↓←→ 无法移动光标**（插入 `^[A` / `^[B` / `^[C` / `^[D` 字面量）
- **PageUp / PageDown 不翻页**

同类问题也会影响 `less`、`htop`、`fzf` 等其他全屏 TUI 程序。

**根因**：Raindrop 之前调用 SSHJ 的 `Session.allocateDefaultPTY()` 分配 PTY，而该方法**硬编码申请 `TERM=vt100`**（80×24）。`vt100` 的 terminfo 条目不包含 `\e[3~`（Delete）、`\e[A`（↑）、`\e[5~`（PageUp）等现代 xterm 键码，因此远端 `vim` 把这些转义序列拆成多个普通字符来解释——`\e[3~` 被读成 `^[` + `[` + `3` + `~`，其中 `~` 正是 vim normal mode 的「切换光标处字符大小写」命令。

**修复**：`SshSession.connect()` 改为显式申请 `xterm-256color`：

```java
session.allocatePTY("xterm-256color", 0, 0, 0, 0, Collections.emptyMap());
session.setEnvVar("COLORTERM", "truecolor");
```

同时设置 `COLORTERM=truecolor`，让远端 `vim` 的 `set termguicolors` 能启用 24-bit 真彩色（之前只能拿到 256 色回退）。

> **注意**：极简服务器镜像（Alpine / debian-slim / distroless）可能缺少 `xterm-256color` 的 terminfo 条目。如果修复后 vim 仍报 `E558: Terminal entry not found in terminfo`，需在服务器端安装：
> - Debian / Ubuntu：`apt install ncurses-term`
> - RHEL / CentOS：`yum install ncurses`
> - Alpine：`apk add ncurses-terminfo`

### ✨ 新增：会话列表「复制连接」

在会话列表中右键任意已保存的连接，菜单新增 **复制**（Duplicate）项，位于 连接 / 编辑 / 删除 之间。

- 点击后打开连接对话框，所有字段自动填充源连接的内容
- 名称自动追加 `- 副本` 后缀，方便与原连接区分
- 保存时**插入新记录**而非更新源连接
- 加密的密码 / 密钥口令会一并沿用，无需重新输入

用于快速创建同一台主机的多个配置（不同端口、不同用户、不同编码），或以现有连接为模板批量新建。

### 🔧 补全：三语 i18n 键

补上此前代码已引用但资源文件缺失的 3 个 key（缺失时界面会显示 `connection_dialog.copy_suffix` 这类原始 key 字符串）：

| Key | English | 简体中文 | 繁體中文 |
|---|---|---|---|
| `connection_dialog.title_duplicate` | Duplicate Connection | 复制连接 | 複製連線 |
| `connection_dialog.copy_suffix` | Copy | 副本 | 副本 |
| `session_list.duplicate` | Duplicate | 复制 | 複製 |

并新增回归测试 `I18nManagerTest.testDuplicateConnectionKeysResolveInAllLanguages`，逐语言校验这些 key 能正确解析（不回退为 key 本身），防止未来漏改资源文件。

### 测试

75 个单元测试全部通过（新增 1 个 i18n 回归测试）。

---

## 核心功能

### SSH 终端
- 多标签页并发 SSH 会话管理
- 基于 JediTermFX 的工业级终端仿真（xterm 兼容、24-bit 色、Canvas 渲染）
- PTY 申请 `xterm-256color` + `COLORTERM=truecolor`，远端 vim / less / htop 键位与配色正常
- 虚拟线程驱动的 I/O，万级并发无压力
- 快速连接栏：在主工具栏内直接输入主机信息一键连接

### SFTP 文件管理
- 双面板文件浏览器（本地 + 远程）
- 拖拽上传/下载
- 批量传输，每个文件独立虚拟线程并行
- 传输进度回调与显示
- 远程目录创建、删除、重命名

### 凭证管理
- 统一凭证库：密码和 SSH 私钥集中管理
- 连接配置可引用共享凭证，一个凭证支持多个主机
- 三种认证方式：共享凭证、内联密码、内联密钥
- 私钥内容加密存储（不仅保存路径），即使删除原始文件也不丢失
- SSH 密钥文件导入，自动检测 OpenSSH/PKCS8 格式

### 主密码与自动锁定
- 主密码保护所有存储的凭证和密码
- 空闲自动锁定（可配置超时时间）
- PBKDF2 派生密钥 + PBEWithHMACSHA256AndAES_256 加密
- 锁定状态下完全屏蔽快捷键和 UI 操作
- 支持破坏性重置（遗忘主密码时）

### 安全
- Jasypt 加密：密码、私钥内容、密钥口令均加密存储
- 密码使用 PasswordField，不在 UI 中明文显示
- 异常信息中不暴露密码
- 渐进式加密迁移：旧数据在下次保存时自动升级为强加密

## 界面

### 主题
- 4 套内置主题：Dark、Light、Solarized Dark、Green-on-Black
- 主题实时切换，无需重启
- 状态栏内存监控（Timeline 驱动，智能缓存避免不必要的 CSS 重算）
- 内置 Sarasa Mono SC 等宽 CJK 字体，安装包环境下中文不再显示豆腐块

### 国际化
- 三语支持：简体中文、繁体中文、English
- JSON 资源格式，模块化 key 管理
- 覆盖所有 7 个核心 Controller 的 UI 文本
- JediTermFX 右键菜单自动翻译（10 个菜单项）

### 连接管理
- 连接配置分组（默认分组 + 自定义分组）
- 树形会话列表，支持筛选和搜索
- 连接 / 编辑 / **复制** / 删除 一键操作
- 连接状态实时显示
- 断线后可原地重连（复用同一标签页）

## 技术架构

| 层 | 选型 | 版本 |
|---|---|---|
| 语言 | Java 21 LTS | 虚拟线程 GA |
| GUI | JavaFX + FXML | 21 |
| SSH/SFTP | SSHJ | 0.40.0 |
| 终端 | JediTermFX | 1.1.0 |
| 数据库 | SQLite | 3.46.1.3 |
| 加密 | Jasypt | 1.9.3 |
| JSON | Jackson Databind | 2.18.2 |
| 构建 | Gradle | 8.5 (Kotlin DSL) |
| 安装包 | jpackage | .msi / .dmg / .deb |

### 架构特点
- 虚拟线程全 I/O 覆盖：SSH 读写、SFTP 传输、数据库操作均在虚拟线程中执行
- SQLite WAL 模式 + busy_timeout，支持高并发读写
- SFTPClient 懒加载缓存（double-checked locking），避免每次操作重新握手
- ConcurrentHashMap 缓存 + NULL_SENTINEL 模式，线程安全的配置读写
- 模态对话框兼容 Linux WM（UTILITY stage + alwaysOnTop，避免 KDE/KWin 取消最大化）
- ArchUnit 架构约束测试：禁止自建线程池、禁止 HashMap 字段、禁止 `Files.readAllLines`、SFTP 客户端仅可由 `SshSession` 创建

## 开源文档

- `README.md` — 项目介绍、功能列表、构建说明、项目结构、技术栈
- `AGENTS.md` — AI 辅助开发指南：架构图、7 个扩展点、7 个常见任务的 prompt 模板
- `CONTRIBUTING.md` — 开发环境搭建、代码规范、提交流程
- `CODE_OF_CONDUCT.md` — 社区行为准则
- `.github/` — Issue / PR 模板

## 测试

- 17 个测试类 / 75 个测试，覆盖核心业务流程
- 测试使用独立的 SQLite 文件数据库（`build/test-tmp/`），不污染生产数据
- SSH 集成测试标记 `@Tag("integration")`，需真实服务器，CI 默认跳过
- 所有测试必须通过后方可发布

## 已知限制

- JediTermFX 第三方库的搜索对话框暂不支持国际化
- 主密码切换后需要重新连接所有会话
- 首次启动需要设置主密码
- 设置项（主题 / 字号 / 编码）的变更只对**新打开**的标签页生效
- 极简服务器镜像可能缺少 `xterm-256color` terminfo，需服务器端安装 ncurses 补全包

## 构建与运行

```bash
# 需要 JDK 21+
export JAVA_HOME=/path/to/jdk-21

# 构建
./gradlew build

# 运行
./gradlew run

# 运行测试
./gradlew test

# 创建安装包
./gradlew jpackage
```

## 许可证

Apache License 2.0
