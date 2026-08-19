# Stage 7 概念、映射与数据流（异步任务调度器）

> 对应阶段：Stage 7 - 异步任务调度器
> 定位：概念 -> 概念与类映射 -> 数据流向（三段式，与 Stage 5/6 笔记同体系）
> 配套：架构设计见 [architecture-stage-7.md](architecture-stage-7.md)，概念与数据流见 [stage-7-scheduler.md](stage-7-scheduler.md)，LLM 驱动调度见 [stage-7-llm-initiated-scheduling.md](stage-7-llm-initiated-scheduling.md)
> 状态：已实现并完成代码审查（3 个边界 bug 修复，112 测试全绿，DynamicSchedulerNode 补齐 Agent 自驱动语义）

---

## 一、6 个核心概念

### 总纲：从「手动 resume」到「自动 resume」

```
Stage 6：节点暂停 -> 返回 ResumeToken -> 调用方手动调 resume(runId)
Stage 7：节点暂停 -> 注册触发器 -> 条件满足时调度器自动调 resume(runId)
```

调度器就是「自动盯着条件、条件满足自动恢复」的引擎。

### 6 个概念

| # | 概念 | 一句话 |
|---|------|--------|
| 1 | **定时恢复** | Agent 说「2 小时后查」-> 调度器计时 -> 到点自动 resume |
| 2 | **事件驱动恢复** | Agent 说「等 CI 通过」-> 订阅事件 -> 事件到达自动 resume |
| 3 | **Agent 自驱动** | 不是外部 Cron 触发，是 Agent 在运行中自己决定「何时继续」 |
| 4 | **异步任务队列** | Agent 运行时动态产出子任务入队（区别于图定义时写死的并行） |
| 5 | **成本控制** | 长时任务的 token 计数与预算，超限自动 FAILED |
| 6 | **LLM 驱动调度参数** | LLM 在运行时决定「等什么事件/等多久」，图定义不写死参数 |

### 概念之间的关系

```
定时恢复 ─┐
事件恢复 ─┼── 都是「自动恢复的触发方式」，注册方式不同
         │
Agent 自驱动 ── 不是外部定时器触发整个任务，是 Agent 自己在运行中注册触发器
         │
异步任务队列 ── 不是恢复机制，是 Agent 运行时动态派活（派给谁、怎么消费）
         │
LLM 驱动调度 ── 让「等什么/等多久」由 LLM 运行时决定，而非人写死在图里
         │
成本控制 ── 防止 Agent 自激振荡（无限恢复烧 token），超限熔断
```

### 一个关键区分：两种「并行」

```
ParallelNode（Stage 5）：人画图时写死「并行 A、B、C」     静态并行
AsyncTaskQueue（Stage 7）：Agent 运行时说「我派 3 个子任务」  动态并行
```

---

## 二、概念与类的映射

### agent-scheduler 模块（10 个类）

| 概念 | 类 | 类型 | 关键成员 | 一句话 |
|------|----|------|---------|--------|
| 调度器门面 | `TaskScheduler` | class | `scheduleResume()` / `waitForEvent()` / `fireEvent()` / `enqueueTask()` / `consumeTokens()` / `restorePausedRuns()` | 唯一入口，包装 RunManager |
| 定时恢复 | `ScheduledResume` | **record** | resumeId + runId + fireAt + recurring + interval | 不可变，注册后不变 |
| 事件恢复 | `EventTrigger` | **可变 class** | triggerId + runId + eventKey + timeout + `volatile firedAt` | 可变是因为 firedAt 有生命周期 |
| 事件总线 | `EventBroker` | class | `subscriptions` + `firedKeys` + `eventPayloads` | 事件 -> runId 的关联存储 + 触发执行 |
| 子任务队列 | `AsyncTaskQueue` | class | `pollNext()` 按优先级降序 + FIFO | 存储 + 排序，不含执行 |
| 子任务 | `AsyncTask` | **record** | taskId + parentRunId + input + priority + status + workflowName | 一个子任务 = 一个待启动的 Run |
| 任务状态机 | `TaskStatus` | enum | PENDING/RUNNING/WAITING_*/SUCCEEDED/FAILED/CANCELLED | 队列侧的独立生命周期 |
| 优先级 | `TaskPriority` | enum | LOW(1)/NORMAL(5)/HIGH(8)/URGENT(10) | 队列消费顺序 |
| 成本计数 | `TokenBudget` | class | limit + `consume()` + `isExceeded()` | 超 limit 返回 false |
| LLM 驱动调度 | `DynamicSchedulerNode` | class | intentKey + keyValidator + minDelay/maxDelay | 从黑板读 LLM 意图，闸门校验后注册 |

**为什么 EventTrigger 是可变 class 而 ScheduledResume 是 record？** `firedAt` 有生命周期（注册时 null，fire 时填充），必须可变；且超时定时器要靠 `isFired()` 判断是否跳过二次 resume（竞态防护）。ScheduledResume 字段注册后不变，record 恰当。

### 4 个节点类型（nodes/ 子包）

| 节点 | 参数来源 | 首次执行 | 恢复执行 |
|------|---------|---------|---------|
| `WaitEventNode` | 构造函数（人定） | `waitForEvent(runId, eventKey)` + 暂停 | `hasEventFired()` ? 读 payload : 超时/re-pause |
| `ScheduleResumeNode` | 构造函数（人定） | `scheduleResume(runId, delay)` + 暂停 | 直接透传 |
| `DispatchTaskNode` | taskProducer 函数 | `enqueue()` N 个任务，**不暂停** | 同首次 |
| `DynamicSchedulerNode` | **黑板（LLM 定）** | 读黑板意图 -> 校验 -> 注册 -> 暂停 | 检查触发状态 -> 读 payload/透传 |

### agent-workflow 模块的 3 个接入点

| 接入点 | 改动 | 为什么 |
|--------|------|--------|
| `NodeContext.scheduler()` | 新增 default 方法，返回 Object | 避免循环依赖（agent-scheduler 依赖 agent-workflow，不能反向）。节点 instanceof 转型 |
| `GraphRuntime.scheduler(Object)` | 新增字段 + setter | 创建 NodeContext 时把 scheduler 传给节点 |
| `RunManager.setRuntime()` | runtime 从 final 变可变 | 装配顺序：先 RunManager -> 再 Scheduler -> 再 setRuntime 回注 |

---

## 三、数据流向

### 场景 A：事件驱动恢复（LLM 决定等什么）

这是 Stage 7 的核心场景，完整链路：

```
【定义期 · 人画图】
  START -> decide(AgentNode) -> wait(DynamicSchedulerNode) -> merge(ActionNode) -> END
  图里没有 eventKey，只有 intentKey="decide"（指向黑板上的意图）

【T0 启动】
  runManager.start(wf, "帮我盯着 PR #42 的 CI")
    └─> GraphRuntime: START -> 路由到 decide

【T1 LLM 决策（AgentNode 内部）】
  decide(AgentNode):
    LLM 看到用户请求
    -> ReAct 循环产出结构化输出：
       {"action":"wait_event","event_key":"ci-passed:pr-42"}
    -> AgentNode 把 output 写黑板：state["decide"] = {"action":...,"event_key":...}
    -> return NodeResult.of(output)
  GraphRuntime: 黑板["decide"] = 意图 JSON -> 路由到 wait

【T2 注册触发器（DynamicSchedulerNode 首次执行，isResuming=false）】
  wait(DynamicSchedulerNode):
    ├─ 从黑板读意图：intent = state.get("decide") = {"action":"wait_event","event_key":"ci-passed:pr-42"}
    ├─ action = "wait_event"
    ├─ eventKey = intent["event_key"] = "ci-passed:pr-42"     ← LLM 决定的参数！
    ├─ 治理闸门：keyValidator.apply(eventKey) -> true（格式合法）
    ├─ scheduler.waitForEvent(ctx.runId(), eventKey)
    │    └─> EventBroker.subscribe: subscriptions["ci-passed:pr-42"] += trigger
    └─ throw PauseException
         └─> GraphRuntime: cursor="wait" -> RunManager 存 Checkpoint -> PAUSED + ResumeToken
              线程释放！

【等待期 · 没有任何代码在跑】
  Run 挂起。LLM 不运行。EventBroker 持有订阅。ScheduledExecutor 空闲。

【T3 外部事件到达】
  外部系统调 scheduler.fireEvent("ci-passed:pr-42", "all-checks-green")
    ├─ firedKeys.add("ci-passed:pr-42")              ← 无条件记录（Bug1 修复点）
    ├─ eventPayloads["ci-passed:pr-42"] = "all-checks-green"
    ├─ subscriptions.remove("ci-passed:pr-42") -> 取出 trigger 列表
    └─ 对每个 trigger:
         ├─ trigger.markFired()                       ← 防超时定时器二次 resume（Bug3 修复点）
         └─ runManager.resume(runId)
              ├─ 终态守卫（Bug2 修复点）
              └─ GraphRuntime 从 cursor="wait" 继续

【T4 恢复（DynamicSchedulerNode 第二次执行，isResuming=true）】
  wait(DynamicSchedulerNode):
    ├─ 从黑板读意图：intent = state.get("decide") = {...}     ← Checkpoint 恢复了黑板
    ├─ action = "wait_event"
    ├─ scheduler.hasEventFired("ci-passed:pr-42") = true
    ├─ payload = scheduler.getEventPayload(...) = "all-checks-green"
    └─ return NodeResult.of("all-checks-green")        ← 事件 payload 变成节点 output

【T5 完成】
  GraphRuntime: 黑板["wait"] = "all-checks-green" -> 路由到 merge
  merge(ActionNode): ctx.input()="all-checks-green" -> "merged with: all-checks-green"
  -> END -> SUCCEEDED
```

**数据流关键**：LLM 的意图通过黑板流向 DynamicSchedulerNode -> 转成调度器注册 -> 事件 payload 通过 EventBroker 中转 -> 恢复后变成节点 output -> 写黑板 -> 流向下游。

### 场景 B：定时恢复（LLM 决定等多久）

```
【T1 LLM 决策】
  LLM 输出 {"action":"schedule","delay_seconds":7200}
  黑板["decide"] = 意图

【T2 注册（DynamicSchedulerNode 首次）】
  ├─ intent = state.get("decide")
  ├─ action = "schedule"
  ├─ delaySeconds = 7200                              ← LLM 决定的参数！
  ├─ 治理闸门：7200 在 [1s, 86400s] 范围内 -> 通过
  ├─ scheduler.scheduleResume(runId, Duration.ofSeconds(7200))
  │    └─> executor.schedule(() -> doResume(runId, resumeId), 7200s)
  └─ throw PauseException -> Checkpoint -> PAUSED

【等待期 · ScheduledExecutor 倒计时，线程释放】

【T+2h 线程池触发】
  doResume(runId, resumeId):
    ├─ run = runManager.getRun(runId)
    ├─ run.getStatus().isTerminal()?                  ← 终态检查（修复点）
    │    是 -> cancelFuture + 清理（防 recurring 空转）
    └─ runManager.resume(runId) -> 从 cursor="wait" 继续

【T+2h 恢复（DynamicSchedulerNode 第二次，isResuming=true）】
  ├─ action = "schedule"
  └─ return NodeResult.of("resumed after 7200s")      ← 定时器已触发，直接透传

【完成】
  下游节点继续 -> END -> SUCCEEDED
```

### 场景 C：异步任务队列（LLM 决定派什么活）

```
【T1 LLM 决策】
  LLM 输出 {"tasks":[{"input":"紧急修复","prio":"URGENT"},{"input":"常规检查","prio":"NORMAL"}]}
  黑板["plan"] = 意图

【T2 派发（DispatchTaskNode，不暂停！）】
  ├─ taskProducer.apply(ctx) -> 从黑板["plan"] 解析出 2 个 AsyncTask
  ├─ 逐个 scheduler.enqueueTask(task) -> AsyncTaskQueue
  └─ return NodeResult.of("dispatched 2 task(s)")
  GraphRuntime 继续 -> END（主流程不等子任务）

【消费方（另一个线程/另一个 Run）】
  while ((t = scheduler.pollNextTask()) != null)
    ├─ pollNext: 按 priority.weight() 降序 -> URGENT 先出，NORMAL 后出
    └─ runManager.start(workflowByName(t.workflowName), t.input)
         每个子任务 = 一个独立的 Run（有自己的 runId、可暂停、可恢复）
```

### 数据流 4 条铁律

```
铁律 1（runId 关联）：
  节点从 ctx.runId() 取 runId -> 注册进调度器（subscriptions/scheduledResumes）
  -> 触发时查回 -> resume(runId)
  LLM 全程不接触 runId，只产出「等什么/等多久」的业务意图

铁律 2（payload 中转）：
  事件 payload 存 EventBroker.eventPayloads -> 恢复节点读出
  -> 作为节点 output 写黑板 -> 成为下游 ctx.input()
  payload 不进 Checkpoint，进的是触发器的注册表

铁律 3（先注册后暂停，恢复必有据）：
  节点永远先注册触发器，再抛 PauseException
  恢复时必能从 firedKeys / 定时到点 / isResuming 三者之一
  找到「为什么被唤醒」

铁律 4（终态单向门）：
  RunState 终态不可逆，resume 前必查 isTerminal()
  三个修复的 bug 全部源于违反此律
```

### 治理闸门（LLM 越自由，闸门越紧）

```
DynamicSchedulerNode 的四道闸门（注册前强制校验）：

  闸门 1（keyValidator）：eventKey 格式/白名单校验
     LLM 输出 "drop table users; --" -> 拒绝
     LLM 输出 "ci-passed:pr-42" -> 通过

  闸门 2（delay 区间）：[minDelay, maxDelay] = [1s, 24h]
     LLM 输出 delay=999999999 -> 拒绝
     LLM 输出 delay=7200 -> 通过

  闸门 3（TokenBudget）：超限自动 fail(runId)
     Agent 无限恢复烧 token -> 预算耗尽 -> FAILED

  闸门 4（事件超时）：等待超时自动 FAILED
     事件迟迟不来 -> 超时 -> FAILED（不死等）
```

---

## 四、代码审查修复的 3 个边界 bug

```text
Bug 1（严重）：fire() 无 payload 时 hasFired() 误报 false
  -> WaitEventNode 恢复后误判事件未触发，re-pause 死等
  -> 修复：EventBroker 增加独立 firedKeys，fire 时无条件记录

Bug 2（严重）：RunManager.resume() 无终态守卫
  -> SUCCEEDED 的 Run 可被重复 resume，从旧 cursor 重新执行节点（重复执行）
  -> 修复：两个 resume() 加 isTerminal() 守卫；TaskScheduler.doResume
     检测终态时取消 recurring 定时器（一并修复空转）

Bug 3（中）：EventTrigger.firedAt 从不更新（record 不可变）
  -> 事件已正常触发后，超时定时器仍会二次 resume（竞态）
  -> 修复：改可变 class（volatile firedAt + markFired()），fire 时标记
```

**三个 bug 全部源于「状态机的不合法转换」**--异步代码的典型风险区。修复后 112 测试全绿。

---

## 附：一句话总结

```
概念：调度器 = 自动恢复引擎（定时/事件/队列三种触发方式 + LLM 驱动参数 + 成本闸门）
映射：TaskScheduler(门面) + ScheduledResume/EventTrigger(触发器) + EventBroker(事件总线)
      + AsyncTaskQueue(队列) + DynamicSchedulerNode(LLM 驱动) + TokenBudget(闸门)
数据流：LLM 意图 -> 黑板 -> DynamicSchedulerNode 读黑板 -> 校验闸门 -> 注册触发器
       -> 暂停 -> 条件满足自动 resume -> payload 透传 -> 下游继续
```
