package com.semif.gate.policy;

import com.semif.gate.contract.Action;
import com.semif.gate.contract.Band;
import com.semif.gate.contract.Decision;

/**
 * 阈值策略：按最高概率分档。
 *
 * <p>这是一份<b>刻意最小</b>的实现。真实策略表会长成
 * 「判定点 × 选项 × 档位 → 动作」外加领先幅度要求，但那些都应该建立在
 * 这一层已经正确的前提上。
 *
 * <h2>一条不能省的规则：降级永远落最低档</h2>
 * provider 超时返回的降级分布，最高概率可能并不低（保守选项可能占 0.5）。
 * 如果只按阈值判断，它有可能被误判成中间带甚至自动执行——
 * 那就等于把「没判定出来」当成了「判定为某个答案」。
 * 因此降级必须先判，而且直接落到最低档。
 *
 * <h2>阈值不是校准过的置信度</h2>
 * SemIf 的文档明确写了 softmax 分数是「conditional option score;
 * uncalibrated as decision confidence」。所以这里的阈值是<b>投放决策</b>
 * 而不是概率保证——它必须由真实工作负载上的评测来定，不能拍脑袋。
 * 新版 SemIf 已加入温度校准（WANLI 上 ECE 从 0.208 降到 0.069），
 * 校准过的分布才能让这些阈值有实际意义。
 */
public final class ThresholdPolicyEngine implements PolicyEngine {

    private final double autoMin;
    private final double reviewMin;
    private final String version;
    private final Action degradedAction;

    /**
     * @param autoMin        自动执行所需的最低最高概率，必须 &gt; reviewMin
     * @param reviewMin      进入人工复核的最低最高概率
     * @param version        策略版本，进审计记录
     * @param degradedAction 降级时采用的保守动作
     */
    public ThresholdPolicyEngine(double autoMin, double reviewMin,
                                 String version, Action degradedAction) {
        if (autoMin <= reviewMin) {
            throw new IllegalArgumentException(
                    "autoMin 必须大于 reviewMin: autoMin=" + autoMin + ", reviewMin=" + reviewMin);
        }
        if (reviewMin < 0.0 || autoMin > 1.0) {
            throw new IllegalArgumentException(
                    "阈值必须落在 [0,1]: autoMin=" + autoMin + ", reviewMin=" + reviewMin);
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("策略版本不能为空——审计需要它");
        }
        if (degradedAction == null) {
            throw new IllegalArgumentException("degradedAction 不能为空");
        }
        this.autoMin = autoMin;
        this.reviewMin = reviewMin;
        this.version = version;
        this.degradedAction = degradedAction;
    }

    /** 一组保守的默认阈值。 */
    public static ThresholdPolicyEngine defaults() {
        return new ThresholdPolicyEngine(0.90, 0.60, "threshold-v1", Action.SAFE_DEFAULT);
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public Band band(Decision decision) {
        if (decision == null) {
            throw new IllegalArgumentException("decision 不能为空");
        }
        // 降级先判：绝不能让它因为「保守选项概率尚可」而落进 auto。
        if (!decision.ok()) {
            return Band.REFUSE;
        }
        double max = decision.maxProbability();
        if (max >= autoMin) {
            return Band.AUTO;
        }
        if (max >= reviewMin) {
            return Band.REVIEW;
        }
        return Band.REFUSE;
    }

    @Override
    public Action onDegraded(String pointId) {
        return degradedAction;
    }

    /** 自动执行阈值。 */
    public double autoMin() {
        return autoMin;
    }

    /** 人工复核阈值。 */
    public double reviewMin() {
        return reviewMin;
    }
}
