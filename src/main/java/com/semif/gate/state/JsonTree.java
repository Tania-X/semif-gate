package com.semif.gate.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 最小 JSON 解析器——只服务于「结构化截断」这一个用途。
 *
 * <p>为什么需要它：截断必须产出<b>仍然合法</b>的 JSON。如果直接在字符层面切一刀，
 * 很可能切在字符串中间，得到 {@code {"a":"abcdef}} 这种坏数据，
 * 送进模型会得到无意义的判定，而且这种失败是静默的。
 *
 * <p>本解析器只支持 state 需要的子集（对象、数组、字符串、数字、布尔、null），
 * 且只用于截断，不对外暴露为通用 JSON 库。输入始终是本项目
 * {@link CanonicalJson} 的输出，因此格式是可控的。
 */
final class JsonTree {

    private JsonTree() {
    }

    /** 解析后的 JSON 值。 */
    sealed interface Node permits ObjectNode, ArrayNode, ScalarNode {
    }

    /** 对象：保持插入顺序（输入是规范化 JSON，键已有序）。 */
    record ObjectNode(Map<String, Node> entries) implements Node {
    }

    /** 数组。 */
    record ArrayNode(List<Node> items) implements Node {
    }

    /** 标量：字符串 / 数字 / 布尔 / null，保留原始字面量。 */
    record ScalarNode(String literal) implements Node {
    }

    /**
     * 解析规范化 JSON。
     *
     * @throws IllegalArgumentException 格式非法
     */
    static Node parse(String text) {
        Cursor cursor = new Cursor(text);
        Node node = parseValue(cursor);
        cursor.skipWhitespace();
        if (!cursor.atEnd()) {
            throw new IllegalArgumentException("JSON 尾部存在多余内容，位置 " + cursor.position);
        }
        return node;
    }

    /**
     * 把节点重新序列化为 JSON，并保证总长度不超过 {@code maxCodePoints}。
     *
     * <p>截断策略（确定性）：
     * <ol>
     *   <li>算出必须缩减的码点数量；</li>
     *   <li>从对象尾部丢弃键值对（数组则从尾部丢弃元素）——代价按「一个单位」计；</li>
     *   <li>仍不够时，对最长的字符串标量从尾部裁剪；</li>
     *   <li>把它们标记为显式的截断占位符。</li>
     * </ol>
     * 因为输入是键已排序的规范化 JSON，同样的内容必然得到同样的结果。
     *
     * @return 截断后的节点
     */
    static Node truncate(Node node, int maxCodePoints) {
        int length = codePointLength(toJson(node));
        if (length <= maxCodePoints) {
            return node;
        }
        int excess = length - maxCodePoints;
        Node shrunk = dropTrailingUnits(node, excess);
        if (codePointLength(toJson(shrunk)) <= maxCodePoints) {
            return shrunk;
        }
        int stillExcess = codePointLength(toJson(shrunk)) - maxCodePoints;
        Node clipped = clipLongestString(shrunk, stillExcess);
        return clipped;
    }

    /** 从对象尾部丢弃键值对，或从数组尾部丢弃元素，直到消化掉 excess 个码点。 */
    private static Node dropTrailingUnits(Node node, int excess) {
        if (excess <= 0) {
            return node;
        }
        if (node instanceof ObjectNode object) {
            TreeMap<String, Node> kept = new TreeMap<>(object.entries());
            int dropped = 0;
            List<String> keys = new ArrayList<>(kept.keySet());
            // 从最大键开始丢：规范化 JSON 的尾部就是最大键
            for (int i = keys.size() - 1; i >= 0 && dropped < excess; i--) {
                String key = keys.get(i);
                Node value = kept.get(key);
                dropped += codePointLength(CanonicalJson.writeStringLiteral(key))
                        + 1 + codePointLength(toJson(value)) + 1;
                kept.remove(key);
                if (kept.isEmpty()) {
                    break;
                }
            }
            TreeMap<String, Node> withMarker = new TreeMap<>(kept);
            withMarker.put(TRUNCATION_MARKER, new ScalarNode("true"));
            return new ObjectNode(withMarker);
        }
        if (node instanceof ArrayNode array) {
            List<Node> items = new ArrayList<>(array.items());
            int dropped = 0;
            while (items.size() > 1 && dropped < excess) {
                Node removed = items.remove(items.size() - 1);
                dropped += codePointLength(toJson(removed)) + 1;
            }
            items.add(new ScalarNode("\"" + TRUNCATION_MARKER + "\""));
            return new ArrayNode(items);
        }
        return clipLongestString(node, excess);
    }

    /** 对最长的字符串标量从尾部裁剪。 */
    private static Node clipLongestString(Node node, int excess) {
        if (node instanceof ScalarNode scalar && scalar.literal().startsWith("\"")) {
            String content = scalar.literal();
            // 去掉首尾引号
            content = content.substring(1, content.length() - 1);
            int keep = Math.max(0, codePointLength(content) - excess - TRUNCATION_MARKER.length());
            String clipped = truncateCodePoints(content, keep) + TRUNCATION_MARKER;
            return new ScalarNode(CanonicalJson.writeStringLiteral(clipped));
        }
        if (node instanceof ObjectNode object) {
            TreeMap<String, Node> replaced = new TreeMap<>();
            object.entries().forEach((key, value) -> replaced.put(key, clipLongestString(value, excess)));
            return new ObjectNode(replaced);
        }
        if (node instanceof ArrayNode array) {
            List<Node> replaced = new ArrayList<>();
            array.items().forEach(item -> replaced.add(clipLongestString(item, excess)));
            return new ArrayNode(replaced);
        }
        return node;
    }

    /** 截断标记：让下游一眼看出这份 state 被裁剪过。 */
    static final String TRUNCATION_MARKER = "__semif_truncated__";

    /** 把节点序列化为 JSON 文本。 */
    static String toJson(Node node) {
        StringBuilder out = new StringBuilder(256);
        appendJson(node, out);
        return out.toString();
    }

    private static void appendJson(Node node, StringBuilder out) {
        if (node instanceof ObjectNode object) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, Node> entry : object.entries().entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append(CanonicalJson.writeStringLiteral(entry.getKey())).append(':');
                appendJson(entry.getValue(), out);
            }
            out.append('}');
        } else if (node instanceof ArrayNode array) {
            out.append('[');
            for (int i = 0; i < array.items().size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                appendJson(array.items().get(i), out);
            }
            out.append(']');
        } else if (node instanceof ScalarNode scalar) {
            out.append(scalar.literal());
        }
    }

    static int codePointLength(String text) {
        return text.codePointCount(0, text.length());
    }

    /** 按码点截断，绝不切断代理对。 */
    static String truncateCodePoints(String text, int maxCodePoints) {
        if (maxCodePoints <= 0) {
            return "";
        }
        int count = text.codePointCount(0, text.length());
        if (count <= maxCodePoints) {
            return text;
        }
        int endIndex = text.offsetByCodePoints(0, maxCodePoints);
        return text.substring(0, endIndex);
    }

    // ---------------------------------------------------------------- 解析

    private static final class Cursor {
        private final String text;
        private int position;

        Cursor(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return position >= text.length();
        }

        void skipWhitespace() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
        }

        char peek() {
            if (atEnd()) {
                throw new IllegalArgumentException("JSON 意外结束");
            }
            return text.charAt(position);
        }

        void expect(char expected) {
            if (atEnd() || text.charAt(position) != expected) {
                throw new IllegalArgumentException(
                        "期望 '" + expected + "' 但得到 "
                                + (atEnd() ? "结尾" : "'" + text.charAt(position) + "'")
                                + "，位置 " + position);
            }
            position++;
        }
    }

    private static Node parseValue(Cursor cursor) {
        cursor.skipWhitespace();
        char c = cursor.peek();
        return switch (c) {
            case '{' -> parseObject(cursor);
            case '[' -> parseArray(cursor);
            case '"' -> new ScalarNode(parseStringLiteral(cursor));
            case 't', 'f', 'n' -> parseKeyword(cursor);
            default -> parseNumber(cursor);
        };
    }

    private static Node parseObject(Cursor cursor) {
        cursor.expect('{');
        TreeMap<String, Node> entries = new TreeMap<>();
        cursor.skipWhitespace();
        if (cursor.peek() == '}') {
            cursor.position++;
            return new ObjectNode(entries);
        }
        while (true) {
            cursor.skipWhitespace();
            String key = parseStringLiteral(cursor);
            cursor.skipWhitespace();
            cursor.expect(':');
            entries.put(key, parseValue(cursor));
            cursor.skipWhitespace();
            char next = cursor.peek();
            if (next == ',') {
                cursor.position++;
            } else if (next == '}') {
                cursor.position++;
                return new ObjectNode(entries);
            } else {
                throw new IllegalArgumentException("对象中期望 ',' 或 '}'，位置 " + cursor.position);
            }
        }
    }

    private static Node parseArray(Cursor cursor) {
        cursor.expect('[');
        List<Node> items = new ArrayList<>();
        cursor.skipWhitespace();
        if (cursor.peek() == ']') {
            cursor.position++;
            return new ArrayNode(items);
        }
        while (true) {
            items.add(parseValue(cursor));
            cursor.skipWhitespace();
            char next = cursor.peek();
            if (next == ',') {
                cursor.position++;
            } else if (next == ']') {
                cursor.position++;
                return new ArrayNode(items);
            } else {
                throw new IllegalArgumentException("数组中期望 ',' 或 ']'，位置 " + cursor.position);
            }
        }
    }

    private static String parseStringLiteral(Cursor cursor) {
        cursor.expect('"');
        StringBuilder raw = new StringBuilder();
        raw.append('"');
        while (true) {
            if (cursor.atEnd()) {
                throw new IllegalArgumentException("字符串未闭合");
            }
            char c = cursor.text.charAt(cursor.position++);
            raw.append(c);
            if (c == '\\') {
                if (cursor.atEnd()) {
                    throw new IllegalArgumentException("转义符后意外结束");
                }
                raw.append(cursor.text.charAt(cursor.position++));
                continue;
            }
            if (c == '"') {
                return raw.toString();
            }
        }
    }

    private static Node parseKeyword(Cursor cursor) {
        if (cursor.text.startsWith("true", cursor.position)) {
            cursor.position += 4;
            return new ScalarNode("true");
        }
        if (cursor.text.startsWith("false", cursor.position)) {
            cursor.position += 5;
            return new ScalarNode("false");
        }
        if (cursor.text.startsWith("null", cursor.position)) {
            cursor.position += 4;
            return new ScalarNode("null");
        }
        throw new IllegalArgumentException("非法字面量，位置 " + cursor.position);
    }

    private static Node parseNumber(Cursor cursor) {
        int start = cursor.position;
        while (!cursor.atEnd()) {
            char c = cursor.text.charAt(cursor.position);
            if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || Character.isDigit(c)) {
                cursor.position++;
            } else {
                break;
            }
        }
        if (start == cursor.position) {
            throw new IllegalArgumentException("非法数字，位置 " + start);
        }
        return new ScalarNode(cursor.text.substring(start, cursor.position));
    }
}
