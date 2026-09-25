package com.semif.gate.audit;

import com.semif.gate.contract.Band;
import com.semif.gate.contract.Decision;
import com.semif.gate.contract.DecisionPoint;
import com.semif.gate.state.DecisionState;

/**
 * 一条判定审计记录。
 *
 * <p>字段对齐设计文档第 7 节的表。它存在的唯一理由是：
 * <b>线上出了问题，能回答「这条判定当时是怎么做出来的」。</b>
 * 因此每一项都不能省——省掉哪一项，就有一种问题永远回答不了。
 *
 * <p>特别说明 {@link #margin()}：它是「平局舍入」与「真实语义漂移」的分界线。
 * 4090 复现中出现过 144 行里唯一一次翻转，其 margin 恰好为 0（A=B=0.4995）——
 * 只看翻转率会把这种舍入噪声误判成模型行为变化，看到 margin 才能立刻澄清。
 *
 * @param decisionId     缓存键与审计主键
 * @param pointId        判定点 ID
 * @param pointVersion   判定点版本
 * @param stateHash      状态哈希
 * @param stateJson      实际发送的状态 JSON（已规范化、已去敏）
 * @param distribution   完整分布
 * @param argmaxOption   概率最高的选项
 * @param maxProbability 最高概率
 * @param margin         最高与次高之差；0 表示平局
 * @param band           策略档位
 * @param policyVersion  策略版本——策略改了，历史记录仍可解释
 * @param cacheHit       本次是否来自缓存
 * @param decision       原始判定（含完整溯源）
 */
public record DecisionRecord(
        String decisionId,
        String pointId,
        int pointVersion,
        String stateHash,
        String stateJson,
        java.util.List<com.semif.gate.contract.OptionScore> distribution,
        String argmaxOption,
        double maxProbability,
        double margin,
        Band band,
        String policyVersion,
        boolean cacheHit,
        Decision decision) {

    public DecisionRecord {
        requireNonBlank(decisionId, "decisionId");
        requireNonBlank(pointId, "pointId");
        requireNonBlank(stateHash, "stateHash");
        if (decision == null) {
            throw new IllegalArgumentException("decision 不能为空");
        }
        if (band == null) {
            throw new IllegalArgumentException("band 不能为空");
        }
        if (distribution == null || distribution.isEmpty()) {
            throw new IllegalArgumentException("distribution 不能为空");
        }
        distribution = java.util.List.copyOf(distribution);
    }

    /** 便捷构造：从一个判定直接产出记录。 */
    public static DecisionRecord of(Decision decision,
                                    DecisionState state,
                                    Band band,
                                    String policyVersion,
                                    boolean cacheHit) {
        return new DecisionRecord(
                decision.decisionId(),
                decision.pointId(),
                decision.pointVersion(),
                state.hash(),
                state.json(),
                decision.distribution(),
                decision.argmaxOption(),
                decision.maxProbability(),
                decision.margin(),
                band,
                policyVersion,
                cacheHit,
                decision);
    }

    /** 降级原因，仅当判定为 DEGRADED 时非空。 */
    public String degradedReason() {
        return decision.degradedReason();
    }

    /** provider 标识（来自溯源）。 */
    public String providerId() {
        return decision.provenance().providerId();
    }

    /** 模型 revision（来自溯源）。 */
    public String modelRevision() {
        return decision.provenance().modelRevision();
    }

    /** 执行后端（来自溯源）。 */
    public String backend() {
        return decision.provenance().backend();
    }

    /** 实际发出 prompt 的哈希（来自溯源）。 */
    public String promptSha256() {
        return decision.provenance().promptSha256();
    }

    /** 注册表整体哈希（来自溯源）。 */
    public String registrySha256() {
        return decision.provenance().registrySha256();
    }

    /** 输入 token 数（来自溯源）。 */
    public int inputTokens() {
        return decision.provenance().inputTokens();
    }

    /** 判定耗时毫秒（来自溯源）。 */
    public long latencyMs() {
        return decision.provenance().latencyMs();
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }
}
