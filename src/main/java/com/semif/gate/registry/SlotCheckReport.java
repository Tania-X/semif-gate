package com.semif.gate.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 槽位检查的汇总报告。
 *
 * <p>启动自检的判定规则很简单：<b>{@link #blocksStartup()} 为真就拒绝启动。</b>
 * 只有 {@link SlotCheck.Status#FAILED} 会阻止启动；
 * {@link SlotCheck.Status#SKIPPED} 不阻止，但必须带原因出现在报告里——
 * 「跳过」和「通过」在运维上是两件完全不同的事，不能混为一谈。
 *
 * @param results 每个判定点的检查结果
 */
public record SlotCheckReport(List<SlotCheck.Result> results) {

    public SlotCheckReport {
        results = List.copyOf(results);
    }

    /** 是否存在阻止启动的失败。 */
    public boolean blocksStartup() {
        return results.stream().anyMatch(SlotCheck.Result::blocksStartup);
    }

    /** 通过的判定点数量。 */
    public long passedCount() {
        return results.stream().filter(r -> r.status() == SlotCheck.Status.OK).count();
    }

    /** 失败的判定点数量。 */
    public long failedCount() {
        return results.stream().filter(r -> r.status() == SlotCheck.Status.FAILED).count();
    }

    /** 跳过的判定点数量。 */
    public long skippedCount() {
        return results.stream().filter(r -> r.status() == SlotCheck.Status.SKIPPED).count();
    }

    /** 按判定点标识排序的失败明细，便于直接打印给运维。 */
    public Map<String, List<String>> failuresByPoint() {
        TreeMap<String, List<String>> failures = new TreeMap<>();
        for (SlotCheck.Result result : results) {
            if (result.status() == SlotCheck.Status.FAILED) {
                failures.put(result.pointRef(), result.failures());
            }
        }
        return Map.copyOf(failures);
    }

    /** 人类可读的一行摘要。 */
    public String summary() {
        return String.format("槽位检查：通过 %d，失败 %d，跳过 %d",
                passedCount(), failedCount(), skippedCount());
    }

    /**
     * 构造拒绝启动的异常消息——把失败明细全部带上。
     *
     * <p>不要只报「有 N 个失败」：运维需要知道是哪个判定点的哪个选项坏了。
     */
    public String toRefusalMessage() {
        StringBuilder message = new StringBuilder("答案槽位契约不满足，拒绝启动。\n");
        message.append(summary()).append('\n');
        failuresByPoint().forEach((ref, failures) -> {
            message.append("  - ").append(ref).append('\n');
            failures.forEach(failure -> message.append("      · ").append(failure).append('\n'));
        });
        List<String> skipped = new ArrayList<>();
        results.stream()
                .filter(r -> r.status() == SlotCheck.Status.SKIPPED)
                .forEach(r -> skipped.add(r.pointRef() + ": " + String.join("; ", r.failures())));
        if (!skipped.isEmpty()) {
            message.append("  跳过（不阻止启动，但需知晓）:\n");
            skipped.forEach(item -> message.append("      · ").append(item).append('\n'));
        }
        return message.toString();
    }
}
