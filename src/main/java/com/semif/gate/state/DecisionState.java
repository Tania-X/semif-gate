package com.semif.gate.state;

import java.util.List;

/**
 * 规范化之后的决策状态——缓存键的唯一来源。
 *
 * <p><b>本类型持有一个不可动摇的不变式：{@link #hash()} 永远等于 {@link #json()} 的哈希。</b>
 * 因此「哈希在截断之后算」这条纪律不是靠调用方自觉遵守，而是由类型本身保证的：
 * 截断只能通过 {@link #truncate(int)} 产生<b>新实例</b>，新实例会重新计算哈希。
 * 想绕开它，只能自己 new 一个——而构造函数不允许。
 *
 * <p>为什么这条纪律如此重要：如果哈希描述的是截断<b>之前</b>的内容，
 * 那么两个截断后完全相同的 state 会得到不同的缓存键（缓存失效），
 * 或者两个截断后不同的 state 得到相同的键（缓存返回错误结果）。
 * 后者是静默的错误判定，比缓存不命中危险得多。
 *
 * @param rawJson     规范化后的完整 JSON
 * @param json        实际会发送出去的 JSON（等于 rawJson，或被截断后更短）
 * @param hash        {@code json} 的 SHA-256
 * @param truncated   是否发生了截断
 * @param originalCodePoints 截断前的内容长度（码点数）
 */
public record DecisionState(
        String rawJson,
        String json,
        String hash,
        boolean truncated,
        int originalCodePoints) {

    /** 合法构造入口：由 {@link StateNormalizer} 使用。 */
    static DecisionState ofEffective(String rawJson, String json) {
        return new DecisionState(
                rawJson,
                json,
                StateHasher.sha256Hex(json),
                !rawJson.equals(json),
                JsonTree.codePointLength(rawJson));
    }

    /**
     * 产生一个被截断到 {@code maxCodePoints} 码点以内的新状态。
     *
     * <p>截断是<b>结构化</b>的：从对象尾部丢弃键值对（数组则从尾部丢弃元素），
     * 必要时再裁剪最长的字符串，因此结果仍然是合法 JSON。
     * 只有在实在无法通过丢弃条目满足限制时，才会退化为原始截断（并可能产生非法 JSON）。
     *
     * @param maxCodePoints 目标上限（码点数）
     * @return 新的状态实例，哈希已按截断后的内容重算
     */
    public DecisionState truncate(int maxCodePoints) {
        if (maxCodePoints < 1) {
            throw new IllegalArgumentException("截断上限必须为正: " + maxCodePoints);
        }
        if (JsonTree.codePointLength(rawJson) <= maxCodePoints) {
            return this;
        }
        JsonTree.Node parsed;
        try {
            parsed = JsonTree.parse(rawJson);
        } catch (IllegalArgumentException e) {
            // rawJson 由 CanonicalJson 产出，正常不会走到这里；真走到了说明上游被改坏了
            throw new IllegalStateException("规范化 JSON 无法解析，这不应该发生", e);
        }
        JsonTree.Node shrunk = JsonTree.truncate(parsed, maxCodePoints);
        return ofEffective(rawJson, JsonTree.toJson(shrunk));
    }

    /** 内容长度（码点数）。 */
    public int codePointLength() {
        return JsonTree.codePointLength(json);
    }

    /**
     * 顶层字段名，按规范顺序排列；非对象状态返回空列表。
     *
     * <p>返回的是未加引号的原始字段名（{@code alpha} 而非 {@code "alpha"}）——
     * 它是给人看的诊断信息，不是 JSON 片段。
     */
    public List<String> topLevelFields() {
        try {
            JsonTree.Node node = JsonTree.parse(json);
            if (node instanceof JsonTree.ObjectNode object) {
                // 解析器保留的是含引号的 JSON 字面量，这里去掉首尾引号与转义，还原字段名
                return object.entries().keySet().stream()
                        .map(DecisionState::unquote)
                        .toList();
            }
        } catch (IllegalArgumentException ignored) {
            // 截断退化情形下可能不是合法对象；此处只做展示用途，不抛异常
        }
        return List.of();
    }

    /** 去掉 JSON 字符串字面量的首尾引号并还原常见转义。 */
    private static String unquote(String literal) {
        if (literal.length() < 2 || literal.charAt(0) != '"') {
            return literal;
        }
        String body = literal.substring(1, literal.length() - 1);
        return body.replace("\\\"", "\"").replace("\\\\", "\\")
                .replace("\\n", "\n").replace("\\t", "\t").replace("\\r", "\r");
    }
}
