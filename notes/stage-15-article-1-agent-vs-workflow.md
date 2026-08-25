# 企业 Agent 为什么必须区分 Agent 和 Workflow

> 配套蓝图：[architecture-stage-15.md](architecture-stage-15.md) §1 / D6 / D7 · 业务场景：[stage-15-business-scenario.md](stage-15-business-scenario.md)
> 对应实现：`agent-enterprise` 的 `task/` 包 + `agent-security` 治理链 + `agent-workflow` 的 `RunManager` / `HumanApprovalNode`
> 全剧本：[EnterpriseAssistantExample.java](../examples/src/main/java/io/github/qwzhang01/agent/examples/EnterpriseAssistantExample.java)
> 这是 Stage 15 系列的第 1 篇。Enterprise 是第一个领域 Profile：缺的是归属层，不是新 Runtime。

---

## 1. 我今天要解决什么问题

很多人第一次看到企业客服 Agent，会把它理解成「一个更会调工具的 ChatBot」：用户说「帮我把订单 8842 退款」，模型决定调 `refund_order`，调用成功就结束。这条路径在 Demo 里成立，在企业里会出事故。

企业真正要办的不是一次工具调用，而是一单业务：

```text
查单 → 校验政策 → 主管放行 → 执行退款 → 留痕
```

中间有不能绕过的审批，有已经发生的副作用，有可能横跨数小时甚至数天的等待。把整单业务塞进一次 `agent.run(...)`，等于把确定性流程交给非确定性模型。今天要回答的问题是：

> 企业场景里，哪些事该交给 Agent，哪些事必须交给 Workflow？两者叠在一起时，审批为什么是两层而不是一层？

---

## 2. 为什么会有这个概念

Stage 1-14 造好的 Runtime 同时给了两套执行器，它们解决的问题本来就不一样：

```text
Agent（ReActAgentLoop + GovernedToolExecutor）
  擅长：模型决定下一步、调用工具、在不确定信息里收敛到一句话
  单位：一次 run、若干 tool call

Workflow（GraphRuntime + RunManager + HumanApprovalNode）
  擅长：确定性骨架、节点顺序、暂停-恢复、副作用边界
  单位：一次 Run、若干 Node、checkpoint
```

企业场景把两套能力同时用上了，于是出现一个容易混淆的交叉点：**审批**。Stage 9 已经能在工具调用前问一次人（`ToolPermission.REQUIRES_APPROVAL`）；Stage 5/6 已经能在流程节点上暂停整条 Run（`HumanApprovalNode` + `RunManager.resume`）。如果不先分清这两层，就会做出三种坏设计：

1. 只用 Agent：主管审批变成「模型再问一句」，流程可以被 prompt 绕过，已经查过的单可能被重查，已经动过的数据可能被重做。
2. 只用 Workflow：每次问答都要先画图，知识检索、角色权限、成本记账全部挤进节点，Agent 的推理优势被浪费。
3. 把 `Run` 直接当工单：技术执行标识泄漏给业务用户，审批记录挂错对象，崩溃恢复时找不到「那单退款」。

Stage 15 的答案不是再写一个企业 Runtime，而是在已有机制上加一层**领域投影**：`BusinessTask` 面向业务，`Run` 面向执行。Enterprise Profile 依赖 `agent-core` + `agent-memory` + `agent-security` + `agent-workflow`，不依赖 `agent-product` / `agent-channel`。缺的是归属层（谁的工单、谁放行、从哪恢复），不是新循环。

---

## 3. 它解决了什么问题

区分 Agent 和 Workflow 之后，企业请求可以走两条入口，职责不再打架：

```text
同步问答  EnterpriseAssistant.ask(ctx, question)
  → 预算闸 → 请求作用域装配 → Agent.run
  → 模型可调 search_knowledge / query_order / refund_order
  → 工具级治理在 loop 内完成（权限 / 审批 / 审计）

长任务    EnterpriseAssistant.submitTask(ctx, description, workflow)
  → 同一预算闸
  → EnterpriseTaskManager.submit → RunManager.start
  → 跑到 HumanApprovalNode 时 PAUSED
  → 任务进入 WAITING_APPROVAL
  → approve → resume，已完成节点不重跑
```

对应蓝图 D6 / D7 的两句话：

```text
工具审批是执行中的一道闸：loop 不停，这次调用能不能做
任务审批是流程中的一个节点：Run 暂停，这单业务能不能继续
```

退款场景两层都要。客服 Alice 触发 `refund_order`，角色矩阵没授这个工具，兜底 `ToolPolicy` 把它标成 `REQUIRES_APPROVAL`——这是安全闸。同一单业务若走工单流程，主管 Bob 批准的是「退款工单 T-0001」，不是某一次 tool call——这是流程节点。两层叠加是纵深，不是冗余。

---

## 4. 核心抽象和架构

站在最顶层：

```text
        企业门面（agent-enterprise，Stage 15）
        ┌────────────────────────────────────────────┐
        │  EnterpriseAssistant                       │
        │    ask()          → Agent 路径             │
        │    submitTask()   → Workflow 路径          │
        └──────────────┬───────────────┬─────────────┘
                       │               │
        ┌──────────────▼──┐     ┌──────▼──────────────┐
        │ Agent 路径       │     │ Workflow 路径        │
        │ ReActAgentLoop  │     │ EnterpriseTaskManager│
        │ GovernedTool    │     │ BusinessTask         │
        │ Executor        │     │ TaskApprovalRecord   │
        │ RoleBased       │     │ TaskApprovalBridge   │
        │ PermissionChecker│    │ RunManager +         │
        └─────────────────┘     │ HumanApprovalNode    │
                                └──────────────────────┘
```

四组必须分清的对象：

| 抽象 | 包 | 一句话 |
|---|---|---|
| `EnterpriseAssistant` | `io.github.qwzhang01.agent.enterprise` | 企业统一入口，同步问答和长任务共用预算闸 |
| `BusinessTask` | `enterprise.task` | 业务工单：`taskId` + 状态机 + `runIds` 历史 + 审批记录 |
| `EnterpriseTaskManager` | `enterprise.task` | 把 `submit` / `approve` / `reject` / `recover` 投影到 `RunManager` |
| `TaskApprovalBridge` | `enterprise.task` | 装配级审批通道：工作流侧问、管理侧答，必须比单个 manager 活得久 |

`BusinessTask` 的状态机来自 `ExecutionResult.Status` 的映射，不是另造一套运行时：

```text
SUCCEEDED → DONE
FAILED    → FAILED
PAUSED    → WAITING_APPROVAL   （本 Profile 里唯一会暂停 Run 的是审批节点）
CANCELLED → CANCELLED
```

`task ≠ run`：一个任务可以对应多个 run（提交、同 run 的 resume、失败重试），`currentRunId()` 取 `runIds` 末位。审批记录挂在 task 上，checkpoint 挂在 run 上。

`EnterpriseTaskManager` 还有两处实现细节，写文章时容易漏、写代码时会踩：

1. **runId 捕获是差集，不是 `start` 的返回值。** `RunManager.start` 只在 PAUSED 时经 `resumeToken().runId()` 暴露 id；终态 run 没有这条路径。manager 在调用前后对 `listRuns()` 做快照差集——这是零存量改动纪律下的唯一通用捕获法，蓝图预告过这处妥协。
2. **`reject` = `cancel` 标记 + 再 `resume`。** `RunManager.cancel` 是协作式 flag，PAUSED run 上只置位；必须再 resume 一次，run 才落到 `CANCELLED`。已执行节点不回滚。`recover(snapshot, workflow)` 则是另一条入口：新 manager + 同一 `CheckpointStore`，`resume(runId, workflow)` 重新进入审批节点；内存决策表是空的，于是再次 PAUSED，主管重批即可。

---

## 5. 一次完整数据流

用 `EnterpriseAssistantExample` 的 T3 / T4 对照看最清楚。

**T3 · 工具级闸门（Agent 路径，loop 不停）**

```text
Alice 登录得到 RequestContext
  → refundAssistant.ask(alice, "帮我把订单 8842 退款")
  → 模型发出 ToolCall(name=refund_order)
  → RoleBasedPermissionChecker：客服矩阵只有 search_knowledge / query_order
  → 兜底 ToolPolicy：refund_order = REQUIRES_APPROVAL
  → ToolApprovalService 放行
  → 执行 refund_order
  → EnterpriseAuditTrail 记 APPROVED + EXECUTED，归属 acme / u-alice
```

这一层回答的是：这次调用越不越权。Run 没有暂停，Agent loop 继续走到终答。

**T4 · 任务级节点（Workflow 路径，Run 暂停）**

```text
Workflow: prepare(ActionNode) → approval(HumanApprovalNode) → execute(ActionNode)

assistant.submitTask(alice, "refund order 8842", refundFlow)
  → EnterpriseTaskManager.submit
  → RunManager.start
  → prepare 执行（副作用已发生）
  → HumanApprovalNode 向 TaskApprovalBridge.requestApproval
  → ExecutionResult.PAUSED → BusinessTask.WAITING_APPROVAL
  → checkpoint 落 FileCheckpointStore

assistant.approve(taskId, "u-bob", "金额在授权内")
  → TaskApprovalBridge.decide(runId, true)
  → RunManager.resume(runId)
  → prepare 不重跑
  → execute 执行
  → TaskApprovalRecord(APPROVED, u-bob) 挂在 task 上
  → BusinessTask.DONE
```

这一层回答的是：这单业务能不能往下走。主管审批的对象是工单，不是某次模型调用。

---

## 6. 最小代码或实验

副作用安全的核心证明在 `EnterpriseTaskManagerTest.approveResumesWithoutRerun`：用节点计数器锁住「prepare 恰好 1 次」。

```java
BusinessTask task = mgr.submit(aliceCtx, "refund order 8842", wf);
BusinessTask done = mgr.approve(task.taskId(), "u-bob", "amount within my limit");

assertEquals(BusinessTask.Status.DONE, done.status());
assertEquals(1, prep.get(), "prepare must NOT re-execute on resume");
assertEquals(1, exec.get());
assertEquals(TaskApprovalRecord.Decision.APPROVED, done.approvals().get(0).decision());
```

同文件还有四条必须一起看的实验：

- `submitPausesAtApproval`：提交后 `prepare=1`、`execute=0`，任务停在 `WAITING_APPROVAL`。这是「中间态可见」的证明。
- `doubleApprovalGates`：两个 `HumanApprovalNode` 之间的校验节点跨两次 approve 仍只执行 1 次；第一次 approve 后任务仍是 `WAITING_APPROVAL`。
- `rejectCancels`：拒绝后 `execute` 仍为 0，但 `prepare` 仍为 1。拒绝不回滚已发生副作用——这是诚实边界，Saga 补偿留给 v2。
- `crashRecoveryFromCheckpointFiles`：`mgr1` 提交后换 `mgr2`（新 `RunManager` + 同目录 `FileCheckpointStore` + **同一个** `TaskApprovalBridge`），`recover` 再暂停，`approve` 后 DONE，`prepare` 全生命周期仍为 1。

`privateBridgeCannotServeForeignRuns` 是反例：私有 bridge 的 approve 永远卡在 `WAITING_APPROVAL`，因为节点绑的还是上一任 manager 的通道。

门面层的对应测试是 `EnterpriseAssistantTest.taskPathThroughFacade`（`submitTask` → `WAITING_APPROVAL` → `approve` → `DONE`）和 `approvalRideAlong`（CSR 触发 `refund_order` 时治理链打出 `APPROVED` 事件）。两条路径都在，才是企业场景。

---

## 7. 常见误区

1. **「企业 Agent = 会调业务 API 的 ReAct」** —— 最危险。工具只解决「模型能不能碰到业务系统」。工单、主管放行、断点恢复、审批留痕，全部不在 ReAct 循环里。
2. **「有了工具审批就不需要任务审批」** —— 工具审批保的是单次调用安全；任务审批保的是业务流程。客服可以在问答里被拦一次退款工具，主管仍可能在工单里放行整单退款。对象不同，时间跨度不同。
3. **「`Run` 就是工单」** —— `Run` 是 `RunManager` 的技术执行；业务用户认的是 `T-0001`。把审批挂到 run 上，重试第二个 run 时审批证据会丢，崩溃恢复也找不到业务入口。
4. **「审批通道可以做成 manager 的私有内部类」** —— `EnterpriseTaskManagerTest.privateBridgeCannotServeForeignRuns` 锁住了这个坑。Workflow 节点在构造期捕获 `ApprovalService`；manager 可以因重启被换掉。决策表必须放在装配级的 `TaskApprovalBridge` 上。

---

## 8. 和相邻概念的区别

```text
Agent vs Workflow
  Agent：模型决定下一步，适合知识问答、工具选择
  Workflow：图决定下一步，适合确定性骨架和人工节点

工具审批 vs 任务审批
  工具审批：GovernedToolExecutor + ToolApprovalService，loop 不停
  任务审批：HumanApprovalNode + TaskApprovalBridge + resume，Run 暂停

BusinessTask vs Run
  BusinessTask：业务投影，用户和主管的索引
  Run：技术执行，checkpoint 和 cursor 的索引
  关系是 1:N，不是改名

用户身份 vs Agent 身份
  RequestContext / User：谁在要求（u-alice，角色=客服，归属=acme）
  Stage 12 ServiceAccount：谁在执行（svc:support-bot）
  本阶段 v1 先记用户侧归属；双归属数据模型已留字段
```

Profile 和 Runtime 的边界也在这里：`Tenant` / `BusinessTask` 在 Runtime 里不存在，在 Enterprise Profile 里是一等公民。Runtime 不需要知道「退款工单」。

---

## 9. 我的设计判断

最重的一条：**企业场景的主路径不是「更聪明的 Agent」，是「Agent 负责不确定，Workflow 负责不能错」。**

不确定的部分（这句话是不是在问政策、该不该先查知识、订单号是不是 8842）交给模型。不能错的部分（审批必须经过主管、已查单不能重做、拒绝必须留痕）交给图和 checkpoint。Stage 15 没有改 `ReActAgentLoop`，也没有改 `RunManager` 的恢复语义；它只是用 `BusinessTask` 把后者翻译成企业语言。

第二条判断来自 M15.4 的装配教训：**绑定什么，要看对象生命周期，不要看谁用起来方便。** 权限和审计绑请求（每请求一份），审批通道绑装配（跨代际一份）。这条规则后面讲请求链路和多租户隔离时还会再出现。

第三条是诚实边界：`reject` 只取消不回滚。`EnterpriseTaskManager` 的 javadoc 写得很清楚——已执行节点的副作用不是本层的补偿事务。框架先保证「不要再做错一次」，再谈「把做错的补回来」。

`failureAfterApprovalMapsToFailed` 补了一条容易忽略的映射：主管已经 `APPROVED`，后续节点仍可能 `FAILED`。业务状态和审批记录同时存在——「批过」不等于「办成」。`nonWaitingApprovalRejected` 则保证终态任务不能再批再拒。控制面的 fail-closed 不只发生在登录，也发生在任务状态机上。`noApprovalWorkflowCompletes` 覆盖无审批直通：`submit` 后直接 `DONE`，runId 走差集捕获而不是 resume token。两条捕获路径都要绿，manager 才能既服务长审批、又服务短工单。

---

## 10. 面试表达

> 「企业 Agent 不能只用 ReAct。ReAct 解决的是模型如何选工具，企业要解决的是一单业务如何在组织边界里办完。我把执行分成两层：Agent 路径走 `EnterpriseAssistant.ask`，工具级审批在 `GovernedToolExecutor` 里，loop 不停；Workflow 路径走 `submitTask`，任务停在 `HumanApprovalNode`，主管批准后 `RunManager.resume` 从 checkpoint 续跑。`BusinessTask` 是 Run 的业务投影，审批记录挂 task 不挂 run。工具审批保安全，任务审批保流程，两层是纵深不是重复。Enterprise 是第一个领域 Profile，缺的是归属层，不是新 Runtime。」

---

## 11. 下一篇连接什么

下一篇把「一次企业请求」从登录拉到记账，走完整条同步链：预算闸 → 请求作用域装配 → Agent.run → 事后记账 → 归属审计。你会看到 `RequestContext` 为什么必须显式传递，以及为什么身份不能藏进 ThreadLocal。

→ [stage-15-article-2-request-chain.md](stage-15-article-2-request-chain.md)
