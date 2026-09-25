package com.semif.gate.contract;

/**
 * 单个选项的概率得分。
 *
 * <p>构造即校验：只接受 {@code [0,1]} 区间内的有限值。拒绝 NaN / Infinity / 越界值，
 * 是为了防止上游 provider 的数值问题静默污染缓存与审计记录——
 * 一个 NaN 概率会一路传播到策略阈值判断，而阈值比较遇到 NaN 永远为 false。
 *
 * @param optionId    对应的选项语义 ID
 * @param probability 概率，必须有限且落在 [0,1]
 */
public record OptionScore(String optionId, double probability) {

    public OptionScore {
        if (optionId == null || optionId.isBlank()) {
            throw new IllegalArgumentException("optionId 不能为空");
        }
        if (Double.isNaN(probability)) {
            throw new IllegalArgumentException("概率不能是 NaN: " + optionId);
        }
        if (Double.isInfinite(probability)) {
            throw new IllegalArgumentException("概率不能是无穷大: " + optionId + " = " + probability);
        }
        if (probability < 0.0 || probability > 1.0) {
            throw new IllegalArgumentException(
                    "概率必须落在 [0,1]: " + optionId + " = " + probability);
        }
    }
}
