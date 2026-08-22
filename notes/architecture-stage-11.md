# Stage 11 架构设计：Multi-Agent 与 A2A 协议

> 对应阶段：Stage 11 - Multi-Agent 与 A2A 协议
> 状态：✅ 已实现（2026-08-22）。M11.1-M11.5 全部完成；验收示例 MultiAgentExample 跑通（2 内部 + 1 外部 A2A 三路并行 + 聚合 + 注入净化 + 失败重试）。全仓 348 测试全绿（orchestrator 45 + mcp 52）。LLM 驱动分派 / HTTP 传输 / 共享黑板留 v2
> 模块：新增 `agent-orchestrator` Maven 模块，依赖 `agent-core`（Agent 接口）+ `agent-mcp`（A2A 数据模型与 A2AClient 接口），不依赖 workflow/scheduler/memory/security
> 前置：Stage 1-10 已完成（289 测试全绿；MCP 真实 Server 互通 + 进程管理自愈已落地）

---

## 1. 核心命题：从「单个 Agent」到「责任网络」

Stage 1-10 造好了一个完整的单 Agent Runtime：它能调用模型、用工具、记事、被治理、连外部生态。但一个 Agent 有天花板：

```text
单 Agent 的三重天花板：
1. 上下文天花板 -- 一个 loop 的窗口装不下"研究 + 写码 + 审查"三份工作记忆
2. 注意力天花板 -- 一个 system prompt 里塞三种人格，模型每种都做不精
3. 权限天花板 -- 给研究 Agent 的只读权限和给执行 Agent 的写权限，
                在同一个 Agent 上只能取并集（权限蠕变）
```

Multi-Agent 的答案：**按责任拆分**。每个 Worker 是一个完整的小 Agent（有自己的 loop、工具集、权限、记忆命名空间），Supervisor 只负责"把任务给对人、把结果拼回来"。

一句话（接 Stage 6-10 的递进叙事）：

```text
Stage 6  让 Run 能暂停-恢复
Stage 7  让 Run 能自动恢复
Stage 8  让 Agent 能记住
Stage 9  让 Agent 能被信任
Stage 10 让 Agent 能连接
Stage 11 让 Agent 能协作 -- 不再是"一个超人"，是"一支团队"
```

### 与相邻概念的三条边界（面试高频）

```text
Workflow vs Multi-Agent（Stage 5 已立，Stage 11 重申）：
  Workflow 编排的是"步骤"（确定性控制流，节点是逻辑）
  Multi-Agent 编排的是"责任"（不确定性的完成者，Worker 是有自主性的 Agent）
  一句话：步骤没有自主性，责任必须有人兜

Multi-Agent vs 多开几个聊天窗口：
  N 个聊天窗口 = N 个互不知情的独立 Agent，用户当人肉 Supervisor
  Multi-Agent = 有编排层的团队：任务拆分 / 并行 / 聚合 / 失败重试是框架的事

内部 Agent vs 外部 Agent（A2A）：
  内部：同 JVM，同治理域，信任高，调用是方法调用
  外部：跨进程/网络，A2A 协议，信任低，调用是委托（异步、有生命周期）
```

---

## 2. 什么时候需要 Multi-Agent（文章 1 的核心论点）

```text
不需要 Multi-Agent 的信号：
  - 任务单线程可完成（问答 / 检索 / 单文件修改）
  - 延迟敏感（多一层编排多一倍延迟）
  - 工具少于 ~10 个，一个 Agent 管得过来

需要 Multi-Agent 的信号：
  - 任务天然可并行（同时查 3 个数据源）
  - 需要不同权限/人格的阶段（研究只读 -> 执行可写 -> 审批人工）
  - 上下文装不下（每个子任务有独立的大量中间产物）
  - 需要失败隔离（一个子任务崩了不该炸掉整个任务）

判断口诀：拆"上下文"，不拆"步骤" -- 步骤拆分是 Workflow 的事
```

---

## 3. 核心抽象（10 个）

| 抽象 | 层 | 一句话 |
|---|---|---|
| `AgentWorker` | 接口 | 统一的 Worker 抽象：name / card / execute(WorkerTask) -- 内外无差别 |
| `InternalAgentWorker` | 实现 | 包装 agent-core 的 `Agent`（同 JVM，直接方法调用，跑 ReAct loop） |
| `ExternalAgentWorker` | 实现 | 桥接 `A2AClient`（跨进程，A2A 委托语义） |
| `WorkerTask` | 数据 | 给 Worker 的任务：taskId / workerName / payload / 约束（超时、重试） |
| `WorkerResult` | 数据 | Worker 的回执：taskId / success / output / error / 耗时 / token 记账 |
| `AgentSupervisor` | 编排 | 编排器：持有 Worker 池，派发任务（并行/串行），收结果 |
| `ResultAggregator` | 聚合 | 接口：多个 WorkerResult -> 一个最终结果（策略可换） |
| `FailurePolicy` | 恢复 | Worker 失败策略：FAIL_FAST（一败全停）/ BEST_EFFORT（尽力而为）+ 重试 |
| `InProcessA2AClient` | A2A | `A2AClient` 的进程内实现（同 JVM 两个 Agent 走 A2A 数据模型互发） |
| `AgentCard`（复用） | 声明 | Stage 10 已有；补 skills 路由语义：Supervisor 按 card 匹配分派 |

### 3.1 关键接口草图

```java
// ---- 统一 Worker 抽象（D1 的载体）----
public interface AgentWorker {
    String name();
    AgentCard card();                       // 能力声明（内部 Worker 也填，对齐外部）
    WorkerResult execute(WorkerTask task);  // 同步 v1；异步 v2
}

// ---- 编排器 ----
public class AgentSupervisor {
    private final Map<String, AgentWorker> workers;

    public void register(AgentWorker worker);
    public List<AgentCard> discoverWorkers();          // 能力清单

    // 并行派发：所有任务同时执行，聚合结果
    public SupervisorResult dispatchAll(List<WorkerTask> tasks,
                                        ResultAggregator aggregator,
                                        FailurePolicy policy);

    // 按能力路由：从 payload 推断 taskType，匹配 card.skills 选 Worker
    public WorkerResult dispatchBySkill(String taskType, JsonNode payload);
}

// ---- 结果聚合策略 ----
public interface ResultAggregator {
    String aggregate(List<WorkerResult> results);  // 拼接/择优/投票/摘要，v1 给两个默认实现
}

// ---- A2A 进程内实现（补上 Stage 10 的接口）----
public class InProcessA2AClient implements A2AClient {
    private final Map<String, Agent> localAgents;    // name -> 内部 Agent
    // sendTask: A2ATask -> 找到本地 Agent -> 跑 loop -> 包成结果返回
    // 协议数据结构完全对齐 A2A，传输层 v2 换 HTTP 不动调用方
}
```

---

## 4. 关键设计决策（8 个）

### D1. Worker 统一抽象，编排层对内外 Agent 无差别

```text
AgentWorker 接口
  ├── InternalAgentWorker   内部：包 Agent，execute() = agent.run(task)
  └── ExternalAgentWorker   外部：包 A2AClient，execute() = sendTask(task)

-> AgentSupervisor / ResultAggregator / FailurePolicy 只认 AgentWorker
-> 内外差异（方法调用 vs 协议委托、信任高 vs 低）被封装在 Worker 实现里
-> 装饰器哲学第三次兑现：
   第一次 Stage 9（GovernedToolExecutor 包装 ToolExecutor）
   第二次 Stage 10（ManagedMcpClient IS-A McpClient）
   第三次 Stage 11（AgentWorker 统一内外协作方）
```

**为什么重要**：面试金句--"我的编排器不知道也不需要知道 Worker 是进程内对象还是网络对端。新增一种协作方（比如 gRPC Agent）= 新增一个 AgentWorker 实现，编排层零改动。"

### D2. v1 静态编排 + 显式并行，不做 LLM 驱动分派

```text
v1：代码显式构造 WorkerTask 列表 -> dispatchAll 并行执行
v2：Supervisor 本身是 Agent（LLM 读任务 + AgentCard 清单，决定分派给谁）
    -- 演进路径与 Stage 7 完全同构（先 TaskScheduler 后 LlmDrivenScheduler）
```

理由：先证明"编排 + 聚合 + 失败恢复"骨架正确，再让模型接管决策。LLM 分派是锦上添花，失败恢复是生死线。

### D3. 消息传递优先，共享状态最小化

```text
WorkerTask 自包含（payload 进、WorkerResult 出）
  -> Worker 之间不共享可变状态
  -> 并行天然安全（无锁）
  -> 结果合并推迟到 ResultAggregator（单线程聚合点）

SharedState（学习规划列的）：v1 不做完整实现
  -- 黑板模式 Stage 5 Workflow 已有（需要共享时用 Workflow 编排）
  -- 多 Worker 并发读写的 SharedState 需要命名空间 + 版本控制，v2/Stage 12 再议
```

这是文章《共享状态和消息传递的取舍》的 v1 答案：**默认消息传递（任务进/结果出），共享状态是显式选择（去 Workflow）而不是默认配置。**

### D4. Worker 失败隔离：单 Worker 崩溃不炸编排

```text
FailurePolicy:
  FAIL_FAST      有一个 Worker 失败 -> 取消其余 -> 整体失败（如：付款流程）
  BEST_EFFORT    尽力而为，失败的标记 error，其余照常（如：多源检索）

+ Worker 级重试（maxRetries / backoffMs，对齐 Stage 5 RetryPolicy 形态）
+ 超时（WorkerTask 约束，到点取消 Future）

对齐 ManagedMcpClient 哲学：单次重试不递归，重试失败按策略走
```

### D5. 外部 Agent 信任降级（呼应 Stage 9/10）

```text
ExternalAgentWorker 的出站结果 = 外部世界输入
  -> 必须经 ResultSanitizer 净化（复用 Stage 9，构造时可选注入）
  -> 外部调用记账：WorkerResult 带 tokenUsage / durationMs（Cost Attribution v1 =
     记账不做分摊；账本按 worker 名聚合，Stage 18 成本仪表盘直接消费）
  -> 外部 Agent 的 AgentCard 是"自报家门"，不可作为信任依据（只做路由依据）
```

### D6. A2A v1 进程内实现，协议对齐优先于传输真实

```text
InProcessA2AClient implements A2AClient（agent-mcp 的接口）
  sendTask: A2ATask -> 本地 Agent 执行 -> 结果（数据结构 100% 对齐 A2A）
  getTaskStatus / sendMessage / discoverAgents 同理

不做：真实 HTTP 传输、AgentCard 的 /.well-known 发现、跨网络鉴权
理由：A2A 的教学价值在"委托语义 + 数据模型"（Card/Task/Message/生命周期），
     传输是工程活。协议对了换传输不动调用方（和 MCP 的 Transport 抽象同构）
```

### D7. AgentCard skills 路由：声明式分派的最小形态

```text
dispatchBySkill("research", payload)
  -> 遍历 Worker 的 card().skills()
  -> 包含匹配命中 -> 派发；多个命中取第一个（v1 简单策略）
  -> 无命中 -> 明确报错（fail-closed，对齐 Stage 9 哲学）
```

这是 v2 "LLM 驱动分派"的地基：模型分派时的输入就是 AgentCard 清单。

### D8. 编排不替代 Workflow，二者正交组合

```text
确定性流程（先 A 后 B，B 失败走 C）-> Workflow（Stage 5 图引擎）
责任委托（"研究这块你负责"）        -> Multi-Agent（Stage 11 编排器）
组合形态：Workflow 的节点可以是"调用 AgentSupervisor.dispatchAll"
         编排器的 Worker 内部跑的是 Agent loop（loop 可在 Workflow 的 AgentNode 里）
v1 不做深集成（不写桥接代码），文档说清组合姿势即可
```

---

## 5. 分层架构图

```text
┌─────────────────────────────────────────────────────────┐
│ examples: MultiAgentExample                              │
│   主任务 -> Supervisor -> 2 内部 Worker + 1 外部 Worker   │
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│ agent-orchestrator（Stage 11 新增）                       │
│  AgentSupervisor ── dispatchAll / dispatchBySkill        │
│       │                                                  │
│       ├── AgentWorker 接口（统一抽象 D1）                  │
│       │      ├── InternalAgentWorker ──► agent-core.Agent│
│       │      └── ExternalAgentWorker ──► A2AClient       │
│       ├── ResultAggregator（拼接/择优策略）                │
│       └── FailurePolicy（FAIL_FAST/BEST_EFFORT + 重试）   │
└────────────────────────┬────────────────────────────────┘
                         │ 依赖
┌────────────────────────▼────────────────────────────────┐
│ agent-mcp（Stage 10，微扩）                                │
│  a2a 包：AgentCard / A2ATask / A2AMessage / A2AClient    │
│  + InProcessA2AClient（新增实现，D6）                      │
└────────────────────────┬────────────────────────────────┘
                         │ 依赖
┌────────────────────────▼────────────────────────────────┐
│ agent-core：Agent / AgentConfig / ModelClient / Tool      │
└─────────────────────────────────────────────────────────┘
```

依赖链：`agent-orchestrator -> agent-mcp -> agent-core`。编排器不依赖 workflow/scheduler（正交关系，见 D8）。

---

## 6. 完整时序：一次三 Worker 并行编排

```text
T0: 注册
    supervisor.register(InternalAgentWorker.of("researcher", researchAgent))
    supervisor.register(InternalAgentWorker.of("executor", execAgent))
    supervisor.register(new ExternalAgentWorker("reviewer", a2aClient, card))

T1: 构造任务（自包含 payload，D3）
    tasks = [task(researcher, "调研 X 库的 API"), 
             task(executor, "写示例代码"),
             task(reviewer, "审查代码风格")]        # -> A2A sendTask

T2: dispatchAll(tasks, aggregator, policy=BEST_EFFORT)
    -> ExecutorService 并行提交 3 个 Worker.execute()
       researcher: agent.run(...)  -- 内部 ReAct loop，跑完返回
       executor:   agent.run(...)  -- 同上
       reviewer:   a2aClient.sendTask(A2ATask{...})  -- A2A 委托
                   -> InProcessA2AClient 找到本地 Agent -> 执行 -> 结果
    -> 全部完成（或超时/失败按 policy 处理）

T3: 聚合
    aggregator.aggregate([result1, result2, result3])
    -> 拼接成最终报告（各 Worker 的 output + 状态 + 耗时）

T4: 失败分支（若 reviewer 崩了）
    BEST_EFFORT: reviewer 标记 error，其余结果照常聚合
    FAIL_FAST:   取消未完成 Worker，整体失败
    重试:        WorkerTask 带 maxRetries=1 -> 重试一次再判
```

---

## 7. 模块结构

```text
agent-orchestrator/                        # 新增 Maven 模块
└── src/main/java/io/github/qwzhang01/agent/orchestrator/
    ├── AgentWorker.java                   # 统一 Worker 接口
    ├── InternalAgentWorker.java           # 内部：包 agent-core Agent
    ├── ExternalAgentWorker.java           # 外部：桥 A2AClient（含信任降级 D5）
    ├── WorkerTask.java                    # 任务 record（含超时/重试约束）
    ├── WorkerResult.java                  # 结果 record（success/output/error/记账）
    ├── AgentSupervisor.java               # 编排器（注册/并行派发/skill 路由）
    ├── ResultAggregator.java              # 聚合接口
    │   ├── ConcatAggregator.java          #   默认：拼接各 Worker 输出
    │   └── FirstSuccessAggregator.java    #   择优：第一个成功的结果（竞速场景）
    ├── FailurePolicy.java                 # FAIL_FAST / BEST_EFFORT enum + 重试参数
    └── SupervisorResult.java              # 编排整体回执（含各 Worker 明细）

agent-mcp/（微扩，不破坏存量）
└── a2a/
    └── InProcessA2AClient.java            # A2AClient 进程内实现（D6）

examples/（新增 1 个）
└── MultiAgentExample.java                 # 验收：2 内部 + 1 外部(A2A) 并行 + 聚合 + 失败重试
```

---

## 8. 实现里程碑（建议 4 天，实际按此前节奏可压缩）

| # | 里程碑 | 交付 | 验证 |
|---|--------|------|------|
| M11.1 | Worker 抽象 + 内部 Worker | AgentWorker / InternalAgentWorker / WorkerTask / WorkerResult + 单测 | 内部 Worker 包装 Mock Agent 跑通 execute |
| M11.2 | 编排器 + 并行 + 聚合 | AgentSupervisor（dispatchAll 并行）+ ResultAggregator 两个实现 + SupervisorResult | 3 个 Mock Worker 并行执行、结果聚合正确、耗时≈最慢者而非之和 |
| M11.3 | 失败处理 | FailurePolicy（FAIL_FAST/BEST_EFFORT）+ Worker 级重试 + 超时取消 | 单 Worker 崩溃：BEST_EFFORT 聚合含 error / FAIL_FAST 整体失败 / 重试后恢复 |
| M11.4 | A2A 桥接 | InProcessA2AClient（实现 A2AClient 接口）+ ExternalAgentWorker + skills 路由 + 出站净化（D5） | 外部 Worker 走 A2A 数据模型往返；dispatchBySkill 按 card 命中 |
| M11.5 | 验收示例 + 收口 | MultiAgentExample（真实 MockModelClient 三 Agent）+ 测试补齐 + README | 规划验收全过；全仓测试全绿 |

依赖：M11.2 依赖 M11.1；M11.3 依赖 M11.2；M11.4 依赖 M11.1（InProcessA2AClient 可与 M11.2 并行）；M11.5 收口。

---

## 9. 验收标准（对齐 18 周规划 + Stage 10 遗留）

```text
1. 主 Agent + 研究 Worker（内部）+ 执行 Worker（内部）+ 外部 Agent（A2A）
   ✅ M11.4 + M11.5（MultiAgentExample 演示三路并行）

2. 并行执行
   ✅ M11.2（ExecutorService 并行，聚合等待全部完成）

3. 结果聚合
   ✅ M11.2（ResultAggregator 策略可换）

4. 单个 Worker 失败后的重试
   ✅ M11.3（Worker 级重试 + FailurePolicy 隔离）

5.（Stage 10 遗留）两个 Agent 能通过 A2A 协议委托任务和返回结果
   ✅ M11.4（InProcessA2AClient 实现完整 A2AClient 接口）
```

---

## 10. 测试策略

- **Worker 抽象**：InternalAgentWorker 包装 MockModelClient 的 Agent，execute 进出正确
- **并行正确性**：3 个 Worker 各 sleep 随机时长，总耗时 ≈ max 而非 sum；结果不串（taskId 对应）
- **聚合策略**：全成功拼接 / 部分失败 BEST_EFFORT 含 error 段 / FAIL_FAST 短路取消
- **失败重试**：Worker 前两次抛异常第三次成功 -> maxRetries=2 下最终 success，重试计数正确
- **超时**：Worker sleep 超过 task 超时 -> 取消 + WorkerResult.error 含 timeout 语义
- **A2A 往返**：InProcessA2AClient sendTask -> Agent 执行 -> 结果字段完整（taskId 关联）
- **skills 路由**：card.skills 命中派发 / 无命中 fail-closed 报错
- **外部信任降级**：ExternalAgentWorker 输出含注入特征 -> Sanitizer 拦截（可注入 mock 验证）
- **向后兼容**：不动存量模块（agent-mcp 只加 InProcessA2AClient 实现），289 存量测试零影响

---

## 11. 文章规划（6 篇 -> 里程碑映射）

| 文章 | 写作时机 | 素材来源 |
|---|---|---|
| 《什么时候需要 Multi-Agent》 | M11.1 | §2 三重天花板 + 判断口诀（拆上下文不拆步骤） |
| 《Orchestrator 和 Worker 如何分工》 | M11.2 | §3 抽象 + D2（v1 静态编排的克制） |
| 《共享状态和消息传递的取舍》 | M11.2 | D3（消息传递默认 + 共享是显式选择） |
| 《Multi-Agent 的通信、失败和结果聚合》 | M11.3 | D4 + 完整时序 T4 失败分支 |
| 《内部 Agent 和外部 Agent 的信任边界与成本归属》 | M11.4 | D1 + D5（统一抽象 + 信任降级 + 记账） |
| 《为什么多 Agent 不是多开几个聊天窗口》 | M11.5 | §1 边界三连 + MultiAgentExample 全景 |

---

## 12. 本阶段不做（范围控制）

- **LLM 驱动分派** -- v2（演进路径同 Stage 7：先骨架后智能）
- **真实 HTTP/gRPC A2A 传输** -- v1 进程内实现，协议对齐优先（D6）
- **AgentCard 网络发现（/.well-known）** -- v2
- **共享黑板 / SharedState 完整实现** -- 消息传递优先（D3），需要时用 Workflow
- **Worker 间直接通信（P2P 消息网）** -- v1 只走 Supervisor 星型拓扑，全连接网格是分布式地狱的开始
- **成本分摊算法** -- v1 只记账（WorkerResult 记 token/耗时），分摊规则 Stage 18
- **跨编排器嵌套（编排器编排编排器）** -- 理论可行（Supervisor 也是 Worker？），v1 不做
- **动态 Worker 注册/注销（热插拔 Agent 池）** -- 复用 Stage 3 插件思想，v2 再议
