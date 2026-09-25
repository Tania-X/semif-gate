# 端到端走查：一条真实判定走完全流程

> 用一条**真实业务案例**（来自 SemIf 的冻结评测集）走完整个链路，
> 每一步给出真实数据。适合作为演示脚本。
>
> 所有数字均为 **RTX 4090 PLUS + Qwen3.5-4B (bf16)** 上的实跑结果。

---

## 0. 业务背景

**场景**：合同/许可条款的自动合规检查。

**要回答的问题**：某条业务规则说「只有在建筑师许可销售时才可销售副本」，
而当前证据显示「建筑师允许内部培训手册复制，并明确禁止销售副本」。
**那么，现在可以销售副本吗？**

这正是那种**散布在业务代码里的 `if/else`** ——
规则是自然语言，证据是非结构化文本，而判定结果要驱动后续动作。

---

## 1. 第 1 层：判定点定义（冻结的契约）

这是**唯一需要人工设计**的部分，且一旦定稿就冻进版本控制。

```jsonc
{
  "id": "semif.bench.0cc61fdd0b37",
  "version": 1,
  "owner": "compliance-team",
  "question": "Sell copies only with the architect's sales permission. May copies be sold?",
  "options": [
    {"id": "insufficient", "description": "The supplied information does not settle whether the rule permits the action"},
    {"id": "prohibited",   "description": "The stated rule prohibits the action"},
    {"id": "permitted",    "description": "The stated rule permits the action"}
  ]
}
```

### 这一步的三个硬约束（为什么不能随便改）

| 约束 | 原因 |
|---|---|
| 选项描述是**冻结常量** | 表述本身影响模型判定 |
| **选项顺序是语义的一部分** | 答案字母按**下标**分配（`LETTERS[i]`）。实测顺序改变导致 **30.6%** 判定翻转 |
| version 进缓存键 | 改一个字就必须升版本，否则缓存会**静默命中旧语义** |

> **为什么需要一个"顺序"这种看起来无关的东西？**
> 因为模型是在一个字母表上给概率。`A/B/C` 对应哪个选项，是**契约规定的**。
> 换个顺序，模型看到的输入就变了。
>
> 实测：按这个顺序渲染 prompt 可以 **144/144** 命中已发布哈希，换成字典序 **0/144**。

---

## 2. 第 2 层：运行时调用

业务代码只做一件事——**把证据交进去**：

```java
Map<String, Object> evidence = Map.of(
    "evidence", "The architect permits reproducing a plan in an internal training booklet "
              + "and expressly forbids selling copies.");

GatewayResult result = gateway.decide(
        evidence,
        List.of("semif.bench.0cc61fdd0b37@1"));

Decision d = result.decision("semif.bench.0cc61fdd0b37");
```

**注意：模型里没有任何业务知识。** 换一条规则、换一个业务域，只换判定点定义和证据，**不换模型**。

---

## 3. 黑盒内部：7 步

```
① state 规范化      字段白名单 + 结构化截断 → 算 stateHash
② 查缓存            命中则直接返回，零模型调用
③ 未命中 → HTTP     把 state + pointRefs 发给判定服务
④ 服务端渲染 prompt 用冻结模板，逐字节复现
⑤ 校验 prompt 哈希  与调用方声明的不一致 → 409，拒绝返回
⑥ 一次前向          读 A/B/C 三个槽位的 logits → softmax
⑦ 返回 + 落审计     完整分布 + 溯源信息
```

### 第 ④ 步：实际发出的 prompt

```
<|im_start|>system
Apply the supplied criterion to the supplied evidence. Choose exactly one listed option.
Respond with only its uppercase letter, with no explanation or reasoning.<|im_end|>
<|im_start|>user
{"evidence": "The architect permits reproducing a plan in an internal training booklet and
expressly forbids selling copies.", "criterion": "Sell copies only with the architect's
sales permission. May copies be sold?", "options": [{"letter": "A", "description": "The
supplied information does not settle whether the rule permits the action"}, {"letter": "B",
"description": "The stated rule prohibits the action"}, {"letter": "C", "description": "The
stated rule permits the action"}]}<|im_end|>
<|im_start|>assistant
<think>

</think>

```

**这段文本的 SHA-256 就是 `promptSha256`。** 它证明「我们到底问了什么」——
只要它一致，这次判定就可以被复现和审计。

### 第 ⑥ 步：模型给了什么

```
A (insufficient) = 0.474969
B (prohibited)   = 0.474969     ← 并列第一
C (permitted)    = 0.050061
```

**这是一次精确平局。**

---

## 4. ⭐ 为什么"只返回一个答案"是错的

这是整条链路最能说明问题的地方。看这一行数据的真相：

| | |
|---|---|
| **Gold（人工标注的正确答案）** | **`prohibited`（即 B）** |
| 模型给 B 的概率 | **0.474969（并列第一）** |
| 但 `argmax` 取字典序最小者 | → 返回 **`insufficient`（A）** ❌ |

**如果这层 API 只返回一个"选中的答案"，我们就会拿到错误结果——
而正确答案明明就在并列第一的位置上。**

这不是极端构造。实测发现：

- 252 行评测集中，`margin < 0.02` 的有 **5 行**
- 同一配置两次独立运行，会翻转 **2/144** —— **全部**发生在这类接近平局的行上

**所以契约规定：永远返回完整分布。** 让策略层看见「这是一次平局」，而不是替它做一个它做不了的判断。

---

## 5. 第 4 层：输出契约

```jsonc
{
  "decisionId": "9f2c8a41…",              // 确定性，可复现，即缓存键
  "pointId": "semif.bench.0cc61fdd0b37",
  "pointVersion": 1,
  "outcome": "OK",                        // OK | DEGRADED
  "distribution": [                       // 【按 optionId 字典序】
    {"optionId": "insufficient", "probability": 0.474969},
    {"optionId": "permitted",    "probability": 0.050061},
    {"optionId": "prohibited",   "probability": 0.474969}
  ],
  "provenance": {
    "providerId": "semif-py",
    "modelRevision": "851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a",
    "backend": "cuda:0",
    "promptSha256": "182c69c28b1efda301044ed0dd652e23a600cdbcc96dce5bdf88dd1a6231aed1",
    "inputTokens": 150,
    "latencyMs": 95
  },
  "degradedReason": null
}
```

**注意 `distribution` 是按 `optionId` 字典序的，不是按字母顺序。**
调用方必须**按键取值**，不能按下标——这是实测踩出来的坑（按下标对齐会让两个选项的概率互换）。

---

## 6. 第 5 层：策略层（纯 Java，不碰模型）

网关**只给判定，不给动作**。动作由业务侧的策略决定：

```java
Decision d = result.decision(pointId);

if (!d.ok()) {
    // 降级：走保守分支，绝不当成"概率为 0"
    return escalateToHuman(d.degradedReason());
}

if (d.margin() < 0.05) {
    // ⚠️ 平局带 → 不自动执行
    // 本例 margin == 0.000000，正是这一支
    return queueForReview(d);
}

String winner = d.argmaxOption();          // 确定性：平局取字典序最小
if (d.probabilityOf("prohibited").orElse(0) > 0.90) {
    return blockSale();                     // 高置信度 → 自动执行
}
return queueForReview(d);
```

### 本例的最终结论

```
margin = 0.000000  <  0.05
→ 进入人工复核队列（不自动放行，也不自动拦截）
```

**这个结果是对的**：模型无法据此判定，因为证据里「允许内部复制」和「禁止销售」同时存在，
连人工也需要确认。**系统没有假装自己知道。**

---

## 7. 审计记录（每一次判定都留痕）

```jsonc
{
  "decisionId": "9f2c8a41…",
  "pointId": "semif.bench.0cc61fdd0b37",
  "pointVersion": 1,
  "stateHash": "31c69679…",
  "distribution": [ … ],
  "argmaxOption": "insufficient",
  "maxProbability": 0.474969,
  "margin": 0.000000,                    // ← 区分「平局舍入」与「真实语义漂移」
  "band": "REVIEW",
  "policyVersion": "compliance-v1",      // 策略改了，历史记录仍可解释
  "providerId": "semif-py",
  "modelRevision": "851bf6e8…",
  "backend": "cuda:0",
  "promptSha256": "182c69c2…",
  "registrySha256": "…",
  "inputTokens": 150,
  "latencyMs": 95,
  "cacheHit": false,
  "degradedReason": null
}
```

**这张表是整个系统的资产**：它让每一次自动判定都能被回答
「当时问了什么、模型说了什么、按哪版策略做的决定」。

---

## 8. 完整时序

```
业务代码               网关                判定服务            模型
   │                    │                     │                │
   ├─ decide(证据, 判定点)┤                     │                │
   │                    ├─ 规范化 + 算哈希      │                │
   │                    ├─ 查缓存 ── 命中 ──► 直接返回           │
   │                    │      └─ 未命中        │                │
   │                    ├───── POST /decide ───►│                │
   │                    │                     ├─ 渲染 prompt     │
   │                    │                     ├─ 校验哈希（不符→409）
   │                    │                     ├─── 一次前向 ────►│
   │                    │                     │◄── A/B/C logits ─┤
   │                    │                     ├─ softmax         │
   │                    │◄── 分布 + 溯源 ──────┤                │
   │                    ├─ 组装 Decision（算 decisionId）        │
   │                    ├─ 写缓存 + 落审计                        │
   │◄── 完整分布 ───────┤                     │                │
   │                                                             │
   ├─ 策略层：margin=0 → 转人工                                   │
```

---

## 9. 这条走查能说明什么

| 观察 | 说明的问题 |
|---|---|
| **模型不知道任何业务规则** | 规则和证据都在请求里。换业务不换模型 |
| **promptSha256 可复现** | 「当时问了什么」有据可查 |
| **返回完整分布而非单一答案** | 本例中 argmax 是**错的**，正确答案在并列位置 |
| **策略层看不到模型** | 阈值、动作、降级路径全是纯 Java，可单测 |
| **降级是一等状态** | 服务挂了返回 DEGRADED + 原因，不伪装成"概率 0" |
| **每次判定都留痕** | 事后可回答"为什么自动拦了/放了" |

### 一句话总结

> 我们把「让模型输出一段话再解析回 if」换成了
> **「让模型输出一个带概率的枚举，然后像调任何不可靠的外部服务一样对待它」**——
> 有契约、有缓存、有熔断、有降级、有审计。

**模型仍然是不可靠的。区别在于：现在它不可靠的方式是可控的。**
