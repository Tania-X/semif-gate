package com.semif.gate.gate;

import com.semif.gate.cache.CachedDecision;
import com.semif.gate.cache.DecisionCache;
import com.semif.gate.cache.InMemoryDecisionCache;
import com.semif.gate.contract.Decision;
import com.semif.gate.contract.DecisionPoint;
import com.semif.gate.contract.Option;
import com.semif.gate.contract.OptionScore;
import com.semif.gate.contract.ScoredPoint;
import com.semif.gate.registry.DecisionPointRegistry;
import com.semif.gate.state.DecisionState;
import com.semif.gate.state.StateHasher;
import com.semif.gate.testkit.Fixtures;
import com.semif.gate.testkit.TestDecisions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缓存语义测试——重点在<b>「绝不返回旧语义结果」</b>这一条。
 *
 * <p>缓存出错有两种：一种是不命中（慢），一种是返回错的结果（错）。
 * 后者不会报错、不会被监控发现，只会在某次事故复盘时暴露。
 * 因此这里的测试刻意围绕它展开。
 */
class CacheSemanticsTest {

    private static final String REGISTRY_HASH = StateHasher.sha256Hex("registry-v1");

    @Test
    @DisplayName("契约指纹不符 → 视为未命中，绝不返回旧语义结果")
    void staleRegistryHashIsRejected() {
        DecisionPoint point = Fixtures.evidenceSupport();
        InMemoryDecisionCache cache = new InMemoryDecisionCache();
        DecisionState state = Fixtures.state(Fixtures.ANCHOR_ID);

        // 用「旧注册表哈希」写入一条
        Decision decision = decisionFor(state, point, 0.9, 0.05, 0.05);
        cache.store(state.hash(), point, decision, REGISTRY_HASH);
        assertTrue(cache.lookup(state.hash(), point, REGISTRY_HASH).isPresent(),
                "同一契约下应当命中");

        // 注册表变了（比如模板或选项描述被改动）
        String changedRegistry = StateHasher.sha256Hex("registry-v2");
        Optional<CachedDecision> afterChange = cache.lookup(state.hash(), point, changedRegistry);

        assertTrue(afterChange.isEmpty(),
                "注册表变更后必须视为未命中——返回旧结果等于用旧语义回答新问题");
        assertEquals(1, cache.staleRejections(), "被拒的命中必须计入 staleRejections，便于观测契约变更");
    }

    @Test
    @DisplayName("选项集变更 → 即使注册表哈希相同也必须未命中")
    void changedOptionsAreRejected() {
        DecisionPoint original = Fixtures.evidenceSupport();
        InMemoryDecisionCache cache = new InMemoryDecisionCache();
        DecisionState state = Fixtures.state(Fixtures.ANCHOR_ID);

        cache.store(state.hash(), original, decisionFor(state, original, 0.9, 0.05, 0.05),
                REGISTRY_HASH);

        // 同 id、同版本，但选项描述被改动 —— 模型看到的候选变了，是语义变更
        DecisionPoint changed = new DecisionPoint(
                original.id(), original.version(), original.owner(), original.frozenAt(),
                original.question(),
                List.of(new Option("contradicted", "改写过的描述。"),
                        new Option("insufficient", "证据不足以判定。"),
                        new Option("supported", "证据支持该主张。")),
                original.promptTemplate(), original.answerStyle(),
                original.modelRevision(), "<待计算>");
        changed = new DecisionPoint(changed.id(), changed.version(), changed.owner(),
                changed.frozenAt(), changed.question(), changed.options(),
                changed.promptTemplate(), changed.answerStyle(), changed.modelRevision(),
                com.semif.gate.registry.RegistryHasher.optionsSha256(changed.options()));

        assertFalse(original.optionsSha256().equals(changed.optionsSha256()),
                "前提：描述改动必须改变选项集哈希");

        assertTrue(cache.lookup(state.hash(), changed, REGISTRY_HASH).isEmpty(),
                "选项集变更后必须未命中");
    }

    @Test
    @DisplayName("命中不调用 provider：第二次同 state 调用后 provider 计数不变")
    void hittingCacheDoesNotCallProvider() {
        CountingProvider provider = new CountingProvider();
        InMemoryDecisionCache cache = new InMemoryDecisionCache();
        DecisionGateway gateway = new DecisionGateway(
                Fixtures.registryOf(Fixtures.evidenceSupport()), Fixtures.normalizer(),
                cache, provider, Fixtures.policy(), new com.semif.gate.audit.InMemoryDecisionAudit());

        Map<String, ?> rawState = Map.of("id", Fixtures.ANCHOR_ID,
                "service", "checkout", "duration_s", 480);

        GatewayResult first = gateway.decide(rawState, List.of("evidence.support@1"));
        assertEquals(1, provider.calls.get(), "首次必须调用 provider");
        assertEquals(1, first.providerCalls());
        assertEquals(0, first.cacheHits());
        assertEquals(1, first.cacheMisses());

        GatewayResult second = gateway.decide(rawState, List.of("evidence.support@1"));
        assertEquals(1, provider.calls.get(), "第二次必须完全由缓存服务，provider 计数不变");
        assertEquals(0, second.providerCalls());
        assertEquals(1, second.cacheHits());
        assertTrue(second.servedEntirelyFromCache());
        assertEquals(1.0, second.cacheHitRate(), 1e-9);

        assertEquals(first.decision("evidence.support").decisionId(),
                second.decision("evidence.support").decisionId(),
                "同一 state 两次调用必须得到同一个 decisionId");
        assertEquals(first.decision("evidence.support").distribution(),
                second.decision("evidence.support").distribution());
    }

    @Test
    @DisplayName("不同 state 不共享缓存条目")
    void differentStatesDoNotShareEntries() {
        CountingProvider provider = new CountingProvider();
        DecisionGateway gateway = new DecisionGateway(
                Fixtures.registryOf(Fixtures.evidenceSupport(), Fixtures.policyCompliance()),
                Fixtures.normalizer(), new InMemoryDecisionCache(), provider,
                Fixtures.policy(), new com.semif.gate.audit.InMemoryDecisionAudit());

        gateway.decide(Map.of("id", Fixtures.ANCHOR_ID, "service", "checkout", "duration_s", 480),
                List.of("evidence.support@1"));
        gateway.decide(Map.of("id", Fixtures.TIE_ID, "service", "checkout", "duration_s", 480),
                List.of("policy.compliance@1"));

        assertEquals(2, provider.calls.get(), "两个不同 state 各自需要一次 provider 调用");
    }

    @Test
    @DisplayName("缓存读失败 → 当作未命中继续，不影响正确性")
    void cacheReadFailureFallsBackToProvider() {
        CountingProvider provider = new CountingProvider();
        DecisionCache brokenCache = new DecisionCache() {
            @Override
            public Optional<CachedDecision> lookup(String stateHash, DecisionPoint point,
                                                   String registrySha256) {
                throw new com.semif.gate.cache.CacheAccessException("模拟缓存不可用",
                        new RuntimeException("boom"));
            }

            @Override
            public void store(String stateHash, DecisionPoint point, Decision decision,
                              String registrySha256) {
                throw new com.semif.gate.cache.CacheAccessException("模拟缓存不可用",
                        new RuntimeException("boom"));
            }
        };
        DecisionGateway gateway = new DecisionGateway(
                Fixtures.registryOf(Fixtures.evidenceSupport()), Fixtures.normalizer(),
                brokenCache, provider, Fixtures.policy(),
                new com.semif.gate.audit.InMemoryDecisionAudit());

        GatewayResult result = gateway.decide(
                Map.of("id", Fixtures.ANCHOR_ID, "service", "checkout", "duration_s", 480),
                List.of("evidence.support@1"));

        assertEquals(1, provider.calls.get(), "缓存坏了必须回退到 provider");
        assertTrue(result.decision("evidence.support").ok(), "判定本身必须仍然成功");
    }

    @Test
    @DisplayName("同一 state 多判定点：只发生一次 provider 调用（批量）")
    void multiplePointsShareOneProviderCall() {
        CountingProvider provider = new CountingProvider();
        DecisionGateway gateway = new DecisionGateway(
                Fixtures.fullRegistry(), Fixtures.normalizer(), new InMemoryDecisionCache(),
                provider, Fixtures.policy(), new com.semif.gate.audit.InMemoryDecisionAudit());

        GatewayResult result = gateway.decide(
                Map.of("id", Fixtures.ANCHOR_ID, "service", "checkout", "duration_s", 480),
                List.of("evidence.support@1", "policy.compliance@1", "candidate.select@1"));

        assertEquals(1, provider.calls.get(),
                "三个判定点必须合并成一次 provider 调用——这正是 provider 接口按批设计的原因");
        assertEquals(3, result.size());
        assertEquals(3, result.cacheMisses());
    }

    // ---------------------------------------------------------------- 测试替身

    /** 计数用 provider：真实回放 + 调用次数统计。 */
    private static final class CountingProvider implements com.semif.gate.provider.DecisionProvider {

        private final com.semif.gate.provider.ReplayProvider delegate = Fixtures.replayProvider();
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String providerId() {
            return delegate.providerId();
        }

        @Override
        public Map<String, ScoredPoint> decide(DecisionState state, List<DecisionPoint> points) {
            calls.incrementAndGet();
            return delegate.decide(state, points);
        }

        @Override
        public com.semif.gate.registry.SlotCheck.Result assertSlots(
                com.semif.gate.registry.SlotCheck.DecisionPointLike point, String promptText) {
            return delegate.assertSlots(point, promptText);
        }
    }

    /** 构造一个确定性的判定，用于缓存测试。 */
    private static Decision decisionFor(DecisionState state, DecisionPoint point,
                                        double first, double second, double third) {
        List<String> ids = new ArrayList<>(point.options().stream().map(Option::id).toList());
        ids.sort(String::compareTo);
        Map<String, Double> byId = new LinkedHashMap<>();
        byId.put(ids.get(0), first);
        byId.put(ids.get(1), second);
        byId.put(ids.get(2), third);
        List<OptionScore> distribution = new ArrayList<>();
        byId.forEach((id, probability) -> distribution.add(new OptionScore(id, probability)));

        ScoredPoint scored = new ScoredPoint(Decision.Outcome.OK, distribution, null,
                Fixtures.REVISION, "torch", "f".repeat(64), 10, 5L);
        return TestDecisions.assemble(state, point, scored, REGISTRY_HASH, "replay");
    }
}
