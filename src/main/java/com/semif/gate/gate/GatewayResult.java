package com.semif.gate.gate;

import com.semif.gate.audit.DecisionRecord;
import com.semif.gate.contract.Decision;

import java.util.List;
import java.util.Map;

/**
 * 一次网关调用的完整结果。
 *
 * <p><b>刻意不只返回 {@code Map<String, Decision>}。</b>
 * 调用方需要知道这次调用花了多少代价：命中了几条缓存、真的调了几次 provider。
 * 这些数字是成本核算与容量评估的输入，一旦在网关内部丢掉就再也拿不回来了。
 *
 * <p>其中 {@link #providerCalls()} 是<b>调用次数</b>而不是判定点数——
 * 一批 N 个判定点只算一次调用，因为 provider 是批量执行的
 * （同一份长 state 上判 N 条准则正是它要优化的场景）。
 *
 * @param decisions    判定点 ID -&gt; 判定结果
 * @param records      全部审计记录（含缓存命中与未命中），顺序与判定点请求顺序一致
 * @param cacheHits    命中缓存的判定点数
 * @param cacheMisses  未命中的判定点数（含被契约校验拒绝的）
 * @param providerCalls provider 实际调用次数（有未命中时才为 1，全命中时为 0）
 */
public record GatewayResult(
        Map<String, Decision> decisions,
        List<DecisionRecord> records,
        int cacheHits,
        int cacheMisses,
        int providerCalls) {

    public GatewayResult {
        if (decisions == null) {
            throw new IllegalArgumentException("decisions 不能为空");
        }
        decisions = Map.copyOf(decisions);
        records = records == null ? List.of() : List.copyOf(records);
        if (cacheHits < 0 || cacheMisses < 0 || providerCalls < 0) {
            throw new IllegalArgumentException("统计值不能为负");
        }
    }

    /** 某个判定点的判定结果。 */
    public Decision decision(String pointId) {
        Decision found = decisions.get(pointId);
        if (found == null) {
            throw new IllegalArgumentException("本次调用没有判定点 " + pointId);
        }
        return found;
    }

    /** 判定点总数。 */
    public int size() {
        return decisions.size();
    }

    /** 缓存命中率；无判定点时返回 0。 */
    public double cacheHitRate() {
        int total = cacheHits + cacheMisses;
        return total == 0 ? 0.0 : (double) cacheHits / total;
    }

    /** 本次调用是否完全没有触碰 provider。 */
    public boolean servedEntirelyFromCache() {
        return providerCalls == 0;
    }
}
