# 企业 Agent 的权限、审批和审计模型

> 配套蓝图：[architecture-stage-15.md](architecture-stage-15.md) D4 / D6 / M15.3 · 业务场景：[stage-15-business-scenario.md](stage-15-business-scenario.md) §4.3 / §5.6
> 对应实现：`govern/RoleBasedPermissionChecker` · `EnterpriseAuditTrail` · `EnterpriseAuditEvent` · `task/` 任务审批
> 全剧本：[EnterpriseAssistantExample.java](../examples/src/main/java/io/github/qwzhang01/agent/examples/EnterpriseAssistantExample.java) T3–T4
> 这是 Stage 15 系列的第 4 篇。上一篇把工具接到了业务系统；本篇回答谁能调、谁放行、事后如何证明。

---

## 1. 我今天要解决什么问题

工具能碰到订单和退款之后，企业立刻会问三句很难用 prompt 回答的话：

```text
这个员工能不能调这个工具？
这次敏感操作有没有经过正确的人？
出了事，能不能按租户、按人、按工具把流水翻出来？
```

Stage 9 已经有治理四件套：`ToolPolicy`、`PermissionChecker`、`ToolApprovalService`、`AuditLogger`。它们按工具名工作，不知道客服和主管，也不知道 acme 和 globex。今天要解决的问题是：

> 如何在不改治理链的前提下，把角色、双层审批和归属审计接进去？

---

## 2. 为什么会有这个概念

`PermissionChecker` 从 Stage 9 起就不是 final。它的 javadoc 原文写着：future stages can add context-aware logic（例如 user role X can call tool Y）。`ToolPermission` 一侧也写过：intentionally NOT fine-grained RBAC，RBAC 留给 Stage 15 Enterprise Profile。M15.3 是在兑现预留扩展点，不是临时打补丁。

与此同时，审批在企业里天然是两层，上一篇系列已经立过命题，这里落到治理模型：

```text
工具审批  这次调用能不能执行     loop 不停
任务审批  这单业务能不能继续     Run 暂停
```

审计也缺一层。Stage 9 的 `AuditEvent` 是通用治理事实（runId / toolName / status / result），不知道归属。企业问的是「这个月 acme 有哪些拒绝」「alice 让 Agent 干过什么」。如果去改 `AuditEvent` record，会牵动所有存量消费者，也会让 Runtime 被迫认识 Tenant。正确做法是组合，不修改。

---

## 3. 它解决了什么问题

治理接线做了三件事，全部挂在已有链上：

```text
权限   RoleBasedPermissionChecker extends PermissionChecker
       角色 × 工具矩阵 + 兜底 ToolPolicy，deny-first

审批   工具层：REQUIRES_APPROVAL → ToolApprovalService（Stage 9）
       任务层：HumanApprovalNode → TaskApprovalBridge → approve/reject

审计   EnterpriseAuditTrail implements AuditLogger（请求视图）
       包装成 EnterpriseAuditEvent：AuditEvent + tenantId + userId + agentName
```

`GovernedToolExecutor` 零改动。它仍然调用 `check(toolName)`，没有用户参数。角色绑定发生在更早的装配时刻：`RoleBasedPermissionChecker.forRequest(matrix, policy, ctx.user().roles())`。这又是 D2：身份显式出现在构造参数里，而不是执行器内部去 ThreadLocal 里找人。

DENIED 事件同样入账。Stage 9 D6 的延伸：谁被拦了，本身就是安全情报。没有归属的拒绝流水，等于只知道「有人试过」，不知道是哪个租户的哪个角色在试。

---

## 4. 核心抽象和架构

```text
装配期（共享）
  roleMatrix:  agent:csr     → {search_knowledge, query_order}
               supervisor    → {search_knowledge, query_order, refund_order}
  ToolPolicy:  refund_order  → REQUIRES_APPROVAL
               delete_order  → DENY
  EnterpriseAuditTrail 台账

请求期（forRequest）
  RoleBasedPermissionChecker.forRequest(..., ctx.user().roles())
  auditTrail.forRequest(ctx, agentName)  → RequestAuditLogger
  GovernedToolExecutor.builder(delegate)
      .permissionChecker(checker)
      .auditLogger(requestAudit)
      .approvalService(toolApprovalService)

任务期（另一条入口）
  HumanApprovalNode → TaskApprovalBridge
  EnterpriseTaskManager.approve / reject
  TaskApprovalRecord 挂在 BusinessTask 上
```

权限判定的三步（`RoleBasedPermissionChecker.check` 原文顺序）：

```text
1. 兜底 policy 是 DENY → 永远 DENY
   硬禁不能被角色矩阵豁免，矩阵只收缩权限，永不覆盖治理
2. 用户任一角色的授权集合包含该工具 → AUTO
3. 否则走兜底：AUTO 或 REQUIRES_APPROVAL
```

对客服 Alice 的蓝图三档：

```text
query_order      矩阵命中 → AUTO
refund_order     矩阵未授 + 兜底 REQUIRES_APPROVAL → 要人批
delete_order     兜底 DENY → 直接拒绝，即使误把 delete_order 写进客服矩阵
```

审计包装：

```text
EnterpriseAuditEvent(
    event,      // Stage 9 原样保留
    tenantId,   // acme
    userId,     // u-alice（v1 记用户侧 onBehalfOf）
    agentName   // support-bot；v2 可演进为 svc:{accountId}
)
```

查询切面在台账上：`byTenant` / `byUser` / `byTool` / `all`。请求视图的 `getAll` / `getByRun` / `getByTool` 只看本请求事件，满足 `AuditLogger` 契约，是 drop-in 替换。

角色名是字符串，不是枚举框架。`User.ROLE_CSR = "agent:csr"`，`User.ROLE_SUPERVISOR = "supervisor"`，矩阵的 key 必须与 `User.roles` 对齐。空角色集合合法：一切回落到 `ToolPolicy`（`noRolesFallback`）。未知工具名也走兜底默认值（`unknownToolUsesPolicyDefault`），不会因为「没配过」就悄悄 AUTO。

`TaskApprovalBridge` 实现的是 workflow 的 `ApprovalService`，不是 security 的 `ToolApprovalService`。它拒绝同步 `approve(Request)`（`bridgeRejectsSyncMode`），只走 `requestApproval` / `checkDecision` / `decide`。决策表按 `runId` 键，v1 假设一条 run 同一时刻只有一个待批节点；多闸工单一次批一闸，后一次 pause 覆盖已消费的决策。

---

## 5. 一次完整数据流

把 T3 和 T4 叠在同一张退款图上。

```text
Alice（agent:csr）要求退款订单 8842

路径 A · 同步 ask（工具闸）
  模型调 refund_order
  → check("refund_order")
  → 矩阵无此工具，兜底 REQUIRES_APPROVAL
  → ToolApprovalService 返回 true
  → 审计：APPROVED + EXECUTED，tenant=acme，user=u-alice，agent=support-bot
  → loop 继续，终答「退款已发起」

路径 B · submitTask（任务节点）
  prepare 已执行
  → HumanApprovalNode 暂停
  → task = WAITING_APPROVAL
  → Bob approve(taskId, "u-bob", "金额在授权内")
  → TaskApprovalRecord(APPROVED, u-bob) 写入 task.approvals
  → resume 后执行退款节点
  → 若该节点再次调用 refund_order，工具闸还会再判一次
     此时若换成主管角色，矩阵直接 AUTO；若仍是客服身份跑工具，仍可能 REQUIRES_APPROVAL
```

两层不是互斥。蓝图的原话：退款任务跑到退款工具时，工具级审批先拦（角色不够）；主管审批任务后 resume，再过工具级审批（主管角色）。纵深的意思是：流程放行不等于调用自动合法，调用合法也不等于工单已经被主管看见。

拒绝路径同样完整：

```text
工具层 DENY     → 不执行，AuditStatus.DENIED 入账，带归属
任务层 reject   → cancel + resume 落地 CANCELLED，TaskApprovalRecord.REJECTED
                 已执行节点不回滚
```

---

## 6. 最小代码或实验

权限三档锁在 `RoleBasedPermissionCheckerTest.blueprintThreeTierCase`；硬禁锁在 `denyCannotBeLiftedByMatrix`：

```text
客服角色 + 越权矩阵里写了 delete_order
兜底 policy 仍是 DENY
check("delete_order") 必须还是 DENY
```

这是「矩阵不能推翻治理」的回归。同文件 `supervisorRefundAuto` 证明主管矩阵命中后 `refund_order` 为 AUTO；`multiRoleUnion` 证明多角色取并集。

审计锁在 `EnterpriseAuditTrailTest`：

- `eventsGetAttributed`：治理事件补上 tenant / user / agentName
- `denialsAttributed`：DENIED 同样归属
- `tenantAndUserCuts`：`byTenant` / `byUser` 切面
- `byToolCut`：事故切面「谁动过 refund_order」
- `requestViewIsolation`：alice 的请求 logger 看不到 bob 的 run

任务审批锁在 `EnterpriseTaskManagerTest.approveResumesWithoutRerun` 和 `rejectCancels`。门面搭车锁在 `EnterpriseAssistantTest.approvalRideAlong`：CSR 触发 `refund_order`，`byTool("refund_order")` 里必须出现 `APPROVED`，且全部 `userId=u-alice`。

`EnterpriseAuditEvent` 不改 `AuditEvent`。打开源码可以看到它只是 record 包装加三个归属字段，`toolName()` / `status()` / `runId()` 是委托。这是「组合不修改」的最小证据。

`RoleBasedPermissionChecker.forRequest` 的三个参数分工要记清：`roleMatrix` 是装配级静态配置，`fallbackPolicy` 是 Stage 9 的硬禁与兜底来源，`roles` 来自本次 `RequestContext.user().roles()`。`boundRoles()` / `roleMatrix()` 只供装配和审计内省。`GovernedToolExecutor` 调用的仍是无用户参数的 `check(toolName)`——扩展发生在子类，调用方契约不变。这是 Stage 9 预留扩展点能兑现的原因。

`EnterpriseAuditTrailTest.forRequestValidation` / `eventValidation` 把空 `agentName`、空归属字段直接拒绝。请求视图一旦建出来，后续 `log(AuditEvent)` 只做包装入账，不再问调用方「这是谁的事件」——归属在 `forRequest` 时已经冻住。和 `KnowledgeTool` 一样：身份是装配决策，不是事件发送方临时填写的字段。`accessors` 与 `factoryValidation` 则保证 checker 自己的空参在构造期失败，而不是在第一次 `check` 时 NPE。

任务侧查询是另一本账：`EnterpriseTaskManager.byTenant(tenantId)` 列出该租户工单，`TaskApprovalRecord.Decision` 只有 `APPROVED` / `REJECTED`。`unknownTaskAndBlankArgsRejected` 保证乱 id、空 approver、空 reason 进不了决策。治理流水回答「谁调过什么工具」，任务留痕回答「谁放行了哪一单」，两套查询不要混用。

`TaskApprovalRecord` 五个字段都是必填：`taskId` / `approverId` / `decision` / `reason` / `at`。reason 允许空语义但不允许 null——主管必须留下一句话。`queries` 测试覆盖 `find` 与 `byTenant`：工单按租户切开，和审计 `byTenant` 是同一隔离直觉，实现却是两张内存表。查合规事件走 trail，查业务进度走 task manager。

---

## 7. 常见误区

1. **「角色矩阵是最终权威」** —— 矩阵是授权白名单，不是治理覆盖层。`DENY` 在第一步就返回，客服被误配 `delete_order` 也不能删单。
2. **「审批只要一层」** —— 工具审批看不见工单；任务审批看不见单次 tool call 的越权。退款场景两层都可能触发。
3. **「改 AuditEvent 加上 tenantId 更省事」** —— 省事的是这一次，贵的是所有 Stage 9 消费者和 Runtime 纯度。归属是企业概念，应留在 Profile。
4. **「没拦住的调用才需要审计」** —— 拒绝事件是「谁在试探边界」。`denialsAttributed` 存在就是为了防止这种漏记。
5. **「任务审批记录可以写在 Run 上」** —— 主管批准的是业务。`TaskApprovalRecord.taskId` 指向工单；run 可能被 resume 或替换。

---

## 8. 和相邻概念的区别

```text
PermissionChecker vs RoleBasedPermissionChecker
  前者：按工具名读 ToolPolicy
  后者：extends 前者，先看硬禁，再看角色矩阵，再回落政策
  扩展点从 Stage 9 就留好了

ToolApprovalService vs TaskApprovalBridge
  前者：Stage 9，给 REQUIRES_APPROVAL 工具用，同步问一次
  后者：Stage 15，给 HumanApprovalNode 用，决策表按 runId，装配级生命周期
  示例 T3 用 lambda (toolCall, runId) -> true
  T4 用 taskManager.approvalService()

EnterpriseAuditEvent vs AuditEvent
  AuditEvent：发生了什么
  EnterpriseAuditEvent：发生了什么 + 属于谁
  v1 userId 是 onBehalfOf；actor=svc:xxx 留给 v2

用户身份 vs Agent 身份
  本阶段审计先记用户和 agentName
  完整双归属：actor=svc:support-bot，onBehalfOf=u-alice
  字段槽位已留，schema 不再为服务身份改一轮
```

成本不在本篇展开，但同属 M15.3：`CostLedger` 是账本，不是权限。额度耗尽拒绝新请求，和工具 DENY 是两种闸，不要合成一个「万能 checker」。`CostLedgerTest.gateFlipsAfterRecording` 证明闸读的是上一笔 `record`；`tenantsIndependent` 证明两租户计数互不串。它仍然只是两维账本，不是 Stage 18 的五维 `BudgetBook`。

---

## 9. 我的设计判断

最重的一条：**治理链保持通用，领域语义用包装和扩展点接入。** `GovernedToolExecutor` 不知道 User，`AuditEvent` 不知道 Tenant。Profile 的职责是翻译，不是改底座。这正是「Enterprise 缺归属层，不是新 Runtime」在治理上的证据。

第二条：**deny-first 比 RBAC 矩阵更重要。** 矩阵写错是配置事故，硬禁被豁免是安全事故。实现把 DENY 放在角色循环之前，测试又用「越权配置仍 DENY」锁住，这个顺序不能倒。

第三条：**审批对象决定记录挂在哪。** 工具审批的证据在 `EnterpriseAuditTrail`（治理事件）；任务审批的证据在 `TaskApprovalRecord`（业务事件）。查「这个工具谁调过」走 `byTool`；查「这单谁批的」走 `task.approvals()`。两本账不要合并成一张表。

零存量改动在这一层特别干净：`agent-security` 一行未改。扩展发生在子类和组合包装上。

---

## 10. 面试表达

> 「企业治理我拆成权限、双层审批和归属审计，全部挂在 Stage 9 链上，不改 GovernedToolExecutor。权限是 RoleBasedPermissionChecker：deny-first，角色矩阵只能授予 AUTO，不能掀开硬禁。工具审批保单次调用，任务审批保工单流程，resume 后工具闸仍可能再判一次。审计用 EnterpriseAuditEvent 组合 AuditEvent，按租户/用户/工具查询，DENIED 也归属。这是组合优于修改，也是第一个 Profile 只补归属层的具体做法。」

---

## 11. 下一篇连接什么

权限和审计已经能按租户切开流水。下一篇把刀用到状态上：知识、记忆、检索白名单如何保证 acme 永远拿不到 globex。隔离不是新存储，是 Stage 8 scope 白名单的兑现。

→ [stage-15-article-5-tenant-isolation.md](stage-15-article-5-tenant-isolation.md)
