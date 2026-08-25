# 游戏 Agent 为什么不能只有一个 Chat Loop

> 配套蓝图：[architecture-stage-16.md](architecture-stage-16.md) §1（核心命题）· 概念笔记：[stage-16-tavern-concepts.md](stage-16-tavern-concepts.md) · 全剧本：[TavernGameExample.java](../examples/src/main/java/io/github/qwzhang01/agent/examples/TavernGameExample.java)
> 上一篇：[stage-16-article-5-replay-debug.md](stage-16-article-5-replay-debug.md)
> 状态：✅ Stage 16 已完成（M16.1~M16.5，agent-tavern 111 测试，全仓 885 全绿，存量零 diff）
> 这是 Stage 16 系列的第 6 篇、收口篇，也是 Stage 17 的引子。

---

## 1. 我今天要解决什么问题

前五篇把酒馆拆开了：领域模型、三层状态、回合管线、关系与事件、存档回放。这一篇把它们收回一句总纲，并回答那个一开始就被躲开的问题：

> 为什么不能只拿一个 Chat Loop，换三份 systemPrompt，叫做游戏 Agent？

如果能，Stage 16 是过度设计。如果不能，我们得说清楚缺的到底是什么——以及缺的那一层，为什么可以零改动地架在既有 Runtime 上。结论先行：**不能。** Chat Loop 缺的不是更长的 prompt，是整个世界层。第三个 Profile 会证明，缺的还可能是别的层。

---

## 2. 为什么会有这个认知冲突

Stage 1-14 造好的 Runtime 有一个更深的隐含共识：**一次对话就是一条消息流。** `agent.run("...")` 进去的是文本、出来的是文本，中间是工具调用。这个共识在五个假设上站稳，到酒馆门口全部破裂：

```text
1. 主体假设 —— 对话是「用户↔助手」一对一
   酒馆里是一个玩家面对多个角色，每人有独立人格、独立记忆、独立关系
   玩家对酒保说的话，诗人不一定听见

2. 状态假设 —— 状态 = AgentState.messages
   游戏状态是世界：第几回合、在哪、哪些 flag
   这些不在消息里，在 WorldState 里；消息只是投影

3. 影响假设 —— Agent 的输出就是终点
   角色的输出会反哺世界：好感、flag、事件
   对话是因果环的一环，不是终点

4. 记忆假设 —— 记忆是关于用户的偏好和事实
   角色记忆是第一人称剧情沉淀，按角色隔离、跨局存活
   「记得你」靠 MemoryStore，不靠复读聊天记录

5. 重复假设 —— 对话不可重演
   一局游戏必须能存档、能回放、能复盘
   存档是快照，回放是走录，两者都不是再聊一遍
```

五个假设，一个共同病灶：**消息假设。** 游戏的主语是世界。只加 Chat Loop，就是坚持用消息去冒充世界。看起来能 demo（三个角色会说话），摸上去没有后果（说话改不了局，局也不能回看）。

---

## 3. 它解决了什么问题

承认「主语是世界」之后，设计目标从「会扮演的 ChatBot」变成「第二个领域 Profile」：

```text
Stage 15 让 Agent 能进企业 —— 第一个领域 Profile：归属层
         谁在问 / 属于哪个租户 / 花了谁的钱

Stage 16 让 Agent 能演戏   —— 第二个领域 Profile：世界层
         角色有灵魂 / 说话有后果 / 一局有历史
```

「演戏」三个字对应五篇已经落地的答案：

```text
灵魂     CharacterCard → CharacterAgentFactory → systemPrompt
分层     人格 / 世界 / 记忆 三种寿命
后果     工具提交指令，TurnEngine 唯一 apply；关系净变幅；事件恰一轮
历史     SaveGame + TurnLog + GameReplayer 走录不重演
装配     TavernGame 门面，同一 Runtime，零存量改动
```

规划原文的六条验收，示例 T0-T7 全过：多角色对话、不同人格、对话改关系、关系影响后续、世界事件可触发、一局可保存回放。`agent-tavern` 111 测试，全仓 885 全绿。需要支持的八项——多角色、人格、世界状态、回合推进、事件、关系、长期记忆、状态回放——每一项都有对应类，而不是对应一段更长的提示词。

---

## 4. 核心抽象和架构

站在最顶层，游戏 Agent = **Chat Loop（机制层，零改动）+ 世界层（领域层，新增）**：

```text
        Chat Loop（agent-core，Stage 2，一行不改）
        ┌──────────────────────────────────────────┐
        │  ReActAgentLoop + AgentState + Tool      │
        └──────────────────────────────────────────┘
                           │ 调工具 / 续跑 state
        ┌──────────────────────────────────────────┐
        │  世界层（agent-tavern，Stage 16 新增）      │
        │                                          │
        │  character/  Card / Factory / Memory     │  ← 角色有灵魂
        │  world/      WorldState / WorldEffect    │  ← 说话有后果（指令）
        │  relation/   Matrix / Policy / Tool      │  ← 净变幅限幅
        │  event/      Rule / Evaluator / Tool     │  ← 恰一轮结算
        │  turn/       Engine / Turn / Log         │  ← 顺序管线
        │  replay/     SaveGame / Store / Replayer │  ← 一局有历史
        └──────────────────────────────────────────┘
```

注意方向：**不是**「Chat Loop 不够用了，得重写」，**而是**「Chat Loop 完全够用，缺的是它脚下那一层」。三处有意不复用把「够用」划清边界：

```text
不复用 RunManager     存档按 gameId 索引全套领域状态，不是 run checkpoint
不复用 EventBroker    事件是 playTurn 内同步规则评估，不是 fire→resume
不复用 Workflow       回合是固定顺序代码，无分支并行，图引擎零收益
```

复用的是机制原语：`systemPrompt`、`run(input, state)`、`Tool`、`MemoryScope.AGENT/SESSION`、`MemoryType.EVENT`、`GovernedToolExecutor`、`AgentState` 消息快照。零新枚举。Stage 15 加了 `TENANT` / `KNOWLEDGE`；Stage 16 一个都没加。

依赖正交：`agent-tavern` 不依赖 `agent-enterprise`，反之亦然。共同底座是 core + memory（+ 各自点用的 security）。两 Profile 平行，不互堵。

---

## 5. 一次完整数据流

用示例把五条破裂一次走完——这是「不是 Chat Loop」的最直观证据：

```text
T0  三张卡、三条规则、±5 政策、治理开、零 LLM（MockModelClient 十响应）

T1  @marcus 晚上好
    主体破裂的修复：路由到一个人，不是「助手」
    人格破裂的修复：systemPrompt 是酒保，不是通用客服

T2  @marcus 请你也喝一杯 → adjust_relationship +3 → 53
    影响破裂的修复：台词之外，关系是一等值

T3  @lyra 那首歌很好听 → set_world_flag(bard-mood, lively)
    状态破裂的修复：世界黑板动了，marcus 的对话历史没动

T4  @brawn 角落安静吗？→ improvisation 结算触发，lyra 事件响应
    主体再次：同一回合两个角色开口，一个 eventDriven
    影响再次：crowd=cheering 不是谁「说」出来的，是规则 apply 的

T5  @marcus 你是天下第一 → +10 被拒 → "sip by sip"
    Chat Loop 会让模型把好感改到满；世界层让拒绝成为观察

T6  save / load / @lyra Remember me?
    重复破裂的修复：续局，不是重聊；历史在 AgentState，once 在 evaluator

T7  replayFromDisk，stateAt(3)，finalState == save → YES
    重复破裂的修复：走录，不重跑模型
    GM 审计 3 条 EXECUTED：每个世界变更可查
```

中间产物是一局可 diff 的历史（turn-log.jsonl），不是一段对话截图。这就是游戏而不是聊天。

---

## 6. 最小代码或实验

零存量改动是总纲的最小实验，也是最硬的实验：除父 POM 注册和 examples 依赖外，`agent-core` / `agent-memory` / `agent-security` 零 diff，全仓存量测试零失败。它证明的不是「游戏简单」，而是 Stage 8 的 scope 设计已经够用——「谁的记忆」是取值问题，不是新机制问题。

人格隔离是第二条最小实验：`twoCharactersTwoPersonas` 同输入两套 SYSTEM。没有世界层时，这条测试写不出来——你会只有一个助手 prompt。有了 Card→Factory，这条测试成为「角色是谁」在机制层的可执行证明。

第三条是走录终态：`finalStateMatchesSave`。Chat Loop 跑完没有终态可对；世界层有。两文件互查失败，说明要么 apply 漏记，要么回放重演了不该重演的东西（模型或规则）。

---

## 7. 常见误区

1. **「游戏 Agent = 带人设的 ChatBot」** —— 人设只解决灵魂的一半（说话像不像）。后果和历史在人设外面。
2. **「再加一个导演 Agent 就有世界了」** —— 导演是 v2 群聊的分派问题。v1 缺的是黑板、限幅、结算、走录。没有这些，导演只是另一个 Chat Loop。
3. **「世界可以放在 systemPrompt 里每回合重写」** —— 那是便签注入的劣质版：不可限幅、不可走录、不可和工具 apply 对账。`WorldState` 必须是值。
4. **「能复用的都该复用」** —— RunManager / EventBroker / Workflow 形似神异。有意不复用是设计，不是偷懒。蓝图 D5/D6/D8 把理由写死，测试把边界锁住。
5. **「第二个 Profile 还要改 Runtime」** —— Stage 16 的证据链反对这句话。改 Runtime 是机制真缺时才做（Stage 15 的两个枚举）。机制不缺，只加领域。

---

## 8. 和相邻概念的区别

三 Profile 递进到这里是两站，第三站已经能看见轮廓：

```text
企业缺归属层   Tenant / User / CostLedger
               Chat Loop 不知道「谁在问、花谁的钱」

游戏缺世界层   WorldState / Relationship / GameEvent
               Chat Loop 不知道「角色有灵魂、说话有后果、一局有历史」

编码将缺变更层  （Stage 17）
               当输出不再是给人看的文本，而是对文件系统的一批修改
               Chat Loop 的文本假设会再破裂一次
```

两次落地，存量改动依次是：Stage 15 两处枚举加法，Stage 16 零。同一 Runtime 两类场景，已经从宣言变成可复核的证据。游戏域概念在企业域零出现，企业域概念在游戏域零出现——Profile 正交，不是一个超级模块兼容所有名词。

三个「状态」、三个「event」、三层「回放」，前五篇都拆过。收口时只留一句：同名不同层。面试能分清，就说明没有把 Profile 做成 Runtime 的补丁。

---

## 9. 我的设计判断

最重的一条：**Chat Loop 是机制，游戏是领域。** 把领域问题交给更强的模型或更长的 prompt，是用非确定性去补本该确定的东西——谁在这一回合说话、好感能不能 +10、事件会不会连环炸、回放能不能对上存档。这些不该问模型。模型负责演戏，引擎负责让戏有世界。

第二条：零存量改动是证明，不是目标本身。为了零改动而扭曲领域（比如伪造 runId 去复用 RunManager）更糟。三处有意不复用，保住了零改动的含金量——我们复用的是抽象，不是每一个看起来能插上的类。

第三条：装配是最后的考官。`TavernGameExample` 比任何单测都早发现「续局 initial 用错」。五篇里的类都对，门面仍可能把两个语义揉成一个参数。所以第 6 篇不把「类齐了」当成结束，而把「剧本跑通 + 重演终态==存档终态」当成结束。

诚实边界也一并收回：不做群聊并行、不做 NPC 自演化、不做异步世界事件、不做事件级联、不做局末压缩、不做 RL 轨迹桥。v1 的世界层刚好够证明 Chat Loop 不够，也刚好够证明 Runtime 已够。再多就是下一个阶段的领域，不是这个阶段的补丁。

---

## 10. 面试表达

> 「游戏 Agent 不能只有一个 Chat Loop，因为 Chat Loop 的五条隐含假设在酒馆场景全部破裂：一对一主体、状态等于消息、输出即终点、记忆等于用户画像、对话不可重演。Stage 16 的回答是第二个领域 Profile——世界层：角色即 Agent，世界即指令式黑板，影响即工具，回合即顺序管线，历史即走录。三个游戏工具只提交指令，TurnEngine 唯一 apply；关系按净变幅限幅，事件恰一轮同步评估；GameReplayer 不重跑模型。RunManager、EventBroker、Workflow 三处有意不复用。存量模块零改动。企业缺归属层，游戏缺世界层，同一 Runtime 兜底。当第三个场景还能零新机制落地时，Runtime 的抽象才算完整。」

---

## 11. 下一篇连接什么

世界层做完，18 周规划里的第三类场景还在：Coding Agent。那里的输出不再是台词或答案，而是对文件系统的一批修改——要可审查、可拒绝、可撤销。Chat Loop 的「输出是文本」这个从未被挑战的假设，会在编码场景再破裂一次。缺的将是变更层：工作区即边界、变更即补丁、命令即白名单客人。

第三个 Profile 若仍能零新机制落地，Runtime 的抽象就完整了。那是下一阶段的开篇命题。

→ [stage-17-article-1-controlled-engineering-loop.md](stage-17-article-1-controlled-engineering-loop.md)
