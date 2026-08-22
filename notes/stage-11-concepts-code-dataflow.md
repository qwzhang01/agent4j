# Stage 11 概念、映射与数据流（Multi-Agent 与 A2A 编排）

> 对应阶段：Stage 11 - Multi-Agent 与 A2A 协议
> 定位：概念 -> 概念与类映射 -> 数据流向（三段式，与 Stage 5/6/7 笔记同体系）
> 配套：架构设计见 [architecture-stage-11.md](architecture-stage-11.md)，文章素材见 [stage-11-articles.md](stage-11-articles.md)，AgentWorker 契约与失败语义以本笔记为准（源码可读 `agent-orchestrator` 模块）
> 状态：已实现（2026-08-22）。orchestrator 45 测试全绿，全仓 348 零影响；MultiAgentExample 验收跑通

---

## 一、核心概念（10 个）

### 总纲：从「单个 Agent」到「责任网络」

```
Stage 1-10：造好一个单 Agent Runtime（loop/工具/记忆/治理/连接）
Stage 11：一个 Agent 有天花板 -> 按责任拆成一支团队 -> 编排层把团队组织起来

三重天花板（拆分的"为什么"）：
  1. 上下文天花板 -- 一个 loop 的窗口装不下"研究 + 写码 + 审查"三份工作记忆
  2. 注意力天花板 -- 一个 system prompt 塞三种人格，模型每种都做不精
  3. 权限天花板   -- 只读与写权限在同一个 Agent 上只能取并集（权限蠕变）

Multi-Agent 的答案：每个 Worker 是完整的小 Agent（自己的 loop/工具/权限/记忆），
Supervisor 只负责"把任务给对人、把结果拼回来"。
```

### 10 个概念

| # | 概念 | 一句话 |
|---|------|--------|
| 1 | **三重天花板** | 上下文/注意力/权限 —— 单 Agent 装不下、做不精、权限收不住 |
| 2 | **责任拆分** | 拆的是"责任"不是"步骤"；步骤拆分是 Workflow 的事 |
| 3 | **Workflow vs Multi-Agent** | 编排步骤（确定性）vs 编排责任（有自主性）；步骤没有自主性，责任必须有人兜 |
| 4 | **Supervisor 不干活** | 只做三件事：路由 + 聚合 + 失败策略；零业务逻辑 |
| 5 | **Worker 两条铁律** | `execute()` 永不抛异常（失败是数据）；每次调用只执行一次（重试/超时是编排策略） |
| 6 | **消息传递优先** | WorkerTask 自包含进、WorkerResult 自包含出，Worker 间零共享 -> 并行安全免费（无锁） |
| 7 | **失败两正交维度** | 一个失败对其他任务的影响（FAIL_FAST/BEST_EFFORT）× 失败后自己怎么办（预算在任务、退避在编排） |
| 8 | **统一抽象（D1）** | 编排层只认 `AgentWorker` 接口；内外差异（方法调用 vs 协议委托、信任高 vs 低）封装在实现里 |
| 9 | **信任降级（D5）** | 外部 Agent 输出 = 不可信输入，进聚合前必经净化；拦截是转失败数据不是崩溃 |
| 10 | **演进克制（D2）** | v1 静态编排（代码决定谁干什么）-> v2 LLM 驱动分派；与 Stage 7 演进同构：先证明骨架，再让模型接管 |

### 概念之间的关系

```
三重天花板 ── 拆分的"为什么"
   │
   ▼
责任拆分（Worker = 有自主性的完成者）
   │
   ├─ Worker 铁律（永不抛异常 / 每次一次）── 失败隔离（D4）的基石
   │
   ├─ 消息传递（任务进/结果出）── 并行安全 + 聚合单点
   │
   ├─ 统一抽象（内外一个接口）── 路由无差别，信任分等级
   │
   └─ 演进克制（v1 静态 -> v2 LLM）── 先骨架后智能
   │
Workflow 边界：确定性步骤归 Workflow（Stage 5），自主性责任归 Multi-Agent（Stage 11）
```

### 三条边界（面试高频）

```
Workflow vs Multi-Agent： 步骤（确定性控制流）      vs 责任（有自主性的完成者）
Multi-Agent vs 多窗口：   框架 Supervisor           vs 人肉 Supervisor（用户当编排器）
内部 vs 外部（A2A）：     方法调用高信任            vs 协议委托低信任
```

---

## 二、概念与类的映射

### agent-orchestrator 模块（10 个类）+ agent-mcp 微扩（2 个）

| 概念 | 类 | 类型 | 关键成员 | 一句话 |
|------|----|------|---------|--------|
| 统一 Worker 抽象 | `AgentWorker` | 接口 | `name()` / `card()` / `execute(WorkerTask)` | 编排层只认这一个接口；javadoc 写明两条铁律 |
| 内部 Worker | `InternalAgentWorker` | class | 包 core `Agent`；`execute` = `agent.run(prompt, state)` | 同 JVM 方法调用，高信任，不净化 |
| 外部 Worker | `ExternalAgentWorker` | class | 包 `A2AClient`；`execute` = `sendTask(A2ATask)` + 净化 | 协议委托，低信任，出站必经净化器 |
| 自包含任务 | `WorkerTask` | **record** | taskId/workerName/taskType/payload/**timeoutMs**/**maxRetries** | payload 进；约束字段由 Supervisor 消费（M11.3） |
| 自包含回执 | `WorkerResult` | **record** | taskId/workerName/success/output/error/durationMs/attempts/totalTokens | 成败是数据不是异常；记账 = durationMs + totalTokens |
| 编排器 | `AgentSupervisor` | class | `register()` / `discoverWorkers()` / `dispatchAll()` / `dispatchBySkill()` / `findWorkerBySkill()` | Worker 池 + 并行派发 + 失败策略执行者 |
| 编排回执 | `SupervisorResult` | **record** | allSucceeded/totalTasks/succeeded/failed/aggregated/results/durationMs | 逐 Worker 明细（可审计）+ 聚合输出 |
| 失败策略 | `FailurePolicy` | **record** | Mode enum（FAIL_FAST/BEST_EFFORT）+ retryBackoffMs | 两正交决策打包；退避节奏全局 |
| 聚合接口 | `ResultAggregator` | @FunctionalInterface | `aggregate(List<WorkerResult>) -> String` | 并行输出唯一汇合点，单线程聚合 |
| 聚合实现 ×2 | `ConcatAggregator` / `FirstSuccessAggregator` | class | 拼接（失败行内标记）/ 首个成功（竞速） | 策略可换：投票/LLM 摘要 v2 同接口扩展 |
| A2A 客户端 | `InProcessA2AClient`（agent-mcp） | class | `registerAgent()` / `sendTask()` / `getTaskStatus()` / `sendMessage()` | 协议数据模型 100% 对齐，传输是假的（D6） |
| 能力声明 | `AgentCard`（agent-mcp） | **record** | name/description/skills/endpoint/version | 自报家门，只做路由依据，不做信任依据（D7/D5） |

**三个值得记住的设计选择：**

1. **为什么 WorkerTask/WorkerResult 是 record（不可变）？** 消息传递优先（D3）的物理形态——不可变 = 并行安全免费送，无锁、无竞争条件。
2. **为什么 InternalAgentWorker 读 `AgentState.Status` 而不是 agent 返回的占位文本？** core Agent 契约把错误编码在 state（`[Agent error: ...]` 只是文本约定），读状态不读字符串，避免依赖不稳定约定。
3. **为什么 orchestrator 不依赖 agent-security？** 净化器是 `UnaryOperator<String>`，由组装层注入 Stage 9 实现——模块边界纪律（同 agent-mcp），安全能力是插件不是依赖。

### 依赖链

```
agent-orchestrator -> agent-mcp -> agent-core
（编排器不依赖 workflow/scheduler/memory/security —— 正交关系，见 D8）
```

---

## 三、数据流向

### 场景 A：`dispatchAll` 并行派发（核心主流程）

```
【T0 注册】
  supervisor.register(InternalAgentWorker.of("researcher", researchAgent, "research"))
  supervisor.register(InternalAgentWorker.of("executor",   execAgent,   "code"))
  supervisor.register(new ExternalAgentWorker("reviewer", a2aClient, card, sanitizer))
  // workers: LinkedHashMap（注册序保序——skill 路由的确定性"先匹配"）

【T1 构造任务（自包含 payload，D3）】
  tasks = [
    WorkerTask.of("researcher", "research", "调研 X 库的 API"),
    WorkerTask.of("executor",   "code",     "写一个最小调用示例"),
    WorkerTask.of("reviewer",   "review",   "审查上面的代码"),
  ]

【T2 dispatchAll(tasks, new ConcatAggregator(), FailurePolicy.bestEffort())】
  ├─ 第一步：全量提交（并行）
  │    for i in tasks:
  │      worker = workers.get(task.workerName())
  │      worker != null -> futures[i] = executor.submit(() -> executeWithPolicies(worker, task, policy))
  │      worker == null -> futures[i] 留 null（未知 worker 的失败是"预计算数据"，不是异常）
  │
  ├─ 第二步：按任务序收集（结果列表镜像任务列表，不是完成序！）
  │    for i in tasks:
  │      future == null -> WorkerResult.failure("unknown worker ...")
  │      否则           -> awaitQuietly(future)   // 所有异常形态都转成 failure data
  │      FAIL_FAST 模式 && result 失败:
  │        failFastTrigger = taskId
  │        剩余 futures 全部 cancel(true) + 标记 "cancelled (FAIL_FAST...)" 失败数据
  │
  └─ 收尾：aggregator.aggregate(results) -> SupervisorResult(results, aggregated, elapsed)

【T3 数据流关键语义】
  被取消的任务不是消失，而是变成一条 success=false 的回执 —— "永不抛异常"契约全场成立
  wall clock ≈ max（并行），不是 sum
```

### 场景 B：`executeWithPolicies`（单任务的重试 + 超时，M11.3）

```
maxAttempts = 1 + task.maxRetries()     // 预算在任务（WorkerTask.maxRetries）
for attempt in 1..maxAttempts:
  ├─ 无 timeout（快路径）：worker.execute(task) 直接内联，零开销
  ├─ 有 timeout（隔离路径）：每次尝试单独 submit 一个 future
  │     attemptFuture.get(task.timeoutMs()) -> TimeoutException -> cancel(true)
  │     -> WorkerResult.failure("timed out after ... ms")     // 超时 = 一次可重试的失败
  ├─ 成功 -> withAttempts(result, attempt) 返回
  └─ 失败且还有预算 -> Thread.sleep(policy.retryBackoffMs())   // 退避节奏在编排器
withAttempts：修正 attempts 计数 —— Worker 永远报 1，真实次数由编排层回填
```

### 场景 C：外部 Worker 的 A2A 委托 + 信任降级（D5/D6）

```
ExternalAgentWorker.execute(task):
  ├─ toA2ATask: WorkerTask -> A2ATask（taskId/recipient/taskType/payload/deadline=timeout 换算）
  ├─ a2aClient.sendTask(a2aTask)
  │    └─ InProcessA2AClient（agent-mcp）:
  │         agents.get(recipient) == null -> throw IllegalArgumentException（远程调用形态）
  │         否则在调用线程同步跑 agent.run(prompt, state)
  │         state 是 ERROR/MAX_STEPS_EXCEEDED -> throw IllegalStateException
  │         taskStatus: running -> completed / failed
  ├─ 取输出 {"output": "..."} -> outputFrom()
  ├─ D5 净化：outputSanitizer.apply(output)        // 外部输出 = 不可信输入
  │    净化器抛异常（BLOCK）-> catch RuntimeException -> WorkerResult.failure  ← 拦截不是崩溃
  └─ WorkerResult.success(task, 净化后 output, elapsed, 1, 0)
```

### 场景 D：skills 路由（M11.4, D7，fail-closed）

```
dispatchBySkill("review", payload):
  ├─ findWorkerBySkill: 按注册序遍历 worker.card().skills()，包含匹配命中
  ├─ 命中 -> WorkerTask.of(worker.name(), ...) -> executeWithPolicies（内外 Worker 同一套 API）
  └─ 无命中 -> WorkerResult.failure("no worker with skill 'review' (available skills: ...)")
       // fail-closed：明确报错 + 列出可用技能，绝不静默 no-op
```

### MultiAgentExample 全景（一次完整数据流）

```
组装 3 个 Agent（researcher/executor/reviewer）
  reviewer 用 MockModelClient.scripted() 故意注入 "ignore previous instructions"（D5 演示）
  -> InProcessA2AClient.registerAgent("reviewer", reviewer, "review")
  -> ExternalAgentWorker 构造时注入 Stage 9 DefaultResultSanitizer

[1] 能力发现：discoverWorkers() 打印三张 AgentCard（name/skills/endpoint）
[2] 三路并行：dispatchAll(3 tasks, ConcatAggregator, BEST_EFFORT)
      reviewer 的输出在聚合前被净化器打出 "Injection detected" 并 [REDACTED]
      wall clock ≈ max ≈ 2ms，per-task 之和远大于墙钟
[3] 聚合输出：ConcatAggregator 拼接，[reviewer] 段已是净化后的文本
[4] skills 路由：dispatchBySkill("review") 命中外部 Worker，API 与内部无差别
[5] 失败重试：flaky worker 第 1 次抛异常 -> maxRetries=1 -> 第 2 次成功 -> attempts=2
```

---

## 四、设计决策精要（D1-D8 对照代码位置）

| # | 决策 | 落点 |
|---|------|------|
| D1 | Worker 统一抽象，内外无差别 | `AgentWorker` 接口 + 两个实现；装饰器哲学第三次兑现（前两次：Stage 9 GovernedToolExecutor / Stage 10 ManagedMcpClient） |
| D2 | v1 静态编排 + 显式并行，不做 LLM 分派 | `dispatchAll` 调用方显式构造任务列表；演进同 Stage 7（TaskScheduler -> LlmDrivenScheduler） |
| D3 | 消息传递优先，共享状态最小化 | `WorkerTask`/`WorkerResult` record 不可变；共享去 Workflow 黑板；聚合单点 |
| D4 | Worker 失败隔离：单崩不炸编排 | execute 永不抛异常契约 + `awaitQuietly` 兜底 + FAIL_FAST/BEST_EFFORT |
| D5 | 外部信任降级 | `ExternalAgentWorker.outputSanitizer`（UnaryOperator 注入，抛异常=BLOCK 转 failure data） |
| D6 | A2A v1 进程内实现，协议对齐优先 | `InProcessA2AClient implements A2AClient`；传输 v2 换 HTTP 不动调用方 |
| D7 | AgentCard skills 路由，fail-closed | `findWorkerBySkill` + `dispatchBySkill` 无命中返回失败数据 |
| D8 | 编排不替代 Workflow，二者正交 | orchestrator 不依赖 workflow；组合姿势：Workflow 节点可调 dispatchAll |

**两条铁律再强调**（面试金句的代码依据）：

> "我的编排器是策略层不是业务层。Worker 的契约是执行一次、如实报告、永不抛异常——这个契约让失败隔离天然成立。"

---

## 五、测试即文档（SupervisorFailureTest 10 用例对照）

| 测试用例 | 验证 | 实现对应 |
|---------|------|---------|
| `retry_transientFailure_recoversWithAttemptCount` | 前 2 败第 3 成，attempts=3 | `executeWithPolicies` 循环 + `maxAttempts=1+maxRetries` |
| `retry_exhausted_reportsFailureWithAttempts` | 预算耗尽报失败且计数正确 | 循环走完 + `withAttempts` |
| `retry_backoff_pausesBetweenAttempts` | 退避 150ms 真的延迟（duration≥140） | `Thread.sleep(policy.retryBackoffMs())` |
| `retry_zeroBudget_failsImmediately` | maxRetries=0 只调一次（calls=1） | `maxAttempts = 1 + task.maxRetries()` |
| `timeout_slowWorker_failsFastWithTimeoutError` | 500ms worker + 100ms 预算，不等完（elapsed<450） | 嵌套 future + `get(timeoutMs)` + `cancel(true)` |
| `timeout_countsAsRetryableAttempt` | 超时算一次失败尝试，重试成功（attempts=2） | TimeoutException 分支后继续走重试逻辑 |
| `failFast_firstFailure_cancelsRemainingTasks` | 2 个 600ms 慢任务被取消（elapsed<450，全 failure 数据） | 收集循环 `failFastTrigger` + `cancel(true)` |
| `failFast_allSucceed_behavesNormally` | 全成功时 FAIL_FAST 无副作用 | 收集循环无短路 |
| `bestEffort_failureDoesNotAffectOthers` | 1 败 2 成，聚合含成功输出 | 无短路，失败只是数据 |
| `failurePolicy_factories_andValidation` | 工厂方法 + 参数校验（-1 拒绝） | `FailurePolicy` record 构造器 |

**测试 worker 全是匿名 `AgentWorker`**（flakyWorker/failingWorker/slowWorker/slowThenFastWorker）—— 接口抽象让测试可以任意注入失败行为，这也是 D1 统一抽象的测试红利。

---

## 六、本阶段不做（范围控制，v2 路线）

- LLM 驱动分派（Supervisor 本身是 Agent 读 AgentCard 分配）
- 真实 HTTP/gRPC A2A 传输（v1 进程内，协议对齐优先）
- AgentCard 网络发现（/.well-known）
- 共享黑板 / SharedState 完整实现（消息传递优先，需要时用 Workflow）
- Worker 间直接通信 P2P 消息网（v1 只走 Supervisor 星型拓扑）
- 成本分摊算法（v1 只记账 durationMs/totalTokens，Stage 18 成本仪表盘）
- 跨编排器嵌套 / 动态 Worker 热插拔

---

## 附：一句话总结

```
概念：单 Agent 有天花板 -> 按责任拆 Worker -> Supervisor 只做路由/聚合/失败
      消息传递默认、共享是显式选择、失败是数据不是异常、外部输出必经净化
映射：AgentWorker(统一接口) + Internal/External(两实现) + WorkerTask/WorkerResult(不可变消息)
      + AgentSupervisor(并行派发) + FailurePolicy/ResultAggregator(策略注入)
      + InProcessA2AClient(假传输真协议) + AgentCard(自报家门)
数据流：任务自包含进 -> 并行执行 -> 按任务序收成回执 -> 单点聚合
       -> 成败全程是数据、永不抛异常 -> 被取消/被拦截也都是数据
一句话：所有不确定性（谁失败/谁超时/外部是否可信）都收敛成"数据"，
        所有确定性策略（怎么重试/怎么聚合/怎么取消）都放在编排层。
```
