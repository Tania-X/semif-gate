package com.semif.gate.provider;

import com.semif.gate.contract.DecisionPoint;
import com.semif.gate.contract.ScoredPoint;
import com.semif.gate.registry.SlotCheck;
import com.semif.gate.state.DecisionState;

import java.util.List;
import java.util.Map;

/**
 * 判定执行器的 SPI——Java 侧与模型侧的唯一分界。
 *
 * <p>所有具体实现（HTTP 调推理服务、本地参考评分器、回放、规则兜底）
 * 都藏在同一个接口后面。这样做的收益很具体：
 * <ul>
 *   <li>换模型/换 provider 不改变上层任何代码；</li>
 *   <li>评测时可以拿两个实现跑同一份输入做漂移对比；</li>
 *   <li>降级实现永远是同一形状，超时路径可测。</li>
 * </ul>
 *
 * <h2>职责边界：provider 只交分数，网关独占 decisionId</h2>
 * 返回类型是 {@link ScoredPoint} 而不是 {@code Decision}：provider 算不出
 * {@code decisionId}（缓存键里含注册表整体哈希与 provider 自己的执行元数据），
 * 让 provider 构造完整判定会形成循环依赖。详见 {@link ScoredPoint} 的说明。
 */
public interface DecisionProvider {

    /** provider 标识，进缓存键与审计记录。必须稳定——它变了等于换了一个判定来源。 */
    String providerId();

    /**
     * 执行判定：一个 state，多个判定点，一次调用完成。
     *
     * <p>一次调用而非逐点调用，是因为「同一份长 state 上判 N 条准则」是最高频的用法，
     * 实现方需要有机会只 prefill 一次再分叉（SemIf 的 serial / shared 模式就是干这个的）。
     *
     * @param state  已规范化的状态
     * @param points 待判定的判定点
     * @return 判定点 ID -&gt; 原始分数；必须为每个请求的判定点都给出结果
     */
    Map<String, ScoredPoint> decide(DecisionState state, List<DecisionPoint> points);

    /**
     * 启动自检：断言答案槽位的 token 契约。
     *
     * <p>不适用的实现（例如约束解码）必须返回 {@link SlotCheck.Status#SKIPPED}
     * 并说明原因，不能默默通过。
     */
    SlotCheck.Result assertSlots(SlotCheck.DecisionPointLike point, String promptText);

    /**
     * 是否支持同一 state 的前缀复用。
     *
     * <p>默认 {@code false}。这个标志用于容量评估：支持前缀复用的 provider
     * 在「一份长文档 × 多条准则」的场景下吞吐可以高一个数量级。
     */
    default boolean supportsPrefixReuse() {
        return false;
    }
}
