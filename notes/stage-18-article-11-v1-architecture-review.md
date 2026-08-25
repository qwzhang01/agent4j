# Java Agent Framework v1.0 架构复盘

> 配套蓝图：[architecture-stage-18.md](architecture-stage-18.md) §13 收官对照 / §1 命题递进 · 实现基线：全仓 **1148** 测试全绿，`agent-observability` 125 · 验收剧本：[ObservabilityExample.java](../examples/src/main/java/io/github/qwzhang01/agent/examples/ObservabilityExample.java)
> 上一篇：[stage-18-article-10-failure-into-eval.md](stage-18-article-10-failure-into-eval.md)
> 状态：✅ Stage 1–18 全部完成（2026-08-16 ~ 08-25）。**v1.0 = Stage 1–18，没有 Stage 19。**
> 这是 Stage 18 系列的第 11 篇，也是 18 周学习路线的收官。前 10 篇拆运营层；本篇收回整条 Runtime，并兑现 M9 面试叙事。

---

## 1. 我今天要解决什么问题

18 周规划的最后一块不是再加一个 Profile，是给已经能跑的 Runtime 发上岗证。前 17 个阶段回答「能不能跑」：

```text
1–2   能不能决策     ModelClient / ReActAgentLoop
3     能不能扩展     Plugin SPI
4·9   能不能安全     Sandbox + GovernedToolExecutor
5–8   能不能持久     Workflow / Checkpoint / Scheduler / Memory
10–12 能不能协作     MCP / A2A / Channel / Ambient
13    能不能产品化   YAML / PromptManager
14    能不能学习     Trajectory / DPO
15    能不能进企业   归属层
16    能不能演戏     世界层
17    能不能写代码   变更层
```

Stage 18 回答「**敢不敢上线运营**」：每次执行看得见，每个 token 记得上账，每次修改经得起回归，每个版本退得回去。

本篇要一次讲清三件事：这套东西在顶层长什么样；为什么三个领域 Profile 加一层运营，不需要第四个 Runtime；以及面试时 5 / 15 / 30 分钟分别讲什么。最后一句必须说死：**v1 在 Stage 18 收口，没有 Stage 19。**

---

## 2. 为什么会有这个认知冲突

最容易说错的一句话是：「再做一个 Stage 19，把 OTel、LLM-as-judge、百分比灰度和前端 SDK 补齐，才叫 1.0。」

那是把「规划里出现过的词」当成「v1 的定义」。v1 的定义在 18 周规划总目标：同一套 Runtime 支撑决策、扩展、安全、持久、协作、产品化、学习，再加三个领域 Profile，再加可观测与成本治理。这些在 Stage 18 收口日已经逐项打勾（蓝图 §13）。OTel、judge、百分比灰度、前端仪表盘是蓝图 §12 明文的有意不做——先稳定自有接口，adapter 是薄壳。

第二个冲突是「三个 Profile 是不是三个框架」。企业要租户，酒馆要世界，编码要补丁，表面上像三条产品线。证据链给出相反结论：机制层（`agent-core` 的 loop / `ModelClient` / `Tool`）一行不改，领域语义叠在各自模块里。Stage 15 加了两个枚举，16 / 17 / 18 连续零存量改动。

第三个冲突是「运营层是第四个 Profile」。不是。Profile 引入领域模型（`Tenant` / `WorldState` / `Patch`）。运营层给**所有** Profile 发同一张上岗证：指标、预算、路由、版本、评估。`agent-observability` 不依赖 enterprise / tavern / coding，只依赖 core + trace-export。

---

## 3. 它解决了什么问题

v1.0 的交付可以压成一张职责图：

```text
                    运营层  agent-observability（18）
                    指标 / 预算 / 路由 / 评估 / 版本
                                    │ 装饰器挂边界
        ┌───────────────────────────┼───────────────────────────┐
        │                           │                           │
   归属层 15                   世界层 16                   变更层 17
   agent-enterprise            agent-tavern                agent-coding
   谁在问 / 哪个租户 / 花谁的钱   角色 / 后果 / 一局历史     补丁 / 白名单 / 有界修复
        │                           │                           │
        └───────────────────────────┼───────────────────────────┘
                                    │ 全部 implements Agent / Tool / ModelClient
                         机制层 Runtime（1–14）
                         loop · 治理 · 恢复 · 记忆 · 频道 · 产品 · 轨迹
```

它解决的不是「再写一个 Agent」，是「同一执行核，三套领域语言，一层运营纪律」。面试时若只能画一张图，就画这一张。

模块清单按 README 的学习路线收口，不再发明第 19 行：

```text
1–2   agent-core / agent-model      模型调用 + 最小 Loop
3     agent-plugin                  SPI 热插拔
4     agent-sandbox                 ClassLoader + 进程隔离
5–6   agent-workflow                图引擎 + Checkpoint / RunManager
7     agent-scheduler               定时 / 事件恢复
8     agent-memory                  三横一纵 + 共享记忆治理
9     agent-security                权限 / 审批 / 审计 / 净化
10    agent-mcp                     MCP stdio + A2A 数据模型
11    agent-orchestrator            Supervisor / Worker
12    agent-channel                 身份 / 共享会话 / Ambient
13    agent-product                 YAML / 模板 / PromptManager
14    agent-trace-export            轨迹 / 奖励 / DPO
15    agent-enterprise              归属层
16    agent-tavern                  世界层
17    agent-coding                  变更层
18    agent-observability           运营层
```

规划总目标（蓝图 §13）逐项已勾：Core Runtime、Model Adapter、Tool、Plugin、Sandbox、Workflow、Checkpoint、Scheduler、Security、Identity、Channel / Ambient、MCP / A2A、Product、Trajectory、三 Profile、Observability & Cost。M1–M8 已达成；M9 随本篇兑现。

---

## 4. 核心抽象和架构

### 4.1 机制层：路径保持愚蠢，能力加在边界

装饰器谱系是 v1 最硬的结构故事（建议作为 15 分钟深潜）：

```text
Stage 1   RetryModelClient / TimeoutModelClient / FallbackModelClient
          —— 可用性：挂了重试、超时、换人
Stage 9   GovernedToolExecutor
          —— 治理：权限三档 + 审批 + 审计 + 净化
Stage 14  RecordingModelClient / RecordingToolExecutor
          —— 训练：边界捕获 S-A-O-R-D
Stage 18  ObservingModelClient / ObservingToolExecutor
          —— 运营：延迟 / token / denied / 成本
```

四代同一哲学：**组合优于修改，`ReActAgentLoop` 一行不改。** 路径会分叉（`AgentNode`、scheduler 恢复、频道共享会话），边界只有两个——模型调用和工具执行。埋在路径上就要追着每条路径埋；挂在边界上，所有 Agent 即插即用。

一次 Run 三种投影，读者不同，采集不重复（D1）：

```text
Trajectory   训练系统读   S-A-O-R-D + messages
AuditEvent   审计员读     APPROVED / DENIED / EXECUTED / SANITIZED
RunMetrics   值班工程师读 延迟 / token / 成本 / denied 计数
```

`ObservabilityExample` T5 用一次 `[DENIED]` 同时点亮三条流，是这张图的活证据。

### 4.2 三个 Profile：缺的是层，不是新 loop

```text
Stage 15 归属层  RequestContext / Tenant / CostLedger
         每个请求有主人，每个租户有边界，每分钱有归属
Stage 16 世界层  CharacterCard / WorldState / TurnEngine / GameReplayer
         角色有灵魂，说话有后果，一局有历史
Stage 17 变更层  Workspace / PatchStore / CommandWhitelist / CodingSession
         输出即变更，验证即测试，循环即收敛
```

判断标准：`Tenant` / `Relationship` / `Patch` 在 Runtime 里不存在，在各自 Profile 里是一等公民。Profile 的 bug 不该需要改 `ReActAgentLoop` 才能修。

### 4.3 运营层：26 个抽象、五组、四处有意不做

```text
metrics   ObservingXxx + MetricsCollector
cost      BudgetBook 五维 + CostMeter microUSD
routing   RoutingModelClient + BudgetAwareRouter
eval      EvalDataset.importFailures + EvalReport 三态
version   ComponentVersion + RunRegistry.byRunId + CostDashboard
```

有意不做（v1 边界，不是没做完）：OpenTelemetry SDK、LLM-as-judge、百分比灰度、前端仪表盘 / SDK。`MetricsSink` 四事件稳定之后，OTLP adapter 是一天的翻译层。

---

## 5. 一次完整数据流

这一节就是 M9 的 30 分钟讲稿：从 `ModelClient` 走到检查点、治理、轨迹、指标。不必真跑 30 分钟，链路必须完整。

```text
用户 prompt
    │
IdentityResolver（12，企业再叠 RequestContext）
    │ fail-closed：非成员 / 空交集直接拒
    ▼
RoutingModelClient（18）
    BudgetAwareRouter 读 BudgetSnapshot
    健康 → premium；余量 < 25% → cheap + reason；余量 0 → BudgetExhaustedException
    内层 FallbackModelClient（1）：cheap 挂了换 backup
    │
Named(ObservingModelClient(mock))     // 候选内侧盖模型名，计价才有键
    延迟 / TokenUsage / finishReason → MetricsSink
    │
ReActAgentLoop（2，零改动）
    ContextBuilder（8）裁剪后的消息 = 模型实见
    │
ObservingToolExecutor(GovernedToolExecutor(...))
    9：PermissionChecker / Approval / AuditLogger / Sanitizer
    18：success / denied / latency
    14：若再包 RecordingToolExecutor，observation 原文进轨迹
    │
副作用按 Profile 分叉
    15 退款 → HumanApprovalNode 暂停，CheckpointStore 落盘，resume(runId)
    16 好感 → WorldState + TurnLog
    17 写文件 → PatchStore 暂存，审批后唯一落盘
    │
run 结束
    MetricsCollector.endRun → RunMetrics
    BudgetBook.recordUsage 五维诚实账
    CostDashboard.recordCost 一次 + 四维分账（各维合计 = 总账）
    RunRegistry.record(三元组, metrics)     // byRunId 时间旅行
    RecordingAgent.finish → Trajectory JSONL
    失败 → EvalDataset.importFailures → 回归集 +1
```

30 分钟讲这条链时，不要从 18 的 sink 讲起。从 `ModelClient.chat` 进门：请求怎么被路由、怎么被观察、loop 怎么保持愚蠢、工具怎么先过治理再过指标、副作用怎么在三个 Profile 里变成不同的一等值，最后 run 结束时四份档案同时落下来——指标行、token 账、钱账、版本三元组，失败再加一条回归用例。

检查点在这条链上的位置：它不是观测的一部分，是 Stage 6 的恢复原语。长任务（企业审批、调度唤醒）从 `CheckpointStore` 恢复的是 cursor + 黑板，不是指标行。`FileCheckpointStore` 让进程崩溃后仍能 `resume(runId)`；内存版只够单测。三种「重放」不要并：

```text
Checkpoint resume（6）  从某点继续跑
Trajectory replay（14） 走录模型决策
GameReplay（16）        重演世界状态流
```

单位分别是 node、model step、game turn。面试能分开，就说明没把 Runtime 收成一张大表。

---

## 6. 最小代码或实验

v1 收官不靠新 API，靠一条装配和一组数字。

**即插即用（不动 loop）**：`MetricsCollectorTest` 端到端用 `SimpleAgent + ReActAgentLoop + 双装饰器` 一行接线，scripted `tool_calls → text` 两轮，断言 `modelCallCount=2`、`toolCallCount=1`、token `100/40/140`。这是「能力在边界」的单元级证据。

**零存量改动证据链（15 → 16 → 17 → 18）**：

```text
Stage 15  存量两处纯加法：MemoryScope.Kind.TENANT、MemoryType.KNOWLEDGE
          向后兼容，其余模块零 diff；全仓 774 全绿收口
Stage 16  零存量。AGENT/SESSION/EVENT 已够用。111 测试
Stage 17  零存量。治理三档 + SandboxSpec 契约复用。138 测试
Stage 18  零存量（父 POM 注册豁免，同 15/16/17 先例）。125 测试
          全仓 1148 全绿 —— 第四次兑现「规划总目标用既有契约拼装」
```

15 不是零，是「最小诚实加法」：Stage 8 javadoc 早写过租户隔离，枚举值后补。16 起才进入连续零 diff。面试不要说「从来没改过存量」——要说「改过两次枚举，之后三次证明抽象够用」。

**六次组装阶段的复用率演进**：

```text
第 1 次  Stage 12 频道
         规划时未做 due diligence。完成后自查：TaskScheduler /
         EventBroker 两处「复用」落空（回调绑死 resume(runId)）
         兑现的是 channel scope 记忆、TaskStatus、AgentState 组合
         复用率：计划高、兑现中，教训写成制度

第 2 次  Stage 13 产品层
         预检先行制度化。EventBroker 再次预检不通过（Webhook 走新 Run）
         若干 ⚠️ 口径修正（治理非自动包装、longTerm 未做）
         复用率：预检让「没复用」变成蓝图里的诚实行，不再是事故

第 3 次  Stage 14 轨迹层
         预检抓住语义陷阱：压缩后模型实见 ≠ state.messages
         Recording 手法从测试装饰器升级为产品代码
         复用率：高，且预检开始抓语义而不只抓类名

第 4 次  Stage 16 酒馆
         （15 是领域 Profile 第一次实证，复用预检同期发生，
          蓝图自题「第三次」——与 14 并行叙事，不另占序号）
         三处有意不复用写进蓝图（Checkpoint / EventBroker / Workflow）
         存量改动：零。复用率：该复用的全复用，不该复用的拒绝硬套

第 5 次  Stage 17 编码
         四处有意不复用（ProcessSandbox.execute / Workflow 环 /
         MemoryStore / RecordingAgent 不进验收）
         沙箱只复用 SandboxSpec/Result 契约。存量：零

第 6 次  Stage 18 运营
         四处有意不复用（OTel / CostLedger / ServiceAccount 身份 /
         LLM-as-judge）
         两处钩子回收：12 的 monthlyTokenBudget → ChannelQuota；
         13 的 PromptManager 版本号 → ComponentVersion
         存量：零。复用率：模式复现 + 数字注入，依赖图无环
```

演进的不是「复用百分比单调上升」，是**预检从无到有、有意不复用从羞耻变成一等公民、存量改动从「两处加法」收敛到连续零**。Stage 12 的三处落空，是后面五次能写「有意不复用」表的学费。

---

## 7. 常见误区

1. **「v1 还要 Stage 19」** —— 没有。OTel / judge / 百分比灰度 / 前端 SDK 是 v2 可选，不是 1.0 缺口。v1 = Stage 1–18。
2. **「三个 Profile 说明 core 不够用」** —— 相反：core 够用到可以零改动承载三类场景。不够用的是领域层。
3. **「运营层该依赖 enterprise，才能对上 CostLedger」** —— 下层不认识上层。`BudgetBook` 五维通用化，装配层桥接。
4. **「把轨迹、审计、指标合成一个 Trace 系统」** —— 读者不同。合成大表是值班、审计、训练互相污染。
5. **「装饰器套太多会慢」** —— 慢的是模型。边界装饰器的成本是一次计时和一次 sink 调用；sink 失败还被隔离。真要减层，减的是重复采集，不是这一哲学。

---

## 8. 和相邻概念的区别

三 Profile 与运营层（收官对比，接 Stage 15 / 16 / 17 命题）：

```text
Stage 15 让 Agent 能进企业 —— 归属层（谁在问 / 哪个租户 / 花谁的钱）
Stage 16 让 Agent 能演戏   —— 世界层（角色有灵魂 / 说话有后果 / 一局有历史）
Stage 17 让 Agent 能写代码 —— 变更层（输出即变更 / 验证即测试 / 循环即收敛）
Stage 18 让 Agent 能运营   —— 运营层（不是第四个 Profile，是全部 Profile 的上岗证）
```

和外部框架的一句话：LangChain4j / Spring AI 缺的不是「再包一个模型客户端」，是暂停-恢复、治理链、多维预算、失败即回归。v1 把这些做成库形态的模块，而不是一个带 UI 的平台。

和「再造一个 AgentLoop」的区别：18 周里 loop 几乎没被要求变聪明。变聪明的是边界上的装饰器和 Profile 里的领域模型。这是从 Stage 1「为什么不从 AgentLoop 开始」贯穿到收官的同一条线。

---

## 9. 我的设计判断

第一：**契约稳定是零存量改动的本金。** Stage 17 与 18 能并行，是因为交集只有 `agent-core` 的 `ModelClient` / `Tool` / `ToolExecutor`，两条线各消费一个方向，互不可见。如果 1–14 把领域概念渗进 core，后面每一次 Profile 都会改 loop。

第二：**有意不复用和复用预检是一对工具。** 12 事后纠偏，13 起预检先行，16–18 把「不复用什么、为什么、去向」写成表。硬套 `EventBroker.resume(runId)` 或 `ProcessSandbox.execute` 的临时目录哲学，比漏复用更贵。

第三：**v1 的完整比 v1 的时髦重要。** 不上 OTel，是避免语义被外部格式绑架；不上 judge，是保住门禁可复现；不上百分比灰度，是承认 sticky session 是另一门课。收官日全仓 1148 绿、模块 125、零新第三方依赖，比一张「技术基线已对齐」的幻灯片硬。

第四：**装配会还你真相。** 17 的 `run_tests` 看不见暂存、18 的 `Observing(Routing)` 拿不到模型名，都是蓝图时序图没写到的层级。v1 把这些偏差写进实现记录，而不是假装草图一次做对。

第五：**并行不是因为赶工。** Stage 17 与 18 的交集只有 `agent-core`：17 消费 `Tool` 契约（`ReadFileTool implements Tool`），18 消费 `ModelClient` / `ToolExecutor` 契约（装饰器 implements 同接口）。构建图无环，验收互不污染。一人开发仍然交错实施——认知切换税大于并行收益——但依赖正交是架构事实，不是日历宽容。收官日两线合并一次全绿，§0 裁决从纸面变成构建事实。

---

## 10. 面试表达

M9 三层，按时间盒用，不要一次倒完。

**5 分钟：整体架构（Runtime + 三 Profile + 运营层）**

> 「我做的是一个 Java Agent Runtime，不是一个聊天 Demo。机制层是 `ReActAgentLoop` + `ModelClient` + `Tool`，能力用装饰器往边界上加，loop 保持愚蠢。三个领域 Profile 叠在同一 Runtime 上：企业是归属层，酒馆是世界层，编码是变更层——缺的是领域模型，不是新的循环。Stage 18 是运营层：指标、五维预算、可解释路由、失败即回归、版本三元组。它不是第四个 Profile，是前面所有 Profile 的上岗证。v1 就是 Stage 1 到 18，没有 Stage 19。」

**15 分钟：深一个模块（装饰器谱系 1 → 9 → 14 → 18）**

> 「同一条 `ModelClient` / `ToolExecutor` 接口，四代装饰器。Stage 1 管可用性：Retry / Timeout / Fallback。Stage 9 管治理：`GovernedToolExecutor` 把权限、审批、审计、净化挂在执行前和执行后。Stage 14 管训练数据：`RecordingXxx` 在同一边界抓 S-A-O-R-D，压缩后 State 必须等于模型实见。Stage 18 管运营：`ObservingXxx` 投影延迟、token、denied。三种读者——训练、审计、值班——订阅同一事件流，零重复埋点。`ReActAgentLoop` 从 Stage 2 到 18 没有为这些能力改过一行。这就是组合优于修改。」

备选 15 分钟：三 Profile。讲 `Tenant` / `WorldState` / `Patch` 为什么不能进 core，以及 15 两处枚举、16–18 零 diff。

**30 分钟：一次完整执行链路**

按第 5 节走一遍，手里打开 `ObservabilityExample` 的 T0–T7：装配 → 看得见的 run → WARN 不阻断 → 余量 17% 切 cheap（reason 带数字）→ 耗尽抛 `BudgetExhaustedException` → `[DENIED]` 三投影 → `importFailures` + 门禁三态 → `byRunId` + 四维对账。

中途必须插入两段不属于 observability 包的东西，否则 30 分钟会缩成「讲了一个模块」：

1. Stage 6 检查点：企业退款走到 `HumanApprovalNode`，`RunManager.pause`，`CheckpointStore` 写下 cursor 与黑板；主管批准后 `resume(runId)`，不重跑已执行的退款工具。问「完整执行链路」却不提暂停-恢复，Runtime 只剩 Demo。
2. Stage 9 治理：`GovernedToolExecutor` 在 `ObservingToolExecutor` **内侧**（wiring 顺序契约）。`[DENIED] ` 前缀既是审计事件，也是 `ToolCallMetrics.denied=true`，也是轨迹 observation 原文。denied 飙升是注入或 prompt 退化的先导指标——F7 的信号，不是工具自己失败（`[ERROR] ` 不算 denied）。

再补一句 Stage 17 的经济对照：`[LIMIT]` 是行为闸（修不好还在修），`BudgetDimension.RUN` 是经济闸（这一跑 token 到顶）。同一条战壕两个哨位，30 分钟里提一次就够，证明你没有把「循环收敛」和「账单收敛」当成一件事。

收尾用证据，不用形容词：

```text
零存量：15 两处加法 → 16 零 → 17 零 → 18 零
组装：12 事后纠偏 → 13 预检制度 → 14 语义陷阱 → 16/17/18 有意不复用一等化
数字：observability 125 / 全仓 1148 / 零新第三方依赖
边界：不做 OTel、judge、百分比灰度、前端 SDK
```

---

## 11. 下一篇连接什么

没有下一篇。v1 完了，没有 Stage 19。

若还要写，那是 v2 的选题，不是本路线的第 19 周：可选的 `otel-exporter` 子模块、`Expectation` 的 judge 槽位、带 sticky session 的百分比 canary、把 `CostDashboard` 的 CSV/JSONL 接到已有 Grafana。那些文章不叫「Stage 19」，叫「在 v1 契约上长出来的适配器」。

本系列到此结束。从 Stage 1「为什么不从 AgentLoop 开始」，到本篇「同一 Runtime、三个 Profile、一层运营」，中间是六次组装、四次零存量（含 15 的两处诚实加法）和一条装饰器谱系。代码在模块里，判断在蓝图的 D 里，剧本在 `ObservabilityExample` 里。
