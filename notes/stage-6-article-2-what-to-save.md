# 一次 Agent Run 应该保存哪些状态

> Stage 6 文章草稿 2/4

---

## 答案：5 个字段，不少一个不多一个

```java
public record Checkpoint(
    String checkpointId,    // 1. 快照标识
    String runId,           // 2. Run 标识
    RunState status,        // 3. 生命周期状态
    String cursor,          // 4. 下一个待执行节点
    WorkflowState state,    // 5. 完整黑板
    long timestamp,         // 元数据
    int stepsExecuted,      // 元数据
    Object pendingInput     // 6. 暂停节点的输入
) {}
```

核心是 5 个，逐个说为什么必须存。

## 1. runId：Run 的唯一标识

```
不存 runId -> 恢复时不知道这是哪个 Run
存 runId -> CheckpointStore 用它做 key（load(runId) / delete(runId)）
```

runId 还是对外关联键：审批系统用 `requestId -> runId`、事件系统用 `eventKey -> runId`、调度器用 `time -> runId`。恢复时用 runId 查回 Checkpoint。

## 2. status：生命周期状态

```java
public enum RunState {
    RUNNING, PAUSED, SUCCEEDED, FAILED, CANCELLED
}
```

```
不存 status -> 恢复后不知道 Run 是暂停了还是失败了
存 status -> 恢复时校验：只有 PAUSED 的 Run 才能 resume
```

恢复时如果 status 不是 PAUSED，说明 Run 已经终态（SUCCEEDED/FAILED/CANCELLED），不应该 resume。

## 3. cursor：下一个待执行节点（最关键）

```
不存 cursor -> 恢复后从 START 重新跑，前 N 步白跑
存 cursor -> 恢复后从 cursor 继续，已完成节点跳过
```

cursor 是幂等恢复的核心。暂停时 cursor = 暂停的节点（恢复时重新执行这个节点，因为它是抛了 PauseException 的，没执行完）；正常执行时 cursor = 下一个待执行节点。

**为什么暂停节点要重跑？** 因为它抛了 PauseException，没产出 output。重跑时 `ctx.isResuming()=true`，走恢复路径（检查审批结果），不再抛 PauseException。

## 4. WorkflowState：完整黑板

```
黑板三区：
  - input：用户原始输入（只读）
  - variables：每个节点的 output，key = node id
  - trace：每步的 StepRecord（节点/状态/耗时/尝试次数）
```

```
不存黑板 -> 恢复后路由条件读不到变量（如 s.get("intent")），路由失败
存黑板 -> 恢复后条件边能正确求值，节点输出不丢
```

黑板是 Workflow 的全部运行时状态。存了黑板 = 存了「跑到这一步时世界长什么样」。恢复时读回黑板，路由条件、节点输出、执行轨迹全部恢复。

**不存 Workflow 本身**（图定义）。Workflow 是不可变的，用 runId 关联到 workflow name 重新加载即可。存图定义浪费空间。

## 5. pendingInput：暂停节点的输入

```
不存 pendingInput -> 恢复时暂停节点的 ctx.input() 是 null，行为错误
存 pendingInput -> 恢复时 ctx.input() = 暂停前的输入，节点能正确执行
```

暂停发生在节点执行中途（抛 PauseException）。节点的 input 是上一个节点的 output，这个值在黑板里没有（因为当前节点没执行完，没产出 output）。所以必须单独存。

恢复时：`NodeContext.of(state, run.getPendingInput(), runId, true)`。

## 不存什么

| 不存的 | 为什么 |
|-------|--------|
| Workflow（图定义） | 不可变，runId 关联到 workflow name 重新加载 |
| 节点实例 | 节点是无状态的（AgentNode 的 AgentState 是例外，见下） |
| RetryPolicy | 在 Workflow 定义里，不随 Checkpoint 变 |
| Edge 定义 | 在 Workflow 定义里 |

## AgentNode 的特殊情况

AgentNode 持有 AgentState（节点内多轮记忆）。这是节点不是完全无状态的例外。

```
方案：AgentState 序列化进黑板 variables
key = "agentState:" + nodeId
value = 序列化的 AgentState（messages + steps）

恢复时：AgentNode 从黑板读回自己的 AgentState
```

这是 Stage 6 设计的 D5，当前未实现（规划中的尾巴），但机制设计已定。

## 一句话

**存 cursor（断点）+ 黑板（世界状态）+ pendingInput（断点节点输入）+ runId/status（元数据）。** 不存图定义（不可变）、不存节点实例（无状态）。存最少的、不可重建的东西。
