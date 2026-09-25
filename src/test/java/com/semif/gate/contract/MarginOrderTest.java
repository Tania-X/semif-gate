package com.semif.gate.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * margin() 必须与分布顺序无关。
 *
 * <p>这是「区分平局舍入与真实语义漂移」的前提：4090 复现中那次 1/144 的翻转
 * （A = B = 0.4995）必须能被识别成 margin = 0，而真实的判定变化必须给出非零 margin。
 */
class MarginOrderTest {

    private static Provenance provenance() {
        return new Provenance("test", "rev", "none",
                "a".repeat(64), "b".repeat(64), "c".repeat(64), 1, 1L);
    }

    private static Decision decision(OptionScore... scores) {
        return new Decision("key", "point", 1, Decision.Outcome.OK,
                List.of(scores), provenance(), null);
    }

    @Test
    @DisplayName("真实间隔 0.10：两个选项、字典序与概率降序同向")
    void marginWithTwoOptions() {
        Decision aligned = decision(new OptionScore("A", 0.55), new OptionScore("B", 0.45));
        assertEquals(0.10, aligned.margin(), 1e-12);

        Decision opposed = decision(new OptionScore("zulu", 0.55), new OptionScore("alpha", 0.45));
        assertEquals(0.10, opposed.margin(), 1e-12, "换 id 不影响 margin");
    }

    @Test
    @DisplayName("平局必须可识别：margin = 0（4090 复现中的真实用例）")
    void tieIsDetectableRegardlessOfOrder() {
        Decision tie = decision(
                new OptionScore("B", 0.4995),
                new OptionScore("A", 0.4995),
                new OptionScore("insufficient", 0.001));
        assertEquals(0.0, tie.margin(), 1e-12, "平局 margin 必须是 0");

        Decision tieRev = decision(
                new OptionScore("zzz", 0.4995),
                new OptionScore("aaa", 0.4995));
        assertEquals(0.0, tieRev.margin(), 1e-12, "换个 id 的平局也必须是 0");
    }

    @Test
    @DisplayName("三选项、字典序与概率序不同向时 margin 仍取真正的 top-2 之差")
    void marginWithThreeOptions() {
        // 真实形状：SemIf 的 contradicted / insufficient / supported 三选项判定。
        // 构造器按 optionId 字典序排列 ⇒ contradicted(.15), insufficient(.05), supported(.80)。
        // "概率最高的两个" 是 supported(.80) 与 contradicted(.15)，间隔 0.65 ——
        // 注意它【不是】字典序相邻的两个（insufficient 与 supported，会得到 0.75）。
        Decision d = decision(
                new OptionScore("insufficient", 0.05),
                new OptionScore("supported", 0.80),
                new OptionScore("contradicted", 0.15));
        assertEquals(0.80 - 0.15, d.margin(), 1e-12, "top-2 间隔必须是 0.65");
        assertEquals("supported", d.argmaxOption());
    }
}
