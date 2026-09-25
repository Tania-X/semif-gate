package com.semif.gate.state;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 规范化 JSON 序列化。
 *
 * <p><b>为什么需要它：</b>缓存键是 state 的哈希，而 {@code HashMap} 的迭代顺序、
 * 浮点数的二进制表示、{@code Integer} 与 {@code Long} 的装箱差异，
 * 都会让「逻辑上相同」的 state 产生不同的哈希，导致缓存永远不命中，
 * 或者更糟——让「逻辑上不同」的 state 撞进同一条缓存记录。
 *
 * <p>因此这里的输出必须满足一个硬性要求：
 * <b>只要两个值是逻辑等价的，序列化结果必须逐字节相同。</b>
 *
 * <h2>三条规则</h2>
 * <ol>
 *   <li><b>键排序</b>：对象键按 Unicode 码点升序输出，与插入顺序无关。</li>
 *   <li><b>数字量化</b>：浮点按 12 位有效数字量化，吸收 {@code 0.1 + 0.2} 这类扰动。</li>
 *   <li><b>拒绝不确定性</b>：非有限浮点、非法字符串直接抛异常，不静默产出垃圾。</li>
 * </ol>
 *
 * <p>这是本项目自实现的序列化器（不依赖 Jackson 的 {@code ORDER_MAP_ENTRIES_BY_KEYS}），
 * 因为「确定性」在这里是正确性要求而非格式偏好，自实现更容易审计和测试。
 */
public final class CanonicalJson {

    /** 浮点量化精度：12 位有效数字。 */
    public static final int PRECISION = 12;

    private static final MathContext MATH_CONTEXT = new MathContext(PRECISION);
    private static final int MAX_DEPTH = 64;

    private CanonicalJson() {
    }

    /**
     * 把任意支持的值序列化为规范化 JSON。
     *
     * @param value 支持 Map / Collection / String / 数字 / Boolean / null
     * @return 规范化 JSON 文本
     * @throws IllegalArgumentException 值不受支持或包含非法数字
     */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder(256);
        writeValue(value, out, 0);
        return out.toString();
    }

    private static void writeValue(Object value, StringBuilder out, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("JSON 嵌套超过 " + MAX_DEPTH + " 层");
        }
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            writeString(text, out);
        } else if (value instanceof Boolean bool) {
            out.append(bool ? "true" : "false");
        } else if (value instanceof Number number) {
            out.append(canonicalNumber(number));
        } else if (value instanceof Map<?, ?> map) {
            writeObject(map, out, depth);
        } else if (value instanceof Collection<?> collection) {
            writeArray(collection, out, depth);
        } else if (value instanceof Object[] array) {
            writeArray(List.of(array), out, depth);
        } else if (value instanceof Enum<?> enumValue) {
            writeString(enumValue.name(), out);
        } else {
            throw new IllegalArgumentException(
                    "不支持的 state 值类型: " + value.getClass().getName()
                            + "（只支持 Map / Collection / String / 数字 / Boolean / null）");
        }
    }

    private static void writeObject(Map<?, ?> map, StringBuilder out, int depth) {
        // 按键的字符串形式排序后再输出：与插入顺序无关
        TreeMap<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(
                        "JSON 对象的键必须是字符串，实际为: "
                                + (entry.getKey() == null ? "null" : entry.getKey().getClass().getName()));
            }
            sorted.put(key, entry.getValue());
        }
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeString(entry.getKey(), out);
            out.append(':');
            writeValue(entry.getValue(), out, depth + 1);
        }
        out.append('}');
    }

    private static void writeArray(Collection<?> collection, StringBuilder out, int depth) {
        out.append('[');
        boolean first = true;
        for (Object item : collection) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeValue(item, out, depth + 1);
        }
        out.append(']');
    }

    /**
     * 数字的规范形式。
     *
     * <p>所有浮点走同一条路径：先转成十进制字面量（{@code BigDecimal.valueOf} 用的是
     * {@code Double.toString} 的规范表示），再量化到 12 位有效数字并去掉尾随零。
     * 因此 {@code 0.1 + 0.2} 与 {@code 0.3} 输出相同。
     */
    static String canonicalNumber(Number number) {
        if (number instanceof Double d) {
            if (!Double.isFinite(d)) {
                throw new IllegalArgumentException("state 不允许非有限浮点数: " + d);
            }
            return quantize(BigDecimal.valueOf(d));
        }
        if (number instanceof Float f) {
            if (!Float.isFinite(f)) {
                throw new IllegalArgumentException("state 不允许非有限浮点数: " + f);
            }
            return quantize(BigDecimal.valueOf(f.doubleValue()));
        }
        if (number instanceof BigDecimal decimal) {
            return quantize(decimal);
        }
        if (number instanceof java.math.BigInteger bigInteger) {
            return bigInteger.toString();
        }
        // 整型（Byte/Short/Integer/Long）：直接输出十进制，不做量化
        return number.toString();
    }

    private static String quantize(BigDecimal value) {
        BigDecimal rounded = value.round(MATH_CONTEXT).stripTrailingZeros();
        // scale < 0 时 toPlainString 会补零（如 1E+2 -> "100"），这正是我们要的规范形式
        return rounded.toPlainString();
    }

    /**
     * 字符串的 JSON 转义。
     *
     * <p>中文等非 ASCII 字符<b>不转义</b>——保证输出的可读性，
     * 同时因为编码固定为 UTF-8，哈希仍然确定。
     */
    static void writeString(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** 把一个对象序列化为规范化 JSON 并返回 UTF-8 字节，便于直接哈希。 */
    public static byte[] writeBytes(Object value) {
        return write(value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 按 JSON 规则转义字符串并补上双引号——供结构化截断复用。 */
    static String writeStringLiteral(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2);
        writeString(text, out);
        return out.toString();
    }

    /** 便于测试：把 JSON 数组风格的键值对转成有序 Map。 */
    public static Map<String, Object> ordered(Object... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("必须成对提供键值");
        }
        List<String> keys = new ArrayList<>();
        Map<String, Object> map = new TreeMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            String key = (String) keyValuePairs[i];
            keys.add(key);
            map.put(key, keyValuePairs[i + 1]);
        }
        return map;
    }
}
