# 如何让游戏 Agent 行为可回放、可调试

> 配套蓝图：[architecture-stage-16.md](architecture-stage-16.md) D6 / D7（存档是局快照，回放走录不重演）· 对应实现：`replay/SaveGame.java`、`GameStore.java`、`GameReplayer.java`、`GameReplay.java`、`ReplayCodec.java`
> 上一篇：[stage-16-article-4-event-and-relationship.md](stage-16-article-4-event-and-relationship.md)
> 状态：✅ Stage 16 已完成
> 这是 Stage 16 系列的第 5 篇：调试格式、训练格式、复盘格式各管各的，游戏走第三条。

---

## 1. 我今天要解决什么问题

前面四篇把一局酒馆变成了可写的世界：人格、黑板、回合管线、关系限幅、事件结算。如果这些变化聊完即弃，世界层仍然是半成品——玩家不能续局，作者不能复盘，GM 不能指着某一拍说「这里 flag 立了」。Chat Loop 的重复假设是「跑完就跑完了」。游戏要求相反：

```text
存档   一局游戏的全套领域状态，能原样拿回来继续玩
回放   按回合重演状态变化流，不重跑模型
调试   坏文件 fail-loud，审计流水能回答「世界被谁改过」
```

这一篇讲这三件事如何落成两个文件、一个走录器、一条治理审计，以及为什么它们都不是 Stage 6 的 checkpoint，也不是 Stage 14 的 trajectory。

---

## 2. 为什么会有这个认知冲突

「能存」看起来像 `RunManager` + `CheckpointStore`。形似：都有快照、都能恢复。神异：checkpoint 载荷是 cursor + `WorkflowState`，索引是 `runId`，语义是单次 workflow run 暂停恢复。游戏存档载荷是 `WorldState` + `RelationshipMatrix` + 全部角色 `AgentState` + 已触发事件集 + TurnLog，索引是 `gameId`。游戏回合不产生 workflow run。硬复用只能把领域状态塞进黑板并伪造 run 生命周期——机制税换零收益。这是 D6。

「能回放」看起来像 Stage 14 的 `TrajectoryReplayer`。纪律同源：append-only JSONL、信封区分行种类、加载时完整性校验、走录不重演。单位不同：trajectory 是模型 step（S-A-O-R-D），消费者是训练；GameReplay 是 game turn（叙事 + 效果），消费者是复盘。平行不合并。这是 D7。

还有第三种诱惑：回放时再调一次模型。温度和采样会给出另一套台词，另一套工具调用，另一套世界。那不是回放，是重开。游戏复盘要的是「当时发生了什么」，不是「现在模型觉得当时该发生什么」。

---

## 3. 它解决了什么问题

双文件拆开两条路径：

```text
save.json        快照：快速回到游戏（load 用）
turn-log.jsonl   账本：可审查的历史（replay 用）
互查             replay.finalState().world() == save.world()
```

`GameStore` 目录布局：`{root}/{gameId}/save.json` + `turn-log.jsonl`。日志第 1 行是 `kind=initial` 信封（开局世界 + 开局关系），随后每行一个 `kind=turn`。每次 save 全量写，但字节在追加下稳定：后一次是前一次的前缀延长，已写行永不变——`GameStoreTest.writtenBytesStableUnderAppend` 逐字节断言。

`GameReplay.stateAt(n)` 从 initial 走录到第 n 回合结束：`nextTurn` + `apply(recorded effects)` + 写入 `RelationshipChange.after`。零模型调用，零规则重评。记录即真相。

调试三件套：完整性校验带行号（缺 initial / turnNo 跳号 / 坏 JSON）、多模态历史 fail-loud、治理审计 `EXECUTED` + args。GM 后台和复盘器看的是同一批已经发生的事。

---

## 4. 核心抽象和架构

### 4.1 SaveGame：全套领域状态

```java
public record SaveGame(
    String gameId,
    WorldState world,
    Map<String, Relationship> relationships,
    Map<String, List<ChatMessage>> characterHistories,
    Set<String> firedEventIds)
```

对照 Stage 6 checkpoint：没有 cursor，没有 runId，没有 WorkflowState。`characterHistories` 复用的是 Stage 2 就为序列化预留的 `AgentState` 消息拷贝——真正的免费点在这里，不在 RunManager。`firedEventIds` 让 once 事件重载后不会再触发：`GameStoreTest.onceBookkeepingSurvivesRestore`。

### 4.2 三处 restore：系统操作，绕限幅

load 不是把快照扔进正在跑的回合。它走专用入口：

```text
RelationshipMatrix.restore(map)   清预算、绕限幅
EventEvaluator.restore(set)       续接 once 簿记
TurnEngine.restoreHistories(...)  注入各角色消息，未知 id → IAE
TurnEngine.resume(...)            拆开 game-initial 与 current-world
```

`restore` 绕限幅是语义分层：限幅约束模型的回合动作，不约束系统把存档装回去。`resume` 工厂是 M16.5 被示例抓出来的真缺口——初版把存档终态当成日志起点，续玩后再 save，校验报 `"line 2: expected turnNo 1, got 6"`。fail-loud 反过来抓住了装配错误。正确语义：

```text
日志第 1 行     真·开局（previous.initialWorld / initialRelationships）
save.json       当前终态（继续玩的世界）
turn-log 全文   已结算回合预填进 TurnLog
续局后再 save   仍然从 turn 1 起连续
```

「存档终态」和「日志起点」是两个角色，续局必须分别携带。

### 4.3 ReplayCodec：格式只有一处权威

`ReplayCodec` 包内可见，手写 JSON 树，零注解魔法。`GameStore` 写、`GameReplayer` 读，都走它。信封：

```text
initial  {"kind":"initial", world{}, relationships{}}
turn     {"kind":"turn", turnNo, playerInput, speakingCharacterId,
          responses[], appliedEffects[], relationshipChanges[],
          triggeredEventIds[], timestamp}
effect   {type:SetFlag|ClearFlag|SetLocation, ...}
```

v1 诚实边界：消息带多模态 parts 存档即 IAE，不静默丢——`GameStoreTest.multimodalHistoryFailsLoud` 锁住。tool 消息的 `toolCallId` / `name` 完整保留，重建后配对不断；`roundTripSaveEquality` 的断言里专门含工具调用消息。缺了这对字段，续局时模型会看见一条「无主」的 tool 结果，ReAct 对不上。这是 Stage 14 `TrajectoryCodec` 纪律的领域层兑现：格式是契约，契约不能散落在两个类里各写各的。

`GameStore.exists(gameId)` 只看 `save.json` 是否为常规文件。缺档 `loadSave` / `loadReplay` 抛 `NoSuchFileException`，消息带 gameId，不猜不造——蓝图 F5。门面再加一层：没配 `storeRoot` 时 `save()` / `replayFromDisk()` 抛 `IllegalStateException`（`TavernGameTest.saveWithoutStoreRootRejected`）。能玩的局不一定能存，这是装配选择，不是默认吞掉。

### 4.4 GameReplayer / GameReplay：走录视图

`GameReplayer.load(path)` 做完整性三态，失败带行号：

```text
空文件           IAE：first line must be the initial envelope
首行非 initial   IAE：line 1: expected the initial envelope
kind 非 turn     IAE：line n: expected kind='turn'
turnNo 不连续    IAE：line 3: expected turnNo 2, got ...
坏 JSON          IAE：line n: not valid JSON
```

`GameReplay.stateAt(0)` 是开局；`stateAt(n)` 是第 n 回合结束；`finalState()` 等于 `stateAt(turnCount)`。`ReplaySnapshot.relationship(id)` 默认 50，与 `GameFacts` / `Matrix.view` 三处统一——实现期初版裸 `map.get("marcus")` 在 t0 NPE，因为初始快照只含被改过的角色。领域默认值必须跟随该类型的每一个读取视图。`minimalLogLoads` 锁最小合法日志：一行 initial 也能加载，`turnCount == 0`，`stateAt(0)` 等于信封。空局也是可回放的历史，不是错误。`describeTurn` 人读：谁说了什么、world 改了什么、关系动了多少、触发了什么事件。

`TavernGame.replay()` 是当前实例内存视图；`replayFromDisk()` 是跨会话完整历史。重载后的内存 replay 只覆盖 load 之后的新回合时，磁盘视图仍能覆盖全剧——所以示例 T7 走 `replayFromDisk()`。

### 4.5 审计 = GM 后台

回放回答「世界变成了什么」；审计回答「哪一次工具调用导致的」。`InMemoryAuditLogger.getByTool("adjust_relationship")` 能看到 `EXECUTED` 和 args 里的 `characterId`。示例结尾打印 `audited tool calls` 全 `EXECUTED`。调试时两条线要一起看：Turn 说 +3 发生了，审计说这次调用被放行；Turn 没记 +10，审计里可能仍有这次调用，工具结果是拒绝文本。记录态和调用态分开，才能查「模型想做什么」和「世界接受了什么」。

---

## 5. 一次完整数据流

示例 T6-T7：

```text
T6  game.save()
      {saveRoot}/golden-oak/save.json
      {saveRoot}/golden-oak/turn-log.jsonl
    TavernGame.builder()...load()
      loadSave → matrix.restore / evaluator.restore
      loadReplay → initial + previous turns
      TurnEngine.resume(..., save.world(), previous.turns(), ...)
      restoreHistories
    playerSay("@lyra Remember me?")
      turnNo 接续为 6；lyra 的历史对模型可见（记得 T3 的歌）
    reloaded.save()     // 日志仍从 turn 1 连续，多一行 turn 6

T7  replayFromDisk()
    describeTurn(1..6)
    stateAt(3).world 含 bard-mood=lively
    stateAt(3).relationship("marcus") 为 T2 之后的 53
    finalState.world.equals(reloaded.world()) → YES
```

`GameStoreTest.restoredGameContinuesConversations` 锁住 T6：重载后 turnNo 接续、恢复的历史对模型可见、恢复的关系进 `[relationship]` 便签、限幅预算按新回合刷新。`GameReplayerTest.stateAtTimePoints` 锁住 T7 时点：t0 空世界 50 关系 / t1 flag+关系 / 累加过程 / 终态。`finalStateMatchesSave` 锁住两文件互查。

---

## 6. 最小代码或实验

走录的最小实验就在 `GameReplay.stateAt`：

```java
WorldState world = initialWorld;
Map<String, Relationship> relationships = new HashMap<>(initialRelationships);
for (int i = 0; i < turnNo; i++) {
    Turn t = turns.get(i);
    world = world.nextTurn();
    for (Turn.WorldEffectEntry entry : t.appliedEffects()) {
        world = world.apply(entry.effect());
    }
    for (Turn.RelationshipChange change : t.relationshipChanges()) {
        relationships.put(change.characterId(),
                new Relationship(change.after(), t.turnNo()));
    }
}
```

没有 `agent.run`，没有 `evaluate`，没有 `matrix.apply`。关系直接写入 `after`——因为限幅已经在当时的回合里裁决过了，回放重审限额会把「当时被拒的世界」变成「现在政策下的世界」。

坏文件实验三态：`missingInitialRejected` / `turnGapRejectedWithLineNumber` / `badLineRejectedWithLineNumber`。对齐 Stage 14 fail-loud。调试游戏 Agent，第一反应不该是「再跑一遍示例」，而该是「打开 turn-log.jsonl，从 initial 走到出事的 turnNo」。

---

## 7. 常见误区

1. **「回放 = 重跑模型」** —— 不可复现。回放重演状态变化流。
2. **「存档 = checkpoint」** —— 索引、载荷、寿命都不同。形似神异。
3. **「一个文件就够」** —— 快照优化 load，账本服务 replay。混在一起，要么续局丢历史，要么回放必须反解快照。
4. **「续局用终态当 initial」** —— M16.5 实录事故。完整性校验会在下一次 replay 时报 turnNo 跳号。
5. **「回放再走一遍 EventEvaluator」** —— 规则若被改过，历史会被改写。记录里的 `triggeredEventIds` 和 effects 才是当时的真相。
6. **「静默丢掉不能序列化的消息」** —— 多模态 parts fail-loud。丢了就是假存档。

---

## 8. 和相邻概念的区别

三层回放，蓝图 §1 写死，本篇收官：

```text
Checkpoint（Stage 6）    从某点继续
                         单位：node 边界；面向恢复；索引 runId
                         载荷：cursor + 黑板快照

Trajectory（Stage 14）   重演模型决策
                         单位：model step；面向训练；S-A-O-R-D
                         走录不重演，JSONL + 信封

GameReplay（Stage 16）   重演叙事历史
                         单位：game turn；面向复盘；世界+关系流
                         走录不重演，JSONL + 信封
```

共同纪律：append-only、kind 信封、加载校验、记录即真相。不共同的东西：不要合并成一个 Replay 接口。消费者不同，字段不同，重演函数不同。可选彩蛋（不承诺）：用 Stage 14 `RecordingAgent` 包角色 Agent，同一局同时产出模型轨迹——「游戏行为也是 RL 数据」，v2 再验证。

和 Stage 15 仍正交：企业任务恢复走 checkpoint / workflow resume；游戏续局走 `SaveGame` + `TurnEngine.resume`。两套恢复不共享索引。

---

## 9. 我的设计判断

可调试性不是加日志，是**让状态变化成为一等值**。前面的 `WorldEffect`、`RelationshipChange`、`triggeredEventIds` 若只活在内存里，这一篇写不成。回放器没有自己的领域逻辑，它只是把已经是值的变化再 apply 一遍。如果某次改世界没有变成值，回放器无法发明它。

第二条判断来自 M16.5 的装配事故：校验不是给「外部坏文件」准备的装饰，它也会抓住自己人。设计图上 save/load 很顺，装配时「终态」和「起点」被揉成一个参数，时序图看不出来。教训和 Stage 17 后来写的那句一样——设计图会骗你，装配会还你真相。游戏域先付过一次学费。

第三条：GM 后台用既有治理链，不新造「游戏审计」。`governance(auditLogger)` 一行接线。Profile 的调试能力优先来自组装，不是来自新模块。

`describeTurn` 是给人看的调试面，不是给引擎看的。它把响应、world 指令、关系变化、事件 id 收成一段可读摘要，示例 T7 逐回合打印。`describeTurnHumanReadable` 锁住格式；越界 `turnNo` 直接 IAE（`boundsAreChecked`）。人读视图和 `stateAt` 数值视图必须同源——都从同一份 `turns` 推导，禁止一边读日志、一边读「当前世界」偷懒。否则 GM 看见的摘要和步进器看见的时点会对不上，调试会变成对口型——看起来都在讲同一局，数字对不上就不是同一局。

---

## 10. 面试表达

> 「游戏 Agent 要可回放、可调试，关键是走录不重演。存档是 SaveGame 全套领域快照，不是 RunManager 的 run checkpoint；回放是 GameReplay 按 Turn 重 apply 已记录的 WorldEffect 和 RelationshipChange，零模型调用、零规则重评。save.json 管恢复，turn-log.jsonl 管复盘，重演终态必须等于存档终态。坏文件带行号 fail-loud，续局必须分开携带日志起点和存档终态。Checkpoint / Trajectory / GameReplay 三层回放单位不同、消费者不同，平行不合并。审计流水是 GM 后台：每个被放行的世界变更都能查到。」

---

## 11. 下一篇连接什么

五篇拆完，可以回头看总纲：Chat Loop 的五条隐含假设为什么在游戏场景全部破裂，零存量改动为什么是第二次实证，以及第三个 Profile 会缺哪一层。下一篇收口，并指向 Stage 17。

→ [stage-16-article-6-not-just-chat-loop.md](stage-16-article-6-not-just-chat-loop.md)
