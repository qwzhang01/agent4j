# Stage 8 记忆系统详解：主流方案、设计哲学与我们的实现

> 对应阶段：Stage 8 - Memory、Context 与共享记忆治理
> 定位：概念补课笔记 -- 主流方案谱系 -> 设计哲学 -> 我们的概念/代码/数据流
> 配套：架构设计见 [architecture-stage-8.md](architecture-stage-8.md)，实现见 `agent-memory` 模块

---

## 0. 一句话总纲

> **记忆系统的第一性原理：上下文是稀缺的，存储是廉价的，而错误的记忆比没有记忆更危险。**
>
> 所有主流设计都在回答同样三个问题：**存什么（写入）、怎么取（读取）、怎么忘（遗忘与治理）。**
> 评估任何一个记忆方案，先看它对这三个问题的回答。

---

## 第一部分：主流设计方案与设计哲学

### 1. 为什么 Agent 需要记忆系统：三个问题

以我们自己的框架为例（Stage 1-7 的隐藏假设：`AgentState.messages` 就是全部状态）：

```text
问题 1：记不住
  AgentState 生命周期 = 一次 run，run 结束即丢。
  用户上一轮说"我对花生过敏"，下一轮 Agent 不知道。

问题 2：装不下
  buildRequest 把全部消息塞回 prompt，无预算、无压缩。
  长会话 -> token 膨胀 -> 成本上升 + 超上下文窗口 + 指令遵循退化。

问题 3：不可治理
  一旦引入频道级共享（多人共享一个 Agent）：
  谁写的记忆？错了怎么办？错误结论被反复检索放大怎么办？
  原始 ChatMessage 没有元数据，无法回答。
```

三个问题分别对应三条设计主线：**持久化（记不住）、上下文管理（装不下）、治理（不可治理）**。

---

### 2. 认知科学参照系（这些概念的来源）

Agent 记忆的术语全部借自认知心理学，理解原型有助于理解设计。

#### 2.1 Atkinson-Shiffrin 记忆模型（1968）

```text
感觉记忆 -> 短期记忆（工作记忆） -> 长期记忆
                ↑                        |
                └────── 检索调回 ←────────┘
                        编码写入 ↓
```

- 工作记忆容量极小（Miller 定律：7±2 个组块）
- 长期记忆容量近乎无限
- **核心机制：容量不够时，靠"编码写入 + 检索调回"在两层之间搬运**

Agent 映射：

| 人脑 | Agent |
|------|-------|
| 工作记忆 | context window（上下文窗口） |
| 长期记忆 | 外部存储（MemoryStore / 向量库 / DB） |
| 编码写入 | extract + store（写入流水线） |
| 检索调回 | recall + inject（读取流水线） |

这就是 Agent 记忆系统的全部骨架：**两层 + 两个方向的搬运**。

#### 2.2 长期记忆的分类（Tulving）

| 类型 | 含义 | 例子 | Agent 对应 |
|------|------|------|-----------|
| 情景记忆 episodic | "发生过什么" | 上周二用户问过退款 | 对话历史 / `EPISODE` |
| 语义记忆 semantic | "事实是什么" | 用户对花生过敏 | `FACT` / `PREFERENCE` |
| 程序记忆 procedural | "怎么做" | 退款要先查订单 | prompt / skill（不属于记忆模块） |

我们的 `MemoryType` 五值（PREFERENCE / FACT / EPISODE / SUMMARY / EVENT）就是这个分类加上工程需要的扩展（SUMMARY 是压缩产物、EVENT 是外部事件）。

#### 2.3 关键洞察

> **人脑不存录像，存"压缩后的要点 + 检索线索"。**

回忆"上周的会议"时你不会逐帧回放，而是想起几个要点。Agent 同理：**存提炼后的 MemoryEntry，不存原始 ChatMessage**（这正是我们的设计决策 D2，也是 mem0 / ChatGPT memory 的共同选择）。

---

### 3. 主流方案四大派系

按"记忆是什么"的回答分派。派系不互斥，是侧重。

#### 派系一：上下文管理派 -- 记忆 = 消息序列

代表：LangChain 的 Memory 组件家族、pi 的 compaction。

```text
ConversationBufferMemory     全量消息进 prompt（最原始）
ConversationWindowMemory     只保留最近 K 轮（滑动窗口）
ConversationSummaryMemory    旧消息总结成摘要替换原文
pi 的 compaction             token 超阈值 -> 旧段总结 + 保留最近 K 条 + 原文归档
```

哲学：**记忆只活在一次会话内，核心矛盾是 token 预算。**

- 解决"装不下"，不解决"记不住"（跨会话）
- LangChain 的各种 Memory 本质是"塞进 prompt 前的消息变形器"
- pi 的 compaction 是其中最完善的：归档保证可回溯（我们 D4 直接借鉴）

#### 派系二：操作系统派 -- 记忆 = 分层存储 + 自编辑

代表：MemGPT（2023 论文，后更名 Letta）。

```text
main context（窗内）              external context（窗外）
├── system instructions          ├── recall storage（完整消息历史，向量检索）
├── working context（自编辑）     └── archival storage（长期知识，向量库）
└── FIFO queue（最近消息）
```

- 把 LLM 当 CPU、context 当 RAM、外部存储当磁盘
- **模型自己管理换页**：通过 `core_memory_append/replace`（改工作集）、`archival_memory_insert/search`（落盘/检索）等函数自编辑记忆
- 触发方式类似 OS 中断：上下文快满时系统提示模型整理

哲学：**把"程序员写死的上下文管理"升级为"模型自管理的虚拟内存"。**

这是 `MemoryTools`（save_memory / search_memory）的思想源头（我们 D8 的参照）。

#### 派系三：知识提取派 -- 记忆 = 提炼后的知识条目

代表：mem0、OpenAI ChatGPT memory、Claude memory tool、Zep。

**mem0**（最典型的提取-更新管线）：

```text
会话结束
  -> LLM 从对话中抽取事实候选
  -> 与库中已有记忆对比（检索相似条目）
  -> LLM 决策：ADD（新事实）/ UPDATE（修正）/ DELETE（矛盾）/ NOOP（重复）
```

与我们的 Policy 三道闸 + supersede **同构**，差别只在判定器：mem0 用 LLM 判，我们 v1 用规则判（教学型可测）。

**OpenAI ChatGPT memory**（双通道）：

```text
saved memories        显式事实条目（模型决定存），每轮注入
chat history reference 对历史会话做语义检索（隐性记忆）
```

**Claude memory tool**（文件式）：模型用 view / create / str_replace / delete 显式读写一个记忆目录。

**Zep / Graphiti**（时序知识图谱）：事实组织成图谱，双时间轴（事实发生时间 vs 系统录入时间），边带 valid_at / invalid_at -- 回答"什么时候知道、什么时候过期"。

哲学：**记忆不是日志，是知识。写入要经过"提炼 + 冲突消解"。**

#### 派系四：共享与治理派 -- 记忆 = 组织公共资产

代表：Claude Tag 式频道共享记忆（我们 v3 规划的参照）。

```text
频道/团队共享一份记忆，多人可读可补充
  -> 管理员可查看/修改/删除
  -> 每条记忆有来源
  -> 待审核机制
```

哲学：**单人记忆错了，大不了重说一遍；共享记忆错了，会被所有人反复引用、持续放大。共享记忆必须先治理、后生效。**

#### 派系对比表

| 派系 | 记忆是什么 | 写入 | 读取 | 遗忘 | 治理 |
|------|-----------|------|------|------|------|
| 上下文管理 | 消息序列 | 不写（就是消息本身） | 全量/窗口/摘要注入 | 窗口滑出/压缩 | 无 |
| 操作系统 | 分层存储 | 模型自编辑（工具调用） | 模型自检索（工具调用） | 模型自删除 | 弱 |
| 知识提取 | 结构化条目 | 提炼 + 冲突消解管线 | 检索注入 | TTL/DELETE 决策 | 溯源 |
| 共享治理 | 组织资产 | 闸门 + 待审 | scope 内检索 | 管理员/审计 | 强（核心） |

#### RAG 与 Memory 的边界

- RAG 检索的是**外部世界知识**（文档、知识库）
- Memory 检索的是**Agent/用户自己的过去**（偏好、事实、历史）
- 底层都收敛到"检索"这一层 -- 所以我们的接口按 `query(MemoryQuery)` 抽象，Stage 15 换向量实现不动调用方

---

### 4. 八条设计哲学（跨派系提炼）

```text
P1 上下文稀缺，存储廉价
   一切记忆设计的第一性原理。compaction 和 recall 的动机都源于此。

P2 写谨慎，读慷慨
   污染一条记忆，之后每一轮都在放大它。所以闸门全部设在写路径，
   读路径尽量少过滤（只要 scope 内、ACTIVE、未过期就给）。

P3 结构化 vs 原始日志，是治理与回放的取舍
   治理要元数据（status/provenance/subject）-> 结构化条目
   回放要完整事件流 -> 原始日志（对照 Stage 6 的 event sourcing vs snapshot）
   两者可以混合：我们用结构化条目 + SUMMARY 归档兜底回溯。

P4 遗忘是特性，不是缺陷
   TTL / supersede / 滑动窗口。永不遗忘的系统终将被过期事实淹没。

P5 隔离与共享是同一个 scope 机制的两面
   不是两套系统。user:u1 是隔离，channel:c1 是共享，实现都是 scope 字符串。

P6 记忆必须可审计
   provenance 四元组：谁说的（actor）/ 怎么来的（sourceType）/ 哪次 run 提取的（runId）/ 何时（at）。
   没有溯源的记忆，治理无从下手。

P7 读取要双通道
   被动注入（recall 进 context，模型无感知）+ 主动检索（search_memory 工具，模型按需查）。
   只有被动通道，模型记不住没被注入的；只有主动通道，每轮都要模型多决策一次。

P8 记忆分层 = 生命周期不同，不是数据结构不同
   Working（run 内）/ Session（会话内）/ Long-term（跨会话）三层的差别是
   "活多久、谁持久化"，不是"长什么样"。
```

---

### 5. 我们的设计在谱系中的位置

```text
我们 = 派系一（compaction）
     + 派系三（结构化条目 + 写入闸门 + supersede）
     + 派系四（channel 待审 + MemoryAdmin + provenance）
     + 派系二的思想（MemoryTools 模型自决存取）

有意不做（范围控制）：
  - 向量检索 / embedding     -> Stage 15（Enterprise RAG），接口已留好
  - MemGPT 式 OS 分页        -> self-editing 只取了 tools 这一刀
  - Zep 式知识图谱            -> v1 只有 subject 键级冲突检测
```

---

## 第二部分：我们的概念、代码与数据流

### 6. 三横一纵模型（记忆的空间结构）

```text
三横（时间层次，生命周期不同）：
┌────────────────────────────────────────────────┐
│ Working Memory  = AgentState.messages（run 内） │ 活一次 run，Checkpoint 管
├────────────────────────────────────────────────┤
│ Session Memory  = ChatSession.history（会话内） │ 活一次会话，会话层持有
├────────────────────────────────────────────────┤
│ Long-term       = MemoryStore 的 MemoryEntry   │ 跨会话，本阶段核心交付
└────────────────────────────────────────────────┘

一纵（空间隔离）：
MemoryScope = "kind:id"
  agent:weather-bot   Agent 自己的常识
  user:u1             用户个人偏好（隔离）
  session:s1          会话级事实
  task:r42            任务工作记忆（对应 Stage 7 AsyncTask）
  channel:c1          频道共享记忆（共享）
```

关键认知：**ChannelMemory 不是一套新系统，它就是 `scope=channel:c1` 的查询结果集**（P5）。

---

### 7. 概念 -> 类 -> 设计点（16 抽象映射）

#### 数据层（6 个）

| 类 | 概念 | 关键设计点 |
|----|------|-----------|
| `MemoryEntry` | 一条记忆 | record：id/scope/type/subject/content/importance/provenance/status/createdAt/expireAt。subject 是冲突检测的键；importance 是写入闸门的度量 |
| `MemoryScope` | 命名空间 | 工厂方法（agent/user/session/task/channel）+ of() 解析校验。隔离与共享统一为一个字符串 |
| `MemoryType` | 记忆类型 | PREFERENCE/FACT/EPISODE/SUMMARY/EVENT，对应 Tulving 分类 + 工程扩展 |
| `MemoryStatus` | 生命周期 | ACTIVE/PENDING_REVIEW/REJECTED/SUPERSEDED/EXPIRED 五态。检索只回 ACTIVE |
| `MemoryProvenance` | 来源溯源 | 四元组 sourceType/actor/runId/at。四种来源：USER_SAID/TOOL_RESULT/MODEL_DERIVED/ADMIN_EDIT |
| `MemoryQuery` | 检索条件 | scopes（必填）+ type/subject/keyword/limit。scopes 必填 = 隔离强制化 |

#### 存储层（1 个）

| 类 | 关键设计点 |
|----|-----------|
| `MemoryStore`（接口） | write/query/findActiveBySubject/update/findById/delete/listByScope。v1 = `InMemoryMemoryStore`（ConcurrentHashMap）。**隔离实现在 query：scope 不在查询列表里绝不返回** |
| `InMemoryMemoryStore` | query 内做四重过滤：scope 交集 -> ACTIVE -> TTL 惰性过滤（不起后台线程）-> keyword 包含。按 createdAt 倒序 + limit |

#### 流水线层（4 个）

| 类 | 概念 | 关键设计点 |
|----|------|-----------|
| `MemoryRetriever` | 读取端 | recall(scopes) / recallByKeyword / recallForContext(limit)。只回 ACTIVE 是 store 保证的，retriever 不重复判 |
| `MemoryExtractor` | 写入端 | 规则提取（USER 消息 + 偏好关键词命中 -> 候选，importance=0.7，subject=前 20 字）；extractAndStore 串完整写入流 |
| `MemoryPolicy` | 污染防御闸门 | shouldStore（闸1 importance>=0.5 + 闸2 同 subject 同内容频控）/ shouldSupersede / defaultStatusForScope（channel -> PENDING_REVIEW） |
| `MemoryAdmin` | 治理面 | listPending/approve/reject/updateContent/supersede/addEntry/setTtl/delete。approve 前先 supersede 同 subject 的旧 ACTIVE |

#### 上下文层（4 个）

| 类 | 概念 | 关键设计点 |
|----|------|-----------|
| `ContextBuilder`（接口，在 agent-core） | 上下文组装扩展点 | build(config, state) 可就地改写 state（D4）。null = 透传（Stage 1-7 行为不变） |
| `ContextBudget` | token 预算 | chars/4 估算，不引 tokenizer。目标是"压缩先于超窗"，不追求精确 |
| `ContextCompressor` | pi 式压缩 | 超预算 -> 保留 system + 最近 K 条，旧段调 ModelClient 总结（失败降级截断），返回 CompressionResult(compressed, archived, didCompress) |
| `MemoryContextBuilder` / `CompressingContextBuilder` / `PassthroughContextBuilder` | 三个实现 | Memory 版 = 压缩 + recall + 注入 [Known memories]；Compressing 版 = 只压缩 + 归档；Passthrough = 透传 |

#### 辅助（2 个）

| 类 | 概念 | 关键设计点 |
|----|------|-----------|
| `ChatSession` | 会话记忆层 | history + toAgentState(拼 system) + syncFrom(剔 SYSTEM 回写)。三横中 Session 层的载体 |
| `MemoryTools` | 模型自决 | save_memory（importance=1.0 豁免闸1，仍走 supersede + 待审）/ search_memory（scope 隔离检索）。MemGPT self-editing 的最小版 |

---

### 8. 五条数据流（逐步追踪）

#### 8.1 写入流（run 结束时）

代码入口：`MemoryExtractor.extractAndStore()`

```text
输入：本次 run 的 messages + 目标 scope + provenance 模板

1. extract：过滤只留 USER 消息
   -> 内容命中偏好关键词（记住/我喜欢/always/prefer...）
   -> 生成候选 MemoryEntry(importance=0.7, type=PREFERENCE, subject=前20字)

2. 闸 1（MemoryPolicy.shouldStore）：
   importance 0.7 >= 阈值 0.5 ?   -- 否 -> 丢弃

3. 闸 2（shouldStore 内）：
   store.findActiveBySubject(scope, subject)
   已有相同内容 ?                  -- 是 -> 丢弃（频控）

4. 闸 3（shouldSupersede）：
   同 subject 但内容不同 ?
   -> 是 -> 旧条目 update 为 SUPERSEDED（不物理删）

5. 状态默认值（defaultStatusForScope）：
   scope 以 "channel:" 开头 -> PENDING_REVIEW（未审不生效）
   否则 -> ACTIVE

6. store.write(entry)  -- 此时才真正落库
```

```65:86:projects/java-agent-framework/agent-memory/src/main/java/io/github/qwzhang01/agent/memory/MemoryExtractor.java
public int extractAndStore(List<ChatMessage> messages, String scope, ...) {
    List<MemoryEntry> candidates = extract(messages, scope, provenance);
    int stored = 0;
    for (MemoryEntry candidate : candidates) {
        if (!policy.shouldStore(candidate, store)) { continue; }
        if (policy.shouldSupersede(candidate, store)) { ...update old SUPERSEDED... }
        MemoryStatus defaultStatus = policy.defaultStatusForScope(candidate.scope());
        store.write(candidate.withStatus(defaultStatus));
        ...
```

关键点：**三道闸全在写路径**（P2 写谨慎）；supersede 不物理删（P6 可审计）。

#### 8.2 读取流（每次 buildRequest 时）

代码入口：`ReActAgentLoop.buildRequest()` -> `MemoryContextBuilder.build()`

```text
1. ReActAgentLoop 每步构造 ModelRequest 前：
   config.getContextBuilder() != null ?
   -> 是 -> cb.build(config, state)        -- Stage 8 路径
   -> 否 -> new ArrayList<>(state.messages) -- 透传，Stage 1-7 行为

2. MemoryContextBuilder.build：
   a. compressor.compress(messages)        -- 预算内则原样通过（见 8.3）
   b. retriever.recall(scopes=[user:u1, channel:c1])
      -> store.query 强制 scope ∈ 查询列表（隔离的实现点！）
      -> 只回 ACTIVE + 未过期
   c. 渲染记忆块：
      "[Known memories]\n- [PREFERENCE] diet: allergic to peanuts"
   d. 插入到 system prompt 之后（不回写 state，每轮重新检索注入）

3. 返回的 messages 进入 ModelRequest 发给模型
```

```115:122:projects/java-agent-framework/agent-core/src/main/java/io/github/qwzhang01/agent/core/agent/ReActAgentLoop.java
private ModelRequest buildRequest(AgentConfig config, AgentState state) {
    // Stage 8: use ContextBuilder if configured, otherwise passthrough (backward compatible)
    List<ChatMessage> messages = config.getContextBuilder() != null
            ? config.getContextBuilder().build(config, state)
            : new ArrayList<>(state.getMessages());
```

关键点：**隔离靠 store.query 强制，不靠调用方自觉**；注入不落盘（否则记忆会被再次 extract，自我复制）。

#### 8.3 压缩流（pi 式 compaction）

代码入口：`ContextCompressor.compress()`

```text
1. ContextBudget.estimate(messages) = 总字符数 / 4
   > budgetTokens ?  否 -> 原样返回（didCompress=false）

2. 分离消息：
   system 消息（全部保留） / 非 system 消息

3. 切分非 system：
   前 (n - keepRecent) 条 = 待归档段
   后 keepRecent 条 = 近期保留段

4. 归档段 -> 拼成总结请求 -> ModelClient.chat
   成功 -> "[Summary of earlier conversation]\n..."（user 角色）
   失败 -> 截断降级（"Compaction failed - first 500 chars"）

5. 重组：[system..., summary, 最近 K 条]

6. CompressingContextBuilder / MemoryContextBuilder：
   -> state.getMessages().clear() + addAll(压缩结果)   -- 就地改写
   -> 归档段原文存为 MemoryEntry(type=SUMMARY, scope=session:xx) -- 可回溯
```

关键点：**为什么改写 state 而不是只压请求副本** -> Checkpoint 存的必须 = 实际发送的（Stage 6 snapshot 哲学），否则恢复后状态与模型见过的历史不一致；有损的代价用"原文归档进 store"补回。

#### 8.4 治理流（channel 共享，Claude Tag 式）

代码入口：`MemoryAdmin`

```text
T1  用户 A 在频道 c1 说"我对花生过敏"
    -> 写入流（8.1）-> scope=channel:c1 -> PENDING_REVIEW

T2  用户 B recall([channel:c1]) -> 空（未审不生效 -- 防污染核心闸）

T3  管理员治理：
    admin.listPending("channel:c1")          -> 看到待审条目
    admin.approve(entryId)
      -> 同 subject 已有 ACTIVE ? 先 supersede 旧的
      -> 条目变 ACTIVE

T4  用户 B recall([channel:c1]) -> 可见 ✅

T5  发现记错了（当初口误）：
    admin.supersede(entryId, "user A is NOT allergic...")
      -> 旧条目 SUPERSEDED（保留）
      -> 新条目 ACTIVE（provenance=ADMIN_EDIT）
      -> 检索只见更正版；listByScope 可见完整审计链（谁说的->谁改的）

T6  过期清理：
    admin.setTtl(entryId, expireAt)  -> 查询时惰性过滤
```

关键点：**"未审不生效"+"supersede 不物理删" = 共享记忆的治理闭环**。前者防错误进入上下文，后者保证错误可追溯、可回滚。

#### 8.5 工具流（模型自决，MemGPT 思想）

代码入口：`MemoryTools.saveMemory / searchMemory`

```text
模型决定保存：
  tool_call: save_memory(subject="diet", content="allergic to peanuts", type="PREFERENCE")
    -> importance=1.0（显式保存 = 高置信，豁免闸 1 阈值）
    -> shouldSupersede 检查（不豁免）
    -> defaultStatusForScope（channel 仍待审，不豁免）
    -> store.write

模型决定检索：
  tool_call: search_memory(keyword="peanut")
    -> recallByKeyword(scopes)   -- scope 隔离照常生效
    -> "Found 1 memories:\n- [PREFERENCE] diet: allergic to peanuts"
```

关键点：**豁免的只是阈值闸，supersede 和待审不豁免** -- 模型有存什么的自由，没有绕过治理的自由。这与 Stage 3 插件自进化（Agent 自己 load/unload 插件）是同一哲学：自己管自己的能力/记忆。

---

### 9. 设计决策 D1-D8 与哲学对照

| 决策 | 内容 | 对应哲学 |
|------|------|---------|
| D1 | AgentState 与 MemoryStore 分层 | P8（分层=生命周期不同） |
| D2 | 结构化 MemoryEntry 而非原始消息 | P3 + P6（治理要元数据） |
| D3 | 共享与隔离统一为 MemoryScope | P5（一面两体） |
| D4 | compaction 改写 state + 原文归档 | P1（上下文稀缺）+ P3（归档兜底回溯） |
| D5 | ContextBuilder 挂 AgentConfig（null 透传） | 组合式框架风格（与装饰器 ModelClient 一致） |
| D6 | 污染防御三道闸 + channel 默认待审 | P2（写谨慎）+ 派系四 |
| D7 | 检索 v1 用 scope+keyword 不用向量 | 范围控制（接口已留好） |
| D8 | MemoryTools 模型自决存取 | 派系二（MemGPT self-editing）+ P7（双通道读取） |

---

### 10. 面试速答（7 问）

**Q1：短期记忆、长期记忆和工作记忆有什么区别？**
生命周期不同，不是数据结构不同。工作记忆 = context window（一次 run 内的 AgentState）；会话记忆 = ChatSession（一次会话）；长期记忆 = MemoryStore 的 MemoryEntry（跨会话持久）。三层靠"编码写入 + 检索调回"连接，原型是 Atkinson-Shiffrin 模型。

**Q2：Memory 是不是把聊天记录全部塞回 prompt？**
不是，那是没有记忆系统的做法。Memory 是提炼后的结构化条目（subject/type/provenance/status），按 scope 检索注入。聊天记录全量塞回是"上下文管理"问题，靠 compaction 解决 -- 压缩历史 + 保留最近 K 条 + 原文归档。

**Q3：记忆污染怎么防？**
写路径三道闸：importance 阈值（不值得存的进不来）+ 频控（相同内容不重复写）+ supersede（同主题更新不覆盖、标记取代保留审计）。共享场景再加"待审闸"：channel 写入默认 PENDING_REVIEW，未 approve 不进任何人的上下文。核心思想：污染一条记忆之后每轮都放大它，所以闸门全在写路径。

**Q4：共享记忆怎么治理？**
四个抓手：scope（channel:* 即共享，与隔离同一机制）、provenance（谁说的/怎么来的/哪次 run/何时）、status 生命周期（待审->ACTIVE->SUPERSEDED）、MemoryAdmin（approve/reject/supersede/setTtl）。更正走 supersede 不物理删，保证审计链完整。

**Q5：compaction 为什么改写 state 而不是只压请求副本？**
Checkpoint 存的必须等于实际发给模型的（snapshot 一致性），否则恢复后状态与模型见过的历史不一致。有损压缩的代价用"原文归档为 SUMMARY 条目"补回，需要时可回溯。

**Q6：多用户隔离和多人共享怎么统一？**
一个 MemoryScope 字符串。user:u1 是隔离（查询列表里没有就查不到），channel:c1 是共享（两个用户的查询列表都含它）。隔离不是一套新机制，是 scope 不在查询列表的自然结果 -- 隔离实现在 store.query 强制 scope 交集。

**Q7：你的设计和 mem0 / LangChain / MemGPT 什么关系？**
组合了三派的精华：LangChain/pi 的 compaction（解决装不下）+ mem0 的提取-冲突消解管线（解决记什么，判定器 v1 用规则不用 LLM，教学可测）+ MemGPT 的 self-editing 思想（MemoryTools）+ 派系四的治理（channel 待审 + admin）。有意不做向量检索（接口留好，Stage 15 RAG 再加）和知识图谱。

---

### 11. 主流方案速查表

| 方案 | 记忆形态 | 写入 | 读取 | 遗忘 | 治理 | 我们借鉴 |
|------|---------|------|------|------|------|---------|
| LangChain Memory 家族 | 消息序列 | 不写 | buffer/window/summary 注入 | 窗口滑出 | 无 | -- |
| pi compaction | 消息序列 + 归档 | 不写 | 压缩后注入 | 压缩归档 | 归档可回溯 | D4 直接借鉴 |
| MemGPT / Letta | 分层存储 | 模型自编辑 | 模型自检索 | 模型自删 | 弱 | MemoryTools（D8） |
| mem0 | 结构化条目 | LLM 提取 + ADD/UPDATE/DELETE/NOOP | 检索注入 | LLM 决策删除 | 溯源 | 三道闸 + supersede 同构 |
| ChatGPT memory | 双通道 | saved memories 显式存 | 注入 + 语义检索 | 用户管理 | 用户可删 | 双通道读取（P7） |
| Claude memory tool | 文件目录 | 模型显式写 | 模型显式读 | 模型/用户删 | 弱 | 显式保存豁免阈值 |
| Zep / Graphiti | 时序知识图谱 | 图谱抽取 | 图检索 | 双时间轴过期 | 时序审计 | 未借鉴（v2 候选） |
| Claude Tag 式频道记忆 | 共享条目 | 待审闸门 | scope 检索 | 管理员 | 强 | 整个 M8.4 |
