# Stage 6 框架对比：dsh / pi / 我们怎么做 Checkpoint

> 对应阶段：Stage 6 - State、Checkpoint 和长任务
> 来源：理解 Checkpoint 机制后，对比 dsh 和 pi 的实现，验证我们的方案合理性
> 配套：架构设计见 [architecture-stage-6.md](architecture-stage-6.md)，使用教程见 [stage-6-usage-runid-correlation.md](stage-6-usage-runid-correlation.md)

---

## 一、三框架的核心单位对比

```text
dsh：  session -> event log -> 重放恢复（没有 run 概念）
pi：   session -> operation(runId) -> event log -> 重放恢复（run 在 session 内）
我们： Run(runId) -> Checkpoint snapshot -> 读快照恢复（Run 独立于 session）
```

| 维度 | dsh | pi | 我们 |
|------|-----|----|------|
| 核心单位 | session | session + operation(runId) | Run(runId) |
| runId 范围 | 无 run 概念 | session 内（operation 级） | 全局（跨 session） |
| 持久化哲学 | event sourcing | event sourcing | checkpoint snapshot |
| 审批模式 | turn 内同步 | turn 内同步（extension 拦截） | 跨 turn 暂停-恢复 |

**pi 比 dsh 精细**（多了 operation/runId 层），**我们和两者都不同**（Run 独立于 session + snapshot 恢复）。

---

## 二、dsh 怎么做

### 2.1 核心设计：session-centric，event sourcing

dsh 的整个设计围绕 **session**（会话）。一个 session = 用户的一个对话，所有状态（消息、工具调用、审批、调度）都挂在 session 上。

### 2.2 审批：turn 内同步阻塞

```text
dsh 的 user-approval：
  ctx.approval.request(req) -> 返回 allowed-once / rejected / cancelled / unavailable
  这是同步调用，在 agent turn 内完成
  没有 PauseException，没有 Checkpoint，没有 ResumeToken
```

dsh 的 README 明确写了「Requests are valid only inside an open turn」--审批必须在当前回合完成，不做跨回合等待。如果要跨回合，dsh 的说法是「a durable out-of-turn approval workflow is deferred」--还没做。

**这是 dsh 的一个明确局限**：它没有我们 Stage 6 做的「暂停-等几小时-恢复」能力。

### 2.3 定时恢复：session-local

dsh 有 `schedule` 包，但有一个关键约束：

```typescript
export type ScheduleDeliveryMode = 'session-local'
// Fixed v1 delivery boundary: the original session must be live.
```

**dsh 的 schedule 是 session-local 的**--定时器只在当前 session 活着时有效，进程死了定时器就没了。恢复靠重放 session log（event sourcing）。

### 2.4 持久化：event sourcing

```text
dsh：session-persistence（event sourcing）
  - 每个操作追加为 session event（schedule/change、approval/asked、approval/decided）
  - 崩溃后重放 event log 重建状态
  - session-checkpoint-policy 决定什么时候 flush（模型请求前、工具执行前、每步边界）
  - JSONL / SQLite 两种 backend
```

### 2.5 关联键：不需要传递 runId

dsh 根本没有「runId 怎么传递给人类」这个问题，因为它的单位是 session--sessionId 天然绑定在用户交互上下文里，不需要额外传递。

---

## 三、pi 怎么做

### 3.1 核心设计：session + operation(runId)，event sourcing

pi 和 dsh 一样以 session 为中心（event sourcing + JSONL），但 pi 比 dsh 多了一层 **operation/run** 概念。pi 的 `OperationStartedRecord` 里有 `runId`，一个 session 可以有多个 operation（run）。

### 3.2 审批：extension 的 tool_call 拦截，同步阻塞

```typescript
// pi 的 permission-gate extension
pi.on("tool_call", async (event, ctx) => {
    if (isDangerous(event.input.command)) {
        const choice = await ctx.ui.select("Allow?", ["Yes", "No"]);
        if (choice !== "Yes") return { block: true, reason: "Blocked by user" };
    }
    return undefined;
});
```

**pi 的审批是 tool_call 事件的同步拦截**--`await ctx.ui.select()` 阻塞等用户选 Yes/No，在当前 turn 内完成。和 dsh 一样，**没有跨 turn 的暂停-恢复**。

### 3.3 持久化：event sourcing（session log）

pi 的 session log 记录了完整的 operation 生命周期：

```typescript
// pi 的 LaneRecord 类型（event log 的事件类型）
OperationStartedRecord    // operation 开始（含 runId + initialMessages + resumeData）
StepAttemptRecord         // 每步尝试（含 runId + step + attempt + resultEntryId）
ToolStartedRecord         // 工具调用开始（含 runId + toolCallId + replay 策略）
QueueEnqueuedRecord       // 队列入队（steer / followUp / nextRun）
WriteDeferredRecord       // 延迟写入
UsageRecord               // token 用量
AbortRequestedRecord      // 取消请求（含 runId）
OperationFinishedRecord   // operation 结束（outcome: completed/aborted/failed/declined）
```

恢复机制：

```typescript
findOpenOperations(lane, { limit: 2 })
// 零结果 = lane 空闲
// 一结果 = 有挂起的 operation（suspended），需要恢复
// 两结果 = corruption（不允许两个 open operation）
```

### 3.4 关联键：runId 在 session 内

pi 的 runId 不需要传递给外部世界--因为它和 dsh 一样，审批是 turn 内同步的，不需要跨 turn 关联。runId 只在 session 内部用于标识 operation，恢复时从 log 里查。

### 3.5 一个有趣的细节：resumeData

pi 的 `OperationStartedRecord` 里有：

```typescript
resumeData?: { [extensionId: string]: JsonValue };
```

给 extension 存恢复数据的--每个 extension 可以在暂停时存自己的状态，恢复时读回。这和我们的 `WorkflowState.variables`（黑板存节点输出）是同一个思路，但粒度不同：pi 按 extension 存，我们按 node id 存。

---

## 四、三框架逐维度对比

### 4.1 审批能力

| 维度 | dsh | pi | 我们 |
|------|-----|----|------|
| 审批位置 | turn 内同步 | turn 内同步（extension 拦截） | 跨 turn 暂停-恢复 |
| 阻塞线程 | 是 | 是 | 否（暂停释放线程） |
| 跨 turn 等待 | ❌（deferred） | ❌ | ✅ |
| 等几小时审批 | ❌ | ❌ | ✅ |
| 实现机制 | `ctx.approval.request()` | `pi.on("tool_call")` + `await ctx.ui.select()` | `PauseException` + Checkpoint + Resume |

**这是我们的差异化能力**：dsh 和 pi 的审批都是「当场决定」，我们的审批可以「等几小时甚至几天」。

### 4.2 持久化哲学

| 维度 | dsh（event sourcing） | pi（event sourcing） | 我们（snapshot） |
|------|----------------------|---------------------|-----------------|
| 存什么 | 每个操作的事件日志 | 每个操作的事件日志 | 某一时刻的完整状态快照 |
| 恢复方式 | 重放全部事件 | findOpenOperations + 重放 | 直接读快照 |
| 存储开销 | 持续增长（要 compaction） | 持续增长（pi 有 compaction） | 固定大小（每次覆盖） |
| 可审计性 | 强（完整操作历史） | 强（完整操作历史） | 弱（只有最终状态 + trace） |
| 实现复杂度 | 高（事件版本、重放、compaction） | 高（事件版本、重放、compaction） | 低（序列化/反序列化） |
| compaction | ❌ | ✅（token 超阈值总结历史） | ❌（Stage 8 Memory） |

### 4.3 关联键

| 维度 | dsh | pi | 我们 |
|------|-----|----|------|
| 关联键 | sessionId | sessionId + runId | runId |
| runId 范围 | 无 run | session 内 | 全局 |
| 需要传递 runId | 不需要 | 不需要（session 内可见） | 需要（存到外部关联存储） |
| 跨进程恢复 | log 持久化 + 重放 | log 持久化 + 重放 | CheckpointStore 持久化 + 读回 |

### 4.4 取消与超时

| 维度 | dsh | pi | 我们 |
|------|-----|----|------|
| 取消 | abort_requested 事件 | AbortRequestedRecord | volatile 标志 + 协作式 |
| 取消粒度 | session 级 | operation 级（runId） | Run 级 |
| 超时 | ❌ | ❌ | ✅（TimeoutPolicy，待实现） |

---

## 五、三框架的定位差异

```text
dsh：  生产级 Agent Harness 平台
       - 一切皆插件（Cordis 框架）
       - event sourcing 追求可审计性
       - 审批 turn 内同步，够用就行
       - schedule 是 session-local，不做跨进程

pi：   生产级 Coding Agent
       - session + operation 两层（比 dsh 精细）
       - event sourcing + compaction（token 管理）
       - 审批用 extension 拦截，灵活但仍是 turn 内同步
       - 有 resumeData 给 extension 存恢复状态

我们： 教学型 Agent Runtime
       - Run 独立于 session（更灵活的恢复触发）
       - snapshot 恢复（机制清晰，易理解）
       - 审批跨 turn 暂停-恢复（能力领先）
       - runId 关联存储（支持外部系统触发恢复）
```

---

## 六、哪种方式好

### 没有绝对的好坏，取决于目标

```text
如果你的目标是「生产级 Coding Agent」-> pi 的方式更好
  - event sourcing 给你完整审计能力（每次操作可追溯）
  - compaction 管理长对话的 token 膨胀
  - extension 拦截足够灵活，turn 内同步审批对编码场景够用

如果你的目标是「平台级 Agent Harness」-> dsh 的方式更好
  - 一切皆插件，扩展性最强
  - session-centric 简化了关联问题
  - event sourcing 适合多租户审计

如果你的目标是「理解 Agent 架构 + 支持长任务审批」-> 我们的方式更好
  - snapshot 机制清晰，容易理解和教学
  - 跨 turn 暂停-恢复是 dsh 和 pi 都没有的能力
  - Run 独立于 session，支持外部系统触发恢复
```

### 三个维度的取舍判断

**1. event sourcing vs snapshot**

```text
event sourcing 优点：完整审计、可重放、可回溯
event sourcing 代价：实现复杂（事件版本、重放逻辑、compaction）、存储持续增长

snapshot 优点：实现简单、恢复快（直接读）、存储固定
snapshot 代价：丢失中间状态、不可回溯、需要定期快照
```

教学型选 snapshot（简单清晰），生产级选 event sourcing（可审计）。**我们的选择对教学目标是合理的**，生产化时可以考虑混合方案：snapshot 做快速恢复 + event log 做审计。

**2. turn 内同步 vs 跨 turn 暂停-恢复**

```text
turn 内同步 优点：实现简单、不需要持久化中间状态
turn 内同步 代价：线程阻塞、无法等几小时、长任务不可行

跨 turn 暂停-恢复 优点：释放线程、可等几小时/几天、支持长任务
跨 turn 暂停-恢复 代价：需要 Checkpoint 机制、需要 runId 关联存储、实现复杂
```

编码场景（pi/dsh）选 turn 内同步（审批快、用户在场），企业审批场景选跨 turn 暂停-恢复（审批慢、用户不在场）。**我们的选择对企业场景是必要的**。

**3. session-centric vs run-centric**

```text
session-centric 优点：关联简单（sessionId 天然在上下文里）、适合对话场景
session-centric 代价：恢复触发只能来自 session 内

run-centric 优点：Run 独立、外部系统可触发恢复（Webhook/定时器/事件）
run-centric 代价：需要 runId 关联存储、关联复杂
```

对话场景选 session-centric（用户在交互），自动化场景选 run-centric（外部事件驱动）。**我们的选择对「外部事件触发恢复」场景是必要的**。

### 最终判断

```text
对于我们的目标（教学 + 企业 Agent + 长任务）：
  - snapshot 恢复：合理（教学清晰，后续可加 event log）
  - 跨 turn 暂停-恢复：必要（企业审批场景必需）
  - run-centric：必要（外部事件触发恢复）

对于 pi 的目标（生产级 Coding Agent）：
  - event sourcing：合理（可审计）
  - turn 内同步审批：合理（编码场景用户在场）
  - session-centric：合理（对话场景）

两者不是谁好谁坏，是不同目标的合理取舍。
```

---

## 附：从 pi 借鉴的点

对比后发现了两个值得后续借鉴的设计：

1. **pi 的 compaction 机制**--token 超阈值时自动总结历史消息。我们 Stage 8（Memory）需要这个能力，可以参考 pi 的 `CompactionEntry` 设计。

2. **pi 的 resumeData**--按 extension 存恢复数据。我们的 `WorkflowState.variables` 是按 node id 存，但 AgentNode 的 `AgentState` 序列化（D5）可以借鉴 pi 的思路：用结构化的 key（如 `agentState:nodeId`）而不是扁平存。
