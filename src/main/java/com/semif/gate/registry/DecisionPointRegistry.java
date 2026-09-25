package com.semif.gate.registry;

import com.semif.gate.contract.AnswerStyle;
import com.semif.gate.contract.DecisionPoint;
import com.semif.gate.contract.Option;
import com.semif.gate.state.StateHasher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 判定点注册表——所有判定点契约的唯一来源。
 *
 * <p><b>加载即校验，任一不过就拒绝启动。</b>这不是洁癖：契约错误（模板漏了证据、
 * 选项 ID 重复、revision 写成 {@code latest}）造成的故障形态是
 * 「系统正常运行但判定语义悄悄变了」，在生产上极难定位。
 * 让它在启动阶段响亮地失败，代价最小。
 *
 * <h2>校验清单</h2>
 * <ol>
 *   <li>ID 形态合法（小写字母开头）</li>
 *   <li>{@code pointId@version} 不重复</li>
 *   <li>选项 ID 唯一</li>
 *   <li>选项数量在 [2, 风格上限] 之间；LETTER 为 16，YESNO 为 2</li>
 *   <li>rev 不得是浮动值</li>
 *   <li>模板占位符合法且三个占位符齐备</li>
 *   <li>模板渲染两次哈希一致（确定性）</li>
 * </ol>
 */
public final class DecisionPointRegistry {

    /** 被明确禁止的浮动 revision 值。 */
    private static final Set<String> FLOATING_REVISIONS =
            Set.of("latest", "head", "main", "master", "dev", "develop", "stable", "nightly");

    /** 判定点数量的合理上限——防止误加载整个目录造成的资源问题。 */
    private static final int MAX_POINTS = 512;

    private final Map<String, DecisionPoint> byRef;
    private final String registrySha256;

    private DecisionPointRegistry(Map<String, DecisionPoint> byRef, String registrySha256) {
        this.byRef = Collections.unmodifiableMap(byRef);
        this.registrySha256 = registrySha256;
    }

    /**
     * 构造并校验注册表。
     *
     * @param points 判定点列表
     * @return 通过全部校验的注册表
     * @throws RegistryException 任一项校验失败
     */
    public static DecisionPointRegistry of(List<DecisionPoint> points) {
        if (points == null || points.isEmpty()) {
            throw new RegistryException("注册表不能为空——没有判定点的网关没有任何意义");
        }
        if (points.size() > MAX_POINTS) {
            throw new RegistryException("判定点数量 " + points.size() + " 超过上限 " + MAX_POINTS);
        }

        TreeMap<String, DecisionPoint> validated = new TreeMap<>();
        for (DecisionPoint point : points) {
            DecisionPoint prepared = prepare(point);
            DecisionPoint previous = validated.put(prepared.ref(), prepared);
            if (previous != null) {
                throw new RegistryException("判定点引用重复: " + prepared.ref());
            }
        }
        return new DecisionPointRegistry(validated, RegistryHasher.registrySha256(new ArrayList<>(validated.values())));
    }

    /** 从 classpath 资源加载并校验。 */
    public static DecisionPointRegistry loadFromClasspath(String resourcePath) {
        return of(DecisionPointLoader.loadFromClasspath(resourcePath));
    }

    /**
     * 对单个判定点做全部校验，并补齐派生字段（{@code optionsSha256}）。
     *
     * @throws RegistryException 校验失败
     */
    static DecisionPoint prepare(DecisionPoint point) {
        DecisionPointLoader.requireValidIdShape(point.id(), point.ref());

        if (point.owner() == null || point.owner().isBlank()) {
            throw new RegistryException("判定点 " + point.ref() + " 缺少 owner");
        }
        if (point.frozenAt() == null || point.frozenAt().isBlank()) {
            throw new RegistryException("判定点 " + point.ref() + " 缺少 frozenAt");
        }
        requirePinnedRevision(point.ref(), point.modelRevision());
        requireOptionCount(point);

        // 补齐 optionsSha256（校验选项 ID 唯一性）
        String optionsHash = RegistryHasher.optionsSha256(point.options());
        DecisionPoint prepared = new DecisionPoint(
                point.id(), point.version(), point.owner(), point.frozenAt(), point.question(),
                point.options(), point.promptTemplate(), point.answerStyle(),
                point.modelRevision(), optionsHash);

        // 模板占位符 + 渲染确定性
        PromptRenderer.requireNoUnknownPlaceholders(prepared);
        PromptRenderer.assertDeterministic(prepared);
        return prepared;
    }

    private static void requireOptionCount(DecisionPoint point) {
        int count = point.options().size();
        if (count < 2) {
            throw new RegistryException("判定点 " + point.ref() + " 只有 " + count
                    + " 个选项——少于 2 个不构成判定");
        }
        int max = point.answerStyle().maxOptions();
        if (count > max) {
            throw new RegistryException("判定点 " + point.ref() + " 有 " + count
                    + " 个选项，超过 " + point.answerStyle() + " 风格的上限 " + max);
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Option option : point.options()) {
            if (!ids.add(option.id())) {
                throw new RegistryException("判定点 " + point.ref() + " 的选项 ID 重复: " + option.id());
            }
        }
    }

    /**
     * 拒绝浮动 revision。
     *
     * <p>为什么这是硬性要求：revision 进缓存键与审计记录。
     * 如果写 {@code latest}，同一条审计记录在不同时间指向不同的模型权重，
     * 于是「这条判定是谁做的」这个问题永远无法回答，
     * 漂移对比也失去了基准。
     */
    private static void requirePinnedRevision(String ref, String revision) {
        String normalized = revision.trim().toLowerCase(Locale.ROOT);
        if (FLOATING_REVISIONS.contains(normalized)) {
            throw new RegistryException("判定点 " + ref + " 使用了浮动 revision \"" + revision
                    + "\"——revision 会进缓存键与审计记录，必须是不可变值");
        }
    }

    /**
     * 取一个判定点；不存在即抛异常。
     *
     * <p>刻意不返回 {@code Optional}：调用方声明了一个判定点却不存在，
     * 这是配置错误而非正常分支，应当立刻失败。
     *
     * @param ref {@code pointId@version} 或 {@code pointId}（后者要求该 ID 只有一个版本）
     */
    public DecisionPoint require(String ref) {
        DecisionPoint point = byRef.get(ref);
        if (point != null) {
            return point;
        }
        // 允许省略版本号，但仅当该 ID 只有一个版本时
        List<DecisionPoint> matches = byRef.values().stream()
                .filter(candidate -> candidate.id().equals(ref))
                .toList();
        if (matches.size() == 1) {
            return matches.get(0);
        }
        if (matches.size() > 1) {
            List<String> refs = matches.stream().map(DecisionPoint::ref).toList();
            throw new RegistryException("判定点 " + ref + " 存在多个版本，必须显式指定: " + refs);
        }
        throw new RegistryException("未注册的判定点: " + ref + "；已注册: " + new TreeSet<>(byRef.keySet()));
    }

    /** 全部判定点，按 {@code pointId@version} 排序。 */
    public List<DecisionPoint> all() {
        return List.copyOf(byRef.values());
    }

    /** 判定点数量。 */
    public int size() {
        return byRef.size();
    }

    /** 注册表整体哈希——进缓存键，任何契约改动都会让它变化。 */
    public String registrySha256() {
        return registrySha256;
    }

    /**
     * 在注册表上执行槽位检查。
     *
     * <p>只对状态为 {@code OK} 或 {@code SKIPPED} 的结果放行；
     * 只要有一个 {@code FAILED}，调用方就应当拒绝启动。
     *
     * @param checker 槽位检查器
     * @return 汇总报告
     */
    public SlotCheckReport runSlotChecks(SlotCheck checker) {
        List<SlotCheck.Result> results = new ArrayList<>(byRef.size());
        for (DecisionPoint point : byRef.values()) {
            String prompt = PromptRenderer.render(point, "{\"__probe__\":\"slot-check\"}");
            results.add(checker.check(new SlotView(point), prompt));
        }
        return new SlotCheckReport(results);
    }

    /**
     * 校验注册表并在槽位检查失败时抛出异常——这就是「拒绝启动」的具体实现。
     *
     * @throws RegistryException 槽位契约不满足
     */
    public SlotCheckReport verifyOrRefuseStartup(SlotCheck checker) {
        SlotCheckReport report = runSlotChecks(checker);
        if (report.blocksStartup()) {
            throw new RegistryException(report.toRefusalMessage());
        }
        return report;
    }

    /** 把 contract 的判定点适配成 {@link SlotCheck} 需要的最小视图。 */
    private record SlotView(DecisionPoint point) implements SlotCheck.DecisionPointLike {

        @Override
        public String ref() {
            return point.ref();
        }

        @Override
        public String answerStyle() {
            return point.answerStyle().name();
        }

        @Override
        public int optionCount() {
            return point.options().size();
        }
    }

    /** 人类可读的注册表摘要，供启动日志使用。 */
    public String describe() {
        StringBuilder text = new StringBuilder();
        text.append("判定点注册表: ").append(byRef.size()).append(" 个，registrySha256=")
                .append(registrySha256, 0, 12).append("…\n");
        for (DecisionPoint point : byRef.values()) {
            text.append("  - ").append(point.ref())
                    .append("  options=").append(point.options().size())
                    .append("  style=").append(point.answerStyle())
                    .append("  owner=").append(point.owner())
                    .append("  optionsSha256=").append(point.optionsSha256(), 0, 12).append("…")
                    .append('\n');
        }
        return text.toString();
    }

    /** 供测试与调试：某个判定点的 prompt 哈希（固定探针 state）。 */
    public String probePromptSha256(String ref) {
        DecisionPoint point = require(ref);
        String prompt = PromptRenderer.render(point, "{\"__probe__\":\"slot-check\"}");
        return StateHasher.sha256Hex(prompt);
    }

    /** 风格上限的只读访问，便于文档与测试引用。 */
    public static int maxOptionsFor(AnswerStyle style) {
        return style.maxOptions();
    }
}
