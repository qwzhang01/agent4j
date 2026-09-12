# 实验记录：KP8 Durable Execution——间隙问题的结构性答案（2026-09-12）

> 性质：KP8（可恢复 ≠ 可持久化）的落地实验笔记，对应新增测试 `E8SideEffectGapExperimentTest`（agent-workflow，7 测试，零生产改动）。
> 关联：`learning-path-2026-09-knowledge-points.md` §KP8、`agent-platform-modules-map.md` §3（间隙问题原始识别）、`stage-6-article-4-idempotency.md`（三层幂等）、`Checkpoint` D2（cursor 语义）、Decision 8。

---

## 一图收口：三保护 × 三跨度

```
run 时间轴（一次有暂停的 run）：
  ──[节点A]──[节点B]──⟨暂停·checkpoint⟩──[节点C]──[节点D]──▶ 终态
  └─ 游标之前 ─┘                          └────── 游标之后 ──────┘

崩溃点落在哪段        框架给的保护              剩下谁的活
──────────────────────────────────────────────────────────────
暂停前已完成的节点     游标保护（Checkpoint D2）   无——重放被结构性挡住
暂停节点内部           isResuming 守卫             节点自觉分两阶段（fire/poll）
暂停后、崩溃前完成的   无                          幂等键（节点自己的活）
```

一图读法：间隙问题不是一个洞，是三段跨度三种解法。agent4j 早已把前两段做成了框架保证（但没有名字和测试钉死），第三段诚实地留白——E8 把三段全部钉成断言。

---

## 心智模型（KP8 自测判据答案）

**判据 1：checkpoint 落盘与可恢复执行差在哪。**

落盘是入场券：有快照就能恢复。可恢复执行的门槛是「任意点恢复后语义不变」，语义不变需要二选一：重放被挡住（游标/历史里有结果，不再执行），或重放无害（幂等，副作用不重复）。agent4j 用游标挡住了「暂停前」，用 isResuming 挡住了「暂停节点内」，「暂停后」只能靠幂等键——三段里两段是框架的活，一段是节点的活，这正是 stage-6 文章「框架管 Run 级、节点管副作用级」的精确展开。

**判据 2：agent4j 的工具是否全部幂等。**

不是，也没有义务是。框架提供的是原料：`NodeContext.runId()`（幂等键前半）、`WorkflowState.getTrace()`（本次访问的稳定计数源）、`isResuming()`（两阶段守卫）。用不用是节点实现者的责任——E8 场景 4 演示了正确用法（trace 派生 ordinal），场景 2 演示了不用 key 的下场（重复扣款）。`GovernedToolExecutor` 有 `runId` 字段但审计之外未把它透传给工具——诚实边界，不是缺陷声明。

---

## 一手工程事实（四条，全部来自测试断言）

### 事实 1：三保护三跨度——每层保护覆盖的 span 不同

| 保护 | 归属层 | 覆盖跨度 | 测试 |
|------|--------|---------|------|
| 游标保护（cursor past completed nodes） | 框架 | 暂停前全部节点 | scenario1 |
| isResuming 守卫（fire once, poll on resume） | 框架钩子 + 节点自觉 | 暂停节点自身 | scenario3 |
| 幂等键（runId:nodeId:visitOrdinal） | 节点 | 暂停后已执行的节点 | scenario2（无 key 重复）/ scenario4（有 key 去重） |

`HumanApprovalNode` 是 isResuming 守卫的仓库内正典：requestApproval 只在首执行发，resume 只查决定。E8 的 `TwoPhaseChargeNode` 镜像了这套纪律。

### 事实 2：恢复粒度是「上一次暂停」，不是「上一个节点」

`RunManager.executeAndPersist` 只有一个 `store.save` 调用点，且只在 `result.isPaused()` 分支——终态 run 落盘为零。于是崩溃后恢复，重放的是「自上次暂停以来的全部节点」，不止崩溃时正在执行的那个（scenario6 钉死：暂停前节点 A 执行 1 次，暂停后节点 B/C 各被重放到第 2 次）。这是 `agent-platform-modules-map.md` §3 之外的发现：间隙不止在节点内部，是整个非暂停跨度。

### 事实 3：教科书幂等键公式 `runId:nodeId:attempt` 在重试下铸新键

stage-6 文章给的公式带 attempt 计数。E8 scenario5a 证明它在 RetryPolicy 重试下是错的：attempt 1 落地外部调用后失败，重试 attempt 2 铸出新键 `:2`——一次逻辑访问交付两次。修正公式：`runId:nodeId:visitOrdinal`，ordinal 从**持久化 trace** 里数本节点 SUCCESS 记录（scenario5b）。为什么稳定：重试中的访问还没有 SUCCESS 记录（记录只在成功后写），所以同一 visit 的所有 attempt 数到同一个 ordinal；崩溃重启后 trace 来自 checkpoint，pre/post 计数一致（scenario4）。attempt 计数器是进程内易变状态，trace 是持久化状态——幂等键的原料必须和恢复介质同源。

### 事实 4：「杀进程」的正确模拟是两代对象，不是一个

模拟崩溃 = 新 RunManager + 同 checkpoint 目录 + 新工作流实例/审批服务（对齐 `EnterpriseTaskManagerTest.crashRecoveryFromCheckpointFiles` 已验证的模式）。关键细节：新进程的审批决定表是空的，恢复的 run 会在审批节点**再暂停一次**（这是文档语义不是 bug）——测试必须重新 setDecision 才能 resume。忘了这一步，测试挂在不相干的断言上。

---

## 业界对照：Temporal 与 Orleans 把间隙压缩到哪

文献层对照（不进实验），回答大纲留下的「两条路线未对照」：

| 维度 | Temporal（durable execution as a service） | Orleans（virtual actor + event sourcing） | agent4j（checkpoint 快照） |
|------|--------------------------------------------|-------------------------------------------|-----------------------------|
| 持久化粒度 | 每个 workflow 事件（Activity 完成、定时器、信号）都追加进 history | grain 显式 `WriteStateAsync` 或事件持久化 | 仅 PAUSED 时写 checkpoint |
| 恢复方式 | **重放**：workflow 代码从头跑，遇到 history 里已有的结果直接取用，不再发起 | **快照**：激活时从存储加载状态 | 快照：从 checkpoint 重建 Run |
| 间隙大小 | 单个 Activity 的重试窗口（Activity 结果进 history 前的崩溃） | 两次持久化之间的全部方法执行 | 整个非暂停跨度（最大） |
| 副作用责任 | Activity 实现仍需幂等（框架只保证不重复发起，不保证 Activity 内部重试幂等） | grain 方法 at-least-once，外部调用需幂等 | 幂等键是节点的活（本文主题） |

三条结论：

1. **durable execution 的本质变量是持久化频率，不是持久化介质。** Temporal 把落盘点推到「每个外部交互完成时」，间隙被结构性压缩到 Activity 重试窗口；快照派的间隙 = 快照间隔。agent4j 的「仅暂停时快照」是这条谱系上最粗的一档——对教学 v1 是诚实的选择，但天花板明确：无暂停的长 run 崩溃 = 整 run 从头重放。
2. **事件溯源的「重放」和 agent4j 的「重放」语义不同。** Temporal 重放 workflow 逻辑但被 history 挡住不重发 Activity（相当于把游标保护做到每个调用点）；agent4j 重放是「真的再执行一遍节点代码」。前者副作用零重复（框架级），后者靠节点自觉（幂等键）。Temporal 的 Activity 幂等提醒（「activity 可能被重试，实现请幂等」）和 agent4j 的边界声明完全同构——框架永远不能替节点知道副作用语义，这条在两条业界路线里都没变。
3. **Orleans 与 agent4j 同构到可以互为镜子。** grain 钝化/激活 ≈ checkpoint/恢复，方法 at-least-once ≈ 节点重放，显式 WriteStateAsync ≈ 主动触发暂停存盘。读 Orleans 的文献时最有用的视角：它是「快照派做到工业强度」长什么样——快照频率从「暂停时」变成「每次状态变更后由开发者显式声明」。

---

## 决策与边界

**落定的决策**：幂等键公式从 `runId:nodeId:attempt` 修正为 `runId:nodeId:visitOrdinal`（trace 派生）。这不是推翻 stage-6 文章的框架，是把文章里「attempt」这个易变词换成持久化原料——文章的分层结论（框架管 Run 级、节点管副作用级）不变。

**诚实边界（v1）**：

- 「崩溃」是两代对象模拟（新 RunManager + 同目录），不是真 kill -9——kill -9 版本需要独立进程 + 信号控制，是 OS 层实验，超出本轮
- 副作用服务器是测试内 recorder，不是真外部系统
- 单 JVM、无并发；两代 RunManager 不会同时活在断言里
- Temporal/Orleans 对照基于公开文档与文献，未跑过两个系统
- scenario6 的 B/C 重复是**断言的事实**不是修好的行为——「每个节点后都 checkpoint」是明显的 v2 方向（代价：写盘频率 × 节点数），本轮不动生产代码

---

## 三段式收口

- **做对了什么**：把 modules-map §3 的一句话风险（「间隙问题未落」）变成七个可重跑的断言；发现恢复粒度真相比笔记记载的更粗（上一次暂停，不是上一个节点）；修正了教科书幂等键公式的 attempt 陷阱；三层保护第一次有了名字和归属（游标/isResuming/幂等键）。
- **缺什么**：真 kill -9 实验（OS 层）；每节点 checkpoint 的 v2（写盘成本权衡未做）；Temporal 路线的「history 挡重发」在 agent4j 里没有对应物（那是一次架构升级，不是补丁）。
- **怎么做**：短期内什么都不用做——三层保护已就位且被测试钉死，业务节点照 scenario4 的公式写即可。若做 v2：先测「每节点 checkpoint」的写盘成本（FileCheckpointStore 全量 JSON 序列化，每次节点完成都写 = trace 增长 × 写放大），再决定是否值得。

---

## 新思考题

1. Temporal 的 history 重放挡住了 Activity 重发，为什么 Activity 实现仍然被要求幂等？（提示：Activity 的 retry 计时器到点 vs workflow 重放到达该调用点，两个时刻谁先谁后）
2. agent4j 若做「每节点完成后 checkpoint」，scenario6 的 B/C 重复消失，但引入了什么新成本？什么形态的 run 会因此变慢最多？（提示：trace 长度 × 序列化成本 × 节点数）
3. `isResuming` 只对「暂停节点本身」为 true。若一个节点在暂停后、崩溃前执行过，恢复重放它时 isResuming=false——为什么框架无法在这里提供更细的信号？（提示：框架知道「这个节点执行过」的唯一途径是 trace，而 trace 里没有「执行过但没写回」的记录）
