# Coding Agent 的本质是一个受控的软件工程循环

> 配套蓝图：[architecture-stage-17.md](architecture-stage-17.md) §1（核心命题）· 对应实现：`agent-coding/` · 全剧本：[CodingAgentExample.java](../examples/src/main/java/io/github/qwzhang01/agent/examples/CodingAgentExample.java)
> 状态：✅ Stage 17 已完成（M17.1~M17.5，agent-coding 138 测试，全仓 1023 全绿）
> 这是 Stage 17 系列的第 1 篇，也是三 Profile 递进叙事的收官篇。

---

## 1. 我今天要解决什么问题

前两个 Profile 做完了：Stage 15 让 Agent 能进企业（租户、权限、审批），Stage 16 让 Agent 能演戏（角色、世界、关系）。现在要回答第三个、也是最能暴露 Runtime 边界的问题：

> 当 Agent 的输出不是「一句话」，而是「对文件系统的一批修改」时，Chat Loop 还成立吗？

如果成立，那 Coding Agent 只是「一个会调 `write_file` 工具的普通 Agent」；如果不成立，我们就得说清楚它到底缺了什么。这篇文章的结论是：**不成立**——Coding Agent 缺的不是新工具，是一整个「变更层」。

---

## 2. 为什么会有这个认知冲突

Stage 1-16 造好的 Runtime 有一个从未被挑战的隐含共识：

```text
Agent 的产出是给人看的文本。
```

`agent.run("...")` 出来的是答案（企业）、台词（游戏）、摘要（频道）——**文本没有副作用，给出去就结束了**。这句话听起来无害，但它悄悄冻结了五个假设。把它们逐个推到 Coding 场景，全部破裂：

```text
Chat Loop 的五个隐含假设，在 Coding 场景全部破裂：

1. 输出假设 —— 假设输出是"给人看的文本"（答案说完即止）
   Coding Agent 的输出是"要落盘的变更"：文件改了什么必须可审查（diff）、
   可拒绝（审批）、可撤销（整体丢弃）——一句"我把除法方法加好了"不是交付，
   一个可审查的 Patch 才是交付

2. 副作用假设 —— 假设工具调用要么无副作用（查询/检索），要么副作用受控
   （游戏数值限幅 / 企业审计留痕）
   Coding Agent 要写文件、跑构建命令——副作用直接作用在真实文件系统上，
   且命令面近乎无限：rm -rf、curl 外发、覆盖 .git、读 .env——每一个都是真实事故

3. 验证假设 —— 假设产出对不对由人判断（用户看答案 / 管理员审记忆 / 主管批退款）
   Coding Agent 的产出可以机器验证：编译过没过、测试绿没绿——裁判从人换成了
   工具，奖励信号内生（测试结果就是 reward），这是三个场景中唯一自带客观裁判的

4. 循环假设 —— 假设一次 run 线性走到答案（ReAct 有 maxSteps 兜底但目标是一次收敛）
   Coding Agent 的常态是迭代收敛环：改 → 测 → 败 → 修 → 再测——
   "失败后修复"不是异常分支，是主流程；而环必须有界（修不好还一直修
   = 无限烧 token 的真实事故）

5. 上下文假设 —— 假设上下文 = 对话消息（Stage 8 后 + 记忆检索）
   Coding Agent 的上下文还有工作区：文件树、文件内容、命令输出——
   单文件可能几千行、一次构建输出可能几 MB，读什么/读多少/输出截断到
   哪里，都是上下文预算的真实战场
```

五个假设，一个共同病灶：**文本假设**。Coding Agent 的输出、副作用、验证、循环、上下文，全都指向同一个新主语——**变更**。

---

## 3. 它解决了什么问题

承认「输出即变更」之后，Coding Agent 的设计目标就变了：不是「造一个会写代码的 ChatBot」，而是「造一个**受控的软件工程循环**」。

「受控」三个字是关键，它对应五个答案：

```text
工作区即边界   -> Workspace 路径白名单：读也是特权（读 .git/.env 会被拒）
变更即补丁     -> 写进暂存区不落盘，审批后 apply：写盘是特权，Patch 是申请单
命令即白名单客人 -> 无 shell 执行 + argv 白名单：注入语法无处生根
测试即裁判     -> TestResult 机器验证：裁判命令不由被裁判者指定
修复环即有界收敛 -> 边界在引擎（计数 [LIMIT]）、节奏在模型（失败观察引导）
```

这五句话，就是 Stage 17 的全部骨架。每一句后面都站着一组真实代码（`Workspace` / `PatchStore` / `CommandWhitelist` / `RunTestsTool` / `CodingSession`），本文只给总纲，后六篇逐一拆。

---

## 4. 核心抽象和架构

站在最顶层，Coding Agent = **Chat Loop（机制层，零改动） + 变更层（领域层，新增）**：

```text
        Chat Loop（agent-core，Stage 2，一行不改）
        ┌──────────────────────────────────────────┐
        │  ReActAgentLoop + GovernedToolExecutor   │
        └──────────────────────────────────────────┘
                           │ 调工具
        ┌──────────────────────────────────────────┐
        │  变更层（agent-coding，Stage 17 新增）      │
        │                                          │
        │  workspace/  Workspace / WorkspacePolicy  │  ← 工作区即边界
        │  patch/      FileChange / Patch /         │  ← 变更即补丁
        │              PatchStore / PatchSummarizer │
        │  exec/       CommandWhitelist /           │  ← 命令即白名单客人
        │              CommandRunner / RunTestsTool │  ← 测试即裁判
        │  session/    CodingSession / FixLoopPolicy│  ← 修复环即有界收敛
        └──────────────────────────────────────────┘
```

注意这张图的方向：**不是**「Chat Loop 不够用了，得重写」，**而是**「Chat Loop 完全够用，缺的是它脚下那一层」。这正是三 Profile 叙事的核心证据：机制层一行不改，三个领域各叠一层领域语义，就支撑了三种完全不同的场景。

---

## 5. 一次完整数据流

用 `CodingAgentExample` 的真实剧本（`check.sh` 是真脚本，`grep` 真实工作区）：

```text
用户："给 Calculator 加一个 divide 方法，带除零守卫，用测试验证。"

1. list_files + read_file        -> 模型读代码（读是特权，路径过白名单）
2. write_file(Calculator.java)   -> 产出 FileChange 进 PatchStore 暂存区
                                    —— 磁盘此刻零变化（字节级断言锁定）
3. run_tests                     -> 执行前 materialize 写盘，让裁判看到暂存变更
                                    -> check.sh 发现没有 guard -> 红（失败）
4. write_file(修复版)             -> 同路径重写 = 替换暂存条目（不是叠加）
5. run_tests                     -> 再次 materialize -> check.sh 绿（通过）
                                    -> Patch: DRAFT -> VALIDATED
6. reviewPatch()                 -> 人审 unified diff（+2 -1 行）
7. approveAndApply()             -> 唯一真落盘点 -> Patch: VALIDATED -> APPLIED
8. 变更摘要                      -> "1 file MODIFY, +2 -1 lines, tests green"
```

整个流程 7 个工具调用，审计链全程留痕（`EXECUTED` / `APPROVED` 状态可见）。这是「软件工程循环」而不是「聊天」的最直观证据：**中间产物是一个可 diff、可拒绝、可撤销的 Patch，不是一段话。**

---

## 6. 最小代码或实验

最核心的「输出即变更」实验，就一句话——写盘和暂存是两件事：

```java
// WriteFileTool 的确认文本（模型被明确告知：还没落盘）
"staged: Calculator.java (kind=MODIFY). Nothing written to disk yet. 2 file(s) staged."

// 磁盘此刻还是旧内容 —— 测试断言（PatchStoreTest.stagingNeverWrites）：
// 写 N 次（含删除与重写）后，磁盘逐字节 Arrays.equals 与写前完全一致
```

验证「暂存不落盘」这个不变量，是 Stage 17 所有测试里优先级最高的一条（`stagingNeverWrites` 做了 5 次 stage 的磁盘字节级断言）。因为它是后面一切（审批、漂移检测、修复环回滚）的地基。

---

## 7. 常见误区

1. **「Coding Agent = 会调 write_file 工具的 Agent」** —— 最危险的误区。工具只是外壳，真正的差异是**变更的生命周期**：暂存 → 审批 → 落盘 → 可回滚。没有这个生命周期，`write_file` 就是个裸写盘的灾难按钮。
2. **「沙箱 = 命令白名单」** —— 白名单只是第二道闸。第一道是「根本没有 shell」（注入语法无处生根），第二道是白名单，第三道是执行器的超时/截断/锚定。任何一道失守都不是事故。
3. **「修复环 = maxSteps」** —— maxSteps 数的是所有步数，分不清「健康探索」（读 5 个文件）和「死循环修复」（改-测-败 ×10）。修复环要的是领域语义的闸：只数失败轮数。
4. **「测试通过就可以交付」** —— 通过只说明「机器裁判说行」，离「落盘」还隔着一个人审 diff。机器验证了正确性，人审验证了意图。

---

## 8. 和相邻概念的区别

三 Profile 递进叙事（本系列收官的核心对比）：

```text
Stage 15 让 Agent 能进企业 -> 第一个领域 Profile：归属层（谁在问 / 哪个租户 / 花谁的钱）
Stage 16 让 Agent 能演戏   -> 第二个领域 Profile：世界层（角色有灵魂 / 说话有后果 / 一局有历史）
Stage 17 让 Agent 能写代码 -> 第三个领域 Profile：变更层（输出即变更 / 变更即补丁 / 验证即测试）

三种领域缺失，同一 Runtime 兜底：
  企业缺归属层   Tenant / User / CostLedger
  游戏缺世界层   WorldState / Relationship / GameEvent
  编码缺变更层   Workspace / Patch / CommandWhitelist
```

三次落地，存量模块的改动依次是：Stage 15 两处枚举加法、Stage 16 零、Stage 17 零。**「同一 Runtime 三类场景」从一句宣言，变成了可复核的证据链。**

---

## 9. 我的设计判断

最重的一条判断：**Coding Agent 的正确答案不是「更强的模型」，是「更硬的纪律」。**

很多人以为 Coding Agent 的核心竞争力是模型写代码的能力。但真正的工程难度在「受控」二字：怎么让一个非确定性的模型，对真实的文件系统做出可审查、可拒绝、可撤销的变更。模型写得好不好是能力问题，写错了能不能安全收场是架构问题——后者才是框架该解决的，也是本文五个假设破裂后所有设计的出发点。

其次是「**装配时才显形的架构缺口**」（M17.5 实录）：蓝图 T3 时序图里 `run_tests` 似乎能看到暂存变更，但 M17.2 的暂存纪律是「不落盘」——裁判永远看不到暂存区的修改，修复环是空转的。这个矛盾被时序图掩盖，直到写端到端剧本时才显形。教训：**设计图会骗你，装配会还你真相**。

---

## 10. 面试表达

> 「我实现 Coding Agent 时的核心判断是：它的本质不是一个更强的聊天机器人，而是一个**受控的软件工程循环**。Chat Loop 的『输出是文本』这个隐含假设，在编码场景五个维度全部破裂——输出变成了要落盘的变更、副作用直击文件系统、验证从人换成了测试、循环从线性变成了有界的迭代收敛、上下文从消息扩展到了工作区。所以我没重写 Runtime，而是在机制层之上叠了一个**变更层**：工作区即边界、变更即补丁、命令即白名单客人、测试即裁判、修复环即有界收敛。最终三个 Profile——企业、游戏、编码——复用同一个 Runtime，存量模块近乎零改动，这就是『用一套 Runtime 支撑三类场景』的证据。」

---

## 11. 下一篇连接什么

下一篇拆「变更层」的三个核心概念：**Workspace、Patch、Command 如何建模**——它们分别对应「状态的家在哪」「变更的粒度是什么」「命令的边界是什么」三个问题，也是三组最容易和 Stage 16/8 概念搞混的边界。

→ [stage-17-article-2-workspace-patch-command.md](stage-17-article-2-workspace-patch-command.md)
