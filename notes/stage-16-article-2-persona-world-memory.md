# 角色人格、世界状态和记忆如何分层

> 配套蓝图：[architecture-stage-16.md](architecture-stage-16.md) D2 / D3（角色即 Agent，世界即黑板）· 对应实现：`character/CharacterCard.java`、`CharacterMemory.java`、`CharacterAgentFactory.java`，`world/WorldState.java`、`WorldEffect.java`
> 上一篇：[stage-16-article-1-domain-model.md](stage-16-article-1-domain-model.md)
> 状态：✅ Stage 16 已完成
> 这是 Stage 16 系列的第 2 篇：把「灵魂 / 世界 / 记忆」拆成三种寿命不同的状态。

---

## 1. 我今天要解决什么问题

上一篇给出了六组领域模型。这一篇只拆三样最容易叠在一起的东西：

```text
人格     CharacterCard.persona → systemPrompt     出厂设定，跨局不变
世界     WorldState + WorldEffect                 活一局，变更须经指令
记忆     MemoryStore 双 scope                     跨局印象 + 本局剧情
```

玩家感知上它们都是「这个角色还记得我」。机制上它们寿命不同、写入路径不同、丢失后果不同。分不清这三层，就会出现三种典型事故：用聊天记录冒充记忆、用记忆冒充世界、用世界 flag 冒充人格。

---

## 2. 为什么会有这个认知冲突

Chat Loop 默认只有一种状态：`AgentState.messages`。多轮续跑就是把历史消息再喂给模型。这个默认在普通问答里够用，在酒馆里会把三件事压成一件：

```text
酒保为什么这么说话？     → 人格（出厂设定）
现在是第 8 回合、大堂、夜晚？ → 世界（本局黑板）
他为什么记得你请过蜜酒？ → 记忆（跨局沉淀）
```

如果只靠消息流：人格会在长对话里被冲淡，世界会随着 token 窗口被截断，跨局「记得你」只能靠复读上一局原文。蓝图 D2 的裁决正好相反：**新开一局 = 全部 AgentState 重建，角色「记得你」靠 MemoryStore，不靠复读原始消息。**

冲突不是「要不要有状态」，而是「状态有几代寿命」。一次 run、一局 game、跨局记忆，三者并存，谁也不能替谁。

---

## 3. 它解决了什么问题

分层之后，每一层只回答自己的问题：

```text
人格层   这个角色是谁？         Card → Factory → systemPrompt，一角色一实例
世界层   这局游戏进行到哪？     WorldState 不可变 + apply(Effect) 唯一写路径
记忆层   这个角色跨局记得什么？  agent:{charId} 跨局 + session:{gameId} 局内
对话层   这一局已经说了什么？   每角色独立 AgentState，run(input, state) 原地续跑
```

对话层仍然在，但它降级为「本局消息投影」，不再兼任世界和记忆。`CharacterAgentFactoryTest.agentStateContinuesAcrossTurns` 锁住续跑：held state 两次 `run`，第二次请求含完整历史。跨局则换一套语义：新 `gameId` 拿不到上一局 `session:` 记忆，但 `agent:` 记忆还在——`CharacterMemoryTest.characterMemorySurvivesAcrossGames` 是这条裁决的可执行证明。

---

## 4. 核心抽象和架构

### 4.1 人格：出厂设定，翻译一次

`CharacterCard` 是纯数据。`displayName` 空则回落 `characterId`；`persona` 空则构造失败。`greeting` 是可选领域数据，门面可以消费，Factory 不把它写进 prompt——人格是「他是谁」，开场白是「他第一句说什么」，两件事。

`CharacterAgentFactory` 是翻译器，不是新循环。`create` 组装 `AgentConfig(card.characterId(), personaPrompt(card), modelClient, registry, maxSteps, contextBuilder)`。`gameTools` 为 null 时挂空 `InMemoryToolRegistry`——纯对话也能装配，工具不是人格的前提。`CharacterAgentFactoryTest.nullToolsPlainConversation` 锁住这条。同一张卡在两局游戏里会产出两个 Agent、两份新鲜 `AgentState`，但共享同一个 `agent:{characterId}` 记忆 scope。一局一实例，跨局靠记忆不靠历史。

`DEFAULT_MAX_STEPS = 6`，比通用默认 10 更紧：角色回合是场景节拍，不是研究任务。步数预算属于装配，不属于卡。卡回答「他是谁」，Factory 回答「他这一拍能走多远」。`configBoundToCard` 断言 `agentId == characterId`、`maxSteps == 6`，防止人格翻译时顺手改掉身份绑定。

Interaction rules 在 M16.1 就预埋进每一张卡的 prompt。游戏工具还没注册时，规则已经在；工具落地后，模型见到的行为契约不用改 Factory。这是「翻译通道只此一处」的好处：改人格改卡，改装配纪律改 Factory，不改 Runtime。

### 4.2 世界：一局黑板，变更即指令

`WorldState.initial(location)` 给出 turn 0、空 flags。引擎每玩一回合先 `nextTurn()`，再在回合内 `apply` 指令。`describe()` 是人读快照：`"Turn 8 · great-hall · bard-mood=lively"`——`[world]` 便签和回放摘要共用同一渲染。flags 用 `LinkedHashMap` 保插入序，因为要注入 prompt、要落盘；`Map.copyOf` 不保序，曾让 `describeWithFlags` 间歇失败。不可变不等于顺序无关。

`WorldEffect` 三指令穷尽世界能发生的事。没有 `map.put`，没有自由 setter。工具、事件随行效果，最后都走进 `TurnEngine.submitEffect`：apply + 记入本回合 `turnEffects`。一个 apply 点 = 一个审计点 + 一个记录点。

对照 `WorkflowState`：哲学同源，形态不同。工作流黑板活一次 run，自由读写优先；世界黑板活一局 game，可追溯优先。组合不继承——硬复用会带进 NodeContext / StepRecord。

### 4.3 记忆：白名单即全部设计

`CharacterMemory.scopesFor` 只做一件事：

```java
return List.of(
    MemoryScope.agent(characterId).value(),
    MemoryScope.session(gameId).value());
```

`contextBuilder` 把白名单交给 `MemoryContextBuilder`，compressor 显式 null，`recallLimit` 默认 8。记忆行渲染在同一条 `[Known memories]` USER 消息里，紧跟 SYSTEM——`memoriesInjectedAfterSystemPrompt` 锁格式，`recallLimitBoundsInjection` 按行数（`startsWith("- [")`）计数而不是按消息条数。实现期踩过一次：按消息条数断言期望 2 实际 1，失败本身证明 Builder 把整块记忆拼成一条。诚实边界：v1 局内全量历史，局末摘要压缩是 v2，复用 Stage 8 的 `ContextCompressor`，本阶段不承诺。

零新 scope kind。`AGENT` / `SESSION` 在 Stage 8 已有。对照 Stage 15 需要 `TENANT` / `KNOWLEDGE` 两处加法，游戏域一次都不用加。这是 D1「零存量改动」的兑现点。

### 4.4 对话状态：本局投影，角色隔离

`TurnEngine` 为每个 `characterId` 持一份 `AgentState`。`@lyra` 的请求里看不到 marcus 的历史——`TurnEngineTest.characterStateIsolation` 断言这一点。玩家对酒保说的话，诗人不一定「听见」；诗人记得的是自己和玩家的过节，靠自己的 state 和自己的 scope。

---

## 5. 一次完整数据流

一回合里三层同时在场，但写入点不同：

```text
playerSay("@marcus 来杯蜜酒")
        │
        ▼
[人格]  marcus Agent 的 systemPrompt 早已固定（Factory 装配时）
        模型始终看见「健谈热心的酒保」+ Interaction rules
        │
        ▼
[世界]  injectContext 前缀：
        [world] Turn 2 · great-hall
        [relationship] affection 50 (NEUTRAL)
        [player] @marcus 来杯蜜酒
        │
        ▼
[对话]  agent.run(注入后的文本, marcus 的 AgentState)
        本局历史续在 state.messages 里
        │
        ▼
[记忆]  MemoryContextBuilder 在 system 后插入 [Known memories]
        只检索 agent:marcus + session:{gameId}
        lyra 的私有记忆、上一局 session 记忆都不在白名单
        │
        ▼
模型若调 set_world_flag → 只改世界，不改人格，不写记忆
模型若什么都不调 → 只多一条对话，世界不动
```

`TavernGameExample` T1 开场：人格生效（Welcome to the Golden Oak），世界从 turn 0 走到 turn 1，关系仍是 50。三层各做各的，没有哪一层冒充另一层。

---

## 6. 最小代码或实验

记忆白名单四向，是分层是否成立的最小实验集（`CharacterMemoryTest`）：

```text
bothScopesVisible              同角色同局：agent + session 两条都能注入
crossCharacterIsolation        lyra 检索看不到 agent:marcus
crossGameIsolation             新 gameId 拿不到上一局 session 记忆
characterMemorySurvivesAcrossGames
                               换 gameId 之后，agent:{charId} 的 FACT/EPISODE 仍在
```

第四条直接锁住 D2：「记得你靠记忆，不靠历史」。新局重建 AgentState，跨局印象走 store，不复读上一局原文。这也避免消息无限膨胀——token 预算和人类记忆模型在这里对齐：记得事实（「你请过我喝蜜酒」），不逐字复读。

世界侧的最小实验是 `WorldStateTest.applyIsImmutable` + `SetWorldFlagToolTest.submitsInstruction`：apply 返回新实例；工具只提交指令。人格侧是 `twoCharactersTwoPersonas`：同输入两套 SYSTEM。三层各有一条「改了自己、没改别人」的测试。

---

## 7. 常见误区

1. **「把上一局 messages 整个拷进新局」** —— 这是用历史冒充记忆。角色会复读，不会记得。token 也会爆。D2 明确拒绝。
2. **「世界 flag 写成 MemoryType.FACT」** —— 「第 8 回合在大堂」是本局黑板，局终即弃或随存档走；「这个玩家请过蜜酒」才是跨局记忆。寿命不同，存错层会导致续局错乱或跨局污染。
3. **「persona 每回合重写」** —— 人格是出厂设定。回合变化的是世界便签和关系便签，不是灵魂本身。关系变了，模型看见的是 `[relationship] affection 62 (WARM)`，不是一张新卡。
4. **「记忆检索不设白名单」** —— 白名单空 = 空结果（fail-closed）。放大检索会让诗人读到酒保的私密印象，人格隔离当场破产。
5. **「WorkflowState 当 WorldState」** —— 一次 run 的黑板活不到下一回合，更活不到存档。寿命对不上，索引也对不上（`runId` vs `gameId`）。

---

## 8. 和相邻概念的区别

```text
一次 run 的状态     AgentState.messages / WorkflowState
                    角色这一次 run 看见的对话；工作流这一次执行的黑板
                    新开一局全部重建

一局 game 的状态    WorldState / RelationshipMatrix / firedEventIds
                    跨回合存活，变更经指令或限幅写路径
                    随 SaveGame 快照，随 TurnLog 走录

跨局记忆            MemoryStore @ agent:{charId}
                    非易失，按角色隔离
                    新 gameId 的 session 不可见，agent 可见
```

再和 Stage 15 对齐一次：企业的 `RequestContext` 是「这一请求属于谁」，寿命是一次请求；游戏的 `CharacterCard` 是「这个角色是谁」，寿命是卡的生命周期。两者都不是 Runtime 原语，都是 Profile 翻译出来的领域语义。依赖正交：企业不加世界，游戏不加租户。

`MemoryType.EVENT` 也容易和世界事件搞混。它是数据层「把发生的事记下来」，可选地把剧情写入角色记忆（「玩家在酒馆赢了骰子局」）。它不驱动世界，也不触发结算。驱动世界的是 `WorldEffect`，触发结算的是 `EventRule`。

---

## 9. 我的设计判断

分层的第一原则是**按寿命拆，不按感觉拆**。感觉上「角色记得世界」是一回事；寿命上人格几乎不变、世界随回合变、记忆跨局变，是三回事。用寿命做刀，切口一定在 Card / WorldState / MemoryScope 三处，而不会切出第四套状态系统。

第二原则是**写入路径比读取视图更重要**。人格几乎只读（改卡才改）；世界只经 `apply`；记忆只经 MemoryStore 的既有 API；对话只经 `run(input, state)`。读取可以有多视图（`describe()`、便签、存档、回放），写入必须唯一。后文的关系限幅、事件结算、走录回放，全部建立在「写入唯一」上。

第三原则是诚实边界写进代码。compressor 传 null，不是忘了，是 D2 写明的 v1 范围。长局 compaction 是顺手活，不在本阶段承诺。学习文章如果把「将来能压」写成「现在已经压」，就是编造。`noMemoriesNoInjection` 也属于诚实：空记忆零注入，不捏一条「你好像没印象」糊弄模型。没有事实，就不假装有记忆。

---

## 10. 面试表达

> 「游戏 Agent 的状态要按寿命分层，不能全塞进对话历史。人格是 CharacterCard，翻译成 systemPrompt，出厂设定几乎不变；世界是 WorldState，活一局，变更必须走 WorldEffect 指令，工具只提交、引擎唯一 apply；记忆是 MemoryStore 的两个已有 scope——agent:{charId} 跨局、session:{gameId} 局内——零新枚举。新开一局重建 AgentState，角色记得你靠记忆不靠复读原文。一次 run、一局 game、跨局记忆，三者并存不互替。这就是世界层和 Chat Loop 的本质差别。」

---

## 11. 下一篇连接什么

三层状态就位后，还缺一根因果轴：一回合对话如何把「说的话」变成「世界的变化」。下一篇走 `TurnEngine` 管线——mention 路由、便签注入、工具提交、引擎 apply——把影响即工具落到一条可回放的数据流上。

→ [stage-16-article-3-turn-drives-state.md](stage-16-article-3-turn-drives-state.md)
