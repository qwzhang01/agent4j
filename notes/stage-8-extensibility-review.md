# Stage 8 架构可扩展性评估：向量与图谱扩展的难度分析

> 对应阶段：Stage 8 - Memory、Context 与共享记忆治理
> 定位：架构评审笔记 -- 回答"现有 agent-memory 架构扩展向量检索和知识图谱容易吗？设计合理吗？"
> 结论先行：**向量扩展容易（接口完全兼容，换实现不动调用方）；图谱扩展不容易（不是换实现，是加一套并行系统）；设计对 v1/v2 目标合理，图谱不在承诺范围内。**
> 配套：源码导读见 [stage-8-source-code-guide.md](stage-8-source-code-guide.md)，存储方案见 [stage-8-memory-storage-backends.md](stage-8-memory-storage-backends.md)，架构设计见 [architecture-stage-8.md](architecture-stage-8.md)

---

## 1. 总结论

```text
向量扩展（v2）：容易。现有接口设计就是为此留的（D7），换实现不动调用方。
图谱扩展（v3）：不容易。不是"换实现"，是"加一套并行系统"。
设计合理性：对 v1/v2 目标来说是合理的，图谱不在它的承诺范围内。
```

---

## 2. 向量扩展：架构完全撑得住

### 2.1 接口逐条检查

把 MemoryStore 的 7 个方法对着向量场景过一遍：

| 方法 | 向量场景下 | 结论 |
|------|-----------|------|
| `write(entry)` | 写入前算 embedding（store 内部做） | 兼容，签名不变 |
| `query(MemoryQuery)` | keyword 文本 -> embedding -> 相似度排序 | 兼容，签名不变，实现换 |
| `findActiveBySubject` | pgvector/Milvus 都支持 metadata 过滤 | 兼容，直接映射 |
| `update / findById / delete / listByScope` | 按 id/scope 的元数据操作 | 兼容，向量库都支持 |

**关键点**：`query(MemoryQuery) -> List<MemoryEntry>` 这个签名对向量检索天然兼容。keyword 字段被实现层"重载"为查询文本--调用方传的还是字符串，store 内部把它 embed 了做相似度。

### 2.2 具体改动清单（预估 1-2 天）

```text
1. MemoryEntry 加一个字段：
   embedding  float[]   // nullable，v1 不填

2. 新增 VectorMemoryStore implements MemoryStore：
   - write()：调 EmbeddingClient 算向量存入 pgvector/Milvus
   - query()：keyword -> embed -> ORDER BY embedding <=> query_vec
   - 其余方法直接映射到 SQL/Milvus SDK

3. 新增 EmbeddingClient 接口 + 一个实现（OpenAI/本地模型）

不改的（零改动）：
   MemoryRetriever / MemoryContextBuilder / MemoryPolicy /
   MemoryExtractor / MemoryAdmin / ChatSession / MemoryTools /
   所有测试的调用方代码
```

### 2.3 一处小瑕疵

`MemoryQuery.keyword` 这个字段名在 v2 语境下语义变了--它不再是"关键词包含匹配"而是"查询文本"。功能没问题，但名字有点误导。处理选项：

- 要么改名 `queryText`（动一次调用方）
- 要么 Javadoc 注明"v2 语义：待 embed 的查询文本"（零成本）

**结论：向量扩展是当初 D7 设计决策的直接兑现，架构完全合理。**

---

## 3. 图谱扩展：现有架构撑不住，但有合理的加法路径

### 3.1 四个不匹配（为什么不能"换实现"）

**不匹配 1：数据模型**

```text
现有：MemoryEntry 是扁平条目
     "小王是订单服务的 owner"  ->  一条 content 字符串

图谱需要：
     (订单服务)-[:OWNER]->(小王)  ->  节点 + 类型化边 + 边属性
```

MemoryEntry 的 10 个字段里没有任何东西能表达"实体"和"关系"。硬把边塞进 content 字符串，图谱查询能力就没了。

**不匹配 2：提取端**

```text
现有：MemoryExtractor 用关键词规则抽"偏好语句"
     输入"小王是订单服务的 owner" -> 命中"我是"？不命中 -> 不提取！

图谱需要：实体识别 + 关系抽取
     输入"小王是订单服务的 owner"
     -> 实体[小王][订单服务] + 关系(订单服务)-[OWNER]->(小王)
```

这不是换个 Store 就能解决的--**写入流水线的 Extractor 本身要换**（规则提取 -> LLM 关系抽取）。

**不匹配 3：查询模型**

```text
现有：MemoryQuery = scopes + type + subject + keyword + limit
     本质是"字段过滤"，表达能力上限是 WHERE 子句

图谱需要：
     MATCH (issue)-[:BELONGS_TO]->(svc)-[:OWNER]->(p)-[:ON_LEAVE]->()
     本质是"图遍历模式"，MemoryQuery 根本表达不了多跳
```

**不匹配 4：返回类型**

```text
现有：query() 返回 List<MemoryEntry>（扁平列表）
图谱：遍历返回的是路径/子图，不是条目列表
```

四个不匹配叠加，结论很清晰：**图谱不是 MemoryStore 的另一个实现，是另一套数据模型 + 另一套查询语言 + 另一套提取器。**

### 3.2 正确的图谱路径：并行加法，不是修改

回看存储方案笔记里的"混合存储：旁路索引"模式：

```text
                          ┌──────────────────────┐
                          │  MemoryStore（现有）   │
  对话 -> Extractor ──────>│  扁平条目 + 元数据     │──> keyword/vector 检索
      │                   └──────────────────────┘
      │                   ┌──────────────────────┐
      └─> GraphExtractor ─>│  GraphStore（新增）   │
          （LLM 关系抽取）  │  节点 + 边            │──> 图遍历推理
                          └──────────────────────┘
```

新增的是**并行组件**，不是修改现有组件：

```text
新增（v3 时）：
   GraphNode / GraphEdge          数据模型
   GraphQuery                     遍历查询 DSL
   GraphStore 接口 + Neo4j 实现    存储
   GraphExtractor                 LLM 实体关系抽取

复用（零改动）：
   MemoryScope        隔离共享机制直接沿用（图也有 scope）
   MemoryProvenance   溯源四元组直接沿用（边也要溯源）
   MemoryStatus       生命周期直接沿用（边也有待审/取代）
   治理三件套思想      supersede 在图里就是 SUPERSEDED_BY 边
```

**治理层是完全跨存储通用的**--这是当初把 provenance/status/scope 做成 MemoryEntry 元数据（而不是存储层特性）的回报。

---

## 4. 设计合理性总评

### 4.1 合理的部分（经得起向量检验）

| 决策 | 为什么对 |
|------|---------|
| `query(MemoryQuery)` 抽象藏查法 | v1 keyword -> v2 向量，调用方零改动，兑现了 |
| MemoryEntry 结构化条目带元数据 | subject/type/status/provenance 在向量库照样用（metadata 过滤） |
| 治理与存储分离（Policy/Admin 在 store 之上） | 换存储不影响治理逻辑 |
| 检索器 Retriever 薄封装 | store 换了它不用动 |
| v1 不引 embedding 依赖（D7） | 教学型可测优先，范围控制明确 |

### 4.2 当初"没做"但回头看值得肯定的部分

如果当初为了"图谱友好"设计了 GraphQuery 抽象、边表结构--那就是为 v3 过度设计（YAGNI），v1/v2 的所有代码都要为它付复杂度税。**当前设计在"够用"和"可扩展"之间切的位置是对的。**

### 4.3 两个诚实的批评（如果重来会微调）

**批评 1：早期笔记"接口抽象藏住怎么查"的说法，只对 v1->v2 成立。**

早期笔记有一句"v1 用 keyword、v2 换向量、v3 换图谱，调用方都不用改"--后半句是错的。v3 图谱换不动调用方（MemoryQuery 表达不了遍历）。源码导读笔记的最后一个附录已修正（"图谱升级两步走"），但 architecture-stage-8.md §3.2 的表述也应补一句边界说明：**接口抽象的承诺范围是 v1->v2，不含 v3。**

**批评 2：keyword 字段语义重载没有预留。**

v2 时 keyword 会变成"查询文本"，字段名误导。要么现在改名，要么 Javadoc 注明。5 分钟的事。

---

## 5. 最终结论表

| 维度 | 向量（v2） | 图谱（v3） |
|------|-----------|-----------|
| 接口兼容性 | 完全兼容 | 四处不匹配 |
| 改动方式 | 换实现（1 个新类 + 1 个字段） | 加并行系统（4 个新类族） |
| 调用方改动 | 零 | 零（旧路径不动），但新查询走新接口 |
| 治理层复用 | 全部 | 全部（scope/provenance/status 思想） |
| 工作量 | 1-2 天 | 1-2 周（含 LLM 关系抽取调优） |
| 现架构合理吗 | 合理，正是为此设计的 | 合理--它从没承诺过图谱，且没为图谱付过度设计税 |

---

## 6. 一句话总结

> **这套架构是一把为 v1/v2 打的钥匙：向量来了一把新锁，钥匙转个角度（换实现）就能开；图谱来的是另一扇门，得另配一把钥匙（并行系统）--但门框（治理、scope、溯源）是通用的。为 v1/v2 的目标，这个切分是准确的；图谱的复杂度被正确地推迟到了真正需要它的那天。**

---

## 附：v1/v2/v3 演进全景（三个扩展性事实）

```text
检索能力演进（检索单位不变：单条记忆）：
  v1 keyword 字面包含   -- "过敏"匹配"过敏"
  v2 向量语义相似       -- "过敏"匹配"禁忌"
  扩展方式：换 Store 实现，接口不变，调用方零改动  ← 架构承诺范围内

推理能力演进（检索单位升级：多条记忆连接）：
  v3 图谱关系推理       -- 多条记忆拼成推理链，跨实体跨时间
  扩展方式：并行加 GraphStore/GraphQuery/GraphExtractor  ← 架构承诺范围外，但治理层可复用

不变的（跨 v1/v2/v3）：
  scope 隔离共享 / provenance 溯源 / status 生命周期 / 三道闸治理思想
  -- 因为它们做成了元数据（数据层的语言），不是存储层的特性
```
