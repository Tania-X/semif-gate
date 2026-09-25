package com.semif.gate.registry;

import com.semif.gate.contract.AnswerStyle;
import com.semif.gate.contract.DecisionPoint;
import com.semif.gate.contract.Option;
import com.semif.gate.state.StateHasher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把冻结的 prompt 模板渲染成实际发送的文本。
 *
 * <p>模板只允许三个占位符：{@code {{state}}}、{@code {{question}}}、{@code {{options}}}。
 * 不允许其它占位符，也不允许在判定点里内联任意逻辑——
 * <b>prompt 模板归注册表管，调用方只能填模板变量，不能自己写 prompt。</b>
 * 这是可审计性的根：任何一条审计记录都能追溯到一份受版本控制的模板。
 *
 * <p>渲染必须满足<b>确定性</b>：同样的输入必须得到逐字节相同的输出，
 * 因为渲染结果的哈希会进审计记录，并被 provider 用来校验它发出的 prompt。
 */
public final class PromptRenderer {

    /** LETTER 风格使用的字母表，与 SemIf 的 {@code core.LETTERS} 一致。 */
    public static final String LETTERS = "ABCDEFGHIJKLMNOP";

    private static final String STATE_PLACEHOLDER = "{{state}}";
    private static final String QUESTION_PLACEHOLDER = "{{question}}";
    private static final String OPTIONS_PLACEHOLDER = "{{options}}";

    /** 捕获 {@code {{...}}} 形式的占位符。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*}}");

    private static final List<String> REQUIRED = List.of("state", "question", "options");

    private PromptRenderer() {
    }

    /**
     * 渲染 prompt。
     *
     * @param point     判定点契约
     * @param stateJson 已规范化的 state JSON
     * @return 渲染后的完整 prompt 文本
     * @throws RegistryException 模板含未知占位符
     */
    public static String render(DecisionPoint point, String stateJson) {
        String template = point.promptTemplate();
        requireNoUnknownPlaceholders(point);
        return template
                .replace(STATE_PLACEHOLDER, stateJson)
                .replace(QUESTION_PLACEHOLDER, quote(point.question()))
                .replace(OPTIONS_PLACEHOLDER, renderOptions(point));
    }

    /**
     * 渲染选项列表为 JSON 数组。
     *
     * <p>格式与 SemIf 的 {@code core.direct_messages} 保持一致：
     * 每个选项带一个字母与一段描述，字母由模板顺序决定。
     * 字母只是模型读取用的槽位标识；语义对齐始终按 {@code optionId} 进行。
     */
    /**
     * 用 state 中**某一个字段的值**作为证据来渲染。
     *
     * <p>为什么需要它：SemIf 的 payload 形状是
     * {@code {"evidence": <值>, "criterion": <值>, "options": [...]}}，
     * 其中 {@code evidence} 是 state 里的**字段值**，不是整个 state 对象。
     * 直接把整个 state JSON 塞进 {@code {{state}}} 会渲染出
     * {@code {"evidence": {"evidence": ...}}} 这种嵌套结构——实测 prompt 从 701 字符
     * 变成 846 字符，哈希自然对不上（且模型会在错误的输入上给出看似合理的输出）。
     *
     * @param point       判定点
     * @param stateJson   state 的规范化 JSON
     * @param evidenceKey state 中承载证据的字段名（通常是 {@code evidence}）
     */
    public static String renderWithField(DecisionPoint point, String stateJson, String evidenceKey) {
        String value = extractFieldValue(stateJson, evidenceKey);
        if (value == null) {
            throw new RegistryException(
                    "state 中找不到证据字段 " + evidenceKey + "：" + abbreviate(stateJson));
        }
        return render(point, value);
    }

    /**
     * 从 JSON 对象里取出某个字段的**原始值文本**（不含字段名）。
     *
     * <p>刻意做成字符串级提取而非完整 JSON 解析：这样能保证逐字节复现原始序列化结果，
     * 不会因为「解析再序列化」引入任何格式差异——prompt 哈希对格式极度敏感。
     */
    static String extractFieldValue(String stateJson, String field) {
        if (stateJson == null) {
            return null;
        }
        String needle = "\"" + field + "\":";
        int at = stateJson.indexOf(needle);
        if (at < 0) {
            return null;
        }
        int start = at + needle.length();
        if (start >= stateJson.length()) {
            return null;
        }
        char first = stateJson.charAt(start);
        if (first == '"') {
            // 字符串值：扫到未被转义的收尾引号
            int i = start + 1;
            while (i < stateJson.length()) {
                char c = stateJson.charAt(i);
                if (c == '\\') {
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    return stateJson.substring(start, i + 1);
                }
                i++;
            }
            return null;
        }
        // 非字符串（对象/数组/数字）：按括号配平扫到值的结尾
        int depth = 0;
        int i = start;
        boolean inString = false;
        while (i < stateJson.length()) {
            char c = stateJson.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                if (depth == 0) {
                    break;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                break;
            }
            i++;
        }
        return stateJson.substring(start, i).strip();
    }

    private static String abbreviate(String text) {
        return text.length() <= 120 ? text : text.substring(0, 120) + "…";
    }

    public static String renderOptions(DecisionPoint point) {
        AnswerStyle style = point.answerStyle();
        List<Option> options = point.options();
        List<Map<String, String>> rendered = new ArrayList<>(options.size());
        for (int i = 0; i < options.size(); i++) {
            String label = style == AnswerStyle.YESNO
                    ? (i == 0 ? "yes" : "no")
                    : String.valueOf(LETTERS.charAt(i));
            rendered.add(Map.of("letter", label, "description", options.get(i).description()));
        }
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rendered.size(); i++) {
            if (i > 0) {
                json.append(", ");
            }
            Map<String, String> entry = rendered.get(i);
            // 键顺序与分隔符必须与 Python 的 json.dumps 默认行为一致：
            // letter 在前、": " 与 ", " 带空格。任何偏差都会让 prompt 哈希对不上。
            json.append("{\"letter\": ").append(quote(entry.get("letter")))
                    .append(", \"description\": ").append(quote(entry.get("description"))).append('}');
        }
        return json.append(']').toString();
    }

    /** 按 JSON 规则转义并加引号（转义规则与 state 序列化保持一致）。 */
    private static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2);
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /**
     * 校验模板占位符：不允许未知占位符，三个必需占位符必须全部出现。
     *
     * <p>「必需全部出现」而非「至少出现一个」是刻意的：
     * 如果模板漏了 {@code {{state}}}，模型就看不到证据，判定会退化成瞎猜，
     * 而这种错误不会报任何异常——所以必须在启动时挡住。
     *
     * @throws RegistryException 模板非法
     */
    public static void requireNoUnknownPlaceholders(DecisionPoint point) {
        Matcher matcher = PLACEHOLDER.matcher(point.promptTemplate());
        TreeMap<String, Boolean> found = new TreeMap<>();
        while (matcher.find()) {
            found.put(matcher.group(1), Boolean.TRUE);
        }
        List<String> unknown = found.keySet().stream()
                .filter(name -> !REQUIRED.contains(name))
                .toList();
        if (!unknown.isEmpty()) {
            throw new RegistryException(
                    "判定点 " + point.ref() + " 的模板含未知占位符: " + unknown
                            + "；只允许 " + REQUIRED);
        }
        List<String> missing = REQUIRED.stream().filter(name -> !found.containsKey(name)).toList();
        if (!missing.isEmpty()) {
            throw new RegistryException(
                    "判定点 " + point.ref() + " 的模板缺少必需占位符: " + missing
                            + "；缺少 {{state}} 会让模型看不到证据，且不会报错");
        }
    }

    /**
     * 用固定输入渲染两次并比较哈希，证明渲染是确定性的。
     *
     * <p>固定输入是刻意的：这里检验的是「同一输入是否总得到同一输出」，
     * 而不是「模板长什么样」。如果模板里混入了时间戳之类的非确定性内容，
     * 两次渲染的哈希就会不同，启动随即失败。
     *
     * @return 渲染结果的哈希
     * @throws RegistryException 两次渲染结果不一致
     */
    public static String assertDeterministic(DecisionPoint point) {
        String probeState = "{\"__probe__\":\"determinism-check\"}";
        String first = render(point, probeState);
        String second = render(point, probeState);
        String firstHash = StateHasher.sha256Hex(first);
        String secondHash = StateHasher.sha256Hex(second);
        if (!firstHash.equals(secondHash)) {
            throw new RegistryException(
                    "判定点 " + point.ref() + " 的模板渲染不确定：两次渲染哈希不同");
        }
        return firstHash;
    }
}
