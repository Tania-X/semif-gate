package com.semif.gate.cache;

import com.semif.gate.contract.Decision;
import com.semif.gate.contract.DecisionPoint;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内缓存实现——测试与单机默认。
 *
 * <p>它同时是「两级校验」语义的参考实现：
 * <ol>
 *   <li>用 {@link DecisionCache#lookupKey}（state + pointRef）定位候选条目；</li>
 *   <li>命中后<b>仍要校验契约指纹</b>（registrySha256 与 optionsSha256），
 *       不符则返回 {@link Optional#empty()} 并计入 {@link #staleRejections()}。</li>
 * </ol>
 *
 * <p>为什么要单独统计「因契约变化而被拒的命中」：这是可观测性需求。
 * 如果这个计数上升，说明注册表在运行期被改动过——那本该是一次需要重新部署的
 * 契约变更，而不是一个静默的缓存行为。
 */
public final class InMemoryDecisionCache implements DecisionCache {

    private final Map<String, CachedDecision> entries = new ConcurrentHashMap<>();
    private final AtomicLong staleRejections = new AtomicLong();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    @Override
    public Optional<CachedDecision> lookup(String stateHash, DecisionPoint point, String registrySha256) {
        CachedDecision entry = entries.get(DecisionCache.lookupKey(stateHash, point));
        if (entry == null) {
            misses.incrementAndGet();
            return Optional.empty();
        }
        // 第二级校验——真实生效的一道闸门，不是形式。
        // 注册表或选项集变更后，旧条目仍能在表里被找到（键没变），
        // 但绝不能把它返回给调用方：那是用旧语义回答新问题。
        if (!entry.matchesContract(registrySha256, point)) {
            staleRejections.incrementAndGet();
            misses.incrementAndGet();
            return Optional.empty();
        }
        hits.incrementAndGet();
        return Optional.of(entry);
    }

    @Override
    public void store(String stateHash, DecisionPoint point, Decision decision, String registrySha256) {
        entries.put(DecisionCache.lookupKey(stateHash, point),
                CachedDecision.from(decision, registrySha256));
    }

    /** 因契约指纹不符而被拒绝的命中次数。 */
    public long staleRejections() {
        return staleRejections.get();
    }

    /** 成功命中次数。 */
    public long hits() {
        return hits.get();
    }

    /** 未命中次数（含被契约校验拒绝的）。 */
    public long misses() {
        return misses.get();
    }

    /** 当前条目数。 */
    public int size() {
        return entries.size();
    }

    /** 清空缓存与统计。 */
    public void clear() {
        entries.clear();
        staleRejections.set(0);
        hits.set(0);
        misses.set(0);
    }
}
