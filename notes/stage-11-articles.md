# Stage 11 文章素材库：Multi-Agent 与 A2A 编排（6 篇）

> 阶段：Stage 11（✅ 已完成，2026-08-22）
> 用途：六篇文章的素材 + 大纲，供后续扩写为公众号文章 / 知乎回答
> 原则：每篇连接一个工程问题（学习规划原则 2），全部引用**已实现的真实代码/测试**，不是纸上概念
> 实证底数：全仓 348 测试全绿；orchestrator 45 测试；MultiAgentExample 实测（并行 2ms≈max / 注入 [REDACTED] / flaky attempts=2）

---

## 素材总览表

| # | 标题 | 核心论点 | 最强实证 |
|---|---|---|---|
| 1 | 什么时候需要 Multi-Agent | 不是潮流，是天花板逼出来的选择；拆上下文不拆步骤 | InternalAgentWorker 各自 AgentConfig |
| 2 | Orchestrator 和 Worker 如何分工 | Supervisor 不干活，只"给对人、拼回来" | AgentWorker.execute 契约 |
| 3 | 共享状态和消息传递的取舍 | 并行安全的秘诀是不共享 | WorkerTask/WorkerResult 自包含 |
| 4 | 通信、失败和结果聚合 | 失败是生死线，聚合是策略不是算法 | SupervisorFailureTest 10 用例 |
| 5 | 内外部 Agent 的信任边界与成本归属 | 统一抽象 + 差异化信任 | MultiAgentExample [REDACTED] |
| 6 | 为什么多 Agent 不是多开几个聊天窗口 | 编排层是 Multi-Agent 的灵魂 | MultiAgentExample 全景 |

---

## 文章 1：《什么时候需要 Multi-Agent》

**一句话立意**：Multi-Agent 不是架构时髦，是单 Agent 天花板逼出来的工程选择。

### 核心论点

1. **单 Agent 三重天花板**（这是全文的"为什么"）
   - 上下文天花板：一个 ReAct loop 的窗口装不下"研究 + 写码 + 审查"三份工作记忆
   - 注意力天花板：一个 system prompt 塞三种人格，模型每种都做不精
   - 权限天花板：研究只读 + 执行可写，在同一个 Agent 上只能取并集（权限蠕变）

2. **三个"需要"信号**（判断 checklist）
   - 任务天然可并行（同时查 3 个数据源）
   - 需要不同权限/人格的阶段（研究只读 → 执行可写 → 审批人工）
   - 上下文装不下（每个子任务有独立的大量中间产物）

3. **三个"不需要"信号**（反过度设计）
   - 单线程任务（问答/检索/单文件修改）
   - 延迟敏感（多一层编排多一倍延迟）
   - 工具少于 ~10 个，一个 Agent 管得过来

4. **判断口诀**：拆"上下文"，不拆"步骤" —— 步骤拆分是 Workflow 的事。

### 代码锚点

- `InternalAgentWorker.of(name, agent, skills...)`：每个 Worker 包一个独立的 `AgentConfig`（自己的 systemPrompt + toolRegistry + maxSteps），这就是"按责任拆分"的落点
- `AgentSupervisor.register()` 的能力清单 `discoverWorkers()`：能力声明是拆分的可视化

### 常见误区

- "Agent 越多越强大"——错，编排开销 + 沟通损耗随 N 增长，超过需要的 N 是负资产
- "多步任务就要 Multi-Agent"——错，确定性多步是 Workflow（Stage 5），有自主性的责任委托才是 Multi-Agent

### 面试金句

> "我拆 Multi-Agent 的判据是天花板，不是炫技：上下文装不下、权限要分级、任务能并行，三条满足才拆。拆的是责任不是步骤——步骤拆分我交给 Workflow。"

---

## 文章 2：《Orchestrator 和 Worker 如何分工》

**一句话立意**：Supervisor 不干活，只负责"把任务给对人、把结果拼回来"。

### 核心论点

1. **职责二分**
   - Worker = 有自主性的完成者：自己的 loop、工具集、记忆命名空间；执行一次、如实报告
   - Supervisor = 路由 + 聚合 + 失败策略；没有任何"业务逻辑"

2. **Worker 的两条铁律**（`AgentWorker` 接口契约，全文的"核心抽象"）
   - `execute()` 永不抛异常——失败是 `WorkerResult` 数据不是异常（失败隔离的基石）
   - 每次调用执行一次——重试/超时/取消是 Supervisor 的策略，不是 Worker 的逻辑

3. **v1 静态编排的克制（D2）**
   - v1：调用方显式构造任务列表，代码决定谁干什么
   - v2：Supervisor 本身是 Agent（LLM 读 AgentCard 分配）
   - 演进路径与 Stage 7 完全同构（先 TaskScheduler 后 LlmDrivenScheduler）——先证明骨架，再让模型接管

### 代码锚点

- `AgentWorker` 接口 javadoc 里的契约条款（never throws / once per call）
- `AgentSupervisor.dispatchAll(tasks, aggregator, policy)`：全量 submit 并行 + 按任务序收集
- `WorkerTask` 的 `timeoutMs` / `maxRetries` 是"约束字段"，消费方是 Supervisor（M11.3）

### 常见误区

- 把 Supervisor 写成上帝对象（所有逻辑都塞进去）——正确姿势是策略下沉到 Worker/聚合器/策略类
- Worker 里做重试——重试是编排策略，Worker 只报告成败

### 面试金句

> "我的编排器是策略层不是业务层。它只做三件事：路由、聚合、失败处理。Worker 的契约是执行一次、如实报告、永不抛异常——这个契约让失败隔离天然成立。"

---

## 文章 3：《共享状态和消息传递的取舍》

**一句话立意**：并行安全的秘诀是不共享。

### 核心论点

1. **消息传递优先（D3）**
   - `WorkerTask` 自包含进，`WorkerResult` 自包含出，Worker 之间零共享可变状态
   - 并行天然无锁，无竞争条件、无死锁

2. **共享状态是显式选择，不是默认配置**
   - 需要跨 Worker 共享时，去 Workflow 的黑板（`WorkflowState`，Stage 5 已实现）
   - 完整 SharedState（并发安全 + 命名空间 + 版本控制）v2/Stage 12 再议

3. **结果合并推迟到单点**
   - `ResultAggregator` 是并行输出唯一的汇合点，单线程聚合——"谁改状态谁就串行化"

### 代码锚点

- `WorkerTask` / `WorkerResult` 都是 record（不可变），无共享可变字段
- `ConcatAggregator.aggregate()` / `FirstSuccessAggregator.aggregate()`：聚合是纯函数，输入 List<WorkerResult> 输出 String

### 常见误区

- 黑板模式是默认配置——错，默认消息传递，黑板是例外
- "为了性能共享状态"——现代 JVM 下无锁消息传递通常比加锁共享更快，且正确性免费

### 面试金句

> "我默认消息传递：任务进、结果出、Worker 之间零共享，并行安全是免费送的。共享状态我只在 Workflow 黑板里显式用——需要共享就换编排范式，而不是给默认范式加锁。"

---

## 文章 4：《Multi-Agent 的通信、失败和结果聚合》

**一句话立意**：失败处理是生死线，聚合是策略不是算法。

### 核心论点

1. **三种通信形态**
   - 任务委托（`WorkerTask` / `A2ATask`）：有明确目标和回执
   - 结果回执（`WorkerResult` / 聚合后 `SupervisorResult`）：成败是数据
   - 消息（`A2AMessage`）：v1 诚实不投递（真消息队列 v2）——不是所有通信都要立刻做完

2. **失败隔离 D4**：单 Worker 崩溃不炸编排
   - `FAIL_FAST`：一个最终失败取消其余（付款流程——校验失败就别扣款）
   - `BEST_EFFORT`：失败隔离，其余照常（多源检索——一个镜像挂了没事）

3. **重试的"预算/节奏分离"**
   - 预算在任务（`WorkerTask.maxRetries`——调用方知道值不值得重试）
   - 节奏在编排（`FailurePolicy.retryBackoffMs`——全局节流）
   - 超时 = 一次可重试的失败尝试（`timeout_countsAsRetryableAttempt` 实测）

4. **聚合是策略**：`ConcatAggregator`（报告式，每个声音都听到）/ `FirstSuccessAggregator`（竞速式，首个成功）；投票、LLM 摘要 v2 同接口扩展

### 代码锚点（这章实证最硬）

- `SupervisorFailureTest` 10 个用例，每个都是活的例子：
  - `retry_transientFailure_recoversWithAttemptCount`：前 2 败第 3 成，attempts=3
  - `timeout_slowWorker_failsFastWithTimeoutError`：500ms worker + 100ms 预算，elapsed<450 不等完
  - `failFast_firstFailure_cancelsRemainingTasks`：2 个 600ms 慢任务被取消，elapsed<450
- `FailurePolicy` 的 `Mode` enum：正交两决策（影响他人 vs 退避节奏）

### 常见误区

- 无限重试（重启风暴）——必须预算 + 退避（对照 Stage 10 的 `McpRestartPolicy` 三参数）
- 把 FAIL_FAST 用在"多源检索"这种本来就能容忍失败的场景

### 面试金句

> "失败处理我分两个正交维度：一个失败对**其他任务**的影响（FAIL_FAST/BEST_EFFORT），和失败后**自己**怎么办（重试预算在任务、退避节奏在编排器）。超时是第五种失败，和重试正交组合。"

---

## 文章 5：《内部 Agent 和外部 Agent 的信任边界与成本归属》

**一句话立意**：统一抽象 + 差异化信任——路由可以无差别，信任必须分等级。

### 核心论点

1. **D1 统一抽象**（装饰器哲学第三次兑现）
   - `AgentWorker` 接口：`InternalAgentWorker`（方法调用，高信任）/ `ExternalAgentWorker`（A2A 委托，低信任）
   - 编排器、聚合、失败策略只认接口——新增 gRPC Agent = 新增一个实现，编排层零改动

2. **D5 信任降级**
   - 外部 Agent 输出 = 不可信外部输入，进入聚合前必经净化
   - 净化是 `UnaryOperator<String>` 注入（模块边界纪律：orchestrator 不依赖 security，同 agent-mcp）
   - 净化器抛异常 = BLOCK → 自动转 failure data（拦截不是崩溃）

3. **AgentCard 是自报家门，只做路由依据，不做信任依据**

4. **成本归属 v1 = 记账不是分摊**
   - `WorkerResult.durationMs` / `totalTokens`：记录，不分摊
   - 按 worker/租户拆分账单是 Stage 18 成本仪表盘的事

### 代码锚点（实证最有说服力的一章）

- `MultiAgentExample` 实测：reviewer（外部）scripted 回答藏 `"ignore previous instructions"`
  → `DefaultResultSanitizer` 打出 `Injection detected (instruction-override)`
  → 聚合结果里 `[REDACTED]`，注入指令在进聚合前被拦
- `ExternalAgentWorkerTest.execute_sanitizerThrows_becomesFailureData_blockSemantics`：BLOCK 语义转失败数据
- `SupervisorRoutingTest.dispatchBySkill_externalWorker_viaA2A`：路由代码对内外 Worker 零差别

### 常见误区

- 信任外部 Agent 的 self-report（AgentCard）——它说"我会审查代码"不代表它能/该被信任
- 为了统一抽象牺牲安全——统一的是路由，不是信任；信任差异封装在 Worker 实现里

### 面试金句

> "统一抽象解决**路由**，信任降级解决**安全**，两者不矛盾。编排器不知道 Worker 是进程内还是网络对端，但 ExternalAgentWorker 知道自己的输出是不可信输入——它在返回前就过了 Stage 9 的净化器。这个在验收示例里是实证：外部 reviewer 返回的注入指令在聚合前被 [REDACTED]。"

---

## 文章 6：《为什么多 Agent 不是多开几个聊天窗口》

**一句话立意**：编排层是 Multi-Agent 的灵魂，多开窗口只是"人肉编排"。

### 核心论点

1. **N 个聊天窗口 = N 个互不知情的独立 Agent**
   - 任务拆分、并行、结果合并、失败重试——全是**用户**的活
   - 用户是"人肉 Supervisor"

2. **Multi-Agent = 有编排层的团队**
   - 拆分 / 并行 / 聚合 / 重试是框架的事，用户只给一个总任务

3. **三条边界一图流**
   - Workflow vs Multi-Agent：编排步骤（确定性）vs 编排责任（有自主性）
   - Multi-Agent vs 多窗口：框架 Supervisor vs 人肉 Supervisor
   - 内部 vs 外部：方法调用高信任 vs A2A 委托低信任

4. **MultiAgentExample 全景**（全文的"一次完整数据流"）
   - 组装（3 个专业 Agent，各自身份）→ 能力发现 → 三路并行（2ms≈max）→ 聚合 → skills 路由 → 失败重试

### 代码锚点

- `MultiAgentExample` 完整运行输出（可以直接截图进文章）
- `AgentSupervisor.dispatchAll` 的并行 + `ResultAggregator` 的聚合 = 编排层的两个可见抓手

### 常见误区

- "多开几个 = Multi-Agent"——缺了编排层，N 个 Agent 只是 N 倍开销，没有 N 倍能力
- 忽略编排开销：单步任务硬拆 Multi-Agent，延迟反而翻倍

### 面试金句

> "多 Agent 的价值不在 Agent 的数量，在编排层的存在。没有 Supervisor 的 N 个窗口，是让人当人肉编排器；有了 Supervisor，拆分、并行、聚合、重试才从人转移到了框架。"

---

## 附：公众号发布建议（agent4j 三要素对照）

每篇发布时嵌入 agent4j 三要素（项目介绍 GitHub 链接 / 当前进度 / 当前难关）：

| 文章 | 适合的当前进度叙事 | 当前难关（诚实点） |
|---|---|---|
| 1-4（概念+机制） | Stage 1-11 完成，348 测试全绿 | Stage 12 频道级共享 Agent / Ambient 还没动工 |
| 5（信任边界） | 最能展示"框架不只是 demo"，有注入拦截实证 | v1 成本只记账不分摊、真实 HTTP A2A 未做 |
| 6（全景收尾） | 一张 MultiAgentExample 输出图 + 11/18 阶段路线图 | LLM 驱动分派 v2 未做 |

**发布节奏建议**：6 篇里 1、4、5 最有公众号传播潜力（痛点清晰 + 实证硬），优先扩写；2、3、6 适合知乎引流（问题驱动：怎么分工 / 共享还是消息 / 为什么不是多窗口）。

**与已有草稿衔接**：Stage 5 笔记的结论"Agent 不是 Workflow 的替代，是 Workflow 的容器"在文章 1 要显式引用，形成系列连载的连续性。
