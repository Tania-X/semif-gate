# semif-gate

Agent 决策网关的**第 1–3 步**实现：把「模型的判定」变成可缓存、可计量、可审计、可评测的基础设施。

> 完整设计见 [`../semif-gate-design.md`](../semif-gate-design.md)。
> 已实现：`contract/`、`state/`、`registry/`、`cache/`、`gate/`、`audit/`、`policy/`，
> 以及三个 Provider（回放 / HTTP / 规则降级）。
> **尚未实现**：`cli/` 与 Python 侧推理服务。

---

## 快速开始

```bash
cd semif-gate

# 跑全部测试（首次运行会自动下载 Maven 到 ~/.m2/wrapper/dists）
./mvnw test

# 安静模式
./mvnw -q test

# 只跑某一类验收
./mvnw test -Dtest='StateNormalizerTest,DecisionKeyTest'
```

**无需预装 Maven**：`mvnw` 会自己找或下载 Apache Maven 3.9.9。

**环境要求**：JDK 17+（`maven.compiler.release=17`，不使用 Java 21 特性）。

---

## 目录结构

```
semif-gate/
├── mvnw / mvnw.cmd / .mvn/wrapper/       Maven Wrapper（自实现，无外部依赖）
├── pom.xml                               依赖：Jackson + JUnit 5（无 Spring、无数据库）
└── src/
    ├── main/java/com/semif/gate/
    │   ├── contract/                     判定契约（纯数据 + 构造校验）
    │   │   ├── Decision.java             判定结果：完整分布 + 溯源 + 降级状态
    │   │   ├── DecisionPoint.java        不可变判定点契约（pointId@version）
    │   │   ├── Option.java               选项：语义 ID + 描述
    │   │   ├── OptionScore.java          概率得分，构造即拒绝 NaN / Infinity / 越界
    │   │   ├── Provenance.java           溯源元数据（进缓存键与审计记录）
    │   │   ├── AnswerStyle.java          LETTER (A..P) / YESNO
    │   │   ├── Band.java                 不确定性档位：AUTO / REVIEW / REFUSE
    │   │   └── Action.java               动作建议：AUTO_APPLY / HUMAN_REVIEW / SAFE_DEFAULT
    │   ├── state/                        状态规范化与缓存键（正确性核心）
    │   │   ├── CanonicalJson.java        确定性 JSON：键排序 + 浮点量化到 12 位有效数字
    │   │   ├── JsonTree.java             最小 JSON 解析器（只服务于结构化截断）
    │   │   ├── DecisionState.java        规范化状态；保证「哈希 == 实际内容的哈希」
    │   │   ├── StateNormalizer.java      字段白名单 + 截断 + 先截断后哈希
    │   │   ├── StateHasher.java          SHA-256 工具
    │   │   └── DecisionKey.java          缓存键：七个分量全参与
    │   ├── registry/                     判定点注册表与启动自检
    │   │   ├── DecisionPointRegistry.java 加载即校验，不过就拒绝启动
    │   │   ├── DecisionPointLoader.java   JSON 资源读取
    │   │   ├── PromptRenderer.java        冻结模板渲染 + 确定性校验
    │   │   ├── RegistryHasher.java        选项集 / prompt / 注册表三个哈希
    │   │   ├── SlotCheck.java             答案槽位契约检查接口与结果类型
    │   │   ├── SlotCheckReport.java       汇总报告 + 拒绝启动的异常消息
    │   │   ├── TokenizerSlotChecker.java  基于词表的纯 Java 实现
    │   │   └── RegistryException.java     拒绝启动
    │   ├── cache/                        判定缓存（两级校验）
    │   │   ├── DecisionCache.java        缓存 SPI + 查找键定义
    │   │   ├── CachedDecision.java       条目：结果 + 写入时的契约指纹
    │   │   ├── InMemoryDecisionCache.java 进程内实现（含命中/陈旧计数）
    │   │   ├── JdbcDecisionCache.java    Postgres 实现（DataSource 注入）
    │   │   └── CacheAccessException.java 缓存访问失败
    │   ├── provider/
    │   │   ├── DecisionProvider.java      Provider SPI（Java 侧与模型侧的唯一分界）
    │   │   ├── ReplayProvider.java        回放真实 SemIf 预测（离线验证主力）
    │   │   ├── HttpProvider.java          HTTP 调推理服务（prompt 哈希必须一致）
    │   │   └── RuleFallbackProvider.java  永不失败的降级实现
    │   ├── gate/
    │   │   ├── DecisionGateway.java       系统唯一入口（独占 decisionId 构造）
    │   │   └── GatewayResult.java         判定 + 审计记录 + 代价统计
    │   ├── audit/
    │   │   ├── DecisionRecord.java        审计记录（含 margin）
    │   │   ├── DecisionAudit.java         落盘 SPI
    │   │   ├── InMemoryDecisionAudit.java 进程内实现
    │   │   ├── JdbcDecisionAudit.java     Postgres 实现（批量单事务）
    │   │   └── AuditAccessException.java  审计失败（必须向上抛）
    │   └── policy/
    │       ├── PolicyEngine.java          策略 SPI（纯函数）
    │       └── ThresholdPolicyEngine.java 阈值分档
    ├── main/resources/
    │   ├── decision-points/               判定点定义（JSON）
    │   └── db/V1__init.sql                Postgres DDL
    └── test/                              181 个测试，见下节
```

---

## 三个锁死的设计决策

这三条来自设计文档，本实现严格遵守：

### 1. 判定点是不可变契约，版本进缓存键

`pointId@version` 是缓存键的第一分量。改动措辞、增删选项、调整描述文字都必须升版本——
否则缓存会**静默**返回旧语义的判定结果，且不会有任何报错。

注册表在加载时会拒绝 `latest` / `head` / `main` / `master` 等浮动 revision：
浮动值会让「这条判定是谁做的」永远无法回答。

### 2. 网关只返回「分布 + 判定」，绝不执行动作

`Decision` 永远携带**完整概率分布**（按 optionId 字典序），而不是一个被选中的答案。
`Band` 描述不确定性，`Action` 是业务策略的选择——两者刻意分开，且都不由本层执行。

### 3. 缓存键 = 精确规范化 state 哈希，不做近似命中

不做语义近邻缓存。精确哈希命中是**正确性保证**，不是性能优化。

### 4. 选项顺序是语义的一部分（实测修正）

`optionsSha256` **顺序敏感**。SemIf 的答案字母按选项**下标**分配（`LETTERS[index]`），
所以顺序一变，字母 ↔ 选项映射就变，prompt 文本随之改变。

在 GPU 机器上用真实 tokenizer 逐条实测：

| 渲染方式 | 命中已发布 `prompt_sha256` |
|---|---:|
| 行内原始顺序 | **144/144** |
| 字典序 | **0/144** |

而顺序改变导致的行为差异是 **30.6%** 的判定翻转，换 GPU 只有 **0.7%**——**差 40 倍**。

原实现用 `TreeMap` 排序把顺序排除在哈希之外，会导致「只调换顺序、版本未升时缓存静默命中旧语义」。
现在顺序进哈希：调换顺序必然改变 `optionsSha256` → 缓存键改变 → 旧条目自然失效。
**防线从「流程上记得升版本」变成「机制上无法复用」。**

---

## 缓存键的七个分量

`DecisionKey.of(...)` 的每个分量缺失都对应一类真实事故：

| 分量 | 漏掉它会怎样 |
|---|---|
| `pointId@version` | 改了准则措辞但版本没升，缓存继续返回旧语义 |
| `optionsSha256` | 增删选项后，旧分布被当成新选项集的答案 |
| `state.hash()` | 不同输入共用同一条缓存 |
| `modelRevision` | 换模型后仍读旧模型的判定 |
| `backend` | 换执行后端（数值实现不同）后结果被静默复用 |
| `providerId` | 切换 provider 后新旧结果混在一起，无法做漂移对比 |
| `registrySha256` | 模板或选项描述被改动后，缓存键却不变 |

分量之间用 `\u0000`（NUL）分隔——它不可能出现在正常内容里，
因此不存在「两个不同分量拼出同一个字符串」的歧义（用 `:` 之类的分隔符会产生这种碰撞）。

---

## 关于「哈希在截断之后算」

这条纪律不是靠调用方自觉遵守，而是**由类型保证**的：

- `DecisionState` 的构造入口不公开，只能通过 `StateNormalizer.normalize()` 或 `truncate()` 产生；
- `truncate()` 返回**新实例**并重新计算哈希；
- 因此不变式 `state.hash() == sha256(state.json())` 永远成立。

截断是**结构化**的：从对象尾部丢弃键值对（数组则从尾部丢弃元素），必要时裁剪最长的字符串，
并在结果里留下 `__semif_truncated__` 标记。这样截断结果仍然是合法 JSON——
字符级切一刀很可能会切在字符串中间，产出坏数据送进模型，而**这种失败是静默的**。

长度按 **Unicode 码点**而非 `char` 计数，避免在代理对（emoji、部分生僻字）中间切断。

---

## 答案槽位契约

SemIf 的读出口依赖一个硬前提：**每个选项的答案必须恰好是一个 token，且往返一致**。
前提不成立时，读到的 logits 不是那个选项的概率，而程序不会报错。

`TokenizerSlotChecker` 覆盖三项可通过词表验证的检查：

1. 每个答案字母恰好一个 token
2. 该 token 解码回来就是那个字母
3. 不同字母不映射到同一个 token

它**不覆盖**第四项——「在已渲染 prompt 末尾追加字母不改变已有 token 序列」（需要真实 tokenizer
编码 prompt）。这个缺口通过 `TokenizerSlotChecker.uncoveredChecks()` **显式声明**，
避免读者误以为「槽位检查通过」等于「完整契约通过」。

槽位检查失败时 `registry.verifyOrRefuseStartup(...)` 抛出 `RegistryException`，
异常消息包含具体的判定点与失败原因。**跳过（SKIPPED）不阻止启动，但必须带原因出现在报告里。**

---

## 测试

**181 个测试，全部通过。**

| 测试类 | 数量 | 覆盖 |
|---|---:|---|
| `StateNormalizerTest` | 16 | 哈希稳定性、浮点量化、敏感性、截断顺序 |
| `CanonicalJsonTest` | 17 | 序列化确定性（Map 实现无关、数字量化、转义、非法值拒绝） |
| `DecisionKeyTest` | 12 | 七个分量逐项敏感性 + 分隔符歧义防护 |
| `DecisionPointRegistryTest` | 24 | 模板改动、选项重复、选项数越界、`revision=latest`、**选项顺序敏感**、启动自检 |
| `TokenizerSlotCheckerTest` | 10 | 槽位契约：单 token、往返、冲突、跳过语义 |
| `RuleFallbackProviderTest` | 13 | 降级契约：必须标记 DEGRADED、确定性、不伪装高置信 |
| `ReplayProviderTest` | 13 | **真实 fixture 映射**：配对再排序、顺序无关、真实平局、选项集不符即降级 |
| `HttpProviderTest` | 13 | 本地假服务：prompt 哈希校验、超时、非 200、非法概率、不可达 |
| `CacheSemanticsTest` | 6 | **契约指纹两级校验**、命中不调 provider、缓存故障回退 |
| `CacheImplementationTest` | 8 | 分布编解码、查找键、往返一致、并发、**调换顺序即失效** |
| `GatewaySemanticsTest` | 10 | provider 异常降级、decisionId 确定性、网关补齐契约指纹 |
| `AuditCompletenessTest` | 9 | 每条判定都有记录、字段齐全、**margin 识别平局**、两条缓存路径都审计 |
| `ThresholdPolicyEngineTest` | 10 | 分档正确、**降级永远落 REFUSE**、纯函数、配置校验 |
| `OptionScoreTest` / `DecisionTest` / `MarginOrderTest` / `DecisionPointContractTest` | 20 | 契约构造校验、平局可识别、margin 取真正 top-2 |

几个值得注意的测试：

- **真实数据而非编造**：`ReplayProviderTest` 全部基于 GPU 上跑出的真实 SemIf 预测。
  其中 `14790d6d50043b03420c` 的 `option_ids` 书写顺序既非字典序也非概率序，
  正好验证「先配对再排序」——按下标搬运会把概率安到错误选项上且不报错。
- **真实平局**：`f2b4ec4930322fe45118` 是实测中 `insufficient = prohibited = 0.4750` 的精确平局，
  `margin` 必须为 0。这是把「舍入噪声」与「语义漂移」区分开的依据。
- **降级永远落 REFUSE**：构造一个「降级但最高概率 0.99」的分布，
  断言它仍然落最低档——否则「没判定出来」会被当成「判定为某个答案」。
- **契约指纹拦截**：写入后改变注册表哈希，断言必须未命中且计入 `staleRejections`。
- **顺序敏感的落地效果**：调换选项顺序 → `optionsSha256` 改变 → 同一 state 不再命中缓存。

测试**不连外网、不需要数据库、不需要 GPU**：HTTP 用 JDK 自带 `HttpServer` 起本地假服务。

---

## 与设计文档的偏离

| 项 | 设计文档 | 本实现 | 原因 |
|---|---|---|---|
| 判定点定义格式 | YAML | **JSON** | 本次要求「依赖尽量少」，只引入 Jackson 即可解析；换 YAML 只需替换 `DecisionPointLoader` 内部，校验逻辑不受影响 |
| `registrySha256` 覆盖面 | 「模板改动也会进 key」 | **模板 + `optionsSha256`** | 实测发现只覆盖模板时，**改选项描述不会让缓存失效**——描述变了模型看到的候选就变了，属于语义变更。已修正并在测试中固定 |
| 启动自检框架 | `@PostConstruct`（Spring） | `verifyOrRefuseStartup(...)` 显式调用 | 本次明确不引入 Spring；语义相同（不过就抛异常），调用时机交给上层 |
| 槽位检查 | 接口 + 结果类型 | 额外提供 `TokenizerSlotChecker` 纯 Java 实现 | 要求「只做接口和纯 Java 可测的部分」，词表级检查正好满足，且能验证前三项契约 |
| 判定点 ID | 未规定形态 | 强制 `[a-z][a-z0-9._-]*` | ID 进缓存键，形态约束能挡住手滑写出的怪值 |
| `Decision` | 未提及 | 增加 `argmaxOption()` / `margin()` / `asMap()` | 策略层与漂移对比都要用；`margin()` 是识别平局翻转的必要信息 |
| `DecisionProvider.decide` 返回类型 | `Map<String, Decision>` | **`Map<String, ScoredPoint>`** | 原签名形成循环依赖：provider 算不出 `decisionId`（键里含注册表哈希与 provider 自身的执行元数据）。改为 provider 只交分数，**网关独占 `decisionId` 与契约指纹的构造** |
| 缓存候选键 | `sha256(state, pointRef, registrySha256, optionsSha256)`，命中后再校验 registry | **查找键 = `sha256(state, pointRef)`；`registrySha256` 作为参数传入用于显式校验** | 原设计两条规则互相抵消：键里已含 registrySha256，注册表一变键就变、根本查不到，第二级校验成了死代码。且 `lookup(stateHash, point)` 签名里没有 registrySha256，实现无法算出规格所述的键 |
| `optionsSha256` | 顺序无关（TreeMap 排序） | **顺序敏感**（按列表顺序拼接） | 实测修正，见上文「决策 4」。顺序改变导致 30.6% 翻转 |
| 降级结果的 `promptSha256` | 未规定 | **填真实 prompt 哈希** | prompt 渲染不依赖模型，降级只是「没拿到分数」而非「不知道问了什么」。填占位符会让审计丢失「当时问的是哪个问题」，且违反 `Provenance` 自身契约 |

---

## 尚未实现（第 4 步）

- `cli/` —— `eval` / `diff` / `replay` / `verify-slot`（漂移门禁是重点）
- Python 侧推理服务（约 150 行 FastAPI，复用 `semif_phase1.direct` / `mlx_backend`）
- `JdbcDecisionCache` / `JdbcDecisionAudit` 的真实数据库集成测试

**刻意不做**：接真实模型或 tokenizer、Spring、任何需要 GPU 或外网的代码。
