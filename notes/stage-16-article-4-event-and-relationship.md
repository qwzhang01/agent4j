# 游戏 Agent 的事件系统和关系系统

> 配套蓝图：[architecture-stage-16.md](architecture-stage-16.md) D4 / D5（治理即平衡，事件是同步规则评估）· 对应实现：`relation/` 四类、`event/` 五类、`TurnEngine` 结算点
> 上一篇：[stage-16-article-3-turn-drives-state.md](stage-16-article-3-turn-drives-state.md)
> 状态：✅ Stage 16 已完成
> 这是 Stage 16 系列的第 4 篇：数值护栏和剧情判定，是世界层真正开始「有后果」的地方。

---

## 1. 我今天要解决什么问题

上一篇把对话接进了工具和 apply 点。工具能改关系、能引爆事件之后，立刻出现两个游戏设计事故：

```text
AI 酒保一回合爱上玩家     模型连调 adjust_relationship，好感从 50 冲到 100
一句台词触发事件风暴       A 置位 B 的条件，B 再置位 C，同一回合连锁爆炸
```

这两件事在 Chat Loop 里不存在——输出是文本，文本没有数值，也没有规则表。世界层一旦有可写状态，就必须回答：谁能写、写多少、写完会不会立刻再写。关系系统和事件系统是对这两个问题的领域回答，不是「再加两个工具」那么简单。

---

## 2. 为什么会有这个需求

关系如果只是一个整数，模型会把它当成分数。ReAct 循环允许一回合多次工具调用。按「单次 |Δ|≤5」限幅，`+1` 连调十次就能 +10；`+3` 四次就是 +12。蓝图把这种绕法叫做切香肠（salami-slicing）。「AI 酒保一回合坠入爱河」不是段子，是限幅粒度选错时的必然结果。

事件如果接 Stage 7 的 `EventBroker`，语义对不上。`EventBroker` 是 `fire(key) → resume(runId)`：机制层「何时继续跑」。游戏事件是回合结算点的剧情判定：条件成立 → 应用 effects → 指定角色当场说话。它没有 run，也不该异步。硬复用会把剧情绑死在 workflow 生命周期上，而游戏回合根本不产生 workflow run。

两个需求共享一个病灶：**领域规则必须和模型的非确定性隔开。** 模型可以提议，规则决定接不接、接完停不停。

---

## 3. 它解决了什么问题

关系侧：

```text
唯一写路径     RelationshipMatrix.apply
净变幅限幅     |本回合累计 + 本次| ≤ maxChangePerTurn（默认 5）
拒绝即游戏流   ApplyResult.Rejected → 工具返回 [REJECTED] 文本
钳位与计费     0-100 钳位在验收后，预算按请求 delta 计费（更严）
回合滚动       新 turnNo 清空每角色预算；角色之间预算独立
系统恢复       restore(map) 绕限幅——存档是系统操作，不是模型动作
```

事件侧：

```text
同步评估       EventEvaluator.evaluate(GameFacts)，在 playTurn 内部
恰一轮         引擎每回合只调一次 evaluate，本批 effects 不二次评估
once 默认      剧情事件默认一生一次；repeatable 是显式工厂
手动引爆       triggerManually 免条件、守 once；工具登记不执行
fail-soft      单条坏条件当不匹配，不炸整批结算
```

两套规则都把「拒绝」做成正常流。模型读到失败观察，按 Interaction rules 第三条自然演下去——`TavernGameExample` T5 的台词是 `"Trust is earned sip by sip, not gulped."`

---

## 4. 核心抽象和架构

### 4.1 关系：值、档位、政策、矩阵

`Relationship` 是 record：`value` 0-100，`lastChangedTurn`，`tier()` 六档：

```text
<20 STRANGER   <35 COLD   <55 NEUTRAL   <75 WARM   <90 FRIEND   ≥90 DEVOTED
初值 50 NEUTRAL，describe() = "affection 62 (WARM)"
```

`RelationshipPolicy` 只有一个数：`maxChangePerTurn`，默认 5。数值平衡的 SSOT 就这一行。`RelationshipMatrix` 持政策、当前值、本回合已用预算、上次见到的 turnNo。`view(id)` 未见角色返回 `Relationship.initial()`——50。这个默认必须跟着每一个读取视图走：`GameFacts.relationship`、`ReplaySnapshot.relationship` 都补了同样语义，裸 `map.get` 会 NPE。

`apply` 的顺序是纪律：

```text
rollTurnIfNeeded(turnNo)     换回合则清预算
projected = already + delta
|projected| > limit          → Rejected（附 alreadyApplied 与原因）
否则写入钳位后的值，预算记 projected（请求量，不是钳位量）
```

`clampingChargesFullRequest`：95+5 → 值变成 100，但预算按 +5 记。不能靠打边界「白嫖」剩余额度。

### 4.2 关系工具：教练式拒绝

`AdjustRelationshipTool` 成功返回 `"Relationship with marcus: 50 -> 53 (NEUTRAL)."` 并 `appliedSink.accept`。失败不抛：

```text
[REJECTED] net change |10| would exceed this turn's limit of ±5
(already applied 0 this turn). Continue the scene naturally instead of forcing the change.
```

`AdjustRelationshipToolTest.rejectedTextNoSubmit` 断言拒绝零提交。`TurnEngineM16_3Test.limitRejectionSelfCorrects` 走全链：scripted +10 → 第二次模型请求含 `[REJECTED] ±5` → 终答正常返回，场景继续，矩阵值不变。

### 4.3 事件：事实、规则、判定器

`GameEvent(eventId, description, respondCharacterId)`。`respondCharacterId` 可空：纯世界变更可以没有戏剧台词。

`EventRule.once(...)` / `EventRule.repeatable(...)`。条件是 `Predicate<GameFacts>`。`GameFacts` 冻结 world + relationships + turnNo，规则不碰实时可变状态——和 Stage 12 ambient facts 同款。

`EventEvaluator` 一张规则表、两条入口、一份 `firedEventIds` 簿记：

```text
evaluate(facts)          按规则顺序走一遍，once 已触发则跳过
                         条件抛异常 → matches=false（fail-soft）
triggerManually(eventId) 免条件（戏剧意图即授权），仍守 once
                         未知 id 或已耗尽 → Optional.empty()
restore(set)             存档续接 once 簿记
```

`TriggeredEvent(event, effects)` 是结算单元。引擎处理它：effects 走 `submitEffect`，id 记入 `triggeredEventIds`，有响应角色则 `run("[event] " + description)`，`eventDriven=true`。

### 4.4 恰一轮和登记顺延

防风暴不在 Evaluator 里做递归检测，而在引擎调用纪律：

```text
batch = evaluate(currentFacts()) + drain(pendingManualEvents)
for each triggered: fireEvent(...)     // 不再 evaluate
事件响应里再 trigger_event → 进下一回合的 pending
```

`TurnEngineM16_3Test.noCascadeWithinTurn`：规则 A 的效果置位规则 B 的条件，B 本回合不触发，下回合才触发。`manualTriggerAtSettlement` 锁主动引爆与自动路径同批处理：工具登记的事件在本回合结算点 fire，effects 和响应都在这一拍完成。`manualTriggerDuringEventResponseDefers`：turn1 只有 first，turn2 才 second——「你的敬酒之后，诗人决定明晚办演唱会」。戏剧节奏和防风暴是同一条纪律。

`EventEvaluatorTest.manualTriggerSemantics` 把「免条件 + 守 once + 联动自动路径不可再触发」收在一处：手动引爆过的 eventId，随后 `evaluate` 即使条件仍成立也不会再发。双路径共享簿记，是超出蓝图字面、符合 D5 精神的实现——主动引爆是戏剧授权，不是另开一套可刷的旁路。

`TriggerEventTool` 未知或已耗尽返回 `"[REJECTED] No triggerable event '...' (unknown id, or it already happened)."`。登记成功则告诉模型：后果在本回合结束时展开。

### 4.5 双层治理

`CharacterAgentFactory` 的 `executorFactory` 可空：null = 直通（M16.1/16.2 兼容）；非空则 `SimpleAgent(config, new ReActAgentLoop(factory.apply(registry)))`。门面 `governance(auditLogger)` 装配：

```text
GovernedToolExecutor(DefaultToolExecutor)
  + PermissionChecker(ToolPolicy(AUTO))
  + AuditLogger
```

三工具全 AUTO：角色回合里改世界是合法动作，不走人工审批。审计全量：GM 后台能查「这一局世界被改了哪些地方」。数值限幅不放进治理链——治理没有「每回合净变幅」这种领域概念，硬塞会污染 Stage 9。

---

## 5. 一次完整数据流

示例 T0 装配的三条规则，和关系限幅一起构成完整因果：

```text
confession     relationship(marcus) ≥ 80 → marcus 告白（once）
improvisation  bard-mood=lively ∧ turn≥4 → lyra 即兴 + crowd=cheering
hostility      relationship(brawn) ≤ 20 → brawn 敌意 + conflict=brewing

T2  +3 → marcus 53（NEUTRAL）  confession 远未到
T3  set bard-mood=lively      improvisation 差回合数
T4  turn≥4 且 flag 仍在       improvisation 结算触发
                              lyra 事件响应 + crowd=cheering
T5  模型尝试 +10
                              Matrix Rejected，值仍 53
                              模型读到 [REJECTED]，改口 sip by sip
```

关系影响后续行为有两条路：每回合 `[relationship]` 便签把档位喂给模型（软影响），阈值规则把档位变成剧情（硬影响）。示例里 confession / hostility 在 T0 就位，T1-T7 没有走到 80 或 20——规则在，触发是数据问题，不是缺设计。`relationshipNoteDerivedFromMatrix` 锁住软影响这条线。

---

## 6. 最小代码或实验

限幅三态是关系系统的核心实验（`RelationshipMatrixTest`）：

```text
singleOversizeRejected         +10 或 -10，一次就拒
accumulatedLimitBlocksSlicing  +3 后再 +3 → 拒（净 +6）
exactLimitBoundary             +5 放行，再 +1 拒
negativeSwingsShareBudget      -4 后再 -2 拒，+1 仍可（净变幅不是绝对值叠加方向）
newTurnResetsBudget            换 turnNo，预算清零
budgetPerCharacter             marcus 用满不影响 lyra
```

切香肠那条最重要：单次限幅防不住，累计净变幅才防得住。

事件侧最小实验是 `EventEvaluatorTest.onceSemantics` + `throwingConditionIsFailSoft` + 引擎的 `noCascadeWithinTurn`。三条分别锁：一生一次、坏规则不炸场、本批不级联。缺任何一条，剧情要么能刷、要么能把整局打崩、要么能在一回合里把故事讲完。

---

## 7. 常见误区

1. **「限幅看单次调用」** —— 模型会化整为零。政策必须按回合累计净变幅。
2. **「超幅抛异常」** —— 炸穿 ReAct loop 会让角色突然变成系统错误。拒绝是台词的一部分。
3. **「事件接 EventBroker」** —— 那是 resume(runId)，不是剧情事实。形似神异，Stage 12 D3 同款：复用思想，不复用绑 run 的实现。
4. **「条件命中就立刻再评估」** —— 级联是事件风暴。v1 语义是恰一轮；级联规则留给 v2。
5. **「手动引爆也要过条件」** —— 角色举杯祝酒，戏剧意图就是授权。once 仍要守，否则同一敬酒能刷全场欢呼。
6. **「把限幅做成 ToolPolicy」** —— 治理链不懂「本回合已用 3 点」。领域政策留在 Matrix，审计留在治理。

---

## 8. 和相邻概念的区别

三个叫 event 的东西，必须能当场分开：

```text
GameEvent           数据：发生了什么、谁被牵动、带哪些 WorldEffect
EventBroker 事件    机制：fire → resume 一个 workflow run
MemoryType.EVENT    记忆：把发生的事记进角色印象（跨局可选）
```

关系也不是「又一个世界 flag」。flag 是命名离散值（`bard-mood=lively`），关系是有预算的连续值，带档位派生和每角色每回合额度。用 flag 模拟好感，会失去限幅和 tier 便签；用关系模拟世界，会把「大堂着火」收成对某个角色的好感，语义错位。

和 Stage 15 的审批对照：企业退款可能要人工批（权限档不是全 AUTO）。游戏三工具全 AUTO，因为「准不准」在玩法上已允许，真正危险的是「对不对」（幅度、once、恰一轮）。同一条治理链，Profile 选择不同的档位和不同的领域校验。

---

## 9. 我的设计判断

关系系统真正保护的不是数字，是**节奏**。好感可以涨，但不能在一拍里涨完。限幅把 LLM 的热情翻译成游戏节拍：sip by sip。把这层做成政策对象而不是魔法数，是为了让「平衡」可测、可存档、可换（`customPolicy` 测试存在就是这个原因）。

事件系统真正保护的是**收尾**。本回合发生的事本回合对玩家可见（事件响应当场说话），但对规则表不可见（不二次评估）。可见与不可见的不对称，就是防风暴。把「登记不执行」写进工具而不是约定，是因为约定挡不住模型在响应里再调一次 `trigger_event`。

蓝图缺口也值得记：初稿 `Turn` 只有 `appliedEffects`。没有 `RelationshipChange`，D7「重演世界与关系」就是空话。实现期补字段，比事后在回放器里猜矩阵更诚实。学习时看到 `Turn.RelationshipChange`，要知道它不是装饰，是回放流的另一半。`relationshipToolFullChain` 把矩阵终值 53 和 Turn 里的 before/after 对上，就是在锁「写路径和落账是同一件事」。

---

## 10. 面试表达

> 「游戏 Agent 的关系系统和事件系统，是世界层的两条护栏。关系不按单次限幅，按每角色每回合净变幅累计，默认 ±5，切香肠的第二次 +3 会被拒；拒绝返回 [REJECTED] 文本，模型自愈，不炸 loop。事件不是 EventBroker，是结算点同步规则评估：恰一轮、once 默认、手动引爆免条件但守簿记、登记不执行。治理链管准不准（三工具 AUTO + 全量审计 = GM 后台），领域政策管对不对。AI 酒保一回合爱上玩家，是限幅粒度选错，不是模型太热情。」

---

## 11. 下一篇连接什么

规则让一局游戏有后果。下一问是：这些后果能不能原样再看一遍。存档是局快照，回放是走录，两者都不是 run checkpoint，也都不能重跑模型。调试游戏 Agent，靠的是记录即真相。

→ [stage-16-article-5-replay-debug.md](stage-16-article-5-replay-debug.md)
