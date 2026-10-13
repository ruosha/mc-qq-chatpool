# 开发笔记

这个文件记录**开发这个插件时的环境约定与踩坑**，公开使用插件不需要它，可以随时删除。
下面用 `<PAPER_ROOT>` 指代本机的 Paper 26.2 开发工作区目录（服务端、离线 JDK、Gradle 缓存都在那里）。

## 典型工作区布局

```
<PAPER_ROOT>\
├─ server\                 服务端运行目录，plugins\ 是部署目标
└─ tools\
   ├─ jdk25.0.4_10\        Java 25（离线，服务端与编译都用它）
   ├─ maven-3.9.9\         本地 Maven（maven 分支用）
   ├─ maven-repo\          Maven 本地仓库（-Dmaven.repo.local，避免写 ~/.m2）
   ├─ maven-home\          Maven wrapper 的发行版缓存（MAVEN_USER_HOME）
   ├─ gradle-9.8.1\        main 分支用的本地 Gradle
   └─ gradle-home\         Gradle 依赖缓存（预下载 paper-api，可离线构建）
```

本机 `%USERPROFILE%\.m2`、`%USERPROFILE%\.gradle` 都**不可写**（受限环境），
所以两个构建系统都把缓存指到工作区里：Maven 用 `-Dmaven.repo.local=` 与 `MAVEN_USER_HOME`，
Gradle 用 `GRADLE_USER_HOME`。

## 构建（本分支 = Maven）

仓库里提交的 `build.bat` 是**可移植版**：自动找 JDK 25（含 `..\tools\jdk25.0.4_10`）、
用包内 Maven wrapper。在开发机上跑之前按需设置环境变量：

```bat
set "JAVA_HOME=<PAPER_ROOT>\tools\jdk25.0.4_10"
set "MCQQ_DEPLOY_DIR=<PAPER_ROOT>\server\plugins"
build.bat nopause
```

受限环境下要额外指定 Maven 的仓库/缓存位置：

```bat
set "MAVEN_USER_HOME=<PAPER_ROOT>\tools\maven-home"
mvnw.cmd -Dmaven.repo.local=<PAPER_ROOT>\tools\maven-repo package
```

作者的机器上另外留了一个 `build-local.bat`（不提交，在 `.gitignore` 里），
把上面几个路径写死，双击即可编译并部署。

## 端到端测试

`scripts\chatpool-e2e-test.ps1` 会：构建 → 临时开 RCON → 启动服务端 → 跑 `/chatpool` 命令 →
打全部 HTTP 接口并断言状态码 → 优雅关服 → 还原 `server.properties`。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\chatpool-e2e-test.ps1 `
  -ServerDir <PAPER_ROOT>\server `
  -JavaExe   <PAPER_ROOT>\tools\jdk25.0.4_10\bin\java.exe
```

脚本参数（`-ServerDir` 必填）：`-PluginDir`、`-JavaExe`、`-ServerJar`、`-JarPath`、`-BuildScript`、
`-RconPort`、`-RconPassword`、`-HttpBase`、`-HttpToken`、`-StartupTimeoutSeconds`、`-SkipBuild`。

其中 `-BuildScript` 默认是仓库里的可移植 `build.bat`；作者本机上工具链在仓库外，所以传
`-BuildScript .\build-local.bat`（脚本构建环节会执行 `<BuildScript> nopause`）。

会检查的前置条件：目标端口（游戏端口、插件 HTTP 端口、RCON 端口）必须空闲，
否则直接报错退出——**不能在已有服务端运行时跑**，否则探测结果会被那个实例污染。

实测（Paper 26.2 构建 132 + JDK 25）：脚本共 **32 项断言**全部通过
（3 项启动就绪 + 1 项 RCON 登录 + 6 项 `/chatpool` 命令 + 20 项 HTTP + 2 项落盘），
其中 HTTP 部分覆盖鉴权 401/200、增量拉取与来源过滤、404/400/405/413 错误码与 limit 夹取；
关服后 `state.yml` 正常落盘。

## 与 AstrBot 侧联调时踩过的坑

这些是真实踩到的，改插件时注意：

1. **平台 ID 不是适配器名**：AstrBot 里平台实例的 ID 由用户在 WebUI 里自己起，
   实测那台机器上 aiocqhttp 实例的 ID 就叫 `default`。AstrBot 侧插件里写死 `aiocqhttp`
   会得到 `cannot find platform for session`，消息静默发不出去 → 已改成 `platform_id: auto` 自动探测。
2. **重启后的连接空窗**：AstrBot 一重启，NapCat 的反向 WebSocket 要等约 30 秒才重连，
   这期间发送会抛 `ApiNotAvailable` → 已改成「挂起不消耗重试次数，连上后自动补发」。
3. **Docker Desktop 能访问宿主机回环端口**：AstrBot 跑在 Docker 里时 `mc_base_url` 用
   `http://host.docker.internal:<port>` 即可，**不需要**把插件的 `http.host` 改成 `0.0.0.0`
   （Linux 上的 Docker 才需要）。
4. **`.bat` 文件必须纯 ASCII**：cmd.exe 用 OEM 代码页解析批处理，UTF-8 中文会把脚本解析坏。
   中文说明放在 README / 这个文件里。
5. **JSON 手写解析要注意**：插件不引入 Gson 等库，`http/Json.java` 是自己实现的构造器 + 递归下降解析器，
   改动时记得同步更新 README 里记录的返回结构。
