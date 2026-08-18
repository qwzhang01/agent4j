# Stage 5 Q&A 沉淀：应用场景 · 编排权属 · 框架对比

> 对应阶段：Stage 5 - Workflow 和 Graph Runtime
> 来源：学习过程中的 3 个核心追问，按「用在哪 -> 谁决定 -> 和业界比合理吗」递进组织
> 配套：概念与数据流见 [stage-5-workflow.md](stage-5-workflow.md)，架构设计见 [architecture-stage-5.md](architecture-stage-5.md)

---

## Q1：Workflow 的应用场景是什么？三个 Profile 怎么用？

### 核心判断标准

**Workflow 用在「流程是确定的，但某些步骤需要 LLM 决策」的地方。** 纯 Agent（ReAct 循环）适合开放式任务；workflow 适合有明确步骤、需要审批、需要并行、需要人工卡点的场景。

### 三个场景的图设计

#### 1. 企业 Agent：客服/审批流（Stage 15）

```text
用户消息
  ↓
意图识别（AgentNode，LLM）          ← 不确定性
  ↓ 条件路由
  ├── 查询 -> RAG检索（ToolNode）-> 答案生成（AgentNode）-> END
  ├── 退款 -> 资格校验（ToolNode）-> 主管审批（HumanApproval）-> 执行退款（ToolNode）-> 通知（ToolNode）-> END
  └── 投诉 -> 情绪安抚（AgentNode）-> 升级人工（HumanApproval）-> END
```

**为什么不用纯 Agent**：
- 退款必须经过审批，纯 Agent 的 ReAct 循环无法保证「绝不跳过审批」--模型可能直接调退款工具
- 审计要求：每个请求走了哪条路、谁批的、执行了什么，StepRecord trace 天然就是审计日志
- 并行：查询类可以并行检索多个知识库（ParallelNode ALL_OF）

**LLM 决策点**：意图识别、答案生成、情绪安抚
**确定性节点**：RAG 检索、资格校验、执行退款、通知

#### 2. 酒馆游戏 Agent：回合编排（Stage 16）

```text
玩家输入
  ↓
上下文构建（ActionNode）           ← 拼接：角色人格 + 关系历史 + 世界状态
  ↓
角色选择（AgentNode，LLM）         ← 不确定性：这个场景谁该回应？
  ↓ 条件路由
  ├── 单角色对话 -> 人格渲染（ActionNode）-> 对话生成（AgentNode）-> END
  └── 多角色互动 -> ParallelNode        ← fork：多个角色并行反应
                     ├── 角色A反应（AgentNode）
                     └── 角色B反应（AgentNode）
                   -> 聚合排序（ActionNode）-> END
  ↓（对话生成后）
状态更新（ActionNode）              ← 更新关系值/亲密度/世界事件计数器（确定性！）
  ↓
事件触发检查（ActionNode）          ← 条件判定：关系值 > 阈值？
  ↓ 条件路由
  ├── 无事件 -> END
  └── 触发事件 -> 事件执行（AgentNode）-> END
```

**为什么不用纯 Agent**：
- 游戏状态更新（关系值/世界状态）必须确定性--不能让 LLM「决定」关系值加多少
- 多角色互动天然并行（ParallelNode），纯 Agent 只能串行
- 事件触发是条件路由（关系值 > 80 触发告白事件），正是 workflow 边条件的用途

**LLM 决策点**：角色选择、对话生成、角色反应、事件执行
**确定性节点**：上下文构建、状态更新、事件触发检查

#### 3. Coding Agent：软件工程循环（Stage 17）

```text
需求输入
  ↓
需求理解（AgentNode，LLM）         ← 不确定性
  ↓
代码阅读（AgentNode + ToolNode）   ← LLM 决定读哪些文件，ToolNode 确定性读取
  ↓
修改计划生成（AgentNode，LLM）     ← 不确定性
  ↓
计划审批（HumanApproval）           ← 人在回路
  ↓ 拒绝 -> END
  ↓ 批准
Patch 生成（AgentNode，LLM）
  ↓
沙箱测试（ToolNode，复用 Stage 4） ← 确定性：在沙箱跑测试
  ↓ 条件路由
  ├── 测试通过 -> 变更摘要（AgentNode）-> END
  └── 测试失败 -> 失败分析（AgentNode）-> 回到 Patch 生成（循环，maxSteps 熔断）
```

**为什么不用纯 Agent**：
- **安全红线**：文件写入和命令执行必须经过审批 + 沙箱，纯 Agent 的模型可能跳过审批直接改文件
- **修复循环有边界**：测试失败 -> 修复 -> 再测试，用条件边 + maxSteps 天然实现，不会无限循环
- **可观测**：每次改了什么、测试跑了几次、哪次失败原因，StepRecord trace 全记下来

**LLM 决策点**：需求理解、修改计划、Patch 生成、失败分析、变更摘要
**确定性节点**：代码读取、沙箱测试、审批

### 三场景共性规律

```text
确定性骨架（workflow 图）  +  不确定性血肉（AgentNode）
        ↑                              ↑
   人能审计、能保证            LLM 负责「理解」和「生成」
   审批卡点、安全边界          图负责「流程」和「治理」
```

**什么时候用纯 Agent（不用 workflow）**：开放式对话、创意写作、自由问答--没有明确步骤、不需要审批、不需要并行，一个 ReAct 循环跑到底就好。

**什么时候用 workflow**：有流程、有卡点、有并行、有状态更新、有安全边界。三个场景全部命中。

**为什么三个场景能复用同一个 Runtime**：Runtime 是同一个，图不同。Stage 15/16/17 做的就是画这三张图。

---

## Q2：Node 和 Edge 的编排，是代码写死的还是 LLM 决定的？

### 答案：编排是人写代码定的，LLM 完全不参与图的拓扑决定

### 分层看谁决定什么

```text
                      谁决定              何时决定
─────────────────────────────────────────────────────────
图的拓扑（哪些节点、连哪些边）    人（写代码/YAML）      定义期（build 之前）
单个节点内部做什么              节点类型决定            定义期
节点执行时走哪条边              运行时计算              运行期
  - 条件边                    黑板数据 + 谓词          运行期（确定性）
  - 显式跳转                  节点返回的 next          运行期（节点自己说）
节点内部的 LLM 决策            LLM                   运行期（不确定性，但被关在节点内）
```

关键区分：**LLM 能决定「节点内做什么」，不能决定「图长什么样、下一步走哪条边」。**

### 代码视角

```java
// 图定义：每一行都是人写的代码
Workflow wf = Workflow.builder("support-flow")
        .node(AgentNode.of("intent", intentAgent))      // 人决定有哪些节点
        .edge("intent", "lookup").when(s -> "QUERY".equals(s.get("intent")))  // 人决定边和条件
        .build();

// AgentNode 内部：LLM 只产出黑板数据，不产出路由指令
String output = agent.run(input, agentState);   // LLM 在这里，输出 "QUERY"
return NodeResult.of(output);                    // 路由由 GraphRuntime 按边条件算，零 LLM
```

**LLM 产出的是「黑板数据」，不是「路由指令」。** 路由是 GraphRuntime 读黑板、套边条件算出来的--纯 Java 逻辑。

### 显式跳转的微妙例外

`NodeResult.jump("target", output)` 看起来像「节点决定下一步」，但跳转目标 `"target"` 是**写代码时就确定的字符串**，不是 LLM 输出的。决策权仍在人手里--只是人选择用代码跳转而不是边条件表达。

### 为什么不让 LLM 决定编排（三个理由）

**1. 可测试性**：编排是代码 -> 意图分类为 QUERY 时一定走 lookup。单测用 MockModelClient 喂 `"QUERY"`，断言 trace 里有 lookup。如果 LLM 决定编排，这个测试写不出来。

**2. 安全边界**：退款必须经过审批。如果 LLM 能决定编排，它可能「觉得不需要审批」直接跳到 execute_refund。编排是人定的 -> 审批节点在图里 -> **模型无法绕过**。

**3. 可审计**：「为什么走了退款路径？」--看黑板 `intent=REFUND` + 边条件，一眼明了。如果 LLM 决定路由，你得复盘 LLM 的推理过程，不可靠且不可复现。

### LLM 动态编排的演进路线

```text
当前（Stage 5）        Stage 13             更远
──────────────────────────────────────────────────
人写 Java 代码编排    人写 YAML 编排        LLM 生成 YAML 编排
拓扑完全静态          拓扑静态但可热加载     拓扑动态生成
LLM 只管节点内决策    同左                  LLM 产出图定义，但执行仍由 Runtime 保证安全
```

即便到「LLM 生成编排」那一步，安全边界（审批、沙箱、权限节点）仍然是人预定义在节点类型库里的--LLM 能组合节点，但不能发明绕过审批的新节点类型。**编排可以动态，治理必须静态。**

### 一句话总结

**Node 和 Edge 的编排是人写代码定的；LLM 只在 AgentNode 内部做决策，产出的数据进黑板，路由由 GraphRuntime 按人定的边条件算。** 图是骨架（人定），AgentNode 是血肉（LLM 定），两者职责严格分离。

---

## Q3：对比 LangChain / LangGraph / CrewAI / DeepSeek Harness，我们的方案合理吗？

### 四个框架的定位

```text
框架            核心抽象              定位                    编排谁定
─────────────────────────────────────────────────────────────────────
LangChain       Chain（链式组合）     LLM 应用粘合层          代码
LangGraph       StateGraph + Node     图状态机，可控 Agent     代码（条件边）
CrewAI          Crew + Agent + Task   多角色协作框架          框架（sequential/hierarchical）
DeepSeek Harness Plugin（一切皆插件） Agent Harness 平台      代码 + 插件运行时组装
我们            Workflow + Node       图执行引擎（Agent Runtime）代码
```

### 逐维度对比

#### 1. 编排模型

| 维度 | LangChain | LangGraph | CrewAI | dsh | 我们 |
|------|-----------|-----------|--------|-----|------|
| 基本结构 | 链（线性） | 有向图 | 角色任务列表 | 插件组合 | 有向图 |
| 条件路由 | 难（嵌套链） | Conditional Edge | 无（框架决定） | 插件内逻辑 | Edge.when() |
| 循环 | 不支持 | 支持（cycle） | 无 | Agent 循环 | 支持（maxSteps 熔断） |
| 并行 | 难 | 支持 | hierarchical 有 | 插件级 | ParallelNode |

**结论：我们的编排模型 ≈ LangGraph。** 方向正确，不是重复造轮子，是独立走到了同一个收敛点。

#### 2. 状态管理

| 维度 | LangChain | LangGraph | CrewAI | dsh | 我们 |
|------|-----------|-----------|--------|-----|------|
| 状态载体 | 内存变量 | State（TypedDict） | Task context | 插件上下文 | WorkflowState 黑板 |
| 持久化 | 无 | Checkpoint（内置） | 无 | 会话日志 | 无（Stage 6 做） |
| 可观测 | 弱 | Trace | 弱 | 插件日志 | StepRecord trace |

**这里我们落后 LangGraph。** LangGraph 内置 Checkpoint + 持久化 + human-in-the-loop 暂停恢复是开箱即用的。但我们的 WorkflowState 三区设计已为 Checkpoint 预留接入口（序列化黑板 = 快照），架构上没欠债，只是实现还没到。

#### 3. Agent 与 Workflow 的关系

| 框架 | Agent 和 Workflow 的关系 |
|------|------------------------|
| LangChain | Agent 是 Chain 的一种（AgentExecutor），混在一起 |
| LangGraph | Agent 是图的一个节点 |
| CrewAI | 没有 Workflow 概念，只有 Agent + Task 的协作 |
| dsh | Agent 循环本身是插件，没有显式 Workflow 图 |
| **我们** | **Agent 是图的一个节点（AgentNode），和 LangGraph 同构** |

**这是最关键的趋同点。** LangGraph 团队和我们都独立得出同一个结论：**Agent 不是 Workflow 的替代，Agent 是 Workflow 图里的一个节点。** 验证了 D2 决策的正确性。

#### 4. 扩展模型

| 框架 | 扩展机制 |
|------|---------|
| LangChain | 继承 BaseTool / BaseChain |
| LangGraph | 自定义 Node 函数 |
| CrewAI | 自定义 Agent + Tool |
| dsh | **一切皆插件**（连 Agent 循环都是插件） |
| 我们 | 自定义 WorkflowNode + Stage 3 的 SPI 插件系统 |

**dsh 的扩展模型比我们激进得多。** dsh 把「模型适配、工具注册、会话日志、Agent 循环本身」全做成插件，是平台级思路。我们是「节点类型库 + SPI 插件系统」两层--这是刻意取舍：dsh 要做平台，我们要做教学型 Runtime，复杂度得控制。

#### 5. 多 Agent

| 框架 | 多 Agent 方案 |
|------|-------------|
| LangChain | 无原生支持 |
| LangGraph | 图里多个 AgentNode |
| CrewAI | **核心卖点**（Crew = 多 Agent 协作） |
| dsh | 插件组合 |
| 我们 | Stage 11 才做（图里多个 AgentNode + A2A） |

**CrewAI 在多 Agent 协作上比我们成熟。** 但 CrewAI 的代价是放弃了图编排的灵活性--它的 Process 只有 sequential 和 hierarchical 两种，没有条件路由。我们是「图编排 + 多 Agent 节点」，表达力更强但实现更晚。取舍不同，不是谁对谁错。

### 合理性评估

#### ✅ 方向正确（和主流趋同）

1. **图编排 + Agent 作为节点** -- 和 LangGraph 同构，业界共识
2. **黑板模式共享状态** -- LangGraph 的 State 也是这个模式
3. **条件边 + 确定性路由** -- LangGraph 的 Conditional Edge 同理
4. **治理同构**（maxSteps/Retry/Trace）-- LangGraph 也有 recursion_limit 和 checkpoint

**核心架构没有走偏，和最被认可的方案在关键决策上一致。**

#### ⚠️ 刻意取舍（合理但要知道代价）

| 取舍 | 我们的选择 | 代价 | 为什么合理 |
|------|-----------|------|-----------|
| 并行 = 节点内 fork-join | 不做图级并行 | 可观测性弱 | v1 覆盖 80% 场景，复杂度可控 |
| 审批 = 同步阻塞 | 不做暂停恢复 | 长任务阻塞线程 | Stage 6 Checkpoint 接管，接口已留缝 |
| 编排 = 代码写死 | 不做 LLM 动态编排 | 灵活性低 | 可测试 + 安全边界 + 可审计 |
| 节点类型预定义 | 不像 dsh「一切皆插件」 | 扩展性弱于平台 | 教学型 Runtime，复杂度要控制 |

**每个取舍都有明确的「为什么现在不做」和「什么时候做」，不是设计缺陷。**

#### 🔴 潜在盲点（后续阶段的债务清单）

1. **缺少「图编译」概念** -- LangGraph 运行前编译图（优化路由查表、验证连通性）。我们运行时逐边扫描，图大了有性能问题。v1 没问题，图超 50 节点要加编译层。

2. **状态版本演进没有方案** -- LangGraph 的 State 是 TypedDict，schema 可见。我们的黑板是 `Map<String, Object>`，黑板上有什么 key 全靠约定--节点多了会混乱。Stage 8 Memory 阶段需引入 State Schema。

3. **没有「子图」抽象** -- LangGraph 支持子图当节点嵌套（图嵌图）。我们只有 ParallelNode 内联分支，没法把整张子 Workflow 当节点复用。企业场景的「通用审批子流程」会需要这个，Stage 13 补。

4. **没有流式输出** -- AgentNode 内部 Agent 可以流式，但 workflow 层没有 StreamEvent 透传。对话类场景（酒馆游戏）需要节点边跑边推 token 给前端，Stage 13 前端 SDK 时补。

### 一句话总结

**核心架构合理--和业界最认可的 LangGraph 在关键决策上同构（图 + Agent 节点 + 黑板 + 条件边），验证了方向正确。** 刻意取舍都有明确的延期理由和接入口。四个盲点（图编译、State Schema、子图、流式）是后续阶段的债务清单--记下来，在对应阶段还掉。

和 dsh 比，没做「一切皆插件」是对的--那是平台级复杂度，教学型 Runtime 不需要。和 CrewAI 比，多 Agent 来得晚但表达力更强，取舍合理。

---

## 附：三个问题的递进关系

```text
Q1 用在哪 → 知道了 workflow 适合「确定性流程 + 不确定性步骤」的场景
            ↓ 但这引出下一个问题
Q2 谁决定编排 → 知道了编排是人定的，LLM 只管节点内决策
               ↓ 那这种设计在业界算什么水平
Q3 框架对比 → 知道了和 LangGraph 同构、方向正确，有 4 个盲点要还债
```

这三个问题串起来就是 Stage 5 的「理解闭环」：会用（Q1）-> 懂原理（Q2）-> 有判断力（Q3）。
