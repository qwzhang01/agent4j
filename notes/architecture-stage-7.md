# Stage 7 架构设计：异步任务调度器

> 对应阶段：Stage 7 - 异步任务调度器
> 状态：✅ 已实现（2026-08-18）。2026-08-22 补：`TaskScheduler.schedule` + `GenerationTaskCoordinator` 把生视频接到事件恢复。
> 模块：新增 `agent-scheduler` Maven 模块，依赖 `agent-workflow`
> 前置：Stage 6 已完成（Checkpoint 暂停-恢复 + RunManager）

---

## 1. 核心命题：从「手动 resume」到「自动 resume」

Stage 6 做了 Checkpoint 暂停-恢复，但恢复是**手动**的：

```text
Stage 6：节点抛 PauseException -> 存 Checkpoint -> 返回 PAUSED
         调用方手动调 RunManager.resume(runId) -> 继续
```

问题是：**谁来调 resume？** 如果 Agent 要「等 CI 通过后继续」，CI 通过时谁来触发 resume？如果 Agent 要「2 小时后检查一次」，谁来计时？

Stage 7 的答案：**调度器自动触发 resume。**

```text
Stage 7：节点暂停时把 runId 注册到调度器（定时/事件）
         定时到达 / 事件到达 -> 调度器自动调 RunManager.resume(runId)
         Agent 不需要人手动 resume，调度器是「自动恢复的引擎」
```

一句话：**Stage 6 让 Run 能暂停-恢复，Stage 7 让 Run 能自动恢复。**

---

## 2. 两种自动恢复模式

```text
模式 1：定时恢复（ScheduledResume）
  Agent 说「2 小时后检查一次」
  -> 节点注册 ScheduledResume(runId, at=now+2h) 到调度器
  -> 调度器用 ScheduledExecutorService 计时
  -> 2 小时后 -> 调度器调 RunManager.resume(runId)

模式 2：事件驱动恢复（EventTrigger）
  Agent 说「等 CI 通过后继续」
  -> 节点注册 EventTrigger(runId, eventKey="ci-passed:pr-123") 到调度器
  -> 外部系统 CI 通过 -> 调度器.fire("ci-passed:pr-123")
  -> 调度器查到 runId -> 调 RunManager.resume(runId)
```

这两种模式就是 Stage 6 使用教程里讲的「runId 关联存储」的具体落地--调度器就是那个关联存储。

---

## 3. 核心抽象（8 个）

| 抽象 | 角色 | 一句话 |
|---|---|---|
| `TaskScheduler` | 调度器 | 门面：注册定时/事件恢复 + 管理 AsyncTaskQueue + 自动触发 resume |
| `ScheduledResume` | 定时恢复 | record：runId + 触发时间 + 可选重复策略 |
| `EventTrigger` | 事件恢复 | record：runId + eventKey + 注册时间 |
| `EventBroker` | 事件总线 | subscribe(eventKey, runId) / fire(eventKey) -> 触发 resume |
| `AsyncTaskQueue` | 子任务队列 | Agent 产出子任务入队，调度器按优先级消费 |
| `AsyncTask` | 子任务 | record：taskId + runId + input + priority + status + createdAt |
| `TaskStatus` | 状态机 | PENDING / RUNNING / WAITING_EVENT / WAITING_HUMAN / SUCCEEDED / FAILED / CANCELLED |
| `TaskPriority` | 优先级 | LOW / NORMAL / HIGH / URGENT（队列消费顺序） |

### 3.1 关键接口草图

```java
// ---- 调度器门面 ----
public class TaskScheduler {
    // 定时恢复
    ScheduledResume scheduleResume(String runId, Duration delay);
    ScheduledResume scheduleResume(String runId, Instant at);

    // 事件恢复
    EventTrigger waitForEvent(String runId, String eventKey);
    void fireEvent(String eventKey);              // 外部事件到达

    // 子任务队列
    String enqueueTask(AsyncTask task);            // Agent 产出子任务
    List<AsyncTask> pendingTasks();                // 查看待消费任务
    AsyncTask pollNextTask();                       // 按优先级取下一个

    // 启动调度器（内部 ScheduledExecutorService）
    void start();
    void shutdown();
}

// ---- 定时恢复 ----
public record ScheduledResume(
    String resumeId,
    String runId,
    Instant fireAt,                // 何时触发
    boolean recurring,             // 是否重复（如每 2 小时检查一次）
    Duration interval              // 重复间隔（recurring=true 时有效）
) {}

// ---- 事件恢复 ----
public record EventTrigger(
    String triggerId,
    String runId,
    String eventKey,               // 如 "ci-passed:pr-123"
    Instant registeredAt,
    Duration timeout,              // 超时未触发则 FAILED
    Instant firedAt                // null = 未触发
) {}

// ---- 子任务 ----
public record AsyncTask(
    String taskId,
    String parentRunId,            // 产出此任务的 Run
    Object input,                  // 子任务输入
    TaskPriority priority,
    TaskStatus status,
    Instant createdAt,
    Instant startedAt,
    Instant completedAt,
    String workflowName            // 用哪个 Workflow 跑这个子任务
) {}

// ---- 事件总线 ----
public class EventBroker {
    void subscribe(String eventKey, String runId);
    void fire(String eventKey);    // 触发所有订阅了这个 key 的 runId 恢复
    void fire(String eventKey, Object payload);  // 带数据触发
}
```

---

## 4. 关键设计决策（6 个）

### D1. TaskScheduler 包装 RunManager，不替代它

```text
调用方 -> TaskScheduler -> RunManager -> GraphRuntime

TaskScheduler 不直接执行 Workflow，它只管「何时 resume」
执行交给 RunManager（Stage 6 已实现）
```

**为什么**：Stage 6 的 RunManager 已经实现了 start/pause/resume/cancel，Stage 7 不重写这些，只加「自动触发 resume」的层。单一职责。

### D2. 定时恢复用 ScheduledExecutorService，不自己写定时器

```java
scheduler.schedule(() -> {
    runManager.resume(runId);
}, delay.toMillis(), TimeUnit.MILLISECONDS);
```

**为什么**：JDK 自带的调度器足够可靠，不需要造轮子。recurring 用 `scheduleAtFixedRate`。进程崩溃后定时器丢失，但 Checkpoint 还在--重启后可以扫描 PAUSED 状态的 Run 重新注册定时（Stage 6 的 CheckpointStore 做这个）。

### D3. 事件恢复用 EventBroker 订阅模型，不用消息队列

```text
EventBroker（进程内）：
  subscribe(eventKey, runId) -> 存 Map<eventKey, List<runId>>
  fire(eventKey) -> 查 Map -> 对每个 runId 调 resume

不做：
  - 消息队列（Kafka/RabbitMQ）-> 太重，教学型不需要
  - 跨进程事件 -> Stage 11 Multi-Agent 再做
```

**为什么**：教学型目标是理解「事件驱动恢复」的机制，不是搭建分布式事件系统。EventBroker 是一个内存 Map + 回调，简单清晰。跨进程留给后续。

### D4. AsyncTaskQueue 是「Agent 自驱动的任务派发」，不是 ParallelNode

```text
ParallelNode（Stage 5）：图定义时确定的并行，分支是固定的
AsyncTaskQueue（Stage 7）：运行时动态产出子任务，Agent 决定派什么活

区别：
  ParallelNode：人画图时就知道要并行 A、B、C
  AsyncTaskQueue：Agent 运行时说「我需要派 3 个子任务：查 A、查 B、查 C」
```

**为什么分开**：ParallelNode 是静态并行的表达（图结构），AsyncTaskQueue 是动态并行的表达（运行时决策）。两者不冲突，AsyncTask 内部可以用 ParallelNode 跑。

### D5. 节点怎么注册恢复触发器：扩展 NodeContext

```java
public interface NodeContext {
    // Stage 5-6 已有
    WorkflowState state();
    Object input();
    String runId();
    boolean isResuming();

    // Stage 7 新增
    default TaskScheduler scheduler() { return null; }  // 调度器入口
}
```

节点通过 `ctx.scheduler()` 拿到调度器，注册定时或事件恢复：

```java
// WaitEventNode：等 CI 通过
public NodeResult execute(NodeContext ctx) throws Exception {
    if (ctx.isResuming()) {
        // 恢复路径：检查黑板里是否有事件 payload
        Object payload = ctx.state().get("event:ci-passed:pr-123");
        return NodeResult.of(payload != null ? payload : "no event data");
    } else {
        // 首次：注册事件触发 + 暂停
        ctx.scheduler().waitForEvent(ctx.runId(), "ci-passed:pr-123");
        throw new PauseException(id, "waiting for CI");
    }
}
```

### D6. 成本控制先做 Token 计数器，不做复杂限流

```text
规划要求：每小时/每天 Token 上限
v1 实现：RunManager 记录每个 Run 的累计 token，超限则 FAILED
v1 不做：动态限流、按用户限流、按模型限流（留给 Stage 18）
```

**为什么**：成本控制的完整实现是 Stage 18（可观测与成本治理）的范围。Stage 7 只做最简单的「单 Run 累计 token 超限 -> FAILED」，证明机制可行即可。

---

## 5. 分层架构图

```mermaid
graph TB
    subgraph Examples["examples 模块"]
        Example["SchedulerExample<br/>定时恢复 + 事件恢复 demo"]
    end

    subgraph SchedulerModule["agent-scheduler 模块（Stage 7 新增）"]
        TaskScheduler["TaskScheduler<br/>━━━━━━━━━━━━━━━━<br/>门面：定时/事件/队列"]
        ScheduledResume_["ScheduledResume<br/>定时恢复 record"]
        EventTrigger_["EventTrigger<br/>事件恢复 record"]
        EventBroker["EventBroker<br/>事件总线<br/>subscribe/fire"]
        AsyncTaskQueue["AsyncTaskQueue<br/>子任务队列"]
        AsyncTask["AsyncTask<br/>子任务 record"]
        TaskStatus["TaskStatus / TaskPriority"]
        TokenBudget["TokenBudget<br/>成本计数器（D6）"]
        SchedulerNodes["新节点类型<br/>WaitEventNode / ScheduleResumeNode / DispatchTaskNode"]
    end

    subgraph WorkflowModule["agent-workflow 模块（Stage 6 已有）"]
        RunManager["RunManager<br/>start/pause/resume/cancel"]
        GraphRuntime["GraphRuntime"]
        CheckpointStore["CheckpointStore"]
        NodeContext["NodeContext<br/>（Stage 7 加 scheduler()）"]
        PauseException["PauseException"]
    end

    Example --> TaskScheduler
    TaskScheduler --> RunManager
    TaskScheduler --> EventBroker
    TaskScheduler --> AsyncTaskQueue
    TaskScheduler --> TokenBudget
    ScheduledResume_ --> TaskScheduler
    EventTrigger_ --> EventBroker
    AsyncTask --> AsyncTaskQueue
    SchedulerNodes -.->|ctx.scheduler()| TaskScheduler
    SchedulerNodes -.->|抛 PauseException| GraphRuntime
    EventBroker -->|fire -> resume| RunManager
    RunManager --> GraphRuntime
    RunManager --> CheckpointStore
```

依赖关系：`agent-scheduler -> agent-workflow`（只用 RunManager + NodeContext + PauseException）。

---

## 6. 两种恢复模式的完整时序

### 6.1 定时恢复

```text
T0: Agent 说「2 小时后检查」
    -> ScheduleResumeNode 执行
    -> ctx.scheduler().scheduleResume(runId, Duration.ofHours(2))
    -> 抛 PauseException
    -> RunManager 存 Checkpoint -> PAUSED

T0+2h: 调度器 ScheduledExecutorService 触发
    -> runManager.resume(runId)
    -> GraphRuntime 从 cursor 继续
    -> ScheduleResumeNode 重跑（isResuming=true）-> 透传 -> 继续
```

### 6.2 事件驱动恢复

```text
T0: Agent 说「等 CI 通过」
    -> WaitEventNode 执行
    -> ctx.scheduler().waitForEvent(runId, "ci-passed:pr-123")
    -> 抛 PauseException
    -> RunManager 存 Checkpoint -> PAUSED

T0+30min: CI 通过
    -> 外部系统调 scheduler.fireEvent("ci-passed:pr-123")
    -> EventBroker 查到 runId
    -> runManager.resume(runId)
    -> WaitEventNode 重跑（isResuming=true）-> 读事件 payload -> 透传 -> 继续
```

---

## 7. 模块结构

```text
agent-scheduler/
└── src/main/java/io/github/qwzhang01/agent/scheduler/
    ├── TaskScheduler.java          # 门面
    ├── ScheduledResume.java        # 定时恢复 record
    ├── EventTrigger.java           # 事件恢复 record
    ├── EventBroker.java            # 事件总线
    ├── AsyncTaskQueue.java         # 子任务队列
    ├── AsyncTask.java              # 子任务 record
    ├── TaskStatus.java             # 状态机枚举
    ├── TaskPriority.java           # 优先级枚举
    ├── TokenBudget.java            # 成本计数器
    └── nodes/
        ├── WaitEventNode.java      # 等事件节点
        ├── ScheduleResumeNode.java # 定时恢复节点
        └── DispatchTaskNode.java   # 派发子任务节点
```

---

## 8. 实现里程碑

| # | 里程碑 | 交付 | 验证 |
|---|--------|------|------|
| M7.1 | 核心抽象 + 定时恢复 | TaskScheduler + ScheduledResume + ScheduledExecutorService | 注册 2 秒后恢复 -> 自动 resume -> SUCCEEDED |
| M7.2 | 事件驱动恢复 | EventBroker + EventTrigger + WaitEventNode | 注册事件 -> fire -> 自动 resume -> SUCCEEDED |
| M7.3 | 子任务队列 | AsyncTaskQueue + AsyncTask + DispatchTaskNode | 派 3 个子任务 -> 按优先级消费 -> 各自完成 |
| M7.4 | 成本控制 | TokenBudget + Run 级 token 超限 | 跑到 token 超限 -> FAILED |
| M7.5 | 验收示例 + 全测试 | SchedulerExample（定时 + 事件 + 子任务 demo）| 验收标准达标 |

---

## 9. 验收标准（对齐规划）

```text
1. Agent 接受一个需要等待外部事件的任务（如等 CI 通过）
   -> WaitEventNode 注册事件 -> 暂停 -> CI 通过 fire -> 自动恢复 ✅

2. Agent 可以约定"2 小时后检查"并自动恢复
   -> ScheduleResumeNode 注册定时 -> 暂停 -> 2 小时后自动恢复 ✅
   （demo 用 2 秒代替 2 小时）

3. 多个子任务可以并行入队和分别完成
   -> DispatchTaskNode 派 3 个子任务入队 -> 调度器按优先级消费 -> 各自完成 ✅
```

---

## 10. 测试策略

- **定时恢复**：注册 100ms 后恢复 -> Thread.sleep(200) -> 断言 Run 已 SUCCEEDED（自动恢复，非手动 resume）
- **事件恢复**：注册事件 -> fire -> 断言 Run 已 SUCCEEDED
- **事件超时**：注册事件 + 100ms 超时 -> 不 fire -> 断言 FAILED(timeout)
- **子任务队列**：入队 3 个任务（不同优先级）-> 消费顺序 = URGENT > HIGH > NORMAL > LOW
- **成本控制**：设 token 上限 100 -> 跑到 150 -> 断言 FAILED(token_exceeded)
- **崩溃恢复**：注册定时 -> 关闭调度器 -> 新建调度器 + 扫描 PAUSED Run -> 重新注册 -> 恢复

---

## 11. 本阶段不做（范围控制）

- **跨进程事件**（Kafka/RabbitMQ）-- EventBroker 是进程内的，跨进程留给 Stage 11
- **分布式调度**（多节点调度器协调）-- 单进程够教学
- **复杂限流**（按用户/模型/时间窗限流）-- Stage 18
- **任务看板 UI** -- Stage 13 声明式层
- **任务依赖图**（子任务之间的 DAG 依赖）-- v2，当前子任务独立
- **抢占式调度**（高优先级任务打断低优先级）-- v2，当前是队列消费
