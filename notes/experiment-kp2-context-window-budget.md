# 实验记录：KP2 窗口预算——四本账与成对裁剪（2026-09-12）

> 性质：KP2（Token Budgeting）的实验收口笔记，对应提交 `0b00e33`（`ContextWindowBudget` / `ContextWindowEnforcer` / `HandoffInputFilter`，测试 22 个新增全绿）。
> 前置：KP1 Handoff 心智模型（决策 24：handoff = 循环内换 `currentConfig`）、KP3 缓存约束（决策 26：前缀稳定性，改写历史有缓存代价）。
> 关联：`learning-path-2026-09-knowledge-points.md` §KP2、`architecture-stance-decision-24-handoff-as-loop-config-swap.md`、`stage-18-article-5-token-budget.md`。

---

## 一图收口：窗口是预算，不是容器

```
totalWindowTokens（模型的物理窗口）
├── systemReserve      system prompt（稳定，不随轮次增长）
├── toolSchemaReserve  工具/handoff 定义（每配置稳定）
├── outputHeadroom     模型输出保留（输入永远不占）
└── historyBudget()    剩下的才是历史  ←唯一可裁的账本
```

心智模型：把窗口当成一笔必须先分账的预算。不先给 system/tools/output 三本账记账，你就不知道历史还能花多少。四本账是产品决策不是技术参数——「超支截谁」是一道产品姿态题：截历史=失忆但对话能继续；截工具=残废 agent 几乎总是错；截输出保留=答案被静默截断难调试；拒服务=诚实但伤 UX。v1 默认：裁最旧历史，对话永不因预算中断。

## 关键认知：四个一手工程事实

### 1. 裁剪单位是逻辑单元，不是消息

`ContextWindowEnforcer.dropOldestUntilFits` 裁的是「逻辑单元」：单条消息算一个单元，但 ASSISTANT（带 tool_calls）+ 紧随其后的所有 TOOL 消息是一个不可拆的单元。为什么：所有主流供应商的 API 都要求 tool result 消息必须紧跟其 assistant tool_call 消息，孤儿 TOOL 消息直接被 API 拒收。裁掉 assistant 保留孤儿 tool result = 下一请求 400。测试 `ContextWindowEnforcerTest` 10 用例钉死：`toolCallPair_droppedTogether`（成对不可拆）、`overBudget_oldestDroppedFirst`（从最旧开始）、`lastMessageNeverDropped`（最后一条永不裁）、`withinBudget_returnedUnchanged`（不超限零动作）。

### 2. `forWindow` 默认配比及其理由（10/15/20/55）

`forWindow(total)`：system 10%、tools 15%、output 20%、history 55%。估算依据（写在 javadoc 里）：system prompt 很少超 2–4K；单工具 schema 约 300–500 tokens，15% 可容纳约 40 个工具；128K 窗口下 20% output = 25.6K 答案空间；剩下 55% 历史是唯一可压缩可截断的正确裁剪对象。preset：`window128k()` / `window32k()` / `window8k()`。配比是观点（opinionated）不是真理，`of(...)` 显式覆盖。

### 3. chars/4 启发式与「一致的错误」哲学

`estimateTokens` 用 chars/4（内容长度 + toolCalls 的 name/arguments），无 tokenizer 依赖，与 agent-memory 的 `ContextBudget` 同款。哲学：预算器要的是「对所有内容同向偏差的一致估算」，不是精确 token 数——四本账内部自洽比绝对准确更重要，跨模块同款启发式保证两侧对「历史有多贵」判断一致。

### 3'. 预算器不问模型要窗口大小

`ContextWindowBudget` 是宿主显式声明的（128k/32k/8k preset 或 `of`），不从 ModelClient 反查。理由：窗口是宿主的部署决策——同一份代码可能接 32k 小模型和 128k 大模型，宿主知道接的哪个；框架不猜配置，宿主对预算负责。（自测点：想想如果预算器自动从模型拉窗口大小，会发生什么？——每个循环 turn 都多一次模型元信息调用，且路由模型切换时预算抖动，见 KP4 路由与预算的交互。）

### 3''. Enforcer 是装饰器不是劫持者

`ContextWindowEnforcer` 是 `ContextBuilder` 装饰器：delegate 先跑（memory builder 照常注入核心/归档记忆层），enforcer 只在最后做预算执法。UI 乙女/梦女向升级同款思路：不动引擎，包一层。`withDelegate_delegateOutputIsEnforced` 钉住「装饰器链生效」，`getDelegate_nullWhenNoDelegate` 钉住裸模式（直接吃 state.messages 再执法）。装上即执法、不装即全量。

### 4. HandoffInputFilter：只裁请求，AgentState 是审计账本

Decision 24 P2 的落点。`HandoffSpec` 新增第 4 个成员 `inputFilter`（null 归一化为 `IDENTITY` 全量携带）。过滤器作用在模型请求边界：loop 的 `buildRequest` 在 ContextBuilder 之后应用 `activeInputFilter.filter(context, handoffFrom, config)`——改的是「下一跳请求看到的上下文」，`AgentState.messages` 永不被重写。身份是配置，历史是事实：state 是审计账本，请求是销售话术。三种携带策略：

| 策略 | 语义 | 适用 |
|------|------|------|
| `IDENTITY`（默认） | 全量携带 | 同任务接力，A 完全信任 B |
| `keepWithin(budget)` | 复用同一套 trim 规则裁到目标预算 | 跨 agent 委托，目标窗口更小 |
| `lastTurn()` | 从最后一条 USER 起（含 handoff tool 对） | 任务移交非会话移交：B 只需当前任务 |
| 摘要携带（KP1 三策略之三） | 不是 filter，走目标 ContextBuilder 压缩 | 长会话跨 agent，需要冻结摘要（决策 26 前缀稳定） |

lastTurn 的细节：找不到 USER 时保留最后一条（请求永不空）；`HandoffInputFilterTest` 4 用例钉死：`identity_carriesFullHistory`、`keepWithin_trimsRequest_notState`（请求被裁而 state 原样）、`lastTurn_keepsFromLastUser`、`lastTurn_noUser_keepsLast`。

## agent4j 对照：这笔账在架构里的位置

```
runLoop
 └── buildRequest(config, state, handoffFrom, activeInputFilter)
      ├── delegate.build(config, state)          ← memory builder 照常注入
      ├── trimToBudget(context, historyBudget)   ← Enforcer 预算执法（若挂载）
      └── inputFilter.filter(context, from, to)  ← handoff 跳边界时再裁（若有）
```

和 KP3 的接头：裁历史会破坏前缀稳定性（决策 26），但 v1 的裁法是从头丢（drop-oldest），破坏的是最旧前缀而非最贵前缀。E3 的发现反过来约束这里：如果未来做 summary-carry，摘要要冻结而非每轮重生成——冻结摘要保留 ~24% 缓存价值。KP2 与 KP3 在「改写历史」这一动作上相遇，一个管「裁多少」、一个管「改写的代价」。

## 计划 vs 实际

| 步骤 | 计划验收 | 实际 | 判定 |
|------|----------|------|------|
| 四本账模型 | ContextWindowBudget record 落地 | 落地，compact 构造器校验负数与超支 | 成 |
| 预算执法 | Enforcer 装饰器 + pair-preserving trim | 落地，22 测试全绿 | 成 |
| Handoff 三携带 | HandoffInputFilter 三策略 + state 永不重写 | IDENTITY/keepWithin/lastTurn 落地；摘要携带显式不做（走 ContextBuilder，不发明第二条压缩路径） | 半 |
| （计划外） | — | window128k/32k/8k preset、估算工具方法 | 白得 |

## 诚实边界（v1）

- 预算从宿主声明，不从模型反查（设计选择，见认知 3'）。
- chars/4 启发式，无 tokenizer；中文估算偏保守（中文 1 char ≈ 1 token，chars/4 对中文低估 ~4x）——中文重负载宿主应自行收紧 budget 或换精确 tokenizer。
- summary-carry 不做（需要压缩器写冻结摘要，走目标 ContextBuilder，不在此发明第二条压缩路径）。
- 趁手缺一把刀：没有「系统级预算审计事件」——裁剪只打 WARN 日志，没有 AgentEvent。审计线里「这次请求裁了多少」目前要翻日志才能知道。
缺一把刀 2：无每 agent 预算独立配额——`AgentConfig.contextBuilder` 挂 Enforcer 是 per-config 的，但 handoff 后「A 的预算 vs B 的预算」切换时 historyBudget 突变，loop 不校验连续性（B 的预算更小时靠 keepWithin 兜底，靠宿主配置正确）。
- 修剪发生于模型请求边界，TCP 请求体大小无限制（模型供应商会收下超长请求后按自己的截断规则处理）。

## 三段式收口

**做对了什么**：四本账一次性把「窗口是预算」的心智模型写进代码（record + 装饰器 + 三携带策略），裁剪以逻辑单元为单位保住 tool-call 不变式，state 与请求分离保住审计账本，22 个新测试钉死；与 KP3 在「改写历史」上会师，一个管裁多少一个管改写代价。

**缺什么**：中文估算偏差 4x（chars/4 对中文低估）；无预算审计事件（裁剪无 AgentEvent，指标线看不见）；无每-agent 预算连续性校验（handoff 后预算突变靠宿主自觉）；summary-carry 待压缩器机制化后接入。

**怎么做**：短期——重读 `stage-18-article-5-token-budget.md` 对照本文闭环自测；中期——预算审计事件接入 KP7 评估指标线（裁剪次数/裁掉 tokens 是 drift 指标的原料）；中文 tokenizer（jieba 级）在 Moonlit 中文场景落地前接入。

## 思考题（留下轮）

`keepWithin` 与 `lastTurn` 在「B 的预算比 A 小」场景下行为差异：keepWithin 从最旧开始丢，lastTurn 只留最后一个任务。设想一个 30 轮长会话，A（128k 预算，记忆系统挂载）向 B（32k 预算，无记忆）handoff。两次操作分别会让 B 看到什么？分别丢失什么信息？哪种丢失对「任务执行」致命，哪种丢失对「用户体验」致命？——提示：回到四本账，想想 B 的 toolSchemaReserve 占比变化对 B 可见工具面的影响。
