# 从需求到 Patch：Coding Agent 的状态机

> 配套蓝图：[architecture-stage-17.md](architecture-stage-17.md) §3（Patch 状态机）+ D1 · 对应实现：`agent-coding/patch/Patch.java`、`PatchStore.java`
> 上一篇：[stage-17-article-2-workspace-patch-command.md](stage-17-article-2-workspace-patch-command.md)
> 状态：✅ Stage 17 已完成

---

## 1. 我今天要解决什么问题

上一篇讲了 `Patch` 是「变更的事务单位」。但一个 Patch 从「模型想改」到「真的落盘」，中间要经过什么？谁在什么时机、把它的状态往前推一步？

这就是本篇的问题：**从需求到 Patch，一条状态机的每一步由谁触发，又为什么不能跳过。**

---

## 2. 为什么需要状态机

因为「变更」不是一个瞬间动作，而是一段**跨越信任边界的旅程**：

```text
模型说"我想改成这样"  ——  模型不可信，这只是提案
  ↓ 中间要经过什么？
文件真的被改了       ——  不可逆，且可能错了
```

如果「想改」和「改了」之间没有中间状态，那 Coding Agent 的每次修改都是**不可逆的赌博**。状态机的本质，就是把「模型不可信的提案」到「人确认的落盘」之间，切成可暂停、可检查、可拒绝的若干站。

而且这个状态机是**双层**的——这是本篇最重要的观察：

```text
对话流：AgentState（Stage 2 已有）—— 模型在 ReAct 里走到哪一步了
变更流：Patch（Stage 17 新增）    —— 这次变更走到了哪一步
```

两层状态各管各的：`AgentState` 管「模型这一轮对话还活着吗」，`Patch` 管「这批文件变更还活着吗」。一个对话可能产出多个 Patch，一个 Patch 的生命周期也可能横跨多次 `run_tests`。

---

## 3. 它解决了什么问题

- 解决「**写盘是特权，不是默认动作**」：状态机把「写盘」单独隔成 `APPLIED` 这一站，之前的所有站（DRAFT / VALIDATED）磁盘都没变。
- 解决「**提案可以被拒绝、被丢弃、被漂移检测拦截**」：每个终态（APPLIED / REJECTED / DISCARDED）都是一个可审计的出口。
- 解决「**测试通过 ≠ 落盘**」：`VALIDATED` 和 `APPLIED` 是两个状态，中间隔着一个人审 diff。

---

## 4. 核心抽象和架构

### 4.1 Patch 状态机

```text
                 ┌──────────┐
    stage() ───▶ │  DRAFT   │◀─── 模型反复 write_file（修复环在这里转）
                 └────┬─────┘
                      │ markValidated()（测试通过自动触发）
                      ▼
                 ┌────────────┐
                 │ VALIDATED  │◀── 冻结：VALIDATED 后不能再 stage（先 discard）
                 └────┬───────┘
          ┌───────────┼─────────────┐
          │           │             │
    approveAndApply() rejectPatch()  discardPatch()
          │           │             │
          ▼           ▼             ▼
     ┌────────┐  ┌─────────┐   ┌───────────┐
     │APPLIED │  │REJECTED │   │DISCARDED  │   （三个终态）
     └────────┘  └─────────┘   └───────────┘

   另：DRAFT 也可以直达 APPLIED —— 人闸可以批准一个没跑测试的 patch，
       状态机不立法流程（流程归 CodingSession），它只立法状态。
```

关键点：

1. **`markValidated()` 由「测试通过」自动触发**（不是模型或人手动调）。这兑现了蓝图 D4 的「通过即出环」——测试通过这件事本身，把 Patch 从 DRAFT 推到 VALIDATED。
2. **`VALIDATED` 是冻结态**：之后不能再 `stage`（`IllegalArgumentException`：先 discard 才能重开）。理由：测试已经绿了，此时再改就应该是一个**新任务**，而不是偷偷改已通过审的内容。
3. **三个终态各有归属**：`APPLIED` 人批了、`REJECTED` 人拒了、`DISCARDED` 丢弃了（含修复环预算耗尽后的丢弃——但注意下一篇会讲，[LIMIT] 时是「保留证据 + 显式丢弃」，不自动销毁）。

### 4.2 不可变 + wither

`Patch` 是 record，状态迁移不原地改，而是生成新实例：

```java
public Patch withStatus(PatchStatus newStatus) { ... }  // 旧实例留作审计事实
```

这和 Stage 14 `RewardResult.applyTo` 是同一个纪律：**迁移产生新实例，旧例留档**。好处是你可以拿到「某个时刻的 Patch 长什么样」作为事实，而不是一个被改来改去的可变对象。

---

## 5. 一次完整数据流

从需求到落盘，双层状态机如何推进（对应 `CodingAgentExample` 剧本）：

```text
需求："加一个 divide 方法，带除零守卫"
  │
  ├─ AgentState: RUNNING，ReAct 第 1 步 list_files
  ├─ AgentState: 第 2 步 read_file
  ├─ 第 3 步 write_file(v1) ──▶ Patch: DRAFT（暂存，磁盘零变化）
  ├─ 第 4 步 run_tests ──▶ materialize 写盘 ──▶ 裁判红
  │                          （Patch 仍是 DRAFT，失败不推进状态）
  ├─ 第 5 步 write_file(v2) ──▶ 同路径替换（Patch 仍 DRAFT）
  ├─ 第 6 步 run_tests ──▶ 裁判绿 ──▶ markValidated()
  │                          ──▶ Patch: DRAFT ──▶ VALIDATED（冻结）
  │
  ├─（模型收尾，AgentState 走向 DONE，产出摘要）
  │
  └─ 人闸：reviewPatch() 看 diff ──▶ approveAndApply()
                                      ──▶ Patch: VALIDATED ──▶ APPLIED（唯一落盘点）
```

注意两个「不推进」的细节：

- 测试**失败**不推进 Patch 状态（Patch 停在 DRAFT，等着被修复）；
- 测试**通过**才推进（DRAFT → VALIDATED）。

这就是「测试即裁判」在状态机上的精确含义：**只有裁判说行，状态才往前走**。

---

## 6. 最小代码或实验

三个关键状态迁移，各一句测试锁定：

```java
// DRAFT -> VALIDATED（通过即出环，CodingSessionTest.passingValidatesPatch）
assertEquals(VALIDATED, session.activePatch().get().status());

// VALIDATED 冻结（PatchStoreTest.validationFreezesStaging）
assertThrows(IllegalArgumentException.class, () -> store.stage("Other.java", "x"));
// 消息含 "discard" —— 提示先 discard 才能重开

// DRAFT 直达 APPLIED（PatchStoreTest.applyFromDraftAllowed）
// 人闸可批准未测试的 patch，状态机不立法流程
```

还有一个值得注意的细节：`VALIDATED` 后想改，必须先 `discardPatch()` 再重新 `stage`——这强制了「已通过审的内容要改，就是一个新任务」的纪律。

---

## 7. 常见误区

1. **「状态机 = 流程图，应该用 Workflow 引擎」** —— 恰恰相反。这个状态机是**领域状态的迁移**（Patch 的五个状态），不是**控制流**（谁先谁后）。控制流在 ReAct 循环里由模型驱动；领域状态迁移由事件（测试通过、人批、人拒）触发。两者层不同，别把 Patch 状态机塞进 GraphRuntime。
2. **「测试通过就 APPLIED」** —— 通过只到 `VALIDATED`。`APPLIED` 是人审 diff 之后的动作。机器验证了正确性，人验证了意图，这是两站。
3. **「DRAFT 直接 APPLIED 是 bug」** —— 不是。它是「人闸可以批准未测试的 patch」这个合法路径（比如改的是文档，没测试可跑）。状态机不立法流程，它只保证状态迁移合法。
4. **「Patch 是不可变对象，所以修复环里每次改动都新建一个」** —— 修复环里改的是**暂存区里的 FileChange**（同路径替换），Patch 对象本身用 `withChanges` 刷新，不产生一堆游离 Patch。终态（DISCARDED/REJECTED）才产生一个新的留档实例。

---

## 8. 和相邻概念的区别

**两层状态机的分工**（本篇核心）：

```text
AgentState（对话流，Stage 2）     Patch（变更流，Stage 17）
-----------------------------     ------------------------------
管"模型还活着吗"                  管"这批变更还活着吗"
RUNNING -> DONE / ERROR /         DRAFT -> VALIDATED ->
  MAX_STEPS_EXCEEDED                APPLIED / REJECTED / DISCARDED
一个对话一个                       一个任务一个（一个对话可能多个 Patch）
maxSteps 兜底                      FixLoopPolicy 兜底（下一篇）
```

别用 `AgentState` 去表达「变更到哪了」，也别的 `Patch` 去表达「对话到哪了」——两层各司其职，组合起来才是完整的 Coding Agent。

---

## 9. 我的设计判断

最重的一条：**状态机的每一步都由「谁触发」来定义，而不是由「顺序」来定义。**

`DRAFT → VALIDATED` 的触发者是「测试通过」这个**事件**，`VALIDATED → APPLIED` 的触发者是「人批准」这个**动作**，`DRAFT → REJECTED` 的触发者是「人拒绝」。把触发者想清楚，状态机就不会变成一张「看起来对、用起来错」的流程图——因为真实世界里，是事件和动作在推状态，不是顺序。

其次是「**冻结态**」这个设计：`VALIDATED` 后禁止 stage。它值钱的地方在于，它阻止了 Coding Agent 最隐蔽的坏习惯——测试已经绿了，模型还在偷偷改文件。冻结态把「已通过审」和「还能改」之间划了一条硬线。

---

## 10. 面试表达

> 「Coding Agent 的状态机是双层的：对话流在 AgentState，变更流在 Patch。Patch 有五个状态——DRAFT 是模型反复写补丁的暂存态，测试通过这个事件把它推到 VALIDATED（通过即出环），人审 diff 后批准才到 APPLIED（这是唯一落盘点），人拒到 REJECTED，丢弃到 DISCARDED。关键设计有两条：一是 VALIDATED 是冻结态，通过审的内容不能再偷偷改；二是 DRAFT 可以直达 APPLIED，因为状态机不立法流程，它只保证状态迁移合法。」

---

## 11. 下一篇连接什么

下一篇聚焦 `DRAFT → VALIDATED` 这一步的触发者：**测试失败后 Agent 如何进入修复循环**——测试即裁判为什么「裁判不能由被裁判者指定」，修复环的「边界在引擎、节奏在模型」怎么落地，以及 M17.5 才显形的 materialize 机制。

→ [stage-17-article-4-fix-loop.md](stage-17-article-4-fix-loop.md)
