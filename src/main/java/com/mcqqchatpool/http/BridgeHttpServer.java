package com.mcqqchatpool.http;

import com.mcqqchatpool.McQqChatPoolPlugin;
import com.mcqqchatpool.config.PluginConfig;
import com.mcqqchatpool.pool.ChatMessage;
import com.mcqqchatpool.pool.ChatPool;
import com.mcqqchatpool.pool.ChatPoolManager;
import com.mcqqchatpool.security.MessageFilter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * 内置 HTTP 接口：本机的 NapCat + AstrBot 靠它和服务器交换聊天内容。
 *
 * <pre>
 * GET  /api/v1/health                          健康检查 + 概况
 * GET  /api/v1/pools                           所有聊天池
 * GET  /api/v1/messages?pool=&amp;since=&amp;limit=      拉取新消息（增量）或不带 since 拉取最近若干条
 * POST /api/v1/messages                        把 QQ 消息注入聊天池
 * GET  /api/v1/players?pool=                   池内订阅玩家
 * </pre>
 *
 * <p>鉴权：配置了 token 时，请求必须带 {@code Authorization: Bearer <token>}、
 * {@code X-Auth-Token: <token>} 或查询参数 {@code ?token=<token>}。
 *
 * <p>线程模型：请求由虚拟线程处理；凡是要碰 Bukkit 的操作都通过
 * {@link McQqChatPoolPlugin#callSync} 跳回主线程（Folia 下即全局区域线程）执行。
 */
public final class BridgeHttpServer {

    private final McQqChatPoolPlugin plugin;
    private final Deque<Long> injectStamps = new ArrayDeque<>();
    private final Object injectLock = new Object();

    private HttpServer server;
    private ExecutorService executor;

    public BridgeHttpServer(final McQqChatPoolPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isRunning() {
        return server != null;
    }

    public int port() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    // ------------------------------------------------------------- 生命周期

    /** 启动接口；失败时只记日志，不影响插件其它功能。 */
    public boolean start() {
        final PluginConfig config = plugin.config();
        if (!config.httpEnabled()) {
            plugin.getLogger().info("HTTP 接口已在配置中关闭（http.enabled: false）。");
            return false;
        }
        if (isRunning()) {
            return true;
        }

        try {
            server = HttpServer.create(new InetSocketAddress(config.httpHost(), config.httpPort()), 0);
        } catch (final IOException | IllegalArgumentException ex) {
            plugin.getLogger().severe("HTTP 接口启动失败（" + config.httpHost() + ":" + config.httpPort()
                    + "）：" + ex.getMessage());
            server = null;
            return false;
        }

        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", this::safeHandle);
        server.start();

        final String token = config.token();
        plugin.getLogger().info("HTTP 接口已启动：http://" + config.httpHost() + ":" + port() + "/api/v1/health"
                + (token.isEmpty() ? "（未设置 token，任何人都能访问）" : "（已启用 token 校验）"));
        if (token.isEmpty()) {
            plugin.getLogger().warning("http.token 为空：请设置一个随机令牌，否则同机进程可随意向服务器注入消息。");
        }
        if (isLoopback(config.httpHost())) {
            plugin.getLogger().info("提示：若 AstrBot 跑在 Docker 容器里，AstrBot 侧的 mc_base_url 填 "
                    + "http://host.docker.internal:" + port() + " 即可（Docker Desktop 能访问宿主机回环端口）；"
                    + "只有在 Linux Docker / 自定义 bridge 网络下才需要把 http.host 改成 0.0.0.0。");
        } else if (token.isEmpty()) {
            plugin.getLogger().warning("http.host 不是本机回环地址且未设置 token，风险很高，请立刻修改配置！");
        }
        return true;
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private static boolean isLoopback(final String host) {
        if (host == null) {
            return false;
        }
        final String value = host.trim().toLowerCase(Locale.ROOT);
        return value.equals("127.0.0.1") || value.equals("localhost") || value.equals("::1") || value.equals("0:0:0:0:0:0:0:1");
    }

    // ---------------------------------------------------------------- 路由

    private void safeHandle(final HttpExchange exchange) {
        final long startedAt = System.nanoTime();
        int status = 200;
        try {
            status = route(exchange);
        } catch (final ApiError error) {
            status = error.status;
            sendJson(exchange, error.status, errorBody(error.getMessage()));
        } catch (final Exception ex) {
            status = 500;
            // 带上异常对象，让日志里能看到完整调用栈（排错时非常关键）
            plugin.getLogger().log(Level.WARNING,
                    "处理 HTTP 请求 " + exchange.getRequestMethod() + " " + exchange.getRequestURI() + " 时出错", ex);
            sendJson(exchange, 500, errorBody("服务器内部错误：" + ex.getClass().getSimpleName()));
        } finally {
            exchange.close();
            if (plugin.config().logRequests()) {
                final long micros = (System.nanoTime() - startedAt) / 1000L;
                plugin.getLogger().info("HTTP " + exchange.getRequestMethod() + " " + exchange.getRequestURI()
                        + " -> " + status + " (" + micros + "µs)");
            }
        }
    }

    private int route(final HttpExchange exchange) throws IOException {
        final String path = normalizePath(exchange.getRequestURI().getPath());
        final String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        final Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());

        if (!authorized(exchange, query)) {
            sendJson(exchange, 401, errorBody("令牌校验失败：请检查 http.token 与请求头 Authorization/X-Auth-Token 是否一致。"));
            return 401;
        }

        if (path.equals("/") || path.equals("/api/v1/health")) {
            requireMethod(exchange, method, "GET");
            return health(exchange);
        }
        if (path.equals("/api/v1/pools")) {
            requireMethod(exchange, method, "GET");
            return pools(exchange);
        }
        if (path.equals("/api/v1/players")) {
            requireMethod(exchange, method, "GET");
            return players(exchange, query);
        }
        if (path.equals("/api/v1/messages")) {
            if (method.equals("GET")) {
                return messages(exchange, query);
            }
            if (method.equals("POST")) {
                return inject(exchange);
            }
            sendJson(exchange, 405, errorBody("/api/v1/messages 只支持 GET 与 POST。"));
            return 405;
        }

        sendJson(exchange, 404, errorBody("未知的接口路径：" + path));
        return 404;
    }

    private void requireMethod(final HttpExchange exchange, final String actual, final String expected) {
        if (!actual.equals(expected)) {
            throw new ApiError(405, "该接口只支持 " + expected + "（收到 " + actual + "）。");
        }
    }

    // --------------------------------------------------------------- 各接口

    private int health(final HttpExchange exchange) throws IOException {
        final ChatPoolManager manager = plugin.pools();
        final Json.Obj body = Json.Obj.create()
                .put("ok", true)
                .put("plugin", plugin.getPluginMeta().getName())
                .put("version", plugin.getPluginMeta().getVersion())
                .put("server_time", Instant.now().toString())
                .put("online_players", plugin.getServer().getOnlinePlayers().size())
                .put("max_players", plugin.getServer().getMaxPlayers())
                .put("next_message_id", manager.currentId())
                .put("default_pool", plugin.config().defaultPool())
                .putStrings("pools", new ArrayList<>(manager.poolNames()));
        sendJson(exchange, 200, body.toString());
        return 200;
    }

    private int pools(final HttpExchange exchange) throws IOException {
        final List<String> items = new ArrayList<>();
        for (final ChatPool pool : plugin.pools().pools()) {
            items.add(toJson(plugin.pools().describe(pool)));
        }
        sendJson(exchange, 200, Json.Obj.create()
                .put("ok", true)
                .put("count", items.size())
                .putRawArray("pools", items)
                .toString());
        return 200;
    }

    private int players(final HttpExchange exchange, final Map<String, String> query) throws IOException {
        final ChatPool pool = resolvePool(query.get("pool"), false);
        final List<String> items = new ArrayList<>();
        int online = 0;
        for (final Map.Entry<UUID, String> entry : pool.subscribers().entrySet()) {
            final boolean isOnline = plugin.getServer().getPlayer(entry.getKey()) != null;
            if (isOnline) {
                online++;
            }
            items.add(Json.Obj.create()
                    .put("uuid", entry.getKey().toString())
                    .put("name", entry.getValue() == null ? "?" : entry.getValue())
                    .put("online", isOnline)
                    .toString());
        }
        sendJson(exchange, 200, Json.Obj.create()
                .put("ok", true)
                .put("pool", pool.name())
                .put("members", items.size())
                .put("online", online)
                .putRawArray("players", items)
                .toString());
        return 200;
    }

    /** GET /api/v1/messages —— AstrBot 定时轮询就在这里拉数据。 */
    private int messages(final HttpExchange exchange, final Map<String, String> query) throws IOException {
        final ChatPool pool = resolvePool(query.get("pool"), false);
        final int limit = resolveLimit(query.get("limit"));
        final Set<String> include = splitSources(query.get("source"));
        final Set<String> exclude = splitSources(query.get("exclude_source"));

        final boolean incremental = query.containsKey("since");
        final long since = incremental ? parseLong(query.get("since"), 0L) : -1L;

        final List<ChatMessage> list = incremental
                ? pool.messagesAfter(since, limit, include, exclude)
                : pool.tail(limit, include, exclude);

        long cursor = incremental ? since : pool.cursor();
        final List<String> items = new ArrayList<>(list.size());
        for (final ChatMessage message : list) {
            items.add(message.toJson());
            cursor = message.id();
        }

        // 增量模式下确认后面还有没有积压，方便对端立刻再拉一次
        boolean hasMore = false;
        if (incremental && !list.isEmpty()) {
            hasMore = !pool.messagesAfter(cursor, 1, include, exclude).isEmpty();
        }

        sendJson(exchange, 200, Json.Obj.create()
                .put("ok", true)
                .put("pool", pool.name())
                .put("mode", incremental ? "incremental" : "tail")
                .put("since", since)
                .put("cursor", cursor)
                .put("pool_cursor", pool.cursor())
                .put("total", pool.size())
                .put("oldest_id", pool.firstId())
                .put("count", items.size())
                .put("has_more", hasMore)
                .putRawArray("messages", items)
                .toString());
        return 200;
    }

    /** POST /api/v1/messages —— QQ 群消息注入聊天池。 */
    private int inject(final HttpExchange exchange) throws IOException {
        if (!allowInject()) {
            sendJson(exchange, 429, errorBody("注入过于频繁，已触发限流（http.max-inject-per-minute = "
                    + plugin.config().maxInjectPerMinute() + "）。"));
            return 429;
        }

        final String bodyText = readBody(exchange);
        final Map<String, Object> body;
        try {
            body = Json.parseObject(bodyText);
        } catch (final IllegalArgumentException ex) {
            sendJson(exchange, 400, errorBody("请求体不是合法的 JSON 对象：" + ex.getMessage()));
            return 400;
        }

        final String content = Json.string(body, "content", "").trim();
        if (content.isEmpty()) {
            sendJson(exchange, 400, errorBody("content 不能为空。"));
            return 400;
        }
        final int maxLength = plugin.config().maxMessageLength();
        if (content.length() > maxLength) {
            sendJson(exchange, 413, errorBody("content 超过长度上限 " + maxLength + " 个字符。"));
            return 413;
        }

        // ---- 阻止敏感操作（1/2）：来源白名单 ----
        // 外部注入只能声明 security.allowed-external-sources 里的 source，
        // 否则可以谎报 console / mc 伪造控制台或玩家身份。
        final String source = PluginConfig.normalize(
                defaultIfBlank(Json.string(body, "source", ChatMessage.SOURCE_QQ), ChatMessage.SOURCE_QQ));
        final Set<String> allowedSources = plugin.config().security().allowedExternalSources();
        if (!allowedSources.isEmpty() && !allowedSources.contains(source)) {
            plugin.getLogger().warning("拒绝了一条来源为 '" + source + "' 的外部注入（只允许 "
                    + String.join(", ", allowedSources) + "）：" + preview(Json.string(body, "sender", ""), content));
            sendJson(exchange, 403, errorBody("外部注入不允许声明 source: " + source
                    + "（security.allowed-external-sources 只允许 " + String.join(", ", allowedSources) + "）。"));
            return 403;
        }

        // ---- 阻止敏感操作（2/2）：内容过滤 ----
        // 命令样式（/、!!）、控制字符、敏感词、链接数量；处置方式由 security.filter.action 决定。
        final MessageFilter.Outcome outcome = plugin.messageFilter().check(content);
        if (!outcome.allowed()) {
            plugin.getLogger().warning("拦截了一条外部注入（" + outcome.reason() + "）："
                    + preview(Json.string(body, "sender", ""), content));
            sendJson(exchange, 403, errorBody("内容被安全过滤拦截：" + outcome.reason()));
            return 403;
        }
        final String safeContent = outcome.text();
        if (outcome.censored()) {
            plugin.getLogger().info("已打码一条外部注入（" + outcome.reason() + "）："
                    + preview(Json.string(body, "sender", ""), safeContent));
        }

        final ChatPool pool = resolvePool(Json.string(body, "pool", null), true);
        if (!pool.config().allowFromQq()) {
            sendJson(exchange, 403, errorBody("聊天池 " + pool.name() + " 设置了 allow-from-qq: false，拒绝注入。"));
            return 403;
        }

        final String sender = defaultIfBlank(Json.string(body, "sender", ""), "QQ用户");
        final String senderId = Json.string(body, "sender_id", "");

        try {
            final ChatPoolManager.PublishResult result = plugin.pools()
                    .publishAsync(pool, source, sender, senderId, safeContent)
                    .get(8, TimeUnit.SECONDS);
            sendJson(exchange, 200, Json.Obj.create()
                    .put("ok", true)
                    .put("id", result.id())
                    .put("pool", pool.name())
                    .put("source", source)
                    .put("delivered", result.delivered())
                    .put("online_in_pool", plugin.pools().onlineSubscriberNames(pool).size())
                    .toString());
            return 200;
        } catch (final Exception ex) {
            plugin.getLogger().warning("注入消息超时或失败：" + ex);
            sendJson(exchange, 504, errorBody("服务器未能及时处理这条消息：" + ex.getClass().getSimpleName()));
            return 504;
        }
    }

    // ---------------------------------------------------------------- 工具

    private ChatPool resolvePool(final String requested, final boolean createIfAllowed) {
        final String name = PluginConfig.normalize(defaultIfBlank(requested, plugin.config().defaultPool()));
        if (!PluginConfig.isValidPoolName(name)) {
            throw new ApiError(400, "池名 '" + name + "' 不合法：只允许小写字母、数字、下划线和短横线，"
                    + "以字母或数字开头，最长 32 字符。");
        }
        final ChatPool pool = createIfAllowed ? plugin.pools().getOrCreate(name) : plugin.pools().get(name);
        if (pool == null) {
            throw new ApiError(404, "没有找到聊天池 '" + name + "'，可在 config.yml 的 pools 段定义，"
                    + "或把 chat.auto-create-pool 设为 true。");
        }
        return pool;
    }

    /** 日志里的内容预览：截断，避免长消息刷屏把日志撑爆。 */
    private static String preview(final String sender, final String content) {
        final String who = defaultIfBlank(sender, "?");
        final String text = content == null ? "" : content;
        return who + ": " + (text.length() > 60 ? text.substring(0, 60) + "…" : text);
    }

    private int resolveLimit(final String raw) {
        final PluginConfig config = plugin.config();
        final long value = raw == null ? config.pollDefaultLimit() : parseLong(raw, config.pollDefaultLimit());
        return (int) Math.max(1L, Math.min(config.pollMaxLimit(), value));
    }

    private static Set<String> splitSources(final String raw) {
        final Set<String> out = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (final String part : raw.split("[,;\\s]+")) {
            if (!part.isBlank()) {
                out.add(part.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private boolean allowInject() {
        final int max = plugin.config().maxInjectPerMinute();
        final long now = System.currentTimeMillis();
        synchronized (injectLock) {
            while (!injectStamps.isEmpty() && now - injectStamps.peekFirst() > 60_000L) {
                injectStamps.pollFirst();
            }
            if (injectStamps.size() >= max) {
                return false;
            }
            injectStamps.addLast(now);
            return true;
        }
    }

    private boolean authorized(final HttpExchange exchange, final Map<String, String> query) {
        final String expected = plugin.config().token();
        if (expected.isEmpty()) {
            return true;
        }
        final String header = exchange.getRequestHeaders().getFirst("Authorization");
        String presented = null;
        if (header != null) {
            final String trimmed = header.trim();
            presented = trimmed.regionMatches(true, 0, "Bearer ", 0, 7) ? trimmed.substring(7).trim() : trimmed;
        }
        if (presented == null || presented.isEmpty()) {
            presented = exchange.getRequestHeaders().getFirst("X-Auth-Token");
        }
        if (presented == null || presented.isEmpty()) {
            presented = query.get("token");
        }
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    private String readBody(final HttpExchange exchange) throws IOException {
        final int max = plugin.config().maxBodyBytes();
        try (InputStream in = exchange.getRequestBody()) {
            final byte[] buffer = in.readNBytes(max + 1);
            if (buffer.length > max) {
                throw new ApiError(413, "请求体超过上限 " + max + " 字节。");
            }
            return new String(buffer, StandardCharsets.UTF_8);
        }
    }

    private void sendJson(final HttpExchange exchange, final int status, final String json) {
        try {
            final byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (final IOException ex) {
            plugin.getLogger().warning("写回 HTTP 响应失败：" + ex.getMessage());
        }
    }

    private static String errorBody(final String message) {
        return Json.Obj.create().put("ok", false).put("error", message).toString();
    }

    private static String toJson(final Map<String, Object> values) {
        final Json.Obj obj = Json.Obj.create();
        for (final Map.Entry<String, Object> entry : values.entrySet()) {
            final Object value = entry.getValue();
            if (value instanceof Boolean bool) {
                obj.put(entry.getKey(), bool);
            } else if (value instanceof Number number) {
                obj.put(entry.getKey(), number.longValue());
            } else {
                obj.put(entry.getKey(), value == null ? null : String.valueOf(value));
            }
        }
        return obj.toString();
    }

    private static String normalizePath(final String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }

    private static Map<String, String> parseQuery(final String rawQuery) {
        final Map<String, String> out = new java.util.LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return out;
        }
        for (final String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            final int eq = pair.indexOf('=');
            final String rawKey = eq < 0 ? pair : pair.substring(0, eq);
            final String rawValue = eq < 0 ? "" : pair.substring(eq + 1);
            final String key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            out.putIfAbsent(key, URLDecoder.decode(rawValue, StandardCharsets.UTF_8));
        }
        return out;
    }

    private static long parseLong(final String raw, final long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (final NumberFormatException ex) {
            return fallback;
        }
    }

    private static String defaultIfBlank(final String value, final String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /** 用于在路由里抛出带状态码的错误。 */
    private static final class ApiError extends RuntimeException {

        private final int status;

        private ApiError(final int status, final String message) {
            super(message);
            this.status = status;
        }
    }
}
