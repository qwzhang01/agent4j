# Stage 6 使用教程：runId 在真实世界怎么流转

> 对应阶段：Stage 6 - State、Checkpoint 和长任务
> 来源：理解 Checkpoint 机制的「最后一公里」--运行时知道 runId，但真实世界（审批人、事件系统）怎么拿到它
> 核心结论：runId 不需要被「记住」，它需要被「存到某个能查回来的地方」。人类记不住 UUID，LLM 也不持有 runId--它是节点代码通过 `ctx.runId()` 拿到的。关键在于暂停时把 runId 存到正确的「关联存储」里。
> 配套：架构设计见 [architecture-stage-6.md](architecture-stage-6.md)

---

## 一、核心问题

Checkpoint 机制跑通了：节点抛 `PauseException` -> 存 Checkpoint -> 返回 `ResumeToken(runId)` -> `RunManager.resume(runId)` 恢复。

但「最后一公里」有个问题：

```text
Runtime 知道 runId（它是 Run 的唯一标识）
但是：
  - 人类审批者记不住 UUID
  - LLM 不持有 runId（它在 AgentNode 内部跑，不知道外层 Run 的存在）
  - 外部事件系统（CI、PR、Webhook）也不认识 runId
```

那 runId 怎么从 Runtime 流转到真实世界，再从真实世界流回来触发恢复？

---

## 二、答案：runId 是关联键（correlation key），不是人类要记住的东西

**runId 在暂停时被存到某个外部系统（审批系统/事件 broker/调度器），在外部条件满足时被查回来触发恢复。**

```text
                    RunManager.start()
                         |
                    PAUSED + runId
                         |
          ┌──────────────┼──────────────┐
          ↓              ↓              ↓
     审批系统         事件订阅        定时调度
   requestId->runId  eventKey->runId  time->runId
          |              |              |
     人类操作         外部事件        时间到达
          |              |              |
          └──────────────┼──────────────┘
                         ↓
                  RunManager.resume(runId)
```

关键机制：**外部系统存一个「人类友好/事件友好的 key -> runId」的映射，触发恢复时用 key 查回 runId。**

---

## 三、三个场景的 runId 流转

### 场景 A：人工审批（当前 Stage 6）

```text
1. HTTP 请求进来 -> Controller 调 RunManager.start() -> PAUSED
2. Controller 从 ExecutionResult.resumeToken() 拿到 runId
3. Controller 调 approvalService.requestApproval(runId, nodeId, summary, payload)
   ↓
   ApprovalService 内部做两件事：
   - 生成人类友好的 requestId（如 "APR-2026-0818-001"）
   - 存映射：requestId -> runId
   - 发通知给审批人（邮件/钉钉/企微），通知里带链接 /approve?requestId=APR-001
4. 审批人点链接 -> 前端调 /approve?requestId=APR-001&decision=true
5. 后端用 requestId 查回 runId -> approvalService.setDecision(runId, nodeId, true)
6. 后端调 RunManager.resume(runId)
```

**关键点**：审批人看到的是 `requestId`（人类友好），不是 `runId`（UUID）。`ApprovalService` 内部维护 `requestId -> runId` 的映射。

代码示例（ApprovalService 的真实实现，非 Mock）：

```java
public void requestApproval(String runId, String nodeId, String summary, Object payload) {
    String requestId = "APR-" + generateId();
    requestMap.put(requestId, runId);              // 存映射
    notifyApprover(requestId, summary);            // 发通知，带 requestId
}

// 审批回调时：
public void onApprovalCallback(String requestId, boolean approved) {
    String runId = requestMap.get(requestId);      // 查回 runId
    setDecision(runId, nodeId, approved);          // 设置决策
    runManager.resume(runId);                       // 恢复
}
```

**人类不持有 runId，人类持有 requestId。runId 在系统内部流转。**

### 场景 B：LLM 等待外部事件（Stage 7 的场景）

```text
AgentNode 内部：
  LLM 输出："我需要等 CI 通过后再继续"
  ↓
节点代码（不是 LLM）做两件事：
  1. ctx.runId() 拿到 runId
  2. eventBroker.subscribe("ci-passed:pr-123", runId)   // 存映射
  3. throw new PauseException(nodeId, "waiting for CI")
  ↓
Run 暂停，runId 存在事件订阅里

几天后 CI 通过：
  eventBroker.fire("ci-passed:pr-123")
  -> eventBroker 查到 runId
  -> RunManager.resume(runId)
```

**LLM 不持有 runId。** LLM 只产出「我要等 CI」这个意图。节点代码从 `ctx.runId()` 拿到 runId，存到事件订阅系统。事件到达时，事件系统用订阅时的 runId 触发恢复。

### 场景 C：定时恢复（Stage 7 的场景）

```text
AgentNode 内部：
  LLM 输出："2 小时后再检查一次"
  ↓
节点代码：
  1. ctx.runId() 拿到 runId
  2. scheduler.schedule(runId, Duration.ofHours(2))    // 存映射
  3. throw new PauseException(nodeId, "scheduled resume in 2h")
  ↓
Run 暂停，runId 存在调度器里

2 小时后：
  scheduler 定时触发
  -> 查到 runId
  -> RunManager.resume(runId)
```

---

## 四、关键设计：为什么 NodeContext 要提供 ctx.runId()

三个场景的共性：**节点代码在暂停前，把 runId 存到外部系统。** 这就是为什么 `NodeContext` 要提供 `ctx.runId()`：

```java
// 节点代码在暂停前，把 runId 存到外部系统
public NodeResult execute(NodeContext ctx) throws Exception {
    if (ctx.isResuming()) {
        // 恢复路径
    } else {
        // 首次：把 runId 存到外部系统
        eventBroker.subscribe(eventKey, ctx.runId());  // ← 关键
        throw new PauseException(id, "waiting for event");
    }
}
```

**LLM 永远不直接接触 runId。** LLM 在 AgentNode 内部跑，产出的是业务意图（"等 CI"、"2 小时后检查"、"需要审批"）。节点代码负责把 `ctx.runId()` 存到正确的关联存储里。

这是「确定性骨架（节点代码）+ 不确定性血肉（LLM 意图）」分工的又一个体现：
- LLM 决定「等什么」（业务意图）
- 节点代码决定「怎么等」（存 runId 到哪个外部系统）

---

## 五、三种关联存储的对照

| 场景 | 关联存储 | 存的 key | 存的 value | 触发恢复的方式 |
|------|---------|---------|-----------|--------------|
| 人工审批 | ApprovalService 内部 Map | requestId（人类友好） | runId | 审批回调 -> 查 runId -> resume |
| 事件等待 | EventBroker 订阅表 | eventKey（如 ci-passed:pr-123） | runId | 事件到达 -> 查 runId -> resume |
| 定时恢复 | Scheduler 任务表 | 触发时间 | runId | 时间到达 -> 查 runId -> resume |

共性规律：**外部系统存「外部友好 key -> runId」映射，触发时查回 runId 调 resume。** Runtime 只负责「给我 runId 我就能恢复」，不关心 runId 怎么从外部系统查回来。

---

## 六、当前实现状态与缺口

### 已实现

- `NodeContext.runId()`：节点代码可以从 ctx 拿到 runId
- `MockApprovalService.requestApproval(runId, ...)`：接收 runId（测试用，直接存内存 Map）
- `MockApprovalService.setDecision(runId, nodeId, approved)`：测试代码直接用 runId 设置决策
- `RunManager.resume(runId)`：用 runId 恢复

### 缺口（诚实地说）

当前 `MockApprovalService` 的 `setDecision(runId, nodeId, approved)` 需要测试代码直接传 runId。真实场景需要一个完整的 `requestId -> runId` 映射机制。

这个映射逻辑属于 `ApprovalService` 的真实实现，不是 Runtime 的职责--Runtime 只负责「给我 runId 我就能恢复」。

**接口设计已经到位，缺的只是真实实现**（Stage 13 声明式层或真实部署时补）。

---

## 七、一句话总结

```text
人类持有 requestId，不持有 runId
LLM 产出业务意图，不接触 runId
节点代码从 ctx.runId() 拿到 runId，存到外部系统的关联存储里
外部条件满足时，用关联 key 查回 runId，调 RunManager.resume(runId)
```

**runId 是 Runtime 与外部世界之间的关联键，不是任何一方要「记住」的东西。它在暂停时被存出去，在恢复时被查回来。**
