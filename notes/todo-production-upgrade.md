# ToDo：引擎生产级升级（agent4j × Moonlit）

> 状态：📋 规划中（2026-09-07）  
> 前置：Wave 1–4 + T28 全部完成（角色引擎施工清单，已并入 `architecture-character-engine.md`）  
> 来源：Moonlit 代码 code-review 后识别的「demo → 生产」差距  
> 原则：**框架只改通用挂钩，产品判断不进框架。每项独立可交付，做完再勾。**

---

## 边界（和 Wave 1-4 一致，不重复说）

### 框架做（A 系列）

- 召回接口扩展（query 参数、类型隔离、排序策略）
- Token 预算控制（上下文截断）
- 可观测事件（TurnTrace）
- PersonaSpec 版本号
- 异步/采样抽取
- stream 重试策略接口

### Moonlit 做（M 系列，已完成，已从 `MOONLIT_BACKLOG.md` 待办删掉）

- 情绪状态机、关系事件驱动、危机分类
- 黄金集 / LLM-judge 回归
- 配额改成功后扣
- 每轮审计扩展

---

## A. 框架 · agent-memory

### A1 · `recallForContext` 加可选 query 参数

**问题：** 召回与「用户这句说什么」完全无关，只按 importance × 时间排，聊咖啡可能仍注入下周面试。

- [ ] `MemoryRetriever.recallForContext(scopes, limit, @Nullable String query)` 新增第三参数
- [ ] 默认实现忽略 query（向后兼容，所有现有调用不变）
- [ ] `MoonlitChatMemoryRetriever` 重写：把 query 传给排序器，后续可接关键词/向量相关度
- [ ] 测试：有 query 时相关条目排名更靠前；无 query 时行为不变

**模块：** `agent-memory`  
**类：** 改 `MemoryRetriever` 接口和实现；改 `MoonlitChatMemoryRetriever`

---

### A2 · SUMMARY 单独池，不和 FACT/EVENT 争 top-N

**问题：** `MemorySource` 用同一个 `recallForContext` 召回所有类型，SUMMARY 会挤掉 FACT/EVENT。

- [ ] `MemoryRetriever` 新增 `recallSummaries(scopes)` 接口，只返回 SUMMARY 类型条目
- [ ] `MemorySource` 组装时：先填 summaries（上限 1 条），再用剩余 N-1 个槽给 FACT/EVENT
- [ ] 测试：5 条 FACT + 1 条 SUMMARY，top5 = 1 SUMMARY + 4 FACT；不因 SUMMARY importance 高而把 FACT 挤出去

**模块：** `agent-memory`、`agent-chat`  
**类：** 改 `MemoryRetriever`、`MemorySource`；测试改 `MemorySourceTest`

---

### A3 · `ExtraTextSource` 支持 token 预算截断

**问题：** ExtraText 无上限，指令越积越长会把人设挤出上下文窗口。

- [ ] `ExtraTextSource(String text, int maxTokens)` 新增参数（默认 -1 = 不限）
- [ ] 超出时按换行段落从末尾截断（越靠后优先级越低）；写 warn 日志说明截了多少
- [ ] 不依赖模型具体 token 计数器，用字节数 / 字符数近似（可后期换）
- [ ] 测试：10000 字 ExtraText，`maxTokens=500` 时输出 ≤ 500 tokens 且开头不截

**模块：** `agent-chat`  
**类：** 改 `ExtraTextSource`；测试 `ExtraTextSourceTest`

---

### A4 · `LlmMemoryExtractor` 支持异步 + 采样率

**问题：** 每轮同步抽取，阻塞 Listener 且贵；开发/测试环境也会打 LLM。

- [ ] `LlmMemoryExtractor.extractAsync(messages, scope, provenance, policy, store)` 返回 `CompletableFuture<Integer>`
- [ ] 构造参数加 `sampleRate`（0–100，整数）；`floorMod(sessionHash ^ seed, 100) < rate` 决定本轮要不要抽（和 `TrajectorySampler` 同一套哈希纪律，跨 JVM 可重算）
- [ ] 失败时 CompletableFuture 完成（不 exceptionally 崩），并打 warn + sessionId
- [ ] `KeywordMemoryExtractor` 同步保留，不受影响

**模块：** `agent-memory`  
**类：** 改 `LlmMemoryExtractor`；新建测试覆盖 sampleRate=0（不抽）/ sampleRate=100（全抽）

---

## B. 框架 · agent-chat

### A5 · ChatRoom 每轮发结构化 TurnTrace 事件 ✅ 完成（2026-09-07）

**问题：** 出事难复盘「模型当时看见了什么」；改人设不知道哪个版本被用了。

- [x] 新建 `AgentEvent.TurnTrace` record（`agent-core`）
- [x] `ChatEngine.stream` 在 `Done` 之前发射 `TurnTrace`
- [x] `MemorySource` 在 `contribute()` 后暴露 `lastRecalledSubjects()`（volatile 字段）
- [x] `ExtraTextSource` 在 `contribute()` 后暴露 `lastOutputBytes()`（volatile 字段）
- [x] `buildTurnTrace` 收集全部 MemorySource subjects + ExtraText bytes + prompt/completion chars + latencyMs
- [x] 测试：`TurnTraceTest`（8 个用例）+ `ChatEngineTest` / `ChatRoomTest` 事件计数更新

**说明：** `personaVersion` 字段当前始终为 null，待 A6 写入 `PersonaSpec.version` 后联动补全。

**模块：** `agent-core`、`agent-chat`  
**类：** 改 `AgentEvent`、`ChatEngine`、`MemorySource`、`ExtraTextSource`；新建 `TurnTraceTest`

---

### A6 · PersonaSpec 携带版本号 ✅ 完成（2026-09-07）

**问题：** 人设改了，回复里看不到用的是哪版，黄金集回归无法关联。

- [x] `PersonaSpec` 加 `String version` 字段（4th component，nullable）
- [x] 3-arg convenience constructor `(personaId, displayName, attributes)` → 向后兼容，现有测试零改动
- [x] 新工厂 `PersonaSpec.of(personaId, version, systemPrompt)`
- [x] `ChatPersona` 加 `String version` 字段（5th component）+ 4-arg convenience constructor
- [x] `ChatPersona.render(spec, renderer)` 从 spec 传播 `spec.version()`
- [x] `ChatEngine.buildTurnTrace` 用 `speaker.version()`（替换 A5 占位 null）
- [x] `MoonlitPersonaRenderer.specFrom` 用 `character.getUpdatedAt()` ISO 格式填 version
- [x] 测试：`PersonaRendererTest`（3 新用例）+ `TurnTraceTest`（2 新用例 - null / v2.1.0 流转）

**说明：** 使用 `updatedAt` 作为 surrogate version，每次 prompt 编辑后 TurnTrace 自动切版本。

**模块：** `agent-chat`、Moonlit `seven-ai-learn`  
**类：** 改 `PersonaSpec`、`ChatPersona`、`ChatEngine`、`MoonlitPersonaRenderer`

---

### A7 · stream 硬标签重试策略接口 ✅ 完成（2026-09-07）

**问题：** 检测到身份泄漏或幻觉品名，没有内环重试的标准化接口。

- [x] 新建 `RetryPolicy` 接口（`shouldRetry / maxAttempts / retryExtraText / never()`）
- [x] `NeverRetryPolicy` 作为默认实现（`RetryPolicy.never()`）
- [x] `ChatEngine` 加 `retryPolicy` 字段，重构 `stream()` 为重试循环
  - 每次尝试的 ContentDelta 事件正常流向 listener
  - Done 在所有重试后统一发射一次；TurnTrace 在 Done 前
  - Error 不重试，直接返回
  - retriesDone >= maxAttempts 时放行（不死循环）
  - retry 时在 basePrefix 末尾追加 retryExtraText（system 消息）
  - 只有最终（接受的）reply 写入 room history
- [x] `ChatRoom.Builder.retryPolicy(RetryPolicy)` 可选挂载
- [x] `RetryPolicyTest`（6 用例）：never 合约 / 行为不变 / 命中→第二次生成 / retryExtraText 注入 / maxAttempts 防死循环 / maxAttempts=0 ≈ never / history 只写最终 reply

**模块：** `agent-chat`  
**类：** 新建 `RetryPolicy`、`NeverRetryPolicy`（`retry` 包）；改 `ChatEngine`、`ChatRoom`

---

## C. 框架 · 扩展（量上来后做）

### A8 · MemoryRetriever 可插拔排序策略 ✅ 2026-09-07

**前置：A1 已落**

- [x] 新建 `RankingStrategy` 接口：`rank(List<MemoryEntry> candidates, String query) → List<MemoryEntry>`；含 `defaults()` 工厂
- [x] `ImportanceRankingStrategy`：封装旧 `MemoryRetriever` 的 token-overlap + importance 逻辑；`queryRelevance()` 可继承覆盖
- [x] `HybridRankingStrategy`（stub）：预留向量融合入口，当前委托给 `ImportanceRankingStrategy`
- [x] `MemoryRetriever` 加 2-arg 构造器注入 strategy；1-arg 默认构造器不破坏现有调用方（`MoonlitChatMemoryRetriever` 无需改动）
- [x] `recallSummaries()` 仍走固定 BY_IMPORTANCE_THEN_RECENCY（摘要不受 query 影响）
- [x] `RankingStrategyTest`（9 用例）：默认排序 / query boost / blank query 退化 / empty input / hybrid 委托 / defaults() 工厂 / 自定义 strategy 注入 / 默认构造器行为 / limit 在 rank 后截取
- 全量：agent-memory 96 tests, 0 failures

**模块：** `agent-memory`  
**类：** 新建 `RankingStrategy` 及内置实现；改 `MemoryRetriever`

---

## D. Code Review 修复（2026-09-08，针对 A1–A8 提交 `122878e` 的复查）

> 来源：对 A1–A8 逐项代码审查后发现的 1 个真实 Bug + 5 项生产可靠性加固。全部已修复，全仓库测试通过（agent-core 32 + agent-memory 96 + agent-chat 128，BUILD SUCCESS，0 failures）。

- [x] **P0 Bug**：`MemorySource.contribute()` 的 `factLimit` 复用 `0` 同时表达"无限制"和"槏位已耗尽"两种含义；当 `limit == summary 槏位数`（如 `limit=1` 且存在一条 SUMMARY）时，FACT 限额被误判为无限制，SUMMARY 之外的全部 FACT 都会泄漏进上下文。改用 `long` + `Long.MAX_VALUE` 单一"无限制"哨兵，消除歧义。新增回归测试 `MemorySourceTest.limitEqualsSummarySlots_factBudgetIsZero_notUnlimited`。
- [x] **P0 并发风险**：`MemorySource.lastRecalledSubjects` / `ExtraTextSource.lastOutputBytes` 是"上次 contribute() 调用"快照，若同一实例被注册到多个 `ChatRoom`，或同一 room 被并发调用，`TurnTrace` 会读到别的会话的数据。已在 `ContextSource`/`MemorySource`/`ExtraTextSource` 类级 Javadoc 补充"一个实例只能属于一个 room、同一时间只处理一轮"的强约束说明。
- [x] **P1**：重试期间旧的 `ContentDelta` 无边界信号，UI 无法感知"上一段要作废"。新增 `AgentEvent.RetryStarted(discardedReply, attemptNumber, maxAttempts)`，在 `ChatEngine` 决定重试时于旧 attempt 结束、新 attempt 开始前发出；未配置 `RetryPolicy` 时从不发出（默认行为不变）。新增 `RetryPolicyTest` 三个用例覆盖"不重试不发"、"内容与序号正确"、"maxAttempts 封顶下计数正确"。
- [x] **P1**：`TurnTrace.promptTokens` 重试场景下统计口径不准——`buildTurnTrace` 始终用第一次 `assemble()` 的 `basePrefix`，未计入被接受那次尝试末尾追加的 `retryExtraText`。改为传入被接受尝试的 `attemptPrefix`。新增测试 `turnTrace_promptTokens_reflectsAcceptedAttemptsPrefix_includingRetryExtraText`。
- [x] **P2**：`LlmMemoryExtractor.extractAsync` 未显式传 `Executor` 时静默退化到 `ForkJoinPool.commonPool()`，与其它并行流任务抢占共享池。改为退化到专用的、daemon 线程、有界固定大小的 `DEFAULT_EXECUTOR`；生产环境仍建议显式注入自有线程池。
- [x] **P2**：`RankingStrategy` 接口 Javadoc 中"Two built-in implementations are provided"措辞让 `HybridRankingStrategy`（当前为空壳委托）显得像完整实现。补充"production-ready / not yet implemented"的明确标注，并将用法示例改回 `ImportanceRankingStrategy`。
- [x] 顺手统一 `ChatEngine.buildTurnTrace` 里的 `instanceof` 写法（去掉裸强转，和文件里其余模式匹配风格一致）。

**模块：** `agent-chat`、`agent-core`、`agent-memory`  
**类：** 改 `MemorySource`、`ExtraTextSource`、`ContextSource`、`ChatEngine`、`AgentEvent`（新增 `RetryStarted`）、`LlmMemoryExtractor`、`RankingStrategy`；测试改 `MemorySourceTest`、`RetryPolicyTest`

---

## 推进顺序

```text
P0（先做，影响读路径和成本）
  A1 (query 参数) → Moonlit 侧 M2 (传当前句)
  A2 (SUMMARY 隔离) → FACT 不再被挤
  A3 (ExtraText 预算) → 人设不被冲
  A4 (抽取异步+采样) → 省钱省时

P1（有了基础再做评测闭环）
  A5 (TurnTrace 事件) → Moonlit M9 (每轮审计)
  A6 (PersonaSpec 版本) → Moonlit M5 (人设版本) + M7 (黄金集)
  A7 (重试策略接口) → Moonlit 配身份泄漏重试

P2（量级上来后）
  A8 (可插拔排序) → 接向量第二路
```

---

## 对照：为什么不直接改 Moonlit

| 错 | 对 |
|----|----|
| Moonlit 覆盖 `recallForContext` 加 if query 逻辑 | 接口支持 query，排序策略可插拔 |
| ExtraText 字符串限制写死在 `MoonlitChatBizServiceImpl` | `ExtraTextSource` 带 maxTokens，任何消费者受益 |
| TurnTrace 事件在 Moonlit Listener 里拼 | 框架发事件，产品只需监听，不侵入生成路径 |
| `PersonaSpec.version` 在 Moonlit 自己加字段 | 框架 spec 带版本，所有 Profile 受益 |
