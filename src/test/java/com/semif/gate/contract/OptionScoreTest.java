package com.semif.gate.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验收测试 7：概率校验 —— {@link OptionScore} 拒绝 NaN / Infinity / 越界值。
 *
 * <p>为什么这件事值得单独一组测试：一个 NaN 概率会让策略层的阈值比较
 * 永远返回 false（NaN 与任何数比较都是 false），于是「高置信度自动执行」
 * 静默失效、全部落进复核队列；而 Infinity 会污染归一化。
 * 这些故障都不会抛异常，只会让系统行为变得难以解释。
 */
class OptionScoreTest {

    @Test
    @DisplayName("验收7a：拒绝 NaN")
    void rejectsNaN() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new OptionScore("yes", Double.NaN));
        assertNotNull(error.getMessage());
        assertTrue(error.getMessage().contains("NaN"));
    }

    @Test
    @DisplayName("验收7b：拒绝正负无穷")
    void rejectsInfinity() {
        IllegalArgumentException positive = assertThrows(IllegalArgumentException.class,
                () -> new OptionScore("yes", Double.POSITIVE_INFINITY));
        assertTrue(positive.getMessage().contains("无穷"));
        assertThrows(IllegalArgumentException.class,
                () -> new OptionScore("yes", Double.NEGATIVE_INFINITY));
    }

    @Test
    @DisplayName("验收7c：拒绝越界值（负数与大于 1）")
    void rejectsOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> new OptionScore("yes", -0.0001));
        assertThrows(IllegalArgumentException.class, () -> new OptionScore("no", 1.0001));
        assertThrows(IllegalArgumentException.class, () -> new OptionScore("no", -1.0));
        assertThrows(IllegalArgumentException.class, () -> new OptionScore("no", 42.0));
    }

    @Test
    @DisplayName("验收7d：接受边界值 0 与 1")
    void acceptsBoundaries() {
        assertEquals(0.0, new OptionScore("yes", 0.0).probability());
        assertEquals(1.0, new OptionScore("yes", 1.0).probability());
    }

    @Test
    @DisplayName("验收7e：拒绝空 optionId")
    void rejectsBlankOptionId() {
        assertThrows(IllegalArgumentException.class, () -> new OptionScore("", 0.5));
        assertThrows(IllegalArgumentException.class, () -> new OptionScore("  ", 0.5));
        assertThrows(IllegalArgumentException.class, () -> new OptionScore(null, 0.5));
    }
}

/**
 * {@link Decision} 的行为契约：分布始终有序、降级必须带原因、平局行为确定性。
 */
class DecisionTest {

    private static Provenance provenance() {
        return new Provenance("test", "rev", "none", "a".repeat(64), "b".repeat(64), "c".repeat(64), 1, 1L);
    }

    private static Decision decision(Decision.Outcome outcome, String reason, OptionScore... scores) {
        return new Decision("key", "point", 1, outcome, List.of(scores), provenance(), reason);
    }

    @Test
    @DisplayName("分布按 optionId 字典序排列，与传入顺序无关")
    void distributionIsSortedByOptionId() {
        Decision decision = decision(Decision.Outcome.OK, null,
                new OptionScore("zulu", 0.1),
                new OptionScore("alpha", 0.3),
                new OptionScore("mike", 0.6));

        assertEquals(List.of("alpha", "mike", "zulu"),
                decision.distribution().stream().map(OptionScore::optionId).toList());
    }

    @Test
    @DisplayName("分布中存在重复选项 → 拒绝")
    void duplicateOptionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> decision(Decision.Outcome.OK, null,
                new OptionScore("yes", 0.5),
                new OptionScore("yes", 0.5)));
    }

    @Test
    @DisplayName("降级结果必须给出原因——否则「没判定出来」会被当成「判定为不可能」")
    void degradedRequiresReason() {
        assertThrows(IllegalArgumentException.class, () -> decision(Decision.Outcome.DEGRADED, null,
                new OptionScore("yes", 0.5)));
        assertThrows(IllegalArgumentException.class, () -> decision(Decision.Outcome.DEGRADED, "  ",
                new OptionScore("yes", 0.5)));
    }

    @Test
    @DisplayName("argmax 与 margin 在平局时行为确定——平局必须可被识别")
    void tieIsDetectableAndDeterministic() {
        // 复刻 SemIf 实测中真实出现过的一次精确平局
        Decision tie = decision(Decision.Outcome.OK, null,
                new OptionScore("B", 0.4995),
                new OptionScore("A", 0.4995),
                new OptionScore("insufficient", 0.001));

        assertEquals(0.0, tie.margin(), 1e-12, "平局的 margin 必须是 0");
        assertEquals("A", tie.argmaxOption(), "平局时取字典序最小者，保证确定性");
        assertEquals(tie.argmaxOption(), tie.argmaxOption(), "重复调用结果一致");

        Decision notTie = decision(Decision.Outcome.OK, null,
                new OptionScore("A", 0.55),
                new OptionScore("B", 0.45));
        assertEquals(0.1, notTie.margin(), 1e-12);
        assertEquals("A", notTie.argmaxOption());
    }

    @Test
    @DisplayName("ok() 正确反映结果状态")
    void okReflectsOutcome() {
        assertTrue(decision(Decision.Outcome.OK, null, new OptionScore("yes", 1.0)).ok());
        assertFalse(decision(Decision.Outcome.DEGRADED, "provider 超时",
                new OptionScore("yes", 0.5)).ok());
    }

    @Test
    @DisplayName("probabilityOf 对不存在的选项返回空，而不是 0 或抛异常")
    void probabilityOfUnknownOptionIsEmpty() {
        Decision decision = decision(Decision.Outcome.OK, null, new OptionScore("yes", 1.0));
        assertTrue(decision.probabilityOf("yes").isPresent());
        assertTrue(decision.probabilityOf("maybe").isEmpty());
    }

    @Test
    @DisplayName("asMap 提供按 optionId 对齐的视图，便于跨结果比较")
    void asMapIsKeyedByOptionId() {
        Decision decision = decision(Decision.Outcome.OK, null,
                new OptionScore("b", 0.4),
                new OptionScore("a", 0.6));

        assertEquals(0.6, decision.asMap().get("a"), 1e-12);
        assertEquals(0.4, decision.asMap().get("b"), 1e-12);
    }
}
