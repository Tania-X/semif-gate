# semif-gate 设计文档

> Agent Infra 的第一块：把「模型的判定」变成可缓存、可计量、可审计、可评测的基础设施。
>
> 状态：设计冻结 v1 · 2026-09-23 · 尚未开始编码

---

## 0. 这份文档解决什么问题

Agent 系统里最贵的不是单次模型调用，而是**大量本来不需要调模型的判定也调了模型**，以及**模型换了之后没人知道哪些行为变了**。

`semif-gate` 要提供的是一个原语：

```
decide(pointId, state) → 类型化判定 + 完整概率分布 + 可复现的溯源信息
```

围绕这个原语，把缓存、预算、策略、审计、漂移门禁全部工程化。

**参考实现**：[SemIf](https://github.com/TheoLeeCJ/SemIf)（`~/dsh/SemIf`）—— 它证明了「模型的判定可以是一个确定性、可缓存、可审计的纯函数」。本项目复用其语义契约与评测方法论，但不复用它作为生产运行时。

---

## 1. 三个锁死的设计取舍

### 决定 1：判定点是「不可变契约」，版本进缓存键

`pointId@version` 是缓存键的组成部分。

- 改一个字的措辞、增删一个选项、调整选项顺序 → **必须升版本**
- 这是这类系统最容易出生产事故的地方：不升版本，缓存会静默返回旧语义的判定结果，且没有任何报错

### 决定 2：网关只返回「分布 + 判定」，绝不执行动作

网关不 page、不派单、不改配置、不发消息。动作永远由调用方的策略表计算。

- 网关是 **primitive**，不是 orchestrator
- 这条决定了它能不能被多个系统复用

### 决定 3：缓存键 = 精确规范化 state 哈希，不做「近似命中」

SemIf 的前提是「同 state → 同结论」，所以**精确哈希命中是正确性保证，不是性能优化**。

- 语义近邻缓存（embedding 相似即命中）**明确不做**：它会让缓存键不再是正确性边界
- 未来若要做，必须显式开关 + 上报误命中率，且默认关闭

---

## 2. 与 SemIf 的边界

| SemIf 的角色 | 具体内容 | 本项目的处理 |
|---|---|---|
| 语义定义来源 | 答案字母=单 token 且往返一致；prompt 哈希；选项数 2–16 | 直接搬进注册表校验与启动自检 |
| 离线参考评分器 | 用其 CLI 在评测集上产出参考概率 | 实现为 `SemIfProvider`（仅离线） |
| 评测方法论 | 冻结 ID/标签/指标、逐行证据、create-only 输出、SHA256 | 搬进 `cli eval` / `cli diff` |
| 负面对照 | reranker 不是通用判定算子；softmax 不是校准置信度 | 写进设计约束与文档 |
| 生产运行时 | GPU 上的 batch CLI | **不复用**，生产走 `HttpProvider` |

### 从 SemIf 直接搬的四样

1. 答案槽位单 token 断言（`direct.py::_slot_ids`）
2. prompt sha256 进审计记录
3. 选项数 2–16 约束
4. 基准输出 create-only 纪律

### 改掉 SemIf 的三样及原因

| SemIf 做法 | 本项目做法 | 原因 |
|---|---|---|
| GPU 模型内嵌在 CLI | 降级为离线参考 Provider，接口与实现解耦 | 生产不能依赖 batch 进程 |
| 阈值与指标写死在评测脚本 | 抽出带版本的 `policy.yaml` | 让历史决策记录永远可解释 |
| batch 进程 | 进程内缓存 + 判定点版本化 | 变成可在线复用的 primitive |

---

## 3. 工程结构

```
semif-gate/                                  # Java 21 + Spring Boot 3
├── pom.xml                                  # spring-web, jackson, postgres, flyway, junit
└── src/main/java/com/semif/gate/            # 包名可改
    ├── contract/     DecisionState, DecisionPoint, Option, Decision, OptionScore, Band
    ├── registry/     DecisionPointRegistry, PointLoader, RegistryHasher
    ├── state/        StateNormalizer(白名单+截断), CanonicalJson, StateHasher, DecisionKey
    ├── cache/        DecisionCache(接口), JdbcDecisionCache
    ├── provider/     DecisionProvider(SPI), RuleFallbackProvider, HttpProvider, SemIfProvider
    ├── policy/       PolicyEngine(纯函数: 分布→动作), policy.yaml
    ├── audit/        DecisionRecord, JdbcDecisionAudit, Flyway V1__init.sql
    ├── gate/         DecisionGateway(唯一入口), GatewayProperties
    └── cli/          SemifGateCli: eval / diff / replay / verify-slot
└── src/test/java/…  契约测试 + 哈希稳定性测试 + 缓存正确性测试
```

Python 侧只有约 150 行 FastAPI 薄封装，复用 `semif_phase1.direct` / `semif_phase1.mlx_backend`。

---

## 4. 核心契约

```java
// contract/Decision.java
public record Decision(
    String decisionId,               // = DecisionKey.of(...)，缓存键与审计主键
    String pointId,
    int pointVersion,
    Outcome outcome,                 // OK | DEGRADED
    List<OptionScore> distribution,  // 按 optionId 字典序，保证可比
    Provenance provenance,
    String degradedReason            // outcome==DEGRADED 时非空
) {
    public enum Outcome { OK, DEGRADED }
    public boolean ok() { return outcome == Outcome.OK; }
}

// contract/OptionScore.java
public record OptionScore(String optionId, double probability) {
    public OptionScore {
        if (!Double.isFinite(probability) || probability < 0.0 || probability > 1.0)
            throw new IllegalArgumentException("probability must be finite in [0,1]: " + probability);
    }
}

// contract/Provenance.java —— 漂移对比与审计所需的全部元数据
public record Provenance(
    String providerId,        // http-vllm / ollama / semif-reference / rules
    String modelRevision,     // 不可变 revision，禁止 "latest"
    String backend,           // vllm / ollama / mlx / torch
    String promptSha256,
    String registrySha256,
    String optionsSha256,     // 顺序无关
    int inputTokens,
    long latencyMs
) {}
```

### 网关入口

签名里没有「动作」，也没有「阈值」：

```java
@Service
public class DecisionGateway {
    /** 一个 state，N 个判定点，一次批量调用。 */
    public Map<String, Decision> decide(Object rawState, List<String> pointRefs) {
        DecisionState state = normalizer.normalize(rawState);
        List<DecisionPoint> points = pointRefs.stream().map(registry::require).toList();

        Map<String, Decision> hits = cache.load(state.hash(), points);
        List<DecisionPoint> misses = points.stream()
                .filter(p -> !hits.containsKey(p.ref())).toList();

        Map<String, Decision> fresh = Map.of();
        if (!misses.isEmpty()) {
            fresh = provider.decide(state, misses);   // Provider 负责一次前向读多个槽位
            cache.store(state.hash(), fresh.values());
        }

        List<Decision> all = Stream.concat(hits.values().stream(), fresh.values().stream()).toList();
        audit.record(state, all, policy.band(all));   // 逐行落盘，含 policyVersion
        return all.stream().collect(toMap(Decision::pointId, identity()));
    }
}
```

### Provider SPI

```java
public interface DecisionProvider {
    String providerId();

    /** 一个 state，多个判定点，一次调用完成。实现方负责批处理与前缀复用。 */
    Map<String, Decision> decide(DecisionState state, List<DecisionPoint> points);

    /**
     * 启动自检：对每个判定点的每个选项，断言答案槽位的 token 契约。
     * 语义来自 SemIf direct.py::_slot_ids / encode_prompt。
     * 不适用的实现（约束解码）返回 SKIPPED，但必须显式声明。
     */
    SlotCheck assertSlots(DecisionPoint point);

    /** 是否支持同一 state 的前缀复用（SemIf serial/shared 模式的能力标记）。 */
    default boolean supportsPrefixReuse() { return false; }
}
```

| 实现 | 用途 | 关键约束 |
|---|---|---|
| `HttpProvider` | 生产 | 校验 `promptSha256 ==` 注册表值才接受响应；超时降级 |
| `SemIfProvider` | 离线参考评分器 | 调 SemIf 的 `direct.py` / `mlx_backend.py`；仅用于评测对齐 |
| `OllamaProvider` | 本机廉价档 | 用 logprobs 参数；启动自检必须验「选项字母落在 top-N 内」 |
| `RuleFallbackProvider` | 降级 | 永不失败，返回 `DEGRADED` + 保守默认分布 |

---

## 5. 判定点注册表

```yaml
# src/main/resources/decision-points/ticket.route.yaml
id: ticket.route
version: 3
owner: platform-team
frozenAt: 2026-09-22
question: "Which team owns the work described by this evidence?"
options:
  - { id: db_team,  description: "Database, storage, replication, or query performance." }
  - { id: app_team, description: "Application logic, API behavior, or deploy artifacts." }
  - { id: sre,      description: "Infrastructure, capacity, or availability response." }
  - { id: network,  description: "Connectivity, DNS, TLS, or edge routing." }
template: |
  {"evidence": {{state}}, "criterion": {{question}}, "options": {{options}}}
answerStyle: LETTER        # LETTER (A..P) | YESNO
```

启动时全量校验，任一不过 → **拒绝启动**：

```java
@PostConstruct
void validate() {
    for (DecisionPoint p : points.values()) {
        require(p.options().size() >= 2 && p.options().size() <= 16);
        require(uniqueIds(p.options()));
        require(noUnresolvedPlaceholders(p));
        require(renderedPromptHashIsStable(p));       // 同模板两次渲染哈希一致
        require(!p.modelRevision().equals("latest")); // 禁止浮动版本
    }
    SlotCheckReport report = provider.assertSlots(all());
    if (report.hasFailure())
        throw new IllegalStateException("答案槽位契约不满足，拒绝启动: " + report);
}
```

---

## 6. 缓存键与状态规范化

> **⚠️ 已修正的设计缺陷（2026-09-25，实现阶段发现）**
>
> 原设计让 `registrySha256` **只覆盖模板文字**。实测发现漏洞：
> **只改「选项描述」而模板一字不动时，注册表哈希不变 → 缓存静默命中旧语义结果。**
>
> 但选项描述变了，模型看到的候选就变了，这是实打实的语义变更。
>
> **修正为**：`registrySha256 = sha256(ref + 模板 + optionsSha256)`。
> 已在 `RegistryHasher.registrySha256` 实现，并由
> `optionDescriptionChangeChangesHashes` 测试固定。
>
> 附带的另一个坑：`optionsSha256` 刻意做成**顺序无关**（选项先后是展示细节，不是语义）。
> 但代价是——**只调换选项顺序而不升版本，缓存会命中**。
> 而 SemIf 实测显示「选项反转」导致 **30.6% 的判定翻转**（见复现报告第 8 节）。
> 因此：**调整选项顺序在流程上必须当作契约变更处理（升版本）。**

```java
public final class DecisionKey {
    /** 全部影响语义的输入，一个都不能少。 */
    public static String of(DecisionState state, DecisionPoint p, Provenance prov) {
        return Hashing.sha256Hex(String.join("\u0000",
                p.ref(),                  // pointId@version ← 改语义必须升版本
                p.optionsSha256(),        // 选项集（顺序无关）
                state.hash(),
                prov.modelRevision(),
                prov.backend(),
                prov.providerId(),
                prov.registrySha256()     // 模板改动也进 key
        ));
    }
}
```

### StateNormalizer 的三条纪律

1. **字段白名单**：只允许注册表声明的字段进入 state。防 PII 泄漏，也防「payload 里加了个时间戳导致缓存永不命中」。
2. **数字规范化**：浮点按固定精度量化后再序列化（取 12 位有效位）。否则 `0.1+0.2` 这类差异会让同一逻辑状态产生两个哈希。
3. **哈希在截断之后算**：`state.hash()` 必须对应**实际发出去的内容**。顺序反了，缓存键描述的就是一个从未被发送过的状态。

---

## 7. 审计表

```sql
CREATE TABLE decision_record (
    decision_id        CHAR(64) PRIMARY KEY,
    decided_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    point_id           TEXT   NOT NULL,
    point_version      INT    NOT NULL,
    state_hash         CHAR(64) NOT NULL,
    state_json         JSONB  NOT NULL,          -- 已规范化、已去敏
    distribution       JSONB  NOT NULL,
    argmax_option      TEXT   NOT NULL,
    max_probability    NUMERIC(6,5) NOT NULL,
    band               TEXT   NOT NULL,          -- auto / review / refuse
    policy_version     TEXT   NOT NULL,          -- 策略改了，历史记录仍可解释
    provider_id        TEXT   NOT NULL,
    model_revision     TEXT   NOT NULL,
    backend            TEXT   NOT NULL,
    prompt_sha256      CHAR(64) NOT NULL,
    registry_sha256    CHAR(64) NOT NULL,
    input_tokens       INT    NOT NULL,
    latency_ms         INT    NOT NULL,
    cache_hit          BOOLEAN NOT NULL,
    degraded_reason    TEXT,
    human_outcome      TEXT,                     -- 人工最终结论，回流成评测集
    human_reviewed_at  TIMESTAMPTZ
);
CREATE INDEX ON decision_record (state_hash, point_id);
CREATE INDEX ON decision_record (point_id, decided_at DESC);
CREATE INDEX ON decision_record (model_revision) WHERE human_outcome IS NOT NULL;
```

`human_outcome` 是整个系统的资产：**它把线上流量变成标注数据。**

---

## 8. 策略引擎（纯函数，不含模型）

```yaml
# policy/policy.yaml
version: 3
policyId: routing-v3
bands:
  auto:   { min: 0.90, action: AUTO_APPLY }
  review: { min: 0.60, action: HUMAN_REVIEW }
  refuse: { min: 0.00, action: SAFE_DEFAULT }
overrides:
  - when: { pointId: ticket.route, optionId: db_team, band: auto }
    require: { secondaryMargin: 0.20 }   # 高影响判定额外要求与次优选项的差距
```

```java
public interface PolicyEngine {
    Band band(Decision decision);
    /** 降级时给保守动作，绝不把「没判定出来」当成「概率 0」。 */
    Action onDegraded(String pointId);
}
```

---

## 9. CLI

```bash
semif-gate verify-slot                    # 启动自检独立跑，CI 门禁
semif-gate eval --point ticket.route@3 \
                --set benchmarks/data/authored144.jsonl \
                --provider semif --out results/eval-YYYYMMDD/   # create-only
semif-gate diff --baseline results/eval-A/ --candidate results/eval-B/ \
                --max-flip-rate 0.02 --max-ece 0.05             # 超阈值 exit 1
```

`diff` 就是**模型升级漂移门禁**。SemIf 的数据证明它必要：换后端翻转 3/777，换 4bit 翻转 20/252。

---

## 10. 实施计划（四步，每步可独立验收）

| 步骤 | 内容 | 规模 | 验收标准 |
|---|---|---|---|
| 1 | `contract/` + `state/` | ~350 行 | 同 state 不同键序 → 同哈希；浮点扰动 → 同哈希；改字段 → 哈希变 |
| 2 | `registry/` + 启动自检 + `RuleFallbackProvider` | ~450 行 | 模板改动 → 哈希变且启动失败；选项重复 → 启动失败 |
| 3 | `cache/` + `DecisionGateway` + `HttpProvider` | ~500 行 | 二次调用零 provider 调用；provider 超时 → `DEGRADED` + 保守动作 |
| 4 | `audit/` + `policy/` + `cli eval` + `cli diff` | ~550 行 | 真跑一轮 eval 并产出 diff 报告 |

总计约 1900 行 Java + 150 行 Python 薄封装。每步可独立跑测试。

---

## 11. 评测方案（无生产数据时的替代）

| 维度 | 数据来源 | 指标 |
|---|---|---|
| 判定质量 | 公开标注数据（如 NLI 类任务）+ 自建判定点 | 准确率、平衡准确率 |
| 概率质量 | 同上 | Brier、NLL、ECE |
| 稳定性 | 选项顺序翻转 / 无关上下文 / 缺失证据子集 | argmax 翻转数、弃权率 |
| 缓存效率 | 合成混合工作负载（约 40% 重复状态、30% 必检索、20% 必弃权、10% 需升级） | 命中率、token 节省、金额节省 |
| 成本与延迟 | 同上，网关开/关缓存与门禁对比 | p50/p95 延迟、单次成本 |
| 漂移 | 换 provider / 换量化精度前后 | 翻转率、概率最大偏移 |

**合成工作负载是缺生产数据时最专业的做法**：能精确控制变量，比真实流量的噪声更好讲故事。面试时应主动说明这一替代方案。

---

## 12. 明确不做

- ❌ 语义近邻缓存（除非显式开启并上报误命中率）
- ❌ 网关内执行任何业务动作
- ❌ 分布式 / 多租户 / K8s operator（单进程 + Postgres + 一个 HTTP 接口足够）
- ❌ 让模型输出业务等级或最终动作（那是调用方策略表的事）
- ❌ 把 `p > 0.8` 当作校准置信度（SemIf 已用实测否定了这一点）

---

## 附：待定项

| 项 | 选项 | 建议 |
|---|---|---|
| Java 包名 | `com.semif.gate` / 其他 | 可改 |
| 生产 Provider | logprobs 路线（Ollama/vLLM） / 约束解码路线 | 先做 logprobs（SemIf 直系血统，评测可复用），SPI 为约束解码留位 |
| 缓存存储 | Postgres / Redis+Postgres | 先 Postgres 单表，命中率上来后再加 Redis |
