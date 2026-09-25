package com.semif.gate.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DecisionPoint} 与 {@link Option} 的构造校验。
 */
class DecisionPointContractTest {

    private static final String TEMPLATE =
            "{\"evidence\": {{state}}, \"criterion\": {{question}}, \"options\": {{options}}}";

    private static List<Option> options() {
        return List.of(new Option("yes", "支持。"), new Option("no", "不支持。"));
    }

    @Test
    @DisplayName("选项描述为空 → 拒绝（空描述会让模型无法区分选项）")
    void optionRejectsBlankDescription() {
        assertThrows(IllegalArgumentException.class, () -> new Option("yes", ""));
        assertThrows(IllegalArgumentException.class, () -> new Option("yes", "   "));
        assertThrows(IllegalArgumentException.class, () -> new Option("", "描述"));
    }

    @Test
    @DisplayName("版本号必须 >= 1")
    void versionMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new DecisionPoint(
                "test.binary", 0, "owner", "2026-09-25", "问题", options(), TEMPLATE,
                AnswerStyle.LETTER, "rev", "hash"));
    }

    @Test
    @DisplayName("ref() 形如 pointId@version，且是缓存键的第一分量")
    void refIsPointIdAtVersion() {
        DecisionPoint point = new DecisionPoint("test.binary", 3, "owner", "2026-09-25",
                "问题", options(), TEMPLATE, AnswerStyle.LETTER, "rev", "hash");

        assertEquals("test.binary@3", point.ref());
    }

    @Test
    @DisplayName("withModelRevision 保留其余字段，只替换 revision")
    void withModelRevisionKeepsOtherFields() {
        DecisionPoint point = new DecisionPoint("test.binary", 1, "owner", "2026-09-25",
                "问题", options(), TEMPLATE, AnswerStyle.LETTER, "old-rev", "hash");
        DecisionPoint replaced = point.withModelRevision("new-rev");

        assertEquals("new-rev", replaced.modelRevision());
        assertEquals(point.ref(), replaced.ref());
        assertEquals(point.optionsSha256(), replaced.optionsSha256());
        assertEquals(point.promptTemplate(), replaced.promptTemplate());
    }

    @Test
    @DisplayName("YESNO 风格最多 2 个选项，LETTER 风格最多 16 个")
    void answerStyleMaxOptions() {
        assertEquals(16, AnswerStyle.LETTER.maxOptions());
        assertEquals(2, AnswerStyle.YESNO.maxOptions());
    }
}
