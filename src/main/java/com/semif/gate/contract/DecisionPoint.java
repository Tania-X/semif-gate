package com.semif.gate.contract;

import java.util.List;

/**
 * 一个不可变的判定点契约。
 *
 * <p><b>核心纪律：判定点是不可变契约，版本进缓存键。</b>
 * 改动措辞、增删选项、调整描述文字，都必须提升 {@code version}；
 * 否则缓存会静默返回旧语义的判定结果，而且不会有任何报错——
 * 这是这类系统最容易出的生产事故。
 *
 * <p>{@link #ref()} 返回 {@code pointId@version}，它是缓存键的第一项。
 *
 * @param id            判定点标识，如 {@code ticket.route}
 * @param version       契约版本，从 1 开始；语义变更必须递增
 * @param owner         负责人/团队，用于审计追溯
 * @param frozenAt      冻结日期，便于人工核对
 * @param question      判定准则的自然语言表述
 * @param options       候选选项，2–16 个，ID 唯一
 * @param promptTemplate 冻结的 prompt 模板，只允许 {@code {{state}}} / {@code {{question}}} / {@code {{options}}} 三个占位符
 * @param answerStyle   答案槽位风格
 * @param modelRevision 模型 revision，禁止 {@code latest} 之类的浮动值
 * @param optionsSha256 选项集哈希（顺序无关），由注册表在加载时计算
 */
public record DecisionPoint(
        String id,
        int version,
        String owner,
        String frozenAt,
        String question,
        List<Option> options,
        String promptTemplate,
        AnswerStyle answerStyle,
        String modelRevision,
        String optionsSha256) {

    public DecisionPoint {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("判定点 id 不能为空");
        }
        if (version < 1) {
            throw new IllegalArgumentException("版本必须 >= 1: " + id + "@" + version);
        }
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question 不能为空: " + id);
        }
        if (options == null || options.isEmpty()) {
            throw new IllegalArgumentException("options 不能为空: " + id);
        }
        options = List.copyOf(options);
        if (promptTemplate == null || promptTemplate.isBlank()) {
            throw new IllegalArgumentException("promptTemplate 不能为空: " + id);
        }
        if (answerStyle == null) {
            throw new IllegalArgumentException("answerStyle 不能为空: " + id);
        }
        if (modelRevision == null || modelRevision.isBlank()) {
            throw new IllegalArgumentException("modelRevision 不能为空: " + id);
        }
        if (optionsSha256 == null || optionsSha256.isBlank()) {
            throw new IllegalArgumentException("optionsSha256 不能为空: " + id);
        }
    }

    /** 不可变契约键：{@code pointId@version}。 */
    public String ref() {
        return id + "@" + version;
    }

    /**
     * 返回一个更换了模型 revision 的副本。
     *
     * <p>用途：同一个判定点在不同机器上使用不同 revision 标签时
     * （例如本地目录加载时填的是溯源标签而非真实 commit），
     * 需要在不重建整个注册表的前提下生成契约副本。
     * {@code optionsSha256} 与模板无关，因此直接沿用。
     */
    public DecisionPoint withModelRevision(String newRevision) {
        return new DecisionPoint(
                id, version, owner, frozenAt, question, options,
                promptTemplate, answerStyle, newRevision, optionsSha256);
    }
}
