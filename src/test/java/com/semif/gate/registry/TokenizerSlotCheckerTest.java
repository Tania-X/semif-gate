package com.semif.gate.registry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TokenizerSlotChecker} 的契约测试。
 *
 * <p>它守护的是 SemIf 读出口的硬前提：每个选项的答案必须恰好是一个 token。
 * 前提不成立时，读到的 logits 就不是那个选项的概率，而程序不会报错——
 * 所以这个检查必须在启动阶段做，而不是等线上跑出错误结果。
 */
class TokenizerSlotCheckerTest {

    private static final Map<String, Integer> VOCABULARY = Map.ofEntries(
            Map.entry("A", 32), Map.entry("B", 33), Map.entry("C", 34), Map.entry("D", 35),
            Map.entry("yes", 100), Map.entry("no", 101));

    /** 测试用的判定点视图。 */
    private static SlotCheck.DecisionPointLike point(String style, int optionCount) {
        return new SlotCheck.DecisionPointLike() {
            @Override
            public String ref() {
                return "test.point@1";
            }

            @Override
            public String answerStyle() {
                return style;
            }

            @Override
            public int optionCount() {
                return optionCount;
            }
        };
    }

    @Test
    @DisplayName("LETTER 风格：字母齐全时通过，并返回各槽位 token id")
    void letterStylePasses() {
        SlotCheck.Result result = new TokenizerSlotChecker(VOCABULARY)
                .check(point("LETTER", 4), "prompt");

        assertEquals(SlotCheck.Status.OK, result.status());
        assertEquals(java.util.List.of(32, 33, 34, 35), result.slotTokenIds());
        assertFalse(result.blocksStartup());
        assertTrue(result.failures().isEmpty());
    }

    @Test
    @DisplayName("字母不在词表中 → 失败（该槽位根本无法读取）")
    void missingLetterFails() {
        Map<String, Integer> partial = Map.of("A", 32, "B", 33);
        SlotCheck.Result result = new TokenizerSlotChecker(partial).check(point("LETTER", 4), "prompt");

        assertEquals(SlotCheck.Status.FAILED, result.status());
        assertTrue(result.blocksStartup(), "缺字母必须阻止启动");
        assertEquals(2, result.failures().size(), "C 与 D 都应报缺失");
        assertTrue(result.failures().stream().anyMatch(f -> f.contains("\"C\"")));
        assertTrue(result.failures().stream().anyMatch(f -> f.contains("\"D\"")));
    }

    @Test
    @DisplayName("两个答案映射到同一 token → 失败（概率不可区分）")
    void collidingLettersFail() {
        // 模拟 tokenizer 把 A 和 B 都切成同一个 token 的情形
        Map<String, Integer> colliding = new LinkedHashMap<>();
        colliding.put("A", 32);
        colliding.put("B", 32);

        SlotCheck.Result result = new TokenizerSlotChecker(colliding).check(point("LETTER", 2), "prompt");

        assertEquals(SlotCheck.Status.FAILED, result.status());
        assertTrue(result.failures().stream().anyMatch(f -> f.contains("共用 token id")),
                "必须报出槽位冲突: " + result.failures());
    }

    @Test
    @DisplayName("YESNO 风格：yes/no 都在词表中时通过")
    void yesNoStylePasses() {
        SlotCheck.Result result = new TokenizerSlotChecker(VOCABULARY)
                .check(point("YESNO", 2), "prompt");

        assertEquals(SlotCheck.Status.OK, result.status());
        assertEquals(java.util.List.of(100, 101), result.slotTokenIds());
    }

    @Test
    @DisplayName("YESNO 风格缺少 no → 失败")
    void yesNoStyleMissingTokenFails() {
        Map<String, Integer> onlyYes = Map.of("yes", 100);
        SlotCheck.Result result = new TokenizerSlotChecker(onlyYes).check(point("YESNO", 2), "prompt");

        assertEquals(SlotCheck.Status.FAILED, result.status());
        assertTrue(result.failures().stream().anyMatch(f -> f.contains("\"no\"")));
    }

    @Test
    @DisplayName("选项数超过风格上限 → 跳过并说明原因（不静默通过）")
    void optionCountBeyondStyleIsSkipped() {
        SlotCheck.Result result = new TokenizerSlotChecker(VOCABULARY)
                .check(point("LETTER", 20), "prompt");

        assertEquals(SlotCheck.Status.SKIPPED, result.status());
        assertFalse(result.blocksStartup(), "跳过不阻止启动");
        assertFalse(result.failures().isEmpty(), "跳过必须说明原因");
        assertTrue(result.failures().get(0).contains("未知的答案风格"));
    }

    @Test
    @DisplayName("未知答案风格 → 跳过，而不是假装通过")
    void unknownStyleIsSkipped() {
        SlotCheck.Result result = new TokenizerSlotChecker(VOCABULARY)
                .check(point("FREETEXT", 3), "prompt");

        assertEquals(SlotCheck.Status.SKIPPED, result.status());
        assertFalse(result.blocksStartup());
    }

    @Test
    @DisplayName("报告汇总：失败阻止启动，跳过不阻止但必须列出")
    void reportAggregatesResults() {
        SlotCheckReport report = new SlotCheckReport(java.util.List.of(
                new SlotCheck.Result("a@1", SlotCheck.Status.OK, java.util.List.of(), java.util.List.of(1, 2)),
                new SlotCheck.Result("b@1", SlotCheck.Status.FAILED, java.util.List.of("字母 C 缺失"), java.util.List.of()),
                new SlotCheck.Result("c@1", SlotCheck.Status.SKIPPED, java.util.List.of("约束解码不适用"), java.util.List.of())));

        assertTrue(report.blocksStartup());
        assertEquals(1, report.passedCount());
        assertEquals(1, report.failedCount());
        assertEquals(1, report.skippedCount());
        assertEquals(java.util.List.of("字母 C 缺失"), report.failuresByPoint().get("b@1"));

        String message = report.toRefusalMessage();
        assertTrue(message.contains("拒绝启动"));
        assertTrue(message.contains("b@1"));
        assertTrue(message.contains("字母 C 缺失"));
        assertTrue(message.contains("c@1"), "跳过项也要出现在拒绝信息里，便于排查");
    }

    @Test
    @DisplayName("未覆盖的检查项必须可枚举——避免读者误以为槽位检查通过就等于完整契约通过")
    void uncoveredChecksAreDeclared() {
        assertFalse(TokenizerSlotChecker.uncoveredChecks().isEmpty());
        assertTrue(TokenizerSlotChecker.uncoveredChecks().stream()
                        .anyMatch(item -> item.contains("token 序列")),
                "必须显式声明「prompt 末尾追加字母不改变已有 token」这一项未覆盖");
    }

    @Test
    @DisplayName("空词表 → 构造即拒绝")
    void emptyVocabularyIsRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new TokenizerSlotChecker(Map.of()));
    }
}
