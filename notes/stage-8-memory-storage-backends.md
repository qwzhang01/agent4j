# Agent 记忆存储方案详解：介质、形态与检索能力

> 对应阶段：Stage 8 - Memory、Context 与共享记忆治理
> 定位：存储方案选型笔记 -- 把"存什么/放哪/怎么查"两个维度拆清楚，附五种方案对比和扩展路径
> 配套：实现见 `agent-memory` 模块的 `MemoryStore` 接口，架构设计见 [architecture-stage-8.md](architecture-stage-8.md)，概念详解见 [stage-8-memory-explained.md](stage-8-memory-explained.md)，9 层教程见 [stage-8-memory-tutorial.md](stage-8-memory-tutorial.md)

---

## 0. 一个常见混淆

新手容易把这几句话当成并列的：

```text
"记忆可以存内容、存硬盘、存关系型数据库、存内存数据库、也可以存向量数据库、还可以做知识图谱"
```

这其实混了两个维度。拆开就清晰：

```text
维度 1：存什么形态（数据结构）
  - 原始消息（ChatMessage，日志形态）
  - 结构化条目（MemoryEntry，有 subject / type / provenance / status）
  - 向量嵌入（embedding，一串数字）
  - 图谱节点边（entity + relation，有向图）

维度 2：放哪里（存储介质）
  - 内存（Map，进程内）
  - 文件（JSONL / SQLite，本地持久）
  - 关系型数据库（MySQL / PostgreSQL）
  - 内存型数据库（Redis，快但易失）
  - 向量数据库（Milvus / pgvector / FAISS / Pinecone）
  - 图数据库（Neo4j / Graphiti）
```

**两个维度是正交的**：同一份记忆，介质可以换，形态也可以混。比如结构化条目可以存内存、可以存 MySQL；图谱节点边可以存 Neo4j、也可以用 JSONL 手搓。

---

## 1. 五种存储方案对比

按"存什么 + 放哪 + 怎么查"组织：

| 方案 | 存什么 | 放哪 | 怎么查 | 代表实现 | 适合场景 |
|------|--------|------|--------|---------|---------|
| **内存 Map** | 结构化条目 | 进程内 ConcurrentHashMap | keyword 包含匹配 | 我们 v1 `InMemoryMemoryStore` | 教学型/单机 Demo |
| **文件 JSONL** | 结构化条目 + 段落原文 | 本地文件 | 按行扫描 / 加载到内存 | LangChain 早期 / dsh 的 session log | 单机持久、易读、易调试 |
| **关系型 DB** | 结构化条目（一行一条） | MySQL / PostgreSQL | SQL where + like | 企业 Agent / ChatGPT memory 落地 | 多租户、事务、可审计 |
| **向量 DB** | 结构化条目 + 向量字段 | Milvus / pgvector / Pinecone | 向量相似度检索 | mem0 / RAG 系统 | 语义模糊匹配（"过敏" 匹配 "禁忌"） |
| **图 DB** | 实体节点 + 关系边 | Neo4j / Graphiti | 图遍历 / 路径推理 | Zep / Graphiti | 时序推理、关系推理 |

### 1.1 内存 Map（我们 v1）

```text
存什么：MemoryEntry record（subject/type/provenance/status/importance/expireAt）
放哪：ConcurrentHashMap<id, MemoryEntry>
怎么查：
  .filter(e -> query.scopes().contains(e.scope()))   scope 隔离
  .filter(e -> e.status() == ACTIVE)                 只回活跃
  .filter(e -> !e.isExpired(now))                    TTL 惰性
  .filter(e -> e.content().contains(keyword))        keyword 包含
```

优点：零依赖、deterministic、好测
缺点：进程崩了就没了；容量受 JVM 堆限制
代码位置：`InMemoryMemoryStore.java`

### 1.2 文件 JSONL

```text
存什么：每行一个 JSON 序列化的 MemoryEntry
放哪：local file（memory.jsonl）
怎么查：启动时加载到内存 Map，之后同 v1；或按行扫描
```

优点：进程崩了数据还在；人可读、可 diff、可手动编辑
缺点：全量加载到内存（或按行扫描），不适合大数据量；并发写要加锁
代表：dsh 的 session log、LangChain ConversationBufferFileMemory
扩展：我们 `JsonlMemoryStore` 的预留实现路径（接口不动，换底层）

### 1.3 关系型数据库（MySQL / PostgreSQL）

```text
存什么：一张 memory_entry 表
  id          VARCHAR PK
  scope       VARCHAR INDEX
  type        VARCHAR
  subject     VARCHAR INDEX
  content     TEXT
  importance  FLOAT
  provenance  JSON
  status      VARCHAR INDEX
  created_at  TIMESTAMP
  expire_at   TIMESTAMP NULL

怎么查：
  SELECT * FROM memory_entry
  WHERE scope IN (?, ?)               -- scope 隔离
    AND status = 'ACTIVE'             -- 只回活跃
    AND (expire_at IS NULL OR expire_at > NOW())  -- TTL
    AND content LIKE CONCAT('%', ?, '%')          -- keyword
  ORDER BY created_at DESC
  LIMIT ?
```

优点：事务、并发、多租户、索引、审计全齐
缺点：依赖重；需要 schema 迁移；keyword 用 LIKE 走全表扫
代表：企业 Agent 生产环境、ChatGPT memory 落地
扩展：我们 `JdbcMemoryStore` 的预留实现路径

### 1.4 向量数据库（Milvus / pgvector / Pinecone）

```text
存什么：MemoryEntry + vector 字段
  （原有字段不变）
  + embedding   VECTOR(384)  -- 新增向量字段

怎么查：
  1. 把查询"用户问过敏"过 embedding 模型 -> 查询向量 [0.12, -0.34, ...]
  2. 在向量库里找余弦相似度最高的 K 条：
     SELECT *, embedding <=> $query_vec AS distance
     FROM memory_entry
     WHERE scope IN (?, ?) AND status = 'ACTIVE'
     ORDER BY distance ASC
     LIMIT 10
```

优点：语义模糊匹配，"过敏"能匹配"禁忌""不耐受"
缺点：需要 embedding 模型（API 或本地）；向量索引占空间；每次写入都要算 embedding
代表：mem0（OpenAI embedding + Qdrant）、RAG 系统
扩展：我们 `VectorMemoryStore` 的预留实现路径（Stage 15 RAG）

### 1.5 图数据库（Neo4j / Graphiti）

```text
存什么：节点 + 边
  (userA:User)-[:SAID {at: T1, runId: r1}]->(fact:Fact {content: "对花生过敏"})
  (fact)-[:SUPERSEDED_BY {at: T3, by: admin}]->(corrected:Fact {content: "不过敏"})
  (userA)-[:RELATIVE_OF]->(userB:User)
  (userB)-[:SAID {at: T2}]->(fact2:Fact {content: "家族坚果过敏史"})

怎么查（图遍历 / 路径推理）：
  问"用户亲属的过敏史"
  -> MATCH (u:User {id: 'u1'})-[:RELATIVE_OF]->(rel:User)-[:SAID]->(f:Fact)
     WHERE f.content CONTAINS '过敏'
     RETURN f
  -> 顺着"用户-亲属-事实"边推理出来
```

优点：关系推理能力强；双时间轴（事实发生时间 vs 系统录入时间）
缺点：实现最复杂；schema 设计难；查询语法（Cypher）学习曲线陡
代表：Zep / Graphiti
扩展：我们 v2 候选，不在当前规划范围内

---

## 2. 检索能力的三档进化

存储方案决定了能查多"准"：

```text
第 1 档：关键词匹配（字面包含）
  "过敏" 只匹配含"过敏"两字的条目
  "禁忌" 查不到 -- 字面不一样就不命中
  -> 适合：内存 Map、关系型 DB 的 LIKE

第 2 档：向量相似（语义接近）
  "过敏" 能匹配到"禁忌""花生不耐受"
  -> 需要：embedding 模型 + 向量索引
  -> 适合：向量 DB / pgvector

第 3 档：图谱推理（关系推理）
  问"用户亲属的过敏史" 能顺着"用户-亲属-病史"边推理出来
  "这个 PR 沉寂 3 天了" 能关联 PR->CI->失败->用户
  -> 需要：图结构 + 路径查询
  -> 适合：图 DB / Zep
```

**检索能力越强，存储结构越复杂、运维越重。** 教学型用第 1 档够，工业级主用第 2 档，复杂场景才上第 3 档。

---

## 3. 我们 v1 的选择 + 扩展路径

### 3.1 v1（已完成）

```text
存什么：结构化条目（MemoryEntry，不存原始消息）
放哪：内存 Map（InMemoryMemoryStore）
怎么查：keyword 包含匹配
↑ 就是第 1 档最简版
```

### 3.2 v2 扩展路径（接口已留好，不动调用方）

```text
┌─────────────────────────────────────────────┐
│ 路径 A：换存储介质（同一形态，换实现）         │
│   InMemory -> JsonlMemoryStore（文件持久）     │
│           -> JdbcMemoryStore（MySQL/PG）      │
│   检索方式不变，还是 keyword                  │
└─────────────────────────────────────────────┘
┌─────────────────────────────────────────────┐
│ 路径 B：换检索能力（数据加字段，查法升级）      │
│   MemoryEntry 加 vector 字段                 │
│   MemoryStore.query 换向量相似              │
│   -> VectorMemoryStore（Milvus/pgvector）    │
│   调用方（retriever/contextBuilder）零改动    │
└─────────────────────────────────────────────┘
```

### 3.3 关键设计：接口抽象藏住"怎么查"

`MemoryStore` 接口按 `query(MemoryQuery)` 抽象，把"怎么查"藏在实现里：

```java
// MemoryStore.java
public interface MemoryStore {
    MemoryEntry write(MemoryEntry entry);
    List<MemoryEntry> query(MemoryQuery query);       // ← 查法藏在实现里
    Optional<MemoryEntry> findActiveBySubject(String scope, String subject);
    MemoryEntry update(MemoryEntry entry);
    Optional<MemoryEntry> findById(String id);
    boolean delete(String id);
    List<MemoryEntry> listByScope(String scope);
}
```

接口注释明确写了扩展意图（第 12-13 行）：

```text
12:13:projects/java-agent-framework/agent-memory/src/main/java/io/github/qwzhang01/agent/memory/MemoryStore.java
 * v1 implementation: {@link InMemoryMemoryStore}. The interface is designed so
 * a persistent backend (JSONL / DB / Redis) can be added later without changing
 * callers.
```

**v1 用 keyword、v2 换向量、v3 换图谱，retriever 和 contextBuilder 都不用改一行**。这是接口抽象的价值：把"存储怎么实现"和"记忆怎么用"解耦。

---

## 4. 主流方案选型参考

### 4.1 按规模选

```text
单机教学 / Demo：
  -> 内存 Map（我们 v1）
  -> 文件 JSONL（要持久）

单机生产 / 小规模：
  -> SQLite / JSONL + 内存缓存
  -> 检索：keyword 够用，或轻量 embedding

多租户企业 Agent：
  -> 关系型 DB（MySQL/PG）
  -> 检索：LIKE 或全文索引

语义检索 / RAG 场景：
  -> 向量 DB（pgvector 起步，Milvus 上规模）
  -> 检索：向量相似

时序推理 / 复杂关联：
  -> 图 DB（Neo4j / Graphiti）
  -> 检索：图遍历 + 路径查询
```

### 4.2 按能力选

| 检索能力 | 介质 | 形态 | 复杂度 | 何时上 |
|---------|------|------|--------|--------|
| keyword 包含 | 内存 / 文件 / 关系 DB | 结构化条目 | 低 | v1 已做 |
| 全文检索 | 关系 DB（全文索引） | 结构化条目 | 中 | 企业级 |
| 向量相似 | 向量 DB / pgvector | 条目 + 向量字段 | 中高 | Stage 15 RAG |
| 图谱推理 | 图 DB | 节点 + 边 | 高 | v2 候选 |

### 4.3 混合存储（生产级常见）

```text
热数据（当前会话活跃）  -> Redis（快）
温数据（近期记忆）      -> 关系 DB（可查可审计）
冷数据（历史归档）      -> 对象存储 / JSONL 文件
语义检索索引            -> 向量 DB（旁路）
关系推理索引            -> 图 DB（旁路，可选）
```

主存 + 索引旁路，是大规模 Agent 系统的工业做法。

---

## 5. 选型决策树

```text
你的 Agent 要记忆吗？
  └── 是 -> 规模多大？
        ├── 教学 / Demo / 单进程
        │     -> 内存 Map（v1 已做）
        │     -> 要持久？加 JSONL 文件
        ├── 单租户 / 单机生产
        │     -> SQLite / JSONL + 内存缓存
        │     -> 检索要语义？加 pgvector
        ├── 多租户企业 Agent
        │     -> MySQL / PostgreSQL
        │     -> 检索要语义？pgvector 扩展
        │     -> 审计严格？加审计日志表
        ├── RAG / 语义检索
        │     -> 向量 DB（Milvus / Pinecone）
        │     -> 主存还是关系 DB，向量旁路
        └── 复杂关联推理
              -> 图 DB（Neo4j / Graphiti）
              -> 通常作为旁路，主存还是别的
```

---

## 6. 与学习规划的对照

18 周规划中，记忆存储的演进路径：

| 阶段 | 存储方案 | 状态 |
|------|---------|------|
| Stage 8 | 内存 Map（InMemoryMemoryStore）+ 接口抽象 | ✅ 已做 |
| Stage 15 | Enterprise Profile -> 可能加关系 DB + pgvector | ⬜ |
| Stage 18 | 可观测性 -> 审计存储可能落关系 DB | ⬜ |

规划 §九 范围控制明确写了"暂不深入"：
- 一开始实现复杂分布式调度
- 一开始覆盖所有协议和生态

对应到记忆层：
- v1 不上向量检索（接口留好，Stage 15 RAG 再加）
- v1 不上图数据库（v2 候选）
- v1 不上跨进程 Store（生产化时再加）

---

## 7. 面试速答

**Q：Agent 记忆系统应该用什么存储？**
看规模和检索要求。教学型用内存 Map 够；单机生产用 JSONL 或 SQLite；多租户企业用关系型 DB；要语义检索加向量 DB；要关系推理才上图 DB。关键不是选哪个，是接口按 `query(MemoryQuery)` 抽象，让存储介质可换、检索方式可升级，调用方不动。

**Q：向量检索和关键词检索有什么区别？**
关键词是字面包含，"过敏"只匹配"过敏"，查不到"禁忌"；向量是语义相似，"过敏"能匹配"禁忌""不耐受"——因为它们在向量空间里距离近。向量检索需要 embedding 模型把文字变成数字，然后算余弦相似度。代价是每次写入都要算 embedding、占空间，但匹配质量高很多。RAG 系统和 mem0 都用这个。

**Q：为什么不直接上向量数据库？**
教学型目标是理解机制，向量引入 embedding 模型依赖、每次结果可能微抖动（不好测）。v1 用 keyword 保证 deterministic，先把"存-取-忘-治"四件套跑通。接口按 `query(MemoryQuery)` 抽象，v2 换向量实现，retriever 和 contextBuilder 都不用改。

**Q：知识图谱和向量数据库有什么区别？**
向量是"语义相似度"，算的是两段文字在向量空间的距离；图谱是"关系推理"，顺着节点和边走，能回答"用户亲属的过敏史"这种需要跨实体推理的问题。向量适合"找相似"，图谱适合"找关联"。Zep/Graphiti 用图谱是因为它要支持双时间轴（事实发生时间 vs 系统录入时间）和复杂时序推理。

---

## 8. 总结对照表

| 你说的 | 精确化 | 我们 v1 选了没 | 何时上 |
|--------|--------|---------------|--------|
| 存内容 | 原始消息 vs 提炼条目 | 选提炼条目（MemoryEntry） | 已做 |
| 存硬盘 | JSONL 文件 | 没做，接口留好 | 单机持久时 |
| 关系型数据库 | MySQL / PostgreSQL | 没做，接口留好 | 多租户生产 |
| 内存数据库 | Redis | 没做，接口留好 | 高速缓存层 |
| 向量数据库 | Milvus / pgvector | 没做，接口留好 | Stage 15 RAG |
| 知识图谱 | Neo4j / Graphiti | 没做 | v2 候选 |

**"存内容"要再细分**：存的是**原始消息**（ChatMessage）还是**提炼条目**（MemoryEntry），这是 Stage 8 设计决策 D2（存录像还是存要点）。v1 选提炼条目——不存原始消息，因为原始消息臃肿、难检索、难治理。
