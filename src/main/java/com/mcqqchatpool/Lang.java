package com.mcqqchatpool;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文案与格式管理。
 *
 * <p>config.yml 的 {@code messages} 与 {@code formats} 两段都由这里负责：
 * 代码里带一份内置默认值，配置缺键时自动补齐并写回，因此升级插件不会覆盖用户改过的文案。
 *
 * <p>格式里的占位符写成 {@code {名字}}。替换时对每个值做 MiniMessage 转义，
 * 因此玩家可以在消息里随便写 {@code <red>} 而不会影响别人客户端上的显示。
 */
public final class Lang {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    /** 匹配 {name} 形式的占位符。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

    private final McQqChatPoolPlugin plugin;
    private final Map<String, String> messageDefaults = new LinkedHashMap<>();
    private final Map<String, String> formatDefaults = new LinkedHashMap<>();

    private volatile String prefix = "";

    public Lang(final McQqChatPoolPlugin plugin) {
        this.plugin = plugin;

        messageDefaults.put("prefix", "<gray>[</gray><aqua>聊天池</aqua><gray>]</gray> ");
        messageDefaults.put("player-only", "<red>这个命令只能由玩家执行。");
        messageDefaults.put("reload.done", "<green>配置已重新加载。");
        messageDefaults.put("pool.not-found", "<red>没有找到聊天池 <white>{pool}</white><red>，用 <white>/chatpool list</white> 看看有哪些。");
        messageDefaults.put("pool.invalid-name", "<red>聊天池名不合法：只能用字母、数字、下划线和短横线，且以字母或数字开头（最长 32 字符）。");
        messageDefaults.put("pool.empty-message", "<red>消息内容不能为空。");
        messageDefaults.put("pool.too-long", "<red>消息太长了（最多 <white>{max}</white> 个字符）。");
        messageDefaults.put("pool.too-fast", "<red>说得太快了，请等 <white>{ms}</white> 毫秒后再发。");
        messageDefaults.put("pool.mc-disabled", "<red>聊天池 <white>{pool}</white><red> 不允许游戏内发言。");
        messageDefaults.put("pool.sent", "<dark_gray>已发送到聊天池 <white>{pool}</white><dark_gray>（#<white>{id}</white><dark_gray>）");
        messageDefaults.put("pool.joined", "<green>已订阅聊天池 <white>{pool}</white><green>，之后会看到这个池的消息。");
        messageDefaults.put("pool.already-joined", "<gray>你已经在聊天池 <white>{pool}</white><gray> 里了。");
        messageDefaults.put("pool.left", "<yellow>已退出聊天池 <white>{pool}</white><yellow>。");
        messageDefaults.put("pool.not-joined", "<gray>你并没有订阅聊天池 <white>{pool}</white><gray>。");
        messageDefaults.put("pool.list-header", "<gray>聊天池（<white>{count}</white> 个）：");
        messageDefaults.put("pool.list-entry", "<gray> - <white>{pool}</white> <dark_gray>(</dark_gray><aqua>{display}</aqua><dark_gray>)</dark_gray> <gray>在线 <white>{online}</white>/订阅 <white>{members}</white>/历史 <white>{history}</white>{flags}");
        messageDefaults.put("pool.info-header", "<gray>聊天池 <white>{pool}</white> <dark_gray>({display})</dark_gray>");
        messageDefaults.put("pool.info-line", "<gray>  {key}<dark_gray>:</dark_gray> <white>{value}");
        messageDefaults.put("pool.history-header", "<gray>聊天池 <white>{pool}</white> 最近 <white>{count}</white> 条消息：");
        messageDefaults.put("pool.history-entry", "<dark_gray>#<white>{id}</white> <gray>[</gray><yellow>{source}</yellow><gray>]</gray> <white>{sender}</white><gray> » </gray><white>{content}</white>");
        messageDefaults.put("pool.history-empty", "<gray>聊天池 <white>{pool}</white><gray> 还没有消息。");
        messageDefaults.put("pool.who-header", "<gray>聊天池 <white>{pool}</white> 的在线成员（<white>{count}</white>）：");
        messageDefaults.put("pool.who-entry", "<gray> - <white>{player}");
        messageDefaults.put("pool.who-empty", "<gray>聊天池 <white>{pool}</white><gray> 目前没有在线成员。");
        messageDefaults.put("join-hint", "<gray>输入 <white>/chatpool help</white> 查看聊天池用法，用 <white>/chatpool say <文本></white> 把消息发到群里。");
        messageDefaults.put("help", "<gray>聊天池命令：<newline><white>/chatpool list <gray>- 列出所有聊天池<newline><white>/chatpool say <文本> <gray>- 发到默认聊天池<newline><white>/chatpool send <池> <文本> <gray>- 发到指定聊天池<newline><white>/chatpool join <池> <gray>- 订阅一个聊天池<newline><white>/chatpool leave <池> <gray>- 退订一个聊天池<newline><white>/chatpool info <池> <gray>- 查看聊天池详情<newline><white>/chatpool history <池> [条数] <gray>- 查看历史消息<newline><white>/chatpool who <池> <gray>- 查看池内在线成员<newline><white>/chatpool reload <gray>- 重载配置（管理员）<newline><gray>别名：<white>/mcpool<gray>、<white>/mcchat<gray>、<white>/cpool");

        formatDefaults.put("qq-to-mc", "<gray>[</gray><aqua>{pool}</aqua><gray>|</gray><gold>QQ</gold><gray>]</gray> <white>{sender}</white><gray> » </gray><white>{content}</white>");
        formatDefaults.put("mc-to-mc", "<gray>[</gray><aqua>{pool}</aqua><gray>|</gray><green>MC</green><gray>]</gray> <white>{sender}</white><gray> » </gray><white>{content}</white>");
        formatDefaults.put("console-to-mc", "<gray>[</gray><aqua>{pool}</aqua><gray>|</gray><dark_gray>控制台</dark_gray><gray>]</gray> <gray>{content}</gray>");

        reload();
    }

    /** 补齐 config.yml 里缺失的文案键，并刷新缓存的前缀。 */
    public void reload() {
        final FileConfiguration config = plugin.getConfig();
        boolean changed = false;
        for (final Map.Entry<String, String> entry : messageDefaults.entrySet()) {
            final String path = "messages." + entry.getKey();
            if (!config.isSet(path)) {
                config.set(path, entry.getValue());
                changed = true;
            }
        }
        for (final Map.Entry<String, String> entry : formatDefaults.entrySet()) {
            final String path = "formats." + entry.getKey();
            if (!config.isSet(path)) {
                config.set(path, entry.getValue());
                changed = true;
            }
        }
        if (changed) {
            plugin.saveConfig();
        }
        this.prefix = config.getString("messages.prefix", messageDefaults.get("prefix"));
    }

    // ------------------------------------------------------------- 文本读取

    /** 取一条 messages 段的原始文本（MiniMessage 源码）。 */
    public String rawMessage(final String key) {
        final String fallback = messageDefaults.getOrDefault(key, key);
        return plugin.getConfig().getString("messages." + key, fallback);
    }

    /** 取一条 formats 段的原始文本（MiniMessage 源码）。 */
    public String rawFormat(final String key) {
        final String fallback = formatDefaults.getOrDefault(key, key);
        return plugin.getConfig().getString("formats." + key, fallback);
    }

    /** 渲染一条 messages 文案；placeholders 是成对的「键, 值」。 */
    public Component msg(final String key, final String... placeholders) {
        return render(rawMessage(key), pairs(placeholders));
    }

    /** 渲染一条 formats 模板。 */
    public Component format(final String key, final Map<String, String> placeholders) {
        return render(rawFormat(key), placeholders);
    }

    /** 发送带前缀的文案。 */
    public void send(final CommandSender target, final String key, final String... placeholders) {
        target.sendMessage(MINI.deserialize(prefix == null ? "" : prefix).append(msg(key, placeholders)));
    }

    /** 发送不带前缀的文案。 */
    public void sendRaw(final CommandSender target, final String key, final String... placeholders) {
        target.sendMessage(msg(key, placeholders));
    }

    // ------------------------------------------------------------- 渲染实现

    /**
     * 把模板里的 {@code {名字}} 换成转义后的值，再交给 MiniMessage 解析。
     *
     * <p>只有一个正则扫描过程：替换进去的内容不会被再次扫描，
     * 因此玩家消息里写 {@code {sender}} 之类的字面量也不会被二次替换。
     */
    public static Component render(final String template, final Map<String, String> placeholders) {
        if (template == null) {
            return Component.empty();
        }
        final Matcher matcher = PLACEHOLDER.matcher(template);
        final StringBuilder out = new StringBuilder(template.length() + 32);
        while (matcher.find()) {
            final String key = matcher.group(1);
            final String value = placeholders == null ? null : placeholders.get(key);
            if (value == null) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(0)));
            } else {
                matcher.appendReplacement(out, Matcher.quoteReplacement(MINI.escapeTags(value)));
            }
        }
        matcher.appendTail(out);
        return MINI.deserialize(out.toString());
    }

    /** 渲染成不带任何样式的纯文本（需要发给非玩家对象时用）。 */
    public static String plain(final String template, final Map<String, String> placeholders) {
        return PLAIN.serialize(render(template, placeholders));
    }

    private static Map<String, String> pairs(final String... placeholders) {
        final Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            map.put(placeholders[i], placeholders[i + 1]);
        }
        return map;
    }
}
