# Release Notes — Raindrop v0.1.0

Raindrop 是一款基于 JavaFX 的跨平台 SSH/SFTP 桌面管理工具，对标 XShell / MobaXterm。

## 核心功能

### SSH 终端
- 多标签页并发 SSH 会话管理
- 基于 JediTermFX 的工业级终端仿真（xterm 兼容、24-bit 色、Canvas 渲染）
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

### 国际化
- 三语支持：简体中文、繁体中文、English
- JSON 资源格式，模块化 key 管理
- 覆盖所有 7 个核心 Controller 的 UI 文本
- JediTermFX 右键菜单自动翻译（9 个菜单项）

### 连接管理
- 连接配置分组（默认分组 + 自定义分组）
- 树形会话列表，支持筛选和搜索
- 连接/断开/重连一键操作
- 连接状态实时显示

## 技术架构

| 层 | 选型 | 版本 |
|---|---|---|
| 语言 | Java 21 LTS | 虚拟线程 GA |
| GUI | JavaFX + FXML | 21.0.2 |
| SSH/SFTP | SSHJ | 0.40.0 |
| 终端 | JediTermFX | 1.1.0 |
| 数据库 | SQLite | 3.42.0.0 |
| 加密 | Jasypt | 1.9.3 |
| 构建 | Gradle | 8.5 (Kotlin DSL) |
| 安装包 | jpackage | .msi / .dmg / .deb |

### 架构特点
- 虚拟线程全 I/O 覆盖：SSH 读写、SFTP 传输、数据库操作均在虚拟线程中执行
- SQLite WAL 模式 + busy_timeout，支持高并发读写
- SFTPClient 懒加载缓存（double-checked locking），避免每次操作重新握手
- ConcurrentHashMap 缓存 + NULL_SENTINEL 模式，线程安全的配置读写
- 模态对话框兼容 Linux WM（UTILITY stage + alwaysOnTop，避免 KDE/KWin 取消最大化）

## 开源文档

- `README.md` — 项目介绍、功能列表、构建说明、项目结构、技术栈
- `AGENTS.md` — AI 辅助开发指南：架构图、7 个扩展点、7 个常见任务的 prompt 模板
- `CONTRIBUTING.md` — 开发环境搭建、代码规范、提交流程
- `CODE_OF_CONDUCT.md` — 社区行为准则
- `.github/` — Issue / PR 模板

## 测试

- 16 个测试类，覆盖核心业务流程
- 测试使用独立的 SQLite 文件数据库（`build/test-tmp/`），不污染生产数据
- 所有测试必须通过后方可发布

## 已知限制

- JediTermFX 第三方库的搜索对话框暂不支持国际化
- 主密码切换后需要重新连接所有会话
- 首次启动需要设置主密码

## 构建与运行

```bash
# 需要 JDK 21+
export JAVA_HOME=/path/to/jdk-21

# 构建
./gradlew build

# 运行
./gradlew run

# 创建安装包
./gradlew jpackage
```

## 许可证

Apache License 2.0
