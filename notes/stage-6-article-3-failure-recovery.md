# 长任务 Agent 的故障恢复设计

> Stage 6 文章草稿 3/4

---

## 三种故障场景

```
场景 1：正常暂停     节点抛 PauseException -> 存 Checkpoint -> resume 恢复
场景 2：进程崩溃     OOM/kill/断电 -> 重启 -> 从 CheckpointStore 读回 -> resume
场景 3：取消         cancel() -> volatile 标志 -> 下一个节点边界退出
```

## 场景 1：正常暂停-恢复

### 暂停流程

```
1. 节点执行时抛 PauseException
2. Runtime catch：
   - run.setCursor(cursor)          // 记住停在哪个节点
   - run.setPendingInput(lastOutput) // 记住节点输入
   - run.setStatus(PAUSED)
   - state.record(StepRecord.paused(...))
   - store.save(run.toCheckpoint())
3. 返回 ExecutionResult(PAUSED, resumeToken)
```

### 恢复流程

```
1. RunManager.resume(runId)
2. 从 activeRuns 或 CheckpointStore 取回 Run
3. run.setStatus(RUNNING)
4. runtime.execute(run):
   - cursor = run.getCursor()       // 从断点开始，不是从 START
   - lastOutput = run.getPendingInput()
   - 第一个节点 ctx.isResuming()=true
5. 继续正常执行：route -> execute -> 写黑板 -> 前进
```

### 幂等保证

```
已完成节点不重跑：
  - cursor 指向「下一个待执行节点」，不是「上一个执行的节点」
  - 已完成节点的 output 已在黑板 variables 里
  - 路由条件读黑板，能正确求值
  - trace 里已有已完成节点的记录

暂停节点重跑（但走恢复路径）：
  - 暂停节点抛了 PauseException，没产出 output
  - 重跑时 isResuming()=true，走恢复路径（检查审批结果），不再抛 PauseException
  - trace 里会出现两次该节点：一次 PAUSED，一次 SUCCESS
```

## 场景 2：进程崩溃恢复

### 前提：CheckpointStore 必须持久化

```
InMemoryCheckpointStore：进程死了数据丢了，不能崩溃恢复
FileCheckpointStore：写 JSON 文件到磁盘，进程死了数据还在（当前未实现，设计已定）
```

### 崩溃恢复流程

```
1. 进程重启
2. RunManager 用持久化 CheckpointStore 初始化
3. 调用 resume(runId, workflow)：
   - store.load(runId) 从磁盘读回 Checkpoint
   - Run.fromCheckpoint(cp, workflow) 重建 Run
   - runtime.execute(run) 从 cursor 继续
4. 用户无感知：就像正常 resume 一样
```

### 关键：workflow 参数必须传入

```java
// 崩溃恢复版 resume（需要传 workflow）
public ExecutionResult resume(String runId, Workflow workflow) {
    Run run = activeRuns.get(runId);
    if (run == null) {
        // 进程重启后 activeRuns 是空的，从磁盘读
        Checkpoint cp = store.load(runId).orElseThrow(...);
        run = Run.fromCheckpoint(cp, workflow);  // workflow 不在 Checkpoint 里，必须传
    }
    return runtime.execute(run);
}
```

Checkpoint 不存 Workflow（不可变，省空间），所以崩溃恢复时必须传入 Workflow 定义。生产环境可以用 `workflow name -> Workflow` 注册表，按 name 自动加载。

### 崩溃时机的边界情况

```
崩溃时机 1：节点执行前（刚路由完）
  -> cursor 已更新，但节点没执行
  -> 恢复后从 cursor 执行，正确

崩溃时机 2：节点执行中（LLM 调用到一半）
  -> 节点没产出 output，黑板没更新
  -> 恢复后从 cursor 重新执行该节点
  -> 如果节点有副作用（如发邮件），可能重复执行 -> 需要幂等性（见文章 4）

崩溃时机 3：节点执行后、写黑板前
  -> output 产出但黑板没更新
  -> 恢复后从 cursor 重新执行该节点
  -> 同上，需要幂等性

崩溃时机 4：写黑板后、存 Checkpoint 前
  -> 黑板更新了但 Checkpoint 没存
  -> 恢复后从旧 Checkpoint 的 cursor 执行
  -> 节点重新执行，output 可能不同（LLM 不确定性）
```

时机 2/3/4 的解法是幂等性，下一篇讲。

## 场景 3：取消

### 机制

```java
// Run 持有 volatile 标志
private volatile boolean cancelled = false;
public void cancel() { this.cancelled = true; }

// Runtime 主循环每步检查
while (!END.equals(cursor)) {
    if (run.isCancelled()) {
        state.record(StepRecord.cancelled(cursor));
        return ExecutionResult.cancelled(state);
    }
    // ... 正常执行
}
```

### 为什么用 volatile 不用 interrupt

```
Thread.interrupt()：
  - 打断 IO 操作（包括 LLM 调用）
  - 抛 InterruptedException，行为不可控
  - 可能导致节点状态不一致

volatile 标志（协作式取消）：
  - 当前节点执行完后检查
  - 干净退出，状态一致
  - 代价：取消不是即时的（等当前节点完成）
```

Agent 场景可接受「等当前节点完成」--节点执行是秒级的，不是毫秒级的。

### 取消 + 暂停的交互

```
Run 处于 PAUSED 状态时 cancel()：
  -> 设置 cancelled = true
  -> resume 时主循环第一步检查 cancelled -> CANCELLED
  -> 不会执行任何节点

这解决了「暂停后用户改主意不想继续了」的场景。
```

## 一句话

**正常暂停靠 PauseException + Checkpoint；崩溃恢复靠持久化 CheckpointStore + 传入 Workflow；取消靠 volatile 标志 + 协作式检查。** 三种场景的恢复入口都是 `runtime.execute(run)`，区别只是 Run 的来源（内存 / 磁盘 / 带取消标志）。
