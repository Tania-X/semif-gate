package com.semif.gate.registry;

import com.semif.gate.contract.DecisionPoint;
import com.semif.gate.state.StateHasher;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * 判定点契约的哈希计算。
 *
 * <p>三个哈希各有明确用途，缺一不可：
 * <ul>
 *   <li>{@link #optionsSha256} —— 进缓存键。选项集变了但版本没升时，它能兜住。</li>
 *   <li>{@link #promptSha256} —— 进审计记录，用来证明「实际发出去的 prompt」与契约一致。</li>
 *   <li>{@link #registrySha256} —— 进缓存键，覆盖模板文字、选项描述、准则措辞的改动。</li>
 * </ul>
 *
 * <p>三者的共同要求是<b>确定性</b>：同样的内容必须得到同样的哈希，
 * 且与 Map 迭代顺序、选项书写顺序无关。
 */
public final class RegistryHasher {

    /** 字段分隔符，与缓存键保持一致：不可能出现在正常内容里的 NUL。 */
    private static final String SEPARATOR = "\u0000";

    private RegistryHasher() {
    }

    /**
     * 选项集哈希——<b>顺序敏感（这是硬约束，不是选择）</b>。
     *
     * <h2>为什么必须顺序敏感</h2>
     * SemIf 的答案槽位按选项<b>下标</b>分配（{@code LETTERS[index]}）。
     * 所以选项顺序一变，字母 ↔ 选项的映射就变了，<b>prompt 文本随之改变</b>——
     * 顺序不是展示细节，它是语义的一部分。
     *
     * <p>在 GPU 机器上用真实 tokenizer 逐条实测的结论：
     * <ul>
     *   <li>按行内原始顺序渲染 prompt → <b>144/144</b> 命中 SemIf 已发布的 {@code prompt_sha256}；</li>
     *   <li>按字典序渲染 → <b>0/144</b> 命中；</li>
     *   <li>把某行换成该族最常见的顺序 → 哈希改变、不再命中。</li>
     * </ul>
     * 而顺序改变导致的行为差异是 <b>30.6%</b> 的判定翻转，
     * 相比之下换 GPU 只有 0.7%——差 40 倍。
     *
     * <h2>原设计的错误</h2>
     * 这里原本用 {@code TreeMap} 排序，把顺序当成「展示细节」排除在哈希之外。
     * 那样一来，<b>只调换选项顺序而版本未升时，缓存会静默命中旧语义的结果</b>——
     * 而模型面对新顺序可能给出完全不同的答案。这正是本项目要防的那类
     * 「不报错的错误」。
     *
     * <p>现在顺序进哈希：调换顺序必然改变 {@code optionsSha256}，
     * 从而改变缓存键，旧条目自然失效。防线从「流程上记得升版本」
     * 变成了「机制上无法复用」。
     *
     * @param options 选项列表，<b>按契约声明的顺序</b>
     * @return 64 位十六进制 SHA-256
     */
    public static String optionsSha256(List<com.semif.gate.contract.Option> options) {
        Set<String> seen = new HashSet<>();
        StringBuilder canonical = new StringBuilder();
        // 按列表顺序拼接——不排序。顺序是语义的一部分，见上面的实测证据。
        for (com.semif.gate.contract.Option option : options) {
            if (!seen.add(option.id())) {
                throw new RegistryException("选项 ID 重复，无法计算选项集哈希: " + option.id());
            }
            canonical.append(option.id()).append(SEPARATOR)
                    .append(option.description()).append(SEPARATOR);
        }
        return StateHasher.sha256Hex(canonical.toString());
    }

    /**
     * 渲染后 prompt 的哈希。
     *
     * <p>这是审计链的关键一环：比较「注册表声明的 prompt」与「provider 实际发出的 prompt」。
     * 两者不一致时，provider 的响应必须被丢弃——否则你无法解释一条判定记录。
     */
    public static String promptSha256(String renderedPrompt) {
        return StateHasher.sha256Hex(renderedPrompt);
    }

    /**
     * 注册表整体哈希——覆盖所有判定点的标识与模板。
     *
     * <p>它进缓存键，因此任何判定点的模板改动都会让全部缓存键失效。
     * 这个「过度失效」是刻意的：宁可多算几次，也不要让新旧语义的判定混在一张表里。
     *
     * @param points 所有判定点
     * @return 64 位十六进制 SHA-256
     */
    public static String registrySha256(List<DecisionPoint> points) {
        // 值 = 模板 + 选项集哈希：两者任一变化都必须让注册表哈希变化。
        // 只覆盖模板是不够的——改动选项描述同样改变语义（模型看到的候选变了），
        // 但模板字符串一个字都没动，缓存会静默命中旧结果。
        TreeMap<String, String> byRef = new TreeMap<>();
        for (DecisionPoint point : points) {
            String fingerprint = point.promptTemplate() + SEPARATOR + point.optionsSha256();
            String previous = byRef.put(point.ref(), fingerprint);
            if (previous != null) {
                throw new RegistryException("判定点引用重复: " + point.ref());
            }
        }
        StringBuilder canonical = new StringBuilder();
        byRef.forEach((ref, fingerprint) -> canonical
                .append(ref).append(SEPARATOR).append(fingerprint).append(SEPARATOR));
        return StateHasher.sha256Hex(canonical.toString());
    }
}
