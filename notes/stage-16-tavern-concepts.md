# Stage 16 学习笔记：Tavern Profile 酒馆游戏域（场景 / 概念 / 映射 / 数据流）

> 目标：回答四个问题——酒馆游戏域要解决什么场景？核心概念与设计哲学是什么？概念如何映射到类？数据如何流转？
> 配套蓝图：[architecture-stage-16.md](architecture-stage-16.md) · 同域姊妹笔记：[stage-15-business-scenario.md](stage-15-business-scenario.md)（第一个 Profile）
> 对应实现：`agent-tavern/` · 全剧本：[TavernGameExample.java](../examples/src/main/java/io/github/qwzhang01/agent/examples/TavernGameExample.java)
> 当前状态：✅ Stage 16 已完成（M16.1~M16.5，agent-tavern 新增 111 测试，全仓 885 全绿）。

---

## 一、一句话定位

`agent-tavern` 是 java-agent-framework 的**第二个「领域 Profile」**——第一个是 Stage 15 的 Enterprise Profile（`Tenant`/`User`）。整个 Stage 16 的核心主张（蓝图 D1）是：

> **用同一套 Runtime 底座，只换「领域语义」，零改动现有模块。**

它做的事：把「能调用工具的 ReAct Agent」包装成「有灵魂、有记忆、会和你发展关系、能触发剧情」的酒馆游戏角色。

判断它是否是 Profile 的标准（同 Stage 15）：

> `Relationship`、`WorldState`、`GameEvent` 在 Runtime 中不存在，在 Tavern Profile 中是一等公民。

---

## 二、场景

### 2.1 主场景：文字冒险酒馆

想象一个文字冒险酒馆：

1. 玩家进入酒馆，里面有几个 AI 角色（酒保、吟游诗人、卫兵……）。
2. 玩家输入 `@marcus 来一杯蜂蜜酒` —— `@角色名` 点名，某个角色回应。
3. 角色不只是「回话」，还会**通过工具改变游戏状态**：
   - `set_world_flag`：吟游诗人心情变 `lively`，酒馆闹起来；
   - `adjust_relationship`：你说中了酒保心事，他对你 `+2` 好感；
   - `trigger_event`：你举杯祝酒 → 触发「全场欢呼」剧情事件，卫兵跟着起哄（事件指定了一个响应角色，会**当场说话**）。
4. 好感被限制：一个角色每回合净变化最多 `±5`，不能一轮刷满好感（防止「AI 酒保一轮坠入爱河」）。
5. 游戏能**存档/读档/回放**：存档是整局状态快照，回放是「重放记录、绝不重跑模型」。

### 2.2 一句话概括

> **这是「角色扮演游戏引擎」，不是聊天机器人**——聊天只是入口，真正的主语是「世界 + 关系 + 剧情」这三块可审计、可回放的状态。

### 2.3 它回答的领域问题

```text
角色是谁？         CharacterCard：persona + 人设，翻译成 systemPrompt
世界在哪、发生了什么？ WorldState + WorldEffect：turn/location/flags 的显式变更
你和他什么关系？   Relationship + RelationshipMatrix：0-100 好感 + 六档 tier
剧情怎么推进？     GameEvent + EventRule + EventEvaluator：结算点判定
一局怎么存、怎么回看？ GameStore + GameReplay：走录不重演
```

---

## 三、概念与设计哲学

### 3.1 最核心的概念：Profile

「Profile」= 在 Runtime **机制**之上叠加**领域语义**，但机制层一个字符都不改。

```text
Runtime 机制                          Tavern 领域语义
--------------------------------------------------------------
Agent / ReActAgentLoop                 Character（persona-ized Agent）
ToolRegistry                           set_world_flag / adjust_relationship / trigger_event
MemoryStore + MemoryScope              角色记忆（agent:{id} + session:{gameId}）
GovernedToolExecutor                   GM 后台（治理链 = 权限 + 审计）
（无）                                  WorldState / Relationship / GameEvent
```

### 3.2 三条「近乎孪生」的边界（蓝图 §1，最容易搞混）

项目里反复强调三组**同名但不同层**的东西，学习时必须分清：

| 概念 | 属于哪一层 | 生命周期 | 例子 |
|---|---|---|---|
| `GameEvent`（剧情事实） | 数据：发生了什么 | 一局游戏 | 「酒馆着火」 |
| EventBroker 事件 | 机制：fire→resume 一个 workflow | 一次 run | Stage 7 的事件机制 |
| `MemoryType.EVENT` | 记忆：记住发生过 | 跨局 | 「上次你救过我」 |
| `WorldState` | 领域状态 | **活一局游戏** | 「第 8 回合在大厅」 |
| WorkflowState | 运行状态 | **活一次 run** | 工作流黑板的游标 |
| `MemoryStore` | 记忆 | **跨局** | 角色记得你这个人 |

### 3.3 六条设计哲学（贯穿全部代码）

1. **影响即工具（D4）**：角色想改变世界/关系/剧情，只能通过工具调用，没有自由 setter。
2. **单一写入点（D3）**：所有世界变更都汇入 `TurnEngine.submitEffect()` 这一个 apply 点——「一个 apply 点 = 一个审计点 + 一个记录点」。
3. **净变幅限幅（D4）**：防「切香肠」——每角色每回合按**累计净变化**计费（不是单次），`+3` 两次就 `+6`，第二次即被拒。
4. **拒绝即游戏流，不是异常**：超限时工具返回 `[REJECTED] 原因... Continue the scene naturally`，模型在 ReAct 循环里读到失败观察、自我纠正。
5. **恰一轮结算（D5）**：剧情事件是**结算点判定**，不是 EventBroker，且每回合只评估一次——A 的效果置位了 B 的条件，B 本回合不触发、下回合才触发，防「事件风暴」。
6. **走录不重演（D7）**：回放 = 重放日志里记录的 effect/change，**绝不重新调模型**。记录本身就是真相（record IS the truth）。

### 3.4 双层治理分工（D4）

- **GovernedToolExecutor**（Stage 9 链：`PermissionChecker` + `AuditLogger`）决定「这个调用**能不能**发生」（IF）——权限 + 审计。
- **工具 domain 校验**决定「调用**内容**合不合法」（WHAT）——如关系限幅在 `AdjustRelationshipTool` 内部。

两层分离：治理管「准不准」，领域管「对不对」。

### 3.5 三个关键设计要点展开

#### （1）净变幅限幅：为什么是「累计净变化」而不是「单次」

```text
防的是「切香肠」：模型一次 +1，连调 5 次刷 +5
若按单次计费：每次 +1 都 < 5，全部放行 → 一轮 +5
若按累计计费：第 3 次时累计 |+3| 可能已超限 → 拒绝
```

`RelationshipMatrix.apply()` 是唯一写路径：`rollTurnIfNeeded` 回合滚动重置、每角色独立预算、钳位（0-100）在验收后、预算按**请求 delta** 计费（更严格）。

#### （2）恰一轮结算：为什么事件不是 EventBroker

```text
EventBroker（Stage 7）：事件到达 → resume 一个 workflow run（异步、跨 run）
GameEvent（Stage 16）：结算点同步判定 → 应用效果 + 响应角色当场说话（同一 Turn）
```

结算只跑一轮：事件效果置位了另一条规则的条件，那条规则**本回合不触发**、下回合才触发——这是防「事件风暴」的时序保证，由引擎「只调一次 evaluate」兑现。

#### （3）手动引爆：登记不执行

`trigger_event` 工具**绕过条件**（角色的戏剧意图就是授权），但仍守 once 记账。关键纪律是**登记不执行**：事件进 `pendingManualEvents` 队列，结算时统一 fire，绝不 inline 执行——否则事件响应会 `run` 嵌套 `run` 造成递归风暴。事件响应里再登记的事件，顺延到下一回合。

---

## 四、概念与类的映射

### 4.1 按包分层的完整映射

| 领域概念 | 承载类 | 类型 | 职责 |
|---|---|---|---|
| 角色人设卡 | `character/CharacterCard` | record | 纯数据：`characterId/displayName/persona/greeting` |
| 卡→Agent 翻译点 | `character/CharacterAgentFactory` | class | persona→systemPrompt，注册工具，装配 memory context，optional 治理链 |
| 角色记忆白名单 | `character/CharacterMemory` | class | scope 白名单 `[agent:{id}, session:{gameId}]` |
| 世界黑板 | `world/WorldState` | record | 不可变 `turnCount/location/flags`，`apply()` 返回新状态 |
| 世界变更指令 | `world/WorldEffect` | sealed interface | `SetFlag / ClearFlag / SetLocation` 三种可枚举变更 |
| 关系值 | `relation/Relationship` | record | `value 0-100` + `tier()` 六档 + `lastChangedTurn` |
| 关系唯一写路径 | `relation/RelationshipMatrix` | class | 回合累计限幅 + `snapshot/restore` |
| 关系政策 | `relation/RelationshipPolicy` | record | `maxChangePerTurn`（默认 5） |
| 剧情事实 | `event/GameEvent` | record | `eventId/description/respondCharacterId` |
| 触发规则 | `event/EventRule` | record | `condition + event + effects + once` |
| 事件判定器 | `event/EventEvaluator` | class | 结算点同步判定 + once 记账 |
| 判定事实快照 | `event/GameFacts` | record | 冻结的 `world + relationships + turnNo` |
| 回合（回放数据单元） | `turn/Turn` | record | playerInput + responses + appliedEffects + relationshipChanges + triggeredEventIds |
| 回合管线 | `turn/TurnEngine` | class | 路由→注入→运行→结算→日志 |
| 只增账本 | `turn/TurnLog` | class | append-only，结算后不可改 |
| 回合结果 | `turn/TurnResult` | sealed interface | `Completed / RoutingMiss` |
| 存档快照 | `replay/SaveGame` | record | 整局域状态（世界+关系+每角色对话史+事件记账） |
| 存档读写 | `replay/GameStore` | class | `save.json` + `turn-log.jsonl` |
| 回放视图 | `replay/GameReplay` / `GameReplayer` | class | 走录不重演 |
| 唯一编解码契约 | `replay/ReplayCodec` | class | 格式只有这一处权威定义 |
| 装配门面 | `TavernGame` + `Builder` | class | 一局游戏所有零件组装（M16.5） |
| 三个「把手」工具 | `SetWorldFlagTool` / `AdjustRelationshipTool` / `TriggerEventTool` | class | 角色影响世界/关系/剧情的唯一入口 |

### 4.2 一张「概念→类」心智图

```text
Profile(领域语义)
 ├─ 角色：CharacterCard ──(CharacterAgentFactory)──▶ Agent（Runtime 机制）
 │                          └─ 记忆：CharacterMemory → [agent:{id}, session:{gameId}]
 ├─ 世界：WorldState  ←── WorldEffect（指令）←── SetWorldFlagTool
 ├─ 关系：Relationship ←── RelationshipMatrix（限幅）←── AdjustRelationshipTool
 ├─ 剧情：GameEvent  ←── EventRule ←── EventEvaluator ←── TriggerEventTool
 ├─ 回合：Turn（回放单元）←── TurnEngine（管线）←── TurnLog（账本）
 └─ 存档：SaveGame ←── GameStore ←── ReplayCodec（契约）──▶ GameReplay（重放）
```

### 4.3 映射一句话总结

> **record 是「值」（不可变数据），class 是「行为」（矩阵/判定器/引擎/工厂/工具），sealed 是「有限的合法结果集」。**

---

## 五、数据流向

### 5.1 运行时：一个回合的完整数据流

```text
玩家输入 "@marcus 来一杯蜂蜜酒"
        │
        ▼
TurnEngine.playTurn()
  ① resolveMention() ── 找不到角色 → 返回 RoutingMiss（不调模型、不推进回合、不记日志）
        │ 找到 marcus
        ▼
  ② world = world.nextTurn()          # 世界推进一回合，清空本回合三个批次列表
        │
        ▼
  ③ injectContext()：拼 "[world] Turn 5 · tavern-hall" + "[relationship] affection 52 (NEUTRAL)" + "[player] …"
        │ （便利贴技术，同 Stage 12 的 [from userId]）
        ▼
  ④ agent.run(input, state)           # ReAct 循环，角色可能调工具：
        │
        ├─ set_world_flag      → SetWorldFlagTool → submitEffect → world.apply(effect) + turnEffects.add
        ├─ adjust_relationship → AdjustRelationshipTool → matrix.apply() → appliedSink → turnRelationshipChanges.add
        └─ trigger_event       → TriggerEventTool → queueManualEvent → pendingManualEvents.add（登记不执行！）
        │
        ▼
  ⑤ 事件结算（恰一轮，只一次）：
     batch = evaluate(currentFacts()) + pendingManualEvents
     for each TriggeredEvent:
        fireEvent() → submitEffect(事件携带的 effects) + respondCharacter.run("[event] …")（同回合追加响应）
        │（事件响应里再 trigger 的 → 顺延到下一回合，杜绝 run 嵌套 run）
        ▼
  ⑥ 结算：new Turn(...) → turnLog.append(turn) → 返回 TurnResult.Completed(turn)
```

**三个关键数据落点**（回合内批次列表 → 最终沉淀进 `Turn`）：

```text
turnEffects            → Turn.appliedEffects
turnRelationshipChanges → Turn.relationshipChanges
triggeredEventIds       → Turn.triggeredEventIds
```

这三样就是回放的「原料」。

### 5.2 三个工具都是「提交器模式」（submitter pattern）

工具**不持有引擎、不直接改状态**，只持「领域对象 + sink」，把变更提交给引擎的单一 apply 点：

```text
SetWorldFlagTool      : 持 Consumer<WorldEffect>              → 提交给 submitEffect
AdjustRelationshipTool: 持 RelationshipMatrix + IntSupplier    → 提交给 appliedSink
TriggerEventTool      : 持 EventEvaluator + Consumer<TriggeredEvent> → 提交给 queueManualEvent
```

好处：工具可独立用收集 sink 测试，回放安全由构造保证。

### 5.3 持久化：存档与回放的数据流

**存档（save）**——`TavernGame.save()` → `GameStore.save(engine)`：

```text
TurnEngine
 ├─ world ─────────────────────┐
 ├─ relationships.snapshot() ──┤
 ├─ characterHistories()  ─────┼──▶ save.json        （整局快照：恢复用）
 └─ eventEvaluator.firedIds() ─┘      └ {world, relationships, character_histories, fired_event_ids}
 │
 ├─ initialWorld ──────────────┐
 ├─ initialRelationships ──────┼──▶ turn-log.jsonl   （历史账本：回放用）
 └─ turnLog.turns() ───────────┘      └ 第1行 initial 信封 + 每回合一行（字节稳定，只增不改）
```

**两个文件、两个用途**：

```text
save.json      = 快速回到游戏的快照
turn-log.jsonl = 可审查的历史
```

两者一致性可校验：`replay.finalState() == save.world`。

**读档（load）**——`TavernGame.Builder.load()`：

```text
loadSave(gameId) ──▶ SaveGame ──▶ matrix.restore / evaluator.restore / restoreHistories
loadReplay(gameId) ─▶ GameReplay ─▶ previous.initialWorld + previous.initialRelationships + previous.turns
      │
      ▼
TurnEngine.resume(… previous …, currentWorld, previousTurns, matrix, evaluator)
      └─ 引擎携带「整局历史」：日志从第 1 回合连续，续玩后 save() 仍写连续日志
```

**回放（replay）**——`GameReplay.stateAt(n)`：

```text
initialWorld / initialRelationships（日志第 1 行的信封）
        │ 从 i=0 到 n 逐步重放：
        ├─ world = world.nextTurn()
        ├─ world = world.apply(每条 recorded effect)         # 纯数据重推，不调模型
        └─ relationships.put(change.after())                  # 不重跑规则
        ▼
ReplaySnapshot(world, relationships)   # 第 n 回合结束时的世界+关系
```

### 5.4 三种「重放/恢复」的分工（永不合并）

```text
Checkpoint（Stage 6） : 恢复一次 workflow run 的暂停态（cursor + blackboard，按 runId）
Trajectory（Stage 14）: 重放模型决策供 RL 训练（模型 step 粒度）
GameReplay（Stage 16）: 重放叙事历史供审查（回合粒度）
```

三者的数据单元不同、消费者不同，保持平行、不互相污染。

---

## 六、学习顺序建议（按依赖顺序读）

1. **`WorldState` + `WorldEffect`** —— 先理解「不可变值 + 指令」模式，这是全项目的地基。
2. **`Relationship` + `RelationshipPolicy` + `RelationshipMatrix`** —— 理解「限幅唯一写路径」。
3. **`CharacterCard` + `CharacterMemory` + `CharacterAgentFactory`** —— 理解「角色 = Agent」翻译点。
4. **`TurnEngine`**（重点）+ 三个工具 —— 理解管线与「提交器模式」。
5. **`EventRule` + `EventEvaluator` + `GameEvent` + `GameFacts`** —— 理解「恰一轮结算」。
6. **`Turn` + `TurnLog` + `GameStore` + `ReplayCodec` + `GameReplay`** —— 理解「走录不重演」。
7. **`TavernGame`** —— 最后看门面，看它如何把上面全部组装成一局游戏。

每步都问自己一句：**「这个东西属于机制（Runtime）还是领域（Tavern）？」**——这是贯穿整个 Stage 16 的唯一主线。

---

## 七、复盘速答

### Q1：Stage 16 解决什么问题？

> 验证「同一个 Runtime 能否零存量改动复用到一个全新领域」。Stage 15 证明了企业域（身份/边界/审计/成本），Stage 16 证明了游戏域（角色/世界/关系/剧情/回放）。它把 Agent 从「能办事」推进到「能扮演一个有状态、有记忆、会发展关系的角色」。

### Q2：为什么「角色 = Agent」而不新写一个 Character 引擎？

> 因为 Agent 已经具备 persona 化的全部机制：systemPrompt（人设）、Tool（影响世界）、Memory（记忆）、GovernedToolExecutor（治理）。`CharacterAgentFactory` 只是把 `CharacterCard` 翻译成 `AgentConfig`，是装配，不是新机制——这正是 D1「零改动」的实证。

### Q3：关系好感为什么要有「净变幅限幅」？

> 防「切香肠」式刷好感：模型可以一次 `+1` 连调多次。按累计净变化计费（而非单次），从机制上杜绝「AI 酒保一轮坠入爱河」。超限不是异常，而是 `[REJECTED]` 文本，模型读到后自愈。

### Q4：剧情事件为什么不用 EventBroker？

> EventBroker 是异步、跨 run 的「机制」，而剧情事件是「结算点判定」的**领域事实**。同步结算 + 恰一轮 + 响应角色当场说话，才能保证「本回合发生的事本回合收尾」，且不会因事件链式触发造成风暴。

### Q5：回放为什么不重新调模型？

> 因为模型调用不可复现（温度、随机性），而「记录」是可复现的真相。回放 = 重放日志里记录的 effect/change，纯数据重推。这保证回看的叙事永远和历史一致（`finalState == save.world`）。

### Q6：`save.json` 和 `turn-log.jsonl` 为什么分成两个文件？

> 快照是「快速回到游戏」的捷径，账本是「可审查历史」的记录。前者为 load 优化，后者为 replay 服务。且账本字节稳定（只增不改），每次 save 都是前一次的字节前缀扩展。

### Q7：Stage 16 最重要的设计思想是什么？

> **Profile 在机制之上叠加领域语义：角色是 persona 化的 Agent，影响是工具，状态是一等值，回放走录不重演。**

---

## 八、最终记忆句

```text
Runtime 提供机制，Profile 提供领域语义：
角色 = persona-ized Agent
影响 = 工具调用（单一写入点 + 双层治理）
状态 = 一等不可变值（World / Relationship / GameEvent）
回放 = 走录不重演（记录就是真相）
```

> **Stage 16 证明：一个稳定的 Runtime 抽象，换一套领域语义、零存量改动，就能从「企业办事」切换到「酒馆演戏」。**
