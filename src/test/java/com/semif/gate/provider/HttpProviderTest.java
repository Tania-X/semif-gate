package com.semif.gate.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.semif.gate.contract.Decision;
import com.semif.gate.contract.DecisionPoint;
import com.semif.gate.contract.ScoredPoint;
import com.semif.gate.registry.PromptRenderer;
import com.semif.gate.registry.RegistryHasher;
import com.semif.gate.testkit.Fixtures;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HttpProvider} 的契约测试——用<b>本地回环假服务</b>，不连外网。
 *
 * <p>这一层的核心不是「能不能调通」，而是<b>对端不可信时怎么办</b>：
 * 对方可能是另一个团队维护的、会升级的、会配错的组件。
 * 所以每个测试都在问同一个问题——「对端返回了不合契约的东西，我们会接受吗？」
 */
class HttpProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private String baseUrl;

    private final AtomicReference<BiFunction<JsonNode, HttpExchange, Response>> handler =
            new AtomicReference<>();

    /** 假服务的响应。 */
    private record Response(int status, String body) {
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/decide", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);
            JsonNode request = MAPPER.readTree(requestBody);
            Response response = handler.get().apply(request, exchange);
            if (response == null) {
                // 模拟超时：不回应，直接睡过客户端的超时窗口
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                exchange.close();
                return;
            }
            byte[] payload = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static DecisionPoint point() {
        return Fixtures.registryOf(Fixtures.evidenceSupport()).require("evidence.support@1");
    }

    /** 构造一个"正确"的响应：prompt 哈希与本地渲染一致。 */
    private static Response correctResponse(JsonNode request, Map<String, Double> distribution) {
        DecisionPoint point = point();
        // 必须与生产代码走同一条渲染路径：
        // payload 里的 evidence 是 state 中【该字段的值】，不是整个 state 对象。
        String promptHash = RegistryHasher.promptSha256(
                PromptRenderer.renderWithField(point, request.get("state").toString(), "evidence"));
        ObjectNode body = MAPPER.createObjectNode();
        var results = body.putArray("results");
        ObjectNode result = results.addObject();
        result.put("point_id", point.id());
        result.put("prompt_sha256", promptHash);
        result.put("model_revision", Fixtures.REVISION);
        result.put("backend", "vllm");
        result.put("input_tokens", 142);
        result.put("latency_ms", 42);
        ObjectNode dist = result.putObject("distribution");
        distribution.forEach(dist::put);
        return new Response(200, body.toString());
    }

    private ScoredPoint callOnce(Duration timeout) {
        HttpProvider provider = new HttpProvider(baseUrl, "http-test", timeout,
                java.net.http.HttpClient.newHttpClient());
        return provider.decide(Fixtures.state(Fixtures.ANCHOR_ID), List.of(point()))
                .get("evidence.support");
    }

    @Test
    @DisplayName("正常响应 → OK，且 providerId 为配置值")
    void happyPath() {
        handler.set((request, exchange) -> correctResponse(request,
                Map.of("contradicted", 0.98, "insufficient", 0.012, "supported", 0.008)));

        ScoredPoint scored = callOnce(Duration.ofSeconds(5));

        assertTrue(scored.ok(), () -> scored.degradedReason());
        assertEquals(0.98, scored.distribution().stream()
                .filter(s -> s.optionId().equals("contradicted"))
                .findFirst().orElseThrow().probability(), 1e-9);
        assertEquals(Fixtures.REVISION, scored.modelRevision());
        assertEquals("vllm", scored.backend());
    }

    @Test
    @DisplayName("prompt 哈希不一致 → 丢弃该结果并降级，绝不接受")
    void mismatchedPromptHashIsRejected() {
        // 对端用了一个不同的 prompt —— 我们就不知道这条判定回答的是哪个问题
        handler.set((request, exchange) -> {
            Response good = correctResponse(request,
                    Map.of("contradicted", 0.98, "insufficient", 0.012, "supported", 0.008));
            return new Response(200, good.body().replace(
                    extractPromptHash(good.body()), "0".repeat(64)));
        });

        ScoredPoint scored = callOnce(Duration.ofSeconds(5));

        assertFalse(scored.ok(), "prompt 哈希不一致时必须拒绝");
        assertEquals(Decision.Outcome.DEGRADED, scored.outcome());
        assertTrue(scored.degradedReason().contains("prompt 哈希不一致"),
                () -> scored.degradedReason());
    }

    @Test
    @DisplayName("选项集不一致 → 降级，绝不把概率安到不存在的选项上")
    void mismatchedOptionSetIsRejected() {
        handler.set((request, exchange) -> correctResponse(request,
                Map.of("contradicted", 0.5, "insufficient", 0.3, "unexpected", 0.2)));

        ScoredPoint scored = callOnce(Duration.ofSeconds(5));

        assertFalse(scored.ok());
        assertTrue(scored.degradedReason().contains("选项集不一致"), scored.degradedReason());
    }

    @Test
    @DisplayName("概率越界 → 降级，不让 NaN/越界值污染缓存与审计")
    void invalidProbabilityIsRejected() {
        handler.set((request, exchange) -> correctResponse(request,
                Map.of("contradicted", 0.5, "insufficient", 0.3, "supported", 1.7)));

        ScoredPoint scored = callOnce(Duration.ofSeconds(5));

        assertFalse(scored.ok());
        assertTrue(scored.degradedReason().contains("非法概率"), scored.degradedReason());
    }

    @Test
    @DisplayName("超时 → DEGRADED，原因为 http timeout")
    void timeoutIsDegraded() {
        handler.set((request, exchange) -> null);   // 服务不回应

        ScoredPoint scored = callOnce(Duration.ofMillis(300));

        assertFalse(scored.ok(), "超时必须降级而不是抛异常");
        assertTrue(scored.degradedReason().contains("http timeout"), scored.degradedReason());
    }

    @Test
    @DisplayName("非 200 状态码 → 降级并带上状态码")
    void non200IsDegraded() {
        handler.set((request, exchange) -> new Response(503, "{\"error\":\"unavailable\"}"));

        ScoredPoint scored = callOnce(Duration.ofSeconds(5));

        assertFalse(scored.ok());
        assertTrue(scored.degradedReason().contains("503"), scored.degradedReason());
    }

    @Test
    @DisplayName("响应非 JSON → 降级")
    void nonJsonResponseIsDegraded() {
        handler.set((request, exchange) -> new Response(200, "not json at all"));

        ScoredPoint scored = callOnce(Duration.ofSeconds(5));

        assertFalse(scored.ok());
        assertTrue(scored.degradedReason().contains("合法 JSON"), scored.degradedReason());
    }

    @Test
    @DisplayName("响应缺少 results → 降级")
    void missingResultsIsDegraded() {
        handler.set((request, exchange) -> new Response(200, "{\"provenance\":{}}"));

        ScoredPoint scored = callOnce(Duration.ofSeconds(5));

        assertFalse(scored.ok());
        assertTrue(scored.degradedReason().contains("results"), scored.degradedReason());
    }

    @Test
    @DisplayName("响应缺少该判定点 → 降级并点名")
    void missingPointIsDegraded() {
        handler.set((request, exchange) -> new Response(200, "{\"results\":[]}"));

        ScoredPoint scored = callOnce(Duration.ofSeconds(5));

        assertFalse(scored.ok());
        assertTrue(scored.degradedReason().contains("缺少判定点"), scored.degradedReason());
    }

    @Test
    @DisplayName("服务不可达 → 降级，不抛异常到上层")
    void unreachableServerIsDegraded() {
        // 指向一个已关闭的端口
        HttpProvider provider = new HttpProvider("http://127.0.0.1:1", "http-test",
                Duration.ofMillis(500), java.net.http.HttpClient.newHttpClient());

        ScoredPoint scored = provider.decide(Fixtures.state(Fixtures.ANCHOR_ID), List.of(point()))
                .get("evidence.support");

        assertFalse(scored.ok());
        assertTrue(scored.degradedReason().contains("http 调用失败"), scored.degradedReason());
    }

    @Test
    @DisplayName("请求体包含 state_hash / points / prompt_sha256")
    void requestShapeIsAsContract() {
        AtomicReference<JsonNode> captured = new AtomicReference<>();
        handler.set((request, exchange) -> {
            captured.set(request);
            return correctResponse(request,
                    Map.of("contradicted", 0.9, "insufficient", 0.05, "supported", 0.05));
        });

        callOnce(Duration.ofSeconds(5));

        JsonNode request = captured.get();
        assertEquals(Fixtures.state(Fixtures.ANCHOR_ID).hash(), request.get("state_hash").asText());
        assertTrue(request.has("state"));
        JsonNode points = request.get("points");
        assertEquals(1, points.size());
        assertEquals("evidence.support", points.get(0).get("id").asText());
        assertEquals("evidence.support@1", points.get(0).get("point_ref").asText());
        assertEquals(64, points.get(0).get("prompt_sha256").asText().length());
    }

    @Test
    @DisplayName("构造校验：空 baseUrl / 非正超时 → 报错")
    void constructorValidates() {
        assertThrows(IllegalArgumentException.class, () -> new HttpProvider(""));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpProvider("http://x", Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpProvider("http://x", Duration.ofSeconds(-1)));
    }

    @Test
    @DisplayName("支持前缀复用——容量评估依赖这个标志")
    void supportsPrefixReuse() {
        assertTrue(new HttpProvider("http://127.0.0.1:1").supportsPrefixReuse());
    }

    private static String extractPromptHash(String body) {
        try {
            return MAPPER.readTree(body).get("results").get(0).get("prompt_sha256").asText();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
