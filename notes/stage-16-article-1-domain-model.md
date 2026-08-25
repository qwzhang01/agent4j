# 酒馆类游戏 Agent 的领域模型

> 配套蓝图：[architecture-stage-16.md](architecture-stage-16.md) §1 / §3（核心命题与六组抽象）· 概念笔记：[stage-16-tavern-concepts.md](stage-16-tavern-concepts.md) · 对应实现：`agent-tavern/` · 全剧本：[TavernGameExample.java](../examples/src/main/java/io/github/qwzhang01/agent/examples/TavernGameExample.java)
> 状态：✅ Stage 16 已完成（M16.1~M16.5，agent-tavern 111 测试，全仓 885 全绿）
> 这是 Stage 16 系列的第 1 篇：先把「游戏域到底缺什么」落到六组领域模型上。

---

## 1. 我今天要解决什么问题

Stage 15 做完第一个领域 Profile 之后，Runtime 已经能回答企业场景的三个问题：谁在问、属于哪个租户、花了谁的钱。缺的是**归属层**。现在换一个完全不同的场景：文字冒险酒馆。玩家走进来，对面坐着酒保、吟游诗人、佣兵队长。这一次缺的不是归属，而是另一层东西：

```text
角色有灵魂     —— 酒保和诗人听到同一句话，不会给出同一套回答
说话有后果     —— 夸一句、骂一句，世界和关系会动
一局有历史     —— 这局游戏能存、能续、能回看，不是聊完即弃
```

如果只拿 `agent.run("来杯蜜酒")` 去撑这个场景，你会得到一个会说话的聊天机器人，而不是一局游戏。这篇文章要回答的就是：游戏域的主语是什么，以及它如何翻译成已经存在的 Runtime 机制，而不是另起一套引擎。

---

## 2. 为什么会有这个认知冲突

Stage 1-14 造好的 Runtime 有一个从未被点破的隐含共识：**一次对话就是一条消息流**。`agent.run(...)` 进去的是文本，出来的是文本，中间是工具调用。这个共识在企业场景还能凑合——答案给用户，对话结束。在酒馆里它全线破裂：

```text
主体：一对一的「用户↔助手」变成一个玩家面对多个角色
状态：对话历史变成世界黑板（回合、地点、flag）
影响：Agent 输出不再是终点，台词会反哺关系和剧情
记忆：不再是「关于用户的偏好」，而是角色第一人称的剧情沉淀
重复：对话不可重演，变成一局必须能存档、能回放的历史
```

冲突的根源不是「游戏比聊天复杂」，而是**主语换了**。聊天的主语是消息；游戏的主语是世界。消息只是世界的投影之一。谁把这个主语认错，谁就会把酒馆做成「三个 systemPrompt 轮流说话」——看起来像多角色，摸上去没有世界。

---

## 3. 它解决了什么问题

Stage 16 的答案不是重写 Runtime，而是叠一层领域语义。蓝图把它收成五句话：

```text
角色即 Agent     CharacterCard 装配为 AgentConfig，persona 即 systemPrompt
世界即黑板       WorldState 领域黑板，变更即 WorldEffect 指令
影响即工具       对话→状态的桥 = 三个游戏工具
回合即管线       TurnEngine：路由 → 注入 → 响应 → 结算 → 落账
历史即事件流     TurnLog append-only → GameReplayer 走录回放
```

判断它是不是 Profile，标准很硬：`Relationship`、`WorldState`、`GameEvent` 在 Runtime 里不存在，在 `agent-tavern` 里是一等公民。机制层一个枚举值都不用加——角色记忆用已有的 `agent:{charId}`，局内剧情用已有的 `session:{gameId}`。Stage 15 给 Runtime 加了 `TENANT` / `KNOWLEDGE` 两个枚举；Stage 16 一个都没加。这本身就是「同一 Runtime」宣言最有力的证据。

---

## 4. 核心抽象和架构

六组抽象，按包落地。record 是值，class 是行为，sealed 是有限合法结果集。

### 4.1 角色：Card → Agent 的唯一翻译通道

`CharacterCard` 是出厂设定：`characterId`、`displayName`、`persona`、`greeting`。persona 为空直接拒绝——构造器写着 `"a character without a soul is just a chat loop"`。`CharacterCardTest.blankPersonaRejected` 锁住这句话。

`CharacterAgentFactory.create(card, gameId, gameTools)` 是领域到机制的唯一通道：persona 渲染成 systemPrompt，记忆白名单挂上 `MemoryContextBuilder`，游戏工具像普通 Tool 一样注册。`personaPrompt` 固定四段：身份行 + persona 正文 + Interaction rules（按情绪用工具 / 世界变化从对话生长 / 工具报错自然继续 / 不出戏）。`maxSteps` 默认 6——角色回合是场景节拍，不是研究任务。

`CharacterMemory.scopesFor(charId, gameId)` 返回 `[agent:{charId}, session:{gameId}]`。共享是 scope 取值，不另造系统。`CharacterMemoryTest` 四向白名单：双 scope 可见、跨角色隔离、跨局隔离、角色记忆跨局存活。

### 4.2 世界：不可变黑板 + 指令

`WorldState` 是 record：`turnCount` + `location` + `flags`。没有 public setter。唯一变更路径是 `apply(WorldEffect)`，返回新实例，原状态不动。`WorldEffect` 是 sealed 三指令：`SetFlag` / `ClearFlag` / `SetLocation`。`WorldStateTest.applyIsImmutable` 断言 apply 之后旧实例 flags 不变。

`SetWorldFlagTool` 不持有世界、不调用 apply。它只把 `WorldEffect.SetFlag` 交给引擎给的 `Consumer<WorldEffect>` sink。工具产出指令，引擎唯一 apply——这是后文反复出现的纪律。

### 4.3 关系：值 + 唯一写路径 + 政策

`Relationship` 是 0-100 好感 + `lastChangedTurn`，`tier()` 派生六档：`STRANGER` / `COLD` / `NEUTRAL` / `WARM` / `FRIEND` / `DEVOTED`。`describe()` 给出 `"affection 62 (WARM)"`，给 `[relationship]` 便签和 GM 视图共用。

`RelationshipMatrix.apply(characterId, delta, turnNo)` 是唯一写路径，返回 sealed 两态：`Applied` / `Rejected`。拒绝是正常游戏流，不是异常。`RelationshipPolicy.maxChangePerTurn` 默认 5，按**净变幅累计**计费，不是单次。

### 4.4 事件：剧情事实 + 同步规则

`GameEvent` 是剧情事实：`eventId` + `description` + 可空的 `respondCharacterId`。`EventRule` 是谓词 + 事件 + 随行 effects + `once`。`EventEvaluator.evaluate(GameFacts)` 在回合结算点同步评估；`triggerManually(eventId)` 免条件、守 once。`GameFacts` 是冻结的世界 + 关系 + 回合数。

### 4.5 回合：固定顺序管线

`TurnEngine.playTurn(input)`：mention 路由 → `world.nextTurn()` → `[world]` / `[relationship]` / `[player]` 便签注入 → 角色 `run(input, state)` → 事件恰一轮结算 → `Turn` 落 `TurnLog`。`TurnResult` 恰两态：`Completed(Turn)` / `RoutingMiss`。路由失败不烧模型、不推进回合、不落账。

`Turn` 是回放数据单元：玩家输入、谁在说、全部响应、`appliedEffects`、`relationshipChanges`、`triggeredEventIds`。

### 4.6 存档回放与门面

`SaveGame` 是全套领域状态：世界 + 关系 + 各角色消息史 + 已触发事件集。`GameStore` 双文件：`save.json` 快照、`turn-log.jsonl` 历史流。`GameReplayer` 走录不重演。`TavernGame` 是装配门面：`playerSay` / `save` / `load` / `replay`。

依赖方向：`agent-tavern → agent-core + agent-memory + agent-security`。不依赖 workflow / scheduler / channel / enterprise。三处有意不复用：`RunManager`、`EventBroker`、`Workflow`。`agent-model` 只在 test scope 出现——全链路用 `MockModelClient.scripted()` 编排台词和工具序列，示例零 LLM。这不是偷懒，是和 Stage 8-15 同一手法：领域模型的正确性不该绑在某次采样上。

---

## 5. 一次完整数据流

`TavernGameExample` 的 T0-T4 已经把领域模型串成因果链：

```text
T0  三张 CharacterCard（marcus / lyra / brawn）
    + 三条 EventRule（confession ≥80 / improvisation flag∧turn≥4 / hostility ≤20）
    + RelationshipPolicy(5) + governance(InMemoryAuditLogger)

T1  playerSay("@marcus Good evening!")
    → Factory 把 marcus.persona 写成 systemPrompt
    → 好感初值 50（NEUTRAL）注入 [relationship]
    → 世界：Turn 1 · great-hall

T2  "@marcus Keep one for yourself"
    → 模型调 adjust_relationship(marcus, +3)
    → Matrix 53，Turn.RelationshipChange 落账

T3  "@lyra That song earlier was genuinely lovely."
    → 路由到 lyra（marcus 的 AgentState 不动）
    → set_world_flag(bard-mood, lively)
    → 引擎 submitEffect → WorldState.apply

T4  "@brawn Quiet corner tonight?"
    → 结算评估：flag=lively ∧ turn≥4 → improvisation 触发
    → 随行 SetFlag(crowd, cheering) + lyra 事件响应
```

玩家看到的是台词。领域模型看到的是：一张卡、一块黑板、一次限幅写入、一条规则命中。聊天只是入口。

---

## 6. 最小代码或实验

人格隔离是领域模型是否成立的第一块试金石。`CharacterAgentFactoryTest.twoCharactersTwoPersonas` 用同一个输入跑两个角色，捕获两次 `ModelRequest`，断言两条 SYSTEM 消息不同：

```java
// CharacterAgentFactory.personaPrompt —— 领域文本到机制文本的唯一翻译
"You are playing the game character " + card.displayName()
    + " (" + card.characterId() + ").\n\n"
    + card.persona().trim() + "\n\n"
    + "Interaction rules:\n"
    + "- Always respond in character: ...\n"
    + "- When game tools are available ... use them only when they genuinely fit ...\n"
    + "- If a tool returns an error ... accept the limit and continue ...\n"
    + "- Never break character: ..."
```

`personaInjectedIntoSystemPrompt` 再锁身份行、persona 正文、Interaction rules 三段都进了模型实见的第一条 SYSTEM。没有这两条测试，「角色即 Agent」只是口号。有了它们，Card 就是可执行的灵魂，不是注释。

另一条最小实验在世界侧：`SetWorldFlagToolTest.submitsInstruction` 给工具一个收集 sink，断言工具执行后 sink 里是一条 `WorldEffect.SetFlag`，世界本身零变化。工具不会 apply——这是领域模型「变更即指令」的字面兑现。

---

## 7. 常见误区

1. **「多角色 = 多个 ChatBot 并列」** —— 没有世界、关系、事件，三个 Bot 只是三个对话框。Profile 的本质是给 Runtime 翻译出领域语义，不是多开几个 Agent。
2. **「把游戏状态塞进 AgentState.messages」** —— 第 8 回合、大堂、bard-mood=lively 不是对话历史。它们活在 `WorldState`。消息是投影，黑板才是主语。
3. **「复用 WorkflowState 当世界」** —— 哲学同源（共享状态集中管理），形态不同。`WorkflowState` 是一次 run 的自由 Map；`WorldState` 是一局游戏的指令式领域类型。硬复用会带进 `runId` / cursor / 黑板自由读写。
4. **「游戏工具直接改 Map」** —— 散落的 `flags.put` 既不能审计也不能回放。指令必须是一等值。
5. **「领域模型要改 Runtime」** —— Stage 16 的证据是零存量改动。新场景能零改动落地，才证明之前的抽象是对的。

---

## 8. 和相邻概念的区别

三组最容易混的边界，蓝图 §1 写死，面试也会问：

```text
Character（16）vs Agent（core）vs User（15）
  Agent 是机制层执行单元（loop + 工具，无领域语义）
  User  是企业层的「谁在要求」（归属 / 权限 / 预算）
  Character 是游戏层的「人格化 Agent」（persona + 角色记忆 + 与玩家的关系）

WorldState（16）vs WorkflowState（5）vs MemoryStore（8）
  WorkflowState 活一次 run（结束即弃）
  WorldState    活一局 game（跨回合，变更须经 Effect）
  MemoryStore   活跨局（角色对玩家的长期印象）

GameEvent（16）vs EventBroker（7）vs MemoryType.EVENT（8）
  EventBroker     是恢复触发器（fire → resume(runId)）
  GameEvent       是剧情事实（发生了什么，改变世界）
  MemoryType.EVENT 是事件记忆（把发生的事记下来）
```

Profile 正交：`agent-tavern` 不依赖 `agent-enterprise`，反之亦然。企业域概念（Tenant / RequestContext / CostLedger）在游戏域零出现；游戏域概念在企业域零出现。两 Profile 共同底座是 core + memory（+ 各自点用的 security）。

---

## 9. 我的设计判断

最重的一条：**第二个领域 Profile 缺的是世界层，不是更强的 Chat Loop。**

很多人接到「做个酒馆游戏 Agent」会先去加导演、加群聊、加更长的 prompt。那些是 v2。v1 要先承认：角色、世界、关系、事件、回合、回放，是六组**领域模型**，不是六段提示词。模型负责演戏，引擎负责让戏有后果。

第二条判断是翻译，不是发明。`CharacterAgentFactory` 没有新循环，`SetWorldFlagTool` 没有新执行器，`CharacterMemory` 没有新 scope kind。全部工作是把领域名词接到已有机制上。零存量改动不是运气，是 Stage 8 `kind:ID` 正交命名空间的必然结果：任何「谁的记忆」都是 scope 取值问题。

第三条是有意不复用。形似的东西（RunManager 能存、EventBroker 能触发、GraphRuntime 能编排）神异——游戏存档按 `gameId` 索引全套领域状态，游戏事件在 `playTurn()` 内部同步评估，回合管线是固定顺序代码。硬复用的代价是机制税换零收益。蓝图把这三处写进 D5 / D6 / D8，测试把它们锁住。学习这六组模型时，优先问「它翻译的是哪条 Runtime 原语」，而不是「它新发明了什么循环」。答得上来，领域模型就立住了；答不上来，多半是在用 Chat Loop 冒充世界层。

---

## 10. 面试表达

> 「Stage 16 是第二个领域 Profile。企业场景缺归属层，游戏场景缺世界层：角色有灵魂、说话有后果、一局有历史。我没有重写 Runtime，而是用六组领域模型做翻译——CharacterCard 变成 AgentConfig，WorldState 变成指令式黑板，关系与事件变成工具和结算规则，TurnEngine 用顺序代码推进回合，GameReplayer 走录回放。三个游戏工具只提交指令，引擎是唯一 apply 点。存量模块零改动，RunManager / EventBroker / Workflow 三处有意不复用。当一个新场景能零存量改动落地时，才证明之前的抽象是对的。」

---

## 11. 下一篇连接什么

领域模型立住之后，下一问是分层：人格、世界、记忆分别活多久、存在哪、谁能改。一次 run、一局 game、跨局记忆，三者并存不互替。搞混这三层，就会用聊天记录冒充世界，或用世界 flag 冒充角色记忆。

→ [stage-16-article-2-persona-world-memory.md](stage-16-article-2-persona-world-memory.md)
