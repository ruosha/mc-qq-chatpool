package com.mcqqchatpool.config;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code security} 段的配置快照（不可变）。
 *
 * <p>这一段的目的是「阻止敏感操作」：外来的注入内容只能以允许的身份出现，
 * 并且在进入游戏之前先过一遍内容过滤。
 *
 * @param filterEnabled         是否启用内容过滤
 * @param action                命中后的处置：reject 丢弃 / censor 打码后放行 / log 只记日志
 * @param blockCommandLike      拦截形如命令的内容（以 / 或 !! 开头）
 * @param blockControlChars     拦截包含控制字符（换行、§ 等）的内容
 * @param blockedKeywords       敏感词（按整词匹配，ASCII 不区分大小写）
 * @param maxLinks              允许的最大链接数，0 表示不限制
 * @param applyToGameChat       是否同样过滤游戏内玩家发言（默认 false，只过滤外部注入）
 * @param allowedExternalSources HTTP 注入允许声明的 source 白名单（默认只有 qq）
 */
public record SecurityConfig(
        boolean filterEnabled,
        FilterAction action,
        boolean blockCommandLike,
        boolean blockControlChars,
        List<String> blockedKeywords,
        int maxLinks,
        boolean applyToGameChat,
        Set<String> allowedExternalSources) {

    /** 命中敏感内容后的处置方式。 */
    public enum FilterAction {
        /** 直接拒绝，消息不入池。 */
        REJECT,
        /** 把命中的词替换成 *** 后放行。 */
        CENSOR,
        /** 放行，但写日志（观察模式，用来先看看会拦到什么）。 */
        LOG;

        public static FilterAction parse(final String raw) {
            if (raw == null) {
                return REJECT;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "censor", "mask", "打码" -> CENSOR;
                case "log", "log-only", "observe", "仅记录" -> LOG;
                default -> REJECT;
            };
        }
    }

    /** 缺省值：过滤开启、拒绝处置、只允许 qq 身份、不动游戏内发言。 */
    public static SecurityConfig defaults() {
        return new SecurityConfig(
                true,
                FilterAction.REJECT,
                true,
                true,
                List.of(),
                0,
                false,
                Set.of("qq"));
    }
}
