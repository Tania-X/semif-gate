package com.semif.gate.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CanonicalJson} 的确定性契约。
 *
 * <p>这是整个缓存体系的地基：如果序列化不确定，上层所有哈希都不确定。
 */
class CanonicalJsonTest {

    @Test
    @DisplayName("HashMap 与 TreeMap 输出相同——迭代顺序不影响结果")
    void mapImplementationDoesNotMatter() {
        Map<String, Object> hashMap = new HashMap<>();
        hashMap.put("z", 1);
        hashMap.put("a", 2);
        hashMap.put("m", 3);

        Map<String, Object> treeMap = new TreeMap<>(hashMap);
        Map<String, Object> linked = new LinkedHashMap<>();
        linked.put("m", 3);
        linked.put("z", 1);
        linked.put("a", 2);

        String expected = "{\"a\":2,\"m\":3,\"z\":1}";
        assertEquals(expected, CanonicalJson.write(hashMap));
        assertEquals(expected, CanonicalJson.write(treeMap));
        assertEquals(expected, CanonicalJson.write(linked));
    }

    @Test
    @DisplayName("数字量化到 12 位有效数字")
    void numbersAreQuantized() {
        assertEquals("0.3", CanonicalJson.write(0.1 + 0.2));
        assertEquals("1", CanonicalJson.write(1.0));
        assertEquals("-2.5", CanonicalJson.write(-2.5));
        assertEquals("480", CanonicalJson.write(480));
        assertEquals("480", CanonicalJson.write(480L));
        assertEquals("480", CanonicalJson.write((short) 480));
        assertEquals("0.000000000001", CanonicalJson.write(1e-12));
    }

    @Test
    @DisplayName("整型与浮点表示同一数值时输出一致")
    void integerAndFloatingPointAgree() {
        assertEquals(CanonicalJson.write(2), CanonicalJson.write(2.0));
        assertEquals(CanonicalJson.write(100L), CanonicalJson.write(1.0e2));
    }

    @Test
    @DisplayName("BigDecimal 与 BigInteger 得到稳定输出")
    void bigNumbersAreHandled() {
        assertEquals("123456789012345678901234567890",
                CanonicalJson.write(new BigInteger("123456789012345678901234567890")));
        assertEquals("1.5", CanonicalJson.write(new BigDecimal("1.5000")));
    }

    @Test
    @DisplayName("极端指数不会产生超长输出（scale 归一到可读形式）")
    void extremeExponentDoesNotExplode() {
        String written = CanonicalJson.write(1e300);
        assertTrue(written.length() <= 320, "输出长度应可控，实际 " + written.length());
        assertEquals("1" + "0".repeat(300), written);
    }

    @Test
    @DisplayName("非有限浮点被拒绝，而不是输出 null 或 NaN 字面量")
    void nonFiniteIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(Float.NaN));
    }

    @Test
    @DisplayName("字符串转义符合 JSON 规则，中文不转义")
    void stringsAreEscaped() {
        assertEquals("\"hello\"", CanonicalJson.write("hello"));
        assertEquals("\"a\\\"b\"", CanonicalJson.write("a\"b"));
        assertEquals("\"line1\\nline2\"", CanonicalJson.write("line1\nline2"));
        assertEquals("\"tab\\there\"", CanonicalJson.write("tab\there"));
        assertEquals("\"back\\\\slash\"", CanonicalJson.write("back\\slash"));
        // 中文保持原样，保证可读性；编码固定 UTF-8，哈希仍然确定
        assertEquals("\"中文\"", CanonicalJson.write("中文"));
        // 控制字符必须转义，否则产出的不是合法 JSON
        assertTrue(CanonicalJson.write("\u0001").contains("\\u0001"));
    }

    @Test
    @DisplayName("数组保持顺序（顺序是语义），对象排序（顺序不是语义）")
    void arraysPreserveOrderObjectsDoNot() {
        assertEquals("[1,2,3]", CanonicalJson.write(List.of(1, 2, 3)));
        assertEquals("[3,2,1]", CanonicalJson.write(List.of(3, 2, 1)));

        Map<String, Object> first = new LinkedHashMap<>();
        first.put("b", 1);
        first.put("a", 2);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("a", 2);
        second.put("b", 1);
        assertEquals(CanonicalJson.write(first), CanonicalJson.write(second));
    }

    @Test
    @DisplayName("嵌套结构递归规范化")
    void nestedStructuresAreCanonical() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("outer", List.of(
                new LinkedHashMap<>(Map.of("z", 0.1 + 0.2, "a", 1)),
                new LinkedHashMap<>(Map.of("a", 1, "z", 0.3))));

        assertEquals("{\"outer\":[{\"a\":1,\"z\":0.3},{\"a\":1,\"z\":0.3}]}",
                CanonicalJson.write(nested));
    }

    @Test
    @DisplayName("字符串键之外的键类型被拒绝（避免 String.valueOf 造成的键碰撞）")
    void nonStringKeysAreRejected() {
        Map<Object, Object> bad = new LinkedHashMap<>();
        bad.put(1, "a");
        bad.put("1", "b");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> CanonicalJson.write(bad));
        assertTrue(error.getMessage().contains("键必须是字符串"));
    }

    @Test
    @DisplayName("不支持的类型被拒绝，而不是退化成 toString")
    void unsupportedTypesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(new Object()));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(new int[]{1, 2}));
    }

    @Test
    @DisplayName("对象数组按数组处理")
    void objectArraysAreSupported() {
        assertEquals("[\"a\",\"b\"]", CanonicalJson.write(new Object[]{"a", "b"}));
    }

    @Test
    @DisplayName("null 与布尔值输出正确")
    void nullAndBooleans() {
        assertEquals("null", CanonicalJson.write(null));
        assertEquals("true", CanonicalJson.write(true));
        assertEquals("false", CanonicalJson.write(false));
    }

    @Test
    @DisplayName("枚举按名称输出，保证跨版本稳定")
    void enumsUseName() {
        assertEquals("\"AUTO\"", CanonicalJson.write(com.semif.gate.contract.Band.AUTO));
    }

    @Test
    @DisplayName("嵌套过深被拒绝，避免栈溢出")
    void deepNestingIsRejected() {
        Object deep = "leaf";
        for (int i = 0; i < 100; i++) {
            deep = new ArrayList<>(List.of(deep));
        }
        Object finalDeep = deep;
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(finalDeep));
    }

    @Test
    @DisplayName("writeBytes 与 write 的 UTF-8 编码一致")
    void writeBytesMatchesWrite() {
        Map<String, Object> value = Map.of("中文", "值");
        assertEquals(CanonicalJson.write(value),
                new String(CanonicalJson.writeBytes(value), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("相同内容的多次序列化结果逐字节相同")
    void serializationIsStable() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("b", 0.1 + 0.2);
        value.put("a", List.of(1, 2, Map.of("x", 1e-3)));

        String first = CanonicalJson.write(value);
        for (int i = 0; i < 50; i++) {
            assertEquals(first, CanonicalJson.write(value));
        }
        assertNotEquals("", first);
    }
}
