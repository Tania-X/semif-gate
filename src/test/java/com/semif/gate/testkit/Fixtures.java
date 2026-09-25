package com.semif.gate.testkit;

import com.semif.gate.cache.DecisionCache;
import com.semif.gate.cache.InMemoryDecisionCache;
import com.semif.gate.contract.AnswerStyle;
import com.semif.gate.contract.DecisionPoint;
import com.semif.gate.contract.Option;
import com.semif.gate.policy.PolicyEngine;
import com.semif.gate.policy.ThresholdPolicyEngine;
import com.semif.gate.provider.ReplayProvider;
import com.semif.gate.registry.DecisionPointRegistry;
import com.semif.gate.state.DecisionState;
import com.semif.gate.state.StateNormalizer;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 测试夹具：把真实 fixture、判定点契约、状态构造集中在一处。
 *
 * <p>判定点的选项集<b>刻意与 fixture 记录一致</b>——回放 provider 会校验两者相符，
 * 不符即降级。这不是限制，而是它应有的行为：选项集不同意味着那条记录
 * 根本不是在回答这个问题。
 */
public final class Fixtures {

    /** 真实预测 fixture（取自 4090 PLUS 上的 SemIf 运行）。 */
    public static final String REPLAY_FIXTURE =
            "src/test/resources/replay/real-predictions.jsonl";

    /** 模型 revision，与 fixture 中的值一致。 */
    public static final String REVISION = "851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a";

    /** fixture 中那条映射锚点的 id。 */
    public static final String ANCHOR_ID = "14790d6d50043b03420c";

    /** fixture 中三选项精确平局的 id（margin = 0）。 */
    public static final String TIE_ID = "f2b4ec4930322fe45118";

    private Fixtures() {
    }

    /** 真实回放 provider。 */
    public static ReplayProvider replayProvider() {
        return ReplayProvider.fromJsonl(Path.of(REPLAY_FIXTURE));
    }

    /**
     * 状态规范化器：白名单必须包含 {@code id}。
     *
     * <p>回放 provider 靠 state 里的 {@code id} 找到对应记录，
     * 而白名单是「只有列出的字段才会进入 state」的闸门——漏掉它就回放不了。
     */
    public static StateNormalizer normalizer() {
        return new StateNormalizer(Set.of("id", "service", "duration_s", "evidence"));
    }

    /**
     * 构造 state；{@code id} 用于对应 fixture 记录。
     *
     * <p>带 {@code evidence} 字段：真实接入方的 state 里承载证据的字段就是它，
     * 而 {@code PromptRenderer.renderWithField} 需要从 state 中取出该字段的**值**
     * 作为 payload 的 evidence（而不是把整个 state 对象塞进去）。
     */
    public static DecisionState state(String id) {
        return normalizer().normalize(Map.of(
                "id", id, "service", "checkout", "duration_s", 480,
                "evidence", "the service reported a latency spike"));
    }

    // ---------------------------------------------------------------- 判定点

    /** 对应 fixture 的 contradicted / insufficient / supported 三选项。 */
    public static DecisionPoint evidenceSupport() {
        return point("evidence.support", "证据是否支持该主张？", List.of(
                new Option("contradicted", "证据指向相反结论。"),
                new Option("insufficient", "证据不足以判定。"),
                new Option("supported", "证据支持该主张。")));
    }

    /** 对应 fixture 的 insufficient / permitted / prohibited 三选项。 */
    public static DecisionPoint policyCompliance() {
        return point("policy.compliance", "该操作是否被策略允许？", List.of(
                new Option("insufficient", "证据不足以判定。"),
                new Option("permitted", "策略允许该操作。"),
                new Option("prohibited", "策略禁止该操作。")));
    }

    /** 对应 fixture 的 A / B / insufficient 三选项。 */
    public static DecisionPoint candidateSelection() {
        return point("candidate.select", "哪个候选满足要求？", List.of(
                new Option("A", "候选 A。"),
                new Option("B", "候选 B。"),
                new Option("insufficient", "没有候选满足要求。")));
    }

    /** 单个判定点的注册表。 */
    public static DecisionPointRegistry registryOf(DecisionPoint... points) {
        return DecisionPointRegistry.of(List.of(points));
    }

    /** 三个判定点齐全的注册表。 */
    public static DecisionPointRegistry fullRegistry() {
        return DecisionPointRegistry.of(List.of(
                evidenceSupport(), policyCompliance(), candidateSelection()));
    }

    private static DecisionPoint point(String id, String question, List<Option> options) {
        return new DecisionPoint(id, 1, "test-team", "2026-09-25", question, options,
                "{\"evidence\": {{state}}, \"criterion\": {{question}}, \"options\": {{options}}}",
                AnswerStyle.LETTER, REVISION, "<待计算>");
    }

    // ---------------------------------------------------------------- 组件

    public static DecisionCache cache() {
        return new InMemoryDecisionCache();
    }

    public static PolicyEngine policy() {
        return ThresholdPolicyEngine.defaults();
    }
}
