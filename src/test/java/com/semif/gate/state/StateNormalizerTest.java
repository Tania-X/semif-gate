package com.semif.gate.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验收测试 1–4：哈希稳定性、浮点量化、敏感性、截断顺序。
 *
 * <p>这四个测试守护的是缓存正确性的根基。它们失败意味着缓存会
 * 静默返回错误结果，而不是报错——所以每个断言都写明了它在防什么。
 */
class StateNormalizerTest {

    private static StateNormalizer normalizer() {
        return new StateNormalizer(java.util.Set.of("alertname", "service", "duration_s", "note"));
    }

    // ---------------------------------------------------------------- 验收 1：哈希稳定性

    @Test
    @DisplayName("验收1：同一 state 不同插入顺序 → 哈希相同")
    void insertionOrderDoesNotChangeHash() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("alertname", "HighLatency");
        first.put("service", "checkout");
        first.put("duration_s", 480);

        Map<String, Object> second = new LinkedHashMap<>();
        second.put("duration_s", 480);
        second.put("alertname", "HighLatency");
        second.put("service", "checkout");

        Map<String, Object> third = new LinkedHashMap<>();
        third.put("service", "checkout");
        third.put("duration_s", 480);
        third.put("alertname", "HighLatency");

        DecisionState a = normalizer().normalize(first);
        DecisionState b = normalizer().normalize(second);
        DecisionState c = normalizer().normalize(third);

        assertEquals(a.json(), b.json(), "插入顺序不应影响规范化 JSON");
        assertEquals(a.json(), c.json(), "插入顺序不应影响规范化 JSON");
        assertEquals(a.hash(), b.hash(), "插入顺序不应影响 state 哈希——否则缓存永不命中");
        assertEquals(a.hash(), c.hash());
    }

    @Test
    @DisplayName("验收1补充：嵌套对象与数组内部的顺序同样无关")
    void nestedOrderDoesNotChangeHash() {
        Map<String, Object> nestedFirst = new LinkedHashMap<>();
        nestedFirst.put("changes", List.of(
                new LinkedHashMap<>(Map.of("type", "deploy", "at", "14:02")),
                new LinkedHashMap<>(Map.of("type", "config", "at", "13:55"))));
        nestedFirst.put("service", "checkout");

        Map<String, Object> nestedSecond = new LinkedHashMap<>();
        nestedSecond.put("service", "checkout");
        nestedSecond.put("changes", List.of(
                Map.of("at", "14:02", "type", "deploy"),
                Map.of("at", "13:55", "type", "config")));

        // 数组顺序是语义（事件先后），因此这里只比较对象内部键序无关
        assertEquals(
                new StateNormalizer(java.util.Set.of("service", "changes")).normalize(nestedFirst).hash(),
                new StateNormalizer(java.util.Set.of("service", "changes")).normalize(nestedSecond).hash());
    }

    // ---------------------------------------------------------------- 验收 2：浮点量化

    @Test
    @DisplayName("验收2：0.1+0.2 与 0.3 量化后哈希相同")
    void floatPerturbationIsAbsorbed() {
        Map<String, Object> computed = new LinkedHashMap<>();
        computed.put("service", "checkout");
        computed.put("duration_s", 0.1 + 0.2);

        Map<String, Object> literal = new LinkedHashMap<>();
        literal.put("service", "checkout");
        literal.put("duration_s", 0.3);

        DecisionState a = normalizer().normalize(computed);
        DecisionState b = normalizer().normalize(literal);

        assertEquals("0.3", extractValue(a.json(), "duration_s"),
                "0.1+0.2 应被量化为 0.3");
        assertEquals(a.hash(), b.hash(),
                "浮点扰动必须被吸收——否则同一逻辑状态会产生两个缓存键");
    }

    @Test
    @DisplayName("验收2补充：Integer 与 Long 表示同一整数时哈希相同")
    void integerBoxingDoesNotChangeHash() {
        Map<String, Object> asInteger = Map.of("service", "checkout", "duration_s", 480);
        Map<String, Object> asLong = Map.of("service", "checkout", "duration_s", 480L);

        assertEquals(normalizer().normalize(asInteger).hash(), normalizer().normalize(asLong).hash(),
                "同值的 Integer 与 Long 不应产生不同哈希");
    }

    @Test
    @DisplayName("验收2补充：超过 12 位有效数字的差异会被吸收，但显著差异不会")
    void quantizationPrecisionBoundary() {
        Map<String, Object> a = Map.of("service", "s", "duration_s", 1.0000000000001);
        Map<String, Object> b = Map.of("service", "s", "duration_s", 1.0000000000002);
        Map<String, Object> c = Map.of("service", "s", "duration_s", 1.01);

        assertEquals(normalizer().normalize(a).hash(), normalizer().normalize(b).hash(),
                "12 位有效数字之内的扰动应被吸收");
        assertNotEquals(normalizer().normalize(a).hash(), normalizer().normalize(c).hash(),
                "显著不同的数值必须产生不同哈希");
    }

    @Test
    @DisplayName("验收2补充：非有限浮点被拒绝，而不是静默产出垃圾")
    void nonFiniteFloatsAreRejected() {
        Map<String, Object> withNaN = new LinkedHashMap<>();
        withNaN.put("service", "checkout");
        withNaN.put("duration_s", Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> normalizer().normalize(withNaN));

        Map<String, Object> withInfinity = new LinkedHashMap<>();
        withInfinity.put("service", "checkout");
        withInfinity.put("duration_s", Double.POSITIVE_INFINITY);
        assertThrows(IllegalArgumentException.class, () -> normalizer().normalize(withInfinity));
    }

    // ---------------------------------------------------------------- 验收 3：敏感性

    @Test
    @DisplayName("验收3：任一字段变化 → 哈希变化")
    void everyFieldChangeChangesHash() {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("alertname", "HighLatency");
        base.put("service", "checkout");
        base.put("duration_s", 480);

        DecisionState baseline = normalizer().normalize(base);

        Map<String, Object> changedAlertname = new LinkedHashMap<>(base);
        changedAlertname.put("alertname", "HighErrorRate");
        Map<String, Object> changedService = new LinkedHashMap<>(base);
        changedService.put("service", "payments");
        Map<String, Object> changedDuration = new LinkedHashMap<>(base);
        changedDuration.put("duration_s", 481);

        assertNotEquals(baseline.hash(), normalizer().normalize(changedAlertname).hash(), "alertname 变化必须改变哈希");
        assertNotEquals(baseline.hash(), normalizer().normalize(changedService).hash(), "service 变化必须改变哈希");
        assertNotEquals(baseline.hash(), normalizer().normalize(changedDuration).hash(), "duration_s 变化必须改变哈希");
    }

    @Test
    @DisplayName("验收3补充：白名单外的字段被丢弃，因此不影响哈希")
    void fieldsOutsideWhitelistAreDroppedAndDoNotAffectHash() {
        Map<String, Object> withExtra = new LinkedHashMap<>();
        withExtra.put("service", "checkout");
        withExtra.put("duration_s", 480);
        withExtra.put("ingested_at", "2026-09-25T11:00:00Z");
        withExtra.put("operator_email", "someone@example.com");

        Map<String, Object> withoutExtra = new LinkedHashMap<>();
        withoutExtra.put("service", "checkout");
        withoutExtra.put("duration_s", 480);

        DecisionState a = normalizer().normalize(withExtra);
        DecisionState b = normalizer().normalize(withoutExtra);

        assertEquals(a.hash(), b.hash(),
                "未声明字段必须被丢弃——时间戳类字段会让缓存永不命中，PII 字段则会泄漏");
        assertFalse(a.json().contains("someone@example.com"), "PII 不应出现在规范化 state 中");
        assertFalse(a.json().contains("ingested_at"), "白名单外字段不应出现在规范化 state 中");
    }

    @Test
    @DisplayName("验收3补充：白名单过滤后为空 → 直接拒绝")
    void emptyAfterWhitelistIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> normalizer().normalize(Map.of("something_else", "x")));
    }

    // ---------------------------------------------------------------- 验收 4：截断顺序

    @Test
    @DisplayName("验收4：哈希在截断之后计算——哈希必须等于实际内容（截断后）的哈希")
    void hashIsComputedAfterTruncation() {
        StateNormalizer tight = new StateNormalizer(
                java.util.Set.of("note", "service", "duration_s"), 60);
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("note", "x".repeat(200));
        raw.put("service", "checkout");
        raw.put("duration_s", 480);

        DecisionState state = tight.normalize(raw);

        assertTrue(state.truncated(), "超过上限时必须标记为已截断");
        assertTrue(state.codePointLength() <= 60,
                "截断后长度必须在上限内，实际 " + state.codePointLength());

        // 核心断言：哈希描述的是实际内容，而不是被截断掉的那部分
        assertEquals(StateHasher.sha256Hex(state.json()), state.hash(),
                "哈希必须等于实际内容（截断后）的哈希");
        assertNotEquals(StateHasher.sha256Hex(state.rawJson()), state.hash(),
                "哈希绝不能等于原始（截断前）内容的哈希——那会让缓存键描述一个从未发送过的状态");
    }

    @Test
    @DisplayName("验收4：截断后内容相同的两个 state → 哈希相同（即使原始内容不同）")
    void statesWithDifferentRawContentButSameTruncatedContentShareHash() {
        // 预算只够放下截断标记，两个不同的超长字段都会被丢弃
        StateNormalizer tight = new StateNormalizer(java.util.Set.of("common", "diff"), 60);

        String sharedPrefix = "s".repeat(300);
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("common", sharedPrefix);
        first.put("diff", "AAAA".repeat(50));

        Map<String, Object> second = new LinkedHashMap<>();
        second.put("common", sharedPrefix);
        second.put("diff", "BBBB".repeat(50));

        DecisionState a = tight.normalize(first);
        DecisionState b = tight.normalize(second);

        assertNotEquals(a.rawJson(), b.rawJson(), "原始内容确实不同");
        assertTrue(a.truncated() && b.truncated(), "两者都应被截断");
        assertEquals(a.json(), b.json(), "截断后内容应完全相同");
        assertEquals(a.hash(), b.hash(),
                "哈希必须基于截断后的内容——先算哈希再由截断产生相同内容，是缓存串味的根源");
    }

    @Test
    @DisplayName("验收4：截断结果仍是合法 JSON，且带显式截断标记")
    void truncationProducesValidJsonWithMarker() {
        StateNormalizer tight = new StateNormalizer(java.util.Set.of("note", "service"), 40);
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("note", "y".repeat(500));
        raw.put("service", "checkout");

        DecisionState state = tight.normalize(raw);

        assertJsonParses(state.json());
        assertTrue(state.json().contains("__semif_truncated__"),
                "截断必须留下显式标记，让下游知道这份 state 不完整");
    }

    @Test
    @DisplayName("验收4：未超限时不截断，哈希等于原始内容哈希")
    void noTruncationWhenWithinLimit() {
        Map<String, Object> raw = Map.of("service", "checkout", "duration_s", 480);
        DecisionState state = normalizer().normalize(raw);

        assertFalse(state.truncated());
        assertEquals(state.rawJson(), state.json());
        assertEquals(StateHasher.sha256Hex(state.rawJson()), state.hash());
    }

    @Test
    @DisplayName("截断按码点进行，绝不切断代理对（emoji）")
    void truncationNeverSplitsSurrogatePairs() {
        StateNormalizer tight = new StateNormalizer(java.util.Set.of("note"), 30);
        Map<String, Object> raw = Map.of("note", "🚨".repeat(50));

        DecisionState state = tight.normalize(raw);

        String json = state.json();
        // 若切断了代理对，会留下无法配对的孤立代理项
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertTrue(i + 1 < json.length() && Character.isLowSurrogate(json.charAt(i + 1)),
                        "高代理项后面必须是低代理项，位置 " + i);
                i++;
            } else {
                assertFalse(Character.isLowSurrogate(c), "出现孤立低代理项，位置 " + i);
            }
        }
    }

    // ---------------------------------------------------------------- 辅助

    private static void assertJsonParses(String json) {
        try {
            JsonTree.parse(json);
        } catch (IllegalArgumentException e) {
            throw new AssertionError("截断结果不是合法 JSON: " + json, e);
        }
    }

    /** 从规范化 JSON 中取出某个顶层字段的原始字面量，用于断言量化结果。 */
    private static String extractValue(String json, String field) {
        String needle = "\"" + field + "\":";
        int start = json.indexOf(needle);
        if (start < 0) {
            throw new AssertionError("字段不存在: " + field + " in " + json);
        }
        int valueStart = start + needle.length();
        int end = valueStart;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') {
            end++;
        }
        return json.substring(valueStart, end);
    }

    @Test
    @DisplayName("规范化输出是紧凑格式（无多余空白），保证跨环境逐字节一致")
    void canonicalOutputHasNoInsignificantWhitespace() {
        Map<String, Object> raw = Map.of("service", "checkout", "duration_s", 480);
        String json = normalizer().normalize(raw).json();

        assertFalse(json.contains(" "), "不应包含空格: " + json);
        assertFalse(json.contains("\n"), "不应包含换行: " + json);
        assertTrue(json.startsWith("{") && json.endsWith("}"));
    }

    @Test
    @DisplayName("键排序按 Unicode 码点升序，与插入顺序及 Map 实现无关")
    void keysAreSortedByCodePoint() {
        Map<String, Object> hashMapBacked = new java.util.HashMap<>();
        hashMapBacked.put("zulu", 1);
        hashMapBacked.put("alpha", 2);
        hashMapBacked.put("mike", 3);

        List<String> keys = new ArrayList<>(new StateNormalizer(
                java.util.Set.of("zulu", "alpha", "mike")).normalize(hashMapBacked).topLevelFields());

        assertEquals(List.of("alpha", "mike", "zulu"), keys, "键必须按码点升序输出");
        assertEquals(Collections.unmodifiableList(List.of("alpha", "mike", "zulu")), keys);
    }
}
