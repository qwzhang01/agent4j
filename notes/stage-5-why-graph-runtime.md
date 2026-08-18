# 为什么 Agent 框架需要 Graph Runtime

> 对应阶段：Stage 5 - Workflow 和 Graph Runtime
> 来源：理解 Stage 5 时的两个核心追问
>   1. Graph Runtime 是 WorkflowState 吗？（不是）
>   2. 为什么 Agent 框架需要 Graph Runtime？（纯 Agent 循环搞不定有流程的任务）
> 配套：概念与数据流见 [stage-5-workflow.md](stage-5-workflow.md)

---

## 一、先纠正一个误解：Graph Runtime ≠ WorkflowState

这两个是完全不同的东西，一个是「执行者」，一个是「被读写的数据」。

```text
GraphRuntime   = 执行者（动词）  -- 谁在跑图、谁在路由、谁在调用节点
WorkflowState  = 数据（名词）    -- 跑的时候记了什么、黑板里存了什么
```

### 棋类比方

```text
GraphRuntime   = 棋手（下棋的人，按规则走子）
WorkflowState  = 棋盘（当前局面，谁在哪、吃了什么）
Workflow       = 棋规（马走日象走田，不可变）
```

`GraphRuntime.run(wf, input)` 做的事：读棋规（Workflow）-> 看棋盘（WorkflowState）-> 走一步（执行节点）-> 更新棋盘（写黑板）-> 再看棋盘决定下一步。**Runtime 是循环本身，State 是循环读写的对象。**

### 代码视角一眼区分

```java
// Runtime 是执行者，有方法 run()
GraphRuntime runtime = new GraphRuntime();
ExecutionResult result = runtime.run(workflow, input);   // Runtime 在跑

// State 是数据，只有 getter/setter
WorkflowState state = WorkflowState.of("用户输入");
state.put("intent", "REFUND");                            // State 被读写
state.getTrace();                                         // State 被读
```

记住这个区分，下面讲「为什么需要 Graph Runtime」就清楚了--我们需要的是一个「能按图走子的执行者」，这就是 Graph Runtime 存在的理由。

---

## 二、为什么 Agent 框架需要 Graph Runtime

不用抽象论述，用一个具体痛点切入。

### 痛点：纯 Agent 循环搞不定「有流程的任务」

假设你做一个客服 Agent，用纯 ReAct 循环（Stage 2 实现的那个）：

```text
用户：我要退款
Agent（思考）：用户要退款，我应该先查订单状态
Agent（调工具）：query_order(orderId=1001) -> 已发货
Agent（思考）：已发货的订单退款需要主管审批
Agent（思考）：我应该调用审批工具... 还是直接退款？
Agent（调工具）：refund(orderId=1001)  ← 💥 模型跳过了审批，直接退款了
```

问题出在哪？**模型「觉得」可以跳过审批，就跳过了。** ReAct 循环里，每一步做什么都是 LLM 说的算--包括「要不要走审批」。你的业务规则是「退款必须审批」，但模型不知道、或者知道但忽略了。

这就是纯 Agent 循环的根本局限：**它是为开放式任务设计的，没有「流程必须这么走」的概念。** 你没法对 ReAct 循环说「这一步必须经过人工审批，跳过就是 bug」。

### Graph Runtime 解决的就是这个问题

Graph Runtime 给你一个能力：**把流程固化成图，让某些步骤不可绕过。**

```text
意图识别（AgentNode）--LLM 决策
  ↓ 条件路由（图定义的，LLM 管不了）
  ├── REFUND -> 审批（HumanApproval）-- 必须经过，模型跳不掉
  │            -> 执行退款（ToolNode）
  └── 其他    -> 人工接管
```

关键在「条件路由是图定义的，LLM 管不了」。意图识别节点里 LLM 产出 `"REFUND"` 这个数据，但**走哪条边是 GraphRuntime 按边条件算的**--LLM 没有投票权。审批节点在图里，模型就绕不过去。

### 三个「纯 Agent 搞不定，Graph Runtime 能搞定」的能力

#### 1. 不可绕过的流程节点

```text
纯 Agent：模型决定每一步，审批可以被跳过
Graph Runtime：图定义了「审批必经」，模型只能决定节点内的事
```

这是安全边界的根基。Coding Agent 为什么必须用 Graph Runtime？因为「改代码前必须审批 + 沙箱测试」是红线，不能让模型自己决定要不要走。

#### 2. 确定性的状态更新

```text
纯 Agent：模型决定关系值加多少 -> 作弊、不可复现
Graph Runtime：状态更新是 ActionNode（确定性 Java 代码）-> 可复现、可审计
```

酒馆游戏场景：玩家说了一句好话，关系值 +5。这个 +5 必须是代码算的，不能是 LLM「觉得」加多少。Graph Runtime 让你把状态更新做成确定性节点，LLM 只负责生成对话内容。

#### 3. 有界的修复循环

```text
纯 Agent：测试失败 -> 修复 -> 再失败 -> 再修复 -> ... 无限循环
Graph Runtime：条件边 + maxSteps 熔断 -> 循环 25 次必停
```

Coding Agent 的「测试失败 -> 修复 -> 再测试」循环，用 Graph Runtime 的条件边天然实现，而且 maxSteps 保证不会无限循环。

### 一句话：Graph Runtime 是「流程的守卫者」

```text
ReAct 循环（Stage 2）：为开放式任务设计 -- LLM 全权决策，灵活但不可控
Graph Runtime（Stage 5）：为有流程的任务设计 -- 流程是人定的，LLM 只在节点内决策
```

**不是所有任务都需要 Graph Runtime。** 开放式问答、创意写作、自由对话--这些没有「必须经过的步骤」，纯 ReAct 循环就好。

**但一旦任务有流程**（审批、并行、状态更新、安全卡点、有界循环），你就需要 Graph Runtime--因为你需要「流程不可被模型绕过」这个保证。这个保证，ReAct 循环给不了你，Graph Runtime 给你。

### 最终比方

```text
ReAct 循环   = 放养（LLM 想去哪去哪，适合探索）
Graph Runtime = 有围墙的花园（LLM 在节点内自由，但流程围墙是人定的）
```

你需要围墙的时候，就需要 Graph Runtime。这就是为什么 Agent 框架需要它--**不是所有 Agent 任务都是开放式的，有流程的任务需要流程的守卫者。**

---

## 附：与 ReAct 循环的对照（Stage 2 vs Stage 5）

| 维度 | ReAct 循环（Stage 2） | Graph Runtime（Stage 5） |
|------|---------------------|------------------------|
| 为谁设计 | 开放式任务 | 有流程的任务 |
| 谁决定下一步 | LLM | 图（人定的边条件） |
| 审批能绕过吗 | 能（模型说了算） | 不能（审批节点在图里） |
| 状态更新 | LLM 决定（不确定） | ActionNode 决定（确定） |
| 循环 | 可能无限 | maxSteps 熔断 |
| 适合场景 | 问答、创意、自由对话 | 客服审批、游戏回合、Coding Agent |
| 执行者 | `ReActAgentLoop` | `GraphRuntime` |
| 状态载体 | `AgentState`（messages + steps） | `WorkflowState`（黑板三区） |

**两者不是替代关系，是协作关系**：Graph Runtime 的 `AgentNode` 内部跑的就是 ReAct 循环。图是围墙，ReAct 是围墙里的自由。
