package com.mcqqchatpool.pool;

import com.mcqqchatpool.McQqChatPoolPlugin;
import com.mcqqchatpool.config.PluginConfig;
import com.mcqqchatpool.config.PoolConfig;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 聊天池总管：池的创建/查找、消息发布与广播、订阅关系、持久化。
 *
 * <p>{@link #publish} 会碰 Bukkit 的在线玩家与事件系统，**必须在主线程调用**；
 * 来自 HTTP 线程或异步聊天事件的调用请走 {@link #publishAsync}。
 */
public final class ChatPoolManager {

    private final McQqChatPoolPlugin plugin;
    private final Map<String, ChatPool> pools = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong(0L);
    private final Map<UUID, Long> lastSpeakAt = new ConcurrentHashMap<>();

    private volatile boolean dirty = false;

    public ChatPoolManager(final McQqChatPoolPlugin plugin) {
        this.plugin = plugin;
    }

    private static String key(final String name) {
        return PluginConfig.normalize(name);
    }

    private File stateFile() {
        return new File(plugin.getDataFolder(), "state.yml");
    }

    // ------------------------------------------------------------ 装载/重载

    /** 按配置建立池，并（可选地）恢复上次的历史与订阅关系。 */
    public void load() {
        final PluginConfig config = plugin.config();

        // 1) 同步 config.yml 里定义的池
        for (final Map.Entry<String, PoolConfig> entry : config.pools().entrySet()) {
            pools.compute(entry.getKey(), (name, existing) -> {
                if (existing == null) {
                    return new ChatPool(name, entry.getValue());
                }
                existing.config(entry.getValue());
                return existing;
            });
        }

        // 2) 配置里删掉的池一并移除（保留在 state.yml 里，方便改回来）
        final List<String> removed = new ArrayList<>();
        for (final String name : pools.keySet()) {
            if (!config.pools().containsKey(name)) {
                removed.add(name);
            }
        }
        for (final String name : removed) {
            pools.remove(name);
        }
        if (!removed.isEmpty()) {
            plugin.getLogger().info("已从配置中移除的聊天池：" + String.join(", ", removed));
        }

        // 3) 默认池必须存在
        if (pools.isEmpty() || !pools.containsKey(key(config.defaultPool()))) {
            final String fallback = config.defaultPool().isEmpty() ? "global" : config.defaultPool();
            pools.computeIfAbsent(fallback, name -> new ChatPool(name, PoolConfig.defaults(name, config.defaultMaxHistory())));
        }

        // 4) 恢复历史与订阅
        if (config.persistHistory()) {
            loadState();
        }

        plugin.getLogger().info("已加载 " + pools.size() + " 个聊天池：" + String.join(", ", pools.keySet()));
    }

    /** 重新读取配置（池定义会被更新，历史保留）。 */
    public void reload() {
        final PluginConfig config = plugin.config();
        for (final Map.Entry<String, PoolConfig> entry : config.pools().entrySet()) {
            pools.compute(entry.getKey(), (name, existing) -> {
                if (existing == null) {
                    return new ChatPool(name, entry.getValue());
                }
                existing.config(entry.getValue());
                return existing;
            });
        }
    }

    // ---------------------------------------------------------------- 查找

    public ChatPool get(final String name) {
        return name == null ? null : pools.get(key(name));
    }

    /** 取池；配置允许时按默认参数新建。池名不合法时返回 null。 */
    public ChatPool getOrCreate(final String name) {
        final String normalized = key(name);
        if (!PluginConfig.isValidPoolName(normalized)) {
            return null;
        }
        final ChatPool existing = pools.get(normalized);
        if (existing != null) {
            return existing;
        }
        if (!plugin.config().autoCreatePool()) {
            return null;
        }
        final ChatPool created = new ChatPool(normalized, PoolConfig.defaults(normalized, plugin.config().defaultMaxHistory()));
        pools.put(normalized, created);
        dirty = true;
        plugin.getLogger().info("按需创建了聊天池：" + normalized);
        return created;
    }

    public Collection<ChatPool> pools() {
        return pools.values();
    }

    public Set<String> poolNames() {
        return new LinkedHashSet<>(pools.keySet());
    }

    public ChatPool defaultPool() {
        ChatPool pool = get(plugin.config().defaultPool());
        if (pool == null) {
            pool = pools.values().iterator().next();
        }
        return pool;
    }

    // ---------------------------------------------------------------- 发布

    /**
     * 一次发布的回执。
     *
     * @param id        新消息的 ID
     * @param delivered 在游戏内实际收到这条消息的玩家数
     */
    public record PublishResult(long id, int delivered) {
    }

    /**
     * 往池里写一条消息并广播给池内在线订阅者（主线程）。
     */
    public PublishResult publish(final ChatPool pool, final String source, final String sender,
                                 final String senderId, final String content) {
        if (pool == null) {
            return new PublishResult(-1L, 0);
        }
        final ChatMessage message = new ChatMessage(
                idSequence.incrementAndGet(),
                pool.name(),
                source,
                sender,
                senderId,
                content,
                System.currentTimeMillis()
        );
        pool.append(message);
        dirty = true;

        final int delivered = broadcast(pool, message);

        if (plugin.config().notifyConsole()) {
            plugin.getLogger().info("[" + pool.name() + "/" + source + "] " + sender + ": " + content);
        }
        return new PublishResult(message.id(), delivered);
    }

    /** 发布并把结果异步送回调用方（HTTP 线程/异步事件用）。 */
    public CompletableFuture<PublishResult> publishAsync(final ChatPool pool, final String source,
                                                         final String sender, final String senderId,
                                                         final String content) {
        return plugin.callSync(() -> publish(pool, source, sender, senderId, content));
    }

    /** 把消息渲染后发给池内所有在线订阅者。 */
    private int broadcast(final ChatPool pool, final ChatMessage message) {
        final String formatKey = switch (message.source() == null ? "" : message.source().toLowerCase(Locale.ROOT)) {
            case ChatMessage.SOURCE_QQ -> "qq-to-mc";
            case ChatMessage.SOURCE_CONSOLE -> "console-to-mc";
            default -> "mc-to-mc";
        };
        final Component rendered = plugin.lang().format(formatKey, message.placeholders());

        int delivered = 0;
        for (final UUID uuid : pool.subscribers().keySet()) {
            final Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                player.sendMessage(rendered);
                delivered++;
            }
        }
        return delivered;
    }

    /** 取池内当前在线的订阅者名字。 */
    public List<String> onlineSubscriberNames(final ChatPool pool) {
        final List<String> names = new ArrayList<>();
        for (final Map.Entry<UUID, String> entry : pool.subscribers().entrySet()) {
            final Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                names.add(player.getName());
            } else if (entry.getValue() != null) {
                // 离线玩家不列出，但保留记录，方便持久化
                continue;
            }
        }
        return names;
    }

    // ------------------------------------------------------------ 订阅关系

    /** 玩家进服：按配置自动订阅默认池。 */
    public void assignDefaultPools(final Player player) {
        if (!plugin.config().autoJoinDefaultPools()) {
            return;
        }
        for (final ChatPool pool : pools.values()) {
            if (pool.config().defaultJoin()) {
                pool.subscribe(player.getUniqueId(), player.getName());
            }
        }
        dirty = true;
    }

    /** 玩家改名后同步订阅名单里的名字。 */
    public void refreshName(final Player player) {
        for (final ChatPool pool : pools.values()) {
            if (pool.isSubscribed(player.getUniqueId())) {
                pool.subscribe(player.getUniqueId(), player.getName());
            }
        }
    }

    // -------------------------------------------------------------- 限流

    /** 距离下次可以发言还剩多少毫秒；0 表示可以发。 */
    public long cooldownRemaining(final UUID uuid) {
        final long interval = plugin.config().minMessageIntervalMs();
        if (interval <= 0L) {
            return 0L;
        }
        final Long last = lastSpeakAt.get(uuid);
        if (last == null) {
            return 0L;
        }
        final long elapsed = System.currentTimeMillis() - last;
        return elapsed >= interval ? 0L : interval - elapsed;
    }

    public void markSpoken(final UUID uuid) {
        lastSpeakAt.put(uuid, System.currentTimeMillis());
    }

    // ------------------------------------------------------------ 持久化

    /** 标记状态有改动，等待下一次自动保存。 */
    public void markDirty() {
        this.dirty = true;
    }

    /** 有改动就保存。 */
    public void saveIfDirty() {
        if (dirty) {
            save();
        }
    }

    /** 把历史消息、ID 游标和订阅关系写进 state.yml。 */
    public synchronized void save() {
        if (!plugin.config().persistHistory()) {
            dirty = false;
            return;
        }

        final YamlConfiguration state = new YamlConfiguration();
        state.set("next-id", idSequence.get());
        state.set("saved-at", System.currentTimeMillis());

        final Map<UUID, String> names = new LinkedHashMap<>();
        final Map<UUID, Set<String>> memberships = new LinkedHashMap<>();

        for (final ChatPool pool : pools.values()) {
            final List<Map<String, Object>> serialized = new ArrayList<>();
            for (final ChatMessage message : pool.snapshot()) {
                serialized.add(message.toMap());
            }
            state.set("pools." + pool.name(), serialized);

            for (final Map.Entry<UUID, String> entry : pool.subscribers().entrySet()) {
                names.put(entry.getKey(), entry.getValue());
                memberships.computeIfAbsent(entry.getKey(), uuid -> new LinkedHashSet<>()).add(pool.name());
            }
        }

        for (final Map.Entry<UUID, Set<String>> entry : memberships.entrySet()) {
            final String base = "players." + entry.getKey();
            state.set(base + ".name", names.get(entry.getKey()));
            state.set(base + ".pools", new ArrayList<>(entry.getValue()));
        }

        try {
            final File file = stateFile();
            if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
                plugin.getLogger().warning("无法创建插件数据目录，state.yml 未保存。");
                return;
            }
            Files.writeString(file.toPath(), state.saveToString(), StandardCharsets.UTF_8);
            dirty = false;
        } catch (final IOException ex) {
            plugin.getLogger().warning("保存 state.yml 失败：" + ex.getMessage());
        }
    }

    /** 从 state.yml 恢复历史与订阅关系。 */
    private void loadState() {
        final File file = stateFile();
        if (!file.isFile()) {
            return;
        }

        final YamlConfiguration state = YamlConfiguration.loadConfiguration(file);
        idSequence.set(Math.max(idSequence.get(), state.getLong("next-id", 0L)));

        int restored = 0;
        final ConfigurationSection poolsSection = state.getConfigurationSection("pools");
        if (poolsSection != null) {
            for (final String poolName : poolsSection.getKeys(false)) {
                final ChatPool pool = pools.get(key(poolName));
                if (pool == null) {
                    continue;
                }
                for (final Map<?, ?> raw : state.getMapList("pools." + poolName)) {
                    final ChatMessage message = ChatMessage.fromMap(pool.name(), raw);
                    pool.append(message);
                    restored++;
                }
            }
        }

        int subscribers = 0;
        final ConfigurationSection playersSection = state.getConfigurationSection("players");
        if (playersSection != null) {
            for (final String uuidText : playersSection.getKeys(false)) {
                UUID uuid;
                try {
                    uuid = UUID.fromString(uuidText);
                } catch (final IllegalArgumentException ex) {
                    continue;
                }
                final String name = state.getString("players." + uuidText + ".name", "?");
                for (final String poolName : state.getStringList("players." + uuidText + ".pools")) {
                    final ChatPool pool = pools.get(key(poolName));
                    if (pool != null) {
                        pool.subscribe(uuid, name);
                        subscribers++;
                    }
                }
            }
        }

        plugin.getLogger().info("已从 state.yml 恢复 " + restored + " 条聊天记录、" + subscribers + " 条订阅关系。");
    }

    /** 当前最大的消息 ID（给日志/接口用）。 */
    public long currentId() {
        return idSequence.get();
    }

    /** 供 HTTP 接口构造池信息用。 */
    public Map<String, Object> describe(final ChatPool pool) {
        final Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", pool.name());
        info.put("display_name", pool.config().displayName());
        info.put("description", pool.config().description());
        info.put("default_join", pool.config().defaultJoin());
        info.put("capture_vanilla_chat", pool.config().captureVanillaChat());
        info.put("allow_from_qq", pool.config().allowFromQq());
        info.put("allow_from_mc", pool.config().allowFromMc());
        info.put("max_history", pool.config().maxHistory());
        info.put("history", pool.size());
        info.put("members", pool.subscriberCount());
        info.put("online", onlineSubscriberNames(pool).size());
        info.put("cursor", pool.cursor());
        return info;
    }
}
