package com.semif.gate.gate;

import com.semif.gate.audit.DecisionAudit;
import com.semif.gate.audit.DecisionRecord;
import com.semif.gate.cache.CacheAccessException;
import com.semif.gate.cache.CachedDecision;
import com.semif.gate.cache.DecisionCache;
import com.semif.gate.contract.Band;
import com.semif.gate.contract.Decision;
import com.semif.gate.contract.DecisionPoint;
import com.semif.gate.contract.OptionScore;
import com.semif.gate.contract.Provenance;
import com.semif.gate.contract.ScoredPoint;
import com.semif.gate.policy.PolicyEngine;
import com.semif.gate.provider.DecisionProvider;
import com.semif.gate.registry.DecisionPointRegistry;
import com.semif.gate.state.DecisionKey;
import com.semif.gate.state.DecisionState;
import com.semif.gate.state.StateNormalizer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 决策网关——<b>系统唯一入口</b>。
 *
 * <h2>它独占两件事</h2>
 * <ol>
 *   <li><b>decisionId 的构造。</b>缓存键里含注册表整体哈希与 provider 的执行元数据，
 *       provider 自己算不出来（会形成循环依赖，详见 {@code ScoredPoint}）。
 *       网关是唯一知道全部输入的地方，因此也是唯一能产出可缓存、可审计标识的地方。</li>
 *   <li><b>契约指纹的补齐。</b>{@code registrySha256} / {@code optionsSha256}
 *       是<b>契约的属性</b>，不是 provider 的属性，只能由网关写进溯源信息。</li>
 * </ol>
 *
 * <h2>失败语义（三条都是刻意的）</h2>
 * <table border="1">
 *   <tr><th>故障</th><th>行为</th><th>理由</th></tr>
 *   <tr><td>provider 抛异常</td><td>该批判定全部 DEGRADED，<b>请求不失败</b></td>
 *       <td>模型超时不该让调用链停摆；降级结果带明确原因，上游可走保守路径</td></tr>
 *   <tr><td>缓存读失败</td><td>当作未命中，继续调 provider</td>
 *       <td>缓存只是加速手段，坏了不该影响正确性</td></tr>
 *   <tr><td>审计写失败</td><td><b>向上抛</b></td>
 *       <td>审计是「这条判定怎么来的」的唯一证据，悄悄丢记录等于系统不可解释</td></tr>
 * </table>
 */
public final class DecisionGateway {

    private final DecisionPointRegistry registry;
    private final StateNormalizer normalizer;
    private final DecisionCache cache;
    private final DecisionProvider provider;
    private final PolicyEngine policy;
    private final DecisionAudit audit;

    public DecisionGateway(DecisionPointRegistry registry,
                           StateNormalizer normalizer,
                           DecisionCache cache,
                           DecisionProvider provider,
                           PolicyEngine policy,
                           DecisionAudit audit) {
        this.registry = require(registry, "registry");
        this.normalizer = require(normalizer, "normalizer");
        this.cache = require(cache, "cache");
        this.provider = require(provider, "provider");
        this.policy = require(policy, "policy");
        this.audit = require(audit, "audit");
    }

    /**
     * 执行判定。
     *
     * @param rawState  原始状态（会被规范化、按白名单裁剪）
     * @param pointRefs 判定点引用，形如 {@code ticket.route@1}
     * @return 本次调用的完整结果，含判定、审计记录与代价统计
     */
    public GatewayResult decide(Map<String, ?> rawState, List<String> pointRefs) {
        if (pointRefs == null || pointRefs.isEmpty()) {
            throw new IllegalArgumentException("pointRefs 不能为空");
        }
        DecisionState state = normalizer.normalize(rawState);
        String registrySha256 = registry.registrySha256();

        List<DecisionPoint> points = new ArrayList<>(pointRefs.size());
        for (String ref : pointRefs) {
            points.add(registry.require(ref));
        }

        // 1) 查缓存
        Map<String, Decision> resolved = new LinkedHashMap<>();
        Map<String, Boolean> cacheHitByPointId = new LinkedHashMap<>();
        List<DecisionPoint> misses = new ArrayList<>();
        for (DecisionPoint point : points) {
            Optional<CachedDecision> hit = lookupQuietly(state, point, registrySha256);
            if (hit.isPresent()) {
                resolved.put(point.id(), hit.get().toDecision(point.id(), point.version()));
                cacheHitByPointId.put(point.id(), true);
            } else {
                misses.add(point);
                cacheHitByPointId.put(point.id(), false);
            }
        }

        // 2) 只把未命中的交给 provider —— 一次批量调用
        int providerCalls = 0;
        if (!misses.isEmpty()) {
            providerCalls = 1;
            Map<String, ScoredPoint> scored = callProviderSafely(state, misses);
            for (DecisionPoint point : misses) {
                ScoredPoint raw = scored.get(point.id());
                if (raw == null) {
                    // provider 契约要求每个点都给结果；漏了就是违约，按降级处理但不静默
                    raw = ScoredPoint.degraded(
                            uniform(point),
                            "provider 未返回判定点 " + point.id(),
                            "none", provider.providerId(), "none");
                }
                Decision decision = assemble(state, point, raw, registrySha256);
                resolved.put(point.id(), decision);
                storeQuietly(state, point, decision, registrySha256);
            }
        }

        // 3) 审计全量落盘（命中与未命中都要）
        List<DecisionRecord> records = new ArrayList<>(points.size());
        for (DecisionPoint point : points) {
            Decision decision = resolved.get(point.id());
            Band band = policy.band(decision);
            records.add(DecisionRecord.of(decision, state, band,
                    policy.version(), cacheHitByPointId.get(point.id())));
        }
        audit.record(records);

        return new GatewayResult(resolved, records, misses.isEmpty() ? points.size() : points.size() - misses.size(),
                misses.size(), providerCalls);
    }

    /**
     * 把 provider 的原始分数组装成完整判定。
     *
     * <p>{@code decisionId} 在这里生成——这是网关独占的职责。
     */
    private Decision assemble(DecisionState state,
                              DecisionPoint point,
                              ScoredPoint scored,
                              String registrySha256) {
        Provenance provenance = new Provenance(
                provider.providerId(),
                scored.modelRevision(),
                scored.backend(),
                scored.promptSha256(),
                registrySha256,
                point.optionsSha256(),
                scored.inputTokens(),
                scored.latencyMs());
        String decisionId = DecisionKey.of(state, point, provenance);
        return new Decision(
                decisionId,
                point.id(),
                point.version(),
                scored.outcome(),
                scored.distribution(),
                provenance,
                scored.degradedReason());
    }

    /** provider 调用：任何异常都转成整批降级，绝不让请求失败。 */
    private Map<String, ScoredPoint> callProviderSafely(DecisionState state, List<DecisionPoint> points) {
        try {
            Map<String, ScoredPoint> scored = provider.decide(state, points);
            return scored == null ? Map.of() : scored;
        } catch (RuntimeException e) {
            String reason = "provider 调用异常: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " - " + e.getMessage());
            Map<String, ScoredPoint> degraded = new LinkedHashMap<>();
            for (DecisionPoint point : points) {
                degraded.put(point.id(), ScoredPoint.degraded(
                        uniform(point), reason, "none", provider.providerId(), "none"));
            }
            return Map.copyOf(degraded);
        }
    }

    /**
     * 缓存读取：失败视为未命中。
     *
     * <p>缓存是加速手段，不是正确性依赖。读不到就重新算，代价是慢一点，
     * 而不是把缓存故障升级成判定故障。
     */
    private Optional<CachedDecision> lookupQuietly(DecisionState state,
                                                   DecisionPoint point,
                                                   String registrySha256) {
        try {
            return cache.lookup(state.hash(), point, registrySha256);
        } catch (CacheAccessException e) {
            return Optional.empty();
        }
    }

    /** 缓存写入：失败不影响本次判定结果。 */
    private void storeQuietly(DecisionState state,
                              DecisionPoint point,
                              Decision decision,
                              String registrySha256) {
        try {
            cache.store(state.hash(), point, decision, registrySha256);
        } catch (CacheAccessException ignored) {
            // 写不进去下次再算一遍即可；判定结果本身已经产出且已进审计
        }
    }

    /** 降级用的均分分布：刻意不制造任何高置信外观。 */
    private static List<OptionScore> uniform(DecisionPoint point) {
        TreeMap<String, String> sorted = new TreeMap<>();
        point.options().forEach(option -> sorted.put(option.id(), option.description()));
        List<OptionScore> distribution = new ArrayList<>(sorted.size());
        double each = 1.0 / sorted.size();
        sorted.keySet().forEach(optionId -> distribution.add(new OptionScore(optionId, each)));
        return List.copyOf(distribution);
    }

    /** 本次调用使用的注册表哈希——供调用方做漂移对比时记录。 */
    public String registrySha256() {
        return registry.registrySha256();
    }

    /** 当前 provider 标识。 */
    public String providerId() {
        return provider.providerId();
    }

    /** 当前策略版本。 */
    public String policyVersion() {
        return policy.version();
    }

    private static <T> T require(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }
}
