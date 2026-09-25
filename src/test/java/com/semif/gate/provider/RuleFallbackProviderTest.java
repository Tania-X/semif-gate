package com.semif.gate.provider;

import com.semif.gate.contract.Decision;
import com.semif.gate.contract.DecisionPoint;
import com.semif.gate.contract.Option;
import com.semif.gate.contract.OptionScore;
import com.semif.gate.contract.ScoredPoint;
import com.semif.gate.registry.DecisionPointRegistry;
import com.semif.gate.registry.SlotCheck;
import com.semif.gate.state.DecisionState;
import com.semif.gate.state.StateNormalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RuleFallbackProvider} 的契约测试。
 *
 * <p>降级路径的正确性同样重要：它是模型不可用时的唯一依赖。
 * 两条纪律必须被测试固定下来——<b>永不伪装成正常判定</b>，以及<b>永不失败</b>。
 *
 * <p>注意 provider 返回的是 {@link ScoredPoint}（只有分数），
 * {@code decisionId} 由网关独占构造，因此本测试不再断言 decisionId。
 */
class RuleFallbackProviderTest {

    private static final String REVISION = "851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a";

    private static final DecisionState STATE = new StateNormalizer(
            java.util.Set.of("service", "duration_s"))
            .normalize(Map.of("service", "checkout", "duration_s", 480));

    private static DecisionPointRegistry registry() {
        return DecisionPointRegistry.of(List.of(new DecisionPoint(
                "test.binary", 1, "test-team", "2026-09-25", "证据是否支持该主张？",
                List.of(new Option("yes", "支持。"), new Option("no", "不支持。")),
                "{\"evidence\": {{state}}, \"criterion\": {{question}}, \"options\": {{options}}}",
                com.semif.gate.contract.AnswerStyle.LETTER, REVISION, "<待计算>")));
    }

    private static ScoredPoint single(DecisionProvider provider, DecisionPoint point) {
        return provider.decide(STATE, List.of(point)).get(point.id());
    }

    private static double probabilityOf(ScoredPoint scored, String optionId) {
        return scored.distribution().stream()
                .filter(score -> score.optionId().equals(optionId))
                .mapToDouble(OptionScore::probability)
                .findFirst()
                .orElseThrow();
    }

    private static String argmax(ScoredPoint scored) {
        return scored.distribution().stream()
                .max((a, b) -> Double.compare(a.probability(), b.probability()))
                .orElseThrow()
                .optionId();
    }

    @Test
    @DisplayName("结果标记为 DEGRADED 并带原因——绝不伪装成正常判定")
    void resultIsMarkedDegradedWithReason() {
        DecisionPoint point = registry().require("test.binary");

        ScoredPoint scored = single(RuleFallbackProvider.defaultInstance(), point);

        assertEquals(Decision.Outcome.DEGRADED, scored.outcome());
        assertFalse(scored.ok(), "降级结果不能被当成正常判定");
        assertTrue(scored.degradedReason().contains("规则兜底"), scored.degradedReason());
        assertTrue(scored.degradedReason().contains("test.binary@1"),
                "原因里应带上判定点，便于定位");
    }

    @Test
    @DisplayName("降级分布：保守选项 0.5，其余均分；且概率和为 1")
    void conservativeDistributionShape() {
        DecisionPoint point = registry().require("test.binary");

        ScoredPoint scored = single(RuleFallbackProvider.defaultInstance(), point);

        assertEquals(0.5, probabilityOf(scored, "yes"), 1e-12,
                "保守选项固定 0.5——刻意不取 1.0，避免被策略层误判为高置信");
        assertEquals(0.5, probabilityOf(scored, "no"), 1e-12);

        double sum = scored.distribution().stream()
                .mapToDouble(OptionScore::probability).sum();
        assertEquals(1.0, sum, 1e-9, "分布必须归一化");
    }

    @Test
    @DisplayName("降级分布的最高概率低于典型自动执行阈值（0.5 < 0.6）")
    void degradedNeverLooksHighConfidence() {
        DecisionPoint point = registry().require("test.binary");

        ScoredPoint scored = single(RuleFallbackProvider.defaultInstance(), point);

        double max = scored.distribution().stream()
                .mapToDouble(OptionScore::probability).max().orElseThrow();
        assertTrue(max < 0.6,
                "降级结果的最高概率必须低于常见的自动执行阈值，否则会把可用性问题升级为正确性问题");
    }

    @Test
    @DisplayName("可配置保守选项：指定 no 后它的概率高于 yes")
    void configurableConservativeOption() {
        DecisionPoint point = registry().require("test.binary");
        RuleFallbackProvider provider = new RuleFallbackProvider(
                Map.of("test.binary", "no"), "模型超时");

        ScoredPoint scored = single(provider, point);

        assertEquals(0.5, probabilityOf(scored, "no"), 1e-12);
        assertEquals("no", argmax(scored));
        assertTrue(scored.degradedReason().contains("模型超时"));
    }

    @Test
    @DisplayName("同一输入重复调用 → 结果完全相同（确定性）")
    void decideIsDeterministic() {
        DecisionPoint point = registry().require("test.binary");
        RuleFallbackProvider provider = RuleFallbackProvider.defaultInstance();

        ScoredPoint first = single(provider, point);
        ScoredPoint second = single(provider, point);

        assertEquals(first, second, "降级结果必须逐字段稳定，否则会污染缓存");
        assertEquals(first.promptSha256(), second.promptSha256());
    }

    @Test
    @DisplayName("降级不伪装成模型产出：revision=none、backend=none")
    void degradedDoesNotClaimModelUse() {
        DecisionPoint point = registry().require("test.binary");

        ScoredPoint scored = single(RuleFallbackProvider.defaultInstance(), point);

        assertEquals(RuleFallbackProvider.NO_MODEL_REVISION, scored.modelRevision());
        assertEquals(RuleFallbackProvider.BACKEND, scored.backend());
    }

    @Test
    @DisplayName("溯源信息完整：prompt 哈希为 64 位十六进制、inputTokens 非负")
    void provenanceFieldsAreComplete() {
        DecisionPoint point = registry().require("test.binary");

        ScoredPoint scored = single(RuleFallbackProvider.defaultInstance(), point);

        assertEquals(64, scored.promptSha256().length());
        assertTrue(scored.inputTokens() > 0, "inputTokens 应反映渲染后的 prompt 规模");
        assertTrue(scored.latencyMs() >= 0);
    }

    @Test
    @DisplayName("槽位检查返回 SKIPPED 并说明原因——不是静默通过")
    void assertSlotsIsSkippedWithReason() {
        SlotCheck.Result result = RuleFallbackProvider.defaultInstance().assertSlots(
                new SlotCheck.DecisionPointLike() {
                    @Override
                    public String ref() {
                        return "test.binary@1";
                    }

                    @Override
                    public String answerStyle() {
                        return "LETTER";
                    }

                    @Override
                    public int optionCount() {
                        return 2;
                    }
                }, "prompt");

        assertEquals(SlotCheck.Status.SKIPPED, result.status());
        assertFalse(result.blocksStartup());
        assertTrue(result.failures().get(0).contains("不存在答案槽位契约"));
    }

    @Test
    @DisplayName("不支持前缀复用——容量评估依赖这个标志")
    void doesNotSupportPrefixReuse() {
        assertFalse(RuleFallbackProvider.defaultInstance().supportsPrefixReuse());
    }

    @Test
    @DisplayName("配置的保守选项不在选项集中 → 报错")
    void unknownConservativeOptionIsRejected() {
        DecisionPoint point = registry().require("test.binary");
        RuleFallbackProvider provider = new RuleFallbackProvider(
                Map.of("test.binary", "maybe"), "模型超时");

        assertThrows(IllegalArgumentException.class, () -> provider.decide(STATE, List.of(point)));
    }

    @Test
    @DisplayName("降级原因不能为空——降级结果必须可解释")
    void blankReasonIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RuleFallbackProvider(Map.of(), ""));
        assertThrows(IllegalArgumentException.class, () -> new RuleFallbackProvider(Map.of(), "   "));
    }

    @Test
    @DisplayName("多个判定点一次调用全部返回")
    void multiplePointsAreAllReturned() {
        DecisionPointRegistry registry = DecisionPointRegistry.loadFromClasspath(
                "decision-points/ticket.route.json");
        Map<String, ScoredPoint> scored = RuleFallbackProvider.defaultInstance()
                .decide(STATE, registry.all());

        assertEquals(2, scored.size());
        assertTrue(scored.containsKey("ticket.route"));
        assertTrue(scored.containsKey("alert.is_real"));
        scored.values().forEach(point -> {
            assertEquals(Decision.Outcome.DEGRADED, point.outcome());
            assertNotNull(point.degradedReason());
        });
    }

    @Test
    @DisplayName("providerId 固定为 rules——它进缓存键，必须稳定")
    void providerIdIsStable() {
        assertEquals("rules", RuleFallbackProvider.defaultInstance().providerId());
    }
}
