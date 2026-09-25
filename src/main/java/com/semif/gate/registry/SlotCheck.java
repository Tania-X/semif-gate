package com.semif.gate.registry;

import java.util.List;

/**
 * 答案槽位契约检查的接口与结果类型。
 *
 * <h2>它防的是什么</h2>
 * SemIf 的读出口依赖一个硬前提：<b>每个选项的答案必须恰好是一个 token，且往返一致</b>。
 * 如果这个前提不成立，读到的 logits 就不是那个选项的概率——
 * 例如字母 {@code B} 在某些 tokenizer 里会被切成两个 token，
 * 此时读「最后一个 token 的 logits」得到的是半个选项的分数，
 * 而程序不会报错，只会安静地返回错误的概率。
 *
 * <p>SemIf 在 {@code direct.py::_slot_ids()} 里把这个检查放在每次编码时执行；
 * 本项目把它提升为<b>启动自检</b>：不满足就拒绝启动，而不是等到线上跑出错误结果。
 *
 * <h2>谁能实现它</h2>
 * <ul>
 *   <li>{@link TokenizerSlotChecker} —— 基于 token 词表的纯 Java 实现，
 *       覆盖单 token、往返一致、无冲突三项。无需真实模型即可测试。</li>
 *   <li>真实 tokenizer 适配器（后续步骤）—— 额外覆盖「在 prompt 末尾追加字母不改变
 *      已有 token 序列」这一项，因为那需要真正的 prompt 编码。</li>
 *   <li>约束解码类 provider —— 不适用本契约，必须显式返回
 *       {@link Status#SKIPPED} 并说明原因，不能默默通过。</li>
 * </ul>
 */
public interface SlotCheck {

    /** 单个判定点的槽位检查结果。 */
    record Result(String pointRef, Status status, List<String> failures, List<Integer> slotTokenIds) {

        public Result {
            failures = List.copyOf(failures);
            slotTokenIds = slotTokenIds == null ? List.of() : List.copyOf(slotTokenIds);
            if (status == Status.FAILED && failures.isEmpty()) {
                throw new IllegalArgumentException("失败结果必须给出原因: " + pointRef);
            }
            if (status == Status.SKIPPED && failures.isEmpty()) {
                throw new IllegalArgumentException("跳过必须说明原因: " + pointRef);
            }
        }

        public boolean passed() {
            return status == Status.OK;
        }

        /** 该结果是否应当阻止启动。跳过不算失败。 */
        public boolean blocksStartup() {
            return status == Status.FAILED;
        }
    }

    /** 单个判定点的检查状态。 */
    enum Status {
        /** 契约满足。 */
        OK,
        /** 契约不满足——必须阻止启动。 */
        FAILED,
        /** 本 provider 不适用该检查（必须给出原因）。 */
        SKIPPED
    }

    /**
     * 检查一个判定点的答案槽位契约。
     *
     * @param point      待检查的判定点
     * @param promptText 已渲染的完整 prompt（供需要真实编码的实现使用）
     * @return 检查结果
     */
    Result check(DecisionPointLike point, String promptText);

    /**
     * 为了让本接口不依赖 contract 包的具体 record 形态而定义的最小视图。
     * 实现方需要的是：判定点标识、答案风格、选项数量。
     */
    interface DecisionPointLike {
        /** {@code pointId@version}。 */
        String ref();

        /** 答案风格：{@code LETTER} 或 {@code YESNO}。 */
        String answerStyle();

        /** 选项数量。 */
        int optionCount();
    }
}
