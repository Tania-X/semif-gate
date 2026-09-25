package com.semif.gate.contract;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 一次判定的结果。
 *
 * <p><b>永远返回完整分布，永远可复现。</b>调用方拿到的不是「一个被选中的答案」，
 * 而是全部选项上的概率——这样策略层才能判断「是否处于中间带」，
 * 评测层才能计算 Brier / ECE 这类概率质量指标。
 *
 * <p>{@link Outcome#DEGRADED} 是显式的一等状态：provider 失败时不能假装返回了
 * 一个概率为 0 的判定，否则「没判定出来」会被当成「判定为不可能」。
 *
 * @param decisionId   缓存键与审计主键
 * @param pointId      判定点 ID
 * @param pointVersion 判定点版本
 * @param outcome      结果状态
 * @param distribution 完整分布，按 optionId 字典序
 * @param provenance   溯源信息
 * @param degradedReason 降级原因，仅当 outcome 为 DEGRADED 时非空
 */
public record Decision(
        String decisionId,
        String pointId,
        int pointVersion,
        Outcome outcome,
        List<OptionScore> distribution,
        Provenance provenance,
        String degradedReason) {

    /** 判定结果状态。 */
    public enum Outcome {
        /** 正常判定。 */
        OK,
        /** 降级：provider 失败或契约不满足，分布为保守默认值。 */
        DEGRADED
    }

    public Decision {
        if (decisionId == null || decisionId.isBlank()) {
            throw new IllegalArgumentException("decisionId 不能为空");
        }
        if (pointId == null || pointId.isBlank()) {
            throw new IllegalArgumentException("pointId 不能为空");
        }
        if (outcome == null) {
            throw new IllegalArgumentException("outcome 不能为空");
        }
        if (distribution == null || distribution.isEmpty()) {
            throw new IllegalArgumentException("distribution 不能为空: " + decisionId);
        }
        // 按 optionId 字典序排列，保证任何来源的分布都可比
        TreeMap<String, OptionScore> sorted = new TreeMap<>();
        for (OptionScore score : distribution) {
            OptionScore previous = sorted.put(score.optionId(), score);
            if (previous != null) {
                throw new IllegalArgumentException("分布中存在重复选项: " + score.optionId());
            }
        }
        distribution = List.copyOf(sorted.values());
        if (provenance == null) {
            throw new IllegalArgumentException("provenance 不能为空: " + decisionId);
        }
        if (outcome == Outcome.DEGRADED && (degradedReason == null || degradedReason.isBlank())) {
            throw new IllegalArgumentException("降级结果必须给出原因: " + decisionId);
        }
    }

    /** 是否为正常判定。 */
    public boolean ok() {
        return outcome == Outcome.OK;
    }

    /** 便捷读取某一选项的概率；选项不存在时返回空。 */
    public Optional<Double> probabilityOf(String optionId) {
        return distribution.stream()
                .filter(score -> score.optionId().equals(optionId))
                .map(OptionScore::probability)
                .findFirst();
    }

    /**
     * 概率最高的选项。
     *
     * <p>平局时取 optionId 字典序最小者——因为 {@code distribution} 已按字典序排列，
     * 这里的行为是确定性的。<b>注意</b>：真实运行中平局并不罕见
     * （SemIf 的实测里就出现过 A=B=0.4995 的精确平局），
     * 因此漂移对比必须能识别「平局翻转」而非把它当成语义变化。
     */
    public String argmaxOption() {
        return distribution.stream()
                .max((a, b) -> {
                    int byProbability = Double.compare(a.probability(), b.probability());
                    // 概率相同时按字典序取较小者
                    return byProbability != 0 ? byProbability : b.optionId().compareTo(a.optionId());
                })
                .orElseThrow()
                .optionId();
    }

    /** 最高概率值。 */
    public double maxProbability() {
        return distribution.stream().mapToDouble(OptionScore::probability).max().orElseThrow();
    }

    /**
     * 最高与次高概率之差。平局时返回 0。
     * 策略层用它做「高影响判定额外要求领先幅度」的判断。
     */
    public double margin() {
        double[] top2 = distribution.stream()
                .mapToDouble(OptionScore::probability)
                .sorted()
                .skip(Math.max(0, distribution.size() - 2L))
                .toArray();
        if (top2.length < 2) {
            return maxProbability();
        }
        return top2[top2.length - 1] - top2[0];
    }

    /** 以 optionId 为键的分布视图，便于与外部结果对齐比较。 */
    public Map<String, Double> asMap() {
        TreeMap<String, Double> map = new TreeMap<>();
        distribution.forEach(score -> map.put(score.optionId(), score.probability()));
        return Map.copyOf(map);
    }
}
