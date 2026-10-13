package com.mcqqchatpool.listener;

import com.mcqqchatpool.McQqChatPoolPlugin;
import com.mcqqchatpool.pool.ChatMessage;
import com.mcqqchatpool.pool.ChatPool;
import com.mcqqchatpool.security.MessageFilter;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * 玩家事件监听：进服自动订阅默认聊天池；（可选）把原版聊天写进聊天池。
 */
public final class ChatPoolListener implements Listener {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final McQqChatPoolPlugin plugin;

    public ChatPoolListener(final McQqChatPoolPlugin plugin) {
        this.plugin = plugin;
    }

    /** 进服：同步名字、自动订阅默认池、给出用法提示。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        plugin.pools().refreshName(player);
        plugin.pools().assignDefaultPools(player);

        if (plugin.config().joinHint() && player.hasPermission("mcqqchatpool.use")) {
            plugin.lang().sendRaw(player, "join-hint");
        }
    }

    /**
     * 原版聊天 → 聊天池（只对开启了 {@code capture-vanilla-chat} 的池生效）。
     *
     * <p>这个事件是异步的，所以只做「读取消息 + 丢回主线程发布」，不碰任何 Bukkit 状态。
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onChat(final AsyncChatEvent event) {
        boolean anyPool = false;
        for (final ChatPool pool : plugin.pools().pools()) {
            if (pool.config().captureVanillaChat()) {
                anyPool = true;
                break;
            }
        }
        if (!anyPool) {
            return;
        }

        final Player player = event.getPlayer();
        String content = PLAIN.serialize(event.message()).trim();
        if (content.isEmpty()) {
            return;
        }
        if (content.length() > plugin.config().maxMessageLength()) {
            return;
        }

        // security.filter.apply-to-game-chat 打开时，游戏内发言也过滤一遍：
        // 命中敏感内容就不写进聊天池（原版聊天照常显示，只是不转发给 QQ）。
        if (plugin.config().security().applyToGameChat()) {
            final MessageFilter.Outcome outcome = plugin.messageFilter().check(content);
            if (!outcome.allowed()) {
                plugin.lang().send(player, "security.blocked", "reason", outcome.reason());
                plugin.getLogger().info("拦下了玩家 " + player.getName() + " 的发言（"
                        + outcome.reason() + "），未写入聊天池。");
                return;
            }
            content = outcome.text();
        }

        for (final ChatPool pool : plugin.pools().pools()) {
            if (pool.config().captureVanillaChat()) {
                plugin.pools().publishAsync(pool, ChatMessage.SOURCE_MC, player.getName(),
                        player.getUniqueId().toString(), content);
            }
        }
    }
}
