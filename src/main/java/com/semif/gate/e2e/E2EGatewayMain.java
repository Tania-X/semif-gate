package com.semif.gate.e2e;

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
import com.semif.gate.registry.PromptRenderer;
import com.semif.gate.registry.RegistryHasher;
import com.semif.gate.state.StateNormalizer;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 端到端联调：Java 网关 → 真实 Python 判定服务 → 真实 Qwen3.5-4B。
 *
 * <p>用的是**真实 SemIf 用例**（authored144 的 {@code a3f18f3a63d45345942b}），
 * 期望结果与 SemIf 在 GPU 上的已发布预测逐位一致：
 * <pre>
 *   supported = 0.009690, insufficient = 0.872262, contradicted = 0.118048
 *   prompt_sha256 = 3cc9e3d1e4e07885afb7652e9c5f92a5b28168362a3d8e7f6e32e5189a4fccde
 * </pre>
 *
 * <p><b>两道防线在本程序里都会被验证</b>：
 * <ol>
 *   <li>本地渲染的 prompt 哈希必须等于已发布值——证明 Java 模板复刻正确；</li>
 *   <li>Python 侧会独立校验同一哈希——不一致直接 409，而不是返回错位的分布。</li>
 * </ol>
 *
 * <p>用法：{@code java -cp … com.semif.gate.e2e.E2EGatewayMain http://127.0.0.1:18080}
 */
public final class E2EGatewayMain {

    /** SemIf {@code core.py} 的 DIRECT_SYSTEM 常量，逐字复刻。 */
    private static final String DIRECT_SYSTEM =
            "Apply the supplied criterion to the supplied evidence. Choose exactly one listed option. "
            + "Respond with only its uppercase letter, with no explanation or reasoning.";

    /**
     * SemIf 的 Qwen chat 外框 + user 正文。
     *
     * <p>user 正文是一个 JSON 对象；{@code {{state}}} 位置必须是**带引号的 JSON 字符串**，
     * 因此 state 以 {@code "evidence"} 为键、证据原文为值。
     * 这样渲染出的字节序列与 SemIf 的 {@code json.dumps(payload, ensure_ascii=False)} 一致。
     */
    private static final String PROMPT_TEMPLATE =
            "<|im_start|>system\n" + DIRECT_SYSTEM + "<|im_end|>\n"
            + "<|im_start|>user\n"
            + "{\"evidence\": {{state}}, \"criterion\": {{question}}, \"options\": {{options}}}"
            + "<|im_end|>\n"
            + "<|im_start|>assistant\n<think>\n\n</think>\n\n";

    private static final String EVIDENCE =
            "The optician ordered replacement lenses. "
            + "The workshop confirms they have not yet been fitted to the customer's glasses.";

    private static final String QUESTION = "Assess the claim: the replacement lenses have been fitted.";

    /** 已发布预测（SemIf 在 GPU 上的实跑结果）。 */
    private static final Map<String, Double> EXPECTED = Map.of(
            "supported", 0.009690,
            "insufficient", 0.872262,
            "contradicted", 0.118048);

    private static final String EXPECTED_PROMPT_HASH =
            "3cc9e3d1e4e07885afb7652e9c5f92a5b28168362a3d8e7f6e32e5189a4fccde";

    private static final String MODEL_REVISION = "851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a";

    private E2EGatewayMain() {
    }

    public static void main(String[] args) {
        String baseUrl = args.length > 0 ? args[0] : "http://127.0.0.1:18080";
        System.out.println("=== Java 网关端到端联调 ===");
        System.out.println("判定服务: " + baseUrl);
        System.out.println();

        // ---- 判定点：选项顺序必须与 SemIf 该行的行内顺序一致 ----
        // 顺序是语义的一部分：实测按行内顺序渲染可命中已发布哈希，字典序 0/144。
        List<Option> options = List.of(
                new Option("supported", "The evidence establishes the claim"),
                new Option("insufficient", "The evidence does not establish either"),
                new Option("contradicted", "The evidence establishes the opposite"));

        DecisionPoint point = new DecisionPoint(
                "semif.evidence_interpretation", 1, "semif-gate", "2026-09-25",
                QUESTION, options, PROMPT_TEMPLATE, AnswerStyle.LETTER,
                MODEL_REVISION, RegistryHasher.optionsSha256(options));

        DecisionPointRegistry registry = DecisionPointRegistry.of(List.of(point));
        System.out.println("判定点        : " + point.ref());

        // ---- 防线 1：本地渲染必须复现已发布 prompt ----
        String rendered = PromptRenderer.renderWithField(point, stateJsonForProbe(), "evidence");
        String localHash = RegistryHasher.promptSha256(rendered);
        System.out.println("本地渲染哈希  : " + localHash);
        if (!localHash.equals(EXPECTED_PROMPT_HASH)) {
            System.out.println("❌ 本地渲染与已发布 prompt 不一致——模板不对，测试无意义");
            System.out.println("   期望: " + EXPECTED_PROMPT_HASH);
            System.out.println("   --- 本地渲染结果（JSON 转义）---");
            System.out.println(quote(rendered));
            System.exit(1);
        }
        System.out.println("✅ 防线 1：本地渲染复现了已发布 prompt 哈希");
        System.out.println();

        // ---- 组装网关 ----
        StateNormalizer normalizer = new StateNormalizer(
                Set.of("evidence", "criterion", "option_order"), 8192);
        InMemoryDecisionCache cache = new InMemoryDecisionCache();
        InMemoryDecisionAudit audit = new InMemoryDecisionAudit();
        ThresholdPolicyEngine policy = new ThresholdPolicyEngine(
                0.90, 0.60, "e2e-v1", Action.SAFE_DEFAULT);
        HttpProvider provider = new HttpProvider(baseUrl, Duration.ofMinutes(5));

        DecisionGateway gateway = new DecisionGateway(
                registry, normalizer, cache, provider, policy, audit);

        // state 同时携带 criterion：Python 侧从 state 取判据（逐行），
        // Java 侧从判定点取（冻结）。两者必须相等——这正是「判定点身份」的一部分。
        Map<String, Object> rawState = new LinkedHashMap<>();
        rawState.put("criterion", QUESTION);
        rawState.put("evidence", EVIDENCE);
        // option_order 必须传：它决定答案字母的分配，进而决定 prompt 与模型行为。
        // 实测按行内顺序渲染可命中已发布哈希，字典序 0/144。
        // 它进 state ⇒ 自动进 stateHash ⇒ 自动进缓存键。
        rawState.put("option_order", List.of("supported", "insufficient", "contradicted"));

        // ---- 第一次：穿透到真实模型 ----
        System.out.println("--- 第一次调用（应穿透到真实模型）---");
        long t1 = System.currentTimeMillis();
        GatewayResult first = gateway.decide(rawState, List.of(point.ref()));
        long elapsed1 = System.currentTimeMillis() - t1;

        Decision decision = first.decision(point.id());
        System.out.println("outcome       : " + decision.outcome());
        if (!decision.ok()) {
            System.out.println("❌ 判定降级: " + decision.degradedReason());
            System.exit(1);
        }
        System.out.println("promptHash    : " + decision.provenance().promptSha256());
        System.out.println("modelRevision : " + decision.provenance().modelRevision());
        System.out.println("backend       : " + decision.provenance().backend());
        System.out.println("argmax        : " + decision.argmaxOption());
        System.out.printf("margin        : %.6f%n", decision.margin());
        System.out.println("cacheHits/Misses/providerCalls: "
                + first.cacheHits() + "/" + first.cacheMisses() + "/" + first.providerCalls());
        System.out.println("端到端耗时    : " + elapsed1 + " ms");
        System.out.println("分布:");
        decision.distribution().forEach(score -> System.out.printf(
                "  %-14s %.6f%n", score.optionId(), score.probability()));

        // ---- 与已发布预测逐位对比 ----
        System.out.println();
        System.out.println("--- 与 SemIf 已发布预测对比 ---");
        boolean allMatch = decision.distribution().size() == EXPECTED.size();
        for (var score : decision.distribution()) {
            Double expected = EXPECTED.get(score.optionId());
            double delta = expected == null ? Double.NaN : Math.abs(score.probability() - expected);
            boolean ok = expected != null && delta < 1e-5;
            allMatch &= ok;
            System.out.printf("  %-14s 网关=%.6f  已发布=%.6f  Δ=%.2e  %s%n",
                    score.optionId(), score.probability(),
                    expected == null ? Double.NaN : expected, delta, ok ? "✅" : "❌");
        }

        // ---- 第二次：必须命中缓存，零 provider 调用 ----
        System.out.println();
        System.out.println("--- 第二次调用（应命中缓存，零 provider 调用）---");
        long t2 = System.currentTimeMillis();
        GatewayResult second = gateway.decide(rawState, List.of(point.ref()));
        long elapsed2 = System.currentTimeMillis() - t2;
        System.out.println("cacheHits/Misses/providerCalls: "
                + second.cacheHits() + "/" + second.cacheMisses() + "/" + second.providerCalls());
        System.out.println("耗时          : " + elapsed2 + " ms（首次 " + elapsed1 + " ms）");
        boolean cacheWorks = second.cacheHits() == 1 && second.providerCalls() == 0;
        System.out.println(cacheWorks ? "✅ 缓存命中且未触碰 provider"
                : "❌ 缓存未按预期工作");

        // ---- 审计记录 ----
        System.out.println();
        System.out.println("--- 审计记录 ---");
        var records = audit.records();
        System.out.println("记录数        : " + records.size());
        boolean auditOk = records.size() == 2;
        for (var record : records) {
            System.out.printf("  point=%s band=%s margin=%.6f cacheHit=%s revision=%s prompt=%s…%n",
                    record.pointId(), record.band(), record.margin(), record.cacheHit(),
                    record.decision().provenance().modelRevision().substring(0, 12),
                    record.decision().provenance().promptSha256().substring(0, 12));
        }

        System.out.println();
        boolean passed = allMatch && cacheWorks && auditOk;
        System.out.println(passed
                ? "✅ 端到端通过：输出与已发布预测逐位一致，缓存与审计均正常"
                : "❌ 端到端失败");
        if (!passed) {
            System.exit(1);
        }
    }

    /** 构造与网关运行时相同的 state JSON，用于本地渲染校验。 */
    private static String stateJsonForProbe() {
        return "{\"criterion\":" + quote(QUESTION) + ",\"evidence\":" + quote(EVIDENCE)
                + ",\"option_order\":[\"supported\",\"insufficient\",\"contradicted\"]}";
    }

    /**
     * 与 SemIf 的 {@code json.dumps(..., ensure_ascii=False)} 行为一致的字符串转义。
     *
     * <p>Python 的 json 默认不转义非 ASCII，只转义 {@code " \ } 与控制字符——
     * 这正是这里实现的口径。任何偏差都会让 prompt 哈希对不上。
     */
    private static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
