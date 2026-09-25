package com.semif.gate.state;

import com.semif.gate.contract.DecisionPoint;
import com.semif.gate.contract.Provenance;

/**
 * 缓存键的构造——<b>全部影响语义的输入，一个都不能少</b>。
 *
 * <h2>为什么是这七项</h2>
 * <table border="1">
 *   <tr><th>分量</th><th>漏掉它会怎样</th></tr>
 *   <tr><td>{@code pointId@version}</td><td>改了准则措辞但版本没升，缓存继续返回旧语义的判定</td></tr>
 *   <tr><td>选项集哈希</td><td>增删选项后，旧的概率分布被当成新选项集的答案</td></tr>
 *   <tr><td>state 哈希</td><td>不同输入共用同一条缓存</td></tr>
 *   <tr><td>modelRevision</td><td>换模型后仍读旧模型的判定</td></tr>
 *   <tr><td>backend</td><td>换执行后端（数值实现不同）后结果被静默复用</td></tr>
 *   <tr><td>providerId</td><td>切换 provider 后新旧结果混在一起，无法做漂移对比</td></tr>
 *   <tr><td>registrySha256</td><td>模板或描述文字被改动后，缓存键却不变</td></tr>
 * </table>
 *
 * <p>分隔符使用 {@code \u0000}（NUL）：它不可能出现在正常的 id、哈希或版本号里，
 * 因此不存在「两个不同分量拼出同一个字符串」的歧义。
 * 若用 {@code :} 或 {@code -} 这类常见字符做分隔，{@code ("a:b", "c")} 与 {@code ("a", "b:c")}
 * 会撞成同一个键——这是哈希拼接的经典陷阱。
 */
public final class DecisionKey {

    private static final String SEPARATOR = "\u0000";

    private DecisionKey() {
    }

    /**
     * 由 state、判定点契约与溯源信息构造缓存键。
     *
     * @param state 已规范化的状态
     * @param point 判定点契约
     * @param provenance 本次判定的溯源信息
     * @return 64 位十六进制 SHA-256
     */
    public static String of(DecisionState state, DecisionPoint point, Provenance provenance) {
        return of(state.hash(),
                point.ref(),
                point.optionsSha256(),
                provenance.modelRevision(),
                provenance.backend(),
                provenance.providerId(),
                provenance.registrySha256());
    }

    /**
     * 底层构造：所有输入必须非空。
     *
     * <p>刻意不做「空值就跳过」的容错——任何一个分量缺失都意味着我们无法
     * 唯一确定这次判定，此时正确行为是拒绝构造而不是产出一个过宽的键。
     */
    public static String of(String stateHash,
                            String pointRef,
                            String optionsSha256,
                            String modelRevision,
                            String backend,
                            String providerId,
                            String registrySha256) {
        requireNonBlank(stateHash, "stateHash");
        requireNonBlank(pointRef, "pointRef");
        requireNonBlank(optionsSha256, "optionsSha256");
        requireNonBlank(modelRevision, "modelRevision");
        requireNonBlank(backend, "backend");
        requireNonBlank(providerId, "providerId");
        requireNonBlank(registrySha256, "registrySha256");

        String joined = String.join(SEPARATOR,
                pointRef, optionsSha256, stateHash, modelRevision, backend, providerId, registrySha256);
        return StateHasher.sha256Hex(joined);
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缓存键分量不能为空: " + field);
        }
    }
}
