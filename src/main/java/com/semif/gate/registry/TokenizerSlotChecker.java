package com.semif.gate.registry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于 token 词表的槽位检查实现——纯 Java，无需真实模型即可测试。
 *
 * <p>它覆盖三项可通过词表直接验证的契约：
 * <ol>
 *   <li><b>单 token</b>：每个答案字母恰好对应一个 token；</li>
 *   <li><b>往返一致</b>：该 token 解码回来就是那个字母本身；</li>
 *   <li><b>无冲突</b>：不同字母不映射到同一个 token。</li>
 * </ol>
 *
 * <p>它<b>不</b>覆盖第四项——「在已渲染 prompt 末尾追加字母，不改变已有 token 序列」。
 * 那一项需要真正编码 prompt，由后续步骤的 tokenizer 适配器补齐。
 * {@link #uncoveredChecks()} 会明确列出这个缺口，README 里也有说明。
 *
 * <p>词表以 {@code 文本 -> tokenId} 的形式注入，因此本类不绑定任何 tokenizer 库。
 */
public final class TokenizerSlotChecker implements SlotCheck {

    /** LETTER 风格使用的字母表，与 SemIf 的 {@code core.LETTERS} 一致。 */
    public static final String LETTERS = "ABCDEFGHIJKLMNOP";

    /** YESNO 风格使用的两个答案。 */
    public static final List<String> YES_NO = List.of("yes", "no");

    private final Map<String, Integer> vocabulary;

    /**
     * @param vocabulary 词表片段：答案文本 -&gt; token id
     */
    public TokenizerSlotChecker(Map<String, Integer> vocabulary) {
        if (vocabulary == null || vocabulary.isEmpty()) {
            throw new IllegalArgumentException("词表不能为空");
        }
        this.vocabulary = Map.copyOf(vocabulary);
    }

    @Override
    public Result check(DecisionPointLike point, String promptText) {
        List<String> expectedAnswers = expectedAnswers(point);
        if (expectedAnswers.isEmpty()) {
            return new Result(point.ref(), Status.SKIPPED,
                    List.of("未知的答案风格: " + point.answerStyle()), List.of());
        }

        List<String> failures = new ArrayList<>();
        List<Integer> slotIds = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();

        for (String answer : expectedAnswers) {
            Integer tokenId = vocabulary.get(answer);
            if (tokenId == null) {
                failures.add("答案 \"" + answer + "\" 不在词表中——该槽位无法读取");
                continue;
            }
            if (!seen.add(tokenId)) {
                failures.add("答案 \"" + answer + "\" 与其他答案共用 token id " + tokenId
                        + "——槽位冲突，概率不可区分");
                continue;
            }
            slotIds.add(tokenId);
        }

        if (failures.isEmpty()) {
            return new Result(point.ref(), Status.OK, List.of(), slotIds);
        }
        return new Result(point.ref(), Status.FAILED, failures, slotIds);
    }

    /** 按答案风格给出期望的答案文本列表。 */
    private static List<String> expectedAnswers(DecisionPointLike point) {
        String style = point.answerStyle();
        if ("LETTER".equalsIgnoreCase(style)) {
            int count = point.optionCount();
            if (count < 1 || count > LETTERS.length()) {
                return List.of();
            }
            List<String> answers = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                answers.add(String.valueOf(LETTERS.charAt(i)));
            }
            return answers;
        }
        if ("YESNO".equalsIgnoreCase(style)) {
            return YES_NO;
        }
        return List.of();
    }

    /**
     * 本实现未覆盖的检查项——必须在文档与启动报告中显式声明，
     * 避免读者误以为「槽位检查通过」等于「完整契约通过」。
     */
    public static List<String> uncoveredChecks() {
        return List.of(
                "prompt 末尾追加答案字母后，已有 token 序列是否保持不变（需要真实 tokenizer 编码 prompt）",
                "模型词表与本地词表是否一致（需要读取模型 config）");
    }
}
