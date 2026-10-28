# Agent / 工单系统评测报告

> 复制本文件为 `eval/results/report-YYYYMMDD.md` 后填写。
> 数字必须来自 `run_agent_eval.py` / `run_tool_eval.py` 的产物，不要手填“感觉”。

## 1. 评测环境

| 项 | 值 |
|---|---|
| 日期 | 2026-xx-xx |
| 机器 | Windows / CPU / 内存 |
| JDK / Node | JDK 17 / Node xx |
| 项目一 | Spring Boot 4.1.1，:10001 |
| Agent | Spring Boot 3.5.15 + Spring AI 1.1.2，:10002 |
| 模型 | provider / model / temperature |
| RAG | 开 / 关；Milvus 版本；embedding 模型 |
| MySQL / Redis | 本地单实例；Redis db4/db5 |
| 数据集 | cases-agent.json（30 条） / cases-rag.json（12 条） |

---

## 2. Agent 质量

### 2.1 全量

| 指标 | 结果 | 备注 |
|---|---:|---|
| 任务通过率 | % | |
| 工具选择准确率 | % | |
| 参数准确率 | % | 只统计标注了参数的 case |
| 答案关键词通过率 | % | |
| SSE done 率 | % | |
| 错误数 | | |

### 2.2 分类

| 类别 | 用例数 | 通过 | 通过率 | 主要失败原因 |
|---|---:|---:|---:|---|
| data | 7 | | | |
| rag | 8 | | | |
| mixed | 5 | | | |
| write | 6 | | | |
| no-tool | 4 | | | |

---

## 3. RAG 开/关对比

| 指标 | RAG 关 | RAG 开 | 变化 |
|---|---:|---:|---:|
| 规则问答通过率 | | | |
| RAG 来源命中率 | | | |
| 答案关键词通过率 | | | |
| 首 token 平均 | | | |

结论（只写数据支持的）：

- RAG 将规则问答通过率从 `__%` 提升到 `__%`；
- 代价是首 token 平均增加 `__ms` / 每轮多 `__ token`；
- 未覆盖问题中，模型是否承认“知识库没有说明”：`__/__`。

---

## 4. 工具性能（项目一 `/tool/**`）

```text
python eval/run_tool_eval.py --endpoint list --requests 30 --concurrency 3
```

| 指标 | 结果 |
|---|---:|
| 成功数 / 总数 | / |
| 成功率 | % |
| 响应形状正确率 | % |
| P50 | ms |
| P95 | ms |
| P99 | ms |
| 吞吐 | req/s |

说明：这是项目一自身 HTTP 路径，不含 LLM。

---

## 5. Agent 端到端延迟

| 指标 | 结果 |
|---|---:|
| 首 token 平均 | ms |
| 首 token P95 | ms |
| 总耗时平均 | ms |
| 总耗时 P95 | ms |
| 工具调用 P95（审计） | ms |

并发：1 / 3 / 5（分别记录，不要只放最好看的一组）。

---

## 6. 韧性与安全（手工/脚本补充）

| 场景 | 结果 |
|---|---|
| 停项目一：熔断打开前失败次数 | |
| 熔断后单次失败耗时 | ms |
| 恢复项目一后恢复时间 | s |
| 停 LLM：SSE error 延迟 | ms |
| 停 Redis：对话是否可用 | |
| 停 Redis：写预览是否 503 且不写 | |
| 写操作：预览不改库 | 通过 / 失败 |
| 写操作：确认只改一次 | 通过 / 失败 |
| 写操作：重放 409 | 通过 / 失败 |
| 写操作：状态漂移 409 | 通过 / 失败 |
| 审计覆盖率 | % |

---

## 7. 结论

只写能被数据支持的结论，例如：

- 权限过滤使用户可见工具从 10 降到 3；
- 熔断把下游不可达时的单次等待从 10s 降到 <100ms；
- RAG 将规则问答通过率从 A 提升到 B（12 条样本）；
- 写操作在预览/重放/漂移场景下 0 次误写。

## 8. 局限

- 单机、本地容器、样本量小，不代表生产；
- RAG 答案为人工关键词/人工评分，存在主观性；
- LLM 有随机性，只跑了 N 次；
- 未做长时间稳定性与多实例压测。

## 9. 原始结果

- Agent JSON：`eval/results/agent-...json`
- Agent CSV：`eval/results/agent-...csv`
- Tool JSON：`eval/results/tool-...json`
- 对比报告：`eval/results/compare-....md`
