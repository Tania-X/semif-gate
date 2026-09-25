package com.semif.gate.state;

import com.semif.gate.contract.AnswerStyle;
import com.semif.gate.contract.DecisionPoint;
import com.semif.gate.contract.Option;
import com.semif.gate.contract.Provenance;
import com.semif.gate.registry.RegistryHasher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验收测试 5：DecisionKey 完整性。
 *
 * <p>这个测试类逐项验证「漏掉任何一个分量都会导致缓存串味」。
 * 每一项失败都对应一类真实事故，注释里写明了后果。
 */
class DecisionKeyTest {

    private static final String REVISION = "851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a";
    private static final String TEMPLATE =
            "{\"evidence\": {{state}}, \"criterion\": {{question}}, \"options\": {{options}}}";

    private static final DecisionState STATE = new StateNormalizer(
            java.util.Set.of("service", "duration_s"))
            .normalize(Map.of("service", "checkout", "duration_s", 480));

    private static DecisionPoint point(int version) {
        List<Option> options = List.of(
                new Option("yes", "The evidence supports the claim."),
                new Option("no", "The evidence does not support the claim."));
        return new DecisionPoint("test.binary", version, "test-team", "2026-09-25",
                "Does the evidence support the claim?", options, TEMPLATE,
                AnswerStyle.LETTER, REVISION, RegistryHasher.optionsSha256(options));
    }

    private static Provenance provenance(String providerId, String revision, String backend,
                                         String registrySha256, String optionsSha256) {
        return new Provenance(providerId, revision, backend,
                "a".repeat(64), registrySha256, optionsSha256, 42, 7L);
    }

    private static Provenance baselineProvenance(DecisionPoint point) {
        return provenance("http-vllm", REVISION, "vllm",
                "b".repeat(64), point.optionsSha256());
    }

    // ---------------------------------------------------------------- 基准

    @Test
    @DisplayName("验收5：相同输入 → 相同 key（确定性）")
    void sameInputsProduceSameKey() {
        DecisionPoint point = point(1);
        Provenance provenance = baselineProvenance(point);

        assertEquals(
                DecisionKey.of(STATE, point, provenance),
                DecisionKey.of(STATE, point, provenance));
    }

    // ---------------------------------------------------------------- 逐项敏感性

    @Test
    @DisplayName("验收5a：pointVersion 改变 → key 必须变（改语义必须升版本）")
    void pointVersionChangesKey() {
        DecisionPoint v1 = point(1);
        DecisionPoint v2 = point(2);
        Provenance provenance = baselineProvenance(v1);

        assertNotEquals(v1.ref(), v2.ref());
        assertNotEquals(DecisionKey.of(STATE, v1, provenance), DecisionKey.of(STATE, v2, provenance),
                "版本是契约的一部分：不升版本就改语义，缓存会静默返回旧结果");
    }

    @Test
    @DisplayName("验收5b：pointId 改变 → key 必须变")
    void pointIdChangesKey() {
        DecisionPoint base = point(1);
        DecisionPoint renamed = new DecisionPoint("test.other", base.version(), base.owner(),
                base.frozenAt(), base.question(), base.options(), base.promptTemplate(),
                base.answerStyle(), base.modelRevision(), base.optionsSha256());
        Provenance provenance = baselineProvenance(base);

        assertNotEquals(DecisionKey.of(STATE, base, provenance),
                DecisionKey.of(STATE, renamed, provenance));
    }

    @Test
    @DisplayName("验收5c：optionsSha256 改变 → key 必须变（选项描述变了）")
    void optionsHashChangesKey() {
        DecisionPoint base = point(1);
        List<Option> changedOptions = List.of(
                new Option("yes", "The evidence strongly supports the claim."),
                new Option("no", "The evidence does not support the claim."));
        DecisionPoint changed = new DecisionPoint(base.id(), base.version(), base.owner(),
                base.frozenAt(), base.question(), changedOptions, base.promptTemplate(),
                base.answerStyle(), base.modelRevision(), RegistryHasher.optionsSha256(changedOptions));

        assertNotEquals(base.optionsSha256(), changed.optionsSha256(), "选项描述变了，选项集哈希应变化");
        assertNotEquals(
                DecisionKey.of(STATE, base, baselineProvenance(base)),
                DecisionKey.of(STATE, changed, baselineProvenance(changed)),
                "选项集变了但 key 不变，旧的概率分布会被当成新选项集的答案");
    }

    @Test
    @DisplayName("验收5d：modelRevision 改变 → key 必须变（换模型）")
    void modelRevisionChangesKey() {
        DecisionPoint point = point(1);

        assertNotEquals(
                DecisionKey.of(STATE, point, provenance("http-vllm", REVISION, "vllm", "b".repeat(64), point.optionsSha256())),
                DecisionKey.of(STATE, point, provenance("http-vllm", "0".repeat(40), "vllm", "b".repeat(64), point.optionsSha256())),
                "换模型后复用旧缓存，等于用旧模型的判定冒充新模型");
    }

    @Test
    @DisplayName("验收5e：backend 改变 → key 必须变（数值实现不同）")
    void backendChangesKey() {
        DecisionPoint point = point(1);

        assertNotEquals(
                DecisionKey.of(STATE, point, provenance("http-vllm", REVISION, "vllm", "b".repeat(64), point.optionsSha256())),
                DecisionKey.of(STATE, point, provenance("http-vllm", REVISION, "ollama", "b".repeat(64), point.optionsSha256())),
                "不同后端的数值实现不同（已实测会出现概率偏移），不能共用缓存");
    }

    @Test
    @DisplayName("验收5f：providerId 改变 → key 必须变（切换 provider）")
    void providerIdChangesKey() {
        DecisionPoint point = point(1);

        assertNotEquals(
                DecisionKey.of(STATE, point, provenance("http-vllm", REVISION, "vllm", "b".repeat(64), point.optionsSha256())),
                DecisionKey.of(STATE, point, provenance("semif-reference", REVISION, "vllm", "b".repeat(64), point.optionsSha256())),
                "provider 切换后结果混在一起，就无法做漂移对比");
    }

    @Test
    @DisplayName("验收5g：registrySha256 改变 → key 必须变（模板被改动）")
    void registryHashChangesKey() {
        DecisionPoint point = point(1);

        assertNotEquals(
                DecisionKey.of(STATE, point, provenance("http-vllm", REVISION, "vllm", "b".repeat(64), point.optionsSha256())),
                DecisionKey.of(STATE, point, provenance("http-vllm", REVISION, "vllm", "c".repeat(64), point.optionsSha256())),
                "模板文字改动必须让缓存失效，否则新模板的判定会与旧的混在一张表里");
    }

    @Test
    @DisplayName("验收5h：state 改变 → key 必须变")
    void stateChangesKey() {
        DecisionPoint point = point(1);
        Provenance provenance = baselineProvenance(point);
        DecisionState otherState = new StateNormalizer(java.util.Set.of("service", "duration_s"))
                .normalize(Map.of("service", "payments", "duration_s", 480));

        assertNotEquals(DecisionKey.of(STATE, point, provenance),
                DecisionKey.of(otherState, point, provenance));
    }

    // ---------------------------------------------------------------- 拼接安全

    @Test
    @DisplayName("验收5补充：分量之间用 NUL 分隔，不存在拼接歧义")
    void separatorPreventsConcatenationAmbiguity() {
        // 若分隔符是 ':' 这类可出现在分量里的字符，下面两组会拼出同一个字符串：
        //   "a:b" + ":" + "options"  ==  "a" + ":" + "b:options"
        // 用 NUL 分隔后两者不同，因此不会撞成同一个缓存键。
        String keyA = DecisionKey.of(
                "hash", "a:b", "options", "rev", "backend", "provider", "registry");
        String keyB = DecisionKey.of(
                "hash", "a", "b:options", "rev", "backend", "provider", "registry");

        assertNotEquals(keyA, keyB,
                "分隔符必须是不可能出现在分量里的字符，否则不同判定会撞成同一个缓存键");

        // 对照：若用 ':' 做分隔，两个不同的分量组合会得到完全相同的拼接串
        String withColonA = String.join(":", "hash", "a:b", "options");
        String withColonB = String.join(":", "hash", "a", "b:options");
        assertEquals(withColonA, withColonB,
                "这一行证明：可出现在分量中的分隔符会造成拼接歧义");
    }

    // ---------------------------------------------------------------- 缺分量即拒绝

    @Test
    @DisplayName("验收5补充：任何分量为空 → 拒绝构造，而不是产出过宽的 key")
    void blankComponentIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> DecisionKey.of(
                "hash", "ref", "options", "rev", "backend", "provider", ""));
        assertThrows(IllegalArgumentException.class, () -> DecisionKey.of(
                "hash", "ref", "options", "rev", "backend", "  ", "registry"));
        assertThrows(IllegalArgumentException.class, () -> DecisionKey.of(
                "", "ref", "options", "rev", "backend", "provider", "registry"));
    }

    @Test
    @DisplayName("验收5补充：key 是 64 位十六进制（可直接作为审计表主键）")
    void keyIsSha256Hex() {
        DecisionPoint point = point(1);
        String key = DecisionKey.of(STATE, point, baselineProvenance(point));

        assertEquals(64, key.length());
        assertEquals(key.toLowerCase(), key);
        assertEquals(true, key.matches("[0-9a-f]{64}"));
    }
}
