package com.mcqqchatpool.pool;

import com.mcqqchatpool.config.PoolConfig;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 一个聊天池：一条独立的、带自增 ID 的消息环形缓冲，外加一份订阅玩家名单。
 *
 * <p>线程安全策略：
 * <ul>
 *   <li>消息缓冲用 {@link ReentrantLock} 保护，读写都在锁内完成快照，锁外做格式化；</li>
 *   <li>订阅名单用 {@link ConcurrentHashMap}，可被 HTTP 线程安全读取。</li>
 * </ul>
 */
public final class ChatPool {

    private final String name;
    private final ReentrantLock lock = new ReentrantLock();
    private final Deque<ChatMessage> history = new ArrayDeque<>();
    private final Map<UUID, String> subscribers = new ConcurrentHashMap<>();

    private volatile PoolConfig config;
    private volatile long lastId;

    /**
     * 实际保留条数上限。
     *
     * <p>默认等于本池的 {@code max-history}；开启 {@code history.retain-all} 后由
     * {@link ChatPoolManager} 统一改成全局上限（这正是「读取全部聊天记录」的前提）。
     */
    private volatile int retentionCap;

    public ChatPool(final String name, final PoolConfig config) {
        this.name = name;
        this.config = config;
        this.retentionCap = Math.max(1, config.maxHistory());
    }

    public String name() {
        return name;
    }

    public PoolConfig config() {
        return config;
    }

    public void config(final PoolConfig config) {
        this.config = config;
        this.retentionCap = Math.max(1, config.maxHistory());
        trim();
    }

    /** 设置实际保留上限（配置重载时由管理器统一覆盖）。 */
    public void retentionCap(final int cap) {
        this.retentionCap = Math.max(1, cap);
        trim();
    }

    /** 当前实际的保留上限。 */
    public int retentionCap() {
        return retentionCap;
    }

    /** 池内当前最大 ID（相当于这个池的游标位置）。 */
    public long cursor() {
        return lastId;
    }

    // ------------------------------------------------------------ 消息缓冲

    /** 追加一条消息，并按 {@link #retentionCap} 裁剪。 */
    public void append(final ChatMessage message) {
        lock.lock();
        try {
            history.addLast(message);
            if (message.id() > lastId) {
                lastId = message.id();
            }
            while (history.size() > retentionCap) {
                history.removeFirst();
            }
        } finally {
            lock.unlock();
        }
    }

    /** 按当前的保留上限裁剪一次。 */
    public void trim() {
        lock.lock();
        try {
            while (history.size() > retentionCap) {
                history.removeFirst();
            }
        } finally {
            lock.unlock();
        }
    }

    /** 池内最早一条（仍被保留的）消息 ID；池为空时等于 {@link #cursor()}。 */
    public long firstId() {
        lock.lock();
        try {
            return history.isEmpty() ? lastId : history.peekFirst().id();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 取出 ID 大于 {@code since} 的消息（升序），最多 {@code limit} 条。
     *
     * @param include 只保留这些来源；空集合表示不过滤
     * @param exclude 排除这些来源
     */
    public List<ChatMessage> messagesAfter(final long since, final int limit,
                                           final Set<String> include, final Set<String> exclude) {
        final List<ChatMessage> out = new ArrayList<>();
        lock.lock();
        try {
            for (final ChatMessage message : history) {
                if (message.id() <= since || !accept(message, include, exclude)) {
                    continue;
                }
                out.add(message);
                if (out.size() >= limit) {
                    break;
                }
            }
        } finally {
            lock.unlock();
        }
        return out;
    }

    /** 取出最新的 {@code limit} 条消息（升序）。 */
    public List<ChatMessage> tail(final int limit, final Set<String> include, final Set<String> exclude) {
        final List<ChatMessage> all = new ArrayList<>();
        lock.lock();
        try {
            for (final ChatMessage message : history) {
                if (accept(message, include, exclude)) {
                    all.add(message);
                }
            }
        } finally {
            lock.unlock();
        }
        if (all.size() <= limit) {
            return all;
        }
        return new ArrayList<>(all.subList(all.size() - limit, all.size()));
    }

    /** 全量快照（用于持久化）。 */
    public List<ChatMessage> snapshot() {
        lock.lock();
        try {
            return new ArrayList<>(history);
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return history.size();
        } finally {
            lock.unlock();
        }
    }

    private static boolean accept(final ChatMessage message, final Set<String> include, final Set<String> exclude) {
        final String source = message.source() == null ? "" : message.source().toLowerCase(Locale.ROOT);
        if (include != null && !include.isEmpty() && !include.contains(source)) {
            return false;
        }
        return exclude == null || exclude.isEmpty() || !exclude.contains(source);
    }

    // ------------------------------------------------------------ 订阅关系

    /** @return true 表示这是一个新订阅 */
    public boolean subscribe(final UUID uuid, final String playerName) {
        return subscribers.put(uuid, playerName == null ? "?" : playerName) == null;
    }

    /** @return true 表示确实取消了订阅 */
    public boolean unsubscribe(final UUID uuid) {
        return subscribers.remove(uuid) != null;
    }

    public boolean isSubscribed(final UUID uuid) {
        return subscribers.containsKey(uuid);
    }

    public Map<UUID, String> subscribers() {
        return new LinkedHashMap<>(subscribers);
    }

    public int subscriberCount() {
        return subscribers.size();
    }
}
