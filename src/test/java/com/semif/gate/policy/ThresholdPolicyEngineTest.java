package com.semif.gate.policy;

import com.semif.gate.contract.Action;
import com.semif.gate.contract.Band;
import com.semif.gate.contract.Decision;
import com.semif.gate.contract.OptionScore;
import com.semif.gate.testkit.TestDecisions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 策略分档测试——<b>纯函数，不碰网络也不碰模型</b>。
 *
 * <p>最关键的一条：<b>降级永远落最低档</b>。
 * 降级分布的保守选项可能占 0.5，按阈值它会落进「需要复核」，
 * 看起来无害；但如果某个判定点的保守选项占到 0.95，它就会落进「自动执行」——
 * 那就等于把「没判定出来」当成了「判定为某个答案」。
 * 所以降级必须<b>先判</b>，与阈值无关。
 */
class ThresholdPolicyEngineTest {

    private static final ThresholdPolicyEngine POLICY = ThresholdPolicyEngine.defaults();

    private static Decision ok(double... probabilities) {
        List<String> ids = List.of("a", "b", "c");
        List<OptionScore> distribution = new java.util.ArrayList<>();
        for (int i = 0; i < probabilities.length; i++) {
            distribution.add(new OptionScore(ids.get(i), probabilities[i]));
        }
        return TestDecisions.decision("test.point", 1, distribution);
    }

    @Test
    @DisplayName("高置信 → AUTO")
    void highConfidenceIsAuto() {
        assertEquals(Band.AUTO, POLICY.band(ok(0.95, 0.04, 0.01)));
        assertEquals(Band.AUTO, POLICY.band(ok(0.90, 0.06, 0.04)), "边界值 0.90 应算 AUTO");
    }

    @Test
    @DisplayName("中间带 → REVIEW")
    void midBandIsReview() {
        assertEquals(Band.REVIEW, POLICY.band(ok(0.80, 0.15, 0.05)));
        assertEquals(Band.REVIEW, POLICY.band(ok(0.60, 0.30, 0.10)), "边界值 0.60 应算 REVIEW");
    }

    @Test
    @DisplayName("低置信 → REFUSE")
    void lowConfidenceIsRefuse() {
        assertEquals(Band.REFUSE, POLICY.band(ok(0.50, 0.30, 0.20)));
        assertEquals(Band.REFUSE, POLICY.band(ok(0.34, 0.33, 0.33)));
    }

    @Test
    @DisplayName("平局 → REFUSE（0.50 低于复核线）")
    void tieIsRefuse() {
        assertEquals(Band.REFUSE, POLICY.band(ok(0.4995, 0.4995, 0.001)));
    }

    @Test
    @DisplayName("降级永远落最低档，即使保守选项概率很高")
    void degradedAlwaysRefuses() {
        // 刻意构造一个「降级但最高概率 0.99」的分布——按阈值它会落 AUTO
        Decision degraded = new Decision(
                "key", "test.point", 1, Decision.Outcome.DEGRADED,
                List.of(new OptionScore("a", 0.99), new OptionScore("b", 0.01)),
                TestDecisions.dummyProvenance(), "provider 超时");

        assertEquals(Band.REFUSE, POLICY.band(degraded),
                "降级必须先判，绝不能因为概率高就被判成自动执行");
    }

    @Test
    @DisplayName("降级动作可配置")
    void degradedActionIsConfigurable() {
        assertEquals(Action.SAFE_DEFAULT, POLICY.onDegraded("any.point"));

        ThresholdPolicyEngine custom = new ThresholdPolicyEngine(
                0.9, 0.6, "custom-v1", Action.HUMAN_REVIEW);
        assertEquals(Action.HUMAN_REVIEW, custom.onDegraded("any.point"));
    }

    @Test
    @DisplayName("阈值与版本可读，用于审计")
    void configurationIsReadable() {
        assertEquals("threshold-v1", POLICY.version());
        assertEquals(0.90, POLICY.autoMin(), 1e-12);
        assertEquals(0.60, POLICY.reviewMin(), 1e-12);
    }

    @Test
    @DisplayName("非法配置 → 构造即报错")
    void invalidConfigurationIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ThresholdPolicyEngine(0.5, 0.9, "bad", Action.SAFE_DEFAULT),
                "autoMin 必须大于 reviewMin");
        assertThrows(IllegalArgumentException.class,
                () -> new ThresholdPolicyEngine(1.5, 0.6, "bad", Action.SAFE_DEFAULT),
                "阈值必须落在 [0,1]");
        assertThrows(IllegalArgumentException.class,
                () -> new ThresholdPolicyEngine(0.9, -0.1, "bad", Action.SAFE_DEFAULT));
        assertThrows(IllegalArgumentException.class,
                () -> new ThresholdPolicyEngine(0.9, 0.6, "", Action.SAFE_DEFAULT),
                "策略版本不能为空——审计需要它");
        assertThrows(IllegalArgumentException.class,
                () -> new ThresholdPolicyEngine(0.9, 0.6, "v1", null));
    }

    @Test
    @DisplayName("策略是纯函数：同输入多次调用结果一致")
    void bandIsPure() {
        Decision decision = ok(0.80, 0.15, 0.05);

        assertEquals(POLICY.band(decision), POLICY.band(decision));
        assertEquals(Band.REVIEW, POLICY.band(decision));
    }

    @Test
    @DisplayName("策略不修改传入的判定")
    void bandDoesNotMutateInput() {
        Decision decision = ok(0.80, 0.15, 0.05);
        List<OptionScore> before = List.copyOf(decision.distribution());

        POLICY.band(decision);

        assertEquals(before, decision.distribution());
    }
}
