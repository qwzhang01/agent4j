# Stage 5 学习笔记：Workflow 要解决什么业务场景？

> 目标：回答三个问题——Stage 5 要解决什么业务问题？涉及哪些概念？这些概念如何落到实现与设计决策？
> 配套笔记：[stage-5-workflow.md](stage-5-workflow.md)（概念与数据流） · [stage-5-why-graph-runtime.md](stage-5-why-graph-runtime.md)（为什么需要 Graph Runtime） · [architecture-stage-5.md](architecture-stage-5.md)（完整架构蓝图）
> 对应实现：`agent-workflow/` · 验收示例：`examples/src/main/java/io/github/qwzhang01/agent/examples/WorkflowSupportFlowExample.java`
> 学习结论：Stage 5 已完成（2026-08-17），`agent-workflow` 23 个测试全绿。

---

## 一、先给结论：Stage 5 不是「再写一个 Agent」

Stage 2 的 ReAct Agent 已经能完成一类任务：

```text
用户提问
  -> LLM 思考
  -> LLM 选择工具
  -> 工具返回结果
  -> LLM 再思考
  -> LLM 给最终答案
```

这种模式适合开放式任务：问答、创意写作、信息整理、自由对话。它的特点是**下一步由 LLM 决定**。

但很多真实业务不是「尽可能灵活地完成」，而是「必须按规则完成」：

```text
退款：先查订单 -> 校验资格 -> 主管审批 -> 执行退款 -> 通知用户
改代码：读代码 -> 生成计划 -> 人工批准 -> 修改文件 -> 沙箱测试 -> 失败则修复
游戏回合：构建上下文 -> 角色响应 -> 更新关系值 -> 检查事件 -> 结算回合
```

这些流程有几个不能交给模型自由发挥的约束：

- 退款不能跳过审批；
- 文件修改不能绕过安全检查；
- 关系值不能由 LLM 随意增加；
- 测试失败可以重试，但不能无限循环；
- 哪条业务路径被走过，需要可解释、可追踪、可复盘。

因此 Stage 5 的核心问题不是「Agent 能不能思考」，而是：

> **如何让 LLM 的不确定性被装进一个确定性的业务流程里？**

答案是：

```text
Workflow     = 确定性流程骨架，规定节点和边
AgentNode    = 不确定性决策点，节点内部仍然运行 ReAct
GraphRuntime = 流程执行者，负责按图路由、执行、写状态、记轨迹、处理失败
```

最重要的一句话：

> **Agent 不是 Workflow 的替代品，Agent 是 Workflow 图里的一个节点。**

---

## 二、Stage 5 要解决的业务场景

### 2.1 主场景：企业客服退款流程

用一个业务场景理解全部设计：用户说「我要退款，订单号是 1001」。

#### 如果只有纯 ReAct Agent

```text
用户：我要退款，订单 1001
Agent：我先查一下订单
      -> query_order(1001)
工具：订单已发货
Agent：已发货订单可能需要审批
      -> 这里由 LLM 自己决定下一步
Agent：我直接调用 refund_order(1001)
      -> 退款执行
```

问题是：模型可能知道审批规则，也可能忽略规则；即使当前模型表现良好，也没有机制保证下一次永远不跳过审批。

**业务规则被写在 prompt 里，不等于业务规则被系统强制执行。**

#### 加入 Workflow 后

```text
START
  -> intent（AgentNode：识别 QUERY / REFUND / COMPLAINT）
      -> QUERY：query_order（ToolNode） -> answer（AgentNode） -> END
      -> REFUND：check_eligibility（ToolNode）
                  -> approval（HumanApprovalNode）
                  -> refund_order（ToolNode）
                  -> notify（ActionNode）
                  -> END
      -> 其他：handoff（ActionNode） -> END
```

这里的职责分工是：

| 决策 | 谁负责 | 原因 |
|---|---|---|
| 用户属于哪种意图 | `AgentNode` 内部的 LLM | 这是语言理解，存在不确定性 |
| REFUND 是否进入审批路径 | `GraphRuntime` + 条件边 | 这是业务流程，必须确定 |
| 是否执行退款 | `HumanApprovalNode` + 审批结果 | 敏感操作必须有人放行 |
| 退款工具如何调用 | `ToolNode`/确定性代码 | 工具参数和副作用不能由流程外绕过 |
| 失败后是否重试/转人工 | `RetryPolicy` + `onError` 边 | 这是可靠性策略，不能无限交给模型 |

LLM 仍然可以识别意图、生成答案、分析投诉，但它没有权力修改图的拓扑，也不能从 `intent` 节点直接跳到 `refund_order`。

### 2.2 三类场景的共同结构

Stage 5 不只服务客服，它抽象的是一类业务：**确定性流程 + 不确定性步骤**。

#### 场景 A：企业 Agent

```text
用户请求
  -> AgentNode：识别意图
  -> 条件边：查询 / 退款 / 投诉
  -> ToolNode：查知识、查订单、校验资格
  -> HumanApprovalNode：敏感操作审批
  -> ActionNode：通知、升级、记录
```

不可交给 LLM 的部分：审批必经、权限边界、退款资格、审计记录。

#### 场景 B：Tavern Game / 游戏 Agent

```text
玩家输入
  -> ActionNode：构建角色人格、关系和世界上下文
  -> AgentNode：角色生成回应
  -> ActionNode：按规则更新关系值和世界状态
  -> 条件边：检查是否触发事件
  -> AgentNode：生成事件内容 / ActionNode：结算回合
```

不可交给 LLM 的部分：关系值加多少、事件是否达到触发阈值、世界状态如何改变。

#### 场景 C：Coding Agent

```text
需求
  -> AgentNode：理解需求、阅读代码、生成修改计划
  -> HumanApprovalNode：计划审批
  -> AgentNode：生成 Patch
  -> ToolNode：沙箱测试
      -> 通过：AgentNode 生成摘要 -> END
      -> 失败：AgentNode 分析失败 -> 回到 Patch
```

不可交给 LLM 的部分：文件写入审批、沙箱边界、测试结果判定、修复循环上限。

### 2.3 什么时候不需要 Workflow

不是所有 Agent 都需要图引擎。

| 任务 | 推荐形态 | 原因 |
|---|---|---|
| 开放式问答 | 纯 ReAct | 没有必须经过的业务步骤 |
| 创意写作 | 纯 ReAct | 输出质量比流程约束重要 |
| 自由聊天 | 纯 ReAct | 没有审批、分支和副作用边界 |
| 退款审批 | Workflow + AgentNode | 有不可绕过的审批和副作用 |
| 代码修改 | Workflow + AgentNode | 有审批、沙箱和有界修复循环 |
| 游戏回合 | Workflow + AgentNode | 有状态更新、事件触发和回合结算 |

判断标准：

> **只要存在必须经过的步骤、人工卡点、确定性状态更新、并行分支或有界循环，就应该考虑 Workflow。**

---

## 三、Stage 5 涉及哪些概念

### 3.1 结构概念：图是什么

| 概念 | 对应实现 | 理解方式 |
|---|---|---|
| `Workflow` | `Workflow.java` | 一张不可变流程图，定义一次、执行多次 |
| `WorkflowNode` | `WorkflowNode.java` | 一个行为单元，只负责做一件事 |
| `Edge` | `Edge.java` | 从一个节点到下一个节点的路由声明 |
| `START` / `END` | `Workflow.START` / `Workflow.END` | 图的虚拟入口和出口 |
| `WorkflowBuilder` | `WorkflowBuilder.java` | 用 fluent API 组装并校验图 |

核心原则：

```text
Workflow 是数据，不是执行过程。
WorkflowNode 是行为，不是路由器。
Edge 是路由，不是节点内部的 if/else。
```

### 3.2 执行概念：图怎么跑

| 概念 | 对应实现 | 理解方式 |
|---|---|---|
| `GraphRuntime` | `GraphRuntime.java` | 图的解释器，负责游标循环 |
| `WorkflowState` | `WorkflowState.java` | 全图共享的黑板 |
| `NodeContext` | `NodeContext.java` | 节点执行时读取黑板和当前输入的上下文 |
| `NodeResult` | `NodeResult.java` | 节点输出，可选择普通走边或显式跳转 |
| `ExecutionResult` | `ExecutionResult.java` | 一次运行的最终结果和状态快照 |
| `StepRecord` | `StepRecord.java` | 每一步的执行轨迹 |

### 3.3 节点类型：不同业务动作如何表达

| 节点 | 解决什么问题 | 是否含 LLM |
|---|---|---:|
| `ActionNode` | 执行确定性 Java 逻辑，例如更新状态、发送通知 | 否 |
| `AgentNode` | 把 Agent/ReAct 循环嵌入流程 | 是 |
| `ToolNode` | 按图执行一个工具，不由模型自由选择 | 否 |
| `RouterNode` | 复杂路由逻辑，输出 route key | 否 |
| `HumanApprovalNode` | 人工审批、人工接管 | 否，但等待人 |
| `ParallelNode` | 节点内部 fork-join 并行 | 可能 |
| `JoinPolicy` | 并行结果如何收敛：`ALL_OF` / `ANY_OF` | 否 |

最关键的边界：

```text
AgentNode：LLM 决定节点内部的内容
ToolNode：图决定什么时候调用工具
ActionNode：代码决定状态如何更新
Edge：图决定下一步走哪条路
```

### 3.4 可靠性和治理概念

| 概念 | 对应实现 | 解决的问题 |
|---|---|---|
| 节点级重试 | `RetryPolicy` | 临时网络错误、瞬时服务失败 |
| 失败路由 | `onError` 边 | 重试耗尽后转补救节点或人工处理 |
| 环保护 | `maxSteps` | 条件边成环时强制停止 |
| 路由确定性 | `GraphRuntime.route()` | 多条件同时命中或无路由时快速失败 |
| 人工审批 | `ApprovalService` | 把人工决策接入节点，不把 UI 写死在 Runtime |
| 执行轨迹 | `StepRecord` | 解释、审计、调试、后续训练数据 |

这些概念不是附加功能，而是让 Workflow 能进入真实业务的必要条件。

### 3.5 三个经常混淆的概念

#### `GraphRuntime` 和 `WorkflowState` 不一样

```text
GraphRuntime   = 动词：谁在跑图、路由、调用节点
WorkflowState  = 名词：运行期间保存了什么数据
Workflow       = 规则：图的结构是什么
```

类比：

```text
Workflow       = 棋规
GraphRuntime   = 棋手
WorkflowState  = 棋盘
```

#### Agent 和 Workflow 不是二选一

```text
Workflow = 外层确定性控制
Agent    = 节点内部不确定性决策
```

一个 `AgentNode` 内部可以执行多轮 ReAct，但从图的角度，它仍然只是一个节点：成功、失败，或者需要进一步处理。

#### 节点输出和路由不是一回事

```text
节点 output = 写入黑板的数据
边 condition = 读取黑板后决定下一步
```

例如 `AgentNode` 输出字符串 `"REFUND"`，它不是直接发出「跳到审批节点」的指令；`GraphRuntime` 读取这个值，再执行人预先写好的条件边。

---

## 四、对应实现和设计思路

### 4.1 用不可变 `Workflow` 保存流程定义

```java
Workflow workflow = Workflow.builder("support-flow")
        .node(AgentNode.of("intent", intentAgent))
        .node(ToolNode.of("lookup", queryTool))
        .node(HumanApprovalNode.of("approval", "退款审批", approvalService))
        .edge(Workflow.START, "intent")
        .edge("intent", "lookup")
        .edge("lookup", Workflow.END)
        .build();
```

设计思路：

1. 定义和执行分离：同一张图可以执行多次；
2. 图可以被检查、可视化、版本管理；
3. 后续可以从 Java Builder 演进到 YAML 或声明式配置；
4. 图本身不携带某一次运行的数据，运行数据放到 `WorkflowState`。

v1 的诚实边界是：节点行为仍然是 Java 对象或 lambda，不能完整序列化；后续如果要 YAML 化，需要 `NodeDescriptor` 注册表，把节点名称解析成工厂。

### 4.2 用黑板模式传递运行状态

`WorkflowState` 可以理解成一张流程黑板：

```text
input       = 用户最初输入，只读
variables   = 每个节点的输出，key 默认是 node id
trace       = 每一步执行记录
```

一次执行的状态变化：

```text
input = "我要退款，订单 1001"

intent 执行后：
variables = { intent: "REFUND" }

approval 执行后：
variables = { intent: "REFUND", approval: "REFUND" }

execute_refund 执行后：
variables = {
  intent: "REFUND",
  approval: "REFUND",
  execute_refund: "refund executed"
}
```

设计思路：

- 节点之间不需要互相持有引用，只通过 `NodeContext` 访问黑板；
- 路由条件可以读取全局状态，而不是只能看上一个节点输出；
- 黑板可以整体快照，为 Stage 6 Checkpoint 做准备；
- `WorkflowState` 与 `Workflow` 分离，避免静态定义被运行数据污染。

### 4.3 用边表达路由，不把路由藏在节点内部

```java
.edge("intent", "lookup")
    .when(state -> "QUERY".equals(state.get("intent")))
.edge("intent", "approval")
    .when(state -> "REFUND".equals(state.get("intent")))
.edge("intent", "handoff")
    .otherwise()
```

路由优先级：

```text
1. NodeResult.jump(next, output) 显式跳转
2. 唯一命中的条件边
3. 无条件边 otherwise 兜底
4. 都没有：死端错误
5. 多条条件边同时命中：二义性错误
```

这里有一个容易犯的错误：`otherwise` 不是「永远都走的边」，而是**条件边零命中时才允许走的兜底边**。如果条件边已经命中，继续走 otherwise 会让路由变得不确定。

设计思路：

- 看图就能看懂所有可能路径；
- 节点只做业务动作，单一职责更清晰；
- 可以单独测试「某个黑板状态对应哪条边」；
- 审计时可以解释「为什么走了退款流程」。

复杂路由超过 5~6 条时，用 `RouterNode` 先计算 route key，再让边只做简单匹配，避免图定义里堆太多复杂谓词。

### 4.4 用 `GraphRuntime` 执行统一主循环

可以把 `GraphRuntime` 理解成一个小型图解释器：

```text
cursor = route(START, state)
lastOutput = state.input

while cursor != END:
    检查 maxSteps
    找到 cursor 对应的 node
    创建 NodeContext(state, lastOutput)
    按 RetryPolicy 执行 node
    失败：走 onError 或返回 FAILED
    成功：state.put(node.id, output)
    记录 StepRecord
    lastOutput = output
    cursor = route(node.id, result.next, state)

return ExecutionResult(SUCCEEDED, lastOutput, state, trace)
```

主循环统一处理所有节点，因此新增节点类型只需要实现 `WorkflowNode`，不需要修改 Runtime。

这是一个重要的扩展设计：

```text
新增 ActionNode / AgentNode / ToolNode / HumanApprovalNode
    -> 都接入同一个 execute(ctx)
    -> 都获得统一的路由、重试、错误、trace、maxSteps 能力
```

### 4.5 用 `RetryPolicy`、`onError` 和 `maxSteps` 把失败变成流程的一部分

#### 临时失败：节点级重试

```text
query_order 第 1 次：网络超时
query_order 第 2 次：网络超时
query_order 第 3 次：成功
```

`RetryPolicy` 应该只处理适合重试的暂时性失败，不能把业务拒绝无限重试。

#### 重试耗尽：失败路由

```java
.onError("lookup", "handoff")
```

失败节点重试耗尽后：

```text
trace 记录 FAILED
错误消息作为下游 input
cursor 转到 handoff
```

这样「失败后转人工」是图的一部分，不需要在每个节点里重复写异常分支。

#### 条件边成环：步数熔断

```text
test_failed -> diagnose -> patch -> test_failed -> ...
```

这是 Coding Agent 必需的修复循环，但必须有 `maxSteps`：

```text
循环次数达到上限
  -> 返回 FAILED
  -> 不允许模型无限烧 Token 或占用线程
```

### 4.6 用可插拔 `ApprovalService` 接入人工

`HumanApprovalNode` 不直接依赖控制台、Web 或数据库，而是依赖接口：

```java
public interface ApprovalService {
    boolean approve(Request request);
}
```

因此 v1 可以有：

```text
MockApprovalService     = 单元测试自动批准/拒绝
ConsoleApprovalService  = 本地示例交互
```

设计思路：

- 节点只关心「得到批准还是拒绝」；
- UI、消息通知、审批人路由属于外部适配层；
- Stage 6 需要真正暂停-恢复时，可以替换审批服务和运行管理机制；
- Workflow 图定义不需要重写。

Stage 5 的同步审批会阻塞线程，这是刻意范围控制；真正的异步暂停、Checkpoint 和恢复属于 Stage 6。

### 4.7 用 `AgentNode` 控制不确定性的爆炸半径

`AgentNode` 内部可以继续使用 `agent-core` 的 Agent 和 AgentState：

```text
GraphRuntime 只看到：
  intent 节点成功，输出 "REFUND"

节点内部实际发生：
  AgentState 加入用户消息
  ReActAgentLoop 调用 ModelClient
  可能多次调用工具
  最终得到 assistant 文本
```

这使得：

- LLM 的复杂性被限制在节点内部；
- 图的拓扑仍然可以确定性测试；
- LLM 失败可以被节点级重试或 `onError` 捕获；
- 业务流程不需要理解 ReAct 的每一个内部步骤。

这就是「不确定性收敛」：不是消灭 LLM 的不确定性，而是把它限制在可控边界内。

### 4.8 用 `ParallelNode` 实现节点内 fork-join

例如企业查询需要同时查订单、物流和知识库：

```text
ParallelNode
  ├── query_order
  ├── query_logistics
  └── search_knowledge
       ↓
JoinPolicy.ALL_OF
       ↓
Map<分支名, 分支结果>
```

v1 选择节点内并行，而不是图级调度器：

- 实现复杂度低；
- `CompletableFuture` 足够覆盖常见并行需求；
- `ALL_OF` 和 `ANY_OF` 已能表达大部分场景；
- 图级就绪调度、入度计数、屏障同步留给后续版本。

---

## 五、用一张表记住 Stage 5 的设计权衡

| 问题 | Stage 5 的选择 | 为什么 | 代价/后续 |
|---|---|---|---|
| 谁决定流程 | 人写图，LLM 只在节点内决策 | 安全、可测、可审计 | 灵活性低；Stage 13 再做声明式装配 |
| 状态怎么传 | `WorkflowState` 黑板 | 简单、可快照、全局可见 | key 依赖约定；后续需要 State Schema |
| 路由怎么表达 | 边上的条件 | 看图即可理解路径 | 复杂路由需 `RouterNode` |
| Agent 怎么接入 | `AgentNode` | 复用已有 Agent，隔离不确定性 | 图只看节点级结果 |
| 并行怎么做 | `ParallelNode` 内部 fork-join | v1 复杂度可控 | 图级并行和独立 Join 留后续 |
| 人工审批怎么做 | `ApprovalService` 接口 + 同步节点 | 先占语义接缝，不提前做 Checkpoint | v1 会阻塞线程，Stage 6 异步恢复 |
| 失败怎么处理 | Retry + onError + FAILED | 可靠性进入流程模型 | 需要区分可重试和不可重试异常 |
| 如何防止死循环 | `maxSteps` | 简单可靠、保护资源 | 更细的预算/超时治理留后续 |
| 图是否持久化 | v1 不持久化 | 先完成执行语义 | Stage 13 做 YAML/声明式图 |

---

## 六、一次「退款」执行的完整理解

### 6.1 图定义期：业务人员/开发者决定流程

```text
START -> intent
intent -- QUERY --> lookup -> END
intent -- REFUND --> approval -> execute_refund -> END
intent -- otherwise --> handoff -> END
```

此时还没有具体用户，也没有运行数据。

### 6.2 运行期：GraphRuntime 创建黑板并执行

```text
输入："I want a refund for order 1001"
黑板：input 有值，variables 为空，trace 为空
```

### 6.3 AgentNode：LLM 只负责识别

```text
AgentNode("intent") -> "REFUND"
```

Runtime 把结果写入：

```text
variables.intent = "REFUND"
trace += intent / SUCCESS
```

### 6.4 条件路由：代码判断，不是 LLM 跳转

```text
"REFUND" 命中 REFUND 条件边
-> approval
```

### 6.5 HumanApprovalNode：人工卡点

```text
批准 -> payload "REFUND" 透传给下游
拒绝 -> ApprovalRejectedException
      -> onError 或 ExecutionResult.FAILED
```

### 6.6 ActionNode/ToolNode：执行确定性副作用

```text
execute_refund -> 调用退款系统
                -> 成功写入黑板
                -> 记录 StepRecord
                -> 走 END
```

### 6.7 最终结果

```text
ExecutionResult {
    status: SUCCEEDED,
    output: "refund executed",
    state: 黑板快照,
    trace: [intent, approval, execute_refund]
}
```

这个结果比「模型说退款成功」更可靠，因为系统能证明：

1. 走过了意图识别；
2. 走过了审批节点；
3. 执行节点确实返回成功；
4. 每一步都有轨迹。

---

## 七、Stage 5 的实现验收如何对应业务价值

| 验收测试 | 证明的业务能力 |
|---|---|
| 线性流程 | 多步骤业务可以按顺序执行 |
| 条件路由 | 不同意图进入不同业务路径 |
| otherwise 兜底 | 未知意图不会无路可走 |
| 多条件命中报错 | 路由不会静默选择错误路径 |
| 死端检测 | 图定义错误在运行前暴露 |
| `maxSteps` 环保护 | 修复循环不会无限运行 |
| 节点重试 | 临时错误不必立即转人工 |
| `onError` 边 | 失败可以进入补救流程 |
| `AgentNode` + Mock 模型 | LLM 可以嵌入流程但不掌控拓扑 |
| `ToolNode` | 工具调用可以由图强制安排 |
| `HumanApprovalNode` | 敏感操作可以有人卡点 |
| `ParallelNode` | 多个独立查询可以并发完成 |
| `StepRecord` | 每一步可解释、可审计、可复盘 |

Stage 5 的完成标准不是「节点很多」，而是这句话被代码证明：

> **同一张确定性流程图，可以安全地容纳多个不确定性 Agent 节点，并且每次运行都有明确状态、明确路由、明确失败结果和完整轨迹。**

---

## 八、面试/复盘速答

### Q1：Stage 5 解决什么问题？

> Stage 5 解决纯 ReAct Agent 无法保证业务流程的问题。把流程建模成确定性图，让审批、状态更新、失败恢复、并行和循环边界由代码控制；LLM 只作为图中的 AgentNode，负责节点内部的不确定性决策。

### Q2：为什么不能只用 Prompt 约束？

> Prompt 是软约束，模型可能忽略；Workflow 的节点和边是硬约束，审批节点在图上，模型就不能从意图识别直接跳到副作用工具。业务红线必须落在执行结构里，而不只是文本指令里。

### Q3：`GraphRuntime` 和 `WorkflowState` 是什么关系？

> `GraphRuntime` 是执行者，负责路由和调用节点；`WorkflowState` 是运行数据，作为黑板保存输入、节点输出和 trace。前者是动词，后者是名词。

### Q4：Agent 和 Workflow 是什么关系？

> Workflow 是确定性外壳，Agent 是其中一个不确定性节点。图决定什么时候进入 AgentNode、从 AgentNode 出来后走哪条边；AgentNode 内部才运行 ReAct。

### Q5：为什么条件写在边上？

> 因为路由是流程的一部分。条件写在边上，读图就能知道全部路径；节点只负责业务动作，路由可单独测试，也更容易审计。

### Q6：Stage 5 为什么暂时同步审批？

> 真正的暂停-恢复需要 Run、Checkpoint 和持久化状态，属于 Stage 6。Stage 5 先通过可插拔 `ApprovalService` 把审批语义接入，保留替换缝隙，避免提前把两个阶段的复杂度混在一起。

### Q7：Workflow 的核心价值是什么？

> **Workflow 是流程的守卫者。** 它不是为了让 Agent 更自由，而是为了在需要时限制 Agent：该审批必须审批，该状态必须按规则更新，该循环必须有上限，该路径必须可解释。

---

## 九、我的最终理解

Stage 5 可以浓缩成一条工程判断：

```text
开放式问题：让 Agent 自己探索。
有流程的问题：给 Agent 一张图，让它在图里探索。
有副作用的问题：把红线放到图上，不要只放到 Prompt 里。
```

因此，Stage 5 的真正产物不是 `Workflow` 这个类，而是一种架构分工：

```text
人/代码：定义流程、审批边界、状态规则、失败策略
GraphRuntime：保证流程真的按定义执行
LLM：理解语言、生成内容、在节点内做不确定性决策
WorkflowState：承载运行数据和轨迹
```

这套分工为后面的阶段提供了底座：

- Stage 6 在图执行之上加入暂停、恢复和 Checkpoint；
- Stage 11 在图节点之上加入多 Agent 协作；
- Stage 13 把图定义进一步声明式化；
- Stage 14 把 `StepRecord` 转成训练轨迹；
- Stage 15 把审批、RAG、审计、成本和长任务装配成企业 Profile；
- Stage 16/17 在同一 Runtime 上替换领域图，验证 Runtime 与业务 Profile 的分离。

**最终记忆句：**

> **Stage 5 让 Agent 从「会思考」变成「能在真实流程里按规矩办事」。**
