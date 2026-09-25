package com.semif.gate.policy;

import com.semif.gate.contract.Action;
import com.semif.gate.contract.Band;
import com.semif.gate.contract.Decision;

/**
 * 策略引擎——<b>纯函数，不碰网络、不碰模型</b>。
 *
 * <p>把「分布 → 档位」这一步独立出来，是因为它是业务规则而非模型能力：
 * 同一个分布在不同判定点上可能对应不同动作（高影响的判定要求更高置信度）。
 * 网关只负责标注档位与建议动作，<b>永远不执行动作</b>——执行是调用方的事。
 *
 * <p>策略必须带版本号并进审计记录：策略改了之后，历史记录仍然要能被解释
 * （「这条当时为什么自动执行了」的答案会随策略变化，必须能追溯到当时那一版）。
 */
public interface PolicyEngine {

    /** 策略版本，进审计记录。 */
    String version();

    /**
     * 按分布计算档位。
     *
     * <p>降级结果必须落在最低档——「没判定出来」绝不能被当成高置信度。
     */
    Band band(Decision decision);

    /**
     * 降级时采用的保守动作。
     *
     * @param pointId 判定点 ID
     */
    Action onDegraded(String pointId);
}
