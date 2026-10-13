package com.mcqqchatpool.security;

import com.mcqqchatpool.config.SecurityConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内容过滤：把「敏感操作」挡在聊天池之外。
 *
 * <p>检查顺序（先命中先生效）：控制字符 → 命令样式 → 敏感词 → 链接数量。
 * 命中后按 {@link SecurityConfig.FilterAction} 处置：
 * <ul>
 *   <li>{@code reject} —— 拒绝，消息不入池（HTTP 侧回 403 并带上原因）；</li>
 *   <li>{@code censor} —— 把命中的片段替换成 {@value #MASK} 后放行；</li>
 *   <li>{@code log} —— 放行，只记日志（观察模式）。</li>
 * </ul>
 *
 * <p>本类是纯函数式的：不碰 Bukkit，可安全地在任何线程调用。
 */
public final class MessageFilter {

    /** 打码后的替换文本。 */
    public static final String MASK = "***";

    private static final Pattern LINK = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");
    private static final Pattern COMMAND_LIKE = Pattern.compile("^(?:/|!!|／)");

    private final SecurityConfig config;
    private final Pattern keywordPattern;

    public MessageFilter(final SecurityConfig config) {
        this.config = config;
        this.keywordPattern = buildKeywordPattern(config.blockedKeywords());
    }

    /** 过滤结果。 */
    public record Outcome(boolean allowed, String text, String reason, boolean censored) {

        static Outcome pass(final String text) {
            return new Outcome(true, text, null, false);
        }
    }

    /**
     * 检查一条内容。
     *
     * @param raw 原始内容
     * @return 处置结果：{@code allowed=false} 表示应当丢弃；否则用 {@code text()} 作为最终内容
     */
    public Outcome check(final String raw) {
        final String text = raw == null ? "" : raw;
        if (!config.filterEnabled()) {
            return Outcome.pass(text);
        }

        if (config.blockControlChars() && containsControlChar(text)) {
            return decide(text, "包含控制字符（如换行、制表符或 § 等）", null);
        }
        if (config.blockCommandLike() && COMMAND_LIKE.matcher(text).find()) {
            return decide(text, "看起来是一条命令（以 / 或 !! 开头）", null);
        }
        if (keywordPattern != null) {
            final Matcher matcher = keywordPattern.matcher(text);
            if (matcher.find()) {
                final String hit = matcher.group();
                return decide(text, "命中敏感词「" + hit + "」", matcher);
            }
        }
        if (config.maxLinks() > 0 && countLinks(text) > config.maxLinks()) {
            return decide(text, "链接数量超过 " + config.maxLinks() + " 个", null);
        }
        return Outcome.pass(text);
    }

    /** 按配置的处置方式给出结果。{@code matcher} 非空时用于打码替换。 */
    private Outcome decide(final String text, final String reason, final Matcher matcher) {
        return switch (config.action()) {
            case REJECT -> new Outcome(false, text, reason, false);
            case LOG -> new Outcome(true, text, reason, false);
            case CENSOR -> new Outcome(true, censor(text, matcher), reason, true);
        };
    }

    /** 打码：敏感词替换成 ***，控制字符直接去掉。 */
    private String censor(final String text, final Matcher matcher) {
        String out = text;
        if (matcher != null) {
            out = matcher.replaceAll(Matcher.quoteReplacement(MASK));
        }
        if (config.blockControlChars()) {
            out = stripControlChars(out);
        }
        return out;
    }

    private static int countLinks(final String text) {
        final Matcher matcher = LINK.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /** 控制字符：C0/C1、DEL，以及 MiniMessage 用来做样式注入的 § 与零宽字符。 */
    public static boolean containsControlChar(final String text) {
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c == '\u00a7' || c == '\u200b' || c == '\ufeff' || c < 0x20 || c == 0x7f
                    || (c >= 0x80 && c <= 0x9f)) {
                return true;
            }
        }
        return false;
    }

    private static String stripControlChars(final String text) {
        final StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c == '\u00a7' || c == '\u200b' || c == '\ufeff' || c < 0x20 || c == 0x7f
                    || (c >= 0x80 && c <= 0x9f)) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 敏感词按「整词」匹配，避免 give 命中 giveaway 这类误伤。
     *
     * <p>ASCII 词用 {@code (?<![a-z0-9_])…(?![a-z0-9_])} 界定；含非 ASCII 的词直接
     * 用 {@link Pattern#quote} 子串匹配（中文没有词边界的概念）。
     */
    private static Pattern buildKeywordPattern(final List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return null;
        }
        final List<String> parts = new ArrayList<>();
        for (final String raw : keywords) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            final String word = raw.trim();
            if (isAsciiWord(word)) {
                parts.add("(?<![a-z0-9_])" + Pattern.quote(word.toLowerCase(Locale.ROOT)) + "(?![a-z0-9_])");
            } else {
                parts.add(Pattern.quote(word));
            }
        }
        if (parts.isEmpty()) {
            return null;
        }
        return Pattern.compile(String.join("|", parts), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static boolean isAsciiWord(final String word) {
        for (int i = 0; i < word.length(); i++) {
            if (word.charAt(i) > 0x7f) {
                return false;
            }
        }
        return true;
    }
}
