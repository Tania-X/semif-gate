# 判定点注册表设计：来自真实数据的依据

> 本文记录从 SemIf 冻结评测集（`authored144.jsonl`）实测得出的结构，
> 作为 `semif-gate` 判定点注册表的**数据依据**。所有数字均为脚本实测，非估计。

---

## 1. 数据集结构

| 任务族（family） | 行数 | 选项集 | 选项描述种数 | 不同 question |
|---|---:|---|---:|---:|
| `evidence_interpretation` | 48 | `contradicted` / `insufficient` / `supported` | **1** | 24 |
| `rule_application` | 48 | `insufficient` / `permitted` / `prohibited` | **1** | 24 |
| `candidate_selection` | 48 | `A` / `B` / `insufficient` | **1** | 24 |

**合计 144 行 = 3 个任务族 × 24 条判定准则 × 每准则 2 份不同证据。**

## 2. 关键结论：什么固定、什么逐行变化

实测核验：

| 元素 | 是否固定 | 证据 |
|---|---|---|
| **选项 ID 与描述** | ✅ **每族固定** | 每族的「不同选项描述集」= 1 |
| **判据 question** | ✅ 固定（每族 24 条） | 72 个不同 question，其中 36 个被 2 行共享 |
| **证据 state** | ❌ **逐行变化** | 144 行产生 144 个不同 payload，**无一重复** |

**这正是 `decide(state, pointRefs)` 契约的语义**：
> 判定点（判据 + 选项）是**冻结的**；state 是**运行时传入**的。

因此注册表的粒度是**任务族级别的判定点**，例如 `semif.evidence_interpretation@1`，
其 `options` 与 `question` 来自该族的冻结定义，而每行的具体证据通过 `state` 传入。

## 3. 曾担心的陷阱：不成立

`candidate_selection` 的选项描述看似行特定（"Candidate A" / "Candidate B" 指向的行文不同），
一度让人以为**选项描述无法冻结**。

**实测证伪**：`A` 的描述在全部 48 行中**只有一种**——`"Candidate A"`。它是个**占位标签**，
真正的候选内容在 `state` 里。所以选项描述可以安全冻结。

> 这个陷阱值得记住：如果某族真的出现「同一 optionId、不同描述」，那它**就不是一个判定点**，
> 必须按描述再拆分成多个 `pointId`，否则缓存键会指向错的语义。

## 4. Prompt 渲染契约

注册表渲染出的 payload 必须与 SemIf `core.py::direct_messages` **完全一致**：

```
system: "Apply the supplied criterion to the supplied evidence. Choose exactly one listed option. "
        "Respond with only its uppercase letter, with no explanation or reasoning."

user:   {"evidence": <state>, "criterion": <question>, "options": [
           {"letter": "A", "description": <option[0].description>},
           {"letter": "B", "description": <option[1].description>}, ... ]}
```

实测样例（`f2b4ec4930322fe45118`，即那行精确平局）：

```json
{"evidence": "The architect permits reproducing a plan in an internal training booklet and expressly forbids selling copies.", "criterion": "Sell copies only with the architect's sales permission. May copies be sold?", "options": [{"letter": "A", "description": "The supplied information does not settle whether the rule permits the action"}, {"letter": "B", "description": "The stated rule prohibits the action"}, {"letter": "C", "description": "The stated rule permits the action"}]}
```

**注意**：字母按**选项在列表中的下标**分配（A/B/C），不是按 optionId。
交付给调用方的分布仍按**语义 optionId** 报告（`insufficient` / `prohibited` / `permitted`）。

⚠️ **prompt 完整哈希（`prompt_sha256`）无法离线复现**——它还包含 tokenizer 的 chat 模板
（`<|im_start|>` 等控制符）。**必须对着真实服务验证一次**，这是第 4 步的验收项之一。

## 5. 建议的注册表内容

```json
[
  {
    "id": "semif.evidence_interpretation",
    "version": 1,
    "owner": "semif-gate",
    "question": "Assess the claim stated in the criterion against the supplied evidence.",
    "options": [
      {"id": "supported",     "description": "The evidence establishes the claim"},
      {"id": "contradicted",  "description": "The evidence establishes the opposite"},
      {"id": "insufficient",  "description": "The evidence does not establish either"}
    ]
  },
  {
    "id": "semif.rule_application",
    "version": 1,
    "options": [
      {"id": "permitted",   "description": "The stated rule permits the action"},
      {"id": "prohibited",  "description": "The stated rule prohibits the action"},
      {"id": "insufficient","description": "The supplied information does not settle whether the rule permits the action"}
    ]
  },
  {
    "id": "semif.candidate_selection",
    "version": 1,
    "options": [
      {"id": "A",           "description": "Candidate A"},
      {"id": "B",           "description": "Candidate B"},
      {"id": "insufficient","description": "Neither candidate supplies the requested evidence"}
    ]
  }
]
```

### 选项描述：逐字抄取的权威版本

以下是从 `authored144.jsonl` 脚本提取的**精确文字**（每族 48 行完全一致，已断言核验）：

```
--- evidence_interpretation ---
  contradicted : "The evidence establishes the opposite"
  insufficient : "The evidence does not establish either"
  supported    : "The evidence establishes the claim"

--- rule_application ---
  insufficient : "The supplied information does not settle whether the rule permits the action"
  permitted    : "The stated rule permits the action"
  prohibited   : "The stated rule prohibits the action"

--- candidate_selection ---
  A            : "Candidate A"
  B            : "Candidate B"
  insufficient : "Neither candidate supplies the requested evidence"
```

> ⚠️ **必须逐字使用上表，不要凭印象改写。**
> 特别注意三处易错点：
> - `evidence_interpretation` 的 `insufficient` 是 "does not establish **either**"
> - `rule_application` 的 `insufficient` 措辞**完全不同**（"does not settle whether the rule permits the action"）
> - `candidate_selection` 的 `insufficient` 结尾是 "requested **evidence**"，不是 "information"
>
> 差一个字符，prompt 哈希就对不上，缓存键与审计链全部失效。

> ⚠️ 上面 `question` 字段的精确文字仍需从数据逐条抄取（每族 24 条判据）。
> 本文件未列出全部 72 条判据——那属于运行时数据（进 `state`），不是注册表常量。
> 注册表的 `question` 是**该族的固定判据框架**，具体判据通过 state 传入。

## 6. 平局行的特殊价值

`f2b4ec4930322fe45118` 值得作为**固定回归用例**：

```
insufficient = 0.4750
prohibited   = 0.4750     ← 精确平局，margin = 0
permitted    = 0.0501
```

且**字典序与概率降序不同向**（字典序：insufficient < permitted < prohibited；
最高两个是 insufficient 与 prohibited，在字典序里相邻）。

它同时验证三件事：
1. `margin()` 取的是真正的 top-2 之差（0.0），而非字典序相邻项之差（0.425）
2. 平局可被识别，能与真实语义漂移区分
3. 三选项分布的语义对齐正确

## 7. 完整平局清单（供测试）

| 文件 | id | 分布 | argmax |
|---|---|---|---|
| `out-direct-rev` | `f2b4ec4930322fe45118` | insufficient 0.4750 / prohibited 0.4750 / permitted 0.0501 | insufficient |
| `out-pert108` | `c62600144038b1f20132` | B 0.4995 / A 0.4995 / insufficient 0.0010 | B |
| `out-pert108` | `9e00994bdec725f3dba5` | B 0.4644 / A 0.4644 / insufficient 0.0712 | B |
| `out-pert108` | `84bdb2d001c7e024f8d8` | A 0.3721 / B 0.3721 / insufficient 0.2558 | A |
| `out-pert108` | `bd04047294bf97c56669` | prohibited 0.4045 / insufficient 0.4045 / permitted 0.1911 | prohibited |

`margin < 0.02` 的行数：`out-direct-rev` 1 行，`out-pert108` 4 行。
