package com.semif.gate.audit;

import com.semif.gate.cache.InMemoryDecisionCache;
import com.semif.gate.contract.Band;
import com.semif.gate.gate.DecisionGateway;
import com.semif.gate.gate.GatewayResult;
import com.semif.gate.testkit.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审计完整性测试。
 *
 * <p>审计是「这条判定是怎么来的」的唯一证据。因此这里逐一断言：
 * 每个判定点都有一条记录、每条记录都带齐能解释它的字段、
 * 并且<b>{@code margin} 必须存在</b>——它是「平局舍入」与「真实漂移」的分界线。
 */
class AuditCompletenessTest {

    private static final Map<String, ?> STATE = Map.of(
            "id", Fixtures.ANCHOR_ID, "service", "checkout", "duration_s", 480);

    private static DecisionGateway gateway(InMemoryDecisionAudit audit) {
        return new DecisionGateway(Fixtures.fullRegistry(), Fixtures.normalizer(),
                new InMemoryDecisionCache(), Fixtures.replayProvider(),
                Fixtures.policy(), audit);
    }

    @Test
    @DisplayName("记录数 == 判定点数，且每条的字段齐全")
    void everyDecisionIsAudited() {
        InMemoryDecisionAudit audit = new InMemoryDecisionAudit();
        GatewayResult result = gateway(audit).decide(STATE,
                List.of("evidence.support@1", "policy.compliance@1", "candidate.select@1"));

        assertEquals(3, audit.size(), "每个判定点都必须留下一条审计记录");
        assertEquals(3, result.records().size());

        for (DecisionRecord record : audit.records()) {
            assertNotNull(record.decisionId());
            assertEquals(64, record.decisionId().length(), "decisionId 是 SHA-256");
            assertNotNull(record.pointId());
            assertTrue(record.pointVersion() >= 1);
            assertEquals(64, record.stateHash().length());
            assertNotNull(record.stateJson());
            assertFalse(record.distribution().isEmpty());
            assertNotNull(record.argmaxOption());
            assertTrue(record.maxProbability() >= 0.0 && record.maxProbability() <= 1.0);
            assertTrue(record.margin() >= 0.0, "margin 不能为负");
            assertNotNull(record.band());
            assertNotNull(record.policyVersion());
            assertEquals("replay", record.providerId());
            // 注意：这个 state 只与 evidence.support 的选项集匹配，
            // 另外两个判定点会被回放 provider 正当降级（revision = "none"）。
            // 这里只断言「非空」，具体取值由 matchingPointCarriesRecordProvenance 覆盖。
            assertNotNull(record.modelRevision());
            assertFalse(record.modelRevision().isBlank());
            assertEquals(64, record.promptSha256().length());
            assertEquals(64, record.registrySha256().length());
            assertTrue(record.inputTokens() >= 0);
            assertTrue(record.latencyMs() >= 0);
        }
    }

    @Test
    @DisplayName("选项集匹配的判定点携带 fixture 的真实溯源")
    void matchingPointCarriesRecordProvenance() {
        InMemoryDecisionAudit audit = new InMemoryDecisionAudit();

        gateway(audit).decide(STATE, List.of("evidence.support@1"));

        DecisionRecord record = audit.records().get(0);
        assertEquals(Fixtures.REVISION, record.modelRevision(),
                "选项集匹配时，revision 必须来自 fixture 记录");
        assertTrue(record.latencyMs() > 0,
                "fixture 的 forward_seconds 应换算成非零毫秒（仅供审计展示，不可用于性能结论）");
        assertTrue(record.inputTokens() > 0);
    }

    @Test
    @DisplayName("margin 字段存在且能识别真实平局")
    void marginDistinguishesTies() {
        InMemoryDecisionAudit audit = new InMemoryDecisionAudit();

        // policy.compliance 的 TIE_ID 在 fixture 里是精确平局（两选项均 0.4750）
        gateway(audit).decide(
                Map.of("id", Fixtures.TIE_ID, "service", "checkout", "duration_s", 480),
                List.of("policy.compliance@1"));

        DecisionRecord record = audit.records().get(0);
        assertEquals(0.0, record.margin(), 1e-9,
                "真实平局的 margin 必须是 0——这是把它与语义漂移区分开的依据");
    }

    @Test
    @DisplayName("非平局记录给出非零 margin")
    void nonTieHasPositiveMargin() {
        InMemoryDecisionAudit audit = new InMemoryDecisionAudit();

        // ANCHOR_ID 的分布是 0.9874 / 0.0124 / 0.0002
        gateway(audit).decide(STATE, List.of("evidence.support@1"));

        DecisionRecord record = audit.records().get(0);
        assertEquals(0.9874 - 0.0124, record.margin(), 1e-3,
                "margin 应为真正的 top-2 之差");
        assertTrue(record.margin() > 0.9);
    }

    @Test
    @DisplayName("缓存命中与未命中都被审计，且 cacheHit 标记正确")
    void bothCachePathsAreAudited() {
        InMemoryDecisionAudit audit = new InMemoryDecisionAudit();
        DecisionGateway gateway = gateway(audit);

        gateway.decide(STATE, List.of("evidence.support@1"));
        assertFalse(audit.records().get(0).cacheHit(), "首次必然是未命中");

        gateway.decide(STATE, List.of("evidence.support@1"));
        assertEquals(2, audit.size(), "第二次调用也要留下记录");
        assertTrue(audit.records().get(1).cacheHit(), "第二次应标记为缓存命中");

        assertEquals(audit.records().get(0).decisionId(), audit.records().get(1).decisionId(),
                "命中与未命中指向同一次判定");
    }

    @Test
    @DisplayName("band 与 policyVersion 落在记录里——策略改了历史仍可解释")
    void bandAndPolicyVersionAreRecorded() {
        InMemoryDecisionAudit audit = new InMemoryDecisionAudit();

        gateway(audit).decide(STATE, List.of("evidence.support@1"));

        DecisionRecord record = audit.records().get(0);
        // 0.9874 >= 0.90 → AUTO
        assertEquals(Band.AUTO, record.band());
        assertEquals(Fixtures.policy().version(), record.policyVersion());
    }

    @Test
    @DisplayName("降级记录带原因，且档位落 REFUSE")
    void degradedRecordCarriesReason() {
        InMemoryDecisionAudit audit = new InMemoryDecisionAudit();
        DecisionGateway gateway = new DecisionGateway(
                Fixtures.fullRegistry(), Fixtures.normalizer(), new InMemoryDecisionCache(),
                new com.semif.gate.provider.RuleFallbackProvider(
                        Map.of(), "模型不可用（测试）"),
                Fixtures.policy(), audit);

        gateway.decide(STATE, List.of("evidence.support@1"));

        DecisionRecord record = audit.records().get(0);
        assertNotNull(record.degradedReason());
        assertTrue(record.degradedReason().contains("模型不可用"));
        assertEquals(Band.REFUSE, record.band());
        assertEquals("rules", record.providerId());
    }

    @Test
    @DisplayName("按判定点检索记录")
    void recordsAreRetrievableByPointId() {
        InMemoryDecisionAudit audit = new InMemoryDecisionAudit();
        gateway(audit).decide(STATE,
                List.of("evidence.support@1", "policy.compliance@1", "candidate.select@1"));

        assertEquals(1, audit.byPointId("evidence.support").size());
        assertEquals(1, audit.byPointId("policy.compliance").size());
        assertEquals(0, audit.byPointId("no.such.point").size());
    }

    @Test
    @DisplayName("记录携带完整溯源：prompt 与 registry 哈希可用于事后比对")
    void recordsCarryProvenance() {
        InMemoryDecisionAudit audit = new InMemoryDecisionAudit();
        DecisionGateway gateway = gateway(audit);
        gateway.decide(STATE, List.of("evidence.support@1"));

        DecisionRecord record = audit.records().get(0);
        assertEquals(gateway.registrySha256(), record.registrySha256());
        assertEquals(gateway.providerId(), record.providerId());
        assertNotNull(record.stateJson());
        assertTrue(record.stateJson().contains(Fixtures.ANCHOR_ID),
                "stateJson 应当含实际发送的状态内容");
    }
}
