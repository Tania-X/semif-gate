package com.semif.gate.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.semif.gate.contract.Decision;
import com.semif.gate.contract.DecisionPoint;
import com.semif.gate.contract.Option;
import com.semif.gate.contract.OptionScore;
import com.semif.gate.contract.ScoredPoint;
import com.semif.gate.registry.PromptRenderer;
import com.semif.gate.registry.RegistryHasher;
import com.semif.gate.registry.SlotCheck;
import com.semif.gate.state.DecisionState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 回放 provider——把<b>真实运行 SemIf 产出的预测文件</b>当作判定来源。
 *
 * <h2>它解决什么问题</h2>
 * 网关的缓存、审计、策略、降级这些逻辑，全都不依赖模型本身。
 * 但它们又必须在<b>真实分数</b>上验证，否则测的是一堆编造的数字。
 * 回放 provider 正好填这个空：没有模型、没有 GPU、没有网络，
 * 却能拿到逐字节真实的 SemIf 输出。
 *
 * <h2>它不是通用 provider</h2>
 * 回放靠「从 state 里取一个标识字段」来对应到 fixture 记录，
 * 这个约定只对基准数据成立。因此：
 * <ul>
 *   <li>{@link #providerId()} 固定返回 {@code "replay"}，绝不复用生产 provider 的标识——
 *       否则回放结果会和真实结果混进同一个缓存命名空间，漂移对比就没法做了；</li>
 *   <li>取不到标识、或 fixture 里没有该标识时<b>返回 DEGRADED</b>，
 *       绝不静默编造一个分布；</li>
 *   <li>fixture 的选项集与判定点契约不一致时同样返回 DEGRADED 并说明差异——
 *       选项集不一致意味着这条记录根本不是在回答这个问题。</li>
 * </ul>
 *
 * <h2>两个实测踩过的坑</h2>
 * <ol>
 *   <li><b>{@code option_ids} 的顺序既不是字典序也不是概率序。</b>
 *       例如 {@code ["supported","insufficient","contradicted"]}。
 *       必须<b>先按 id 配对再排序</b>，绝不能按下标搬运——
 *       否则概率会被安到错误的选项上，而且不会有任何报错。</li>
 *   <li><b>{@code forward_seconds} 含首次调用的 CUDA 编译开销，不是稳态延迟。</b>
 *       填进 {@code latencyMs} 没问题（审计需要它），
 *       但任何「平均延迟 / p95」结论都必须来自真实计时运行，不能来自回放。</li>
 * </ol>
 */
public final class ReplayProvider implements DecisionProvider {

    /** provider 标识，固定不变——它进缓存键。 */
    public static final String PROVIDER_ID = "replay";

    /** 回放记录默认不含 backend 字段时使用的标记。 */
    public static final String DEFAULT_BACKEND = "torch";

    /** 回放记录默认使用的 state 标识字段。 */
    public static final String DEFAULT_STATE_KEY_FIELD = "id";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, JsonNode> recordsById;
    private final String stateKeyField;

    private ReplayProvider(Map<String, JsonNode> recordsById, String stateKeyField) {
        this.recordsById = Map.copyOf(recordsById);
        this.stateKeyField = stateKeyField;
    }

    /** 从 JSONL 文件加载。 */
    public static ReplayProvider fromJsonl(Path path) {
        return fromJsonl(path, DEFAULT_STATE_KEY_FIELD);
    }

    /** 从 JSONL 文件加载，并指定 state 中用于对应记录的字段名。 */
    public static ReplayProvider fromJsonl(Path path, String stateKeyField) {
        if (path == null) {
            throw new IllegalArgumentException("fixture 路径不能为空");
        }
        if (!Files.isReadable(path)) {
            throw new IllegalArgumentException("fixture 不可读: " + path);
        }
        Map<String, JsonNode> records = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode record = MAPPER.readTree(line);
                JsonNode idNode = record.get("id");
                if (idNode == null || idNode.asText().isBlank()) {
                    throw new IllegalArgumentException("fixture 记录缺少 id 字段: " + path);
                }
                String id = idNode.asText();
                if (records.put(id, record) != null) {
                    throw new IllegalArgumentException("fixture 中存在重复 id: " + id);
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("读取 fixture 失败: " + path, e);
        }
        if (records.isEmpty()) {
            throw new IllegalArgumentException("fixture 为空: " + path);
        }
        return new ReplayProvider(records, stateKeyField);
    }

    /** fixture 中的记录数。 */
    public int size() {
        return recordsById.size();
    }

    /** fixture 中的全部记录 id，按字典序。 */
    public Set<String> recordIds() {
        return new TreeSet<>(recordsById.keySet());
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public Map<String, ScoredPoint> decide(DecisionState state, List<DecisionPoint> points) {
        Map<String, ScoredPoint> results = new LinkedHashMap<>();
        String recordId = extractRecordId(state);
        for (DecisionPoint point : points) {
            results.put(point.id(), scoreOne(state, recordId, point));
        }
        return Map.copyOf(results);
    }

    private ScoredPoint scoreOne(DecisionState state, String recordId, DecisionPoint point) {
        if (recordId == null) {
            return degraded(state, point, "state 中缺少回放标识字段 '" + stateKeyField + "'，无法对应 fixture 记录");
        }
        JsonNode record = recordsById.get(recordId);
        if (record == null) {
            return degraded(state, point, "fixture 中没有记录 " + recordId);
        }
        JsonNode optionIds = record.get("option_ids");
        JsonNode probabilities = record.get("probabilities");
        if (optionIds == null || probabilities == null
                || !optionIds.isArray() || !probabilities.isArray()
                || optionIds.size() != probabilities.size() || optionIds.isEmpty()) {
            return degraded(state, point, "fixture 记录 " + recordId + " 的 option_ids/probabilities 形状不合法");
        }

        // 先配对再排序——绝不能按下标搬运，因为 fixture 的顺序既非字典序也非概率序。
        TreeMap<String, Double> byOptionId = new TreeMap<>();
        for (int i = 0; i < optionIds.size(); i++) {
            String optionId = optionIds.get(i).asText();
            double probability = probabilities.get(i).asDouble();
            if (byOptionId.put(optionId, probability) != null) {
                return degraded(state, point, "fixture 记录 " + recordId + " 含重复选项 " + optionId);
            }
        }

        // 选项集一致性检查：不一致说明这条记录不是在回答这个判定点。
        Set<String> declared = new TreeSet<>();
        for (Option option : point.options()) {
            declared.add(option.id());
        }
        if (!declared.equals(byOptionId.keySet())) {
            return degraded(state, point, "fixture 记录 " + recordId + " 的选项集与判定点 "
                    + point.ref() + " 不一致：记录=" + byOptionId.keySet() + "，契约=" + declared);
        }

        List<OptionScore> distribution = new ArrayList<>(byOptionId.size());
        byOptionId.forEach((optionId, probability) ->
                distribution.add(new OptionScore(optionId, probability)));

        String backend = record.path("model").path("backend").asText(DEFAULT_BACKEND);
        String revision = record.path("model").path("revision").asText("");
        if (revision.isBlank()) {
            return degraded(state, point, "fixture 记录 " + recordId + " 缺少 model.revision");
        }
        String promptSha256 = record.path("prompt_sha256").asText("");
        if (promptSha256.isBlank()) {
            return degraded(state, point, "fixture 记录 " + recordId + " 缺少 prompt_sha256");
        }

        return new ScoredPoint(
                Decision.Outcome.OK,
                distribution,
                null,
                revision,
                backend,
                promptSha256,
                record.path("input_tokens").asInt(0),
                // 回放没有真实耗时可言；沿用记录里的 forward_seconds 仅供审计展示。
                // ⚠️ 这个值含首次编译开销，不能用于任何性能结论。
                Math.round(record.path("forward_seconds").asDouble(0.0) * 1000.0));
    }

    /** 从 state JSON 中取出用于对应 fixture 记录的标识。 */
    private String extractRecordId(DecisionState state) {
        try {
            JsonNode root = MAPPER.readTree(state.json());
            JsonNode node = root.get(stateKeyField);
            if (node == null || !node.isValueNode() || node.asText().isBlank()) {
                return null;
            }
            return node.asText();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 构造降级结果：选项按字典序，保守地给每个选项相同概率。
     *
     * <p>注意这里仍然计算<b>真实的 prompt 哈希</b>——prompt 渲染不依赖模型，
     * 降级只是「没拿到分数」，不是「不知道问了什么」。
     * 填占位符会让审计丢失「当时问的是哪个问题」，
     * 而 {@code Provenance.promptSha256} 的契约要求它是一个真实哈希。
     */
    private static ScoredPoint degraded(DecisionState state, DecisionPoint point, String reason) {
        TreeMap<String, Option> sorted = new TreeMap<>();
        point.options().forEach(option -> sorted.put(option.id(), option));
        List<OptionScore> distribution = new ArrayList<>(sorted.size());
        double each = 1.0 / sorted.size();
        sorted.keySet().forEach(optionId ->
                distribution.add(new OptionScore(optionId, each)));
        String promptHash = RegistryHasher.promptSha256(
                PromptRenderer.render(point, state.json()));
        return ScoredPoint.degraded(distribution, reason, "none", DEFAULT_BACKEND, promptHash);
    }

    @Override
    public SlotCheck.Result assertSlots(SlotCheck.DecisionPointLike point, String promptText) {
        return new SlotCheck.Result(point.ref(), SlotCheck.Status.SKIPPED,
                List.of("回放 provider 不经过模型，不存在答案槽位契约"), List.of());
    }
}
