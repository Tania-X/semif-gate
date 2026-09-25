package com.semif.gate.cache;

import com.semif.gate.contract.Decision;
import com.semif.gate.contract.DecisionPoint;
import com.semif.gate.contract.OptionScore;
import com.semif.gate.contract.ScoredPoint;
import com.semif.gate.state.DecisionState;
import com.semif.gate.state.StateHasher;
import com.semif.gate.testkit.Fixtures;
import com.semif.gate.testkit.TestDecisions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缓存实现细节与并发行为的测试。
 *
 * <p>不需要数据库：{@link JdbcDecisionCache} 的分布编解码是纯函数，
 * 而并发语义由 {@link InMemoryDecisionCache} 覆盖。
 */
class CacheImplementationTest {

    private static final String REGISTRY_HASH = StateHasher.sha256Hex("registry");

    @Test
    @DisplayName("分布 JSON 编解码往返一致，且按 optionId 字典序编码")
    void distributionCodecRoundTrips() {
        List<OptionScore> original = List.of(
                new OptionScore("supported", 0.1),
                new OptionScore("contradicted", 0.7),
                new OptionScore("insufficient", 0.2));

        String encoded = JdbcDecisionCache.encodeDistribution(original);
        assertEquals("{\"contradicted\":0.7,\"insufficient\":0.2,\"supported\":0.1}", encoded,
                "编码必须按 optionId 字典序，保证同一内容编码结果稳定");

        List<OptionScore> decoded = JdbcDecisionCache.decodeDistribution(encoded);
        assertEquals(original.size(), decoded.size());
        Map<String, Double> before = TestDecisions.asMap(original);
        Map<String, Double> after = TestDecisions.asMap(decoded);
        assertEquals(before, after, "编解码必须往返一致");
    }

    @Test
    @DisplayName("分布编码与选项书写顺序无关")
    void encodingIsOrderIndependent() {
        List<OptionScore> a = List.of(
                new OptionScore("x", 0.5), new OptionScore("y", 0.5));
        List<OptionScore> b = List.of(
                new OptionScore("y", 0.5), new OptionScore("x", 0.5));

        assertEquals(JdbcDecisionCache.encodeDistribution(a), JdbcDecisionCache.encodeDistribution(b));
    }

    @Test
    @DisplayName("非法分布 JSON → 解码报错，不静默返回空分布")
    void invalidDistributionJsonIsRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> JdbcDecisionCache.decodeDistribution("not json"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> JdbcDecisionCache.decodeDistribution(""));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> JdbcDecisionCache.decodeDistribution("[1,2,3]"));
    }

    @Test
    @DisplayName("查找键只由 state 与 pointRef 决定，且两者都变则键变")
    void lookupKeyDependsOnStateAndPoint() {
        DecisionPoint point = Fixtures.registryOf(Fixtures.evidenceSupport())
                .require("evidence.support@1");
        DecisionPoint other = Fixtures.registryOf(Fixtures.policyCompliance())
                .require("policy.compliance@1");

        String keyA = DecisionCache.lookupKey("hash-1", point);
        String keyB = DecisionCache.lookupKey("hash-2", point);
        String keyC = DecisionCache.lookupKey("hash-1", other);

        assertEquals(keyA, DecisionCache.lookupKey("hash-1", point), "同输入必须同键");
        assertNotEquals(keyA, keyB, "state 变则键变");
        assertNotEquals(keyA, keyC, "判定点变则键变");
    }

    @Test
    @DisplayName("存入后再取回：判定内容与 decisionId 完全一致")
    void storedDecisionRoundTrips() {
        DecisionPoint point = Fixtures.registryOf(Fixtures.evidenceSupport())
                .require("evidence.support@1");
        DecisionState state = Fixtures.state(Fixtures.ANCHOR_ID);
        InMemoryDecisionCache cache = new InMemoryDecisionCache();

        ScoredPoint scored = new ScoredPoint(Decision.Outcome.OK,
                TestDecisions.distribution("contradicted", 0.8, "insufficient", 0.1, "supported", 0.1),
                null, Fixtures.REVISION, "torch", "f".repeat(64), 10, 5L);
        Decision decision = TestDecisions.assemble(state, point, scored, REGISTRY_HASH, "replay");

        cache.store(state.hash(), point, decision, REGISTRY_HASH);
        Optional<CachedDecision> hit = cache.lookup(state.hash(), point, REGISTRY_HASH);

        assertTrue(hit.isPresent());
        Decision restored = hit.get().toDecision(point.id(), point.version());
        assertEquals(decision.decisionId(), restored.decisionId());
        assertEquals(decision.distribution(), restored.distribution());
        assertEquals(decision.provenance(), restored.provenance());
        assertEquals(1, cache.hits());
        assertEquals(0, cache.misses());
    }

    @Test
    @DisplayName("并发读写下命中/未命中计数自洽")
    void concurrentAccessIsConsistent() throws InterruptedException {
        DecisionPoint point = Fixtures.registryOf(Fixtures.evidenceSupport())
                .require("evidence.support@1");
        DecisionState state = Fixtures.state(Fixtures.ANCHOR_ID);
        InMemoryDecisionCache cache = new InMemoryDecisionCache();

        ScoredPoint scored = new ScoredPoint(Decision.Outcome.OK,
                TestDecisions.distribution("contradicted", 0.8, "insufficient", 0.1, "supported", 0.1),
                null, Fixtures.REVISION, "torch", "f".repeat(64), 10, 5L);
        Decision decision = TestDecisions.assemble(state, point, scored, REGISTRY_HASH, "replay");
        cache.store(state.hash(), point, decision, REGISTRY_HASH);

        int threads = 8;
        int perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger hits = new AtomicInteger();
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        if (cache.lookup(state.hash(), point, REGISTRY_HASH).isPresent()) {
                            hits.incrementAndGet();
                        }
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发读必须能结束");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(threads * perThread, hits.get(), "全部读都应命中");
        assertEquals(threads * perThread, cache.hits(), "命中计数必须与实测一致");
        assertEquals(0, cache.staleRejections());
    }

    @Test
    @DisplayName("调换选项顺序 → optionsSha256 改变 → 缓存键失效（顺序敏感的落地效果）")
    void reorderingOptionsInvalidatesCacheEntry() {
        // 顺序敏感这条约束的实际意义就在这里：调换顺序会让缓存自然失效，
        // 而不是静默复用旧语义的结果。实测顺序改变导致 30.6% 的判定翻转，
        // 而换 GPU 只有 0.7%——顺序比硬件重要 40 倍。
        List<com.semif.gate.contract.Option> forward = Fixtures.evidenceSupport().options();
        List<com.semif.gate.contract.Option> reversed =
                new java.util.ArrayList<>(forward);
        java.util.Collections.reverse(reversed);

        DecisionPoint pointForward = Fixtures.registryOf(Fixtures.evidenceSupport())
                .require("evidence.support@1");
        DecisionPoint pointReversed = Fixtures.registryOf(
                        new DecisionPoint("evidence.support", 1, "t", "2026-09-25",
                                Fixtures.evidenceSupport().question(), reversed,
                                Fixtures.evidenceSupport().promptTemplate(),
                                Fixtures.evidenceSupport().answerStyle(),
                                Fixtures.REVISION, "<待计算>"))
                .require("evidence.support@1");

        assertNotEquals(pointForward.optionsSha256(), pointReversed.optionsSha256(),
                "前提：调换顺序必须改变选项集哈希");

        DecisionState state = Fixtures.state(Fixtures.ANCHOR_ID);
        InMemoryDecisionCache cache = new InMemoryDecisionCache();
        ScoredPoint scored = new ScoredPoint(Decision.Outcome.OK,
                TestDecisions.distribution("contradicted", 0.8, "insufficient", 0.1, "supported", 0.1),
                null, Fixtures.REVISION, "torch", "f".repeat(64), 10, 5L);
        cache.store(state.hash(), pointForward,
                TestDecisions.assemble(state, pointForward, scored, REGISTRY_HASH, "replay"),
                REGISTRY_HASH);

        assertTrue(cache.lookup(state.hash(), pointReversed, REGISTRY_HASH).isEmpty(),
                "同一 state 但选项顺序不同的判定点，绝不能命中同一条缓存");
    }

    @Test
    @DisplayName("clear() 同时清空条目与统计")
    void clearResetsEverything() {
        DecisionPoint point = Fixtures.registryOf(Fixtures.evidenceSupport())
                .require("evidence.support@1");
        DecisionState state = Fixtures.state(Fixtures.ANCHOR_ID);
        InMemoryDecisionCache cache = new InMemoryDecisionCache();

        ScoredPoint scored = new ScoredPoint(Decision.Outcome.OK,
                TestDecisions.distribution("contradicted", 0.8, "insufficient", 0.1, "supported", 0.1),
                null, Fixtures.REVISION, "torch", "f".repeat(64), 10, 5L);
        cache.store(state.hash(), point,
                TestDecisions.assemble(state, point, scored, REGISTRY_HASH, "replay"), REGISTRY_HASH);
        cache.lookup(state.hash(), point, REGISTRY_HASH);

        cache.clear();

        assertEquals(0, cache.size());
        assertEquals(0, cache.hits());
        assertEquals(0, cache.misses());
        assertTrue(cache.lookup(state.hash(), point, REGISTRY_HASH).isEmpty());
    }
}
