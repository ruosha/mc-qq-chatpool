# McQqChatPool — Minecraft ↔ 聊天服务「聊天池」桥接（Paper 26.2 插件）

把服务器上的聊天开放成本机 HTTP 接口：**游戏内消息按「聊天池」排队，外部程序轮询拉走**（转发到 QQ 群、
Discord、Telegram 等由你决定），**外部程序也能把消息注入回来**，由服务器广播给池内在线的玩家。

```
   游戏内玩家 ──/chatpool send <池> <文本>──┐
                                          ▼
                                 ┌──────────────────┐
                                 │   聊天池 ChatPool   │   自增 ID 环形缓冲 + 订阅玩家
                                 │   global / tech …  │
                                 └────────┬─────────┘
                    外部程序每 N 秒 GET    │        ▲   POST /api/v1/messages
            /api/v1/messages?since=<游标>  │        │   （把群聊消息送进游戏）
                                          ▼        │
                                   转发到 QQ 群 / 其它平台
```

* 每条消息有**全局自增 ID**，外部程序用 `since=<上次游标>` 增量拉取，不重复也不漏。
* 支持多个**聊天池**（频道），玩家自己订阅；池可以只用于特定话题。
* 零第三方运行时依赖：只用 Paper API + JDK 自带的 HTTP 服务器。

---

## 1. 环境要求

| 项目 | 要求 |
|---|---|
| 服务端 | **Paper 26.2**（构建 132 已验证；同大版本其它构建应兼容） |
| Java | **Java 25**（Paper 26.x 的硬性要求） |
| 编译（可选） | 想自己改代码才需要；JDK 25 即可，Gradle 由包内 wrapper 自动准备 |

## 2. 安装

1. 把 `mc-qq-chatpool-1.0.0.jar` 复制到服务端的 `plugins/` 目录。
2. 启动（或重启）服务器。首次启动会生成 `plugins/McQqChatPool/config.yml`，日志里出现：

   ```
   [McQqChatPool] 已加载 2 个聊天池：tech, global
   [McQqChatPool] HTTP 接口已启动：http://127.0.0.1:8123/api/v1/health（已启用 token 校验）
   ```

3. **先设置访问令牌**：编辑 `plugins/McQqChatPool/config.yml`

   ```yaml
   http:
     host: "127.0.0.1"     # 只给本机程序用就保持回环地址
     port: 8123
     token: "换成一段随机字符串"   # ← 必填，例如 openssl rand -hex 16
   ```

   然后在控制台执行 `chatpool reload`（或重启服务器）。**token 留空等于不校验**，
   本机任何进程都能往服务器注入消息，插件启动时会打 WARN 提醒。

4. 客户端（转发程序）用同一个 token 调用接口，见 [第 5 节](#5-客户端对接)。

---

## 3. 游戏内命令

主命令 `/chatpool`，别名 `/mcpool`、`/mcchat`、`/cpool`；单独敲 `/chatpool` 等价于 `/chatpool help`。

| 命令 | 作用 | 权限（默认） |
|---|---|---|
| `/chatpool help` | 显示用法 | `mcqqchatpool.use` (true) |
| `/chatpool list` | 列出所有聊天池（在线 / 订阅 / 历史条数） | `mcqqchatpool.use` |
| `/chatpool say <文本>` | 发到默认聊天池 | `mcqqchatpool.send` (true) |
| `/chatpool send <池> <文本>` | 发到指定聊天池 | `mcqqchatpool.send` |
| `/chatpool join <池>` | 订阅一个池，之后能看到这个池的消息 | `mcqqchatpool.use` |
| `/chatpool leave <池>` | 退订 | `mcqqchatpool.use` |
| `/chatpool info <池>` | 池的详情（人数、游标、各项开关） | `mcqqchatpool.use` |
| `/chatpool history <池> [条数]` | 查看最近消息（默认 10，最多 50） | `mcqqchatpool.history` (true) |
| `/chatpool who <池>` | 池内在线成员 | `mcqqchatpool.use` |
| `/chatpool reload` | 重载配置并重开 HTTP 接口 | `mcqqchatpool.reload` (op) |

**订阅是可见性的唯一依据**：只有订阅了某个池的玩家，才会在聊天栏看到这个池的消息。
`default-join: true` 的池会在玩家进服时自动订阅（由 `chat.auto-join-default-pools` 控制）。

反刷屏保护：`chat.max-message-length`（单条长度上限）、`chat.min-message-interval-ms`（同一玩家发言间隔）。

---

## 4. HTTP 接口

默认 `http://127.0.0.1:8123`，只监听本机回环地址。

**鉴权**：设置了 `http.token` 时，请求必须带以下之一，否则返回 `401`：

```
Authorization: Bearer <token>
X-Auth-Token: <token>
?token=<token>
```

### `GET /api/v1/health`

```json
{"ok":true,"plugin":"McQqChatPool","version":"1.0.0","server_time":"2026-10-12T14:02:50Z",
 "online_players":0,"max_players":20,"next_message_id":7,"default_pool":"global",
 "pools":["global","tech"]}
```

### `GET /api/v1/pools`

```json
{"ok":true,"count":2,"pools":[
  {"name":"global","display_name":"全服","description":"…","default_join":true,
   "capture_vanilla_chat":false,"allow_from_qq":true,"allow_from_mc":true,
   "max_history":300,"history":6,"members":1,"online":1,"cursor":7}]}
```

### `GET /api/v1/messages` — 拉取消息（转发程序用这个）

| 参数 | 说明 |
|---|---|
| `pool` | 池名，省略时用 `chat.default-pool` |
| `since` | **带上 = 增量模式**：只返回 `id > since` 的消息；**不带 = 尾部模式**：返回最近 `limit` 条 |
| `limit` | 条数，默认 `history.poll-default-limit`（50），上限 `history.poll-max-limit`（200） |
| `source` | 只保留这些来源（逗号分隔：`mc`、`qq`、`console`、`system`） |
| `exclude_source` | 排除这些来源。**建议传 `qq`**，这样从外部注入的消息不会再被拉回去，避免回环刷屏 |
| `token` | 令牌（也可以放请求头） |

```json
{"ok":true,"pool":"global","mode":"incremental","since":0,"cursor":7,"pool_cursor":7,
 "total":7,"oldest_id":1,"count":2,"has_more":false,
 "messages":[
   {"id":6,"pool":"global","source":"mc","sender":"Steve","sender_id":"<玩家UUID>",
    "content":"有人一起打末影龙吗","time":"2026-10-12T14:14:02.927Z","time_ms":1791814442927}]}
```

* `cursor` 是**可以安全保存的游标**：增量模式下没有新消息时它等于你传入的 `since`，
  不会跳过被你排除来源的消息。
  尾部模式（不带 `since`）下它是**最后一条返回消息的 ID**；若过滤后一条都没有，则取该池当前的最大 ID
  （`pool_cursor`），也就是「已经同步到最新位置」的意思——首次同步就该这样用。
* `has_more` 为 `true` 表示还有积压，可以立刻再拉一次。它**只在增量模式下计算**，尾部模式恒为 `false`。
* `total` 是该池当前保留的消息条数，`oldest_id` 是最早一条（仍保留的）消息 ID。这两个字段配合
  `since` 就能界定「全部聊天记录」的范围：从 `oldest_id` 开始拉、拉到 `total` 条为止。
* `pool_cursor` 是该池当前最大 ID，仅供展示。

**怎么读取全部聊天记录**：服务端把 `history.retain-all` 设为 `true`（每个池保留 `retain-all-limit` 条，
默认 5 万，此时池自己的 `max-history` 被忽略）；客户端用 `since=0&limit=200` 循环拉取，
每轮把返回的 `cursor` 当作下一轮的 `since`，直到 `has_more` 为 `false`。
AstrBot 侧对应的能力是配置项 `read_all_history`（首次同步就回放全部历史）和 `#mc all` 命令（手动导出）。

### `POST /api/v1/messages` — 注入一条外部消息（群聊 → 游戏）

```json
{"pool":"global","sender":"群友小张","sender_id":"10001","content":"大家好","source":"qq"}
```

```json
{"ok":true,"id":7,"pool":"global","source":"qq","delivered":1,"online_in_pool":1}
```

`delivered` 是在游戏内实际收到这条消息的在线玩家数。

### `GET /api/v1/players?pool=global`

```json
{"ok":true,"pool":"global","members":1,"online":1,
 "players":[{"uuid":"…","name":"Steve","online":true}]}
```

### 错误码

| 状态 | 场景 |
|---|---|
| `400` | 请求体不是合法 JSON / `content` 为空 / 池名不合法 |
| `401` | 令牌缺失或不匹配 |
| `403` | 该池设置了 `allow-from-qq: false`；或外部注入声明了不在 `security.allowed-external-sources` 里的 `source`；或内容被安全过滤拦截（`error` 里带原因） |
| `404` | 路径不存在，或池未定义且 `chat.auto-create-pool: false` |
| `405` | 方法不对 |
| `413` | 请求体超过 `http.max-body-bytes`，或内容超过 `chat.max-message-length` |
| `429` | 超过 `http.max-inject-per-minute` 注入频率 |
| `500` | 插件内部错误（例如写广播时抛出异常），日志里会有堆栈 |
| `504` | 服务器未能在 8 秒内处理完注入 |

错误体统一为 `{"ok":false,"error":"…"}`（`500` 也一样）。

---

## 5. 客户端对接

任何能发 HTTP 的程序都行。下面是一个最小 Python 示例（标准库，无依赖）：启动时跳到最新位置，
之后每 5 秒拉一次新消息并转发，同时提供注入的半边。

```python
import json, time, urllib.parse, urllib.request

BASE, TOKEN = "http://127.0.0.1:8123", "你的token"
POOL, GROUP = "global", "把消息发到这里"

def call(method, path, params=None, body=None):
    url = BASE + path + ("?" + urllib.parse.urlencode(params) if params else "")
    data = json.dumps(body).encode() if body else None
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Authorization": f"Bearer {TOKEN}",
                                          "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=8) as resp:
        return json.loads(resp.read())

# 首次：只取游标，不回放历史
cursor = call("GET", "/api/v1/messages", {"pool": POOL, "limit": 1})["cursor"]

while True:
    data = call("GET", "/api/v1/messages",
                {"pool": POOL, "since": cursor, "limit": 50, "exclude_source": "qq"})
    for msg in data["messages"]:
        send_to_your_chat(f"[MC] {msg['sender']}: {msg['content']}")   # 你的转发逻辑
    cursor = data["cursor"]          # 保存它，重启后从这儿继续
    time.sleep(5)
```

反过来（群里有新消息时）：

```python
call("POST", "/api/v1/messages",
     body={"pool": "global", "sender": "群友小张", "sender_id": "10001",
           "content": "你好", "source": "qq"})
```

要点：

* **首次同步**不要带 `since`（尾部模式）拿到 `cursor` 存起来，避免把服务器里的旧消息一次性刷出去。
* 拉取时带 `exclude_source=qq`，否则你注入的消息会被再拉回来，形成回环。
* 游标要**持久化**；重启后从上次的 `cursor` continue 即可。

---

## 6. 配置说明（`plugins/McQqChatPool/config.yml`）

```yaml
http:
  enabled: true
  host: "127.0.0.1"          # 只给本机程序用就保持回环；对外暴露务必配合强 token
  port: 8123
  token: ""                  # 必填
  max-body-bytes: 65536
  max-inject-per-minute: 60  # 外部注入限流
  log-requests: false        # 排错时可打开

chat:
  default-pool: "global"
  auto-join-default-pools: true
  auto-create-pool: false    # 是否允许使用未定义的池名
  notify-console: true       # 池内消息同时打到服务器控制台
  max-message-length: 300
  min-message-interval-ms: 800
  join-hint: true

history:
  persist: true              # 记录与订阅关系写入 state.yml
  save-interval-seconds: 300
  poll-default-limit: 50
  poll-max-limit: 200
  default-max-history: 200
  retain-all: false          # 不裁剪历史（「读取全部聊天记录」的总开关，见 6.3）
  retain-all-limit: 50000    # 不裁剪时每个池的保留上限（安全阀，防止内存无限增长）
  save-max-messages: 2000    # state.yml 每个池最多写多少条（内存里可以留更多）

# 安全：阻止敏感操作（见 6.4）
security:
  # 外部 HTTP 注入允许声明的 source 白名单。默认只允许 qq —— 否则外部程序可以
  # 谎报 console / mc 来伪造控制台或玩家身份。写空列表 [] 表示不限制（不建议）。
  allowed-external-sources: [ "qq" ]
  filter:
    enabled: true
    action: "reject"          # reject 拒绝 | censor 打码后放行 | log 只记日志（先观察）
    block-command-like: true  # 拦截以 / 或 !! 开头的内容
    block-control-chars: true # 拦截换行、制表符、§ 颜色符、零宽字符等
    blocked-keywords: [ "op", "deop", "ban", "ban-ip", "pardon", "kick",
                        "whitelist", "gamemode", "execute", "sudo" ]   # 整词匹配
    max-links: 0              # 允许的最大链接数，0 = 不限制
    apply-to-game-chat: false # 是否同样过滤游戏内发言（默认只过滤外部注入）

pools:
  global:
    display-name: "全服"
    description: "全服公共聊天池，所有人都能看到"
    default-join: true             # 玩家进服自动订阅
    max-history: 300               # 内存中保留多少条
    capture-vanilla-chat: false    # 打开后原版聊天栏内容也进这个池
    allow-from-qq: true            # 是否允许外部注入
    allow-from-mc: true            # 是否允许游戏内发言
  tech:
    display-name: "技术"
    default-join: false
    max-history: 200

formats:      # MiniMessage；可用占位符 {id} {pool} {source} {sender} {sender_id} {content} {time}
  qq-to-mc:    "<gray>[</gray><aqua>{pool}</aqua><gray>|</gray><gold>QQ</gold><gray>]</gray> <white>{sender}</white><gray> » </gray><white>{content}</white>"
  mc-to-mc:    "…"
  console-to-mc: "…"

messages:     # 所有提示文案，同样是 MiniMessage，可随意改
  prefix: "<gray>[</gray><aqua>聊天池</aqua><gray>]</gray> "
```

**池名规则**：只能用小写字母、数字、下划线和短横线，且以字母或数字开头，最长 32 字符
（输入会统一转成小写，所以 `?pool=GLOBAL` 与 `/chatpool send GLOBAL 文本` 都能用）。
原因是池名同时是配置键、HTTP 参数和 `/chatpool send <池> <文本>` 里的命令标识，
统一成 ASCII 标识符最省心。展示名 `display-name` 随便写中文。
配置里写了不合法的池名会在启动日志里警告并跳过；接口传不合法池名返回 `400`，
命令里用引号写（`/chatpool send "中文池" x`）会得到「聊天池名不合法」的友好提示。

**取值范围**：几个数值配置会被自动夹取——`chat.max-message-length` 1–4096、
`http.max-body-bytes` ≥1024、`history.save-interval-seconds` ≥10、
`history.poll-default-limit` 1–1000、`history.poll-max-limit` 不超过 2000 且不小于前者、
池的 `max-history` ≥1、接口的 `limit` 参数同样按上面两条夹取。

### 6.3 读取全部聊天记录（`history.retain-all`）

默认每个池按自己的 `max-history`（默认 200~300 条）裁剪，接口只能拉到这段尾巴。
把 `history.retain-all` 设为 `true` 之后：

* 每个池实际保留 `history.retain-all-limit` 条（默认 50000，最大 1000000），此时池自己的
  `max-history` 被忽略，启动/重载日志里会写明；
* 接口可以从 `since=0` 开始分页拉取全部历史，响应里的 `total` / `oldest_id` 帮你界定范围；
* `history.save-max-messages`（默认 2000）只限制写进 `state.yml` 的条数，避免状态文件无限膨胀
  —— 重启后最多恢复这么多条。

代价是内存占用随保留条数增长（一条消息约几百字节，5 万条约十几 MB）。`/chatpool info <池>`
里的「历史保留上限」显示的就是实际值。

### 6.4 阻止敏感操作（`security`）

三道防线：

1. **来源白名单** `security.allowed-external-sources`（默认 `[ "qq" ]`）：`POST /api/v1/messages`
   里客户端自带的 `source` 必须在这个列表里，否则 `403`。不设这道闸，任何能访问接口的程序都能
   谎报 `console` / `mc` 来伪造控制台或玩家身份。
2. **内容过滤** `security.filter`：依次检查控制字符 → 命令样式（`/`、`!!` 开头）→ 敏感词（**整词**匹配，
   默认 `op` / `ban` / `kick` / `gamemode` 等 10 个，可用 `blocked-keywords` 增删）→ 链接数量，
   命中后按 `action` 处置：`reject` 丢弃（接口回 `403` 并说明原因）、`censor` 把命中片段替换成
   `***` 后放行、`log` 放行但记日志（先用来观察会拦到什么，再收紧）。
3. **桥接内容永远不会被当作命令执行**：消息只会被广播成聊天，插件里没有任何
   `dispatchCommand` 之类的调用，过滤器只是第二道防线。

`apply-to-game-chat: true` 时游戏内玩家的发言也会过一遍过滤，命中就不写进聊天池（原版聊天照常显示，
发言的玩家会收到一条提示）；默认 `false`，只过滤外部注入。

**转义**：占位符替换后的内容会被 MiniMessage 转义，玩家无法用 `<click:…>` 之类的标签注入样式或点击事件。

运行时文件：

| 文件 | 说明 |
|---|---|
| `plugins/McQqChatPool/config.yml` | 配置，改完用 `/chatpool reload` 或重启生效 |
| `plugins/McQqChatPool/state.yml` | 聊天记录尾巴、ID 游标、玩家订阅；关服与每 `save-interval-seconds` 秒落盘 |

---

## 7. 自己编译

需要 **JDK 25**（Gradle 由包内 wrapper 自动准备）。Windows 上一键：

```bat
build.bat
```

脚本会：自动定位 JDK 25 → 用 wrapper 编译 → 若找到 `..\server\plugins` 就顺手把 jar 复制过去。
常用参数/环境变量：

```bat
build.bat nopause                 :: 供 CI/任务调用，结束时不等按键
set MCQQ_DEPLOY_DIR=D:\mc\plugins :: 指定部署目录（找不到 ..\server\plugins 时用）
set JAVA_HOME=D:\jdk-25           :: 明确指定 JDK
set GRADLE_USER_HOME=D:\gradle    :: 指定 Gradle 缓存目录（默认用用户目录）
```

手动编译：

```bash
./gradlew build          # Linux/macOS
gradlew.bat build        # Windows
# 或已装 Gradle 9.x：
gradle build
```

产物在 `build/libs/mc-qq-chatpool-1.0.0.jar`。

依赖只有 `io.papermc.paper:paper-api:26.2.build.132-stable`（compileOnly）+ Java 25 工具链，
不 shade 任何库——JSON 读写是插件内自己实现的。

---

## 8. 实现要点

| 关注点 | 做法 |
|---|---|
| 线程模型 | HTTP 请求跑在虚拟线程上；所有碰 Bukkit 的动作都经 `GlobalRegionScheduler` 跳回主线程（Folia 同样可用） |
| 异步聊天 | `AsyncChatEvent` 只做「读消息 + 丢回主线程」，不碰任何 Bukkit 状态 |
| 消息缓冲 | 每池一个 `ArrayDeque` + `ReentrantLock` 的环形缓冲；落盘只存尾巴 |
| 游标语义 | 全局自增 ID + `state.yml` 里的 `next-id`，重启后继续递增，外部不会因为 ID 回退而漏消息 |
| 防回环 | 来源标记（`mc`/`qq`/`console`/`system`）+ 接口的 `exclude_source` |
| 无第三方依赖 | JSON 构造器与解析器是自己写的（`http/Json.java`） |

---

## 9. 安全建议

* `http.token` **必须**设置；为空时启动会打 WARN，此时本机任何进程都能注入消息。
* `http.host` 保持 `127.0.0.1`。确实需要跨机访问时，改成内网 IP（不是 `0.0.0.0`）并配合强 token + 防火墙放行。
* 不要把 8123 直接暴露到公网；跨机场景建议走 SSH/内网隧道。
* `chat.max-inject-per-minute` 是第二道闸，防止外部程序失控刷屏。

---

## 10. FAQ

**Q：游戏里发消息了，但群里没收到？**
依次看：`/chatpool list` 池名对不对 → 转发程序的 `since`/`exclude_source` 参数 →
`GET /api/v1/messages?pool=<池>&since=0` 手动确认池里到底有没有消息 → 转发程序日志里有没有 `401`。

**Q：群里发的消息，游戏里看不到？**
接口返回的 `delivered` 为 0，说明当时没有玩家订阅这个池。玩家用 `/chatpool join <池>`，
或把该池设为 `default-join: true` 让玩家重连一次。

**Q：想让所有公开聊天都同步出去，不想让玩家打命令？**
把目标池的 `capture-vanilla-chat` 设为 `true`，原版聊天栏内容就会进池。
命令不会触发聊天事件，所以不会重复。

**Q：服务器重启后 ID 会重复吗？**
不会。`next-id` 存在 `state.yml` 里，重启后从上次的位置继续递增。

**Q：为什么池名不能用中文？**
池名同时是配置键、HTTP 参数和命令标识，统一限制成 ASCII 标识符最稳妥（配置键与 URL 参数
都不适合非 ASCII）。展示名 `display-name` 可以随便写中文。

**Q：接口能被反代/跨容器访问吗（比如客户端跑在 Docker 里）？**
可以。Docker Desktop for Windows 上，容器里用 `http://host.docker.internal:8123` 就能访问宿主机
`127.0.0.1:8123`，不必改 `http.host`。Linux 上的 Docker 通常访问不到宿主机回环端口，
那时把 `http.host` 改成宿主机内网 IP（或 `0.0.0.0` 并配合强 token）。

**Q：能改消息显示的样式吗？**
可以，`formats` 段是 MiniMessage，支持 `<gradient>`、`<hover>` 等；`messages` 段是全部提示文案。

---

## 11. 已验证环境

Paper **26.2 构建 132** + JDK 25（Amazon Corretto 25.0.4），2026-10-12 在真实服务器上端到端验证。
`scripts/chatpool-e2e-test.ps1` 会自己构建、起服、跑命令并断言全部 HTTP 行为，**48 项断言 0 失败**：

| 项目 | 结果 |
|---|---|
| 插件加载 | `已加载 2 个聊天池：tech, global`、`HTTP 接口已启动…（已启用 token 校验）` |
| 命令 | `plugins / help / list / send / info / history / who / reload` 输出正常；非法池名给出友好提示 |
| 鉴权 | 无 token、错误 token → `401`；正确 token（请求头或查询参数）→ `200` |
| 增量拉取 | `since` 游标推进正确；`exclude_source=qq` 能正确过滤注入消息 |
| 注入 | `POST /messages` 返回新 ID 与 `delivered` 人数；在线玩家实时收到广播 |
| 错误码 | 脚本断言了 `400 / 401 / 403 / 404 / 405 / 413`；`429`（超过注入频率）与 `500`（内部错误）有实现但脚本没构造这两种场景 |
| 阻止敏感操作 | `source=console` / `source=mc` → `403`；`/op Steve`（命令样式）、`please give me op now`（敏感词）、带 `§` 的内容 → `403`，且被拒内容确认没有进池 |
| 全部聊天记录 | 响应带 `total` / `oldest_id`；`retain-all` 关闭时 `max-history: 5` 只留 5 条，打开后 8 条全留，`/chatpool info` 显示实际保留上限与过滤动作 |
| 持久化 | 关服后 `state.yml` 写入历史尾巴、ID 游标与订阅关系 |

## 12. 版本

**1.0.0** — 首个版本：聊天池、`/chatpool` 命令树、HTTP 接口（健康检查/池列表/增量拉取/注入/成员）、
`state.yml` 持久化、MiniMessage 文案与格式、可选的进服自动订阅与原版聊天捕获。

---

Paper 是 PaperMC 团队的项目（服务端 GPL-3.0 / API MIT）；Minecraft 是 Mojang Studios 的商标。
