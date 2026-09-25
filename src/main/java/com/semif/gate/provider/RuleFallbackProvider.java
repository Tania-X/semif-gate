package com.semif.gate.provider;

import com.semif.gate.contract.Decision;
import com.semif.gate.contract.DecisionPoint;
import com.semif.gate.contract.Option;
import com.semif.gate.contract.OptionScore;
import com.semif.gate.contract.ScoredPoint;
import com.semif.gate.registry.PromptRenderer;
import com.semif.gate.registry.RegistryHasher;
import com.semif.gate.registry.SlotCheck;
import com.semif.gate.state.DecisionState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 永不失败的降级实现。
 *
 * <p><b>它存在的理由是：模型超时不应该让告警链路停摆。</b>
 * 当真实 provider 超时、熔断或返回不合契约的响应时，网关必须还能给出一个
 * 明确标记为「降级」的结果，让上游走保守路径。
 *
 * <h2>两条不可妥协的纪律</h2>
 * <ol>
 *   <li><b>结果必须标记为 {@link Decision.Outcome#DEGRADED} 并带上原因。</b>
 *       绝不能伪装成正常判定——否则「没判定出来」会被下游当成「判定为不可能」，
 *       这是把可用性问题升级成正确性问题的经典方式。</li>
 *   <li><b>分布必须确定性。</b>同样的 state 与判定点永远得到同样的分布，
 *       否则缓存键与实际内容不一致，降级路径会污染缓存。</li>
 * </ol>
 *
 * <p>降级分布的形状：给配置的保守选项一个高于其余选项的概率，其余均分。
 * 之所以不给「保守选项 = 1.0」：那会让策略层的阈值判断误以为模型非常确信，
 * 从而可能触发自动执行分支。这里刻意让最高概率低于典型的高置信阈值。
 */
public final class RuleFallbackProvider implements DecisionProvider {

    /** provider 标识，进缓存键。 */
    public static final String PROVIDER_ID = "rules";

    /** 降级时使用的后端标记。 */
    public static final String BACKEND = "none";

    /**
     * 降级结果中保守选项的概率。
     *
     * <p>取 0.5 是刻意的：它低于常见的高置信阈值（0.6–0.9），
     * 因此策略层会把降级结果稳定地判为「需要复核」而不是「自动执行」。
     */
    private static final double DEFAULT_OPTION_PROBABILITY = 0.5;

    /** 降级结果默认使用的模型 revision 标签——表明这次判定没有用到模型。 */
    public static final String NO_MODEL_REVISION = "none";

    private final String degradedReason;
    private final Map<String, String> conservativeOptionByPoint;

    /**
     * @param conservativeOptionByPoint 每个判定点的保守选项：{@code pointId -> optionId}
     * @param degradedReason            记录到判定结果中的降级原因
     */
    public RuleFallbackProvider(Map<String, String> conservativeOptionByPoint, String degradedReason) {
        if (degradedReason == null || degradedReason.isBlank()) {
            throw new IllegalArgumentException("必须说明降级原因——降级结果不能没有解释");
        }
        this.conservativeOptionByPoint = Map.copyOf(conservativeOptionByPoint);
        this.degradedReason = degradedReason;
    }

    /** 无自定义保守选项的默认实例：一律取第一个选项。 */
    public static RuleFallbackProvider defaultInstance() {
        return new RuleFallbackProvider(Map.of(), "provider 不可用，使用规则兜底");
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public Map<String, ScoredPoint> decide(DecisionState state, List<DecisionPoint> points) {
        Map<String, ScoredPoint> results = new LinkedHashMap<>();
        for (DecisionPoint point : points) {
            results.put(point.id(), decideOne(state, point));
        }
        return Map.copyOf(results);
    }

    private ScoredPoint decideOne(DecisionState state, DecisionPoint point) {
        String prompt = PromptRenderer.render(point, state.json());
        return new ScoredPoint(
                Decision.Outcome.DEGRADED,
                conservativeDistribution(point),
                degradedReason + "（判定点 " + point.ref() + "）",
                NO_MODEL_REVISION,
                BACKEND,
                RegistryHasher.promptSha256(prompt),
                state.codePointLength(),
                0L);
    }

    /**
     * 构造保守分布：保守选项占 {@value #DEFAULT_OPTION_PROBABILITY}，其余均分。
     *
     * <p>按 optionId 字典序构造，保证确定性。
     */
    private List<OptionScore> conservativeDistribution(DecisionPoint point) {
        TreeMap<String, Option> sorted = new TreeMap<>();
        point.options().forEach(option -> sorted.put(option.id(), option));
        String conservativeId = conservativeOptionByPoint.getOrDefault(
                point.id(), sorted.firstKey());
        if (!sorted.containsKey(conservativeId)) {
            throw new IllegalArgumentException("判定点 " + point.ref()
                    + " 配置的保守选项不在选项集中: " + conservativeId);
        }

        List<String> others = new ArrayList<>(sorted.keySet());
        others.remove(conservativeId);
        List<OptionScore> distribution = new ArrayList<>(sorted.size());
        if (others.isEmpty()) {
            distribution.add(new OptionScore(conservativeId, 1.0));
            return distribution;
        }
        double each = (1.0 - DEFAULT_OPTION_PROBABILITY) / others.size();
        for (String optionId : sorted.keySet()) {
            distribution.add(new OptionScore(optionId,
                    optionId.equals(conservativeId) ? DEFAULT_OPTION_PROBABILITY : each));
        }
        return List.copyOf(distribution);
    }

    @Override
    public SlotCheck.Result assertSlots(SlotCheck.DecisionPointLike point, String promptText) {
        return new SlotCheck.Result(point.ref(), SlotCheck.Status.SKIPPED,
                List.of("规则兜底不经过模型，不存在答案槽位契约"), List.of());
    }

    @Override
    public boolean supportsPrefixReuse() {
        return false;
    }
}
