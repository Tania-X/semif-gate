package com.semif.gate.provider;

import com.semif.gate.contract.Decision;
import com.semif.gate.contract.DecisionPoint;
import com.semif.gate.contract.OptionScore;
import com.semif.gate.contract.ScoredPoint;
import com.semif.gate.state.DecisionState;
import com.semif.gate.testkit.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ReplayProvider} 的契约测试——全部基于<b>真实 SemIf 输出</b>。
 *
 * <p>这些断言的价值在于：它们验证的是「程序能否正确读懂真实数据」，
 * 而不是「程序能否读懂我自己编的数据」。
 */
class ReplayProviderTest {

    private static final ReplayProvider PROVIDER = Fixtures.replayProvider();

    private static ScoredPoint scored(String stateId, DecisionPoint point) {
        return PROVIDER.decide(Fixtures.state(stateId), List.of(point)).get(point.id());
    }

    private static Map<String, Double> asMap(List<OptionScore> distribution) {
        return com.semif.gate.testkit.TestDecisions.asMap(distribution);
    }

    @Test
    @DisplayName("providerId 固定为 replay——它进缓存键，绝不复用生产标识")
    void providerIdIsReplay() {
        assertEquals("replay", PROVIDER.providerId());
    }

    @Test
    @DisplayName("映射锚点：option_ids 顺序既非字典序也非概率序，仍须正确配对")
    void mapsAnchorRecordCorrectly() {
        // 真实记录 14790d6d50043b03420c 的 option_ids 书写顺序是
        // ["contradicted","insufficient","supported"]，按 NOTES 的说明既不是字典序
        // 也不是概率序——按下标搬运会把概率安到错误选项上，而且不会报错。
        ScoredPoint point = scored(Fixtures.ANCHOR_ID, Fixtures.evidenceSupport());

        assertTrue(point.ok(), () -> "应当映射成功，实际: " + point.degradedReason());
        Map<String, Double> distribution = asMap(point.distribution());
        assertEquals(0.9874, distribution.get("contradicted"), 1e-4);
        assertEquals(0.0124, distribution.get("insufficient"), 1e-4);
        assertEquals(0.0002, distribution.get("supported"), 1e-4);
    }

    @Test
    @DisplayName("分布按 optionId 字典序存储，与 fixture 书写顺序无关")
    void distributionIsSortedByOptionId() {
        ScoredPoint point = scored(Fixtures.ANCHOR_ID, Fixtures.evidenceSupport());

        List<String> ids = point.distribution().stream().map(OptionScore::optionId).toList();
        List<String> sorted = new ArrayList<>(ids);
        sorted.sort(String::compareTo);
        assertEquals(sorted, ids, "必须按 optionId 字典序");
        assertEquals(List.of("contradicted", "insufficient", "supported"), ids);
    }

    @Test
    @DisplayName("打乱 fixture 数组顺序 → 映射结果不变（顺序无关）")
    void mappingIsOrderIndependent() throws Exception {
        // 把真实记录的两个数组同时打乱，重新写一份临时 fixture，结果必须逐字节相同。
        java.nio.file.Path original = java.nio.file.Path.of(Fixtures.REPLAY_FIXTURE);
        java.nio.file.Path shuffled = java.nio.file.Files.createTempFile("replay-shuffled", ".jsonl");
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            List<String> lines = new ArrayList<>();
            for (String line : java.nio.file.Files.readAllLines(original)) {
                if (line.isBlank()) {
                    continue;
                }
                com.fasterxml.jackson.databind.node.ObjectNode node =
                        (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(line);
                com.fasterxml.jackson.databind.JsonNode ids = node.get("option_ids");
                com.fasterxml.jackson.databind.JsonNode probs = node.get("probabilities");
                List<com.fasterxml.jackson.databind.JsonNode> pairs = new ArrayList<>();
                for (int i = 0; i < ids.size(); i++) {
                    com.fasterxml.jackson.databind.node.ArrayNode pair = mapper.createArrayNode();
                    pair.add(ids.get(i));
                    pair.add(probs.get(i));
                    pairs.add(pair);
                }
                java.util.Collections.reverse(pairs);
                com.fasterxml.jackson.databind.node.ArrayNode newIds = mapper.createArrayNode();
                com.fasterxml.jackson.databind.node.ArrayNode newProbs = mapper.createArrayNode();
                for (com.fasterxml.jackson.databind.JsonNode pair : pairs) {
                    newIds.add(pair.get(0));
                    newProbs.add(pair.get(1));
                }
                node.set("option_ids", newIds);
                node.set("probabilities", newProbs);
                lines.add(mapper.writeValueAsString(node));
            }
            java.nio.file.Files.write(shuffled, lines);

            ReplayProvider shuffledProvider = ReplayProvider.fromJsonl(shuffled);
            ScoredPoint fromOriginal = scored(Fixtures.ANCHOR_ID, Fixtures.evidenceSupport());
            ScoredPoint fromShuffled = shuffledProvider
                    .decide(Fixtures.state(Fixtures.ANCHOR_ID), List.of(Fixtures.evidenceSupport()))
                    .get("evidence.support");

            assertEquals(asMap(fromOriginal.distribution()), asMap(fromShuffled.distribution()),
                    "数组顺序打乱后分布必须完全一致");
        } finally {
            java.nio.file.Files.deleteIfExists(shuffled);
        }
    }

    @Test
    @DisplayName("fixture 中真实的精确平局：margin 为 0，且字典序与概率降序不同向")
    void realTieHasZeroMargin() {
        // f2b4ec4930322fe45118：insufficient = prohibited = 0.4750
        ScoredPoint point = scored(Fixtures.TIE_ID, Fixtures.policyCompliance());

        assertTrue(point.ok(), () -> "应当映射成功，实际: " + point.degradedReason());
        Map<String, Double> distribution = asMap(point.distribution());
        assertEquals(0.4750, distribution.get("insufficient"), 1e-4);
        assertEquals(0.4750, distribution.get("prohibited"), 1e-4);
        assertEquals(0.4750 - 0.4750,
                Math.abs(distribution.get("insufficient") - distribution.get("prohibited")), 1e-9,
                "两个最高分相等——这是真正的平局");
        // 字典序是 insufficient < permitted < prohibited，而最高两个恰好是字典序相邻的
        // insufficient 与 prohibited。这个形状能同时验证「取真正 top-2」和「平局可识别」。
        assertEquals(0.0501, distribution.get("permitted"), 1e-4);
    }

    @Test
    @DisplayName("backend 缺失时取默认值 torch，revision 与 prompt 哈希来自记录")
    void provenanceComesFromRecord() {
        ScoredPoint point = scored(Fixtures.ANCHOR_ID, Fixtures.evidenceSupport());

        assertEquals("torch", point.backend(), "fixture 没有 backend 字段，按规范取 torch");
        assertEquals(Fixtures.REVISION, point.modelRevision());
        assertEquals(64, point.promptSha256().length());
        assertTrue(point.inputTokens() > 0);
    }

    @Test
    @DisplayName("选项集与判定点不一致 → 降级并说明差异，绝不误配")
    void mismatchedOptionSetIsDegraded() {
        // candidate.select 的选项是 A/B/insufficient，用它去读 evidence 记录
        ScoredPoint point = scored(Fixtures.ANCHOR_ID, Fixtures.candidateSelection());

        assertFalse(point.ok(), "选项集不一致时不能给出 OK 判定");
        assertTrue(point.degradedReason().contains("选项集与判定点"),
                () -> "原因应说明选项集不一致: " + point.degradedReason());
        assertTrue(point.degradedReason().contains("candidate.select"));
    }

    @Test
    @DisplayName("fixture 里没有该 id → 降级，绝不静默编造一个分布")
    void unknownIdIsDegraded() {
        ScoredPoint point = scored("does-not-exist-in-fixture", Fixtures.evidenceSupport());

        assertFalse(point.ok());
        assertEquals(Decision.Outcome.DEGRADED, point.outcome());
        assertTrue(point.degradedReason().contains("fixture 中没有记录"),
                () -> point.degradedReason());
    }

    @Test
    @DisplayName("state 缺少标识字段 → 降级并说明，不猜测")
    void missingStateKeyIsDegraded() {
        // 白名单里没有 id，规范化后 state 里就没有这个字段
        com.semif.gate.state.DecisionState state = new com.semif.gate.state.StateNormalizer(
                java.util.Set.of("service")).normalize(Map.of("service", "checkout"));

        ScoredPoint point = PROVIDER.decide(state, List.of(Fixtures.evidenceSupport()))
                .get("evidence.support");

        assertFalse(point.ok());
        assertTrue(point.degradedReason().contains("缺少回放标识字段"), point.degradedReason());
    }

    @Test
    @DisplayName("降级分布按字典序均分——不制造任何高置信外观")
    void degradedDistributionIsUniform() {
        ScoredPoint point = scored("does-not-exist-in-fixture", Fixtures.evidenceSupport());

        double expected = 1.0 / 3.0;
        point.distribution().forEach(score ->
                assertEquals(expected, score.probability(), 1e-9));
    }

    @Test
    @DisplayName("多个判定点一次调用全部返回")
    void multiplePointsAreAllReturned() {
        Map<String, ScoredPoint> scored = PROVIDER.decide(Fixtures.state(Fixtures.ANCHOR_ID),
                List.of(Fixtures.evidenceSupport(), Fixtures.policyCompliance()));

        assertEquals(2, scored.size());
        assertTrue(scored.get("evidence.support").ok());
        // policyCompliance 的选项集与 evidence 记录不符 → 降级
        assertFalse(scored.get("policy.compliance").ok());
    }

    @Test
    @DisplayName("不存在的 fixture 路径 → 构造时即报错")
    void missingFixtureFileIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ReplayProvider.fromJsonl(java.nio.file.Path.of("no-such-fixture.jsonl")));
    }

    @Test
    @DisplayName("fixture 记录数正确")
    void fixtureSizeIsAsExpected() {
        assertEquals(6, PROVIDER.size(), "测试 fixture 含 6 条真实记录");
    }
}
