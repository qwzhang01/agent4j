# 一回合对话如何驱动游戏状态变化

> 配套蓝图：[architecture-stage-16.md](architecture-stage-16.md) D4 / D8 / §5（影响即工具，回合是顺序代码，一回合数据流）· 对应实现：`turn/TurnEngine.java`、`Turn.java`、`TurnResult.java`，三个游戏工具
> 上一篇：[stage-16-article-2-persona-world-memory.md](stage-16-article-2-persona-world-memory.md)
> 状态：✅ Stage 16 已完成
> 这是 Stage 16 系列的第 3 篇：对话不是终点，回合管线才是因果环。

---

## 1. 我今天要解决什么问题

人格、世界、记忆分层之后，还差一口气：玩家说一句话，这三层里哪些会动、按什么顺序动、谁有权动。Chat Loop 的默认答案是「模型吐出文本，对话结束」。游戏的答案必须是另一句：

> 台词是因果环的一环，不是终点。

这一篇只讲这一环怎么转：`TavernGame.playerSay(text)` 进去，`TurnResult` 出来，中间世界、关系、事件批次如何被工具提交、被引擎 apply、被 `Turn` 落账。

---

## 2. 为什么会有这个认知冲突

影响假设是 Chat Loop 五条破裂里最隐蔽的一条。企业场景里 Agent 的输出就是答案；频道场景里输出就是回复。游戏里骂酒保不只多一条消息——好感掉点、「闹事」flag 置位、若干回合后守卫介入。如果把「改变世界」做成模型在台词里声称「我把旗子立了」，世界其实没动；如果做成工具直接 `world.flags.put`，回放和审计都会丢。

冲突在于：**模型可以决定「想改什么」，但不能决定「怎么落地」。** 想改，是 ReAct 循环里的工具调用；落地，是引擎的唯一 apply 点。把这两步合成一步，领域状态就变成模型的私有副作用，无法治理、无法限幅、无法走录。

另一个冲突是编排幻觉：管线有四步，看起来像图。但四步是固定顺序、无分支、无并行。用 `GraphRuntime` 包一层，每回合一个 `runId`、一份 checkpoint、一次黑板进出，换不来任何收益。D8 的裁决：回合是引擎顺序代码，不是 Workflow 图。

---

## 3. 它解决了什么问题

`TurnEngine.playTurn` 把一回合收成五步，每一步都有失败语义：

```text
1. mention 路由     未命中 → RoutingMiss（不烧模型、不推进、不落账）
2. 世界推进 + 注入  nextTurn + [world]/[relationship]/[player] 便签
3. 角色 run         held AgentState 续跑；工具只提交指令
4. 事件结算         恰一轮：规则评估 + 排队列队的手动引爆
5. Turn 落账        append-only，回放原料就此冻结
```

对话→状态的桥是三个普通 Tool：`adjust_relationship` / `set_world_flag` / `trigger_event`。模型视角里，世界就是另一个外部系统，和查天气没有区别。框架视角里，三个工具都是提交器：不持引擎、不直接改状态，把变更交给 sink。

治理在外层（`GovernedToolExecutor`：权限 AUTO + 全量审计），数值边界在工具内（关系净变幅）。两层分工：治理管「准不准」，领域管「对不对」。

---

## 4. 核心抽象和架构

### 4.1 路由：确定性优先于聪明

`resolveMention` 用正则 `@([A-Za-z0-9_-]+)` 取第一个命中已注册角色的 token。`@marcus,` 能命中，`@marcusville` 不会误伤 `marcus`——和 Stage 12 `ChannelMessage.autoDetect` 同一套分隔符边界。未命中返回：

```text
TurnResult.RoutingMiss(input, "No character mentioned. Address someone with @name.", availableCharacters)
```

`TurnEngineTest.mentionRoutingMissWithoutAt` / `mentionRoutingMissUnknownCharacter` 三条断言绑在一起：模型零调用、回合数零推进、日志零追加。v1 不做群聊、不做导演分派。确定性路由是世界层能被回放的前提——「谁在这一回合说话」必须是记录，不能是模型猜的。

### 4.2 便签注入：世界进入 prompt，但不进入人格

`injectContext` 拼三行：

```text
[world] Turn 2 · great-hall
[relationship] affection 53 (NEUTRAL)
[player] @marcus Keep one for yourself, you've earned it.
```

手法同 Stage 12 的 `[from userId]` / `[handoff]`：ambient 事实是前缀，不是 systemPrompt 重写。`TurnEngineTest.worldStickyNoteInjection` 断言请求以 `[world]` 开头、以 `[player]` 原文结尾；`relationshipStickyNoteInjection` 用 stub describer 锁格式。M16.3 之后 describer 默认从矩阵派生：`relationships.view(id).describe()`。没有矩阵、没有 describer，就不写 `[relationship]` 行——机制先立，真数据后接。

### 4.3 提交器模式：工具产出指令

三个工具的构造依赖说明了一切：

```text
SetWorldFlagTool        Consumer<WorldEffect>                         → submitEffect
AdjustRelationshipTool  RelationshipMatrix + IntSupplier + appliedSink → submitRelationshipChange
TriggerEventTool        EventEvaluator + pendingSink                   → queueManualEvent
```

`SetWorldFlagTool.execute` 校验 key/value，构造 `WorldEffect.SetFlag`，`effectSink.accept(effect)`，返回确认文本。它从不拿到 `WorldState`。`TurnEngine.submitEffect` 才是唯一 apply+记录点：

```java
this.world = this.world.apply(effect);
this.turnEffects.add(effect);
```

事件随行 effects 也走这一口。回放安全由构造保证：能进 `Turn.appliedEffects` 的，一定经过 apply。

`trigger_event` 更严：登记不执行。`TriggerEventTool` 调 `evaluator.triggerManually`，成功则推进 `pendingManualEvents`，返回「consequences unfold at the end of this turn」。内联 fire 会在 `run` 里再 `run`，递归风暴。事件响应里再登记的手动事件，顺延下一回合结算。

### 4.4 Turn 是回放数据单元

`Turn` 一次立满字段：`turnNo` / `playerInput` / `speakingCharacterId` / `responses` / `appliedEffects` / `relationshipChanges` / `triggeredEventIds` / `timestamp`。M16.2 就把 `triggeredEventIds` 预立为空列表，避免下一里程碑改 record 签名——字段位一次立满，是回放兼容性的小纪律。`CharacterResponse.eventDriven` 区分主响应和事件响应。`RelationshipChange` 记录 `delta/before/after`——蓝图初稿只有 appliedEffects，D7 要重演关系，M16.3 补上这个字段。被拒的关系调整不进 Turn：没发生的事不是历史。

`Turn.WorldEffectEntry` 把 effect 包一层，而不是直接存 `WorldEffect`。javadoc 写明用意：以后要加 provenance（哪次工具调用、哪条事件产出）时，不用改 Turn 的外层形状。这是 D4 风格的字段稳定性——回放器今天只读 `entry.effect()`，明天多一个来源字段也不炸。

`TurnLog.append` 只增。`TurnLogTest.viewIsUnmodifiable` 锁住结算后不可改。JSONL 字节稳定留到 `GameStore`。引擎对外的 `playTurn(String)` 也值得记一笔：蓝图草图写过 `playTurn(TavernGame, String)`，实现改成引擎内持局状态，门面 `TavernGame.playerSay` 只做转调。管线逻辑不变，签名更诚实——M16.2 还没有门面时，引擎必须自己能跑完测试。

---

## 5. 一次完整数据流

把 `TavernGameExample` T2-T4 展开成引擎视角：

```text
T2  playerSay("@marcus Keep one for yourself, you've earned it.")
    resolveMention → marcus
    world = nextTurn()          // Turn 2
    injectContext → [world][relationship][player]
    marcus.run(...)
      respondToolCalls(adjust_relationship, {characterId:marcus, delta:3})
        → matrix.apply → Applied(50→53)
        → appliedSink → turnRelationshipChanges
      respondText("You seem the decent sort. Mead, coming right up.")
    evaluate(facts)：confession 要 ≥80，未触发
    落账：RelationshipChange(marcus, +3, 50, 53)

T3  "@lyra That song earlier was genuinely lovely."
    路由 lyra；marcus AgentState 不动
    lyra 调 set_world_flag(bard-mood, lively)
      → submitEffect → world.apply(SetFlag)
      → describe() 变成 "Turn 3 · great-hall · bard-mood=lively"
    结算：improvisation 要 turn≥4，未触发

T4  "@brawn Quiet corner tonight?"
    brawn 只回话，不调工具
    结算：flag=lively ∧ turn≥4 → improvisation
      → submitEffect(SetFlag(crowd, cheering))
      → lyra.run("[event] Lyra strikes up an unannounced improvisation.")
      → CharacterResponse(lyra, ..., eventDriven=true)
    玩家看见双重输出：世界活过来了
```

治理链在工具外：`TavernGame.Builder.governance(auditLogger)` 一行挂上 `GovernedToolExecutor` + `ToolPolicy(AUTO)` + 审计。`TurnEngineM16_3Test.governanceChainAuditsGameTools` 断言 `adjust_relationship` / `set_world_flag` 的 `EXECUTED` 记录和 args 可见。审计流水就是 GM 后台。

---

## 6. 最小代码或实验

`TurnEngineTest.toolChangesWorld` 是「对话驱动状态」的最小闭环：scripted `respondToolCalls(set_world_flag)` → 断言 `world.flag` 命中、`describe()` 可读、`turn.appliedEffects` 有记录、角色终答仍在。四件事同时真，才说明因果环闭合——不是模型嘴上说改了。

路由两态是另一条最小实验：`mentionRoutingHit` 走完管线；miss 两态（无 `@`、未知 `@`）保证失败不产生副作用。世界层的可回放性从这里开始：没发生的回合不占 turnNo。

限幅自愈在下一篇展开，但管线已经为它留好位置：工具返回文本观察，不抛异常炸穿 loop。`AdjustRelationshipTool` 超幅时返回 `"[REJECTED] ... Continue the scene naturally instead of forcing the change."`——Stage 2 的工具错误契约，在游戏域变成戏剧节奏。

`TurnEngineTest.turnFieldsComplete` 和 `turnAdvances` 把「落账完整」和「回合真的在走」拆开锁：第一回合字段齐，第二回合 `turnNo == 2` 且世界 `turnCount` 同步。`engineLogAppendOnly` 再锁日志视图不可变。这三条看起来像流水账，缺一条就会出现「世界走了、Turn 没记」或「Turn 记了、世界没走」——回放器两边对不上时，最先该翻的就是这两份测试。

---

## 7. 常见误区

1. **「让模型在台词里宣布状态变化」** —— 台词不是状态。没有工具调用，世界不动，回放也看不见。
2. **「工具直接持有 WorldState 并 apply」** —— 多个 apply 点 = 多个漏记点。回放只能重演被记录的指令；没记录的 apply 是幽灵。
3. **「用 GraphRuntime 编排回合」** —— 固定顺序无分支。图引擎的机制税（runId / checkpoint / 黑板）换零收益。群聊并行响应才是 ParallelNode 的真需求，那是 v2。
4. **「路由失败也推进回合」** —— 玩家打错名字不该让时间流逝，更不该留下空 Turn。`RoutingMiss` 是领域结果，不是异常。
5. **「事件在工具里当场 fire」** —— 嵌套 `run` 是风暴。登记到结算点，才是「本回合发生的事本回合收尾，但不递归」。

---

## 8. 和相邻概念的区别

```text
TurnEngine.playTurn     领域管线，顺序代码，索引是 gameId + turnNo
GraphRuntime            机制图，节点/边/checkpoint，索引是 runId
ReActAgentLoop          单角色一次 run 内部的模型-工具循环
                        它是管线第 3 步，不是整局游戏

Channel @ 检测          Stage 12 思想复现：mention 决定谁说话
                        不引入 agent-channel 依赖（D9）

GovernedToolExecutor    IF：这个调用能不能发生（权限 + 审计）
工具 domain 校验        WHAT：调用内容合不合法（限幅、参数、once）
```

和 Stage 15 的对照：企业长任务在一个点接 workflow（`EnterpriseTaskManager`），游戏回合全程不接。Profile 层「哪里需要哪里接」，不是「一切都要过图引擎」。依赖正交在这里再次出现：游戏不用企业的任务恢复，企业不用游戏的回合管线。

---

## 9. 我的设计判断

管线设计里最值钱的不是「四步很完整」，而是**失败被做成了类型**。`TurnResult` sealed 两态，路由失败零副作用；`ApplyResult` sealed 两态，超幅是游戏流；`trigger_event` 未知或已耗尽返回 `[REJECTED]` 文本。模型、玩家、GM 看到的都是领域结果，不是堆栈。

第二条判断：提交器模式是世界层能被调试的前提。工具可独立用收集 sink 单测（`SetWorldFlagToolTest` / `AdjustRelationshipToolTest` / `EventEvaluatorTest.toolQueuesAndReports`），引擎可独立断言 apply 与落账。没有这个缝，端到端测试只能「看起来像」，不能指认是工具错了还是引擎错了。

第三条：便签格式是兼容性表面。`[world]` / `[relationship]` / `[player]` 前缀被测试锁住，因为改格式等于回放与 prompt 契约同时破。ambient 事实用前缀，不用改人格，也不用改卡。

构造函数的演进也说明管线是长出来的，不是一次画完的。M16.2 只有 `cards + world`，关系 describer 可空；M16.3 接上 matrix 与 evaluator，默认 describer 从矩阵派生；M16.5 再拆 `resume`，把日志起点和当前世界分开。每次加法都落在同一条 `playTurn` 上，没有分出第二条管线。这就是「顺序代码」的好处：演进是加步骤和加依赖，不是加图节点。`emptyRosterRejected` 与 `nullInputRejected` 守住入口——没有角色的局不是局，null 输入不是 RoutingMiss，是编程错误。领域结果和程序员错误要分开，前者给玩家看，后者该在装配期炸。

---

## 10. 面试表达

> 「游戏里一回合对话能改世界，是因为影响被建模成工具，落地被收成引擎的唯一 apply 点。TurnEngine 是固定顺序代码：mention 路由、便签注入、角色 run、恰一轮事件结算、Turn 落账——不是 Workflow 图。三个游戏工具只提交指令或排队事件，从不自己改 WorldState。路由失败返回 RoutingMiss，不烧模型、不推进回合。治理链管准不准，工具内限幅管对不对。对话是因果环的一环，Turn 记录才是这一环留下的真相。」

---

## 11. 下一篇连接什么

管线已经能改世界和关系。下一篇把两块最容易失控的领域规则展开：事件系统为什么不是 EventBroker，关系系统为什么按净变幅限幅。一句台词触发连锁剧情、AI 酒保一回合爱上玩家，都是真实的设计事故，也是规则层要防的东西。

→ [stage-16-article-4-event-and-relationship.md](stage-16-article-4-event-and-relationship.md)
