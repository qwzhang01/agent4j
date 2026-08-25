# 多租户 Agent 的状态隔离

> 配套蓝图：[architecture-stage-15.md](architecture-stage-15.md) D3 / D5 · 业务场景：[stage-15-business-scenario.md](stage-15-business-scenario.md) §5.3
> 对应实现：`RequestContext.memoryScopes()` · `MemoryScope.tenant` · `MemoryType.KNOWLEDGE` · `KnowledgeBase` / `KnowledgeTool`
> 全剧本：[EnterpriseAssistantExample.java](../examples/src/main/java/io/github/qwzhang01/agent/examples/EnterpriseAssistantExample.java) T5
> 这是 Stage 15 系列的第 5 篇。上一篇能按租户查审计；本篇保证知识和记忆在机制上不能跨租户泄漏。

---

## 1. 我今天要解决什么问题

SaaS 企业 Agent 最严重的事故不是答错政策，而是答对了别人的政策：

```text
Acme 客服 Alice 问退货
  → 检索到 Globex「all sales final」
```

一次泄漏就是安全事故，也是合同事故。Demo 里「所有知识放一个 List，检索时自己 filter tenantId」看起来能用，本质是约定：调用方必须传对、必须记得传、必须不会被模型改掉。

今天要解决的问题是：

> 多租户隔离能不能做成存储机制，而不是调用约定？知识、个人记忆、审计和账单各自的边界在哪一层执行？

---

## 2. 为什么会有这个概念

Stage 8 的 `MemoryStore` 已经承诺过：query 的 scope 列表是白名单，store never returns entries outside this list。当时枚举里有 `agent` / `user` / `session` / `task` / `channel`，javadoc 也写了 Multi-tenant isolation is enforced by the store。设计意图先于枚举值存在。

企业场景第一次真的需要 `tenant:{id}` 这条命名空间，以及一种不是对话沉淀的类型 `KNOWLEDGE`。Stage 15 对存量只做了两处**纯加法**：

```text
agent-memory MemoryScope.Kind + TENANT
             MemoryScope.tenant(String) → "tenant:" + tenantId
agent-memory MemoryType + KNOWLEDGE
```

除此之外，`agent-core` / `agent-security` / `agent-workflow` / `agent-product` / `agent-channel` 零改动。隔离没有新机制，只是把 Stage 8 D3 的白名单兑现成企业语义。

如果这里新建 `TenantKnowledgeStore`，就会出现第二套「别把 tenantId 传错」的约定，测试也要测两遍。知识即记忆，隔离才能免费继承。

---

## 3. 它解决了什么问题

企业层做三件事，让白名单从文档变成请求路径上的 SSOT：

```text
1. RequestContext.memoryScopes()
   = [tenant:{tid}, user:{uid}]
   记忆注入、个人偏好检索走这份名单

2. KnowledgeBase.search(tenantId, query, topK)
   MemoryQuery.scopes = [tenant:{tid}]
   知识只查租户命名空间，不带 user scope

3. KnowledgeTool.forTenant(kb, ctx.tenantId())
   模型参数没有 tenant 槽位
   构造期绑定 + store 白名单，双重拦截
```

fail-closed 也继承自 store：白名单为空，结果为空，不降级成全库扫描。`TenantIsolationTest.emptyWhitelistIsEmpty` 锁住这条，防止「忘传 scope = 看见所有租户」。

登录本身也是隔离闸。`TenantRegistry.login` 校验用户归属，acme 的 key 不能冒领 globex 的上下文；未知租户、停用租户、归属不一致全部 `EnterpriseAuthException`。没有验证过的身份，就没有匿名降级上下文。

---

## 4. 核心抽象和架构

```text
                    RequestContext
                    memoryScopes() = [tenant:acme, user:u-alice]
                    tenantId()     = acme
                         │
          ┌──────────────┼──────────────┐
          ▼              ▼              ▼
   MemoryContextBuilder  KnowledgeTool  CostLedger / AuditTrail
   注入个人+租户记忆     只绑 acme      按 ctx 记账和归属
          │              │
          ▼              ▼
        MemoryStore（同一实例可服务多租户）
        条目 A: type=KNOWLEDGE, scope=tenant:acme
        条目 B: type=KNOWLEDGE, scope=tenant:globex
        条目 C: type=FACT,      scope=user:u-alice
        条目 D: type=FACT,      scope=user:u-bob

        query(scopes=[tenant:acme])     → 只能看到 A
        query(scopes=[tenant:acme, user:u-alice]) → A + C
        query(scopes=[])                → 空
```

相关类型：

| 类型 | 位置 | 隔离职责 |
|---|---|---|
| `MemoryScope.tenant(id)` | `agent-memory` | 命名空间工厂，存量纯加法 |
| `MemoryType.KNOWLEDGE` | `agent-memory` | 类型过滤，避免 FACT 冒充知识 |
| `RequestContext` | `enterprise.tenant` | 白名单 SSOT + `actor()` |
| `KnowledgeBase` | `enterprise.knowledge` | search 恰好一个 tenant scope |
| `KnowledgeTool` | `enterprise.knowledge` | 租户绑定不可变 |
| `Tenant` / `User` | `enterprise.tenant` | 领域实体；凭证不进 `User` record |

`Tenant.monthlyTokenBudget` 和 `CostLedger` 构成账单隔离：acme 的 Token 不计入 globex。`EnterpriseAuditTrail.byTenant` 构成证据隔离。它们不是 MemoryStore 白名单，但同属「按租户切开状态」：数据、知识、审计、成本四条边都要切。

`Tenant` 本身是隔离边界的领域实体：`tenantId` + `ACTIVE/SUSPENDED` + 预算字段。`Tenant.active(id, name)` 默认无限额；`suspended()` 是 wither，返回新实例。`User` 的 `tenantId` 在 v1 不可变，注册时 `TenantRegistry.registerUser` 会校验归属租户已存在且 ACTIVE、`userId` 全局唯一、apiKey 非空。身份和凭证分离：apiKey 只活在 registry 内部表，`User.toString()` 永不带密钥。已签发的 `RequestContext` 持有登录当时的 `Tenant` 快照，`suspendTenant` 不改进行中的请求，只挡下一道 `login`。

共享 store 是标准部署形态。`KnowledgeBase` 的 javadoc 写明：隔离由 store 保证，不由本类保证；一个 `KnowledgeBase` 服务多个租户是安全的，前提是每次 search 只放一个 tenant scope。`KnowledgeBaseTest.sharedStoreMultiTenant` 覆盖一 store 三租户。

---

## 5. 一次完整数据流

T5 是隔离的验收剧本，不是附加彩蛋。

```text
同一套知识库：
  acme   《退货政策》30 天无理由
  globex 《退货政策》all sales final

carol = registry.login("globex", "u-carol", "key-carol")
globexAssistant.ask(carol, "退货政策？")
  → forRequest(carol)
  → KnowledgeTool.forTenant(..., "globex")
  → search 只带 tenant:globex
  → 终答 Globex: all sales final

assistant.auditTrail().byTenant("acme")
  → 不含 carol 的请求（carol 走的是另一条 assistant / 另一本 trail）

dave = registry.login("acme", "u-dave", "key-dave")
同一 CostLedger，用户额度 50
  第一次 ask 花 60 → record 后 userUsed=60
  第二次 requireBudget → BudgetExceededException
  第二次零 Token 消耗
```

注意示例里 carol 用了另一台 `EnterpriseAssistant`、另一本 `EnterpriseAuditTrail`。端到端隔离的更强证明在测试：`EnterpriseAssistantTest.tenantIsolationEndToEnd` 让 carol 和知识库共享同一套装配，只换 `RequestContext`，然后断言：

```text
carol 的审计 result 含 globex policy，不含 acme
byTenant("acme") 事件数为 0
ledger.tenantUsed("globex") = 120
```

登录白名单端到端在 `TenantIsolationTest.contextWhitelistSeesOwnDataOnly`：alice 的 recall 恰好是 acme 知识 + alice 记忆，bob 的记忆和 globex 知识都不可见。

---

## 6. 最小代码或实验

白名单 SSOT 只有几行，但所有检索都应读它：

```java
public List<String> memoryScopes() {
    return List.of(
            MemoryScope.tenant(tenant.tenantId()).value(),
            MemoryScope.user(user.userId()).value()
    );
}
```

`TenantIsolationTest` 是隔离测试的主文件：

- `tenantScopeFormat` / `tenantScopeRoundTrip`：`tenant:acme` 格式与解析
- `crossTenantZeroLeakage`：keyword 命中前提下的双向零泄漏
- `emptyWhitelistIsEmpty`：空名单不放大
- `userPrivacyBoundary`：user scope 互不可见
- `contextWhitelistSeesOwnDataOnly`：login 产出的名单端到端
- `knowledgeTypeFilter`：KNOWLEDGE 过滤不混入 FACT

`KnowledgeBaseTest.crossTenantZeroLeakage` 与 `knowledgeTypePurity` 从知识门面再证一遍：tenant scope 里直接写入的 FACT 不会被 `search` 当成知识。类型和 scope 是两道正交过滤器。

写隔离测试时有一个容易失效的构造：两边内容必须都能命中同一关键词。初版 globex 写 `all sales final`、查询 `returns`，globex 恒为 0——测试「通过」了，证明的却是数据不可检索，不是隔离。修正后两侧都含 `policy`，唯一变量只剩 scope。这条教训写在蓝图 §13。

`userOfUnregisteredTenantRejected` / `userOfSuspendedTenantRejected` / `duplicateUserRejected` / `blankKeyRejected` 把注册期隔离也锁住：用户不能挂到不存在或已停用的租户上。隔离从「检索时 filter」前移到「身份根本发不出来」。

---

## 7. 常见误区

1. **「应用层 if (tenantId.equals(...)) 就算隔离」** —— 漏写一次就泄漏。白名单必须在 store 执行。
2. **「让模型传 tenantId，后台再校验」** —— 校验能拦恶意参数，拦不住「忘记校验」。绑定直接删掉参数槽位更硬。
3. **「知识库按租户 new 多个 MemoryStore」** —— 可以，但不是隔离的必要条件。共享 store + scope 才是机制；多 store 是部署选择。
4. **「记忆注入和知识检索用同一份 scope 列表」** —— 记忆要带 `user:{uid}`，知识只要 `tenant:{tid}`。混用会把个人偏好当公司政策，或把公司知识漏进个人回忆测试。
5. **「停用租户用重新 register 同名租户来测」** —— 只会打到 already registered。正确路径是 `suspendTenant` + 再 `login`，见 `TenantRegistryTest.suspendedTenantRejected`。

---

## 8. 和相邻概念的区别

```text
租户隔离 vs 用户隐私
  租户：acme 看不见 globex 的知识、审计、账单
  用户：alice 看不见 bob 的 user scope 记忆
  RequestContext 两份 scope 同时给出，职责不同

TENANT scope vs CHANNEL scope
  CHANNEL（Stage 12）：频道内多人共享
  TENANT（Stage 15）：企业租户资产
  都是 MemoryScope.Kind 的值，不是两套存储

知识隔离 vs 审计隔离 vs 成本隔离
  知识：MemoryStore 白名单
  审计：EnterpriseAuditTrail 按字段过滤（台账在内存里，查询切面）
  成本：CostLedger 按 tenantId / userId 两个计数器
  机制不同，验收都要做跨租户对照

Profile 租户 vs 产品层 TenantAgentConfig
  前者：领域实体，登录、隔离、预算的主人
  后者：YAML 定义的租户覆盖
  Enterprise 不依赖 product，避免两套租户缠在一起
```

Stage 13 留下过「租户记忆隔离 v2」的方向。本篇就是那笔承诺的兑现：加法发生在 `MemoryScope` / `MemoryType`，企业层只负责把白名单填对。

---

## 9. 我的设计判断

最重的一条：**隔离是机制，不是约定。** 约定依赖每个人都正确；机制让错误路径返回空。`MemoryStore` 的白名单是唯一实现点，企业层只提供正确的名单和不可变的工具绑定。

第二条：**测试必须控制变量。** 跨租户测试若一边检索不到，绿色是假的。隔离证明的标准形态是：同一 keyword、两侧都有命中可能、结果集合仍不相交。

第三条：**存量改动保持纯加法。** 加枚举值、加工厂方法，旧路径不引用新值，Stage 8 的 66 个测试零影响。这是「Profile 的 bug 不该先改 Runtime」的可复核证据：Runtime 只补了早就写在 javadoc 里的命名空间。

已签发的 `RequestContext` 是不可变快照。`suspendTenant` 之后，旧 context 仍持有当时的 `Tenant` 状态；新登录会被拒。这是有意的：进行中的请求不因管理员操作突然改身份，停用作用于下一道登录闸。

知识检索和记忆注入的 scope 宽度不同，写装配时不要图省事共用一份名单：`KnowledgeBase.search` 只放 `tenant:{tid}`，因为政策是租户资产；`MemoryContextBuilder` 吃 `ctx.memoryScopes()`，因为个人偏好要进对话。`knowledgeTypeFilter` 再加第三道：就算有人往 tenant scope 里写入 `FACT`，`type=KNOWLEDGE` 的 query 也不会把它当政策返回。scope、type、工具绑定，三道正交。

`MemoryScope` 的其它工厂（`user` / `agent` / `session` / `task` / `channel`）本阶段一个没改。`tenant(String)` 只是并列加法，`Kind.TENANT` 的 javadoc 写明「Isolation is enforced by the store」。`TenantIsolationTest.tenantScopeFormat` 断言值为 `tenant:acme`，`tenantScopeRoundTrip` 走 `MemoryScope.of` 再解析 `kind()`。存量测试零影响，是因为旧路径从不引用这两个新符号——纯加法的可复核定义就在这里。

`userPrivacyBoundary` 专门锁个人记忆：同一租户下 alice 的 `user:u-alice` 条目，用 bob 的 user scope 查询必须为空。租户共享不等于同事共享。客服可以检索公司退货政策，不能检索另一位客服的对话偏好。这是 SaaS 里仅次于跨租户泄漏的第二类事故。

`KnowledgeBase.count(tenantId)` 给管理端用：只数该租户 `KNOWLEDGE` 条目，不把 FACT/PREFERENCE 算进知识库容量。`countIsTypeAndScopeBounded` 锁住这条。共享 store 时，acme 的 count 增加不得改变 globex 的 count——隔离验收不只看 search 结果，也看计量。账单、审计、知识条数，三套计数都要按租户独立。

---

## 10. 面试表达

> 「多租户 Agent 的隔离我没有新做存储。Stage 8 的 MemoryStore 本来就是 scope 白名单，Stage 15 只加了 MemoryScope.TENANT 和 MemoryType.KNOWLEDGE 两处纯加法。RequestContext.memoryScopes() 给出 [tenant:id, user:id]，知识检索只带 tenant scope，KnowledgeTool 在构造时绑死租户，schema 不出现 tenant。跨租户零泄漏用同一关键词双向对照测试，空白名单返回空而不是全库。隔离是机制，调用方传错也拿不到别人的数据。」

---

## 11. 下一篇连接什么

身份、链路、接入、治理、隔离都已经拆过。最后一篇从 Demo 往回看：企业 Agent 到底补齐了哪些控制面，哪些刻意没做，以及同一 Runtime 的下一个领域 Profile 为什么可以继续零存量改动。

→ [stage-15-article-6-control-plane.md](stage-15-article-6-control-plane.md)
