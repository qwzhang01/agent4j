# Stage 16 架构设计：Tavern Game Profile

> 对应阶段：Stage 16 - Tavern Game Profile（游戏场景第二个领域 Profile：多角色 / 人格 / 世界状态 / 回合 / 事件 / 关系 / 记忆 / 回放）
> 状态：📐 规划定稿（2026-08-24）—— 未开工；实现记录留 §13 起追加
> 模块：新增 `agent-tavern` Maven 模块，依赖 `agent-core`（Agent/AgentConfig/AgentState/Tool/ChatMessage）+ `agent-memory`（MemoryStore/MemoryScope/MemoryContextBuilder）+ `agent-security`（GovernedToolExecutor/AuditLogger/ToolPolicy）；`agent-model`（MockModelClient）test scope。**不依赖 workflow / scheduler / channel / trace-export / product / enterprise**（见 D5-D9 五处"有意不复用"裁决）
> 前置：Stage 1-14 已完成；Stage 15 进行中（M15.1~M15.3 ✅ 租户用户域/知识层 RAG/治理接线，全仓 754 测试全绿；M15.4/M15.5 待回补）——**两 Profile 依赖正交，互不阻塞**（见 D9）
> 定位：18 周规划「同一 Runtime 支撑三类场景」宣言的**第二次实证**。Stage 15 验证了企业场景缺的是**归属层**（谁在问 / 属于哪个租户 / 花了谁的钱）；Stage 16 验证游戏场景缺的是**世界层**（角色有灵魂 / 说话有后果 / 一局有历史）——而且这一次，Runtime 一个枚举值都不用加

---

## 1. 核心命题：Chat Loop 没有世界

Stage 1-14 造好的 Runtime 有一个更深的隐含共识：**一次对话就是一条消息流**。`agent.run("...")` 进去的是文本、出来的是文本，中间是工具调用。这个共识在游戏场景全线破裂：

```text
Chat Loop 的五个隐含假设，在游戏场景全部破裂：
1. 主体假设 -- 假设对话是"用户↔助手"一对一
   酒馆里是一个玩家面对多个角色（酒保/吟游诗人/佣兵队长），每个角色有独立人格、
   独立记忆、独立与玩家的关系——玩家对酒保说的话，诗人不一定"听见"，但诗人
   记得自己上周和玩家的过节
2. 状态假设 -- 假设状态 = 对话历史（AgentState.messages）
   游戏的状态是一个世界：时间在走（第几回合/白天黑夜）、地点在变、剧情 flag 在
   置位——这些不在消息里，在世界黑板里；消息只是世界的投影之一
3. 影响假设 -- 假设 Agent 的输出就是终点（答案给用户，对话结束）
   游戏里角色的输出会反哺世界：骂了酒保不只多一条消息，酒保好感掉 5 点、
   "闹事"flag 置位、第 8 回合守卫介入事件被触发——对话是因果环的一环，不是终点
4. 记忆假设 -- 假设记忆是"关于用户的偏好和事实"
   角色的记忆是第一人称的剧情沉淀（"这个玩家上次请我喝了一杯蜜酒"），
   按角色隔离、跨局存活——角色"记得你"靠记忆，不靠复读聊天记录
5. 重复假设 -- 假设对话不可重演（跑完就跑完了）
   游戏要求可存档、可回放、可复盘：一局游戏是一个能被完整重演的历史——
   存档是快照，回放是重演状态变化流，两者语义不同（见 D6/D7）
```

Stage 16 的答案：**角色即 Agent**（CharacterCard 装配为 AgentConfig，persona 即 systemPrompt）、**世界即黑板**（WorldState 领域黑板，变更即指令）、**影响即工具**（对话→状态的桥 = 游戏状态工具，治理链限幅 = 数值平衡）、**回合即管线**（TurnEngine：路由→响应→结算→事件评估）、**历史即事件流**（TurnLog append-only → GameReplayer 走录回放）。

一句话（接 Stage 6-15 的递进叙事）：

```text
Stage 15 让 Agent 能进企业 -- 第一个领域 Profile：归属与隔离
Stage 16 让 Agent 能演戏   -- 第二个领域 Profile：
         每个角色有灵魂（persona），每句话有后果（effect），每局游戏有历史（replay）
```

### 与相邻概念的四条边界（面试高频）

```text
Character（16）vs Agent（core）vs User（15）：
  Agent 是机制层执行单元（loop + 工具，无领域语义）
  User 是企业层的"谁在要求"（归属/权限/预算）
  Character 是游戏层的"人格化 Agent"（persona + 角色记忆 + 与玩家的关系）
  判断标准：Relationship 在 Runtime 里不存在，在 Tavern Profile 里是一等公民——
  Profile 的本质就是给 Runtime 翻译出领域语义

WorldState（16）vs WorkflowState（5）vs MemoryStore（8）——三个"状态"的寿命不同：
  WorkflowState 是单次执行的黑板（run 结束即弃，Map 自由读写）
  WorldState 是一局游戏的持久世界（跨回合存活，变更须经 Effect 指令）
  MemoryStore 是跨局的非易失记忆（角色对玩家的长期印象）
  一次 run / 一局 game / 跨局三代寿命，三者并存不互替——
  "游戏进行到第 8 回合"（WorldState）≠"角色记得玩家请过酒"（MemoryStore）

GameEvent（16）vs EventBroker 事件（7）vs MemoryType.EVENT（8）：
  EventBroker 事件是"恢复触发器"（fire → resume(runId)，机制层：何时继续跑）
  GameEvent 是"剧情事实"（发生了什么，改变世界，领域层）
  MemoryType.EVENT 是"事件记忆"（把发生的事记下来，数据层）
  同名不同物：一个是机制，两个是数据——面试时能分清这三个"event"就赢了

GameReplay（16）vs Trajectory Replay（14）vs Checkpoint Resume（6）——三层"重放"：
  Checkpoint 是"从某点继续"（面向恢复：cursor + 黑板快照）
  Trajectory 是"重演模型决策"（面向训练：S-A-O-R-D step-through）
  GameReplay 是"重演叙事历史"（面向复盘：Turn-by-Turn 世界状态流）
  回放单位不同：node 边界 / model step / game turn——平行不合并（见 D7）
```

---

## 2. 复用清单：Stage 16 是第四次「组装阶段」（预检先行）

延续 Stage 12 教训、13/14/15 制度化的做法：**规划时就做复用预检**。本清单每行标注预检结论，含三处「有意不复用」与一处「零存量改动」。

| 能力需求 | 已有设施（阶段） | Stage 16 做什么 | 复用预检 |
|---|---|---|---|
| 人格与对话循环 | `AgentConfig.systemPrompt` + `ReActAgentLoop` + `Agent.run(input, state)`（1/2） | CharacterCard → Agent 装配（persona→systemPrompt），一角色一实例 | ✅ 直接兑现 |
| 多轮对话状态 | `AgentState` + `run(input, state)` 原地续跑（1/2） | TavernGame 持每角色 AgentState，跨回合续跑 | ✅ 直接兑现 |
| 角色长期记忆 + 局内剧情记忆 | `MemoryStore` + `MemoryContextBuilder` + scope 白名单（8） | 检索白名单 = [agent:{charId}, session:{gameId}]（角色跨局记忆 + 本局剧情） | ✅ **零存量改动**：AGENT/SESSION kind 已有——对照 Stage 15 需要 TENANT/KNOWLEDGE 两处加法，游戏域一次都不用加 |
| 事件沉淀记忆 | `MemoryType.EVENT`（8，javadoc 例 "PR #123 was merged"） | 剧情事件可选择性写入角色记忆（"玩家在酒馆赢了骰子局"） | ✅ 直接兑现（EVENT type 语义天然匹配游戏事件） |
| 对话→状态影响 | `Tool` + `ToolRegistry` + `GovernedToolExecutor` 治理链（2/9） | 游戏三工具（adjust_relationship / set_world_flag / trigger_event）挂治理链：权限 AUTO + 全量审计 | ✅ 直接兑现 + 新叙事：**审计流水 = GM 后台，治理限幅 = 数值平衡器** |
| mention 路由 | `ChannelMessage.autoDetect` 的 @检测语义（12） | 玩家 `@角色名` 决定谁响应（未命中 → 友好提示可用角色） | ✅ 思想复现，直接实现（不引 channel 依赖——D9） |
| 回放纪律 | `TrajectoryReplayer`/`ReplayView` 走录不重演 + `JsonlTrajectoryWriter` append-only（14） | GameReplayer 平行实现（TurnRecord 单位 + JSONL 信封 + 完整性校验） | ✅ 模式复用非代码复用（回放单位不同：turn vs model step） |
| 状态快照序列化 | `AgentState.snapshot()`（2，为 Stage 6 checkpoint 预留的深拷贝） | SaveGame 存每角色消息快照 | ✅ 直接兑现 |
| 存档/恢复 | `RunManager` + `CheckpointStore`（6） | **不依赖**：run checkpoint 语义是"单次 workflow run 暂停恢复"（cursor/黑板/runId）；游戏存档是"全套领域状态快照"（世界+关系+全部角色+TurnLog）——形似神异（见 D6） | ⚠️ 有意不复用，蓝图显式记录 |
| 事件触发 | `EventBroker` subscribe/fire（7） | **不依赖**：fire→resume(runId) 绑死 workflow run；游戏事件是回合结算点的**同步规则评估**，无 run 语义（Stage 12 D3 同款裁决，见 D5） | ⚠️ 有意不复用，蓝图显式记录 |
| 回合编排 | `Workflow` / `GraphRuntime`（5/6） | **不依赖**：回合管线是固定顺序代码（路由→响应→结算→评估），无分支并行需求；图引擎的机制税（runId/黑板传递/checkpoint）换不来收益（见 D8） | ⚠️ 有意不复用，蓝图显式记录 |
| Mock 验收 | `MockModelClient` scripted：`respondText` / `respondToolCalls(ToolCall...)`（1） | 按序编排角色台词与游戏工具调用序列，全链路零 LLM 依赖 | ✅ 同 Stage 8-15 手法 |

### 存量改动清单（预检裁决：零）

**零存量改动**。这是三个 Profile 中第一个完全落在既有机制能力面内的（15 需要两处枚举加法）。角色记忆 = `agent:{charId}`（AGENT kind 已有）、局内剧情 = `session:{gameId}`（SESSION kind 已有）、事件沉淀 = EVENT type（已有）、人格 = systemPrompt（已有）、多轮续跑 = `run(input, state)`（已有）。

这本身就是「同一 Runtime」宣言最有力的证据。面试金句：

```text
Stage 15 我给 Runtime 加了两个枚举值（TENANT/KNOWLEDGE）；
Stage 16 一个都没加——游戏没有新机制，只有新领域模型。
当一个新场景能零存量改动落地时，才证明之前的抽象是对的。
```

**依赖方向**：`agent-tavern -> agent-core + agent-memory + agent-security`（compile）；`agent-model`（test scope）。零新第三方依赖（Jackson 已由 core 传递）。

---

## 3. 核心抽象（16 个，六组）

### 第一组：角色（character 包，M16.1）

| 抽象 | 层 | 一句话 |
|---|---|---|
| `CharacterCard` | 数据 | 人格卡：characterId + displayName + persona（背景/性格/说话风格）+ greeting（开场白可选）——角色的"出厂设定" |
| `CharacterAgentFactory` | 核心 | Card → Agent 翻译器：persona 渲染为 systemPrompt + 挂记忆 ContextBuilder + 注册游戏工具——领域定义到机制装配的唯一通道 |
| `CharacterMemory` | 核心 | 角色记忆工厂：`contextBuilder(charId, gameId)` = MemoryContextBuilder + 白名单 [agent:{charId}, session:{gameId}]（同 Stage 12 `channelMemoryContext` 手法：共享是 scope 取值，不另造系统） |

### 第二组：世界（world 包，M16.2）

| 抽象 | 层 | 一句话 |
|---|---|---|
| `WorldState` | 数据 | 领域黑板：turnCount + location + flags（Map）——**唯一变更路径是 apply(Effect)**，没有自由 setter |
| `WorldEffect` | 数据 | sealed 变更指令：`SetFlag(key, value)` / `ClearFlag(key)` / `SetLocation(loc)`——变更即指令：可审计、可回放、可校验（WorkflowState 黑板哲学的领域版） |
| `SetWorldFlagTool` | 核心 | `implements Tool`：set_world_flag——角色在 ReAct 循环中改变世界的"手柄" |

### 第三组：关系（relation 包，M16.3）

| 抽象 | 层 | 一句话 |
|---|---|---|
| `Relationship` | 数据 | 玩家↔单角色关系：value（0-100）+ lastChangedTurn + `tier()` 派生（STRANGER/COLD/NEUTRAL/WARM/FRIEND/DEVOTED） |
| `RelationshipMatrix` | 核心 | 全角色关系表：view/apply/快照；apply 经 Policy 校验，拒绝超幅（fail-closed） |
| `RelationshipPolicy` | 数据 | 数值平衡 SSOT：maxChangePerTurn（默认 5，**按回合累计**——防"化整为零"多次小步刷好感）+ 0-100 钳位 |
| `AdjustRelationshipTool` | 核心 | `implements Tool`：adjust_relationship {characterId, delta}——超幅返回错误文本（模型看到失败观察，ReAct 自愈） |

### 第四组：事件（event 包，M16.3）

| 抽象 | 层 | 一句话 |
|---|---|---|
| `GameEvent` | 数据 | 剧情事件：eventId + description + respondCharacterId（可空）——发生了什么、谁被牵动 |
| `EventRule` | 数据 | 触发规则：condition 谓词（世界+关系快照视图）→ GameEvent + 随行 effects；once 默认 true（触发过不再评估） |
| `EventEvaluator` | 核心 | 回合结算点同步评估全部规则（**恰一轮**，不级联）；触发的事件应用 effects + 注入事件响应 |
| `TriggerEventTool` | 核心 | `implements Tool`：trigger_event {eventId}——角色可以主动引爆剧情（敬酒 → "全场欢呼"事件） |

### 第五组：回合（turn 包，M16.2）

| 抽象 | 层 | 一句话 |
|---|---|---|
| `Turn` | 数据 | 回合记录 record：turnNo + playerInput + speakingCharacterId + responses（主响应 + 0..n 事件响应）+ appliedEffects + triggeredEventIds + timestamp——回放数据单元 |
| `TurnEngine` | 核心 | 回合管线：mention 路由 → 上下文注入（世界/关系快照前缀）→ 角色 run → 事件评估 → Turn 落账 |
| `TurnLog` | 核心 | append-only 回合账本（JSONL，首行 initial 快照信封）——回放数据源 + 存档组成部分 |

### 第六组：存档回放与门面（replay 包 + root，M16.4/M16.5）

| 抽象 | 层 | 一句话 |
|---|---|---|
| `SaveGame` | 数据 | 局快照：gameId + WorldState + RelationshipMatrix + 各角色 AgentState 快照 + 已触发事件集——"全套领域状态" |
| `GameStore` | 核心 | save/load（目录布局：{dir}/{gameId}/save.json + turn-log.jsonl） |
| `GameReplayer` | 核心 | 走录不重演：load TurnLog → 逐 Turn 步进（stateAt(turnNo) 重演世界与关系）+ 完整性校验（turnNo 连续 / 重演终态 == 快照终态） |
| `TavernGame` | 核心 | 一局游戏的门面：`playerSay(text)` 驱动回合；`save()/load()`；`world()/relationships()/replay()` 查看 |

### 3.1 关键接口草图

```java
// ---- 角色（第一组）----
public record CharacterCard(String characterId, String displayName,
                            String persona, String greeting) {
    // persona 是自由文本人格描述（背景/性格/说话风格/行为边界）
}

public final class CharacterAgentFactory {
    public CharacterAgentFactory(ModelClient modelClient, MemoryStore memoryStore) {}
    public Agent create(CharacterCard card, String gameId, ToolRegistry gameTools) {
        // AgentConfig(card.characterId(), personaPrompt(card), modelClient,
        //             gameTools, maxSteps, characterMemory.contextBuilder(card, gameId))
        // personaPrompt = card.persona + 行为约束（工具使用守则）
    }
}

// ---- 世界（第二组）----
public final class WorldState {
    public int turnCount();
    public String location();
    public Optional<String> flag(String key);
    public Map<String, String> flags();               // 只读视图
    public WorldState apply(WorldEffect effect);      // 唯一变更路径；返回新状态（record 风格）
    public String describe();                         // 人读快照（注入 prompt 用）："第8回合/夜晚/大堂"
}

// ---- 关系（第三组）----
public final class RelationshipMatrix {
    public Relationship view(String characterId);     // 未见过的角色 = NEUTRAL 50
    public ApplyResult apply(String characterId, int delta, int turnNo);
    // 经 RelationshipPolicy 校验：回合累计限幅（fail-closed 拒绝，返回带原因）
    public Map<String, Relationship> snapshot();
}

public final class AdjustRelationshipTool implements Tool {
    // name="adjust_relationship"; schema: {characterId: string, delta: integer}
    // 构造绑定 RelationshipMatrix + RelationshipPolicy（数值边界在工具内，
    // 权限与审计在治理链——两层分工见 D4）
    // 超幅 → 返回错误文本（不抛异常炸穿 loop，模型读到失败观察可自省修正）
}

// ---- 事件（第四组）----
public record EventRule(String ruleId, Predicate<GameFacts> condition,
                        GameEvent event, List<WorldEffect> effects, boolean once) {
    // GameFacts = 世界+关系+回合数的只读快照视图（规则的评估入参）
}

public final class EventEvaluator {
    public List<TriggeredEvent> evaluate(GameFacts facts) {
        // 恰一轮：评估全部规则一次，触发集不引发同回合二次评估（防事件链风暴）
    }
}

// ---- 回合（第五组）----
public final class TurnEngine {
    public TurnResult playTurn(TavernGame game, String playerInput) {
        // 1) mention 解析 → speakingCharacter（未命中 → 提示可用角色，不空跑）
        // 2) 回合输入注入："[world] 第8回合/大堂 [relationship] 好感62(WARM)
        //    [player] @酒保 来杯蜜酒"（同 Stage 12 [from userId]/[handoff] 便签手法）
        // 3) characterAgent.run(input, state) —— ReAct 循环内可调游戏三工具
        // 4) 事件评估（恰一轮）→ 触发事件应用 effects + 事件响应（respondCharacter 再 run 一次）
        // 5) Turn 落 TurnLog（append-only）
    }
}

// ---- 存档回放与门面（第六组）----
public final class TavernGame {
    public static Builder builder();   // cards + world init + rules + policy + model + store
    public TurnResult playerSay(String input);
    public void save(Path dir);
    public static TavernGame load(Path dir, TavernGame Blueprint 装配参数);
    public WorldState world();
    public RelationshipMatrix relationships();
    public GameReplay replay();
}
```

---

## 4. 关键设计决策（9 个）

### D1. 零存量改动：第二次实证的最佳证据

```text
Stage 15 加了两个枚举值（MemoryScope.TENANT + MemoryType.KNOWLEDGE）——
  企业场景的隔离维度与知识类型在机制层真缺
Stage 16 零存量改动——
  角色记忆 = agent:{charId}（AGENT kind 已有）
  局内剧情 = session:{gameId}（SESSION kind 已有）
  事件沉淀 = EVENT type（已有，javadoc 例 "PR #123 was merged" 与游戏事件同形态）
  人格注入 = systemPrompt（已有）
  多轮续跑 = run(input, state)（已有）

零改动不是运气，是 Stage 8 scope 设计（kind:ID 的正交命名空间）的必然结果：
  任何"谁的记忆"问题都是 scope 取值问题，不需要新机制。
  当第 N 个场景还能零改动落地，之前 N-1 次的抽象才算被证明
```

### D2. 角色即 Agent：一局一实例，跨局靠记忆不靠历史

```text
装配：CharacterCard → AgentConfig（persona → systemPrompt），一角色一 Agent 实例；
      TavernGame 持每角色 AgentState，每回合 run(input, state) 原地续跑

跨局语义（关键裁决）：新开一局 = 全部 AgentState 重建（对话历史不跨局），
  角色"记得你"靠 MemoryStore 的 agent:{charId} 记忆（EPISODE/FACT），
  不靠复读上一局的原始消息流

为什么：与人类记忆模型一致——记得事实（"你请过我喝蜜酒"），
  不逐字复读原文；也避免消息无限膨胀（token 预算角度）
诚实边界：v1 局内全量历史 + 跨局记忆检索（无局末摘要压缩）——
  长局的 compaction 复用 ContextCompressor 是 v2 顺手活，不在本阶段承诺
```

### D3. 世界即黑板，变更即指令

```text
WorldState 没有 public setter，唯一变更路径 = apply(WorldEffect)：
  sealed 指令集（SetFlag/ClearFlag/SetLocation）——每条变更是可枚举、可审计、
  可回放的一等值，不是散落在代码里的 map.put

对照 WorkflowState（Stage 5 黑板）：哲学同源（共享可变状态集中管理），形态不同——
  WorkflowState 是 Map<String,Object> 自由读写（执行期灵活优先），
  WorldState 是领域类型 + 指令式变更（长期状态的可追溯优先）
组合不继承：硬复用 WorkflowState 会带进 NodeContext/StepRecord 语义负担
```

### D4. 影响即工具，治理即平衡：Stage 9 治理链的游戏域妙用

```text
对话→状态的桥 = 三个游戏工具（adjust_relationship / set_world_flag / trigger_event）：
  角色在 ReAct 循环里"顺手"改变世界——与查天气、调 API 是同一种动作
  （模型视角里世界状态就是另一个外部系统）

两层分工：
  治理链（GovernedToolExecutor，Stage 9）：权限三档（三工具全 AUTO）+ 审计全量
  工具内部（RelationshipPolicy）：数值边界校验

数值平衡的 fail-closed：限幅按【回合累计】非单次调用——
  否则模型可以每回合调 4 次 +3 绕过 |Δ|≤5 的单次限制（化整为零刷好感）
  超幅返回错误文本（不炸穿 loop）→ 模型读到失败观察 → ReAct 自愈修正——
  工具错误处理（Stage 2）在游戏域的戏剧性用法

叙事金句：Stage 9 的审计流水在游戏域 = GM 后台（每个状态变更都有日志）；
  治理限幅 = 数值平衡器（防 LLM 角色行为失控——"AI 酒保一回合爱上玩家"是真实的
  游戏设计事故，限幅就是护栏）
```

### D5. 事件是同步规则评估，不是 EventBroker

```text
EventBroker（Stage 7）的语义是"事件驱动的 run 恢复"：fire(key) → resume(runId)，
  回调绑死 RunManager——它解决的是"workflow run 等外部事件后继续跑"

游戏事件是"回合结算点的剧情判定"：EventRule 谓词评估（关系阈值/flag/回合数）
  → 触发 → 应用 effects + 事件响应。全部发生在 playTurn() 内部，同步、无 run 语义

Stage 12 D3 同款裁决模式：复用【语义】（规则化的事件触发思想）而非【实现】
  （绑 runId 的订阅表）。异步剧情事件（定时事件/挂机触发/跨局世界事件）留 v2
```

### D6. 存档是局快照，不是 run checkpoint

```text
RunManager/CheckpointStore（Stage 6）语义 = 单次 workflow run 的暂停恢复：
  checkpoint 载荷是 cursor + WorkflowState 黑板，索引是 runId

游戏存档语义 = 一局游戏的全套领域状态快照：
  WorldState + RelationshipMatrix + 全部角色的 AgentState + 已触发事件集 + TurnLog 位置
  索引是 gameId（业务标识），不是 runId（游戏回合根本不产生 workflow run）

形似神异，硬复用的代价：把领域状态塞进 WorkflowState 黑板 + 伪造 run 生命周期
  ——机制税换零收益。真正的免费复用点是 AgentState.snapshot()（它从 Stage 2
  起就为序列化预留）与 FileCheckpointStore 的目录管理思想（不引依赖，参考形态）
```

### D7. 回放走录不重演：Stage 14 纪律的领域层第二次兑现

```text
GameReplayer 重演的是【状态变化流】：TurnLog 逐行 apply(appliedEffects) 重建
  WorldState/Relationships 的时间线——模型不重跑（回放不需要 LLM）

TurnRecord（领域轨迹）与 Trajectory（模型轨迹，Stage 14）平行不合并：
  回放单位不同（turn vs model step）、消费者不同（游戏复盘 vs RL 训练）、
  数据形态不同（叙事+效果 vs S-A-O-R-D）
共同的纪律同源：append-only JSONL + 信封（kind 字段区分 initial/turn 行）+
  加载时完整性校验（turnNo 连续 / 首行必须是 initial）+ 走录不重演

可选彩蛋（不承诺）：RecordingAgent（Stage 14）包角色 Agent → 同一局游戏
  同时产出模型轨迹——"游戏行为也是 RL 数据"的叙事接口，v2 验证
```

### D8. 回合是引擎顺序代码，不是 Workflow 图

```text
回合管线 = 路由 → 注入 → 角色 run → 事件评估 → 落账，固定顺序无分支：
  用 GraphRuntime 编排的机制税（每回合一个 run：runId/checkpoint/黑板进出）
  换不来任何收益（无条件路由、无并行、无人工节点）

图引擎的正确出场时机是 v2 群聊（多角色并行响应 = ParallelNode 天然形态）——
  届时再评估，届时才有真需求驱动的设计
哲学对齐 Stage 15：EnterpriseTaskManager 也只在长任务一个点用 workflow——
  Profile 层"哪里需要哪里接"，不是"一切都要过图引擎"
```

### D9. 与 Stage 15 平行不互赖：Profile 正交性

```text
agent-tavern 不依赖 agent-enterprise（反之亦然）：
  两者的共同底座是 core + memory（+ 各自点用的 security）
  企业域概念（Tenant/RequestContext/CostLedger）在游戏域零出现；
  游戏域概念（WorldState/Relationship/GameEvent）在企业域零出现

工程含义：Stage 15 剩余里程碑（M15.4 业务任务与恢复 / M15.5 装配收口）可以之后回补，
  不阻塞 Stage 16 开工——两 Profile 平行推进是"同一 Runtime 三类场景"
  叙事的并行证明（第三个 Profile 在 Stage 17）
```

---

## 5. 分层架构图

```text
┌───────────────────────────────────────────────────────────────────────┐
│ examples: TavernGameExample（全链路验收剧本）                            │
└───────────────────────────────────────────┬───────────────────────────┘
                                            │
┌───────────────────────────────────────────▼───────────────────────────┐
│ agent-tavern（Stage 16 新增）                                          │
│                                                                       │
│  character/  CharacterCard / CharacterAgentFactory / CharacterMemory   │
│              —— D2：persona→systemPrompt；跨局记忆=scope 取值           │
│  world/      WorldState / WorldEffect / SetWorldFlagTool               │
│              —— D3：变更即指令的黑板                                    │
│  relation/   Relationship / RelationshipMatrix / RelationshipPolicy    │
│              / AdjustRelationshipTool                                  │
│              —— D4：回合累计限幅 fail-closed                            │
│  event/      GameEvent / EventRule / EventEvaluator / TriggerEventTool │
│              —— D5：同步规则评估，恰一轮防风暴                           │
│  turn/       Turn / TurnEngine / TurnLog                               │
│              —— D8：顺序管线；注入便签手法（[world]/[relationship]）     │
│  replay/     SaveGame / GameStore / GameReplayer                       │
│              —— D6/D7：局快照 + 走录回放                                │
│  (root)      TavernGame（门面：一局游戏 = 一个实例）                     │
└────┬──────────────────────┬──────────────────────────┬────────────────┘
     │ compile              │ compile                  │ compile
┌────▼───────────┐ ┌────────▼─────────┐ ┌──────────────▼───────────────┐
│ agent-core     │ │ agent-memory     │ │ agent-security               │
│ Agent/Config   │ │ MemoryStore      │ │ GovernedToolExecutor         │
│ /AgentState    │ │ /MemoryScope     │ │ /ToolPolicy（三工具全 AUTO） │
│ /ReActAgentLoop│ │ /MemoryContext   │ │ /AuditLogger（GM 后台数据源）│
│ /Tool/Registry │ │  Builder         │ │                              │
└────────────────┘ └──────────────────┘ └──────────────────────────────┘
  agent-model（MockModelClient）= test scope；
  workflow/scheduler/channel/trace-export/product/enterprise 不依赖（D5-D9）
```

数据流（一回合的旅程）：

```text
玩家 ──playerSay("@酒保 来杯蜜酒，今晚诗人唱得如何？")──▶ TavernGame
                                                            │
                                                        TurnEngine
                                                            │
              ┌─────────────────────────────────────────────┤
              ▼                                             ▼
        mention 路由                              回合输入注入（便签手法）
        （@酒保 → marcus）                        [world] 第8回合/夜晚/大堂
              │                                   [relationship] 好感62(WARM)
              ▼                                   [player] @酒保 来杯蜜酒…
        marcus Agent.run(input, state)  ◀─────────────┘
              │
              │  ReAct 循环（模型决策）
              ├── adjust_relationship(marcus, +3) ──▶ 治理链(AUTO+审计) ──▶
              │     RelationshipPolicy：本回合累计 |+3| ≤ 5 ✓ ──▶ 应用
              ├── set_world_flag(吟游诗人气氛, 活跃) ──▶ WorldState.apply
              └── 台词："蜜酒来了。Lyra 今晚状态确实不错…"
              │
              ▼
        事件评估（恰一轮）：
        EventRule[好感≥80 → 告白事件]：62 未触发
        EventRule[flag 吟游诗人气氛=活跃 ∧ 回合≥8 → 诗人的即兴创作]：触发！
              │   ├── 随行 effects：WorldState.apply(...)
              │   └── 事件响应：lyra Agent.run("[event] 诗人的即兴创作") → 台词
              ▼
        Turn 落 TurnLog（append-only）──▶ 返回本回合全部响应给玩家
        审计流水（治理链）：adjust_relationship / set_world_flag / trigger_event
                          全量留痕 —— GM 后台可查"这一局世界被改了哪些地方"
```

---

## 6. 完整时序：一局酒馆的剧本

```text
T0: 装配（游戏设计师一次性）
    三张 CharacterCard：marcus 酒保（健谈热心）/ lyra 吟游诗人（傲娇毒舌）/
                        brawn 佣兵队长（寡言多疑）
    WorldState 初始：location=大堂, flags={} , turnCount=0
    EventRule 三条：
      R1 好感(marcus)≥80 → "酒保的告白"（once, 响应角色 marcus）
      R2 flag(吟游诗人气氛=活跃) ∧ turn≥8 → "诗人的即兴创作"（once, 响应 lyra）
      R3 好感(brawn)≤20 → "佣兵的敌意"（once, 响应 brawn, 效果: flag(冲突酝酿)=是）
    RelationshipPolicy：maxChangePerTurn=5
    治理：三游戏工具全 AUTO + AuditLogger 全量

T1: 开场
    game.playerSay("@酒保 晚上好") → marcus 回应（人格=systemPrompt 已生效）
    → 好感初值 50（NEUTRAL）注入回合输入

T2: 对话改变世界（影响即工具）
    "@酒保 来杯蜜酒，顺便聊聊最近酒馆的传闻"
    → 模型调 adjust_relationship(marcus, +3) + 台词
    → Turn 记录 appliedEffects=[relationship:marcus+3]

T3: 多角色切换（多角色验收）
    "@诗人 那首歌叫什么？"
    → 路由到 lyra（marcus 的 AgentState 不动——各自独立对话历史）
    → lyra 调 set_world_flag(吟游诗人气氛, 活跃) —— 模型"感觉"自己被欣赏

T4: 事件触发（世界事件验收）
    回合结算：R2 命中（flag=活跃 ∧ turn≥8）
    → GameEvent 应用随行 effects + lyra 被事件唤醒追加响应
    → 玩家看到酒保和诗人的双重输出——"世界活过来了"

T5: 限幅演示（数值平衡验收）
    模型（scripted 编排）调 adjust_relationship(marcus, +10)
    → Policy 拒绝：本回合累计超 5 → 工具返回错误文本
    → 模型读到失败观察，改口自然对话（ReAct 自愈）

T6: 存档与续局（保存回放验收）
    game.save(dir) → {gameId}/save.json + turn-log.jsonl
    TavernGame.load(dir) → 新实例：世界/关系/三角色对话状态全恢复
    → 继续对话，lyra 还记得 T3 聊过的歌（AgentState 续跑语义）

T7: 回放（复盘验收）
    game.replay() → GameReplayer 逐 Turn 步进：
      stateAt(4).world.flag("吟游诗人气氛") == "活跃"（重演到 T4 时点）
      describeTurn(4) 人读摘要：玩家输入/谁说了什么/世界改了什么/触发了什么
    → 重演终态 == save.json 终态（完整性校验）

失败分支：
    F1 未命中 mention：playerSay("随便看看") → 返回可用角色提示（不空跑模型）
    F2 超幅调整：+10 被拒 → 错误文本进对话 → 模型自愈（T5）
    F3 事件风暴防护：R3 触发置位 flag(冲突酝酿)，若另一规则条件依赖此 flag
       且同回合已评估过 → 本回合不再评估（恰一轮，不级联）
    F4 回放坏文件：turnNo 跳号/首行非 initial → IAE 带行号（对齐 Stage 14 fail loud）
    F5 存档缺失：load 目录不完整 → 明确异常（不猜不造）
```

---

## 7. 模块结构

```text
agent-tavern/                                     # 新增 Maven 模块（父 POM <modules> 增补）
└── src/main/java/io/github/qwzhang01/agent/tavern/
    ├── character/                                # 3 类（M16.1）
    │   ├── CharacterCard.java
    │   ├── CharacterAgentFactory.java
    │   └── CharacterMemory.java
    ├── world/                                    # 3 文件（M16.2）
    │   ├── WorldState.java
    │   ├── WorldEffect.java                      # sealed interface + record 变体
    │   └── SetWorldFlagTool.java
    ├── relation/                                 # 4 类（M16.3）
    │   ├── Relationship.java
    │   ├── RelationshipMatrix.java
    │   ├── RelationshipPolicy.java
    │   └── AdjustRelationshipTool.java
    ├── event/                                    # 4 类（M16.3）
    │   ├── GameEvent.java
    │   ├── EventRule.java                        # 含 GameFacts 快照视图
    │   ├── EventEvaluator.java
    │   └── TriggerEventTool.java
    ├── turn/                                     # 3 类（M16.2）
    │   ├── Turn.java                             # record（含 CharacterResponse）
    │   ├── TurnEngine.java
    │   └── TurnLog.java
    ├── replay/                                   # 3 类（M16.4）
    │   ├── SaveGame.java
    │   ├── GameStore.java
    │   └── GameReplayer.java                     # 含 GameReplay 步进视图
    └── TavernGame.java                           # 门面 + Builder（M16.5）
```

```text
存量改动：无（§2 预检裁决——零，对照 Stage 15 的两处枚举加法）
```

```text
examples/（新增 1 个）
└── TavernGameExample.java    # 验收剧本：T0-T7 全景（三角色人格 → 关系工具 →
                              #   事件触发 → 限幅自愈 → 存档续局 → 逐回合回放）
```

不改动其他任何存量模块（agent-core / agent-memory / agent-security 零 diff）。

---

## 8. 实现里程碑（5 个，节奏对齐 Stage 13/14/15）

| # | 里程碑 | 交付 | 验证 |
|---|--------|------|------|
| M16.1 | 角色域 | character 3 类 + 单测 | Card→Agent 装配：persona 进 systemPrompt（捕获 ModelRequest 断言）；同输入两角色两套响应（人格隔离）；记忆白名单双 scope 生效（agent:{charId} 跨局命中 / session:{gameId} 局内隔离）；跨局：新 gameId 的检索拿不到上一局 session 记忆 |
| M16.2 | 世界与回合 | world 3 文件 + turn 3 类 + 单测 | 回合管线全链：mention 路由（命中/未命中两态）；[world]/[relationship] 便签注入实证（捕获请求断言）；工具变更世界（apply 后 flag 可读、describe 可读）；Turn 落账字段完整；append-only（写后字节不变） |
| M16.3 | 关系与事件 | relation 4 类 + event 4 类 + 单测 | 限幅三态（单次超幅拒 / 回合累计超幅拒 / 合法放行）+ 拒绝文本进对话模型可读；事件：规则命中触发 + effects 应用 + 事件响应追加；once 语义（二次评估不触发）；恰一轮（构造级联条件断言不级联）；TriggerEventTool 主动引爆 |
| M16.4 | 存档与回放 | replay 3 类 + 单测 | save→load round-trip：世界/关系/三角色 AgentState 全恢复（续跑行为连续）；GameReplayer 逐 Turn 步进：stateAt 时点正确；完整性校验（跳号/缺 initial/坏行全 IAE）；重演终态 == 存档终态 |
| M16.5 | 装配与收口 | TavernGame 门面 + TavernGameExample + README/笔记收口 | 示例实跑 T0-T7 全剧本（三角色/关系变化/事件触发/限幅自愈/存档续局/回放）；人格-关系-事件三者互影响的完整因果链演示；全仓存量零影响 |

依赖：M16.2 ← M16.1（Agent 装配）；M16.3 ← M16.2（结算点在回合管线）；M16.4 ← M16.2（TurnLog）；M16.5 ← 全部。主路径串行。

---

## 9. 验收标准（对齐 18 周规划原文）

```text
规划原文：完成一个最小酒馆场景：
1. 玩家与多个角色对话
   -> M16.1 多角色注册 + M16.2 mention 路由（@角色 显式指定，路由确定性）
2. 角色拥有不同人格
   -> M16.1 persona→systemPrompt 翻译（测试捕获模型实见消息断言人格注入）
3. 对话会改变关系值
   -> M16.2/M16.3 AdjustRelationshipTool（治理链搭车 + 回合累计限幅）
4. 关系值会影响后续行为
   -> M16.3 关系快照注入回合输入（tier 文本）+ 关系阈值事件（R1/R3 两方向）
5. 世界事件可以被触发
   -> M16.3 EventRule 条件触发 + TriggerEventTool 主动触发 + 事件强制响应
6. 一局对话可以保存和回放
   -> M16.4 SaveGame/GameStore + GameReplayer（走录不重演 + 完整性校验）

「需要支持」八项对照：多角色=M16.1/M16.2 路由 / 角色人格=M16.1 /
世界状态=M16.2 WorldState / 回合推进=M16.2 TurnEngine / 事件系统=M16.3 /
角色关系=M16.3 / 长期记忆=M16.1 agent scope 跨局 / 状态回放=M16.4
```

---

## 10. 测试策略

- **人格隔离（最高优先级）**：同输入两角色两套响应；捕获 ModelRequest 断言 systemPrompt 差异（角色是"谁"在机制层的可执行证明）
- **记忆白名单**：跨局隔离（新 gameId 拿不到旧局 session 记忆）；跨角色隔离（lyra 检索不到 agent:marcus 私有记忆）；白名单空 = 空结果（fail-closed 不放大）
- **限幅正确性**：单次超幅 / 回回累计超幅 / 多次小步累计到限 三态；拒绝不炸穿 loop（错误文本成为可读观察）；0-100 钳位
- **事件语义**：条件命中 / once 不重复 / 恰一轮不级联（防风暴核心证明）/ 事件响应角色正确
- **回合完整性**：Turn 字段全量落账；注入便签格式稳定（[world]/[relationship]/[player] 前缀——改格式=回放兼容性破坏，测试锁住）
- **存档 round-trip**：save→load 后世界/关系/对话状态逐项相等；续跑一轮行为与不存档路径一致（状态连续性）
- **回放完整性**：stateAt 各时点正确；坏文件三形态（跳号/缺 initial/坏 JSON 行带行号）全 IAE；重演终态 == 存档终态
- **append-only**：TurnLog 写后字节不变（同 Stage 14 sidecar 纪律）
- **向后兼容**：零存量改动——全仓存量测试零 diff 即证明（最轻的回归负担）
- **Mock 验收**：全链路零 LLM 依赖（MockModelClient scripted，respondToolCalls 编排工具序列，同 Stage 8-15 手法）

---

## 11. 文章规划（规划原文 6 篇全收）

| 文章（规划原文） | 写作时机 | 素材来源 |
|---|---|---|
| 《酒馆类游戏 Agent 的领域模型》 | M16.1/M16.2 | 六领域模型与"翻译"视角（Card→Config / 世界→黑板 / 影响→工具） |
| 《角色人格、世界状态和记忆如何分层》 | M16.2 | D2/D3 + 三状态寿命（一次 run / 一局 game / 跨局记忆）——与 WorkflowState/MemoryStore 的三分法 |
| 《一回合对话如何驱动游戏状态变化》 | M16.2/M16.3 | D4 影响即工具 + §5 数据流（对话→工具→治理→世界→事件→下一回合的因果环） |
| 《游戏 Agent 的事件系统和关系系统》 | M16.3 | D5 + D4 限幅（事件三义辨析 + "AI 酒保一回合爱上玩家"的数值护栏叙事） |
| 《如何让游戏 Agent 行为可回放、可调试》 | M16.4 | D6/D7 + 三层回放对照（Checkpoint/Trajectory/GameReplay）+ 审计=GM 后台 |
| 《游戏 Agent 为什么不能只有一个 Chat Loop》 | 收口 | §1 五假设破裂 + 零存量改动的证明力（总纲，M8 里程碑叙事） |

**系列衔接**：文章 6 是 Stage 17（Coding Agent Profile）的引子——"第三个 Profile 零新机制落地时，Runtime 的抽象就完整了"；文章 5 呼应 Stage 14 的三层回放，是"调试格式 vs 训练格式 vs 复盘格式"的收官篇。

---

## 12. 本阶段不做（范围控制）

- **群聊多角色同时响应 / 导演 Agent 分派** —— v1 mention 单角色显式路由（确定性优先）；并行响应是 v2 图引擎（ParallelNode）的真需求场景（D8）
- **角色间自主对话（NPC↔NPC 无玩家在场自演化）** —— v2；回合内事件响应已是"角色被动卷入"的最小形态
- **异步事件（定时剧情 / 挂机触发 / 跨局世界事件）** —— D5：回合结算同步评估；异步需要事件总线与游戏时钟，v2
- **事件链级联（一回合多轮事件评估）** —— 恰一轮防风暴是 v1 语义，级联规则的设计留 v2
- **玩家侧 Agent（玩家也被建模为角色）** —— v1 玩家是纯输入方
- **局末摘要压缩 / 长局 compaction** —— ContextCompressor 复用是 v2 顺手活
- **RL 轨迹桥（RecordingAgent 包游戏 Agent）** —— D7 可选彩蛋，不承诺
- **向量记忆 / 角色卡前端 / 多存档槽管理** —— 库形态 + 单存档目录原语
- **多模态角色卡（立绘/语音）** —— ContentPart 机制已在（多模态 Stage 13 后已有），但游戏 v1 纯文本

---

## 13. M16.1 实现记录（2026-08-24，角色域）

### 交付

- 新增 `agent-tavern` Maven 模块（父 POM `<modules>` + dependencyManagement 注册；compile 依赖仅 `agent-core` + `agent-memory`——「依赖随用随加」纪律，agent-security 留 M16.2 治理链落地时再加；agent-model test scope）
- **零存量改动兑现（蓝图 D1 的直接验证）**：除父 POM 两处注册外，agent-core / agent-memory 等存量模块零 diff——全仓 793 测试全绿（Stage 15 收官 774 + tavern 19），存量零影响
- **character 包 3 类**：
  - `CharacterCard`（record：characterId + displayName[null/blank 默认 characterId] + persona[必填 fail-fast——"a character without a soul is just a chat loop"] + greeting[可选领域数据，门面 M16.5 消费]）
  - `CharacterMemory`（**白名单即全部设计**：`scopesFor(charId, gameId)` = [agent:{charId}, session:{gameId}]——AGENT/SESSION kind 均已有，零新枚举值，这是蓝图 D1「零存量改动」的兑现点；`contextBuilder` = MemoryContextBuilder + 白名单 + null compressor[诚实边界：局末压缩 v2] + recallLimit 默认 8）
  - `CharacterAgentFactory`（Card→Agent 翻译器：`personaPrompt` = 身份行 + persona 正文 + **Interaction rules 四条**——M16.2 游戏工具的行为契约预埋[按角色情绪用工具/世界变化从对话中生长/工具报错自然继续/不出戏]；maxSteps 默认 6——角色回合是场景节拍不是研究任务；null gameTools → 空 registry 纯对话可用）
- 测试 19 个：`CharacterCardTest` 4（校验三态 + displayName 默认）+ `CharacterMemoryTest` 9（**白名单四向**：双 scope 可见 / 跨角色隔离 / 跨局隔离 / **角色记忆跨局存活**[D2「记得你靠记忆不靠历史」的可执行证明]；[Known memories] 注入紧跟 system 的格式契约 + recallLimit 行数边界 + 空记忆零注入）+ `CharacterAgentFactoryTest` 6（**persona 注入模型实见实证**——CapturingModelClient 捕获 ModelRequest 断言首条 SYSTEM 含身份+persona+Interaction rules / **同输入两角色两人格**[人格隔离的可执行证明，两 prompt assertNotEquals] / config 绑定与 maxSteps / 端到端白名单[run 全链注入块只见 agent:marcus 内容，agent:lyra 与 session:game-2 不可见] / **AgentState 跨回合续跑**[M16.5 门面原语：held state 两次 run，第二次请求含 4 条完整历史]）

### 测试侧 bug 1 处（记入防复发）

`recallLimitBoundsInjection` 初版按**消息条数**计数断言期望 2 实际 1——所有记忆行渲染在**同一条**注入 USER 消息内（MemoryContextBuilder 把整块记忆拼成一条 `[Known memories]` 消息），应按注入块内的**行数**（`startsWith("- [")`）计数。对齐 M15.3 教训：「测试失败先核对自己算式和语义」。

### 环境备忘

- `JAVA_HOME=$(路径)` 写法在 zsh 下把目录当命令执行 → 赋值为空 → 回落 JDK 8 → maven-compiler 报 `TypeTag :: UNKNOWN`；正确姿势是直接赋值：`JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home mvn test -pl agent-tavern`
- 全仓跑时 agent-scheduler `TaskSchedulerTest` 偶发 1 Error，单独重跑 18/18 全绿——异步时序竞态 flaky，与本次改动无关（tavern 不依赖 scheduler）；留观察不修

### 与蓝图的一致性

- 16 抽象第一组（character 3 类）落地，签名与 §3.1 草图一致（scopesFor / contextBuilder / create）
- D2「角色即 Agent」三要素齐备：persona→systemPrompt 翻译（FactoryTest 实证）、双 scope 记忆（MemoryTest 四向白名单）、`run(input, state)` 续跑原语（FactoryTest 跨回合测试）——跨局记忆存活测试是「记忆而非历史」设计判断的可执行锁定
- D1 零存量改动：全仓除父 POM 注册外零 diff，793 全绿即证明

---

## 14. M16.2 实现记录（2026-08-24，世界与回合）

### 交付

- **world 3 文件 + turn 4 文件**（蓝图 turn 3 类 + `TurnResult`——§3.1 草图明确背书的返回类型，超蓝图第 4 文件同 M15.1 suspendTenant 先例：领域语义正当），+28 测试全仓 821 全绿（存量 793 零影响，零存量改动继续兑现）
- **world 包**：
  - `WorldEffect`（sealed 三指令：SetFlag/ClearFlag/SetLocation，紧凑构造器校验——变更即指令 D3 的类型层落地）
  - `WorldState`（record 不可变：apply 返回新实例原状态不动；flags Map.copyOf 防外泄；`describe()` 人读快照——[world] 便签与将来回放摘要共用同一渲染）
  - `SetWorldFlagTool`（**纯指令提交器**：持有 `Consumer<WorldEffect>` sink 而非世界引用——"工具产出指令、引擎 apply"是 D3「唯一变更路径」的字面兑现；收集 sink 让工具契约零引擎可测；参数缺失/空白 → ToolException 对齐 Stage 2 错误契约）
- **turn 包**：
  - `Turn`（record：turnNo/playerInput/speakingCharacterId/responses/appliedEffects/**triggeredEventIds（M16.3 预立空字段）**/timestamp——字段位一次立满避免 M16.3 record 签名变更；`CharacterResponse(characterId, text, eventDriven)` eventDriven 恒 false 至 M16.3；`WorldEffectEntry` 包装 effect 为 provenance 留位）
  - `TurnResult`（sealed 恰两态：`Completed(Turn)` / `RoutingMiss(input, message, availableCharacters)`——蓝图 F1 语义：路由失败不烧模型调用、不推进回合数、不落账）
  - `TurnEngine`（管线四步：mention 路由[正则 `@([A-Za-z0-9_-]+)` 首个命中，Stage 12 autoDetect 分隔符边界语义] → nextTurn + 便签注入[`[world] describe\n[relationship] describer\n[player] 原文`，Stage 12 [from userId]/[handoff] 手法] → 角色 run[held AgentState 续跑] → Turn 落账；**`submitEffect` 是唯一 apply+记录点**——工具与 M16.3 事件效果同走一口；世界工具由引擎构造时注册（有引擎才有可变世界）；`relationshipDescriber` 构造注入 Function 可 null——机制 M16.2 立好、真矩阵 M16.3 接）
  - `TurnLog`（内存 append-only：unmodifiable view + 只增 append——"落账不可改"的内存契约；JSONL 首行 initial 信封 + 写后字节不变验证随 M16.4 GameStore 落地，诚实边界）
- 测试 28 个：WorldStateTest 9（三指令语义 / apply 不可变返回新实例 / flags 拷贝防外泄 / nextTurn 边界 / describe 含 flags / 空白 location 拒绝）+ SetWorldFlagToolTest 4（提交契约 / 参数三态拒绝且零提交 / 元数据）+ TurnLogTest 3 + TurnEngineTest 12（**mention 两态**：命中路由 + 无@与未知@双 miss（模型零调用+零推进+零落账三断言）；**便签注入模型实见实证**：[world] 前缀开头 + [player] 原文结尾 + 无 describer 无 [relationship] 行 / stub describer 注入实证；**工具变世界全链**：scripted toolCalls → world.flag 命中 + describe 可读 + turn.appliedEffects 记录 + 角色终答仍在；落账字段完整 / 回合推进 Turn 2 / **角色状态隔离**（lyra 请求零 marcus 历史）/ log 不可变 / 空 roster 与 null input 守卫）

### 实现期坑 3 条（记入防复发）

1. **Java 17 语法边界连踩两次**：record 解构模式（`case SetFlag(String k, String v)`，21 转正）与 switch 类型模式（`case SetFlag f ->`，21 转正 / 17 preview）先后编译失败——**17 兼容写法 = sealed + instanceof 类型模式链**（JEP 394 在 16 转正）；链尾兜底 throw 是 sealed 穷尽性无法被 17 编译器证明的诚实补丁（javadoc 注明）
2. 测试断言索引 bug 反证续跑正确：多轮后第二次模型请求是 4 条（含第一轮历史），新注入在**末条**非 index 1——断言取 last；失败本身恰证明 held AgentState 续跑工作正常
3. `mapper.readTree` 的 checked `JsonProcessingException` 忘在测试方法声明（assertThrows 的 lambda 内可抛无需处理——Executable.execute() throws Throwable）

### 与蓝图的一致性

- 交付清单对齐（world 3 + turn 3 + TurnResult）；M16.2 五条验证全过（mention 两态 / [world]+[relationship] 便签注入实证[stub describer] / 工具变世界 / 落账完整 / append-only[内存级]）
- §3.1 签名偏差诚实记录：`playTurn(String)` 而非草图 `playTurn(TavernGame, String)`——M16.2 引擎内持局状态（world/agentStates/log），M16.5 TavernGame 门面出现时提取重构、管线逻辑不变（javadoc 已注）
- TurnLog「写后字节不变」验证随 M16.4 GameStore 的 JSONL 形态整体落地（本阶段立内存 append-only 语义）
- 治理链（agent-security 依赖 + GovernedToolExecutor）推迟 M16.3 与关系限幅一起接——「依赖随用随加」纪律第三次执行（pom 未动）

---

## 15. M16.3 实现记录（2026-08-24，关系与事件）

### 交付

- **relation 4 类 + event 5 类**（蓝图 4 类 + `GameFacts`——蓝图 EventRule 行备注背书的评估入参视图）+ Turn 补字段 + 两处扩展 + pom 增补 agent-security，+37 测试全仓 858 全绿（存量 821 零影响）
- **relation 包**：
  - `Relationship`（value 0-100 + lastChangedTurn + tier 六档派生 [STRANGER/COLD/NEUTRAL/WARM/FRIEND/DEVOTED] + describe "affection 62 (WARM)"——[relationship] 便签与 GM 视图共用）
  - `RelationshipPolicy`（maxChangePerTurn 默认 5，**净变幅语义**：|本回合累计 + 本次| ≤ 上限）
  - `RelationshipMatrix`（**唯一写路径带限幅**：`ApplyResult` sealed 两态 Applied/Rejected——拒绝是正常游戏流非异常；view 未见角色 50 NEUTRAL；回合滚动重置预算；每角色独立预算；0-100 钳位但预算按**请求 delta** 计费——更严防边界操作）
  - `AdjustRelationshipTool`（matrix + currentTurn + **appliedSink 三注入**：Accepted 提交引擎落账 Turn.RelationshipChange；Rejected 返回 "[REJECTED] reason… Continue the scene naturally" **教练式文本**——模型读失败观察自愈）
- **event 包**：
  - `GameEvent`（respondCharacterId 可空——纯世界变更无戏剧响应）
  - `GameFacts`（world + relationships 快照 + turnNo 只读视图，Stage 12 ambient facts 同款；relationship(id) 默认 50）
  - `EventRule`（once 默认工厂 + repeatable 工厂）
  - `EventEvaluator`（**双路径一规则表**：`evaluate(facts)` 回合结算恰一轮批量评估；`triggerManually(eventId)` 目录引爆——**免条件检查**（角色的戏剧意图即授权）**但守 once 簿记**；坏条件 fail-soft 不炸结算；firedEventIds() 存档视图）
  - `TriggerEventTool`（**登记不执行**：事件进 pendingManualEvents 延迟到结算点统一处理——内联执行会在 run 中嵌套 run，递归风暴；未知/已耗尽 id 返回 [REJECTED] 文本）
- **TurnEngine 结算点**（管线第 3 步）：`evaluate(响应后 facts) + drain(pendingManual)` 合批 → 逐个 fireEvent：effects 走 submitEffect（同工具一口）+ triggeredEventIds 记录 + respondCharacter `run("[event] description")` 响应追加（eventDriven=true）；**事件响应中再登记的手动事件顺延下回合结算**（防风暴的戏剧化表达——"你的敬酒之后，诗人决定明晚办演唱会"）
- **Turn 补 `RelationshipChange` 字段**（characterId/delta/before/after）——**蓝图缺口修补**：D7 要求回放重演"世界+关系"，但蓝图 Turn 字段清单只有 appliedEffects 没有关系变更；引擎 `submitRelationshipChange` 与 `submitEffect` 平行成对（关系变化的回放流）
- **治理链接线**（D4 两层分工）：`CharacterAgentFactory` + `executorFactory`（Function&lt;ToolRegistry, ToolExecutor&gt;，null=直通兼容 M16.1/16.2 行为）——`GovernedToolExecutor(DefaultToolExecutor) + PermissionChecker(ToolPolicy(AUTO)) + InMemoryAuditLogger` 装配即 **GM 后台**：`audit.getByTool("adjust_relationship")` 断言 EXECUTED + args 含 characterId（每个世界/关系变更都有审计流水）
- **关系影响行为接线**：TurnEngine 内建 describer 从矩阵派生（`relationships.view(id).describe()`）——M16.2 的 Function 注入点完成使命（自定义覆盖仍支持）

### 测试 37 个

- MatrixTest 13：**限幅全语义**——单次超幅拒（±5）/ **切香肠防御**（+3 后 +3 拒，净 +6）/ 负向共享预算（-4 后 -2 拒、+1 放行）/ 精确边界（+5 放 +1 拒）/ 回合滚动重置 / 每角色独立 / 钳位全请求计费（95+5→100）/ 快照不可变 / 守卫 / policy 与 tier 边界
- AdjustRelationshipToolTest 4：成功确认+sink 提交 / 拒绝文本零提交 / 参数三态 / 元数据
- EventEvaluatorTest 12：命中触发 / 未命中 / **once 跨评估** / repeatable / **fail-soft 坏条件**（一条坏规则不炸整批）/ facts 默认关系 / **手动引爆**（免条件+守 once+联动自动路径不可再触发）/ 未知 id / 工具排队与 [REJECTED] / record 守卫
- TurnEngineM16_3Test 8 集成：关系工具全链落账（矩阵 53 + Turn.RelationshipChange before/after）/ 便签矩阵派生（"affection 62 (WARM)"）/ **限幅拒绝模型实见+自愈**（+10 拒 → 第二请求含 "[REJECTED] ±5" 失败观察 → 终答正常返回场景继续）/ **事件结算全链**（marcus 置 flag → 规则命中 → 事件 effects 应用 + lyra 强制响应 eventDriven=true + triggeredEventIds）/ **无级联**（A 效果置位 B 条件，B 本回合不触发、下回合才触发——恰一轮的时序证明）/ **手动引爆同回合结算**（工具登记 → 结算 effects+响应）/ **响应中登记顺延下回合**（防风暴核心证明：turn1 只 first、turn2 才 second）/ **治理审计 GM 后台**（getByTool 断言两工具 EXECUTED + args 可见 whose relationship）

### 实现期坑 4 条（记入防复发）

1. **Java 17 switch 模式第三次踩**（AdjustRelationshipTool 的 ApplyResult 分派）——M16.2 防复发记录本人复犯，恰好证明该记录价值；惯例升级：**tavern 模块写任何 switch 分派前先自问"17 还是 21"**，默认 instanceof 链
2. **Map.copyOf 不保序暴露 M16.2 遗留 bug**：WorldState.flags 用 Map.copyOf 顺序不稳定 → describe()/[world] 便签 flags 顺序不确定 → describeWithFlags 间歇性失败；修复 = `Collections.unmodifiableMap(new LinkedHashMap<>())` 保插入序。教训：**不可变 ≠ 顺序无关——凡是要渲染/落盘/注入 prompt 的集合，都要显式选择顺序语义**
3. readTree checked 异常第三度出现（EventEvaluatorTest ×3 + AdjustRelationshipToolTest ×2）——惯例定型：**测试方法直接 `throws Exception`**
4. 测试侧 2 处：预热矩阵 +12 被默认限幅拒（失败信息恰好证明限幅在工作——换 policy(20) 预热）；noCascade 初版误设 throw helper（已删）

### 与蓝图的一致性

- 交付对齐：relation 4 + event 4（+GameFacts 备注背书）+ 两处扩展（Factory/TurnEngine）+ Turn 补字段；M16.3 五条验证全过（限幅三态+拒绝文本进对话 / 规则命中+effects 应用+事件响应追加 / once / 恰一轮不级联 / 主动引爆）
- D4 两层分工实证：治理链（AUTO 权限+全量审计=GM 后台）与工具内数值限幅（净变幅累计）互不越界
- D5 兑现：同步评估恰一轮 + 手动登记顺延=防风暴的完整闭环；蓝图「事件触发后事件响应角色再触发」的链式风暴场景被「登记顺延」消解为跨回合戏剧节奏
- 蓝图缺口修补：Turn.RelationshipChange 使 D7「重演世界与关系」的数据流完整（M16.4 GameReplayer 直接消费）
- 蓝图偏差：EventEvaluator 双路径设计（evaluate/triggerManually 共享 once 簿记）超出蓝图描述但符合 D5 精神——主动引爆是"角色的戏剧授权"语义

---

## 16. M16.4 实现记录（2026-08-24，存档与回放）

### 交付

- **replay 包 5 文件**（蓝图 3 类 + GameReplay 步进视图 + ReplayCodec 编解码器——蓝图 D7「模式复用 Stage 14 TrajectoryCodec 纪律」的实体化：格式契约单一权威点，GameStore 写与 GameReplayer 读都走它）+ TurnEngine/RelationshipMatrix/EventEvaluator 三处恢复入口扩展，+17 测试全仓 875 全绿（存量 858 零影响）
- **`SaveGame`**（record：gameId + world + relationships + characterHistories + firedEventIds——D6 的"全套领域状态"，对照 Stage 6 run checkpoint 的形似神异在 javadoc 里写死）
- **`GameStore`**（save/load 双文件布局：`{root}/{gameId}/save.json` 终态快照 + `turn-log.jsonl` 历史流——**快是恢复路径、流是复盘路径，重演终态==存档终态让两文件互查**；save 全量写但**字节在追加下稳定**：同 turns 同字节，后次 save 是前次的字节前缀延长，已写行永不变）
- **`ReplayCodec`**（手写 JSON 树：world/relationship/message/toolCall/effect/turn/initial 信封——**零注解魔法**，enum switch + instanceof 链全 17 兼容；**v1 诚实边界：多模态 parts 消息存档即 IAE**，fail-loud 不静默丢；tool 消息的 toolCallId/name 完整保留，重建后配对不断）
- **`GameReplayer` + `GameReplay`**（load 校验 + 步进视图：**stateAt(n) 走录重演**——从 initial 逐 turn nextTurn + apply(effects) + apply(relationshipChanges)，纯数据流推导，模型零调用规则零重评（记录即真相）；**describeTurn** 人读复盘[谁说了什么/world 改了什么/关系动了多少/触发了什么]；ReplaySnapshot.relationship(id) 与 GameFacts/Matrix.view 同款默认 50 语义——三处默认语义统一）
- **三处恢复入口**（load 路径的部件）：TurnEngine 记录 initial 信封（initialWorld + 构造时刻关系快照）+ `characterHistories()` 存档视图 + `restoreHistories()` 历史注入；`RelationshipMatrix.restore(map)` **绕限幅**（系统操作非模型回合动作，预算清零）；`EventEvaluator.restore(set)` once 簿记续接

### 测试 17 个

- GameStoreTest 8：目录布局 / **round-trip 全等**（世界 record equals + 关系 + 全部角色历史[含工具调用消息] + fired 事件）/ 首行 initial 信封 / **写后字节不变**（save→bytes1→再玩两回合→save→bytes2，逐字节断言 bytes1 是 bytes2 前缀）/ 缺档 NoSuchFileException / **续局对话连续**（蓝图 T6：重载引擎续跑 turnNo=2 接续 + 恢复的历史对模型可见 + 恢复的关系进 [relationship] 便签 + 限幅预算新回合刷新）/ **once 簿记跨重载**（存档前触发过的事件，重载后同条件不再触发）/ 多模态历史 fail-loud
- GameReplayerTest 9：**stateAt 时点正确**（蓝图 T7 精确对应：t0 空世界 50 关系 / t1 flag+关系 / t2 关系累加 flag 未至 / t3 终态）/ **重演终态==存档终态**（世界+关系双断言——两文件互查闭环）/ describeTurn 人读 / 越界拒绝 / 完整性三态带行号（缺 initial 信封 / turnNo 跳号"line 3: expected turnNo 2" / 坏 JSON"line 2"）/ 空 log 拒绝 / 最小合法 log 加载

### 实现期坑 2 条（记入防复发）

1. **快照默认语义漏挖**：stateAtTimePoints 初版裸 `relationships().get("marcus")` NPE——初始快照只含已跟踪角色（跟踪=被改过），"未跟踪=中性 50"的默认语义在 GameFacts/Matrix.view 都有而 Snapshot 裸 map 没有；修复 = ReplaySnapshot.relationship(id) 补齐，**三处默认语义统一**。教训：一个领域默认值（未见过的角色=50）要跟随该类型的**每一个读取视图**
2. ObjectNode 忘 import + mapper::createTextNode 不存在（ArrayNode.add(String) 才是正路）——小错快修

### 与蓝图的一致性

- 交付对齐：蓝图 3 类 + GameReplay 视图 + ReplayCodec（D7 TrajectoryCodec 模式复用的实体化，超蓝图第 5 文件同 M15.1/M16.2 先例：机制正当）；M16.4 四条验证全过（round-trip 续跑连续 / 逐 Turn 步进 / 完整性校验带行号 / 重演终态==存档终态）
- D6 兑现：存档=局快照双文件；恢复部件（restore×3）绕限幅的系统操作语义与 D4 的模型操作限幅语义分层清晰
- D7 兑现：走录不重演（stateAt 纯数据流）；append-only 字节稳定（前缀断言）；信封 kind 区分 initial/turn；fail-loud 带行号（对齐 Stage 14）
- M16.2 的 TurnLog「写后字节不变」遗留验证条目随本阶段落地（内存 append-only → 文件字节级前缀稳定）

---

## 17. M16.5 实现记录（2026-08-24，装配与收口）+ Stage 16 总验收

### 交付

- **`TavernGame` 门面 + Builder**（M16.5）：一个 Builder 定义一局游戏（model/memory/roster/world/policy/rules/governance/storeRoot 全带默认），`build()` 新局、`load()` 续局、`governance(auditLogger)` **一行装配 GM 后台**（GovernedToolExecutor + ToolPolicy(AUTO) + 审计）；门面路径恒有 matrix+evaluator（默认 DEFAULT policy / 空规则表）；`replay()` 内存视图 + `replayFromDisk()` 跨会话完整历史
- **`TurnEngine.resume` 工厂**：拆分 **game-initial（日志第 1 行）与 current-world（运行状态）两个语义**——新局时相同、续局时分离；`load()` 双读（save.json 终态 + turn-log.jsonl 完整历史）恢复：旧 turns 预填 turnLog、真 initial 进日志信封、存档终态作当前世界——**续局引擎的 save() 仍写出从 turn 1 起的连续日志，replay() 覆盖全部回合**
- **`TavernGameExample`**（examples 模块 + agent-tavern 依赖）：蓝图 §6 的 T0-T7 全剧本，MockModelClient 十响应全链零 LLM 实跑：三角色人格（marcus/lyra/brawn 各自台词）/ T2 关系 +3 / T3 世界 flag / **T4 事件 improvisation 结算触发（lyra 事件响应 + crowd=cheering 效果）**/ **T5 +10 限幅拒绝自愈（"trust is earned sip by sip"）**/ T6 save-load 续局（lyra 记得歌，turn 6 接续）/ T7 完整回放 describeTurn × 6 + stateAt(3) 时点 + **重演终态==存档终态 YES** / GM 审计 3 条全 EXECUTED
- 测试 +10（TavernGameTest）：门面全链 / 默认 matrix+evaluator / builder 校验三态 / [relationship] 默认注入 / governance 一行审计 / save-load 续局（turnNo 接续 + 恢复历史对模型可见）/ 无 storeRoot 拒绝 + load 双守卫 / replay 双视图 / 无治理兼容

### 示例实跑揪出真缺口（本阶段最有价值的坑）

初版续局引擎的 initial=存档终态、turnLog 只有恢复后新回合 → 续玩后 save() 覆盖写日志（initial 行=恢复点 + turnNo 6 一行）→ `replayFromDisk` 完整性校验 **"line 2: expected turnNo 1, got 6" fail-loud 拦截**。M16.4 的防坏文件校验反过来抓住了 M16.5 装配的真实语义错误——**fail-loud 纪律的自我证明**。修复即 resume 工厂的双语义拆分（上节）。教训：**"存档终态"与"日志起点"是两个角色，续局装配时必须分别携带**。

小坑 2 条：CharacterAgentFactory 四参构造是 `(ModelClient, CharacterMemory, int, Function)` 传 MemoryStore 不匹配；builderValidation 测试初版断言写反（三件齐全不抛异常）——测试侧 bug。

### Stage 16 总验收（对照 18 周规划原文六条 + 「需要支持」八项）

```text
规划原文：完成一个最小酒馆场景：
1. 玩家与多个角色对话     -> ✅ mention 路由 + 三角色（示例 T1-T4 先后与 marcus/lyra/brawn 对话）
2. 角色拥有不同人格       -> ✅ persona→systemPrompt 翻译（M16.1 捕获模型实见断言；示例三角色三套台词）
3. 对话会改变关系值       -> ✅ adjust_relationship 工具 + 净变幅限幅（示例 T2 +3 → 53；T5 +10 被拒）
4. 关系值会影响后续行为   -> ✅ [relationship] 便签每回合注入（M16.3 派生注入实证）+ 阈值事件规则就位
                             （confession ≥80 / hostility ≤20 在示例 T0 装配）
5. 世界事件可以被触发     -> ✅ 规则命中（示例 T4 improvisation 结算触发 + 事件响应）+ TriggerEventTool 主动引爆
6. 一局对话可以保存和回放 -> ✅ save/load 续局（T6 turn 6 接续、lyra 记得歌）+ GameReplay 逐回合步进
                             + stateAt 时点 + 重演终态==存档终态（T7 输出 YES）

「需要支持」八项：多角色 ✓ / 角色人格 ✓ / 世界状态 ✓ / 回合推进 ✓ / 事件系统 ✓ /
角色关系 ✓ / 长期记忆 ✓（M16.1 双 scope：agent:{charId} 跨局 + session:{gameId} 局内）/ 状态回放 ✓
```

### Stage 16 收官数字

- 五里程碑全过：M16.1 角色域（+19）/ M16.2 世界与回合（+28）/ M16.3 关系与事件（+37）/ M16.4 存档与回放（+17）/ M16.5 装配收口（+10）= **agent-tavern 111 测试**
- 全仓 885 测试全绿（Stage 15 收官 774 + tavern 111），存量零影响
- **零存量改动兑现**：除父 POM 两处注册 + examples pom 依赖外，agent-core/agent-memory/agent-security 等存量模块零 diff——蓝图 D1「当一个新场景能零存量改动落地时，才证明之前的抽象是对的」的完整证明
- 三处有意不复用全部经测试锁定：RunManager（D6）/ EventBroker（D5）/ Workflow（D8）
- 文章素材六篇就位（蓝图 §11），《游戏 Agent 为什么不能只有一个 Chat Loop》总纲待写
