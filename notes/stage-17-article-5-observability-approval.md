# Coding Agent 为什么必须具备可观测性和审批

> 配套蓝图：[architecture-stage-17.md](architecture-stage-17.md) D8（审批四档）· 对应实现：`agent-coding/CodingAgentFactory.java`（治理链装配）+ Stage 9 的 `GovernedToolExecutor`/`ToolPolicy`/`AuditLogger`
> 上一篇：[stage-17-article-4-fix-loop.md](stage-17-article-4-fix-loop.md)
> 状态：✅ Stage 17 已完成

---

## 1. 我今天要解决什么问题

前四篇都在讲「怎么让 Coding Agent 正确地改代码」。这一篇讲「怎么让人**相信**它改得对、以及它**没乱来**」。

两个问题：

```text
1. 可观测性：Agent 到底干了什么？凭什么信它？（工具调用留痕）
2. 审批：哪些动作该让人点头？（权限分档）
```

对 Coding Agent，这两个问题比别的场景更致命——因为它的副作用**直击真实文件系统**：写错一个文件、跑错一条命令，损失是真实的。

---

## 2. 为什么会有这个需求

回到「输出即变更」：Coding Agent 的输出是**变更**，而变更有两个特质：

1. **不可逆**：文件被覆盖后，旧内容就没了（除非有回滚机制）。
2. **难复核**：一次任务可能改 5 个文件、跑 3 条命令，事后你从结果里看不出中间发生了什么。

所以「可观测性」不是锦上添花，是 Coding Agent 的**底线**——没有留痕，你甚至不知道它刚才是不是悄悄读了你 `.env` 里的密钥（虽然我们的 deny 列表会拦）。而「审批」是留痕之上的**决策闸门**——不是所有动作都自动放行，高危动作必须让人点头。

---

## 3. 它解决了什么问题

- **可观测性**解决「**怎么证明 Agent 没乱来**」：每个工具调用（执行了、被批准了、被拒绝了）都留一条审计事件，事后可逐条回放。
- **审批**解决「**怎么防止高危动作被自动执行**」：跑命令、落盘这类有真实副作用的动作，必须先过审批。

但审批有一个隐藏的敌人：**审批疲劳**。

```text
如果什么都审批 -> 人会机械地点"同意" -> 审批形同虚设
如果什么都不审批 -> 高危动作被自动执行 -> 出了事故没人拦
```

所以审批设计的关键不是「要不要审批」，而是「**审批档位跟着什么走**」。

---

## 4. 核心抽象和架构

### 4.1 治理链复用（零改动挂车）

Stage 17 的可观测性和审批，**一行都没新写**，直接复用 Stage 9 的治理四件套：

```text
GovernedToolExecutor   工具执行治理器（权限检查 + 审批 + 审计的统一入口）
ToolPolicy             工具名 -> 权限档位的映射
ToolApprovalService    审批服务（auto / autoReject / console / callback 四模式）
AuditLogger            审计日志
```

装配时挂车（`CodingAgentFactory.create`）：

```java
GovernedToolExecutor executor = GovernedToolExecutor.builder(new DefaultToolExecutor(registry))
        .permissionChecker(new PermissionChecker(defaultPolicy()))
        .approvalService(approvalService)   // 示例用 autoApprove，生产用 console
        .auditLogger(auditLogger)
        .build();
```

这就是「同一 Runtime」叙事在治理维度上的兑现：Stage 9 造的治理链，Stage 17 拿来即用，一个枚举值都不用加。

### 4.2 审批四档：跟着真副作用走，不是跟着工具名走（D8）

`CodingAgentFactory.defaultPolicy()` 的分档逻辑：

```text
read_file      AUTO             零副作用
list_files     AUTO             零副作用
write_file     AUTO             暂存区动作，磁盘零变化（真副作用在 apply）
run_command    REQUIRES_APPROVAL 进程执行，副作用即时
run_tests      REQUIRES_APPROVAL 同上（v1 从严）

apply（落盘）  人工 review diff   唯一真写盘点，diff 人审后落盘
```

**原则：无盘上副作用的动作不扰民，有副作用的动作必过闸。**

这句话的分量在于：`write_file` 是 AUTO 的——不是因为它不重要，而是因为它**没有真副作用**（只写暂存区，不落盘）。真正的写盘动作是 `apply`，而 `apply` 不是工具，是 `CodingSession.approveAndApply()` 这个人闸（人看 diff，批准才落盘）。

所以审批档位的本质是：**把人有穷的注意力预算，花在真正不可逆的动作上。**

### 4.3 两道闸、两个粒度

```text
工具粒度（Stage 9）：run_command 设 REQUIRES_APPROVAL —— 管"这个工具能不能调"
参数粒度（Stage 17）：argv 白名单 —— 管"这条命令合不合法"

审批过后命令还要过白名单；白名单放行还要受超时/截断/锚定。
纵深防御：任何单道闸失守都不是事故。
```

---

## 5. 一次完整数据流

`CodingAgentExample` 的真实审计流水（7 条事件）：

```text
[EXECUTED]  list_files  {"max_depth":0}
[EXECUTED]  read_file   {"path":"Calculator.java"}
[EXECUTED]  write_file  {"path":"Calculator.java",...}   <- AUTO，直接执行
[APPROVED]  run_tests   {}                              <- REQUIRES_APPROVAL，过审批
[EXECUTED]  run_tests   {}
[EXECUTED]  write_file  {...}                           <- 修复，AUTO
[APPROVED]  run_tests   {}                              <- 再测，再审批
[EXECUTED]  run_tests   {}
```

注意两条规律：

1. `write_file` 全是 `EXECUTED`（AUTO，因为不落盘）；
2. `run_tests` 每次都是「`APPROVED` + `EXECUTED`」两条（先审批后执行）。

而「拒绝」也是一等事件（`denied is intelligence`，Stage 9 D6）：白名单拒绝、deny 列表拒绝，都会留 `DENIED` 审计——**拒绝不是噪音，是情报**。

---

## 6. 最小代码或实验

权限分档的最小验证（`CodingAgentFactoryTest.permissionTiersFollowSideEffects`）：

```java
ToolPolicy policy = CodingAgentFactory.defaultPolicy();
assertEquals(AUTO,             policy.permissionFor("read_file"));
assertEquals(AUTO,             policy.permissionFor("write_file"));  // 暂存无真副作用
assertEquals(REQUIRES_APPROVAL, policy.permissionFor("run_command"));
assertEquals(REQUIRES_APPROVAL, policy.permissionFor("run_tests"));
```

全链治理剧本（`fullLoopThroughGovernance`）验证「每个工具调用都过链」：

```java
assertTrue(audit.getAll().size() >= 3, "every tool call went through the chain");
assertTrue(audit.getAll().stream().anyMatch(e -> "run_tests".equals(e.toolName())));
```

---

## 7. 常见误区

1. **「可观测性 = 打日志」** —— 不是。日志是给人看的技术细节，审计是**可追责的事实链**：谁（Agent）、何时、用什么参数、调了什么工具、结果如何、有没有被批准/拒绝。审计事件是结构化的事实，不是散落的日志行。
2. **「write_file 应该 REQUIRES_APPROVAL」** —— 恰恰相反。它只写暂存区，没有真副作用，AUTO 是对的。真写盘是 `apply`（人闸）。如果把 write_file 也设审批，就是审批疲劳的经典错误：该批的是落盘，不是暂存。
3. **「审批越严越好」** —— 审批疲劳会让「严」变成「形同虚设」。档位设计的目标是**把有限的注意力花在刀刃上**，而不是什么都批。
4. **「拒绝了就完事了」** —— 拒绝要留痕。一条 `DENIED` 事件是「模型试图越界」的情报，积累起来能发现模型的系统性坏习惯。

---

## 8. 和相邻概念的区别

**工具审批（Stage 9）vs Patch 人闸（Stage 17）**：

```text
工具审批（run_command REQUIRES_APPROVAL）
  管"这个动作能不能做"（进程执行这个动作本身）
  在工具调用链路上，一条一条批

Patch 人闸（reviewPatch + approveAndApply）
  管"这批变更能不能落"（diff 人审）
  在变更生命周期上，整批审
```

这是 Stage 15「工具审批 vs 任务审批」双层在编码域的形态：前者保「这个动作能做」，后者保「这批变更能落」。两者互补，一个管过程，一个管结果。

---

## 9. 我的设计判断

最重的一条：**审批档位跟着真副作用走，不跟着工具名走。**

这是反直觉但正确的一条。直觉会告诉你「写文件危险，要审批」，但 `write_file` 不写盘（写暂存区），真正危险的是 `apply`。如果按工具名分档，你会把 `write_file` 设成审批，然后人天天批「暂存一个文件」这种无副作用的动作，最后对真正的落盘也麻木了。按副作用分档，审批才能精准命中「不可逆」这个靶心。

其次是「**审批疲劳是真实敌人**」这个认知。治理系统的失效往往不是「没有审批」，而是「审批太多了，人机械点同意」。好的治理设计，是把人的注意力当成稀缺资源来分配。

---

## 10. 面试表达

> 「Coding Agent 的可观测性和审批，我直接复用了 Stage 9 的治理链，一行没新写。关键设计是审批四档：read/list/write 是 AUTO，run_command/run_tests 是 REQUIRES_APPROVAL，真正的落盘 apply 是人审 diff 的闸门。为什么 write_file 是 AUTO？因为它只写暂存区、不落盘，没有真副作用——审批档位跟着真副作用走，不跟着工具名走。背后的认知是：审批疲劳是真实敌人，什么都批等于什么都不批，要把人有穷的注意力预算花在真正不可逆的动作上。同时每一个工具调用——包括拒绝——都留一条审计事件，拒绝是情报不是噪音。」

---

## 11. 下一篇连接什么

下一篇把「权限」这个主题从「工具审批」下沉到「文件和命令」两个更细的粒度：**Coding Agent 的文件权限和命令权限设计**——路径逃逸、symlink 逃逸、deny 列表、无 shell 的第一道防御。

→ [stage-17-article-6-file-and-command-permission.md](stage-17-article-6-file-and-command-permission.md)
