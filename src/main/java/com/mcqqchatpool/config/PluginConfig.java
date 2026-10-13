package com.mcqqchatpool.config;

import com.mcqqchatpool.McQqChatPoolPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

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

    public Map<String, PoolConfig> pools() {
        return pools;
    }
}
