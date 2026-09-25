package com.semif.gate.audit;

import java.util.ArrayList;
import java.util.List;

/**
 * 进程内审计实现——测试与单机默认。
 *
 * <p>用 {@link ArrayList} 而不是并发集合：审计的顺序本身就是信息，
 * 而且测试需要按写入顺序断言。并发场景请用 {@link JdbcDecisionAudit}。
 */
public final class InMemoryDecisionAudit implements DecisionAudit {

    private final List<DecisionRecord> records = new ArrayList<>();

    @Override
    public synchronized void record(List<DecisionRecord> batch) {
        if (batch == null) {
            throw new IllegalArgumentException("batch 不能为空");
        }
        records.addAll(batch);
    }

    /** 已记录的全部条目，按写入顺序。 */
    public synchronized List<DecisionRecord> records() {
        return List.copyOf(records);
    }

    /** 已记录条数。 */
    public synchronized int size() {
        return records.size();
    }

    /** 按判定点 ID 取记录。 */
    public synchronized List<DecisionRecord> byPointId(String pointId) {
        return records.stream().filter(r -> r.pointId().equals(pointId)).toList();
    }

    /** 清空。 */
    public synchronized void clear() {
        records.clear();
    }
}
