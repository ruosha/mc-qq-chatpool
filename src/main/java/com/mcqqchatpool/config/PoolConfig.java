package com.mcqqchatpool.config;

import org.bukkit.configuration.ConfigurationSection;

/**
 * 单个聊天池的配置。
 *
 * @param name               池名（命令与 HTTP 接口里用的标识，小写）
 * @param displayName        展示名
 * @param description        说明文字
 * @param defaultJoin        玩家进服是否自动订阅
 * @param maxHistory         历史消息保留条数
 * @param captureVanillaChat 是否把原版聊天栏内容也写进本池
 * @param allowFromQq        是否允许 QQ 侧往本池注入消息
 * @param allowFromMc        是否允许游戏内命令往本池发言
 */
public record PoolConfig(
        String name,
        String displayName,
        String description,
        boolean defaultJoin,
        int maxHistory,
        boolean captureVanillaChat,
        boolean allowFromQq,
        boolean allowFromMc
) {

    /** 配置里没定义的池按这份默认值临时创建。 */
    public static PoolConfig defaults(final String name, final int maxHistory) {
        return new PoolConfig(name, name, "", false, maxHistory, false, true, true);
    }

    /** 从 config.yml 的 {@code pools.<name>} 段读取，缺项用 fallback 补齐。 */
    public static PoolConfig from(final String name, final ConfigurationSection section, final int fallbackMaxHistory) {
        if (section == null) {
            return defaults(name, fallbackMaxHistory);
        }
        return new PoolConfig(
                name,
                section.getString("display-name", name),
                section.getString("description", ""),
                section.getBoolean("default-join", false),
                Math.max(1, section.getInt("max-history", fallbackMaxHistory)),
                section.getBoolean("capture-vanilla-chat", false),
                section.getBoolean("allow-from-qq", true),
                section.getBoolean("allow-from-mc", true)
        );
    }
}
