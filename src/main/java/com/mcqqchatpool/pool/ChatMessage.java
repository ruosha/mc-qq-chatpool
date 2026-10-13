package com.mcqqchatpool.pool;

import com.mcqqchatpool.http.Json;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 聊天池里的一条消息。
 *
 * @param id       全局自增 ID（跨池唯一，客户端用它做游标）
 * @param pool     所属聊天池
 * @param source   来源：{@link #SOURCE_MC} / {@link #SOURCE_QQ} / {@link #SOURCE_CONSOLE} / {@link #SOURCE_SYSTEM}
 * @param sender   发送者显示名
 * @param senderId 发送者标识（MC 玩家是 UUID，QQ 是 QQ 号，控制台是 "console"）
 * @param content  消息正文（纯文本）
 * @param time     毫秒时间戳
 */
public record ChatMessage(
        long id,
        String pool,
        String source,
        String sender,
        String senderId,
        String content,
        long time
) {

    public static final String SOURCE_MC = "mc";
    public static final String SOURCE_QQ = "qq";
    public static final String SOURCE_CONSOLE = "console";
    public static final String SOURCE_SYSTEM = "system";

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    /** ISO-8601 UTC 时间，方便对端直接展示。 */
    public String isoTime() {
        return ISO.format(Instant.ofEpochMilli(time));
    }

    /** 渲染成 HTTP 接口返回的 JSON 对象。 */
    public String toJson() {
        return Json.Obj.create()
                .put("id", id)
                .put("pool", pool)
                .put("source", source)
                .put("sender", sender)
                .put("sender_id", senderId)
                .put("content", content)
                .put("time", isoTime())
                .put("time_ms", time)
                .toString();
    }

    /** 渲染 {@code {pool} {sender} {content} ...} 形式占位符用的键值对。 */
    public Map<String, String> placeholders() {
        return Map.of(
                "id", Long.toString(id),
                "pool", pool,
                "source", source,
                "sender", sender,
                "sender_id", senderId == null ? "" : senderId,
                "content", content,
                "time", isoTime()
        );
    }

    /** 写进 state.yml 的形式。 */
    public Map<String, Object> toMap() {
        final Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("id", id);
        map.put("source", source);
        map.put("sender", sender);
        map.put("sender-id", senderId);
        map.put("content", content);
        map.put("time", time);
        return map;
    }

    /** 从 state.yml 读回来。 */
    public static ChatMessage fromMap(final String pool, final Map<?, ?> map) {
        return new ChatMessage(
                asLong(map.get("id")),
                pool,
                str(map, "source", SOURCE_SYSTEM),
                str(map, "sender", "?"),
                str(map, "sender-id", ""),
                str(map, "content", ""),
                asLong(map.get("time"))
        );
    }

    private static String str(final Map<?, ?> map, final String key, final String fallback) {
        final Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static long asLong(final Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String str) {
            try {
                return Long.parseLong(str.trim());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }
}
