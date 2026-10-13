package com.mcqqchatpool.command;

import com.mcqqchatpool.McQqChatPoolPlugin;
import com.mcqqchatpool.pool.ChatMessage;
import com.mcqqchatpool.pool.ChatPool;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /chatpool} 命令树（别名 {@code /mcpool}、{@code /mcchat}、{@code /cpool}）。
 *
 * <p>玩家用 {@code /chatpool send <池> <文本>} 把消息投进指定聊天池；
 * 池里的消息由本机 AstrBot 定时拉走并发到对应的 QQ 群。
 */
public final class ChatPoolCommand {

    private final McQqChatPoolPlugin plugin;

    public ChatPoolCommand(final McQqChatPoolPlugin plugin) {
        this.plugin = plugin;
    }

    /** 通过 Paper 的命令生命周期事件注册命令树。 */
    public void register() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(build(), "聊天池：把游戏内消息发到 QQ 群", List.of("mcpool", "mcchat", "cpool")));
    }

    private LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("chatpool")
                .requires(source -> source.getSender().hasPermission("mcqqchatpool.use"))
                .executes(this::help)
                .then(Commands.literal("help")
                        .executes(this::help))
                .then(Commands.literal("list")
                        .executes(this::list))
                .then(Commands.literal("say")
                        .requires(source -> source.getSender().hasPermission("mcqqchatpool.send"))
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> talk(ctx, null, StringArgumentType.getString(ctx, "message")))))
                .then(Commands.literal("send")
                        .requires(source -> source.getSender().hasPermission("mcqqchatpool.send"))
                        .then(Commands.argument("pool", StringArgumentType.string())
                                .suggests(this::suggestPools)
                                .then(Commands.argument("message", StringArgumentType.greedyString())
                                        .executes(ctx -> talk(ctx,
                                                StringArgumentType.getString(ctx, "pool"),
                                                StringArgumentType.getString(ctx, "message"))))))
                .then(Commands.literal("join")
                        .then(Commands.argument("pool", StringArgumentType.string())
                                .suggests(this::suggestPools)
                                .executes(ctx -> join(ctx, StringArgumentType.getString(ctx, "pool")))))
                .then(Commands.literal("leave")
                        .then(Commands.argument("pool", StringArgumentType.string())
                                .suggests(this::suggestJoinedPools)
                                .executes(ctx -> leave(ctx, StringArgumentType.getString(ctx, "pool")))))
                .then(Commands.literal("info")
                        .then(Commands.argument("pool", StringArgumentType.string())
                                .suggests(this::suggestPools)
                                .executes(ctx -> info(ctx, StringArgumentType.getString(ctx, "pool")))))
                .then(Commands.literal("history")
                        .requires(source -> source.getSender().hasPermission("mcqqchatpool.history"))
                        .then(Commands.argument("pool", StringArgumentType.string())
                                .suggests(this::suggestPools)
                                .executes(ctx -> history(ctx, StringArgumentType.getString(ctx, "pool"), 10))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 50))
                                        .executes(ctx -> history(ctx,
                                                StringArgumentType.getString(ctx, "pool"),
                                                IntegerArgumentType.getInteger(ctx, "count"))))))
                .then(Commands.literal("who")
                        .then(Commands.argument("pool", StringArgumentType.string())
                                .suggests(this::suggestPools)
                                .executes(ctx -> who(ctx, StringArgumentType.getString(ctx, "pool")))))
                .then(Commands.literal("reload")
                        .requires(source -> source.getSender().hasPermission("mcqqchatpool.reload"))
                        .executes(this::reload))
                .build();
    }

    // ---------------------------------------------------------------- 子命令

    private int help(final CommandContext<CommandSourceStack> ctx) {
        plugin.lang().sendRaw(ctx.getSource().getSender(), "help");
        return Command.SINGLE_SUCCESS;
    }

    private int list(final CommandContext<CommandSourceStack> ctx) {
        final CommandSender sender = ctx.getSource().getSender();
        final List<ChatPool> pools = List.copyOf(plugin.pools().pools());
        plugin.lang().sendRaw(sender, "pool.list-header", "count", String.valueOf(pools.size()));
        for (final ChatPool pool : pools) {
            final Map<String, Object> info = plugin.pools().describe(pool);
            plugin.lang().sendRaw(sender, "pool.list-entry",
                    "pool", pool.name(),
                    "display", pool.config().displayName(),
                    "online", String.valueOf(info.get("online")),
                    "members", String.valueOf(info.get("members")),
                    "history", String.valueOf(info.get("history")),
                    "flags", pool.config().defaultJoin() ? " ●默认" : "");
        }
        return Command.SINGLE_SUCCESS;
    }

    /** 解析池名；不合法或不存在时给出提示并返回 null。 */
    private ChatPool resolvePool(final CommandSender sender, final String poolName, final boolean createIfAllowed) {
        final String normalized = com.mcqqchatpool.config.PluginConfig.normalize(poolName);
        if (!com.mcqqchatpool.config.PluginConfig.isValidPoolName(normalized)) {
            plugin.lang().send(sender, "pool.invalid-name");
            return null;
        }
        final ChatPool pool = createIfAllowed ? plugin.pools().getOrCreate(normalized) : plugin.pools().get(normalized);
        if (pool == null) {
            plugin.lang().send(sender, "pool.not-found", "pool", poolName);
        }
        return pool;
    }

    /** {@code /chatpool say <文本>} 与 {@code /chatpool send <池> <文本>} 共用的逻辑。 */
    private int talk(final CommandContext<CommandSourceStack> ctx, final String requestedPool, final String rawContent) {
        final CommandSender sender = ctx.getSource().getSender();

        final String content = rawContent == null ? "" : rawContent.trim();
        if (content.isEmpty()) {
            plugin.lang().send(sender, "pool.empty-message");
            return 0;
        }
        final int max = plugin.config().maxMessageLength();
        if (content.length() > max) {
            plugin.lang().send(sender, "pool.too-long", "max", String.valueOf(max));
            return 0;
        }

        final String poolName = requestedPool == null || requestedPool.isBlank()
                ? plugin.config().defaultPool()
                : requestedPool;
        final ChatPool pool = resolvePool(sender, poolName, true);
        if (pool == null) {
            return 0;
        }
        if (!pool.config().allowFromMc()) {
            plugin.lang().send(sender, "pool.mc-disabled", "pool", pool.name());
            return 0;
        }

        // 玩家发言限速
        if (sender instanceof Player player) {
            final long wait = plugin.pools().cooldownRemaining(player.getUniqueId());
            if (wait > 0L) {
                plugin.lang().send(sender, "pool.too-fast", "ms", String.valueOf(wait));
                return 0;
            }
            plugin.pools().markSpoken(player.getUniqueId());
        }

        final boolean isPlayer = sender instanceof Player;
        final String senderName = isPlayer ? ((Player) sender).getName() : "控制台";
        final String senderId = isPlayer ? ((Player) sender).getUniqueId().toString() : "console";
        final String source = isPlayer ? ChatMessage.SOURCE_MC : ChatMessage.SOURCE_CONSOLE;

        final var result = plugin.pools().publish(pool, source, senderName, senderId, content);
        plugin.lang().send(sender, "pool.sent", "pool", pool.name(), "id", Long.toString(result.id()));
        return Command.SINGLE_SUCCESS;
    }

    private int join(final CommandContext<CommandSourceStack> ctx, final String poolName) {
        final Player player = playerOrNull(ctx);
        if (player == null) {
            return 0;
        }
        final ChatPool pool = resolvePool(player, poolName, true);
        if (pool == null) {
            return 0;
        }
        if (pool.subscribe(player.getUniqueId(), player.getName())) {
            plugin.lang().send(player, "pool.joined", "pool", pool.name());
            plugin.pools().markDirty();
        } else {
            plugin.lang().send(player, "pool.already-joined", "pool", pool.name());
        }
        return Command.SINGLE_SUCCESS;
    }

    private int leave(final CommandContext<CommandSourceStack> ctx, final String poolName) {
        final Player player = playerOrNull(ctx);
        if (player == null) {
            return 0;
        }
        final ChatPool pool = resolvePool(player, poolName, false);
        if (pool == null) {
            return 0;
        }
        if (pool.unsubscribe(player.getUniqueId())) {
            plugin.lang().send(player, "pool.left", "pool", pool.name());
            plugin.pools().markDirty();
            return Command.SINGLE_SUCCESS;
        }
        plugin.lang().send(player, "pool.not-joined", "pool", pool.name());
        return 0;
    }

    private int info(final CommandContext<CommandSourceStack> ctx, final String poolName) {
        final CommandSender sender = ctx.getSource().getSender();
        final ChatPool pool = resolvePool(sender, poolName, false);
        if (pool == null) {
            return 0;
        }
        final Map<String, Object> info = plugin.pools().describe(pool);
        plugin.lang().sendRaw(sender, "pool.info-header", "pool", pool.name(), "display", pool.config().displayName());
        line(sender, "说明", String.valueOf(info.get("description")));
        line(sender, "在线 / 订阅", info.get("online") + " / " + info.get("members"));
        line(sender, "历史条数", String.valueOf(info.get("history")));
        line(sender, "历史保留上限", info.get("retention_cap")
                + (Boolean.TRUE.equals(info.get("retain_all")) ? "（retain-all：不裁剪）" : ""));
        line(sender, "安全过滤", String.valueOf(info.get("security_filter")));
        line(sender, "消息游标", String.valueOf(info.get("cursor")));
        line(sender, "默认订阅", pool.config().defaultJoin() ? "是" : "否");
        line(sender, "允许 QQ 注入", pool.config().allowFromQq() ? "是" : "否");
        line(sender, "允许游戏内发言", pool.config().allowFromMc() ? "是" : "否");
        line(sender, "捕获原版聊天", pool.config().captureVanillaChat() ? "是" : "否");
        return Command.SINGLE_SUCCESS;
    }

    private void line(final CommandSender sender, final String key, final String value) {
        plugin.lang().sendRaw(sender, "pool.info-line", "key", key, "value", value);
    }

    private int history(final CommandContext<CommandSourceStack> ctx, final String poolName, final int count) {
        final CommandSender sender = ctx.getSource().getSender();
        final ChatPool pool = resolvePool(sender, poolName, false);
        if (pool == null) {
            return 0;
        }
        final List<ChatMessage> messages = pool.tail(count, java.util.Set.of(), java.util.Set.of());
        if (messages.isEmpty()) {
            plugin.lang().sendRaw(sender, "pool.history-empty", "pool", pool.name());
            return 0;
        }
        plugin.lang().sendRaw(sender, "pool.history-header",
                "pool", pool.name(), "count", String.valueOf(messages.size()));
        for (final ChatMessage message : messages) {
            plugin.lang().sendRaw(sender, "pool.history-entry",
                    "id", Long.toString(message.id()),
                    "source", message.source(),
                    "sender", message.sender(),
                    "content", message.content());
        }
        return Command.SINGLE_SUCCESS;
    }

    private int who(final CommandContext<CommandSourceStack> ctx, final String poolName) {
        final CommandSender sender = ctx.getSource().getSender();
        final ChatPool pool = resolvePool(sender, poolName, false);
        if (pool == null) {
            return 0;
        }
        final List<String> names = plugin.pools().onlineSubscriberNames(pool);
        if (names.isEmpty()) {
            plugin.lang().sendRaw(sender, "pool.who-empty", "pool", pool.name());
            return 0;
        }
        plugin.lang().sendRaw(sender, "pool.who-header", "pool", pool.name(), "count", String.valueOf(names.size()));
        for (final String name : names) {
            plugin.lang().sendRaw(sender, "pool.who-entry", "player", name);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int reload(final CommandContext<CommandSourceStack> ctx) {
        plugin.reloadEverything();
        plugin.lang().send(ctx.getSource().getSender(), "reload.done");
        return Command.SINGLE_SUCCESS;
    }

    // -------------------------------------------------------------- 工具方法

    private Player playerOrNull(final CommandContext<CommandSourceStack> ctx) {
        final CommandSender sender = ctx.getSource().getSender();
        if (sender instanceof Player player) {
            return player;
        }
        plugin.lang().send(sender, "player-only");
        return null;
    }

    private CompletableFuture<Suggestions> suggestPools(final CommandContext<CommandSourceStack> ctx,
                                                        final SuggestionsBuilder builder) {
        for (final String name : plugin.pools().poolNames()) {
            builder.suggest(name);
        }
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestJoinedPools(final CommandContext<CommandSourceStack> ctx,
                                                              final SuggestionsBuilder builder) {
        if (ctx.getSource().getSender() instanceof Player player) {
            for (final ChatPool pool : plugin.pools().pools()) {
                if (pool.isSubscribed(player.getUniqueId())) {
                    builder.suggest(pool.name());
                }
            }
        }
        return builder.buildFuture();
    }
}
