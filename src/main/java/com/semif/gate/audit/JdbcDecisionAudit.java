package com.semif.gate.audit;

import com.semif.gate.contract.OptionScore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 基于 JDBC 的审计落盘。
 *
 * <p>写入是<b>批量 + 单事务</b>的：一次网关调用的全部判定要么都进去，要么都不进去。
 * 部分写入会让审计表出现「半个请求」的记录，复盘时反而误导人。
 *
 * <p>失败时抛 {@link AuditAccessException} 而不是吞掉——见
 * {@link DecisionAudit} 关于两者失败语义相反的说明。
 */
public final class JdbcDecisionAudit implements DecisionAudit {

    private static final String INSERT = "INSERT INTO decision_record ("
            + " decision_id, point_id, point_version, state_hash, state_json, distribution,"
            + " argmax_option, max_probability, margin, band, policy_version, provider_id,"
            + " model_revision, backend, prompt_sha256, registry_sha256, input_tokens,"
            + " latency_ms, cache_hit, degraded_reason)"
            + " VALUES (?,?,?,?,?::jsonb,?::jsonb,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
            + " ON CONFLICT (decision_id) DO NOTHING";

    private final DataSource dataSource;

    public JdbcDecisionAudit(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource 不能为空");
        }
        this.dataSource = dataSource;
    }

    @Override
    public void record(List<DecisionRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
                for (DecisionRecord record : records) {
                    bind(statement, record);
                    statement.addBatch();
                }
                statement.executeBatch();
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new AuditAccessException("写入判定审计失败（" + records.size() + " 条）", e);
        }
    }

    private static void bind(PreparedStatement statement, DecisionRecord record) throws SQLException {
        statement.setString(1, record.decisionId());
        statement.setString(2, record.pointId());
        statement.setInt(3, record.pointVersion());
        statement.setString(4, record.stateHash());
        statement.setString(5, record.stateJson());
        statement.setString(6, encodeDistribution(record.distribution()));
        statement.setString(7, record.argmaxOption());
        statement.setDouble(8, record.maxProbability());
        statement.setDouble(9, record.margin());
        statement.setString(10, record.band().name());
        statement.setString(11, record.policyVersion());
        statement.setString(12, record.providerId());
        statement.setString(13, record.modelRevision());
        statement.setString(14, record.backend());
        statement.setString(15, record.promptSha256());
        statement.setString(16, record.registrySha256());
        statement.setInt(17, record.inputTokens());
        statement.setLong(18, record.latencyMs());
        statement.setBoolean(19, record.cacheHit());
        statement.setString(20, record.degradedReason());
    }

    private static String encodeDistribution(List<OptionScore> distribution) {
        return distribution.stream()
                .map(score -> "\"" + score.optionId().replace("\"", "\\\"")
                        + "\":" + score.probability())
                .collect(Collectors.joining(",", "{", "}"));
    }
}
