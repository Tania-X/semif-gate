package com.semif.gate.registry;

import com.semif.gate.contract.AnswerStyle;
import com.semif.gate.contract.DecisionPoint;
import com.semif.gate.contract.Option;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验收测试 6：注册表校验——加载即校验，不过就拒绝启动。
 *
 * <p>每个测试对应一类真实故障：模板漏掉证据、选项 ID 重复、revision 写成 latest……
 * 它们的共同点是<b>不会在运行时抛异常</b>，只会让判定语义悄悄变化。
 */
class DecisionPointRegistryTest {

    private static final String TEMPLATE =
            "{\"evidence\": {{state}}, \"criterion\": {{question}}, \"options\": {{options}}}";
    private static final String REVISION = "851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a";

    private static List<Option> binaryOptions() {
        return List.of(
                new Option("yes", "支持。"),
                new Option("no", "不支持。"));
    }

    private static DecisionPoint point(String id, int version, List<Option> options, String template) {
        return new DecisionPoint(id, version, "test-team", "2026-09-25",
                "证据是否支持该主张？", options, template, AnswerStyle.LETTER, REVISION, "<待计算>");
    }

    private static DecisionPoint validPoint(String id) {
        return point(id, 1, binaryOptions(), TEMPLATE);
    }

    // ---------------------------------------------------------------- 正常路径

    @Test
    @DisplayName("合法判定点可以加载，并补齐 optionsSha256")
    void validRegistryLoads() {
        DecisionPointRegistry registry = DecisionPointRegistry.of(List.of(validPoint("test.binary")));

        DecisionPoint loaded = registry.require("test.binary@1");
        assertEquals(1, registry.size());
        assertTrue(loaded.optionsSha256().matches("[0-9a-f]{64}"),
                "加载后必须补齐 optionsSha256，它要进缓存键");
        assertEquals(64, registry.registrySha256().length());
    }

    @Test
    @DisplayName("require 支持省略版本号，但同 ID 多版本时必须显式指定")
    void requireResolvesRefs() {
        DecisionPointRegistry single = DecisionPointRegistry.of(List.of(validPoint("test.binary")));
        assertEquals("test.binary@1", single.require("test.binary").ref());
        assertThrows(RegistryException.class, () -> single.require("test.missing"));

        DecisionPointRegistry multi = DecisionPointRegistry.of(List.of(
                validPoint("test.binary"), point("test.binary", 2, binaryOptions(), TEMPLATE)));
        assertEquals("test.binary@2", multi.require("test.binary@2").ref());
        RegistryException error = assertThrows(RegistryException.class,
                () -> multi.require("test.binary"));
        assertTrue(error.getMessage().contains("多个版本"), "同 ID 多版本时必须要求显式版本号");
    }

    @Test
    @DisplayName("从 classpath 资源加载（主资源与测试夹具）")
    void loadsFromClasspath() {
        DecisionPointRegistry main = DecisionPointRegistry.loadFromClasspath("decision-points/ticket.route.json");
        assertEquals(2, main.size());
        assertEquals(4, main.require("ticket.route@1").options().size());
        assertEquals(3, main.require("alert.is_real@1").options().size());

        DecisionPointRegistry fixture = DecisionPointRegistry.loadFromClasspath("decision-points/valid-binary.json");
        assertEquals(1, fixture.size());
    }

    // ---------------------------------------------------------------- 验收 6a：模板改动 → 哈希变

    @Test
    @DisplayName("验收6a：模板被改动 → registrySha256 与 prompt 哈希都变")
    void templateChangeChangesHashes() {
        DecisionPoint original = validPoint("test.binary");
        DecisionPointRegistry first = DecisionPointRegistry.of(List.of(original));

        // 只加一个空格——最容易被忽视的「无害改动」
        String tweakedTemplate = TEMPLATE.replace("\"criterion\"", "\"criterion \"");
        DecisionPoint tweaked = point("test.binary", 1, binaryOptions(), tweakedTemplate);
        DecisionPointRegistry second = DecisionPointRegistry.of(List.of(tweaked));

        assertNotEquals(first.registrySha256(), second.registrySha256(),
                "模板改动必须改变注册表哈希，否则缓存会继续返回旧提示词的判定");
        assertNotEquals(first.probePromptSha256("test.binary"), second.probePromptSha256("test.binary"),
                "渲染后的 prompt 哈希也必须变化");
    }

    @Test
    @DisplayName("验收6a补充：选项描述改动 → optionsSha256 与注册表哈希都变")
    void optionDescriptionChangeChangesHashes() {
        DecisionPointRegistry first = DecisionPointRegistry.of(List.of(validPoint("test.binary")));

        List<Option> changed = List.of(
                new Option("yes", "强烈支持。"),
                new Option("no", "不支持。"));
        DecisionPointRegistry second = DecisionPointRegistry.of(
                List.of(point("test.binary", 1, changed, TEMPLATE)));

        assertNotEquals(first.require("test.binary").optionsSha256(),
                second.require("test.binary").optionsSha256());
        assertNotEquals(first.registrySha256(), second.registrySha256());
    }

    @Test
    @DisplayName("选项顺序变化 → optionsSha256 必须改变（顺序敏感，机制上防旧语义复用）")
    void optionOrderChangesOptionsHash() {
        DecisionPointRegistry first = DecisionPointRegistry.of(List.of(validPoint("test.binary")));
        List<Option> reversed = new ArrayList<>(binaryOptions());
        java.util.Collections.reverse(reversed);
        DecisionPointRegistry second = DecisionPointRegistry.of(
                List.of(point("test.binary", 1, reversed, TEMPLATE)));

        // 顺序是语义的一部分：SemIf 的答案字母按选项【下标】分配，
        // 顺序一变，字母 ↔ 选项映射就变，prompt 文本随之改变。
        // 真实数据实测：顺序改变导致 30.6% 的判定翻转，而换 GPU 只有 0.7%。
        assertNotEquals(first.require("test.binary").optionsSha256(),
                second.require("test.binary").optionsSha256(),
                "调换选项顺序必须改变 optionsSha256，否则缓存会静默命中旧语义结果");
        assertNotEquals(first.registrySha256(), second.registrySha256(),
                "注册表哈希也必须随之改变");
        assertNotEquals(first.probePromptSha256("test.binary"),
                second.probePromptSha256("test.binary"),
                "渲染出的 prompt 不同（字母与描述配对变了）");
    }

    @Test
    @DisplayName("顺序敏感是逐位的：只交换前两个选项也会改变哈希")
    void swappingAdjacentOptionsChangesHash() {
        // 三选项、只交换前两个——最容易被误认为「无影响」的情形
        List<Option> triple = List.of(
                new Option("one", "第一个。"),
                new Option("two", "第二个。"),
                new Option("three", "第三个。"));
        List<Option> swapped = List.of(triple.get(1), triple.get(0), triple.get(2));

        DecisionPointRegistry original = DecisionPointRegistry.of(
                List.of(point("test.triple", 1, triple, TEMPLATE)));
        DecisionPointRegistry reordered = DecisionPointRegistry.of(
                List.of(point("test.triple", 1, swapped, TEMPLATE)));

        assertNotEquals(original.require("test.triple").optionsSha256(),
                reordered.require("test.triple").optionsSha256(),
                "只交换前两个选项也必须改变选项集哈希");
        assertNotEquals(original.probePromptSha256("test.triple"),
                reordered.probePromptSha256("test.triple"),
                "字母与描述的配对变了，prompt 必然不同");
    }

    // ---------------------------------------------------------------- 验收 6b：选项重复

    @Test
    @DisplayName("验收6b：选项 ID 重复 → 加载失败")
    void duplicateOptionIdFailsLoading() {
        List<Option> duplicated = List.of(
                new Option("yes", "支持。"),
                new Option("yes", "另一段描述。"));

        RegistryException error = assertThrows(RegistryException.class,
                () -> DecisionPointRegistry.of(List.of(point("test.binary", 1, duplicated, TEMPLATE))));
        assertTrue(error.getMessage().contains("重复"), "错误信息应指出重复: " + error.getMessage());
    }

    // ---------------------------------------------------------------- 验收 6c：选项数越界

    @Test
    @DisplayName("验收6c：选项数越界 → 加载失败（少于 2 或多于风格上限）")
    void optionCountOutOfRangeFailsLoading() {
        List<Option> single = List.of(new Option("yes", "只有一项。"));
        RegistryException tooFew = assertThrows(RegistryException.class,
                () -> DecisionPointRegistry.of(List.of(point("test.binary", 1, single, TEMPLATE))));
        assertTrue(tooFew.getMessage().contains("少于 2"), tooFew.getMessage());

        List<Option> seventeen = new ArrayList<>();
        for (int i = 0; i < 17; i++) {
            seventeen.add(new Option("option_" + i, "描述 " + i));
        }
        RegistryException tooMany = assertThrows(RegistryException.class,
                () -> DecisionPointRegistry.of(List.of(point("test.binary", 1, seventeen, TEMPLATE))));
        assertTrue(tooMany.getMessage().contains("上限 16"), tooMany.getMessage());
    }

    @Test
    @DisplayName("验收6c补充：YESNO 风格超过 2 个选项 → 加载失败")
    void yesNoStyleRejectsMoreThanTwoOptions() {
        DecisionPoint yesNo = new DecisionPoint("test.yesno", 1, "test-team", "2026-09-25",
                "证据是否支持该主张？", List.of(
                        new Option("a", "第一项。"),
                        new Option("b", "第二项。"),
                        new Option("c", "第三项。")),
                TEMPLATE, AnswerStyle.YESNO, REVISION, "<待计算>");

        RegistryException error = assertThrows(RegistryException.class,
                () -> DecisionPointRegistry.of(List.of(yesNo)));
        assertTrue(error.getMessage().contains("上限 2"), error.getMessage());
    }

    // ---------------------------------------------------------------- 验收 6d：浮动 revision

    @Test
    @DisplayName("验收6d：revision=latest → 加载失败（含各种浮动写法）")
    void floatingRevisionFailsLoading() {
        for (String floating : List.of("latest", "LATEST", "Latest", "head", "main", "master", "stable")) {
            DecisionPoint bad = new DecisionPoint("test.binary", 1, "test-team", "2026-09-25",
                    "证据是否支持该主张？", binaryOptions(), TEMPLATE,
                    AnswerStyle.LETTER, floating, "<待计算>");

            RegistryException error = assertThrows(RegistryException.class,
                    () -> DecisionPointRegistry.of(List.of(bad)),
                    "revision=" + floating + " 必须被拒绝");
            assertTrue(error.getMessage().contains("浮动 revision"), error.getMessage());
        }
    }

    @Test
    @DisplayName("验收6d补充：固定的 commit 哈希与显式本地标签可以加载")
    void pinnedRevisionsAreAccepted() {
        DecisionPointRegistry.of(List.of(validPoint("test.binary")));

        DecisionPoint local = new DecisionPoint("test.local", 1, "test-team", "2026-09-25",
                "证据是否支持该主张？", binaryOptions(), TEMPLATE,
                AnswerStyle.LETTER, "local:sha256-abc123", "<待计算>");
        assertEquals(1, DecisionPointRegistry.of(List.of(local)).size());
    }

    // ---------------------------------------------------------------- 占位符

    @Test
    @DisplayName("模板缺少 {{state}} → 加载失败（模型看不到证据，且不会报错）")
    void missingStatePlaceholderFailsLoading() {
        String noState = "{\"criterion\": {{question}}, \"options\": {{options}}}";
        RegistryException error = assertThrows(RegistryException.class,
                () -> DecisionPointRegistry.of(List.of(point("test.binary", 1, binaryOptions(), noState))));
        assertTrue(error.getMessage().contains("缺少必需占位符"), error.getMessage());
        assertTrue(error.getMessage().contains("state"), error.getMessage());
    }

    @Test
    @DisplayName("模板含未知占位符 → 加载失败")
    void unknownPlaceholderFailsLoading() {
        String withUnknown = TEMPLATE + "{{timestamp}}";
        RegistryException error = assertThrows(RegistryException.class,
                () -> DecisionPointRegistry.of(List.of(point("test.binary", 1, binaryOptions(), withUnknown))));
        assertTrue(error.getMessage().contains("未知占位符"), error.getMessage());
    }

    @Test
    @DisplayName("渲染确定性：同一输入两次渲染哈希一致；模板不同则哈希不同")
    void renderingIsDeterministic() {
        DecisionPoint point = validPoint("test.binary");
        String probe = "{\"__probe__\":\"determinism-check\"}";

        String first = PromptRenderer.render(point, probe);
        String second = PromptRenderer.render(point, probe);
        assertEquals(first, second);
        assertEquals(PromptRenderer.assertDeterministic(point),
                com.semif.gate.state.StateHasher.sha256Hex(first));
    }

    @Test
    @DisplayName("渲染结果包含证据、准则与带字母的选项列表")
    void renderingIncludesAllParts() {
        DecisionPoint point = validPoint("test.binary");
        String rendered = PromptRenderer.render(point, "{\"service\":\"checkout\"}");

        assertTrue(rendered.contains("\"service\":\"checkout\""), "必须包含证据");
        assertTrue(rendered.contains("证据是否支持该主张？"), "必须包含准则");
        assertTrue(rendered.contains("\"letter\": \"A\""), "必须包含字母 A（SemIf 格式，带空格）");
        assertTrue(rendered.contains("\"letter\": \"B\""), "必须包含字母 B（SemIf 格式，带空格）");
        assertTrue(rendered.contains("支持。"), "必须包含选项描述");
    }

    // ---------------------------------------------------------------- 其它校验

    @Test
    @DisplayName("判定点 ID 形态非法 → 加载失败")
    void invalidIdShapeFailsLoading() {
        // 能构造出对象、但形态不合法的 ID：由注册表拒绝
        for (String badId : List.of("Test.Binary", "1test", "test binary", "test@binary", "test/Binary")) {
            assertThrows(RegistryException.class,
                    () -> DecisionPointRegistry.of(List.of(validPoint(badId))),
                    "ID 形态非法应被拒绝: " + badId);
        }
    }

    @Test
    @DisplayName("空 ID → 在契约构造阶段即被拒绝")
    void blankIdIsRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> validPoint(""));
        assertThrows(IllegalArgumentException.class, () -> validPoint("   "));
    }

    @Test
    @DisplayName("ref 重复（同 ID 同版本）→ 加载失败")
    void duplicateRefFailsLoading() {
        RegistryException error = assertThrows(RegistryException.class,
                () -> DecisionPointRegistry.of(List.of(validPoint("test.binary"), validPoint("test.binary"))));
        assertTrue(error.getMessage().contains("重复"), error.getMessage());
    }

    @Test
    @DisplayName("空注册表 → 加载失败")
    void emptyRegistryFailsLoading() {
        assertThrows(RegistryException.class, () -> DecisionPointRegistry.of(List.of()));
        assertThrows(RegistryException.class, () -> DecisionPointRegistry.of(null));
    }

    @Test
    @DisplayName("资源缺少 modelRevision 且无文件级默认值 → 加载失败")
    void missingRevisionFailsLoading() {
        String json = """
                {
                  "points": [
                    { "id": "test.binary", "version": 1, "owner": "t", "frozenAt": "2026-09-25",
                      "question": "问题", "answerStyle": "LETTER",
                      "options": [ {"id":"yes","description":"是。"}, {"id":"no","description":"否。"} ],
                      "template": "%s" }
                  ]
                }
                """.formatted(TEMPLATE.replace("\"", "\\\""));

        RegistryException error = assertThrows(RegistryException.class,
                () -> DecisionPointLoader.loadFromJson(json, "inline"));
        assertTrue(error.getMessage().contains("modelRevision"), error.getMessage());
    }

    @Test
    @DisplayName("describe() 输出包含每个判定点的关键信息，便于启动日志")
    void describeIsInformative() {
        DecisionPointRegistry registry = DecisionPointRegistry.loadFromClasspath("decision-points/ticket.route.json");
        String text = registry.describe();

        assertTrue(text.contains("ticket.route@1"));
        assertTrue(text.contains("alert.is_real@1"));
        assertTrue(text.contains("options=4"));
        assertTrue(text.contains("registrySha256="));
    }

    // ---------------------------------------------------------------- 启动自检

    @Test
    @DisplayName("槽位检查通过时 verifyOrRefuseStartup 返回报告")
    void startupVerificationPasses() {
        DecisionPointRegistry registry = DecisionPointRegistry.loadFromClasspath("decision-points/ticket.route.json");
        Map<String, Integer> vocabulary = Map.ofEntries(
                Map.entry("A", 32), Map.entry("B", 33), Map.entry("C", 34), Map.entry("D", 35));

        SlotCheckReport report = registry.verifyOrRefuseStartup(new TokenizerSlotChecker(vocabulary));

        assertTrue(report.passedCount() == 2, report.summary());
        assertEquals(0, report.failedCount());
        assertTrue(report.summary().contains("通过 2"));
    }

    @Test
    @DisplayName("槽位检查失败时拒绝启动，且异常信息给出具体判定点与原因")
    void startupVerificationRefusesOnFailure() {
        DecisionPointRegistry registry = DecisionPointRegistry.loadFromClasspath("decision-points/ticket.route.json");
        // 词表缺少 C、D —— 模拟字母被切成多 token 或不在词表里的情况
        Map<String, Integer> incompleteVocabulary = Map.of("A", 32, "B", 33);

        RegistryException error = assertThrows(RegistryException.class,
                () -> registry.verifyOrRefuseStartup(new TokenizerSlotChecker(incompleteVocabulary)));

        assertTrue(error.getMessage().contains("拒绝启动"), error.getMessage());
        assertTrue(error.getMessage().contains("ticket.route@1"), error.getMessage());
        assertTrue(error.getMessage().contains("C"), "应指出具体是哪个答案出了问题");
    }
}
