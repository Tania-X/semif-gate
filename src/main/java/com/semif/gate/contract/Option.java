package com.semif.gate.contract;

/**
 * 判定点的一个候选选项。
 *
 * <p>{@code id} 是语义标识，参与缓存键与结果对齐；{@code description} 是送入模型的文字。
 * 两者分离让「选项顺序变化」不影响结果比对——SemIf 的实测显示选项顺序变化
 * 会导致 777 个决策里翻转若干（其记录为 10 个），因此结果必须按 id 对齐而非按位置。
 *
 * @param id          选项语义 ID，非空
 * @param description 送入模型的描述文字，非空
 */
public record Option(String id, String description) {

    public Option {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("option id 不能为空");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("option description 不能为空: " + id);
        }
    }
}
