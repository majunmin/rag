# Rerank 策略调研

**Date:** 2026-05-11
**Status:** 调研归档，对应当前项目状态：已实现"Cross-encoder API rerank"（commit `4ba9e71`），其他策略未实施。
**Project:** rag0429 RAG 后端

---

## 0. 为什么需要 rerank

向量召回（cosine similarity）只反映 **embedding 空间的几何距离**，不直接对应"这段 chunk 能否回答这个问题"。

常见失败模式：
1. query "如何配置 SSL" 召回到大量"配置"相关 chunk，但实际能回答的那条排在第 8 位
2. 同义词 / 近义关系召回 OK，但语义反向（"如何启用" vs "如何禁用"）也很相似
3. chunk 长短分布不均时，长 chunk 因为信息量大被偏向召回
4. 同一段话被切到两个相邻 chunk（带 overlap），它们会同时进入 top-K，挤占其他相关 chunk 的位置

**Rerank 的工作模式**：先 vector recall 拿 `topK_recall` 个候选（比如 20-50），再用更精准但更慢的模型对 (query, chunk) 配对打分，重排后取 `topK_final`（比如 5）。

> **召回（recall stage）保 recall，rerank（rerank stage）保 precision。**

---

## 1. 六大策略概览

| 策略 | 实现成本 | 延迟（典型） | 召回质量提升 | 依赖 |
|---|---|---|---|---|
| **A. 启发式 / 规则** | 极低 | +5-20ms | 中 | 无 |
| **B. Cross-encoder（API）** | 低 | +200-500ms | 高 | 第三方 API |
| **C. Cross-encoder（自部署）** | 中 | +50-200ms (GPU) / 1-3s (CPU) | 高 | GPU / 模型服务 |
| **D. LLM-as-reranker** | 低 | +1-3s | 极高 | 已有 LLM，贵 |
| **E. ColBERT / Late-interaction** | 高 | +30-100ms | 高 | 单独的向量服务 |
| **F. RRF 多路融合** | 中 | +存储 / 召回开销 | 中-高（语义+精确双覆盖） | 多种召回源 |

---

## 2. 策略详解

### 2.1 启发式 / 规则（Heuristic）

**零模型成本，可与其他策略叠加**。适合作为兜底/补丁。

#### 2.1.1 关键词加权（BM25 boost）

把 BM25 / TF-IDF 词频得分与向量召回得分按权重融合：

```
final_score = α * vector_sim + (1-α) * bm25_score
```

适合**专有名词、产品代号、API 名**这类 embedding 不敏感的词。Elasticsearch / OpenSearch 用得多。

#### 2.1.2 元数据 boost

```
final_score = vector_sim * field_weight(chunk.metadata)
```

例：
- 文档新鲜度（`created_at` 越近权重越高）
- 来源可信度（"官方文档" 比 "FAQ 帖子" 权重高）
- 章节类型（"代码示例" 在 how-to 查询里 boost）

#### 2.1.3 长度归一化

向量召回偏向长 chunk（信息量大���，但长 chunk 不一定回答得准。除以 `sqrt(token_count)` 抑制偏倚。

#### 2.1.4 MMR (Maximal Marginal Relevance) — 去冗余

```
score(c) = λ * sim(c, query) - (1-λ) * max(sim(c, c') for c' in selected)
```

选 chunk 时同时考虑"与 query 相关"和"与已选 chunk 不重复"。**RAG 必备**，因为向量召回经常拿到几个高度重复的 chunk（同段话被切两半 + overlap）。

参数 `λ` 一般取 0.5-0.7：
- λ 偏向 1 → 越像传统排序，重复度高
- λ 偏向 0 → 越分散，可能丢相关性

#### 2.1.5 分数阈值过滤

`final_score < threshold` 直接 drop。最高分都低于阈值时返回空，让 LLM 走"我无法回答"兜底，避免胡编。

**适用判断**：单独不够撑 RAG 质量；最好作为 cross-encoder rerank 之后的 post-processing。

---

### 2.2 Cross-encoder Rerank（项目当前选择）

把 (query, chunk) 对一起送入 Transformer，输出一个 0-1 的相关性分数。

| 类型 | 编码方式 | 速度 | 精度 |
|---|---|---|---|
| **bi-encoder** | query 和 chunk 分别编码，再算余弦 | 快 | 糙 |
| **cross-encoder** | query 和 chunk 一起送入，token 间 cross-attention | 慢 | 准 |

向量召回用的是 bi-encoder（速度优先），rerank 用 cross-encoder（精度优先）。

#### 主流模型对比

| 模型 | 来源 | 多语言 | 部署方式 | 价格特点 |
|---|---|---|---|---|
| `gte-rerank-v2` | 阿里百炼 | ✓ | SaaS API | 国内合规、与 chat/embedding 同账号 |
| `bge-reranker-v2-m3` | BAAI | ✓ | 自部署（HF） | 开源、可微调 |
| `cohere-rerank-3.5` | Cohere | ✓ | SaaS API | 海外质量标杆 |
| `jina-reranker-v2-base-multilingual` | Jina | ✓ | SaaS + 自部署 | 性价比好 |
| `mxbai-rerank-large-v1` | mixedbread.ai | ✓ | 开源自部署 | 最新、社区评测高 |

延迟：API 200-500ms / 自部署 GPU 50-200ms / 自部署 CPU 1-3s

#### 当前项目落地（commit `4ba9e71`）

走的是百炼 `gte-rerank-v2`，因为已经在用百炼 chat/embedding，账号、计费、监控都复用。

```
vector_store.similaritySearch(topK = top-k-recall, 默认 20)
  ▼  20 个候选
RerankService.rerank(query, candidates, finalTopK = 5)
  └─ BailianRerankService.rerank
        POST /services/rerank/text-rerank/text-rerank
        → 收到 [(idx, score)] 排序
        按分数 desc 取前 5，metadata 注入 rerank_score
  ▼
5 个最终 chunks → LLM
```

关键设计：
- **fail-soft**：HTTP 5xx / 超时 / 异常 / 空响应 / 越界 idx 一律 log warn + 返回 recall 前 K 个，绝不抛异常
- **enable=false 切回 NoOp**（recall-only 模式）
- 重排后 chunk metadata 多一个 `rerank_score`，前端可展示

---

### 2.3 LLM-as-reranker

直接让 LLM 给每个 chunk 打分或排序。

#### 2.3.1 Pointwise — 逐 chunk 打分

```
prompt = "判断下面 chunk 对回答 query '...' 的相关性，输出 0-10 分"
对每个候选调一次 LLM，按分数排序
```

最贵（N 次 LLM 调用），但灵活——可以让 LLM 解释为什么打这个分。

#### 2.3.2 Pairwise — 两两比较

```
对每对 (chunk_a, chunk_b)：哪个更相关？
按胜场数（Bradley-Terry / Elo-like）排序
```

O(N²) 调用，仅适合 N≤10。质量上限最高，几乎不用，因为太贵。

#### 2.3.3 Listwise — 一次给出排名（RankGPT 算法）

```
prompt = """下面 10 个 chunk，对回答 query '...' 的重要性从高到低排序。
仅输出排好的 chunk 编号列表，如 [3, 7, 1, 5, ...]。
chunk 1: ...
chunk 2: ...
...
"""
LLM 一次返回排名
```

**最实用的 LLM rerank 方式**。一次 LLM 调用即可，候选数受 LLM 上下文窗口限制。

缺点：
- LLM 排序输出可能不稳定（同样 prompt 多次输出排名略有差异）
- 候选超过 ~20 个时 LLM 容易丢 chunk

**适用**：高价值场景（金融/医疗/法律 RAG），延迟可接受 1-3s 时。质量天花板可超过 cross-encoder。

---

### 2.4 ColBERT / Late-interaction

折中方案，介于 bi-encoder 和 cross-encoder 之间：

- **索引时**：每个 token 单独 embed（chunk 有 100 token 就存 100 个 vector）
- **查询时**：query 每个 token 与 chunk 每个 token 做 max-sim，求和：

```
score(q, d) = Σ_i max_j (q_i · d_j)
```

比 cross-encoder 快（不需要 token 级 attention 计算），比 bi-encoder 准。

**代价**：**存储成本上升 ~30 倍**（每 token 一个 vector vs. 整 chunk 一个）。

实现库：`RAGatouille`、`PyLate`、`ColBERTv2`。

**适用**：召回质量很关键、向量库存储不是瓶颈的场景。pgvector 不天然支持 token-level vector，需要单独的 ColBERT 服务。

---

### 2.5 Reciprocal Rank Fusion (RRF) — 多路融合

**不是替换 rerank，而是融合多个召回源**：

```
final_score(d) = Σ_{rankers} 1 / (k + rank_i(d))
```

`k=60` 是 RRF 原论文（Cormack et al. 2009）的经验值。

例如：
- 向量召回的 rank
- BM25 召回的 rank
- 元数据召回的 rank（按 doc 名称精确匹配）

不需要分数归一化、无超参（除了 k）、鲁棒性极好。**hybrid search** 的标配。

PostgreSQL 上 RRF 的天然搭配：
```sql
WITH vector_recall AS (
  SELECT id, ROW_NUMBER() OVER (ORDER BY embedding <=> $1) AS rk
  FROM vector_store WHERE metadata->>'knowledge_base_id' = $2
  LIMIT 20
),
bm25_recall AS (
  SELECT id, ROW_NUMBER() OVER (ORDER BY ts_rank(tsv, plainto_tsquery($3)) DESC) AS rk
  FROM vector_store WHERE tsv @@ plainto_tsquery($3)
                      AND metadata->>'knowledge_base_id' = $2
  LIMIT 20
)
SELECT id, SUM(1.0 / (60 + rk)) AS score
  FROM (SELECT * FROM vector_recall UNION ALL SELECT * FROM bm25_recall) u
 GROUP BY id ORDER BY score DESC LIMIT 5;
```

**适用**：手上有多种异构召回（密集 + 稀疏 + 关键词），又不想训练单一模型时。

---

### 2.6 微调 / Adaptive rerank

#### 2.6.1 自训练 cross-encoder

用业务数据（query + 正例 chunk + 负例 chunk）微调一个领域专用 reranker。
**Sentence Transformers** 库的 `CrossEncoder.fit()` 几十行代码就能跑。

数据来源：
- 人工标注（最贵但最准）
- LLM 合成（用强 LLM 给 (q, c) 打标签，半监督）
- 用户反馈（点击 / 踩赞 / dwell time）

#### 2.6.2 用户反馈在线学习

记录"用户点了哪个 chunk / 哪个回答被踩"，定期重训 reranker 或调权。需要长期数据沉淀。

#### 2.6.3 Query routing

不同类型 query 走不同 rerank 策略：

| Query 模式 | rerank 偏好 |
|---|---|
| "如何 / how to" | 偏好 step-by-step chunk，长度归一化 |
| "什么是 / what is" | 偏好定义性 chunk，章节类型 boost |
| 含代码片段 | 偏好代码块 chunk |
| 含日期 / 版本 | 偏好新鲜度高的 chunk |

简单分类用 keyword regex；复杂场景用一个小 LLM 做分类（成本可控）。

**适用**：项目稳定后、有用户行为数据、想精细化优化的阶段。

---

## 3. 当前项目状态

### 已实现

| 项 | 实现 | 文件 |
|---|---|---|
| Cross-encoder API rerank | 百炼 `gte-rerank-v2`（默认开启） | `retrieval/BailianRerankService.java` |
| Fail-soft 降级 | HTTP/超时/异常 → 返回 recall 前 K | 同上 |
| NoOp 降级模式 | `app.rerank.enabled=false` 切换 | `retrieval/NoOpRerankService.java` |
| 配置可调 | `top-k-recall` / `model` / `timeout-ms` | `application.yml` |

### 未实现，按优先级排序

| 优先级 | 项 | 估时 | 收益 |
|---|---|---|---|
| **高** | MMR 去冗余（在 rerank 之后） | 1 工时 | 中—消除重复 chunk |
| **高** | 阈值过滤 → "无法回答"兜底 | 1 工时 | 中—减少 LLM 胡编 |
| 中 | BM25 + RRF 融合（pgvector + `tsvector`） | 1-2 工日 | 中—专有名词召回更好 |
| 中 | actuator metrics（rerank 调用数 / 延迟 / 失败率） | 半天 | 运营可观测 |
| 低 | LLM listwise rerank（仅高价值 query） | 1 工日 | 高—但贵 |
| 低 | 自训练 cross-encoder（基于业务语料） | 1-2 工周 | 高—长期最优 |
| 低 | Query routing（先做 query 分类） | 1 工日 | 中 |

---

## 4. 实战栈推荐（按演进顺序）

| 阶段 | 增量策略 | 收益 | 代价 |
|---|---|---|---|
| MVP | 单纯向量召回 | — | — |
| 上线版 *(当前)* | + Cross-encoder API rerank | 大 | 低 |
| 第一次迭代 | + MMR 去冗余 + 阈值过滤 | 中 | 几乎零 |
| 数据多起来 | + BM25 hybrid + RRF | 中 | 加列 + SQL 改造 |
| 质量瓶颈 | + LLM-as-reranker（高价值 query） | 大 | 贵 |
| 规模化 | + 自训练 reranker + query routing | 大 | 工程量大 |

---

## 5. 决策参考

### 哪种方案做不做？

**应该做（高 ROI、低成本）**：
- MMR 去冗余
- 阈值过滤
- RRF 多路融合（当 BM25 路径接入后）
- 元数据 boost（新鲜度 / 来源）

**视场景做**：
- LLM-as-reranker（高价值 query 走专门通道）
- 自训练 reranker（语料积累足够后）

**通常不做**：
- ColBERT（存储代价 30x，pgvector 不直接支持）
- Pairwise LLM rerank（O(N²) 太贵）

### 评估指标

引入 rerank 后必须有评估手段，否则改了不知道是否好：
- **NDCG@K**：标注 query 的相关 chunk，看排名是否对
- **MRR**：最相关 chunk 的平均排名倒数
- **业务 metric**：用户点击率 / 回答采纳率 / 踩赞比

可以建一个固定的 evaluation set（10-50 个高频 query + 人工标注的 ground truth chunk），每次改 rerank 策略就跑一遍。

---

## 6. 参考资料

- **RRF 原论文**: Cormack, G. V., Clarke, C. L. A., & Buettcher, S. (2009). *Reciprocal rank fusion outperforms condorcet and individual rank learning methods.* SIGIR'09.
- **RankGPT (LLM listwise)**: Sun, W. et al. (2023). *Is ChatGPT Good at Search? Investigating Large Language Models as Re-Ranking Agent.* EMNLP'23.
- **ColBERT**: Khattab, O., & Zaharia, M. (2020). *ColBERT: Efficient and Effective Passage Search via Contextualized Late Interaction over BERT.* SIGIR'20.
- **Sentence Transformers 文档**: <https://www.sbert.net/examples/applications/cross-encoder/README.html>
- **百炼 rerank 文档**: <https://help.aliyun.com/zh/dashscope/developer-reference/general-text-sorting-quick-start>
- **MTEB Leaderboard（包含 reranker 评测）**: <https://huggingface.co/spaces/mteb/leaderboard>

---

## 7. 关联文档

- 当前项目架构：`docs/architecture/SYSTEM_ARCHITECTURE.md`
- 检索层技术设计：`docs/architecture/TECHNICAL_DESIGN.md` § 4
- 当前 rerank 实现 commit：`4ba9e71`（feat(retrieval): two-stage retrieval with cross-encoder rerank）
