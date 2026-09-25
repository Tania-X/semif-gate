package com.semif.gate.contract;

/**
 * 答案槽位的表达方式。
 *
 * <p>对齐 SemIf 的两种读出口：
 * <ul>
 *   <li>{@link #LETTER} —— 直接读 {@code A..P} 槽位的 logits（SemIf 的 direct 模式，支持 2–16 个选项）</li>
 *   <li>{@link #YESNO} —— 读 {@code yes}/{@code no} 两个槽位（SemIf 的 reranker 模式）</li>
 * </ul>
 */
public enum AnswerStyle {
    /** 大写字母槽位，最多 16 个选项。 */
    LETTER,
    /** yes/no 槽位，仅支持二元判定。 */
    YESNO;

    /** 该风格下最多可表达的选项数量。 */
    public int maxOptions() {
        return this == LETTER ? 16 : 2;
    }
}
