# Changelog

本文件记录每个版本的变更。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [1.0.0] - 2026-10-12

首个版本，Paper 26.2（构建 132）+ Java 25 实测通过。

### 新增

- **聊天池（ChatPool）**：每个池是一条独立频道，拥有自增 ID 的环形消息缓冲与订阅玩家集合；
  只有订阅了某个池的玩家才会在聊天栏看到它的消息。
- **游戏内命令** `/chatpool`（别名 `/mcpool`、`/mcchat`、`/cpool`）：
  `say`、`send <池> <文本>`、`list`、`join`、`leave`、`info`、`history`、`who`、`reload`，
  带 Tab 补全、权限节点与玩家发言限速。
- **HTTP 接口**（默认 `127.0.0.1:8123`）：
  - `GET /api/v1/health` 概况
  - `GET /api/v1/pools` 聊天池列表
  - `GET /api/v1/messages` 增量（`since`）/ 尾部拉取，支持 `source` / `exclude_source` 过滤
  - `POST /api/v1/messages` 把外部消息注入聊天池并广播给池内在线玩家
  - `GET /api/v1/players` 池内订阅玩家
- **鉴权与限流**：Bearer token（请求头或 `?token=`）、每分钟注入上限、请求体与消息长度上限。
- **持久化** `state.yml`：历史消息尾巴、全局 ID 游标、玩家订阅关系；关服与每
  `save-interval-seconds` 秒落盘，重启后 ID 继续递增、不重复。
- **可选行为**：进服自动订阅（`default-join`）、原版聊天捕获（`capture-vanilla-chat`）、
  按需自动建池（`auto-create-pool`）。
- **文案与格式**：`messages` / `formats` 两段全部可用 MiniMessage 覆盖；
  占位符替换后的内容会被转义，玩家无法注入点击/悬浮事件。
- **端到端测试** `scripts/chatpool-e2e-test.ps1`：构建 → 临时开 RCON → 起服 →
  跑 `/chatpool` 命令 → 断言全部 HTTP 接口 → 优雅关服 → 校验 `state.yml` → 还原 `server.properties`。
- **CI**：GitHub Actions 构建 + 打 tag 时自动创建 Release 并附上 jar。

### 说明

- 运行时零第三方依赖：只用 Paper API 与 JDK 自带的 `com.sun.net.httpserver`，
  JSON 读写为自带实现（`http/Json.java`）。
- Folia 兼容：所有交互 Bukkit 的操作都经 `GlobalRegionScheduler` 回到主线程。

[1.0.0]: https://github.com/ruosha/mc-qq-chatpool/releases/tag/v1.0.0
