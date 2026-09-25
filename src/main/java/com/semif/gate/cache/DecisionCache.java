package com.semif.gate.cache;

import com.semif.gate.contract.Decision;
import com.semif.gate.contract.DecisionPoint;
import com.semif.gate.state.StateHasher;

import java.util.Optional;

/**
 * 判定缓存的 SPI。
 *
 * <h2>为什么查找键里不能含 registrySha256 —— 一处规格修正</h2>
 * 最初的规格把候选键定为
 * {@code sha256(stateHash, pointRef, registrySha256, optionsSha256)}，
 * 同时要求「命中后校验存储的 registrySha256 == 当前 registrySha256」。
 * 这两条<b>互相抵消</b>：键里已经含 registrySha256，
 * 那么注册表一变键就变、条目根本查不到，第二级校验永远不会失败——是死代码。
 *
 * <p>更直接的问题是：{@code lookup(stateHash, point)} 的签名里<b>没有</b>
 * registrySha256，实现根本无法知道「当前」注册表哈希。
 *
 * <p><b>修正</b>：
 * <ul>
 *   <li>查找键 = {@code sha256(stateHash, pointRef)}——只含查找阶段真正已知的量；</li>
 *   <li>{@code registrySha256} 作为<b>参数传入</b> {@link #lookup}，用于命中后的显式校验；</li>
 *   <li>校验同时比对 {@code registrySha256} 与 {@code optionsSha256} 两项。</li>
 * </ul>
 * 这样第二级校验是真实生效的：注册表变更后旧条目仍在表里（键没变），
 * 但会被校验拦下并计入 {@code staleRejections}——这正是我们想要的可观测行为，
 * 而不是让它悄悄消失。
 *
 * <p><b>绝不允许</b>在校验不过时返回旧结果：那等于用旧语义回答了一个新问题，
 * 而且不会有任何报错。
 */
public interface DecisionCache {

    /**
     * 查找缓存。
     *
     * @param stateHash      状态哈希
     * @param point          判定点契约
     * @param registrySha256 当前注册表整体哈希，用于命中后的契约校验
     * @return 命中且契约校验通过时返回条目；否则 {@link Optional#empty()}
     */
    Optional<CachedDecision> lookup(String stateHash, DecisionPoint point, String registrySha256);

    /**
     * 写入缓存。
     *
     * @param stateHash      状态哈希
     * @param point          判定点契约
     * @param decision       判定结果
     * @param registrySha256 写入时的注册表整体哈希
     */
    void store(String stateHash, DecisionPoint point, Decision decision, String registrySha256);

    /**
     * 计算查找键。
     *
     * <p>放在接口里作为静态方法，是为了让所有实现在「键怎么算」这件事上不可能产生分歧——
     * 它是缓存正确性的一部分，不是实现细节。
     *
     * <p>分隔符用 {@code \u0000}（与 {@code DecisionKey} 一致）：避免
     * {@code ("a:b", "c")} 与 {@code ("a", "b:c")} 撞成同一个键。
     */
    static String lookupKey(String stateHash, DecisionPoint point) {
        requireNonBlank(stateHash, "stateHash");
        if (point == null) {
            throw new IllegalArgumentException("point 不能为空");
        }
        return StateHasher.sha256Hex(stateHash + '\u0000' + point.ref());
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("查找键分量不能为空: " + field);
        }
    }
}
