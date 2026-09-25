# 性能基准输入

`PerfBenchMain` 使用的输入数据。三份文件**同源生成**，判定点 ID 与映射必须配套使用。

| 文件 | 内容 |
|---|---|
| `perf-input-252.jsonl` | SemIf 冻结评测集 `authored144` + `perturbations108` 的输入行（id / question / state / options） |
| `perf-mapping.json` | 行 id → 判定点 id 的映射 |
| `perf-same-point-new-state.jsonl` | 96 行子集：同一判定点、不同 evidence，用于测「新证据」路径 |

## 为什么需要这三份

**判定点身份 = (判据, 选项集, 选项顺序, 模型 revision)。**
选项顺序改变会让答案字母的分配改变，进而改变 prompt 与模型行为
（实测顺序对判定的影响达 30.6%，换 GPU 只有 0.7%）。

实测这 252 行里有 **188 个不同的 (判据, 选项顺序) 组合**——
同一判据下选项顺序也可能不同（36 个判据出现多种顺序）。

所以映射关系必须由**同一份数据生成**，不能让两侧各自推导 ID：
任何推导差异都会让 prompt 哈希校验失败（实测 409）。

## 复现方式

```bash
java -cp <classpath> com.semif.gate.e2e.PerfBenchMain \
    http://127.0.0.1:8080 \
    perf-fixtures/perf-input-252.jsonl \
    <semif-service>/registry/decision-points.json \
    perf-fixtures/perf-mapping.json \
    240 \
    perf-fixtures/perf-same-point-new-state.jsonl
```

注意 `decision-points.json` 来自 [semif-service](https://github.com/Tania-X/semif-service)，
必须与本目录的映射配套（那 188 个判定点由这批数据生成）。

## 数据来源

SemIf 的冻结评测集，项目自有（Project authored）。见
[SemIf](https://github.com/TheoLeeCJ/SemIf) 的 `benchmarks/data/`。
