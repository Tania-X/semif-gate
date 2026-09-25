package com.semif.gate.cache;

import com.semif.gate.contract.Decision;
import com.semif.gate.contract.DecisionPoint;
import com.semif.gate.contract.OptionScore;
import com.semif.gate.contract.Provenance;

import java.util.List;

/**
 * 缓存条目——<b>存回来的不只是结果，还有「这条结果是在什么契约下产生的」</b>。
 *
 * <p>为什么必须连契约指纹一起存：缓存键里含 {@code modelRevision} / {@code backend} /
 * {@code providerId}，这些要等 provider 执行完才知道，所以<b>查找时算不出完整键</b>。
 * 查找只能用「候选键」（state + 判定点 + 注册表 + 选项集），
 * 命中后再用存下来的溯源信息做第二级校验。
 *
 * <p>第二级校验不是锦上添花，它防的是<b>静默的错误判定</b>：
 * 如果注册表被改动（模板或选项描述）而候选键恰好相同，
 * 直接把旧结果返回给调用方，就等于用旧语义回答了一个新问题——而且不会有任何报错。
 *
 * @param decisionId     原判定的缓存键与审计主键
 * @param distribution   完整分布，按 optionId 字典序
 * @param outcome        原判定的结果状态
 * @param degradedReason 降级原因，仅当 outcome 为 DEGRADED 时非空
 * @param provenance     原判定的溯源信息（含 modelRevision / backend / providerId）
 * @param registrySha256 该条目写入时的注册表整体哈希
 * @param optionsSha256  该条目写入时判定点的选项集哈希
 */
public record CachedDecision(
        String decisionId,
        List<OptionScore> distribution,
        Decision.Outcome outcome,
        String degradedReason,
        Provenance provenance,
        String registrySha256,
        String optionsSha256) {

    public CachedDecision {
        if (decisionId == null || decisionId.isBlank()) {
            throw new IllegalArgumentException("decisionId 不能为空");
        }
        if (distribution == null || distribution.isEmpty()) {
            throw new IllegalArgumentException("distribution 不能为空");
        }
        distribution = List.copyOf(distribution);
        if (outcome == null) {
            throw new IllegalArgumentException("outcome 不能为空");
        }
        if (provenance == null) {
            throw new IllegalArgumentException("provenance 不能为空");
        }
        if (registrySha256 == null || registrySha256.isBlank()) {
            throw new IllegalArgumentException("registrySha256 不能为空");
        }
        if (optionsSha256 == null || optionsSha256.isBlank()) {
            throw new IllegalArgumentException("optionsSha256 不能为空");
        }
    }

    /** 从一次判定结果构造缓存条目。 */
    public static CachedDecision from(Decision decision, String registrySha256) {
        return new CachedDecision(
                decision.decisionId(),
                decision.distribution(),
                decision.outcome(),
                decision.degradedReason(),
                decision.provenance(),
                registrySha256,
                decision.provenance().optionsSha256());
    }

    /** 还原成可返回给调用方的判定。 */
    public Decision toDecision(String pointId, int pointVersion) {
        return new Decision(decisionId, pointId, pointVersion, outcome,
                distribution, provenance, degradedReason);
    }

    /**
     * 第二级校验：本条目的契约指纹是否仍然适用于当前判定点与注册表。
     *
     * <p>两项都要比：注册表哈希变了说明模板或选项被改过；
     * 选项集哈希变了说明这个判定点的候选变了。任一不符都必须当作未命中。
     */
    public boolean matchesContract(String currentRegistrySha256, DecisionPoint point) {
        return registrySha256.equals(currentRegistrySha256)
                && optionsSha256.equals(point.optionsSha256());
    }
}
