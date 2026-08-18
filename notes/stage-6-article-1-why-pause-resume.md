# Agent 为什么必须支持暂停和恢复

> Stage 6 文章草稿 1/4

---

## 纯同步 Agent 的三个死穴

### 死穴 1：审批阻塞线程

```
用户发起退款 -> Agent 调用审批工具 -> 等主管批
主管在开会，3 小时后才批
线程被阻塞 3 小时，占用资源，无法服务其他请求
```

ReAct 循环是同步的：`model.chat()` -> `tool.execute()` -> `model.chat()` -> ...。工具执行阻塞线程。审批工具「执行」= 等人决定，人决定要几小时，线程就占几小时。

### 死穴 2：进程崩了全部白跑

```
Agent 跑了 10 步（意图识别 -> 查询 -> 分析 -> ...）
第 11 步时进程 OOM 崩了
重启后从第 1 步重新开始，前 10 步白跑
LLM 调用白花钱，用户等的时间白等
```

AgentState 在内存里，进程死了状态就没了。Workflow 虽然可复用（不可变图定义），但运行状态（WorkflowState）是易失的。

### 死穴 3：长任务没法取消

```
Agent 跑一个 20 分钟的数据分析任务
跑了 10 分钟用户说「不用了」
没有取消机制，只能 kill 进程或等它跑完
```

同步循环没有取消检查点，`Thread.interrupt()` 会打断 IO 操作（包括 LLM 调用），行为不可控。

## 暂停-恢复怎么解决这三个死穴

### 解决 1：暂停释放线程

```
审批节点执行 -> 发审批请求 -> 抛 PauseException -> Runtime 存 Checkpoint -> 返回 PAUSED
线程立即释放，可以服务其他请求

几小时后主管审批通过 -> resume(runId) -> 从 Checkpoint 恢复 -> 继续跑
```

关键机制：**节点抛 `PauseException` 表达「我需要等」，Runtime catch 后存档退出，不阻塞线程。** 恢复时从存档点继续，对调用方来说是两次独立的同步调用。

### 解决 2：Checkpoint 持久化

```
每步执行后，状态写入 Checkpoint（cursor + 黑板 + trace）
进程崩溃 -> 重启 -> 从 CheckpointStore 读回 -> 从 cursor 继续
前 N 步不重跑，LLM 不重花
```

Checkpoint 存的是「下一个待执行节点 + 完整黑板」，恢复时从断点继续，不是从头开始。

### 解决 3：协作式取消

```
Run.cancel() -> 设置 volatile 标志
Runtime 主循环每步检查标志 -> 命中则 CANCELLED 退出
当前节点执行完才检查，不打断 IO
```

协作式取消：节点执行完当前步骤后检查标志，干净退出。代价是取消不是即时的（等当前节点完成），但 Agent 场景可接受。

## 什么场景必须暂停-恢复

| 场景 | 同步够用吗 | 必须暂停-恢复 |
|------|-----------|-------------|
| 编码助手审批命令 | ✅（用户在场，秒级决定） | ❌ |
| 客服退款审批 | ❌（主管不在，几小时） | ✅ |
| 等 CI 通过 | ❌（几分钟到几十分钟） | ✅ |
| 定时检查状态 | ❌（2 小时后） | ✅ |
| 开放式问答 | ✅（无审批无等待） | ❌ |

**判断标准：工具执行是否需要「等外部条件」且等待时间超过秒级。** dsh 和 pi 都是 Coding Agent，审批是秒级（用户在场），turn 内同步够用。企业 Agent 审批是小时级，必须暂停-恢复。

## 代码机制（我们的实现）

```java
// 节点请求暂停
public NodeResult execute(NodeContext ctx) throws Exception {
    if (ctx.isResuming()) {
        // 恢复路径：检查审批结果
        Boolean decision = approvalService.checkDecision(ctx.runId(), id);
        if (decision == null) throw new PauseException(id, "still pending");
        if (!decision) throw new ApprovalRejectedException(id);
        return NodeResult.of(ctx.input());
    } else {
        // 首次：发请求，然后暂停
        approvalService.requestApproval(ctx.runId(), id, summary, ctx.input());
        throw new PauseException(id, "waiting for approval");
    }
}

// Runtime catch 暂停
try {
    outcome = executeWithRetry(workflow, node, ctx);
} catch (PauseException pe) {
    run.setCursor(cursor);           // 记住停在哪个节点
    run.setPendingInput(lastOutput);  // 记住节点的输入
    run.setStatus(RunState.PAUSED);
    store.save(run.toCheckpoint());   // 持久化
    return ExecutionResult.paused(resumeToken, state);
}

// 恢复
Run run = Run.fromCheckpoint(store.load(runId), workflow);
runtime.execute(run);  // 从 cursor 继续，不重跑已完成节点
```

## 一句话

**同步 Agent 是「一跑到底」的，暂停-恢复把它变成「可中断、可续跑」的。** 不是所有场景都需要，但一旦工具执行要等外部条件（审批/事件/定时），同步就死路一条。
