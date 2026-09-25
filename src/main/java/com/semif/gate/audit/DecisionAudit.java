package com.semif.gate.audit;

import java.util.List;

/**
 * 审计落盘的 SPI。
 *
 * <p>它的失败语义与缓存<b>正好相反</b>：
 * <ul>
 *   <li>缓存读失败 → 当作未命中，继续调 provider（可用性优先）；</li>
 *   <li>审计写失败 → <b>必须让调用方知道</b>。</li>
 * </ul>
 * 理由：审计是「这条判定是怎么来的」的唯一证据。如果它悄悄丢记录，
 * 你会得到一个看起来正常工作、但无法解释任何一条判定的系统——
 * 而这种缺陷只有在事故复盘时才会暴露，那时已经太晚。
 */
public interface DecisionAudit {

    /**
     * 记录一批判定。
     *
     * @param records 本次调用的全部判定记录（含缓存命中与未命中）
     */
    void record(List<DecisionRecord> records);

    /** 便捷单条记录。 */
    default void record(DecisionRecord record) {
        record(List.of(record));
    }
}
