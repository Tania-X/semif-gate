package com.semif.gate.gate;

import com.semif.gate.audit.DecisionRecord;
import com.semif.gate.audit.InMemoryDecisionAudit;
import com.semif.gate.cache.InMemoryDecisionCache;
import com.semif.gate.contract.Decision;
import com.semif.gate.contract.DecisionPoint;
import com.semif.gate.contract.ScoredPoint;
import com.semif.gate.policy.ThresholdPolicyEngine;
import com.semif.gate.provider.DecisionProvider;
import com.semif.gate.provider.ReplayProvider;
import com.semif.gate.registry.SlotCheck;
import com.semif.gate.state.DecisionState;
import com.semif.gate.testkit.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关的失败语义与确定性。
 *
 * <p>核心主张：<b>provider 挂掉不该让调用链停摆，但降级必须留下明确痕迹。</b>
 */
class GatewaySemanticsTest {

    private static final Map<String, ?> STATE = Map.of(
            "id", Fixtures.ANCHOR_ID, "service", "checkout", "duration_s", 480);

    /** 永远抛异常的 provider，模拟超时/连接失败。 */
    private static final class ExplodingProvider implements DecisionProvider {
        private final String providerId;
        private final RuntimeException failure;
        private int calls;

        ExplodingProvider(String providerId, RuntimeException failure) {
            this.providerId = providerId;
            this.failure = failure;
        }

        @Override
        public String providerId() {
            return providerId;
        }

        @Override
        public Map<String, ScoredPoint> decide(DecisionState state, List<DecisionPoint> points) {
            calls++;
            throw failure;
        }

        @Override
        public SlotCheck.Result assertSlots(SlotCheck.DecisionPointLike point, String promptText) {
            return new SlotCheck.Result(point.ref(), SlotCheck.Status.SKIPPED, List.of("n/a"), List.of());
        }
    }

    private static DecisionGateway gatewayWith(DecisionProvider provider, InMemoryDecisionAudit audit) {
        return new DecisionGateway(Fixtures.fullRegistry(), Fixtures.normalizer(),
                new InMemoryDecisionCache(), provider, Fixtures.policy(), audit);
    }

    @Test
    @DisplayName("provider 抛异常 → 该点 DEGRADED 且有原因，请求整体不失败")
    void providerFailureDegradesWithoutFailingRequest() {
        InMemoryDecisionAudit audit = new InMemoryDecisionAudit();
        ExplodingProvider provider = new ExplodingProvider("http", new IllegalStateException("connection refused"));
        DecisionGateway gateway = gatewayWith(provider, audit);

        GatewayResult result = gateway.decide(STATE,
                List.of("evidence.support@1", "policy.compliance@1"));

        assertEquals(2, result.size(), "请求必须仍然返回全部判定点");
        for (Decision decision : result.decisions().values()) {
            assertEquals(Decision.Outcome.DEGRADED, decision.outcome());
            assertFalse(decision.ok());
            assertNotNull(decision.degradedReason());
            assertTrue(decision.degradedReason().contains("provider 调用异常"),
                    () -> "原因应说明是 provider 异常: " + decision.degradedReason());
        }
        assertEquals(1, provider.calls);
    }

    @Test
    @DisplayName("超时降级 → 档位落 REFUSE，绝不被误判为可自动执行")
    void degradedNeverLandsInAutoBand() {
        InMemoryDecisionAudit audit = new InMemoryDecisionAudit();
        // HttpTimeoutException 继承自 IOException 而非 RuntimeException，
        // 真实 HttpProvider 会把它转成 DEGRADED；这里模拟 provider 侧的最终行为。
        DecisionGateway gateway = gatewayWith(
                new ExplodingProvider("http", new IllegalStateException("http timeout")), audit);

        GatewayResult result = gateway.decide(STATE, List.of("evidence.support@1"));

        DecisionRecord record = result.records().get(0);
        assertEquals(com.semif.gate.contract.Band.REFUSE, record.band(),
                "降级必须落最低档——否则「没判定出来」会被当成「判定为某个答案」");
        assertEquals(com.semif.gate.contract.Action.SAFE_DEFAULT,
                Fixtures.policy().onDegraded("evidence.support"));
    }

    @Test
    @DisplayName("降级样本的最高概率低于自动阈值，但策略仍按降级先判")
    void degradedDistributionIsNotHighConfidence() {
        InMemoryDecisionAudit audit = new InMemoryDecisionAudit();
        DecisionGateway gateway = gatewayWith(
                new ExplodingProvider("http", new RuntimeException("boom")), audit);

        GatewayResult result = gateway.decide(STATE, List.of("evidence.support@1"));
        Decision decision = result.decision("evidence.support");

        // 降级用均分分布，最高概率 = 1/3，远低于 0.6 的复核线
        assertTrue(decision.maxProbability() < 0.6);
        assertEquals(1.0 / 3.0, decision.maxProbability(), 1e-9);
    }

    @Test
    @DisplayName("provider 返回不完整结果 → 缺失的点降级，不静默成功")
    void incompleteProviderResultDegradesMissingPoints() {
        DecisionProvider partial = new DecisionProvider() {
            @Override
            public String providerId() {
                return "partial";
            }

            @Override
            public Map<String, ScoredPoint> decide(DecisionState state, List<DecisionPoint> points) {
                // 只返回第一个点，故意漏掉其余的
                DecisionPoint first = points.get(0);
                return Map.of(first.id(), ScoredPoint.degraded(
                        Fixtures.evidenceSupport().options().stream()
                                .map(o -> new com.semif.gate.contract.OptionScore(o.id(), 1.0 / 3.0))
                                .toList(),
                        "测试用", Fixtures.REVISION, "test", "f".repeat(64)));
            }

            @Override
            public SlotCheck.Result assertSlots(SlotCheck.DecisionPointLike point, String promptText) {
                return new SlotCheck.Result(point.ref(), SlotCheck.Status.SKIPPED, List.of("n/a"), List.of());
            }
        };
        InMemoryDecisionAudit audit = new InMemoryDecisionAudit();
        DecisionGateway gateway = gatewayWith(partial, audit);

        GatewayResult result = gateway.decide(STATE,
                List.of("evidence.support@1", "policy.compliance@1"));

        assertEquals(2, result.size());
        assertTrue(result.decision("policy.compliance").degradedReason().contains("provider 未返回"));
    }

    @Test
    @DisplayName("decisionId 确定性：同输入两次调用（都穿透缓存）得到相同 id")
    void decisionIdIsDeterministic() {
        // 每次都用全新的缓存，强制两次都走 provider
        DecisionGateway first = gatewayWith(Fixtures.replayProvider(), new InMemoryDecisionAudit());
        DecisionGateway second = gatewayWith(Fixtures.replayProvider(), new InMemoryDecisionAudit());

        Decision a = first.decide(STATE, List.of("evidence.support@1")).decision("evidence.support");
        Decision b = second.decide(STATE, List.of("evidence.support@1")).decision("evidence.support");

        assertEquals(a.decisionId(), b.decisionId(),
                "decisionId 必须只由输入决定，否则审计无法关联同一次判定");
        assertEquals(a.distribution(), b.distribution());
        assertEquals(a.provenance().registrySha256(), b.provenance().registrySha256());
    }

    @Test
    @DisplayName("网关独占补齐 registrySha256 与 optionsSha256")
    void gatewayFillsContractFingerprint() {
        DecisionGateway gateway = gatewayWith(Fixtures.replayProvider(), new InMemoryDecisionAudit());

        Decision decision = gateway.decide(STATE, List.of("evidence.support@1"))
                .decision("evidence.support");

        assertEquals(gateway.registrySha256(), decision.provenance().registrySha256(),
                "registrySha256 是契约属性，只能由网关补齐");
        // optionsSha256 取自【注册表内】的判定点（由注册表计算），
        // 而不是 Fixtures 里那份未注册的原型——后者的值还是占位符。
        assertEquals(Fixtures.fullRegistry().require("evidence.support@1").optionsSha256(),
                decision.provenance().optionsSha256());
        assertEquals("replay", decision.provenance().providerId());
        assertEquals(Fixtures.REVISION, decision.provenance().modelRevision());
    }

    @Test
    @DisplayName("未注册的判定点 → 直接报错，不产生静默降级")
    void unknownPointRefIsRejected() {
        DecisionGateway gateway = gatewayWith(Fixtures.replayProvider(), new InMemoryDecisionAudit());

        assertThrows(com.semif.gate.registry.RegistryException.class,
                () -> gateway.decide(STATE, List.of("no.such.point@1")));
    }

    @Test
    @DisplayName("空判定点列表 → 报错")
    void emptyPointRefsIsRejected() {
        DecisionGateway gateway = gatewayWith(Fixtures.replayProvider(), new InMemoryDecisionAudit());

        assertThrows(IllegalArgumentException.class, () -> gateway.decide(STATE, List.of()));
    }

    @Test
    @DisplayName("不同 providerId → 不同 decisionId（跨 provider 结果不混用）")
    void differentProviderYieldsDifferentDecisionId() {
        Decision viaReplay = gatewayWith(Fixtures.replayProvider(), new InMemoryDecisionAudit())
                .decide(STATE, List.of("evidence.support@1")).decision("evidence.support");

        ReplayProvider renamed = Fixtures.replayProvider();
        DecisionProvider aliased = new DecisionProvider() {
            @Override
            public String providerId() {
                return "replay-alias";
            }

            @Override
            public Map<String, ScoredPoint> decide(DecisionState state, List<DecisionPoint> points) {
                return renamed.decide(state, points);
            }

            @Override
            public SlotCheck.Result assertSlots(SlotCheck.DecisionPointLike point, String promptText) {
                return renamed.assertSlots(point, promptText);
            }
        };

        Decision viaAlias = gatewayWith(aliased, new InMemoryDecisionAudit())
                .decide(STATE, List.of("evidence.support@1")).decision("evidence.support");

        assertNotEquals(viaReplay.decisionId(), viaAlias.decisionId(),
                "providerId 进缓存键，换了 provider 就是另一次判定");
    }

    @Test
    @DisplayName("策略阈值配置校验：autoMin 必须大于 reviewMin")
    void policyThresholdsAreValidated() {
        assertThrows(IllegalArgumentException.class,
                () -> new ThresholdPolicyEngine(0.5, 0.9, "bad",
                        com.semif.gate.contract.Action.SAFE_DEFAULT));
    }
}
