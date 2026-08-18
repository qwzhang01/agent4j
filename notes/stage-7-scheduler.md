# Stage 7 概念与数据流（异步任务调度器）

> 对应阶段：Stage 7 - 异步任务调度器
> 定位：概念与设计哲学 -> 概念与实体映射 -> 代码架构 -> 数据流向
> 配套：架构设计见 [architecture-stage-7.md](architecture-stage-7.md)
> 状态：已实现并完成代码审查（3 个边界 bug 已修复，97 测试全绿），审查报告要点已融入本文

---

## 一、概念与设计哲学

### 一句话总纲

**Stage 6 让 Run 能暂停-恢复（手动），Stage 7 让 Run 能自动恢复。调度器就是「自动恢复的引擎」。**

### 6 个核心概念

```text
1. 自动恢复        Stage 6 的 resume 是调用方手动调；Stage 7 由调度器在条件满足时自动调
2. 定时恢复        Agent 说「2 小时后检查」-> 调度器计时 -> 到点自动 resume
3. 事件驱动恢复    Agent 说「等 CI 通过」-> 订阅事件 -> 事件到达自动 resume
4. Agent 自驱动    不是外部 Cron 定时触发整个任务，是 Agent 在运行中自己决定「何时继续」
5. 异步任务        Agent 运行时动态产出子任务入队（区别于画图时确定的并行）
6. 成本控制        长时任务的 token 计数与预算
```

### 「不是 Cron，是 Agent 自己决定」--怎么理解

这是规划里最核心的一句话。对比：

```text
外部 Cron：  系统管理员配一条 crontab，每天 2:00 跑一次任务
             Agent 是被动被执行的，自己不知道何时会被跑

Agent 自驱动：Agent 执行到某步，LLM 产出意图「2 小时后检查一次」
             节点代码解析意图 -> ctx.scheduler().scheduleResume(runId, 2h)
             Agent 主动约定了自己的未来
```

**决策权在 Agent（LLM 产出意图），执行权在节点代码（注册到调度器），触发权在调度器（到点自动 resume）**--还是「确定性骨架 + 不确定性血肉」的分工：LLM 决定「等多久、等什么」，节点代码决定「怎么注册」，调度器保证「到点必触发」。

### 5 个设计决策（每个都有明确的「为什么不做别的」）

**D1. TaskScheduler 包装 RunManager，不替代它。**
调度器只管「何时 resume」，执行全部委托给 Stage 6 的 RunManager。单一职责--如果调度器自己也会执行 Workflow，暂停-恢复-Checkpoint 的逻辑就要写两遍。

**D2. 定时恢复用 JDK 的 ScheduledExecutorService，不造轮子。**
崩溃后定时器会丢，但 Checkpoint 还在磁盘（Stage 6），重启后扫描 PAUSED 的 Run 重新注册即可。定时器的易失性被 Checkpoint 的持久性兜住。

**D3. 事件恢复用进程内 EventBroker（一个 Map + 回调），不上消息队列。**
教学目标是理解「事件驱动恢复」的机制。Kafka 是分布式的事，留给 Stage 11。

**D4. AsyncTaskQueue ≠ ParallelNode。**

```text
ParallelNode（Stage 5）：人画图时写死的并行，「我要并行 A、B、C」
AsyncTaskQueue（Stage 7）：Agent 运行时动态派活，「我觉得这事该拆成 3 个子任务」
```

静态并行进图定义，动态并行进队列。

**D5. 节点通过 `ctx.scheduler()` 拿到调度器。**
NodeContext 加一个方法，节点注册定时/事件后抛 PauseException 暂停。和 Stage 6 的 HumanApprovalNode 是同一个模式：**节点注册触发器 + 抛异常暂停，调度器到点恢复**。

### 隐藏的第六个哲学：调度器是 Stage 6「runId 关联存储」的落地

Stage 6 教程（[stage-6-usage-runid-correlation.md](stage-6-usage-runid-correlation.md)）讲过：「runId 不需要被记住，需要被存到能查回来的地方」。Stage 7 的调度器**就是**那个地方：

```text
scheduledResumes:  resumeId -> runId     （定时触达）
subscriptions:     eventKey -> runId     （事件触达）
```

---

## 二、概念与实体的映射关系

### agent-scheduler 模块（9 个类）

| 概念 | 类 | 类型 | 关键成员 |
|------|----|------|---------|
| 调度器门面 | `TaskScheduler` | class | `scheduleResume()` / `waitForEvent()` / `fireEvent()` / `enqueueTask()` / `setBudget()` |
| 定时恢复 | `ScheduledResume` | **record** | resumeId + runId + fireAt + recurring + interval |
| 事件恢复 | `EventTrigger` | **可变 class** | triggerId + runId + eventKey + timeout + `volatile firedAt` |
| 事件总线 | `EventBroker` | class | `subscriptions: Map<eventKey, List<trigger>>` + `firedKeys` + `eventPayloads` |
| 子任务队列 | `AsyncTaskQueue` | class | `pollNext()` 按优先级降序 + FIFO |
| 子任务 | `AsyncTask` | **record** | taskId + parentRunId + input + priority + status + workflowName |
| 任务状态机 | `TaskStatus` | enum | PENDING/RUNNING/WAITING_EVENT/WAITING_HUMAN/SUCCEEDED/FAILED/CANCELLED |
| 优先级 | `TaskPriority` | enum | LOW(1)/NORMAL(5)/HIGH(8)/URGENT(10) |
| 成本计数 | `TokenBudget` | class | limit + `consume()` + `isExceeded()` |

**为什么 EventTrigger 是可变 class 而 ScheduledResume 是 record？** 这是代码审查修复后的刻意设计：`firedAt` 有生命周期（注册时 null，fire 时填充），必须可变；且超时定时器要靠 `isFired()` 判断是否跳过二次 resume（竞态防护）。ScheduledResume 的字段注册后不变，record 恰当。

### 3 个节点类型（nodes/ 子包）

| 节点 | 概念 | 首次执行 | 恢复执行（isResuming） |
|------|------|---------|----------------------|
| `WaitEventNode` | 等外部事件 | `waitForEvent(runId, eventKey)` + 抛 PauseException | `hasEventFired()` ? 读 payload 透传 : 超时失败/re-pause |
| `ScheduleResumeNode` | 定时恢复 | `scheduleResume(runId, delay)` + 抛 PauseException | 直接透传（到点即恢复） |
| `DispatchTaskNode` | 动态派发子任务 | `enqueue()` N 个任务，**不暂停**，立即返回 | 同首次（无状态） |

### agent-workflow 模块的 3 个接入点（Stage 7 对旧代码的全部改动）

| 接入点 | 改动 | 为什么这样改 |
|--------|------|-------------|
| `NodeContext.scheduler()` | 新增 default 方法，返回 **Object** | 避免循环依赖：agent-scheduler 依赖 agent-workflow，反向引用会成环。代价是节点要 `instanceof` 转型（已知妥协，记入债务清单） |
| `GraphRuntime.scheduler(Object)` | 新增字段 + setter | Runtime 创建 NodeContext 时把 scheduler 传给节点 |
| `RunManager.setRuntime()` | runtime 从 final 变可变 + setter | 装配顺序需要：先建 RunManager，再建 Scheduler（要引用 RunManager），再回头把带 scheduler 的 Runtime 注入 |

---

## 三、代码架构

### 分层与依赖方向

```text
调用方（HTTP Controller / 示例 main）
   ↓
TaskScheduler（门面：何时恢复）
   ↓
RunManager（Stage 6：start/resume/cancel + Checkpoint）
   ↓
GraphRuntime（Stage 5/6：主循环 + 暂停/取消/恢复）
   ↓
WorkflowNode（节点执行）
   ↓ ctx.scheduler()
TaskScheduler（节点注册触发器 ← 回到顶层！）
```

**注意这条链是一个环**：调度器触发 resume -> Runtime 执行节点 -> 节点又向调度器注册新的触发器 -> 再次暂停。这不是缺陷，是异步 Agent 的本质：**Run 的生命周期由「执行段」和「等待段」交替构成，调度器管理等待段的出口**。装配上靠「先 RunManager、后 Scheduler、再 setRuntime 回注」打破构造期循环。

### 架构图

```mermaid
graph TB
    subgraph Scheduler["agent-scheduler（Stage 7 新增）"]
        TS["TaskScheduler 门面"]
        SE["ScheduledExecutorService<br/>（JDK 线程池，定时）"]
        EB["EventBroker<br/>subscriptions + firedKeys + payloads"]
        TQ["AsyncTaskQueue<br/>（优先级队列）"]
        TB["TokenBudget"]
        N1["WaitEventNode"]
        N2["ScheduleResumeNode"]
        N3["DispatchTaskNode"]
    end

    subgraph Workflow["agent-workflow（Stage 5/6 已有）"]
        RM["RunManager"]
        GR["GraphRuntime<br/>.scheduler(s)"]
        CS["CheckpointStore"]
    end

    TS --> SE
    TS --> EB
    TS --> TQ
    TS --> TB
    SE -->|到点| RM
    EB -->|fire -> resume| RM
    RM --> GR
    RM --> CS
    GR -->|ctx.scheduler()| N1 & N2 & N3
    N1 -.->|注册+暂停| EB
    N2 -.->|注册+暂停| SE
    N3 -.->|enqueue| TQ
```

### 模块内职责边界

```text
TaskScheduler  = 「何时恢复」的唯一入口（对节点和调用方）
EventBroker    = 「事件 -> runId」的关联存储 + 触发执行
AsyncTaskQueue = 「任务 -> 消费顺序」的存储（不含执行）
TokenBudget    = 「runId -> 剩余额度」的存储（不含强制中断）
节点           = 「注册什么触发器」的决策处（解析 LLM 意图）
```

每个类只有一个变化的理由。

### 代码审查修复的 3 个边界 bug（97 测试全绿）

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

**三个 bug 全部源于「状态机的不合法转换」**--异步代码的典型风险区。

---

## 四、数据流向

### 场景 A：事件驱动恢复（等 CI 通过）

```text
T0  用户请求 -> RunManager.start()
       └─> GraphRuntime: submit(✓) -> wait-ci(WaitEventNode 首次执行)

T1  WaitEventNode.execute(ctx)【isResuming=false】
       ├─ ctx.scheduler() 拿到 TaskScheduler
       ├─ scheduler.waitForEvent(runId, "ci-passed:pr-42")
       │    └─> EventBroker.subscribe: subscriptions["ci-passed:pr-42"] += trigger
       └─ throw PauseException
            └─> GraphRuntime catch: cursor="wait-ci" -> RunManager 存 Checkpoint
                 └─> 返回 PAUSED + ResumeToken（线程释放）

T2  ⏳ 30 分钟后，CI 通过，外部系统调 scheduler.fireEvent("ci-passed:pr-42", "all-green")
       ├─ firedKeys.add("ci-passed:pr-42")          ← Bug1 修复点：无条件记录
       ├─ eventPayloads["ci-passed:pr-42"] = "all-green"
       ├─ subscriptions.remove(key) -> 取出 trigger 列表
       └─ 对每个 trigger:
            ├─ trigger.markFired()                   ← Bug3 修复点：防超时定时器二次 resume
            └─ runManager.resume(runId)
                 ├─ 终态守卫（Bug2 修复点）
                 └─ GraphRuntime 从 cursor="wait-ci" 继续

T3  WaitEventNode.execute(ctx)【isResuming=true】
       ├─ scheduler.hasEventFired("ci-passed:pr-42") = true
       ├─ payload = scheduler.getEventPayload(...) = "all-green"
       └─ return NodeResult.of("all-green")          ← 事件 payload 变成下游 input

T4  merge 节点收到 ctx.input()="all-green" -> "merged with: all-green" -> END
```

**数据流关键**：事件的 payload 通过 `EventBroker.eventPayloads` 中转，恢复后节点读出、作为 output 写进黑板、流向下游节点。

### 场景 B：定时恢复（2 小时后检查）

```text
T0  check-later(ScheduleResumeNode 首次) 【isResuming=false】
       ├─ scheduler.scheduleResume(runId, Duration.ofHours(2))
       │    └─> executor.schedule(() -> doResume(runId, resumeId), 2h)
       │         scheduledResumes[resumeId] = sr
       └─ throw PauseException -> Checkpoint -> PAUSED

T+2h  线程池触发 doResume:
       ├─ runManager.getRun(runId).getStatus().isTerminal()?
       │    是 -> cancelFuture + 清理（防 recurring 空转）    ← 修复点
       └─ runManager.resume(runId) -> 从 cursor 继续

T+2h  check-later(ScheduleResumeNode 恢复)【isResuming=true】
       └─ return "resumed after PT2H" -> 下游继续
```

### 场景 C：异步任务队列（派发 3 个子任务）

```text
dispatch(DispatchTaskNode)
   ├─ taskProducer.apply(ctx) -> [URGENT-B, NORMAL-A, LOW-C]   ← Agent 运行时决定
   ├─ 逐个 scheduler.enqueueTask(task) -> TQ
   └─ return "dispatched 3 task(s)"（不暂停！主流程继续走）

消费方（另一个线程/另一个 Run）：
   while ((t = scheduler.pollNextTask()) != null)
      └─ pollNext: 按 priority.weight() 降序，同优先级按 createdAt FIFO
         -> URGENT-B, NORMAL-A, LOW-C
      └─ 每个 task 可用 t.workflowName 找到 Workflow -> runManager.start(wf, t.input)
         子任务本身又是一个完整的 Run（有自己的 runId、可暂停、可恢复）
```

### 数据流 4 条铁律（Stage 6 教程的延续）

```text
铁律 1（runId 关联）：节点从 ctx.runId() 取 runId -> 注册进调度器
     （subscriptions / scheduledResumes）-> 触发时查回 -> resume(runId)
     LLM 全程不接触 runId，只产出「等什么/等多久」的业务意图

铁律 2（payload 中转）：事件 payload 存 EventBroker -> 恢复节点读出
     -> 作为节点 output 写黑板 -> 成为下游 ctx.input()
     （payload 不进 Checkpoint，进的是触发器的注册表）

铁律 3（触发器先注册后暂停，恢复必有据）：节点永远先注册触发器、
     再抛 PauseException；恢复时必能从 firedKeys / 定时到点
     / isResuming 三者之一找到「为什么被唤醒」

铁律 4（终态单向门）：RunState 终态不可逆，resume 前必查 isTerminal()
     --三个修复的 bug 全部源于违反此律的状态滥用
```

### TaskStatus 状态机（队列侧的独立生命周期）

```text
PENDING -> RUNNING -> SUCCEEDED
              \-> FAILED
              \-> WAITING_EVENT/WAITING_HUMAN -> RUNNING
任意态 -> CANCELLED
```

注意这是**任务**的状态机，与 Run 的状态机（RUNNING/PAUSED/...）平行存在：一个 AsyncTask 被消费后会启动一个新 Run，任务状态反映的是「这个子任务的进度」，不等于 Run 内部状态。当前 v1 里两者是手动同步的（消费方负责），自动化同步是债务清单项。

---

## 附：遗留债务清单（审查产出）

| 项 | 现状 | 建议处理时机 |
|----|------|-------------|
| `NodeContext.scheduler()` 返回 Object + instanceof 转型 | 循环依赖妥协，类型不安全 | Stage 8+ 设计 SchedulerPort 接口 |
| `AsyncTaskQueue.pollNext()` O(n) 扫描 + 非原子 | 并发消费者可能丢失一次 poll | 生产化改 PriorityBlockingQueue |
| `EventBroker.eventPayloads` 永不清理 | 长期运行内存缓慢增长 | 加 TTL 或消费后清理 |
| `TokenBudget` 未接入 Runtime | 只是计数器，无自动消费/超限中断 | Stage 8 接入 AgentNode 上报 token |
| `Run.startTime` 未使用 | Stage 6 为 TimeoutPolicy 预留 | 实现 TimeoutPolicy 时启用 |
