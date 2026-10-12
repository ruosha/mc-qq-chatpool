package com.dsh.mcqqchatpool;

import com.dsh.mcqqchatpool.command.ChatPoolCommand;
import com.dsh.mcqqchatpool.config.PluginConfig;
import com.dsh.mcqqchatpool.http.BridgeHttpServer;
import com.dsh.mcqqchatpool.listener.ChatPoolListener;
import com.dsh.mcqqchatpool.pool.ChatPoolManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/**
 * McQqChatPool —— Minecraft(Paper 26.2) 与 QQ 群聊互通的「聊天池」插件。
 *
 * <p>组件：
 * <ul>
 *   <li>{@link PluginConfig} 配置读取与默认值补齐</li>
 *   <li>{@link Lang} MiniMessage 文案与消息格式</li>
 *   <li>{@link ChatPoolManager} 聊天池、消息缓冲、订阅关系、持久化</li>
 *   <li>{@link BridgeHttpServer} 给本机 AstrBot 用的 HTTP 接口</li>
 *   <li>{@link ChatPoolCommand} {@code /chatpool} 命令树</li>
 *   <li>{@link ChatPoolListener} 进服订阅与可选的原版聊天捕获</li>
 * </ul>
 */
public final class McQqChatPoolPlugin extends JavaPlugin {

    private PluginConfig config;
    private Lang lang;
    private ChatPoolManager pools;
    private BridgeHttpServer http;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.config = new PluginConfig(this);
        config.ensureDefaults();
        config.load();

        this.lang = new Lang(this);

        this.pools = new ChatPoolManager(this);
        pools.load();

        new ChatPoolCommand(this).register();
        getServer().getPluginManager().registerEvents(new ChatPoolListener(this), this);

        this.http = new BridgeHttpServer(this);
        http.start();

        scheduleAutosave();

        getLogger().info("McQqChatPool 已启用：默认聊天池 = " + config.defaultPool()
                + "，HTTP 接口 = " + (http.isRunning() ? "已开启（端口 " + http.port() + "）" : "未开启"));
    }

    @Override
    public void onDisable() {
        if (http != null) {
            http.stop();
        }
        if (pools != null) {
            pools.save();
        }
        getLogger().info("McQqChatPool 已停用。");
    }

    /** 每 save-interval-seconds 秒把有改动的状态落盘一次。 */
    private void scheduleAutosave() {
        final long periodTicks = Math.max(20L, config.saveIntervalSeconds() * 20L);
        getServer().getGlobalRegionScheduler().runAtFixedRate(this,
                task -> pools.saveIfDirty(),
                periodTicks, periodTicks);
    }

    /** {@code /chatpool reload} 用：重载配置、重开 HTTP 接口。 */
    public void reloadEverything() {
        reloadConfig();
        config.ensureDefaults();
        config.load();
        lang.reload();
        pools.reload();
        if (http != null) {
            http.stop();
            http.start();
        }
    }

    // ---------------------------------------------------------------- 调度器

    /**
     * 把一段需要碰 Bukkit 的逻辑送回主线程执行（Folia 下是全局区域线程）。
     *
     * <p>HTTP 线程与异步聊天事件都靠它安全地发布消息。
     */
    public <T> CompletableFuture<T> callSync(final Callable<T> action) {
        final CompletableFuture<T> future = new CompletableFuture<>();
        if (getServer().isPrimaryThread()) {
            try {
                future.complete(action.call());
            } catch (final Throwable throwable) {
                future.completeExceptionally(throwable);
            }
            return future;
        }
        try {
            getServer().getGlobalRegionScheduler().execute(this, () -> {
                try {
                    future.complete(action.call());
                } catch (final Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            });
        } catch (final Throwable throwable) {
            future.completeExceptionally(throwable);
        }
        return future;
    }

    // ---------------------------------------------------------------- 访问器

    public PluginConfig config() {
        return config;
    }

    public Lang lang() {
        return lang;
    }

    public ChatPoolManager pools() {
        return pools;
    }

    public BridgeHttpServer http() {
        return http;
    }
}
