package com.semif.gate.contract;

/**
 * 一次判定的溯源信息——漂移对比与审计所需的全部元数据。
 *
 * <p>这些字段全部参与缓存键：任何一项变化都意味着「这不是同一个判定」。
 * 缺少任何一项，模型升级或后端切换造成的行为变化都无法追溯。
 *
 * @param providerId     provider 标识，如 {@code http-vllm} / {@code ollama} / {@code semif-reference} / {@code rules}
 * @param modelRevision  不可变模型 revision
 * @param backend        执行后端，如 {@code vllm} / {@code ollama} / {@code mlx} / {@code torch}
 * @param promptSha256   实际发出 prompt 的 SHA-256（十六进制）
 * @param registrySha256 判定点注册表整体哈希
 * @param optionsSha256  选项集哈希（顺序无关）
 * @param inputTokens    实际消耗的输入 token 数
 * @param latencyMs      判定耗时（毫秒）
 */
public record Provenance(
        String providerId,
        String modelRevision,
        String backend,
        String promptSha256,
        String registrySha256,
        String optionsSha256,
        int inputTokens,
        long latencyMs) {

    public Provenance {
        requireNonBlank(providerId, "providerId");
        requireNonBlank(modelRevision, "modelRevision");
        requireNonBlank(backend, "backend");
        requireNonBlank(promptSha256, "promptSha256");
        requireNonBlank(registrySha256, "registrySha256");
        requireNonBlank(optionsSha256, "optionsSha256");
        if (inputTokens < 0) {
            throw new IllegalArgumentException("inputTokens 不能为负: " + inputTokens);
        }
        if (latencyMs < 0) {
            throw new IllegalArgumentException("latencyMs 不能为负: " + latencyMs);
        }
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }
}
