package com.semif.gate.state;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 状态规范化器——把任意调用方传入的 state 变成确定性的、可哈希的、已去敏的 JSON。
 *
 * <h2>三条纪律</h2>
 * <ol>
 *   <li><b>字段白名单</b>：只允许调用方显式声明的字段进入 state。
 *       这既防 PII 意外泄漏，也防「payload 里多了个时间戳导致缓存永不命中」——
 *       后者是线上最常见的缓存失效原因，而且很难排查。</li>
 *   <li><b>数字规范化</b>：委托给 {@link CanonicalJson}，浮点按 12 位有效数字量化。</li>
 *   <li><b>哈希在截断之后算</b>：{@link #normalize} 先按上限截断，再对实际内容取哈希。</li>
 * </ol>
 *
 * <p>长度限制按 <b>Unicode 码点</b>而非 {@code char} 计数——{@code char} 计数会在
 * 代理对（emoji、部分生僻字）中间切断，产生半个字符。
 */
public final class StateNormalizer {

    /** 默认长度上限：12000 码点。取这个值是因为 SemIf 的系统基准里 state 约 8000 字符，
     *  留出约 50% 余量；上限本身不影响正确性，只影响「多大算太大」。 */
    public static final int DEFAULT_MAX_CODE_POINTS = 12_000;

    private final Set<String> allowedFields;
    private final int maxCodePoints;

    /**
     * @param allowedFields 允许进入 state 的字段名白名单，不能为空
     */
    public StateNormalizer(Set<String> allowedFields) {
        this(allowedFields, DEFAULT_MAX_CODE_POINTS);
    }

    /**
     * @param allowedFields  允许进入 state 的字段名白名单，不能为空
     * @param maxCodePoints  长度上限（码点数），必须为正
     */
    public StateNormalizer(Set<String> allowedFields, int maxCodePoints) {
        if (allowedFields == null || allowedFields.isEmpty()) {
            throw new IllegalArgumentException("字段白名单不能为空——空白名单意味着没有字段能进入 state");
        }
        if (maxCodePoints < 1) {
            throw new IllegalArgumentException("长度上限必须为正: " + maxCodePoints);
        }
        this.allowedFields = Set.copyOf(allowedFields);
        this.maxCodePoints = maxCodePoints;
    }

    /**
     * 规范化一个原始的 state 映射。
     *
     * <p>处理顺序（顺序本身就是语义的一部分）：
     * <ol>
     *   <li>按白名单过滤字段，未声明的字段被丢弃</li>
     *   <li>序列化为规范化 JSON（键排序 + 数字量化）</li>
     *   <li>若超过长度上限则结构化截断</li>
     *   <li>对<b>截断后</b>的内容计算哈希</li>
     * </ol>
     *
     * @param rawState 调用方提供的原始状态
     * @return 规范化后的状态，其 {@code hash()} 与 {@code json()} 严格对应
     * @throws IllegalArgumentException 输入不是映射、白名单过滤后为空、或含非法值
     */
    public DecisionState normalize(Map<String, ?> rawState) {
        if (rawState == null) {
            throw new IllegalArgumentException("state 不能为 null");
        }
        Map<String, Object> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : rawState.entrySet()) {
            if (allowedFields.contains(entry.getKey())) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        if (filtered.isEmpty()) {
            throw new IllegalArgumentException(
                    "白名单过滤后 state 为空；白名单=" + allowedFields + "，实际字段=" + rawState.keySet());
        }
        String canonical = CanonicalJson.write(filtered);
        DecisionState state = DecisionState.ofEffective(canonical, canonical);
        // 纪律 3：先截断，后算哈希——由 DecisionState.truncate 重新计算
        return state.truncate(maxCodePoints);
    }

    /** 被允许的字段名，按字典序。 */
    public Set<String> allowedFields() {
        return Set.copyOf(new java.util.TreeSet<>(allowedFields));
    }

    /** 长度上限（码点数）。 */
    public int maxCodePoints() {
        return maxCodePoints;
    }
}
