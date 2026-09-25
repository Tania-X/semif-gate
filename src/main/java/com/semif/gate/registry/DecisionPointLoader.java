package com.semif.gate.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.semif.gate.contract.AnswerStyle;
import com.semif.gate.contract.DecisionPoint;
import com.semif.gate.contract.Option;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 判定点资源文件的读取器。
 *
 * <p>文件格式为 JSON（结构直接对应设计文档中的 YAML 形态）。
 * 选择 JSON 而非 YAML 的原因：本次范围要求「依赖尽量少」，
 * 只引入 Jackson 即可完成解析；如需 YAML，只需在本类内部替换解析器，
 * 注册表的校验逻辑完全不受影响。
 *
 * <p>单个文件的结构：
 * <pre>{@code
 * {
 *   "revision": "851bf6e8...",          // 文件级默认模型 revision
 *   "points": [ { "id": "...", "version": 1, "owner": "...", "frozenAt": "...",
 *                 "question": "...", "answerStyle": "LETTER",
 *                 "modelRevision": "...",   // 可选，覆盖文件级默认值
 *                 "options": [ {"id": "...", "description": "..."} ],
 *                 "template": "...{{state}}...{{question}}...{{options}}..." } ]
 * }
 * }</pre>
 */
public final class DecisionPointLoader {

    /** 判定点 ID 的合法形态：小写字母开头，允许小写字母、数字、点、下划线、连字符。 */
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z][a-z0-9._-]*");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DecisionPointLoader() {
    }

    public static List<DecisionPoint> loadFromClasspath(String resourcePath) {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new RegistryException("找不到判定点资源: " + resourcePath);
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return loadFromJson(text, resourcePath);
        } catch (IOException e) {
            throw new RegistryException("读取判定点资源失败: " + resourcePath, e);
        }
    }

    /**
     * 从 JSON 文本加载判定点。
     *
     * @param json         JSON 文本
     * @param sourceLabel  用于错误信息的来源标识
     * @return 判定点列表
     * @throws RegistryException 结构或字段非法
     */
    public static List<DecisionPoint> loadFromJson(String json, String sourceLabel) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (IOException e) {
            throw new RegistryException(sourceLabel + ": JSON 解析失败", e);
        }
        if (root == null || !root.isObject()) {
            throw new RegistryException(sourceLabel + ": 顶层必须是 JSON 对象");
        }
        String fileRevision = textOrNull(root, "revision");
        JsonNode pointsNode = root.get("points");
        if (pointsNode == null || !pointsNode.isArray() || pointsNode.isEmpty()) {
            throw new RegistryException(sourceLabel + ": 缺少非空的 points 数组");
        }

        List<DecisionPoint> points = new ArrayList<>(pointsNode.size());
        for (int i = 0; i < pointsNode.size(); i++) {
            JsonNode node = pointsNode.get(i);
            String where = sourceLabel + " points[" + i + "]";
            String revision = textOrNull(node, "modelRevision");
            if (revision == null) {
                revision = fileRevision;
            }
            if (revision == null) {
                throw new RegistryException(where + ": 缺少 modelRevision，且文件级未提供默认值");
            }
            points.add(toPoint(node, revision, where));
        }
        return List.copyOf(points);
    }

    private static DecisionPoint toPoint(JsonNode node, String revision, String where) {
        String id = requireText(node, "id", where);
        int version = requireInt(node, "version", where);
        String owner = requireText(node, "owner", where);
        String frozenAt = requireText(node, "frozenAt", where);
        String question = requireText(node, "question", where);
        String template = requireText(node, "template", where);
        String styleText = requireText(node, "answerStyle", where);

        AnswerStyle answerStyle;
        try {
            answerStyle = AnswerStyle.valueOf(styleText.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new RegistryException(where + ": 未知的 answerStyle: " + styleText
                    + "（可选 LETTER / YESNO）");
        }

        JsonNode optionsNode = node.get("options");
        if (optionsNode == null || !optionsNode.isArray() || optionsNode.isEmpty()) {
            throw new RegistryException(where + ": 缺少非空的 options 数组");
        }
        List<Option> options = new ArrayList<>(optionsNode.size());
        for (int i = 0; i < optionsNode.size(); i++) {
            JsonNode optionNode = optionsNode.get(i);
            String optionWhere = where + " options[" + i + "]";
            String optionId = requireText(optionNode, "id", optionWhere);
            String description = requireText(optionNode, "description", optionWhere);
            try {
                options.add(new Option(optionId, description));
            } catch (IllegalArgumentException e) {
                throw new RegistryException(optionWhere + ": " + e.getMessage(), e);
            }
        }

        try {
            return new DecisionPoint(id, version, owner, frozenAt, question, options,
                    template, answerStyle, revision, "<待计算>");
        } catch (IllegalArgumentException e) {
            throw new RegistryException(where + ": " + e.getMessage(), e);
        }
    }

    /** 校验判定点 ID 的形态——它会进入缓存键，形态约束能防住手滑写出的怪值。 */
    static void requireValidIdShape(String id, String ref) {
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new RegistryException(
                    "判定点 ID 形态非法: " + id + "（要求 " + ID_PATTERN.pattern() + "）");
        }
    }

    private static String requireText(JsonNode node, String field, String where) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
            throw new RegistryException(where + ": 缺少非空字符串字段 " + field);
        }
        return value.asText();
    }

    private static int requireInt(JsonNode node, String field, String where) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToInt()) {
            throw new RegistryException(where + ": 缺少整数字段 " + field);
        }
        return value.asInt();
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
            return null;
        }
        return value.asText();
    }
}
