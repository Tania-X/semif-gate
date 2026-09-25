# 集成实录：两个静默 bug 与一次端到端验证

> 日期：2026-09-25 · 环境：RTX 4090 PLUS · 结果：**端到端 3/3 通过，Δ 全为 0.00e+00**
> 目的：把 Python 判定服务接上真实模型，验证「真实 SemIf → Java 网关」链路的正确性

---

## 1. 最终结果

用真实模型跑三条真实用例（覆盖三个任务族 + 一个精确平局），与 SemIf 在 GPU 上的已发布预测逐位对比：

| id | 判定点 | prompt 哈希 | argmax | 最大 Δ | 结果 |
|---|---|---|---| ---:|---|
| `f2b4ec4930322fe45118` | `semif.rule_application@1` | ✅ | `insufficient` ✅ | **0.00e+00** | **PASS** |
| `a3f18f3a63d45345942b` | `semif.evidence_interpretation@1` | ✅ | `insufficient` ✅ | **0.00e+00** | **PASS** |
| `8c992e17e4fb54b044f5` | `semif.candidate_selection@1` | ✅ | `A` ✅ | **0.00e+00** | **PASS** |

**逐位精确一致，不是「接近」。** 第一条还是那个精确平局（`insufficient = prohibited = 0.474969`），
服务正确识别并给出与字典序取小一致的 argmax。

---

## 2. Bug 一：标签顺序贴错（静默）

### 症状

`a3f18f3a63d45345942b` 上，服务返回：

```
option_ids    = [contradicted, insufficient, supported]      ← 注册表顺序
probabilities = [0.00969, 0.872262, 0.118048]
```

已发布（按行内原始顺序 `supported, insufficient, contradicted`）：

```
probabilities = [0.00969, 0.872262, 0.118048]                ← 数值完全相同
```

**数值一模一样，标签顺序不同** ⇒ `contradicted` 与 `supported` 的概率被互换，Δ = 0.108。

### 为什么危险

- **`prompt_sha256` 校验照样通过**——哈希只证明「问了什么」，不证明「答案贴对了标签」
- argmax 恰好没变（`insufficient` 仍是最高），所以**不会触发任何告警**
- 只有拿已发布的逐位分布对比才暴露

### 根因

答案字母按**下标**分配（`LETTERS[i]`），槽位读出的数值天然属于**渲染时的那个顺序**。
而选项顺序由 `state.option_order` 决定（逐行不同），可能与注册表冻结的顺序不一致。

原实现用 `[o.id for o in point.options]`（注册表顺序）贴标签，而渲染用的是 `row["options"]`
（state 给出的顺序）——两者错位。

### 修复

```python
def _to_result(self, p, point, row, ids, vocab):
    # 标签必须跟随【实际渲染用的顺序】，不能跟随注册表冻结的顺序
    option_ids = [o["id"] for o in row["options"]]
    ...
```

### 定位方法（可复用）

在同一台机器上**直接调 SemIf 官方代码**做对照：

```python
from semif_phase1.direct import _slot_ids, _forward
# 官方路径 → probs = [0.00969, 0.872262, 0.118048]  与已发布完全一致
```

官方路径正确、服务错误 ⇒ 问题在服务层，不在模型或渲染。
再打印 `slots`（`[32,33,34]` = A/B/C）与两个顺序的哈希（`3cc9e3d1` vs `e3ebef64`），
即可确定是「标签与顺序不匹配」而非「模型读错槽位」。

---

## 3. Bug 二：前缀复用给出错误分布（静默）

### 症状

`a3f18f3a63d45345942b` 上分布 Δ = **0.43**，且 `contradicted` 与 `supported` 几近对调。

### 为什么危险

同样是**静默的**：没有异常、没有告警、哈希校验通过、argmax 甚至可能不变。

### 排查过程（三步，每步都被数据推翻一次假设）

| 步骤 | 假设 | 实测结果 |
|---|---|---|
| 1 | 前缀切分点不对 | 对前缀文本单独 `encode` 得 60 tokens，按 `offset_mapping` 从完整 prompt 切是 **59** —— 确实是 bug，但只是**部分**原因（Δ 0.245 → 0.031） |
| 2 | 修好切分就够了 | ❌ Δ 仍有 0.031，且服务输出与已发布仍有系统性错位 |
| 3 | 问题在标签顺序 | ✅ 修复后 Δ = 0 |

### 结论：Qwen3.5 的缓存不能按任意 token 边界切分

Qwen3.5 是**混合架构**（线性注意力 + 因果卷积），其原生缓存不像标准 Transformer
那样可任意切分复用。SemIf 的 `docs/MLX.md` 也专门警告过这点。

**处置**：`server.py` **默认使用全量前向**（`--prefix-reuse` 为实验开关，默认关闭），
并在 provenance 中记录 `execution_shape`，使审计链能区分两种形状。

---

## 4. 顺带修正的其他问题

| 问题 | 现象 | 修复 |
|---|---|---|
| **SSH 自匹配** | `pkill -f "server.py"` 匹配到 SSH 命令自身，杀掉会话 | 用 `pvenv/bin/pytho[n]` 正则技巧避开自匹配 |
| **SSH 挂起** | 后台启动服务时 SSH 等待 stdout 关闭而挂死 | 改为前台跑在保持的 SSH 会话中 |
| **无 `timeout` 命令** | macOS 无 `timeout`，命令静默不执行 | 移除依赖 |
| **测试脚本按位置对比** | 又踩了「按位置而非按语义 id 对齐」的坑——**这正是我自己在文档里反复强调的** | 改为按 `option_id` 对齐 |

---

## 5. 当前链路状态

```
真实 SemIf 模型（Qwen3.5-4B, 4090 PLUS）
        │  full-forward（默认，已验证 Δ=0）
        ▼
semif-service  /render  → 冻结 prompt_sha256
               /decide  → 校验哈希 → 一次前向 → 按渲染顺序贴标签
        │  HTTP
        ▼
semif-gate（Java，181 测试全绿）
   ReplayProvider  ← 离线回放 252 行真实预测
   HttpProvider    ← 接上面的服务
   InMemoryCache / JdbcCache · DecisionGateway · Audit · Policy
```

**已验证**：Python 服务侧（3/3，Δ=0）、Java 侧单元与集成测试（181 全绿）。
**未验证**：Java 的 `HttpProvider` 真实调用该服务（Java 侧测试用的是本地假 HTTP 服务）。

---

## 6. 下一步

1. **Java `HttpProvider` 真实联调**——用真实服务替换假 HTTP 服务，跑通完整链路
2. Java 侧 `JdbcDecisionCache` / `JdbcDecisionAudit` 尚无真实 Postgres 集成测试
3. 端到端演示：真实模型 → 网关 → 缓存命中 → 审计落盘 → 策略分档
