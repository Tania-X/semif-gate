# 集成结论：选项顺序是语义的一部分

> 日期：2026-09-25 · 在 RTX 4090 PLUS 上用真实 tokenizer 逐条实测得出
> 影响：`semif-gate` 契约、`optionsSha256` 定义、缓存键、注册表结构

---

## 1. 决定性证据

拿 SemIf 的 144 行冻结评测集，用**真实 tokenizer** 渲染 prompt，与 GPU 上跑出的
已发布 `prompt_sha256` 逐行对照：

| 渲染所用的选项顺序 | 哈希命中 |
|---|---:|
| **行内原始顺序** | **144 / 144** ✅ |
| 字典序 | **0 / 144** ❌ |

进一步验证「能否用族级统一顺序」：

| 用例 | 行内顺序 | 换成族内最常见顺序 |
|---|---|---|
| `a3f18f3a63d45345942b` | ✅ 命中 | ❌ 不命中，且哈希改变 |
| `f2b4ec4930322fe45118` | ✅ 命中 | ❌ 不命中，且哈希改变 |
| `695dd61195ca025c4f4c` | ✅ 命中 | ❌ 不命中，且哈希改变 |

**结论：顺序改变 → prompt 改变 → 哈希改变。没有任何"等价顺序"。**

---

## 2. 为什么会这样：字母是按位置分配的

SemIf `core.py::direct_messages` 的关键两行：

```python
options=[{"letter": LETTERS[index], "description": option["description"]}
         for index, option in enumerate(row["options"])]
```

**答案字母由选项在列表中的下标决定**（`A` = 第 0 个）。所以：

```
选项顺序变了  →  字母↔选项 的映射变了  →  prompt 文本变了  →  模型行为变了
```

这不是实现瑕疵，而是「按位置分配标签」这一设计的必然结果。

---

## 3. 与实测行为一致

这与另外两组已实测数据完全自洽：

| 观察 | 数值 | 出处 |
|---|---|---|
| 选项反转导致的判定翻转率 | **30.6%**（11/36） | 本次扰动集实测 |
| 已发布语义不变变换中的最高翻转 | 选项反转 10/36 | SemIf `docs/RESULTS.md` |
| 换 GPU 架构的翻转率 | 0.7%（1/144） | 本次跨硬件复现 |

**选项顺序对判定的影响，比换一张显卡大 40 倍。**

---

## 4. 每行的顺序都不同（实测分布）

每族有 **6 种**顺序，最常见的只覆盖少数行：

| 任务族 | 顺序种数 | 最常见顺序覆盖 |
|---|---:|---|
| `evidence_interpretation` | 6 | 13/48 |
| `rule_application` | 6 | 11/48 |
| `candidate_selection` | 6 | 15/48 |

**不存在「族级固定顺序」**——顺序是逐行的。

---

## 5. 对架构的四条结论

### 5.1 判定点身份

> **判定点 = (判据, 选项集, 选项顺序, 模型 revision)，四者任一变化都是语义变更。**

### 5.2 `optionsSha256` 必须**顺序敏感**

原设计（在 `RegistryHasher` 中实现并被注释辩护）刻意让选项哈希**顺序无关**，
理由是「先后位置是展示细节，不是语义」。

**该理由已被数据推翻。** 必须改为顺序敏感，否则「只调换顺序不升版本」会导致缓存静默命中。

> 注：实现里的注释已经预警了这个风险（「如果只调换了选项顺序而没有升版本，缓存会命中」），
> 现在有了实测依据——**那个预警必须升级为硬约束，而不是注释里的一句提醒**。

### 5.3 字母↔选项映射属于 `state`，不属于注册表常量

因为顺序是逐行的，**固定注册表无法提供逐行映射**。

建议在传入 state 中显式携带：

```json
{
  "criterion": "...",
  "evidence": "...",
  "option_order": ["contradicted", "insufficient", "supported"]
}
```

判定点的 `options` 只声明**选项集与描述**（描述确实是冻结的，实测每族仅 1 种）；
**顺序由 state 给出**。prompt 渲染按 `option_order` 排列，字母按其下标分配。

**缓存影响**：`option_order` 进 state ⇒ 自动进 `stateHash` ⇒ 自动进缓存键。无需额外机制。

### 5.4 prompt 哈希校验是不可省的

Java 侧声明 `prompt_sha256`、Python 侧渲染后校验，**顺序不一致会直接 409**，
而不是静默返回错位的概率。这是本次设计里最有价值的一道防线。

---

## 7. 前缀复用的实现陷阱（实测）

写 `semif-service` 的共享前缀逻辑时踩到一个**静默错误**，记录如下。

### 错误做法：对前缀文本单独 `encode`

```python
boundary = prompt.index(prefix_text) + len(prefix_text)
prefix = tokenizer.encode(prompt[:boundary], add_special_tokens=False)   # ❌
```

**BPE 在边界处会合并**，单独编码得到的 token 序列与完整 prompt 上的切分不同：

```
该 token 覆盖: (325, 332) ' copies'
下一个 token  : (332, 335) '."',
单独 encode 前缀文本得到: 60 tokens
按 offset 从完整 prompt 切出: 59 tokens     ← 不同
```

后果是**静默的**：分布变了，但 argmax 没变，所以不会被察觉。

| 路径 | 分布（insufficient / prohibited / permitted） | 与已发布最大偏差 |
|---|---|---:|
| 已发布（GPU 实跑） | 0.474969 / 0.474969 / 0.050061 | — |
| 全量前向（单次 prefill） | 0.474969 / 0.474969 / 0.050061 | **0.000000** ✅ |
| 前缀复用（错误切分） | 0.720160 / 0.233802 / 0.046038 | **0.245191** ❌ |
| 前缀复用（offset 修正后） | 0.506198 / 0.446718 / 0.047084 | 0.031229 |

### 正确做法：在完整 prompt 上按 `offset_mapping` 切

```python
enc = tokenizer(prompt, add_special_tokens=False, return_offsets_mapping=True)
cut = 最后一个满足 `end <= boundary` 的 token 下标 + 1
prefix = enc["input_ids"][:cut]
```

并加断言 `ids[:cut] == tokenizer.encode(prompt)[:cut]` 兜底。

### 残留偏差的诚实结论

修正后仍存在 **0.031** 的偏差，argmax 不变。这与 SemIf 自己记录的现象一致
（其 serial/shared 模式会改变部分 argmax；本次实测 direct vs serial 为 0/144 翻转）。

**当前取舍**：`semif-service` 保留前缀复用（长 state 场景收益显著），
但**必须在审计里记录执行形状**（`shared_prefix_reused`），
并且在需要与已发布值逐位对齐的验证场景下改用全量前向路径。

---

## 6. 遗留：族级注册表与逐行顺序的关系

本文件描述的张力尚未在代码中完全落实。当前 `semif-service/registry/decision-points.json`
冻结的是「族内最常见顺序」，它**只能复现该族约 25% 的行**。

要让 144/144 全部通过哈希校验，注册表必须**逐行携带顺序**（或等价的映射表）。

**这是第 4 步的待办**，需与 Java 侧 `DecisionPointRegistry` 的粒度一起决定：

| 方案 | 判定点数量 | 说明 |
|---|---|---|
| 甲：族级判定点 + state 携带顺序 | 3 | 推荐。顺序进 state，缓存键自然正确 |
| 乙：逐行判定点 | 144+ | 注册表膨胀，但顺序成为契约常量 |

**推荐甲**：顺序本来就是数据（逐行），不是契约常量。放在注册表里会让注册表随数据增长。
