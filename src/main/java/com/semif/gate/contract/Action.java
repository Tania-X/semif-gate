package com.semif.gate.contract;

/**
 * 判定之后要执行的动作。
 *
 * <p>刻意与 {@link Band} 分开：档位是对不确定性的描述，动作是业务策略的选择。
 * 同一个档位在不同判定点上可能对应不同动作（策略表配置决定），
 * 而网关永远不执行动作——它只把动作建议交给调用方。
 */
public enum Action {
    /** 直接应用判定结果。 */
    AUTO_APPLY,
    /** 转人工复核队列。 */
    HUMAN_REVIEW,
    /** 采用保守默认动作（判定不可用时的兜底）。 */
    SAFE_DEFAULT
}
