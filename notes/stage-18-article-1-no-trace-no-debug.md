# 没有 Trace，就没有真正可调试的 Agent

> 配套蓝图：[architecture-stage-18.md](architecture-stage-18.md) §1（五假设）· D1（三种投影）· 对应实现：`agent-observability/metrics/` · 全剧本：[ObservabilityExample.java](../examples/src/main/java/io/github/qwzhang01/agent/examples/ObservabilityExample.java)
> 状态：✅ Stage 18 已完成（M18.1~M18.5，`agent-observability` 125 测试，全仓 1148 全绿）
> 这是 Stage 18 系列的第 1 篇，也是理解本阶段的钥匙：一次 Run 的三种投影。

---

## 1. 我今天要解决什么问题

前 17 个阶段造好的 Runtime，有一个从未被挑战的隐含共识：**跑起来、答对了，就算成功**。Demo 里确实如此。生产环境里这句话全线破裂——值班工程师凌晨三点被叫醒，问的不是「Agent 会不会说话」，而是：

> 昨晚那次失败 Run 用的什么模型、调了哪些工具、花了多少 token、卡在哪一步？

答不上来，就没有真正可调试的 Agent。对着日志 `grep` 是玄学，不是运营。Stage 18 的开局命题就是这句话：**没有 Trace，就没有真正可调试的 Agent**。但「Trace」在这个框架里不是再造一套采集系统，而是承认一件更硬的事实——一次 Run 本来就该有三种投影，缺的那一种叫运营指标。

---

## 2. 为什么会有这个认知冲突

Stage 1-17 的 Demo 悄悄冻结了五个假设。把它们逐个推到生产，全部破裂：

```text
Demo 的五个隐含假设，在生产环境全部破裂：

1. 跑完即忘假设 —— 假设 Run 结束就结束了（答案给出去，故事就完了）
   生产里"昨晚 3 点那次失败"必须可回答：模型、工具、token、卡点。
   没有指标就没有可调试的生产系统。

2. token 免费假设 —— 假设模型调用没有成本（Mock 是零成本的）
   生产里 token 是真金白银：没被 [LIMIT] 拦住的修复死循环（17）、
   被注入诱导的刷量循环（9）、Ambient 定时任务的静默膨胀（12）——
   每一个都是真实账单事故。

3. 一维预算假设 —— 假设按用户限预算就够了
   频道级共享 Agent（12）意味着 50 人共同烧一份配额；
   租户域（15）意味着一个客户能拖垮平台资源。

4. 改了就好假设 —— 假设修改只会变好
   一次"优化"让目标 case 变好的同时，可能让另外 30% 边角 case 悄悄变坏。
   Agent 非确定性让"手动回归"彻底不可行。

5. 版本透明假设 —— 假设永远只有一版
   prompt 有版本（13 PromptManager）、模型有版本、工具有版本——
   "这次答错了"必须能回答"当时用的是哪个组合"。
```

五个假设，一个共同病灶：**把 Demo 的成功当成了生产的充分条件**。能跑 ≠ 敢上线。Stage 18 要补的不是第四个领域 Profile，而是全部 Profile 的上岗证。

---

## 3. 它解决了什么问题

Stage 18 的定位必须先讲清楚，否则后面十篇都会走偏：

```text
Stage 15 让 Agent 能进企业 -- 第一个领域 Profile：归属层
Stage 16 让 Agent 能演戏   -- 第二个领域 Profile：世界层
Stage 17 让 Agent 能写代码 -- 第三个领域 Profile：变更层
Stage 18 让 Agent 能运营   -- 运营层（不是第四个 Profile，是全部 Profile 的上岗证）：
         每次执行看得见（指标在边界），
         每个 token 记得上账（预算是事前闸），
         每次修改经得起回归（失败样本即回归集），
         每个版本退得回去（版本三元组）
```

「看得见」是本篇的全部工作。看得见不是「多打几行日志」，而是承认一次 Run 有三类读者，每类读者要的不是同一张大表，而是自己视角的投影：

```text
一次 Run 的三种投影（D1）：
  Trajectory（14） -- 训练格式（S-A-O-R-D），读者是 RL 训练系统
  AuditEvent（9）  -- 治理格式（谁批的 / 拒的 / 净化了什么），读者是审计员
  Metrics（18）    -- 运营格式（延迟 / token / 成本 / 成功率），读者是值班工程师

三者共享同一组装饰器边界（Model 调用 / Tool 执行），
区别不是数据量，是读者。
```

这把钥匙一旦拿到，后面的评估、指标、路由、预算、配额都是同一件事的不同投影面，而不是五个互不相干的子系统。

---

## 4. 核心抽象和架构

Stage 18 新增 `agent-observability` 模块，compile 只依赖 `agent-core` + `agent-trace-export`（评估读失败轨迹时才用后者）。不依赖 channel / enterprise / product / sandbox / workflow。零新第三方依赖——**不引入 OpenTelemetry**（D9：先稳定自有 `MetricsSink` 接口，OTLP adapter 是薄壳，留 v2）。

本篇只展开第一组（metrics 包，M18.1）：

```text
metrics/
  MetricsSink            观测事件出口：onModelCall / onToolCall / onRun / onAlarm
  ModelCallMetrics       一次模型调用：model + token 三项 + latencyMs + finishReason + error
  ToolCallMetrics        一次工具调用：toolName + latencyMs + success + denied + error
  RunMetrics             值班工程师的一屏答案：status / 调用计数 / token 汇总 / costMicros
  ObservingModelClient   ModelClient 装饰器：边界测延迟、读 usage、记 finishReason
  ObservingToolExecutor  ToolExecutor 装饰器：边界测延迟、记成败与 denied
  MetricsCollector       边界事件 → run 级聚合（beginRun / endRun + 内存查询）
```

装饰器谱系（组合优于修改的编年史，本篇只点名，第 3 篇展开）：

```text
Stage 1   Retry / Timeout / Fallback     —— 可用性
Stage 9   GovernedToolExecutor           —— 治理
Stage 14  RecordingModelClient / RecordingToolExecutor —— 训练数据
Stage 18  ObservingModelClient / ObservingToolExecutor —— 运营指标
```

四代装饰器站在同一组边界上。`ReActAgentLoop` 一行不改。路径保持愚蠢，能力加在边界上。

`RunMetrics` 的字段值得对一下实现，避免把蓝图草图当 API：它用 `AgentState.Status status` + `String lastError`，不用 Stage 14 的 `DoneReason`——metrics 包不为一个枚举去 import `agent-trace-export`。另外两个超蓝图字段是诚实的：`modelCallErrors` 和 `deniedToolCalls`，验收点名的错误率与 denied 计数显式落在 record 上，不靠事后推导。

`MetricsCollector` 还嵌了一个 `AgentStats(agentName, totalRuns, succeededRuns, failedRuns, successRate)`：只有 `Status.DONE` 算成功，`MAX_STEPS_EXCEEDED` 算失败。这是线上任务成功率口径；离线评估成功率是另一口径，第 2 篇再讲。

---

## 5. 一次完整数据流

`ObservabilityExample` 的 T5 是本篇最该记住的剧本：一次被治理链拒绝的工具调用，同一 Run 同时流出三种投影。

```text
用户："summarize the incident"
  → 模型先调 echo（放行）再调 dangerous_tool（ToolPolicy.DENY）

同一组边界，三种投影同源对照：

  1. AuditEvent（9，读者=审计员）
     audit.getAll() 里一条 AuditStatus.DENIED：toolName + reason
     —— "谁试图调了什么、为什么被拒"

  2. RunMetrics（18，读者=值班工程师）
     deniedToolCalls=1 / toolCallCount=2
     —— "denied 飙升是注入攻击或 prompt 退化的先导指标"（F7）

  3. Trajectory（14，读者=RL 训练系统）
     对应 step 的 observation 保留 [DENIED] 原文
     —— 训练系统看到的是模型真实看见的文本，不是被洗过的摘要
```

装配顺序是契约，不是风格：`ObservingToolExecutor.wrap(GovernedToolExecutor, sink)`——观察层必须在治理链**外侧**。反过来包，denied 全部静默丢失，值班工程师永远看不到「被拒」这件事。

示例里模型侧的实际装配是 `Named(Observing(Recording(mock)))`，不是蓝图字面的 `Observing(Routing(...))` 外置。原因很具体：`ReActAgentLoop` 不设 `request.model()`，外置观察层拿到的 model 是 null，后续计价全部落空。命名包装器把层名盖上 request，观察装饰器在其内侧才看得到计价所需的模型名。**设计图会骗你，装配会还你真相**——这条 Stage 17 的教训在 T5 又兑现了一次。

---

## 6. 最小代码或实验

「不动 loop 一行、指标即插即用」的最小实验，就是 `MetricsCollectorTest` 里那条端到端：`SimpleAgent` + `ReActAgentLoop` + 双装饰器一行接线，scripted `tool_calls → text` 两轮，断言 `RunMetrics.modelCallCount=2`、`toolCallCount=1`、token 三项精确累加。

接线形状如下（方法名以代码为准）：

```java
MetricsCollector collector = new MetricsCollector();
ModelClient observed = ObservingModelClient.wrap(mock, collector);
ToolExecutor tools = ObservingToolExecutor.wrap(executor, collector);

collector.beginRun("run-1", "assist");
agent.run("summarize this for me");
RunMetrics row = collector.endRun(AgentState.Status.DONE, null);
// row 就是值班工程师的一屏答案
```

两个必须锁住的不变量：

1. **指标是旁路**。`ObservingModelClient` 对 delegate 异常「记完照抛」（对齐 14 `RecordingModelClient`）；对 sink 异常「吞 + warn」（对齐 12 listener 隔离）。旁路失败不伤主流程。
2. **流式语义保真**。`stream()` 只在终止事件 `StreamEvent.Done` / `StreamEvent.Error` 恰发一次指标；无终止事件的流零发射——没被消费的调用从来没发生过。

`MetricsCollector` 还有一条运营记账纪律：没有 run 上下文的边界事件不丢弃，计入 `totalModelCalls()` / `totalToolCalls()`。run 外烧的 token 也是 token。嵌套 run、复用 runId、二次 `endRun` 全部 IAE——单 run 纪律对齐 Stage 14。

线程边界也要诚实写出来。run 上下文是 `ThreadLocal` 的（v1 与 Stage 14 的录制会话同一纪律）。内侧若有 Timeout 之类会切线程的装饰器，事件会从当前 run 脱落，变成孤儿计数。所以观察层要放最外，或者接受「这条调用记在全局、不进这一行 RunMetrics」。这不是漏测，是单体 Runtime 对线程模型的诚实边界；分布式 `traceparent` 不在 v1 范围。

`onRun` 在 `MetricsCollector` 里是空实现：它是 `RunMetrics` 的生产者，不是消费者。外部 console / JSONL sink 若要接到汇总行，装配自己订一份 `onRun`。生产者再消费自己，会把同一行写两遍。

---

## 7. 常见误区

1. **「再造一套 Trace 系统」** —— 最贵的误区。Stage 14 已经有 `Trajectory` / `TrajectoryRecorder`，Stage 9 已经有 `AuditEvent`。再造第三套采集，就是同一边界埋三次点。运营 Trace = `RunMetrics` 汇总行 + 轨迹文件引用，不是第三套 SDK。
2. **「先接 OpenTelemetry」** —— D9 否决。本项目是库形态，没有「既有 Prometheus/Grafana」可接；OTel SDK 是一整棵依赖树，收官阶段破「零新第三方依赖」是坏示范。先稳定 `MetricsSink` 四事件，OTLP exporter 是纯翻译层。
3. **「Stage 18 是第四个 Profile」** —— 错层。15/16/17 是领域层（归属 / 世界 / 变更），18 是运营层，给**全部** Profile 发上岗证。企业客服、酒馆 NPC、Coding Agent，值班问题是同一句。
4. **「usage 没报就当免费」** —— `ModelCallMetrics.from` 对未报告的 usage 诚实记 0；M18.2 的 `CostMeter` 对缺价行 fail-loud，拒猜。两级诚实分工：没报 ≠ 免费，没定价 ≠ 零成本。

---

## 8. 和相邻概念的区别

三种投影是本系列反复使用的对照表，先在这里钉死：

```text
Metrics（18）vs AuditEvent（9）vs Trajectory（14）
  Trajectory  训练格式   S-A-O-R-D + messages 双通道     读者：RL 训练系统
  AuditEvent  治理格式   APPROVED/DENIED/EXECUTED/...    读者：审计员
  Metrics     运营格式   延迟/token/成本/成功率           读者：值班工程师

三者共享边界，零重复埋点。
区别不是数据量，是读者。
一个成熟的 Runtime 不是把所有观测数据塞进一张大表，
而是让每类读者拿到自己视角的投影。
```

另外两对相邻概念，后面几篇会拆，这里只立界：

- `BudgetBook`（18）vs `CostLedger`（15）：通用五维账本 vs 企业域特化。观测层是下层，下层不认识上层（D4）。
- `RoutingModelClient`（18）vs `FallbackModelClient`（1）：选谁走 vs 挂了换谁。第 4 篇展开。

---

## 9. 我的设计判断

最重的一条：**三种投影是理解 Stage 18 的钥匙，也是理解「可调试」的钥匙。**

很多人把可观测性理解成「把所有字段打进一份 JSON」。值班工程师不读 S-A-O-R-D，审计员不读 token 单价，训练系统不读审批理由。把三种读者塞进同一张表，结果是谁都读得累、谁都读不全。正确的做法是边界只埋一次，投影按读者分。

第二条是 **D2：指标在边界不在路径**。延迟不在 `ReActAgentLoop` 里测。路径会分叉——`AgentNode`、scheduler 恢复、channel 共享会话——埋在路径就要追着每条路径埋。边界是收口：所有路径最终都过 `ModelClient` / `ToolExecutor`。零存量改动不是洁癖，是「指标能力对所有既有 Agent 即插即用」的前提。

第三条是诚实：`RunMetrics.costMicros` 在没接 `CostMeter` 时是 0，javadoc 写明「无定价表 = 无成本计算」。占位 0 不是假账，假账是悄悄填一个猜出来的数。M18.1 收口时全仓 1047，M18.5 收到 1148，中间每一次加的都是观测模块自己的测试——存量模块零 diff。上岗证能发到所有 Profile 头上，正是因为前 17 阶段把 `ModelClient` / `ToolExecutor` 契约收稳了，18 才能只做装饰器、不动 loop。

---

## 10. 面试表达

> 「Stage 18 不是第四个领域 Profile，是全部 Profile 的上岗证。前 17 阶段回答 Agent 能不能跑，18 回答敢不敢上线运营。可调试的前提不是再造一套 Trace，而是承认一次 Run 有三种投影：Trajectory 给训练系统、AuditEvent 给审计员、RunMetrics 给值班工程师。三者共享 Model / Tool 两个装饰器边界，区别是读者不是数据量。ObservingModelClient / ObservingToolExecutor 是装饰器第四代，ReActAgentLoop 一行不改。没有这三种投影，凌晨那次失败就只能 grep 玄学。」

---

## 11. 下一篇连接什么

看得见之后，下一问是：**看见了，怎么判断好不好？** Agent 的评估对象到底是终答文本、中间路径，还是任务结果？单元测试护不住 prompt，评估集才护得住——但评估集本身也必须可复现，否则门禁就是装饰。

→ [stage-18-article-2-how-to-evaluate.md](stage-18-article-2-how-to-evaluate.md)
