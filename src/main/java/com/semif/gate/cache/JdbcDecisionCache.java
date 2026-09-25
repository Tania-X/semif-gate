package com.semif.gate.cache;

import com.semif.gate.contract.Decision;
import com.semif.gate.contract.DecisionPoint;
import com.semif.gate.contract.OptionScore;
import com.semif.gate.contract.Provenance;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 基于 JDBC 的持久缓存——跨进程重启保留判定结果。
 *
 * <p><b>连接由外部注入</b>（{@link DataSource}），库里不硬编码任何连接信息。
 * 这样换连接池、换数据库、在测试里换成内存库都不需要改这个类。
 *
 * <p>表结构见 {@code db/V1__init.sql} 的 {@code decision_cache}。
 * 写入用 upsert（{@code ON CONFLICT}），因为同一个查找键会随模型升级被重复写入，
 * 后写的条目带新的 {@code registrySha256}，从而让旧条目在下次校验时自然失配——
 * 这正是我们要的行为：<b>失效由契约校验负责，而不是靠删除</b>。
 */
public final class JdbcDecisionCache implements DecisionCache {

    private final DataSource dataSource;

    public JdbcDecisionCache(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource 不能为空");
        }
        this.dataSource = dataSource;
    }

    @Override
    public Optional<CachedDecision> lookup(String stateHash, DecisionPoint point, String registrySha256) {
        String key = DecisionCache.lookupKey(stateHash, point);
        String sql = "SELECT decision_id, distribution, outcome, degraded_reason,"
                + " provider_id, model_revision, backend, prompt_sha256,"
                + " registry_sha256, options_sha256, input_tokens, latency_ms"
                + " FROM decision_cache WHERE lookup_key = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                CachedDecision entry = read(rows);
                // 与内存实现同一条纪律：契约指纹不符一律视为未命中。
                if (!entry.matchesContract(registrySha256, point)) {
                    return Optional.empty();
                }
                return Optional.of(entry);
            }
        } catch (SQLException e) {
            // 缓存故障不能升级成判定故障：读不到就当作未命中，让上层去调 provider。
            throw new CacheAccessException("读取判定缓存失败", e);
        }
    }

    @Override
    public void store(String stateHash, DecisionPoint point, Decision decision, String registrySha256) {
        String key = DecisionCache.lookupKey(stateHash, point);
        String sql = "INSERT INTO decision_cache (lookup_key, state_hash, point_ref, decision_id,"
                + " distribution, outcome, degraded_reason, provider_id, model_revision, backend,"
                + " prompt_sha256, registry_sha256, options_sha256, input_tokens, latency_ms,"
                + " stored_at)"
                + " VALUES (?,?,?,?,?::jsonb,?,?,?,?,?,?,?,?,?,?, now())"
                + " ON CONFLICT (lookup_key) DO UPDATE SET"
                + " decision_id = EXCLUDED.decision_id,"
                + " distribution = EXCLUDED.distribution,"
                + " outcome = EXCLUDED.outcome,"
                + " degraded_reason = EXCLUDED.degraded_reason,"
                + " provider_id = EXCLUDED.provider_id,"
                + " model_revision = EXCLUDED.model_revision,"
                + " backend = EXCLUDED.backend,"
                + " prompt_sha256 = EXCLUDED.prompt_sha256,"
                + " registry_sha256 = EXCLUDED.registry_sha256,"
                + " options_sha256 = EXCLUDED.options_sha256,"
                + " input_tokens = EXCLUDED.input_tokens,"
                + " latency_ms = EXCLUDED.latency_ms,"
                + " stored_at = now()";
        Provenance provenance = decision.provenance();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setString(2, stateHash);
            statement.setString(3, point.ref());
            statement.setString(4, decision.decisionId());
            statement.setString(5, encodeDistribution(decision.distribution()));
            statement.setString(6, decision.outcome().name());
            statement.setString(7, decision.degradedReason());
            statement.setString(8, provenance.providerId());
            statement.setString(9, provenance.modelRevision());
            statement.setString(10, provenance.backend());
            statement.setString(11, provenance.promptSha256());
            statement.setString(12, registrySha256);
            statement.setString(13, provenance.optionsSha256());
            statement.setInt(14, provenance.inputTokens());
            statement.setLong(15, provenance.latencyMs());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new CacheAccessException("写入判定缓存失败", e);
        }
    }

    private static CachedDecision read(ResultSet rows) throws SQLException {
        Provenance provenance = new Provenance(
                rows.getString("provider_id"),
                rows.getString("model_revision"),
                rows.getString("backend"),
                rows.getString("prompt_sha256"),
                rows.getString("registry_sha256"),
                rows.getString("options_sha256"),
                rows.getInt("input_tokens"),
                rows.getLong("latency_ms"));
        return new CachedDecision(
                rows.getString("decision_id"),
                decodeDistribution(rows.getString("distribution")),
                Decision.Outcome.valueOf(rows.getString("outcome")),
                rows.getString("degraded_reason"),
                provenance,
                rows.getString("registry_sha256"),
                rows.getString("options_sha256"));
    }

    /**
     * 把分布编码成 JSON 对象——按 optionId 字典序，保证同一内容编码结果稳定。
     *
     * <p>用对象而不是数组：数组会隐含位置语义，而选项顺序恰恰是我们要消除的变量。
     */
    static String encodeDistribution(List<OptionScore> distribution) {
        List<OptionScore> sorted = new ArrayList<>(distribution);
        sorted.sort((a, b) -> a.optionId().compareTo(b.optionId()));
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            OptionScore score = sorted.get(i);
            json.append('"').append(escape(score.optionId())).append("\":")
                    .append(score.probability());
        }
        return json.append('}').toString();
    }

    /** 解析 {@link #encodeDistribution} 产出的 JSON 对象。 */
    static List<OptionScore> decodeDistribution(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("分布字段为空");
        }
        String body = json.trim();
        if (!body.startsWith("{") || !body.endsWith("}")) {
            throw new IllegalArgumentException("分布字段不是 JSON 对象: " + json);
        }
        body = body.substring(1, body.length() - 1).trim();
        if (body.isEmpty()) {
            return List.of();
        }
        List<OptionScore> scores = new ArrayList<>();
        for (String pair : body.split(",")) {
            int colon = pair.lastIndexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException("分布字段格式错误: " + pair);
            }
            String id = unescape(pair.substring(0, colon).trim());
            double probability = Double.parseDouble(pair.substring(colon + 1).trim());
            scores.add(new OptionScore(id, probability));
        }
        return List.copyOf(scores);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String literal) {
        String body = literal;
        if (body.startsWith("\"") && body.endsWith("\"") && body.length() >= 2) {
            body = body.substring(1, body.length() - 1);
        }
        return body.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
