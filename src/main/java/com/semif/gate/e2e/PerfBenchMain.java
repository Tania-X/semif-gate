package com.semif.gate.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.semif.gate.audit.InMemoryDecisionAudit;
import com.semif.gate.cache.InMemoryDecisionCache;
import com.semif.gate.contract.Action;
import com.semif.gate.contract.AnswerStyle;
import com.semif.gate.contract.Decision;
import com.semif.gate.contract.DecisionPoint;
import com.semif.gate.contract.Option;
import com.semif.gate.gate.DecisionGateway;
import com.semif.gate.gate.GatewayResult;
import com.semif.gate.policy.ThresholdPolicyEngine;
import com.semif.gate.provider.HttpProvider;
import com.semif.gate.registry.DecisionPointRegistry;
import com.semif.gate.registry.RegistryHasher;
import com.semif.gate.state.StateNormalizer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 性能基准：Java 网关 + Python 判定服务 + 真实 Qwen3.5-4B。
 *
 * <p><b>能看到什么</b>：真实端到端延迟、网关自身开销、缓存带来的收益。
 *
 * <p><b>不能看到什么</b>：与 SemIf 已发布吞吐（2.33 / 10.75 / 20.03 decisions/s）的直接对比——
 * 那组数字产自单张 RTX 3090，本机是 RTX 4090 PLUS。跨硬件比吞吐没有意义，
 * 本基准的数据只作为本机自洽基线。
 *
 * <p>用法：{@code java … PerfBenchMain <baseUrl> <fixture.jsonl>[,<fixture2.jsonl>] [limit]}
 */
public final class PerfBenchMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DIRECT_SYSTEM =
            "Apply the supplied criterion to the supplied evidence. Choose exactly one listed option. "
            + "Respond with only its uppercase letter, with no explanation or reasoning.";

    private static final String PROMPT_TEMPLATE =
            "<|im_start|>system\n" + DIRECT_SYSTEM + "<|im_end|>\n"
            + "<|im_start|>user\n"
            + "{\"evidence\": {{state}}, \"criterion\": {{question}}, \"options\": {{options}}}"
            + "<|im_end|>\n"
            + "<|im_start|>assistant\n<think>\n\n</think>\n\n";

    private static final String MODEL_REVISION = "851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a";

    private PerfBenchMain() {
    }

    /** 一条真实用例。 */
    private record Case(String id, String criterion, String evidence, List<String> optionOrder) {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法: PerfBenchMain <baseUrl> <fixture.jsonl> <registry.json> <mapping.json> [limit] [samePointFixture]");
            System.exit(2);
        }
        String baseUrl = args[0];
        int limit = args.length > 4 ? Integer.parseInt(args[4]) : 240;
        String registryFile = args.length > 2 ? args[2] : "perf-registry.json";
        String mappingFile = args.length > 3 ? args[3] : "perf-mapping.json";

        List<Case> cases = new ArrayList<>();
        for (String file : args[1].split(",")) {
            cases.addAll(loadCases(Path.of(file.trim())));
        }
        if (cases.size() > limit) {
            cases = cases.subList(0, limit);
        }

        // 判定点与「行 → 判定点」映射都从文件读入，由同一份数据生成（见 semif-service/registry）。
        // 不让两侧各自推导 ID：判定点身份 = (判据, 选项顺序)，任何推导差异都会让哈希校验失败。
        List<DecisionPoint> points = loadPoints(Path.of(registryFile));
        Map<String, String> rowToPoint = loadMapping(Path.of(mappingFile));
        Map<String, DecisionPoint> pointById = new LinkedHashMap<>();
        for (DecisionPoint dp : points) {
            pointById.put(dp.id(), dp);
        }
        DecisionPointRegistry registry = DecisionPointRegistry.of(points);
        System.out.println("=== 性能基准：Java 网关 + Python 服务 + Qwen3.5-4B (bf16) ===");
        System.out.println("服务地址    : " + baseUrl);
        System.out.println("用例数      : " + cases.size());
        System.out.println("判定点数    : " + registry.size() + "（按判据去重）");
        System.out.println();

        StateNormalizer normalizer =
                new StateNormalizer(Set.of("evidence", "criterion"), 8192);
        InMemoryDecisionCache cache = new InMemoryDecisionCache();
        InMemoryDecisionAudit audit = new InMemoryDecisionAudit();
        ThresholdPolicyEngine policy =
                new ThresholdPolicyEngine(0.90, 0.60, "perf-v1", Action.SAFE_DEFAULT);
        HttpProvider provider = new HttpProvider(baseUrl, Duration.ofMinutes(5));
        DecisionGateway gateway =
                new DecisionGateway(registry, normalizer, cache, provider, policy, audit);

        // 预热：一次真实调用，排除首次 CUDA 上下文与内核编译的影响
        System.out.println("--- 预热（1 次真实判定，不计入统计）---");
        long warm = System.currentTimeMillis();
        gateway.decide(stateOf(cases.get(0)), List.of(refOf(pointById, rowToPoint, cases.get(0))));
        System.out.println("预热耗时    : " + (System.currentTimeMillis() - warm) + " ms");
        cache.clear();
        audit.clear();
        System.out.println();

        // ---- 第 1 轮：冷启动，每个 state 都未命中 ----
        Round cold = runRound(gateway, cases, pointById, rowToPoint, "第 1 轮：全冷（每行都未命中缓存）");

        // ---- 第 2 轮：用【已经出现过的 state】再问不同的判据 ----
        // 实测数据结构：252 行 = 108 个判据 × 144 个不同 state，每个 state 只出现一次。
        // 所以「重复跑同一列表」测不出缓存收益。真实流量的形状是
        // 「同一份文档被反复问到」，这里用循环取前面的 state 来模拟。
        List<Case> reuse = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            reuse.add(cases.get(i % Math.max(1, cases.size() / 2)));
        }
        Round warmRound = runRound(gateway, reuse, pointById, rowToPoint,
                "第 2 轮：复用已有 state（模拟同一文档被反复询问）");

        // ---- 第 3 轮：混合（一半新 state，一半复用）----
        List<Case> mixed = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            mixed.add(i % 2 == 0 ? cases.get(i) : cases.get(i % Math.max(1, cases.size() / 2)));
        }
        Round mixedRound = runRound(gateway, mixed, pointById, rowToPoint,
                "第 3 轮：混合（约 50% 复用已有 state）");

        // ---- 第 4 轮：同判定点、新 state（重复 5 遍，展示缓存升温）----
        if (args.length > 5) {
            List<Case> samePoint = loadCases(Path.of(args[5]));
            System.out.println("--- 第 4 轮：同判定点/新 state，连续 5 遍（" + samePoint.size() + " 行）---");
            for (int pass = 1; pass <= 5; pass++) {
                Round r = runRound(gateway, samePoint, pointById, rowToPoint,
                        "  第 " + pass + " 遍");
                System.out.printf("    命中 %d / 未命中 %d | %.2f decisions/s | p50 %d ms%n",
                        r.hits(), r.misses(),
                        r.count() * 1000.0 / r.elapsedMs(),
                        r.latencies()[r.latencies().length / 2]);
            }
            System.out.println();
        }

        // ---- 汇总 ----
        System.out.println("=== 汇总 ===");
        System.out.printf("%-34s %10s %10s %10s %10s %14s%n",
                "轮次", "总耗时s", "decisions/s", "p50 ms", "p95 ms", "providerCalls");
        printRound("第 1 轮 全冷", cold);
        printRound("第 2 轮 全热（缓存）", warmRound);
        printRound("第 3 轮 混合", mixedRound);
        System.out.println();

        double coldPerDecision = cold.elapsedMs() / (double) cold.count();
        double warmPerDecision = warmRound.elapsedMs() / (double) warmRound.count();
        System.out.printf("缓存带来的单次决策加速: %.2f ms → %.2f ms（%.0f×）%n",
                coldPerDecision, warmPerDecision, coldPerDecision / Math.max(warmPerDecision, 0.001));
        System.out.printf("第 3 轮命中率: %.1f%%（%d 命中 / %d 未命中）%n",
                100.0 * mixedRound.hits() / mixedRound.count(),
                mixedRound.hits(), mixedRound.misses());
        System.out.println();
        System.out.println("注：SemIf 已发布吞吐（2.33/10.75/20.03 decisions/s）产自 RTX 3090，");
        System.out.println("    本机是 RTX 4090 PLUS，跨硬件不可直接比较。以上仅为本机自洽基线。");
    }

    private static String refOf(Map<String, DecisionPoint> pointById,
                                Map<String, String> rowToPoint, Case c) {
        String pointId = rowToPoint.get(c.id());
        if (pointId == null) {
            throw new IllegalStateException("映射里没有这一行: " + c.id());
        }
        DecisionPoint point = pointById.get(pointId);
        if (point == null) {
            throw new IllegalStateException("注册表里没有该判定点: " + pointId);
        }
        return point.ref();
    }

    /** 从注册表 JSON 读取判定点（选项顺序即数组顺序，是语义的一部分）。 */
    private static List<DecisionPoint> loadPoints(Path file) throws Exception {
        List<DecisionPoint> points = new ArrayList<>();
        for (JsonNode node : MAPPER.readTree(Files.readString(file))) {
            List<Option> options = new ArrayList<>();
            node.get("options").forEach(o ->
                    options.add(new Option(o.get("id").asText(), o.get("description").asText())));
            points.add(new DecisionPoint(
                    node.get("id").asText(), 1, "perf-bench", "2026-09-25",
                    node.get("question").asText(), options,
                    PROMPT_TEMPLATE, AnswerStyle.LETTER, MODEL_REVISION,
                    RegistryHasher.optionsSha256(options)));
        }
        return points;
    }

    private static Map<String, String> loadMapping(Path file) throws Exception {
        Map<String, String> mapping = new LinkedHashMap<>();
        MAPPER.readTree(Files.readString(file)).fields()
                .forEachRemaining(e -> mapping.put(e.getKey(), e.getValue().asText()));
        return mapping;
    }


    /**
     * 构造 state。
     *
     * <p><b>不传 {@code option_order}</b>：Java 侧 {@code PromptRenderer} 按判定点自带的能力序
     * 渲染，并不读 state 里的顺序。若这里传了，服务端会按它渲染，两边顺序不一致 →
     * 哈希校验失败（实测 409）。
     *
     * <p>顺序属于**判定点身份**（判定点 = 判据 + 选项集 + 选项顺序 + revision），
     * 基准里 3 个判定点各自固定一个顺序，三个族内部一致，因此可直接比较。
     */
    private static Map<String, Object> stateOf(Case c) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("criterion", c.criterion());
        state.put("evidence", c.evidence());
        return state;
    }

    private static List<Option> optionSetOf(Case c) {
        // 描述由注册表提供；基准只关心顺序与 ID，描述取自固定的 SemIf 文本
        Map<String, String> descriptions = Map.of(
                "supported", "The evidence establishes the claim",
                "insufficient", "The evidence does not establish either",
                "contradicted", "The evidence establishes the opposite",
                "permitted", "The stated rule permits the action",
                "prohibited", "The stated rule prohibits the action",
                "A", "Candidate A",
                "B", "Candidate B");
        List<Option> options = new ArrayList<>();
        for (String id : c.optionOrder()) {
            String description = descriptions.get(id);
            if (description == null) {
                throw new IllegalStateException("未知选项 ID（需要补充描述映射）: " + id);
            }
            options.add(new Option(id, description));
        }
        return options;
    }

    private record Round(String label, int count, long elapsedMs, long[] latencies,
                         int hits, int misses, int providerCalls) {
    }

    private static Round runRound(DecisionGateway gateway, List<Case> cases,
                                  Map<String, DecisionPoint> pointById,
                                  Map<String, String> rowToPoint, String label) {
        System.out.println("--- " + label + " ---");
        long[] latencies = new long[cases.size()];
        int hits = 0;
        int misses = 0;
        int providerCalls = 0;
        long started = System.nanoTime();
        for (int i = 0; i < cases.size(); i++) {
            Case c = cases.get(i);
            long t0 = System.nanoTime();
            GatewayResult result = gateway.decide(stateOf(c), List.of(refOf(pointById, rowToPoint, c)));
            latencies[i] = (System.nanoTime() - t0) / 1_000_000;
            hits += result.cacheHits();
            misses += result.cacheMisses();
            providerCalls += result.providerCalls();
            if (result.cacheMisses() > 0) {
                Decision decision = result.decision(rowToPoint.get(c.id()));
                if (!decision.ok()) {
                    System.out.println("  ⚠️ 第 " + i + " 行降级: " + decision.degradedReason());
                    return new Round(label, i + 1, (System.nanoTime() - started) / 1_000_000,
                            Arrays.copyOf(latencies, i + 1), hits, misses, providerCalls);
                }
            }
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        Round round = new Round(label, cases.size(), elapsedMs, latencies, hits, misses, providerCalls);
        System.out.printf("总耗时 %.1f s | %.2f decisions/s | 命中 %d / 未命中 %d | providerCalls %d%n",
                elapsedMs / 1000.0, cases.size() * 1000.0 / elapsedMs, hits, misses, providerCalls);
        System.out.println();
        return round;
    }

    private static void printRound(String label, Round round) {
        long[] sorted = round.latencies().clone();
        Arrays.sort(sorted);
        long p50 = sorted[(int) (sorted.length * 0.50)];
        long p95 = sorted[Math.min(sorted.length - 1, (int) (sorted.length * 0.95))];
        System.out.printf("%-34s %10.1f %10.2f %10d %10d %14d%n",
                label, round.elapsedMs() / 1000.0,
                round.count() * 1000.0 / round.elapsedMs(), p50, p95, round.providerCalls());
    }

    private static List<Case> loadCases(Path file) throws Exception {
        List<Case> cases = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode row = MAPPER.readTree(line);
            List<String> order = new ArrayList<>();
            row.get("options").forEach(o -> order.add(o.get("id").asText()));
            cases.add(new Case(
                    row.get("id").asText(),
                    row.get("question").asText(),
                    row.get("state").asText(),
                    order));
        }
        return cases;
    }
}
