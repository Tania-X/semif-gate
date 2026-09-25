package com.semif.gate.testkit;

import com.semif.gate.contract.Decision;
import com.semif.gate.contract.DecisionPoint;
import com.semif.gate.contract.OptionScore;
import com.semif.gate.contract.Provenance;
import com.semif.gate.contract.ScoredPoint;
import com.semif.gate.state.DecisionKey;
import com.semif.gate.state.DecisionState;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 测试共用工具：把 {@link ScoredPoint} 组装成 {@link Decision}。
 *
 * <p>生产代码里这个组装动作<b>只发生在网关内部</b>（见 {@code DecisionGateway}）。
 * 测试里需要单独验证 provider 的输出，所以在这里复刻一份最小实现——
 * 刻意与网关使用同一个 {@link DecisionKey}，避免测试与生产算出不同的键。
 */
public final class TestDecisions {

    private TestDecisions() {
    }

    /** 构造一份字段齐全的溯源信息。 */
    public static Provenance provenance(String providerId,
                                        String modelRevision,
                                        String backend,
                                        String promptSha256,
                                        String registrySha256,
                                        String optionsSha256) {
        return new Provenance(providerId, modelRevision, backend, promptSha256,
                registrySha256, optionsSha256, 1, 1L);
    }

    /** 一组固定的合法哈希，供不需要关心具体值的测试使用。 */
    public static Provenance dummyProvenance() {
        return provenance("test", "rev", "none",
                "a".repeat(64), "b".repeat(64), "c".repeat(64));
    }

    /**
     * 把 provider 的原始分数组装成判定。
     *
     * <p>{@code decisionId} 由 {@link DecisionKey} 计算——与网关走同一条路径。
     */
    public static Decision assemble(DecisionState state,
                                    DecisionPoint point,
                                    ScoredPoint scored,
                                    String registrySha256,
                                    String providerId) {
        Provenance provenance = provenance(
                providerId,
                scored.modelRevision(),
                scored.backend(),
                scored.promptSha256(),
                registrySha256,
                point.optionsSha256());
        String decisionId = DecisionKey.of(state, point, provenance);
        return new Decision(
                decisionId,
                point.id(),
                point.version(),
                scored.outcome(),
                scored.distribution(),
                provenance,
                scored.degradedReason());
    }

    /** 便捷构造：直接给若干 (optionId, probability) 对。 */
    public static ScoredPoint scored(String modelRevision, String backend, Object... idProbPairs) {
        List<OptionScore> distribution = distribution(idProbPairs);
        return new ScoredPoint(Decision.Outcome.OK, distribution, null,
                modelRevision, backend, "f".repeat(64), 10, 5L);
    }

    /** 把 (optionId, probability, ...) 变长参数转成分布。 */
    public static List<OptionScore> distribution(Object... idProbPairs) {
        if (idProbPairs.length % 2 != 0) {
            throw new IllegalArgumentException("必须成对给出 optionId 与概率");
        }
        TreeMap<String, OptionScore> sorted = new TreeMap<>();
        for (int i = 0; i < idProbPairs.length; i += 2) {
            String id = (String) idProbPairs[i];
            double probability = ((Number) idProbPairs[i + 1]).doubleValue();
            sorted.put(id, new OptionScore(id, probability));
        }
        return List.copyOf(sorted.values());
    }

    /** 从分布构造判定（跳过 provider，直接测契约）。 */
    public static Decision decision(String pointId, int version, List<OptionScore> distribution) {
        return new Decision(pointId + "-key", pointId, version, Decision.Outcome.OK,
                distribution, dummyProvenance(), null);
    }

    /** 以 optionId 为键的分布视图。 */
    public static Map<String, Double> asMap(List<OptionScore> distribution) {
        TreeMap<String, Double> map = new TreeMap<>();
        distribution.forEach(score -> map.put(score.optionId(), score.probability()));
        return Map.copyOf(map);
    }
}
