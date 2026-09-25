package com.semif.gate.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.semif.gate.contract.Decision;
import com.semif.gate.contract.DecisionPoint;
import com.semif.gate.contract.OptionScore;
import com.semif.gate.contract.ScoredPoint;
import com.semif.gate.registry.PromptRenderer;
import com.semif.gate.registry.RegistryHasher;
import com.semif.gate.registry.SlotCheck;
import com.semif.gate.state.DecisionState;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 通过 HTTP 调用推理服务——生产路径。
 *
 * <h2>它信不过对端，所以每一处都要验</h2>
 * 网关与推理服务之间隔着一个网络，而对方可能是另一个团队维护的、会升级的、
 * 会配错的组件。因此这个实现把「防御」写在明处：
 * <ol>
 *   <li><b>prompt 哈希必须与注册表一致。</b>响应里的 {@code prompt_sha256} 若与
 *       本地渲染出的不一致，说明对方用的不是这份契约——那条结果被丢弃并降级。
 *       接受它就等于承认「我不知道这条判定回答的是哪个问题」。</li>
 *   <li><b>选项集必须与判定点一致。</b>多一个少一个都说明双方理解不同。</li>
 *   <li><b>任何异常都变成 DEGRADED，绝不向上抛。</b>模型超时不该让调用链断掉，
 *       但降级必须带明确原因，不能伪装成正常判定。</li>
 * </ol>
 *
 * <p>超时是<b>显式配置</b>而不是默认值碰运气：判定服务慢一点没关系，
 * 但调用方需要知道自己在等多久。
 */
public final class HttpProvider implements DecisionProvider {

    /** provider 标识，固定不变——它进缓存键。 */
    public static final String PROVIDER_ID = "http";

    /** 默认超时。 */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String providerId;
    private final Duration timeout;
    private final HttpClient httpClient;

    public HttpProvider(String baseUrl) {
        this(baseUrl, PROVIDER_ID, DEFAULT_TIMEOUT, HttpClient.newHttpClient());
    }

    public HttpProvider(String baseUrl, Duration timeout) {
        this(baseUrl, PROVIDER_ID, timeout, HttpClient.newHttpClient());
    }

    /**
     * @param baseUrl    推理服务根地址，如 {@code http://127.0.0.1:8080}
     * @param providerId 写入缓存键与审计的 provider 标识；不同部署应使用不同值，
     *                   否则跨部署的结果会混进同一个缓存命名空间
     * @param timeout    请求超时
     * @param httpClient 可注入的客户端（测试用）
     */
    public HttpProvider(String baseUrl, String providerId, Duration timeout, HttpClient httpClient) {
        this(baseUrl, providerId, timeout, httpClient, DEFAULT_EVIDENCE_FIELD);
    }

    /**
     * 完整构造。
     *
     * @param evidenceField state 中承载证据的字段名（部署配置，默认 {@code evidence}）
     */
    public HttpProvider(String baseUrl, String providerId, Duration timeout,
                        HttpClient httpClient, String evidenceField) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl 不能为空");
        }
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId 不能为空");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout 必须为正");
        }
        if (httpClient == null) {
            throw new IllegalArgumentException("httpClient 不能为空");
        }
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.providerId = providerId;
        this.timeout = timeout;
        this.httpClient = httpClient;
        this.evidenceField = evidenceField == null || evidenceField.isBlank()
                ? DEFAULT_EVIDENCE_FIELD : evidenceField;
    }

    /** state 中承载证据的字段名。它是**部署配置**：不同接入方的 state 形状不同。 */
    private static final String DEFAULT_EVIDENCE_FIELD = "evidence";

    private final String evidenceField;

    /** 设置证据字段名（链式，供部署时指定）。 */
    public HttpProvider withEvidenceField(String field) {
        return new HttpProvider(baseUrl, providerId, timeout, httpClient,
                field == null || field.isBlank() ? DEFAULT_EVIDENCE_FIELD : field);
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public Map<String, ScoredPoint> decide(DecisionState state, List<DecisionPoint> points) {
        // 先把本地渲染结果算出来——它同时用于构造请求和校验响应。
        Map<String, String> promptHashes = new LinkedHashMap<>();
        for (DecisionPoint point : points) {
            // 证据是 state 里的字段【值】，不是整个 state 对象——见 renderWithField 的说明
            String renderedPrompt = PromptRenderer.renderWithField(point, state.json(), evidenceField);
            promptHashes.put(point.id(), RegistryHasher.promptSha256(renderedPrompt));
            if (Boolean.getBoolean("semif.gate.debugHttp")) {
                System.err.println("[HttpProvider] state.json() = " + state.json());
                System.err.println("[HttpProvider] prompt = " + renderedPrompt);
                System.err.println("[HttpProvider] prompt 长度 = " + renderedPrompt.length());
                System.err.println("[HttpProvider] promptSha256 = " + promptHashes.get(point.id()));
            }
        }

        ObjectNode request = MAPPER.createObjectNode();
        request.put("state_hash", state.hash());
        request.set("state", readTreeOrNull(state.json()));
        ArrayNode pointNodes = request.putArray("points");
        for (DecisionPoint point : points) {
            ObjectNode node = pointNodes.addObject();
            node.put("id", point.id());
            node.put("point_ref", point.ref());
            node.put("prompt_sha256", promptHashes.get(point.id()));
        }

        String body;
        try {
            body = MAPPER.writeValueAsString(request);
            if (Boolean.getBoolean("semif.gate.debugHttp")) {
                System.err.println("[HttpProvider] 请求体: " + body);
                System.err.println("[HttpProvider] body 长度(字符) = " + body.length()
                        + ", UTF-8 字节 = " + body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
                java.nio.file.Files.writeString(
                        java.nio.file.Path.of("/tmp/java-request-body.json"), body);
            }
        } catch (IOException e) {
            return allDegraded(state, points, "请求序列化失败: " + e.getMessage());
        }

        HttpResponse<String> response;
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/decide"))
                    // 显式用 HTTP/1.1。Java HttpClient 默认会先尝试 h2c 升级，
                    // 而 Uvicorn 不支持该升级（只记一条 "Unsupported upgrade request"），
                    // 回落后请求体会丢失——服务端看到 body 为 null，直接 422。
                    // 实测：curl / urllib 用 HTTP/1.1 正常，Java 默认升级则失败。
                    .version(HttpClient.Version.HTTP_1_1)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException e) {
            return allDegraded(state, points, "http timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return allDegraded(state, points, "http interrupted");
        } catch (IOException | RuntimeException e) {
            return allDegraded(state, points, "http 调用失败: " + e.getClass().getSimpleName());
        }

        if (response.statusCode() != 200) {
            // 带上响应体：服务端的校验错误信息全在这里，只报状态码会让排障无从下手
            String detail = response.body() == null ? "" : response.body().strip();
            if (detail.length() > 400) {
                detail = detail.substring(0, 400) + "…";
            }
            return allDegraded(state, points,
                    "http 状态码 " + response.statusCode() + (detail.isEmpty() ? "" : "：" + detail));
        }

        JsonNode payload;
        try {
            payload = MAPPER.readTree(response.body());
        } catch (IOException e) {
            return allDegraded(state, points, "响应不是合法 JSON");
        }
        JsonNode results = payload.get("results");
        if (results == null || !results.isArray()) {
            return allDegraded(state, points, "响应缺少 results 数组");
        }

        Map<String, JsonNode> byPointId = new LinkedHashMap<>();
        for (JsonNode result : results) {
            JsonNode idNode = result.get("point_id");
            if (idNode != null) {
                byPointId.put(idNode.asText(), result);
            }
        }

        Map<String, ScoredPoint> scored = new LinkedHashMap<>();
        for (DecisionPoint point : points) {
            scored.put(point.id(), readOne(state, point, byPointId.get(point.id()),
                    promptHashes.get(point.id()), payload.path("provenance")));
        }
        return Map.copyOf(scored);
    }

    /** 解析单点结果，任何不合契约之处都降级。 */
    private ScoredPoint readOne(DecisionState state, DecisionPoint point, JsonNode result,
                                String expectedPromptHash, JsonNode sharedProvenance) {
        if (result == null) {
            return degraded(state, point, "响应中缺少判定点 " + point.id());
        }
        JsonNode distributionNode = result.get("distribution");
        if (distributionNode == null || !distributionNode.isObject() || distributionNode.isEmpty()) {
            return degraded(state, point, "响应中 " + point.id() + " 的 distribution 缺失或为空");
        }

        String responsePromptHash = result.path("prompt_sha256").asText("");
        if (responsePromptHash.isBlank()) {
            responsePromptHash = sharedProvenance.path("prompt_sha256").asText("");
        }
        // 关键校验：对端实际发出的 prompt 必须与我们渲染的一致。
        if (!expectedPromptHash.equals(responsePromptHash)) {
            return degraded(state, point, "prompt 哈希不一致：契约=" + expectedPromptHash
                    + "，对端=" + (responsePromptHash.isBlank() ? "<缺失>" : responsePromptHash));
        }

        TreeMap<String, Double> byOptionId = new TreeMap<>();
        distributionNode.fields().forEachRemaining(entry -> {
            if (entry.getValue().isNumber()) {
                byOptionId.put(entry.getKey(), entry.getValue().asDouble());
            }
        });
        if (byOptionId.isEmpty()) {
            return degraded(state, point, "响应中 " + point.id() + " 的 distribution 没有数值项");
        }

        TreeMap<String, String> declared = new TreeMap<>();
        point.options().forEach(option -> declared.put(option.id(), option.description()));
        if (!declared.keySet().equals(byOptionId.keySet())) {
            return degraded(state, point, "选项集不一致：对端=" + byOptionId.keySet()
                    + "，契约=" + declared.keySet());
        }

        List<OptionScore> distribution = new ArrayList<>(byOptionId.size());
        try {
            byOptionId.forEach((optionId, probability) ->
                    distribution.add(new OptionScore(optionId, probability)));
        } catch (IllegalArgumentException e) {
            return degraded(state, point, "响应含非法概率: " + e.getMessage());
        }

        String revision = result.path("model_revision").asText(
                sharedProvenance.path("model_revision").asText(""));
        String backend = result.path("backend").asText(
                sharedProvenance.path("backend").asText("http"));
        if (revision.isBlank()) {
            return degraded(state, point, "响应缺少 model_revision");
        }

        return new ScoredPoint(
                Decision.Outcome.OK,
                distribution,
                null,
                revision,
                backend,
                responsePromptHash,
                result.path("input_tokens").asInt(sharedProvenance.path("input_tokens").asInt(0)),
                result.path("latency_ms").asLong(sharedProvenance.path("latency_ms").asLong(0L)));
    }

    private JsonNode readTreeOrNull(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            return MAPPER.createObjectNode();
        }
    }

    private Map<String, ScoredPoint> allDegraded(DecisionState state, List<DecisionPoint> points, String reason) {
        Map<String, ScoredPoint> scored = new LinkedHashMap<>();
        for (DecisionPoint point : points) {
            scored.put(point.id(), degraded(state, point, reason));
        }
        return Map.copyOf(scored);
    }

    /**
     * 降级分布：按字典序均分——刻意不制造任何「高置信」外观。
     *
     * <p>仍然计算<b>真实的 prompt 哈希</b>：prompt 渲染不依赖对端，
     * 降级只是「没拿到分数」，不是「不知道问了什么」。
     * 填占位符会让审计丢失「当时问的是哪个问题」，
     * 而 {@code Provenance.promptSha256} 的契约要求它是真实哈希。
     */
    private static ScoredPoint degraded(DecisionState state, DecisionPoint point, String reason) {
        TreeMap<String, String> sorted = new TreeMap<>();
        point.options().forEach(option -> sorted.put(option.id(), option.description()));
        List<OptionScore> distribution = new ArrayList<>(sorted.size());
        double each = 1.0 / sorted.size();
        sorted.keySet().forEach(optionId -> distribution.add(new OptionScore(optionId, each)));
        String promptHash = RegistryHasher.promptSha256(
                PromptRenderer.render(point, state.json()));
        return ScoredPoint.degraded(distribution, reason, "none", "http", promptHash);
    }

    @Override
    public SlotCheck.Result assertSlots(SlotCheck.DecisionPointLike point, String promptText) {
        return new SlotCheck.Result(point.ref(), SlotCheck.Status.SKIPPED,
                List.of("HTTP provider 的槽位契约由推理服务在启动时自检"), List.of());
    }

    @Override
    public boolean supportsPrefixReuse() {
        return true;
    }
}
