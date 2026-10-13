package com.mcqqchatpool.http;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 工具：只服务于本插件的 HTTP 接口，不引入任何第三方依赖。
 *
 * <p>提供两件事：
 * <ul>
 *   <li>{@link Obj} —— 手写响应体的链式构造器；</li>
 *   <li>{@link #parse(String)} —— 递归下降解析器，把请求体解析成
 *       {@code Map/List/String/Double/Boolean/null}。</li>
 * </ul>
 */
public final class Json {

    private Json() {
    }

    // ================================================================ 序列化

    /** 转义一个字符串，使其可以安全地放进 JSON 字符串字面量里。 */
    public static String escape(final String raw) {
        if (raw == null) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** 把字符串包成 JSON 字符串字面量。 */
    public static String quote(final String raw) {
        return "\"" + escape(raw) + "\"";
    }

    /** JSON 对象构造器。 */
    public static final class Obj {

        private final StringBuilder sb = new StringBuilder(256);
        private boolean first = true;

        private Obj() {
            sb.append('{');
        }

        public static Obj create() {
            return new Obj();
        }

        private void key(final String name) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(quote(name)).append(':');
        }

        public Obj put(final String name, final String value) {
            key(name);
            sb.append(value == null ? "null" : quote(value));
            return this;
        }

        public Obj put(final String name, final long value) {
            key(name);
            sb.append(value);
            return this;
        }

        public Obj put(final String name, final boolean value) {
            key(name);
            sb.append(value);
            return this;
        }

        /** 直接塞入一段已经拼好的 JSON（对象、数组、数字皆可）。 */
        public Obj putRaw(final String name, final String rawJson) {
            key(name);
            sb.append(rawJson == null ? "null" : rawJson);
            return this;
        }

        /** 字符串数组。 */
        public Obj putStrings(final String name, final Collection<String> values) {
            key(name);
            sb.append('[');
            boolean firstItem = true;
            if (values != null) {
                for (final String value : values) {
                    if (!firstItem) {
                        sb.append(',');
                    }
                    firstItem = false;
                    sb.append(quote(value));
                }
            }
            sb.append(']');
            return this;
        }

        /** 由已拼好的 JSON 片段组成的数组。 */
        public Obj putRawArray(final String name, final Collection<String> rawItems) {
            key(name);
            sb.append('[');
            boolean firstItem = true;
            if (rawItems != null) {
                for (final String item : rawItems) {
                    if (!firstItem) {
                        sb.append(',');
                    }
                    firstItem = false;
                    sb.append(item);
                }
            }
            sb.append(']');
            return this;
        }

        @Override
        public String toString() {
            return sb.toString() + "}";
        }
    }

    // ================================================================== 解析

    /** 解析任意 JSON 文本。 */
    public static Object parse(final String text) {
        if (text == null) {
            throw new IllegalArgumentException("JSON 内容为空");
        }
        final Parser parser = new Parser(text);
        parser.skipWhitespace();
        final Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.eof()) {
            throw new IllegalArgumentException("JSON 尾部存在多余内容（位置 " + parser.pos + "）");
        }
        return value;
    }

    /** 解析一个 JSON 对象；不是对象则抛异常。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(final String text) {
        final Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("请求体必须是一个 JSON 对象");
        }
        return (Map<String, Object>) value;
    }

    /** 取字符串字段。 */
    public static String string(final Map<String, Object> map, final String key, final String fallback) {
        final Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        return String.valueOf(value);
    }

    /** 取整数字段。 */
    public static long number(final Map<String, Object> map, final String key, final long fallback) {
        final Object value = map.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String str) {
            try {
                return Long.parseLong(str.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    /** 取布尔字段。 */
    public static boolean bool(final Map<String, Object> map, final String key, final boolean fallback) {
        final Object value = map.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String str) {
            return switch (str.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "true", "1", "yes", "on" -> true;
                case "false", "0", "no", "off" -> false;
                default -> fallback;
            };
        }
        return fallback;
    }

    /** 递归下降解析器。 */
    private static final class Parser {

        private final String src;
        private int pos;

        private Parser(final String src) {
            this.src = src;
        }

        private boolean eof() {
            return pos >= src.length();
        }

        private char peek() {
            if (eof()) {
                throw new IllegalArgumentException("JSON 意外结束");
            }
            return src.charAt(pos);
        }

        private void skipWhitespace() {
            while (!eof() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        private void expect(final char expected) {
            skipWhitespace();
            if (eof() || src.charAt(pos) != expected) {
                throw new IllegalArgumentException("JSON 位置 " + pos + " 期望字符 '" + expected + "'");
            }
            pos++;
        }

        private Object readValue() {
            skipWhitespace();
            final char c = peek();
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject() {
            expect('{');
            final Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (!eof() && peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                final String name = readString();
                expect(':');
                map.put(name, readValue());
                skipWhitespace();
                final char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == '}') {
                    pos++;
                    return map;
                }
                throw new IllegalArgumentException("JSON 位置 " + pos + " 期望 ',' 或 '}'");
            }
        }

        private List<Object> readArray() {
            expect('[');
            final List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (!eof() && peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWhitespace();
                final char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == ']') {
                    pos++;
                    return list;
                }
                throw new IllegalArgumentException("JSON 位置 " + pos + " 期望 ',' 或 ']'");
            }
        }

        private String readString() {
            skipWhitespace();
            if (peek() != '"') {
                throw new IllegalArgumentException("JSON 位置 " + pos + " 期望字符串");
            }
            pos++;
            final StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) {
                    throw new IllegalArgumentException("JSON 字符串未闭合");
                }
                final char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (eof()) {
                    throw new IllegalArgumentException("JSON 转义未结束");
                }
                final char esc = src.charAt(pos++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > src.length()) {
                            throw new IllegalArgumentException("JSON \\u 转义不完整");
                        }
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw new IllegalArgumentException("未知的 JSON 转义 \\" + esc);
                }
            }
        }

        private Object readLiteral(final String literal, final Object value) {
            if (!src.startsWith(literal, pos)) {
                throw new IllegalArgumentException("JSON 位置 " + pos + " 期望 " + literal);
            }
            pos += literal.length();
            return value;
        }

        private Object readNumber() {
            final int start = pos;
            if (!eof() && (peek() == '-' || peek() == '+')) {
                pos++;
            }
            while (!eof()) {
                final char c = src.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    pos++;
                } else {
                    break;
                }
            }
            if (start == pos) {
                throw new IllegalArgumentException("JSON 位置 " + pos + " 无法识别的值");
            }
            try {
                return Double.valueOf(src.substring(start, pos));
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("JSON 位置 " + start + " 数字格式错误: " + src.substring(start, pos));
            }
        }
    }
}
