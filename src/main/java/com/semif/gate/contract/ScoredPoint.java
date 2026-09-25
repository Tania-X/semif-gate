package com.semif.gate.contract;

import java.util.List;
import java.util.TreeMap;

/**
 * Provider 交回的原始判定：<b>只有分数与它自己的执行元数据，不含 decisionId</b>。
 *
 * <h2>为什么需要这个类型（一次真实的契约冲突）</h2>
 * 最初的 {@code DecisionProvider.decide} 直接返回 {@code Map<String, Decision>}，
 * 但 {@link Decision} 强制要求 {@code decisionId} 与 {@link Provenance}，
 * 而 <b>provider 算不出 decisionId</b>——缓存键里含 {@code registrySha256}、
 * 而网关才知道当前注册表的整体哈希；键里还含 provider 自己的
 * {@code modelRevision}/{@code backend}，那要等模型真正加载后才知道。
 *
 * <p>让 provider 去构造完整的 {@code Decision} 就形成了循环依赖：
 * 要算键需要溯源信息，要溯源信息需要先执行，而执行结果又要用键来标识。
 *
 * <p><b>解法：把职责切开。</b>
 * <ul>
 *   <li>provider 只负责「跑出分数」并如实报告自己的执行元数据
 *       （{@code modelRevision} / {@code backend} / {@code promptSha256} / 耗时）；</li>
 *   <li>网关独占 {@code decisionId} 的构造，并补齐 {@code registrySha256} /
 *       {@code optionsSha256}——这两项是<b>契约的属性</b>，不是 provider 的属性。</li>
 * </ul>
 *
 * <p>这样 provider 的实现者不需要理解缓存键的构成，而网关是唯一能产出
 * 可缓存、可审计标识的地方。
 *
 * @param outcome        结果状态；DEGRADED 表示 provider 未能给出有效判定
 * @param distribution   分布，按 optionId 字典序
 * @param degradedReason 降级原因，仅当 outcome 为 DEGRADED 时非空
 * @param modelRevision  provider 实际使用的模型 revision
 * @param backend        provider 的执行后端标记，如 {@code torch} / {@code vllm} / {@code replay}
 * @param promptSha256   provider 实际发出 prompt 的哈希，用于与契约比对
 * @param inputTokens    实际消耗的输入 token 数
 * @param latencyMs      provider 侧的判定耗时（毫秒）
 */
public record ScoredPoint(
        Decision.Outcome outcome,
        List<OptionScore> distribution,
        String degradedReason,
        String modelRevision,
        String backend,
        String promptSha256,
        int inputTokens,
        long latencyMs) {

    public ScoredPoint {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome 不能为空");
        }
        if (distribution == null || distribution.isEmpty()) {
            throw new IllegalArgumentException("distribution 不能为空");
        }
        // 与 Decision 相同的分布纪律：按 optionId 字典序、ID 唯一、概率合法。
        // OptionScore 的构造器已经拒绝了 NaN / Infinity / 越界值，这里只需处理重复与排序。
        TreeMap<String, OptionScore> sorted = new TreeMap<>();
        for (OptionScore score : distribution) {
            if (score == null) {
                throw new IllegalArgumentException("distribution 不能含空元素");
            }
            OptionScore previous = sorted.put(score.optionId(), score);
            if (previous != null) {
                throw new IllegalArgumentException("分布中存在重复选项: " + score.optionId());
            }
        }
        distribution = List.copyOf(sorted.values());

        requireNonBlank(modelRevision, "modelRevision");
        requireNonBlank(backend, "backend");
        requireNonBlank(promptSha256, "promptSha256");
        if (inputTokens < 0) {
            throw new IllegalArgumentException("inputTokens 不能为负: " + inputTokens);
        }
        if (latencyMs < 0) {
            throw new IllegalArgumentException("latencyMs 不能为负: " + latencyMs);
        }
        // 与 Decision 同一条纪律：降级必须给出原因，否则「没判定出来」会被当成
        // 「判定为不可能」——这是把可用性问题升级成正确性问题。
        if (outcome == Decision.Outcome.DEGRADED
                && (degradedReason == null || degradedReason.isBlank())) {
            throw new IllegalArgumentException("降级结果必须给出原因");
        }
    }

    /** 是否为正常判定。 */
    public boolean ok() {
        return outcome == Decision.Outcome.OK;
    }

    /** 构造一个降级结果，用于 provider 表达「我没能给出有效判定」。 */
    public static ScoredPoint degraded(List<OptionScore> conservativeDistribution,
                                       String reason,
                                       String modelRevision,
                                       String backend,
                                       String promptSha256) {
        return new ScoredPoint(Decision.Outcome.DEGRADED, conservativeDistribution, reason,
                modelRevision, backend, promptSha256, 0, 0L);
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }
}
