package com.mcqqchatpool.config;

import com.mcqqchatpool.McQqChatPoolPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 插件配置的读取与默认值补齐。
 *
 * <p>标量配置在 {@link #load()} 时一次性读进字段；{@code pools} 段解析成
 * {@link PoolConfig} 映射。{@link #ensureDefaults()} 负责把 config.yml 里缺失的键补上，
 * 这样用户升级插件时不至于因为少了一个键就报错。
 */
public final class PluginConfig {

    private final McQqChatPoolPlugin plugin;

    // ---- http ----
    private boolean httpEnabled = true;
    private String httpHost = "127.0.0.1";
    private int httpPort = 8123;
    private String token = "";
    private int maxBodyBytes = 65536;
    private int maxInjectPerMinute = 60;
    private boolean logRequests = false;

    // ---- chat ----
    private String defaultPool = "global";
    private boolean autoJoinDefaultPools = true;
    private boolean autoCreatePool = false;
    private boolean notifyConsole = true;
    private int maxMessageLength = 300;
    private long minMessageIntervalMs = 800L;
    private boolean joinHint = true;

    // ---- history ----
    private boolean persistHistory = true;
    private int saveIntervalSeconds = 300;
    private int pollDefaultLimit = 50;
    private int pollMaxLimit = 200;
    private int defaultMaxHistory = 200;
    private boolean retainAllHistory = false;
    private int retainAllLimit = 50000;
    private int saveMaxMessages = 2000;

    // ---- security ----
    private SecurityConfig security = SecurityConfig.defaults();

    // ---- push（主动推送：MC 服务器不在 AstrBot 那台机器上时用）----
    private boolean pushEnabled = false;
    private String pushUrl = "http://127.0.0.1:6198/mc-chatpool/push";
    private String pushToken = "";
    private Set<String> pushExcludeSources = Set.of("qq");
    private Set<String> pushPools = Set.of();
    private int pushTimeoutMs = 5000;
    private int pushMaxRetries = 3;
    private long pushRetryDelayMs = 1000L;
    private int pushQueueSize = 512;
    private boolean pushLogRequests = false;

    private Map<String, PoolConfig> pools = new LinkedHashMap<>();

    public PluginConfig(final McQqChatPoolPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ 默认值补齐

    /** 补齐 config.yml 中缺失的键（不动用户已经写好的值）。 */
    public void ensureDefaults() {
        final FileConfiguration config = plugin.getConfig();
        boolean changed = false;

        changed |= setIfMissing(config, "http.enabled", true);
        changed |= setIfMissing(config, "http.host", "127.0.0.1");
        changed |= setIfMissing(config, "http.port", 8123);
        changed |= setIfMissing(config, "http.token", "");
        changed |= setIfMissing(config, "http.max-body-bytes", 65536);
        changed |= setIfMissing(config, "http.max-inject-per-minute", 60);
        changed |= setIfMissing(config, "http.log-requests", false);

        changed |= setIfMissing(config, "chat.default-pool", "global");
        changed |= setIfMissing(config, "chat.auto-join-default-pools", true);
        changed |= setIfMissing(config, "chat.auto-create-pool", false);
        changed |= setIfMissing(config, "chat.notify-console", true);
        changed |= setIfMissing(config, "chat.max-message-length", 300);
        changed |= setIfMissing(config, "chat.min-message-interval-ms", 800);
        changed |= setIfMissing(config, "chat.join-hint", true);

        changed |= setIfMissing(config, "history.persist", true);
        changed |= setIfMissing(config, "history.save-interval-seconds", 300);
        changed |= setIfMissing(config, "history.poll-default-limit", 50);
        changed |= setIfMissing(config, "history.poll-max-limit", 200);
        changed |= setIfMissing(config, "history.default-max-history", 200);
        changed |= setIfMissing(config, "history.retain-all", false);
        changed |= setIfMissing(config, "history.retain-all-limit", 50000);
        changed |= setIfMissing(config, "history.save-max-messages", 2000);

        changed |= setIfMissing(config, "security.allowed-external-sources", List.of("qq"));
        changed |= setIfMissing(config, "security.filter.enabled", true);
        changed |= setIfMissing(config, "security.filter.action", "reject");
        changed |= setIfMissing(config, "security.filter.block-command-like", true);
        changed |= setIfMissing(config, "security.filter.block-control-chars", true);
        changed |= setIfMissing(config, "security.filter.blocked-keywords", List.of(
                "op", "deop", "ban", "ban-ip", "pardon", "kick", "whitelist", "gamemode", "execute", "sudo"));
        changed |= setIfMissing(config, "security.filter.max-links", 0);
        changed |= setIfMissing(config, "security.filter.apply-to-game-chat", false);

        changed |= setIfMissing(config, "push.enabled", false);
        changed |= setIfMissing(config, "push.url", "http://127.0.0.1:6198/mc-chatpool/push");
        changed |= setIfMissing(config, "push.token", "");
        changed |= setIfMissing(config, "push.exclude-sources", List.of("qq"));
        changed |= setIfMissing(config, "push.pools", List.of());
        changed |= setIfMissing(config, "push.timeout-ms", 5000);
        changed |= setIfMissing(config, "push.max-retries", 3);
        changed |= setIfMissing(config, "push.retry-delay-ms", 1000);
        changed |= setIfMissing(config, "push.queue-size", 512);
        changed |= setIfMissing(config, "push.log-requests", false);

        final ConfigurationSection poolSection = config.getConfigurationSection("pools");
        if (poolSection == null || poolSection.getKeys(false).isEmpty()) {
            config.set("pools.global.display-name", "全服");
            config.set("pools.global.description", "全服公共聊天池");
            config.set("pools.global.default-join", true);
            config.set("pools.global.max-history", 300);
            config.set("pools.global.capture-vanilla-chat", false);
            config.set("pools.global.allow-from-qq", true);
            config.set("pools.global.allow-from-mc", true);
            changed = true;
        }

        if (changed) {
            plugin.saveConfig();
        }
    }

    private static boolean setIfMissing(final FileConfiguration config, final String path, final Object value) {
        if (config.isSet(path)) {
            return false;
        }
        config.set(path, value);
        return true;
    }

    // ------------------------------------------------------------------ 读取

    /** 把配置读进字段。 */
    public void load() {
        final FileConfiguration config = plugin.getConfig();

        this.httpEnabled = config.getBoolean("http.enabled", true);
        this.httpHost = config.getString("http.host", "127.0.0.1");
        this.httpPort = clamp(config.getInt("http.port", 8123), 1, 65535);
        this.token = config.getString("http.token", "");
        this.maxBodyBytes = Math.max(1024, config.getInt("http.max-body-bytes", 65536));
        this.maxInjectPerMinute = Math.max(1, config.getInt("http.max-inject-per-minute", 60));
        this.logRequests = config.getBoolean("http.log-requests", false);

        this.defaultPool = normalize(config.getString("chat.default-pool", "global"));
        this.autoJoinDefaultPools = config.getBoolean("chat.auto-join-default-pools", true);
        this.autoCreatePool = config.getBoolean("chat.auto-create-pool", false);
        this.notifyConsole = config.getBoolean("chat.notify-console", true);
        this.maxMessageLength = clamp(config.getInt("chat.max-message-length", 300), 1, 4096);
        this.minMessageIntervalMs = Math.max(0L, config.getLong("chat.min-message-interval-ms", 800L));
        this.joinHint = config.getBoolean("chat.join-hint", true);

        this.persistHistory = config.getBoolean("history.persist", true);
        this.saveIntervalSeconds = Math.max(10, config.getInt("history.save-interval-seconds", 300));
        this.pollDefaultLimit = clamp(config.getInt("history.poll-default-limit", 50), 1, 1000);
        this.pollMaxLimit = clamp(config.getInt("history.poll-max-limit", 200), this.pollDefaultLimit, 2000);
        this.defaultMaxHistory = clamp(config.getInt("history.default-max-history", 200), 1, 10000);
        this.retainAllHistory = config.getBoolean("history.retain-all", false);
        this.retainAllLimit = clamp(config.getInt("history.retain-all-limit", 50000), 1, 1_000_000);
        this.saveMaxMessages = clamp(config.getInt("history.save-max-messages", 2000), 1, 1_000_000);

        // ---- security ----
        final List<String> keywords = config.getStringList("security.filter.blocked-keywords");
        final Set<String> allowedSources = new LinkedHashSet<>();
        for (final String raw : config.getStringList("security.allowed-external-sources")) {
            if (raw != null && !raw.isBlank()) {
                allowedSources.add(raw.trim().toLowerCase(Locale.ROOT));
            }
        }
        this.security = new SecurityConfig(
                config.getBoolean("security.filter.enabled", true),
                SecurityConfig.FilterAction.parse(config.getString("security.filter.action", "reject")),
                config.getBoolean("security.filter.block-command-like", true),
                config.getBoolean("security.filter.block-control-chars", true),
                List.copyOf(keywords),
                Math.max(0, config.getInt("security.filter.max-links", 0)),
                config.getBoolean("security.filter.apply-to-game-chat", false),
                Set.copyOf(allowedSources));

        // ---- push ----
        this.pushEnabled = config.getBoolean("push.enabled", false);
        this.pushUrl = config.getString("push.url", "http://127.0.0.1:6198/mc-chatpool/push");
        this.pushToken = config.getString("push.token", "");
        this.pushExcludeSources = lowerSet(config.getStringList("push.exclude-sources"));
        this.pushPools = normalizeSet(config.getStringList("push.pools"));
        this.pushTimeoutMs = clamp(config.getInt("push.timeout-ms", 5000), 500, 60_000);
        this.pushMaxRetries = clamp(config.getInt("push.max-retries", 3), 1, 10);
        this.pushRetryDelayMs = Math.max(0L, config.getLong("push.retry-delay-ms", 1000L));
        this.pushQueueSize = clamp(config.getInt("push.queue-size", 512), 1, 100_000);
        this.pushLogRequests = config.getBoolean("push.log-requests", false);

        final Map<String, PoolConfig> parsed = new LinkedHashMap<>();
        final ConfigurationSection section = config.getConfigurationSection("pools");
        if (section != null) {
            for (final String rawName : section.getKeys(false)) {
                final String name = normalize(rawName);
                if (!isValidPoolName(name)) {
                    plugin.getLogger().warning("跳过不合法的聊天池名 '" + rawName
                            + "'：只允许小写字母/数字/下划线/短横线，且以字母或数字开头，最长 32 字符。");
                    continue;
                }
                final ConfigurationSection poolSection = section.getConfigurationSection(rawName);
                parsed.put(name, PoolConfig.from(name, poolSection, defaultMaxHistory));
            }
        }
        this.pools = Collections.unmodifiableMap(parsed);

        if (!isValidPoolName(this.defaultPool)) {
            plugin.getLogger().warning("chat.default-pool = '" + this.defaultPool
                    + "' 不是合法的池名，改用 global。");
            this.defaultPool = "global";
        }
    }

    // ---------------------------------------------------------------- 工具

    /** 池名统一转成小写并去掉首尾空白。 */
    public static String normalize(final String name) {
        if (name == null) {
            return "";
        }
        return name.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 池名必须是 ASCII 标识符：小写字母或数字开头，之后可含字母、数字、下划线、短横线，最长 32 字符。
     *
     * <p>之所以限制成标识符：池名同时是 config.yml 的键、HTTP 查询参数和
     * {@code /chatpool send <池> <文本>} 里的命令标识，统一成 ASCII 标识符最省心
     * （命令参数本身是可带引号的字符串，但配置键与 URL 参数不适合非 ASCII）。
     * 输入会统一转成小写，因此 {@code ?pool=GLOBAL} 与 {@code pool: global} 等价。
     */
    public static boolean isValidPoolName(final String normalized) {
        return normalized != null && normalized.matches("[a-z0-9][a-z0-9_-]{0,31}");
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(max, value));
    }

    // -------------------------------------------------------------- 访问器

    public boolean httpEnabled() {
        return httpEnabled;
    }

    public String httpHost() {
        return httpHost;
    }

    public int httpPort() {
        return httpPort;
    }

    public String token() {
        return token == null ? "" : token;
    }

    public int maxBodyBytes() {
        return maxBodyBytes;
    }

    public int maxInjectPerMinute() {
        return maxInjectPerMinute;
    }

    public boolean logRequests() {
        return logRequests;
    }

    public String defaultPool() {
        return defaultPool;
    }

    public boolean autoJoinDefaultPools() {
        return autoJoinDefaultPools;
    }

    public boolean autoCreatePool() {
        return autoCreatePool;
    }

    public boolean notifyConsole() {
        return notifyConsole;
    }

    public int maxMessageLength() {
        return maxMessageLength;
    }

    public long minMessageIntervalMs() {
        return minMessageIntervalMs;
    }

    public boolean joinHint() {
        return joinHint;
    }

    public boolean persistHistory() {
        return persistHistory;
    }

    public int saveIntervalSeconds() {
        return saveIntervalSeconds;
    }

    public int pollDefaultLimit() {
        return pollDefaultLimit;
    }

    public int pollMaxLimit() {
        return pollMaxLimit;
    }

    public int defaultMaxHistory() {
        return defaultMaxHistory;
    }

    /** 是否不裁剪历史（「读取全部聊天记录」的总开关）。 */
    public boolean retainAllHistory() {
        return retainAllHistory;
    }

    /** 不裁剪模式下每个池的保留上限（安全阀，防止无限增长）。 */
    public int retainAllLimit() {
        return retainAllLimit;
    }

    /** 写进 state.yml 的最大消息条数（内存里可以留更多）。 */
    public int saveMaxMessages() {
        return saveMaxMessages;
    }

    /** 安全段配置（来源白名单 + 内容过滤）。 */
    public SecurityConfig security() {
        return security;
    }

    // ---- push ----

    /** 是否开启主动推送（还要 push.url 非空才真正生效）。 */
    public boolean pushEnabled() {
        return pushEnabled;
    }

    /** AstrBot 推送接收端地址。 */
    public String pushUrl() {
        return pushUrl == null ? "" : pushUrl;
    }

    /** 与接收端约定的令牌（放在 Authorization: Bearer）。 */
    public String pushToken() {
        return pushToken == null ? "" : pushToken;
    }

    /** 这些来源的消息不回推（默认丢 qq，避免 QQ → MC → QQ 回环）。 */
    public Set<String> pushExcludeSources() {
        return pushExcludeSources;
    }

    /** 只推送这些池；空集合 = 全部池。 */
    public Set<String> pushPools() {
        return pushPools;
    }

    public int pushTimeoutMs() {
        return pushTimeoutMs;
    }

    public int pushMaxRetries() {
        return pushMaxRetries;
    }

    public long pushRetryDelayMs() {
        return pushRetryDelayMs;
    }

    public int pushQueueSize() {
        return pushQueueSize;
    }

    /** 是否把每次推送的入队与结果写进日志（跨机部署排错时打开）。 */
    public boolean pushLogRequests() {
        return pushLogRequests;
    }

    public Map<String, PoolConfig> pools() {
        return pools;
    }

    /** 来源列表统一转小写（HTTP 注入的 source 也是小写比较）。 */
    private static Set<String> lowerSet(final List<String> raw) {
        final Set<String> out = new LinkedHashSet<>();
        for (final String item : raw) {
            if (item != null && !item.isBlank()) {
                out.add(item.trim().toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(out);
    }

    /** 池名列表按池名规则归一化；不合法的直接忽略并警告。 */
    private Set<String> normalizeSet(final List<String> raw) {
        final Set<String> out = new LinkedHashSet<>();
        for (final String item : raw) {
            final String name = normalize(item);
            if (name.isEmpty()) {
                continue;
            }
            if (!isValidPoolName(name)) {
                plugin.getLogger().warning("push.pools 里的 '" + item + "' 不是合法池名，已忽略。");
                continue;
            }
            out.add(name);
        }
        return Set.copyOf(out);
    }
}
