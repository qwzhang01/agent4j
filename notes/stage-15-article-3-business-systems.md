# 企业 Agent 如何接入真实业务系统

> 配套蓝图：[architecture-stage-15.md](architecture-stage-15.md) §2 复用清单 / D1 / D5 · 业务场景：[stage-15-business-scenario.md](stage-15-business-scenario.md) §4.2
> 对应实现：`knowledge/KnowledgeTool` · `KnowledgeBase` · `EnterpriseAgentFactory.tool(...)` · 示例 mock 业务工具
> 全剧本：[EnterpriseAssistantExample.java](../examples/src/main/java/io/github/qwzhang01/agent/examples/EnterpriseAssistantExample.java)
> 这是 Stage 15 系列的第 3 篇。上一篇走完了请求链；本篇拆链上的工具如何碰到业务数据和业务 API。

---

## 1. 我今天要解决什么问题

请求链装配好之后，模型仍然只看得到工具名。企业客服真正要碰的是两样东西：

```text
企业知识   退货政策、FAQ、产品参数 —— 先检索再开口
业务系统   订单、退款、工单状态   —— 一次调用就有副作用
```

Demo 里常见的做法是把政策写进 system prompt，把订单做成内存 Map。这能演示，但不能回答三个工程问题：

1. 知识按租户隔离，模型能不能指定别人的租户？
2. 业务 API 走哪条工具契约，治理能不能免费搭车？
3. 企业 Profile 不依赖 `agent-product`，真实 HTTP 接入放哪一层？

今天要解决的是：**接入业务系统不需要新的工具机制，需要的是正确的绑定时机和依赖边界。**

---

## 2. 为什么会有这个概念

Runtime 从 Stage 2 起就有 `Tool`：`getName` / `getDescription` / `getParametersSchema` / `execute`。Stage 9 的 `GovernedToolExecutor` 按工具名做权限、审批、审计；Stage 13 的 `HttpApiTool`（`io.github.qwzhang01.agent.product.tools`）把 YAML 声明的 REST 端点做成一个普通 `Tool`。这些机制已经够用。

企业场景新增加的不是「另一种 Tool」，而是两种**领域语义**：

```text
知识是受控输入     管理员 ingest，type=KNOWLEDGE，scope=tenant:{tid}
业务操作是副作用   query_order 只读，refund_order 敏感，必须进治理链
```

如果为知识再造一个 `KnowledgeStore`，就会多一条隔离边界、多一套检索 bug。如果为企业再造一套 HTTP 客户端，就会绕开已经存在的治理链。蓝图的预检结论是：Profile 不做新工具机制；知识即记忆；业务工具走 `Tool` 注册。

D1 同时规定：`agent-enterprise` 只依赖 `agent-core` + `agent-memory` + `agent-security` + `agent-workflow`，**不依赖** `agent-product` / `agent-channel`。所以 `HttpApiTool` 可以在示例或产品装配层使用，但不能变成 Enterprise 模块的 compile 依赖。领域层泄漏进声明层，编译期就该暴露。

---

## 3. 它解决了什么问题

接入被拆成三条可组合的路径，全部落在已有 `Tool` 契约上：

```text
1. 知识检索
   KnowledgeTool implements Tool，name = search_knowledge
   租户 ID 构造期绑定，参数 schema 只有 query / top_k

2. 业务工具（v1 示例）
   EnterpriseAssistantExample 里的 query_order / refund_order
   实现 io.github.qwzhang01.agent.core.tool.Tool
   经 Factory.tool(...) 注册为共享无状态工具

3. 真实 HTTP（产品层可选）
   HttpApiTool / HttpApiToolFactory（agent-product，Stage 13）
   同样 implements Tool，注册进同一 ToolRegistry 即受治理链包裹
   Enterprise 模块本身不引用它们
```

模型侧看到的都是工具。差别在装配：知识工具每请求克隆且绑死租户；业务工具共享，靠角色矩阵和 `ToolPolicy` 决定能不能调、调之前要不要人批。

「先检索再开口」也不靠 ContextBuilder 预拼整库。蓝图 D5：检索时机交给模型，检索边界（租户）交给装配。`KnowledgeTool` 的 description 明确要求：回答公司规则和产品事实前先调用，并引用检索到的标题。

---

## 4. 核心抽象和架构

```text
                    EnterpriseAgentFactory.forRequest(ctx)
                    ┌─────────────────────────────────────┐
                    │ per-request InMemoryToolRegistry    │
                    │   共享：query_order / refund_order   │
                    │   请求：KnowledgeTool.forTenant(tid) │
                    └──────────────┬──────────────────────┘
                                   │
                    GovernedToolExecutor（Stage 9，零改动）
                                   │
              ┌────────────────────┼────────────────────┐
              ▼                    ▼                    ▼
     KnowledgeTool          query_order            refund_order
     boundTenantId=acme     只读查单               敏感副作用
              │                    │                    │
              ▼                    ▼                    ▼
     KnowledgeBase          业务系统（mock / HTTP）
     MemoryStore            ORDERS map 或 HttpApiTool
     type=KNOWLEDGE
     scope=tenant:acme
```

知识层三个类：

| 类 | 包 | 职责 |
|---|---|---|
| `KnowledgeEntry` | `enterprise.knowledge` | 领域视图：title / content / source / tags |
| `KnowledgeBase` | `enterprise.knowledge` | `ingest(tenantId, entries, adminId)` + `search(tenantId, query, topK)` |
| `KnowledgeTool` | `enterprise.knowledge` | `implements Tool`；`forTenant(kb, tenantId)` 不可变绑定 |

`KnowledgeEntry.toMemoryEntry(tenantId, adminId)` 的投影是「知识即记忆」的落点：

```text
type   = MemoryType.KNOWLEDGE
scope  = MemoryScope.tenant(tenantId)   // tenant:acme
status = MemoryStatus.ACTIVE            // 受控导入，免 PENDING_REVIEW
provenance = MemoryProvenance.adminEdit(adminId, now)
```

`source` / `tags` 在 v1 只留在导入侧。`MemoryEntry` 没有自由元数据槽，`fromMemoryEntry` 返回空而不是从正文猜出处。`KnowledgeBaseTest.honestMetadataBoundary` 锁住这条诚实边界。

`KnowledgeBase.search` 的检索式值得对着源码看一遍：`MemoryQuery.builder().scopes(List.of(MemoryScope.tenant(tenantId).value())).type(MemoryType.KNOWLEDGE).keyword(query).limit(limit)`。`topK <= 0` 回落到 `KnowledgeEntry.DEFAULT_TOP_K = 3`。keyword 为空时走「最新条目、不按词过滤」——这是 MemoryStore 的既有语义，不是知识层另写的模糊搜索。`count(tenantId)` 同样带 type+scope，只数本租户的 KNOWLEDGE，给管理端用。

`KnowledgeTool.execute` 的输出契约是固定 JSON：`{"count": n, "results": [{"title", "content"}]}`，空结果再加 `"message": "no knowledge found for: ..."`。缺 `query` 抛 `ToolException`，由默认执行器变成模型可读的 `[ERROR]`，不炸 loop。`boundTenantId()` 只给装配和审计看，模型看不到。

业务工具没有企业专用基类。示例里 `orderQueryTool()` / `refundTool()` 直接实现 `Tool`，参数 schema 只有 `orderId`。真实系统替换的是 `execute` 的内部，不是工具机制。

---

## 5. 一次完整数据流

管理员先导入，模型后检索，业务工具按需调用。

**T0 · 知识导入（管理员，不经模型）**

```java
KnowledgeBase knowledge = new KnowledgeBase(new InMemoryMemoryStore());
knowledge.ingest("acme", List.of(
        KnowledgeEntry.of("Return Policy", "acme policy: 30-day no-question returns"),
        KnowledgeEntry.of("Invoice Policy", "acme policy: invoices within 24 hours")),
        "admin");
knowledge.ingest("globex", List.of(
        KnowledgeEntry.of("Return Policy", "globex policy: all sales final")),
        "admin");
```

两侧内容都含关键词 `policy`，这是隔离测试的控制变量：唯一能造成差异的是 scope，而不是一边检索不到。

**T2 · 模型检索本租户知识**

```text
alice(acme) 问「退货政策是什么？」
  → search_knowledge({"query":"policy"})
  → KnowledgeTool 内部只用构造时的 tenantId=acme
  → KnowledgeBase.search("acme", "policy", 3)
  → MemoryQuery(scopes=[tenant:acme], type=KNOWLEDGE, keyword=policy)
  → JSON {"count":n,"results":[{"title":"...","content":"..."}]}
```

即使恶意 prompt 写成 `{"query":"policy","tenant":"globex"}`，schema 没有 `tenant` 槽位，绑定不会变。`KnowledgeToolTest.tenantBindingImmutable` 就是这条双保险的测试。

**T3 · 业务副作用走同一执行器**

```text
模型调 refund_order({"orderId":"8842"})
  → 共享工具，不绑租户
  → 权限由 RoleBasedPermissionChecker 按角色判定
  → 敏感工具再过 ToolApprovalService
  → execute 里改业务系统（示例用 AtomicInteger 计数）
```

知识工具防的是**读错租户**；业务工具防的是**写错权限**。两者都是 `Tool`，治理挂点相同，约束点不同。

---

## 6. 最小代码或实验

知识工具的租户绑定是接入层最重要的不变量：

```java
public static KnowledgeTool forTenant(KnowledgeBase knowledgeBase, String tenantId) {
    return new KnowledgeTool(knowledgeBase, tenantId);
}

// schema 只有 query / top_k，没有 tenant
```

`KnowledgeToolTest` 一组测试把契约钉死：

- `jsonOutputContract`：输出 `count` + `results[].title/content`
- `noHitSearch`：空结果是 `count=0` 加 message，不是异常
- `tenantBindingImmutable`：参数里夹带别人的 tenant 无效
- `missingQueryRejected`：缺 `query` 抛 `ToolException`
- `toolMetadata`：`NAME = "search_knowledge"`

业务侧看示例，不要把 mock 当成框架 API：

```java
.tool(orderQueryTool())          // name = query_order
.tool(refundTool(refundCount))   // name = refund_order
.knowledgeBase(knowledge)        // 每请求自动注册 KnowledgeTool
```

知识门面测试补齐接入的存储侧：`KnowledgeBaseTest.ingestSearchRoundTrip` 是导入再搜的最小闭环；`sharedStoreMultiTenant` 是一 store 三租户各搜各的（标准部署）；`knowledgeTypePurity` 防止 tenant scope 里的 FACT 冒充知识；`topKTruncation` 锁截断。

`EnterpriseAssistantTest.askFullChain` 用审计事件的 `result` 断言检索到的是 acme 切片；`tenantIsolationEndToEnd` 断言 carol 的 `result` 含 `globex policy`、acme 台账没有 carol。接入是否正确，最终用治理证据验收，而不是用打印语句。

若要把 mock 换成真实 HTTP：在**装配层**（examples 或未来的产品壳）用 `HttpApiToolFactory` 造出 `Tool`，再 `builder.tool(httpTool)`。不要把 `agent-product` 加进 `agent-enterprise` 的 compile 依赖。`HttpApiTool` 的 javadoc 已经说明：注册进 `ToolRegistry` 后，Stage 9 治理免费包裹。

---

## 7. 常见误区

1. **「企业接入 = 给 Agent 加一套 HTTP 模块」** —— 机制已在。缺的是租户绑定、角色矩阵和审批。新模块往往绕开治理。
2. **「把 tenantId 当作工具参数，让模型传入」** —— 这是跨租户泄漏的标准写法。租户是装配决策，不是模型决策。
3. **「知识预拼进 system prompt」** —— 整库预拼既贵又无法按请求白名单裁剪。v1 把时机交给模型，把边界交给 `KnowledgeTool`。
4. **「Enterprise 应该依赖 HttpApiTool」** —— 违反 D1。产品层的 HTTP 声明和领域层的租户模型不同源；编译期依赖会把两套租户概念缠在一起。
5. **「知识导入也走 PENDING_REVIEW」** —— 对话记忆需要审核，因为是模型沉淀；知识是管理员选过的，导入动作本身就是控制点。`toMemoryEntry` 直接 `ACTIVE`。

---

## 8. 和相邻概念的区别

```text
KnowledgeTool vs 业务 Tool
  KnowledgeTool：只读、租户绑定、无业务副作用
  query_order / refund_order：碰业务系统，靠权限和审批约束

Knowledge vs Memory
  Knowledge：管理员导入的业务输入，type=KNOWLEDGE，scope=tenant
  Memory：对话沉淀，PREFERENCE / FACT / EPISODE...，常带 PENDING_REVIEW
  同一 MemoryStore，不同类型与治理语义

KnowledgeBase.search vs RequestContext.memoryScopes
  search 只查 [tenant:{tid}] —— 知识是租户共享资产
  memoryScopes = [tenant:{tid}, user:{uid}] —— 记忆注入含个人偏好
  知识比个人记忆更窄，不是写错了

HttpApiTool vs 示例 mock Tool
  两者都是 Tool
  mock 用于零外部依赖验收（EnterpriseAssistantExample）
  HttpApiTool 用于产品层声明式 REST
  Enterprise 模块对两者都只认 Tool 接口
```

「同一机制」在这里是第三次兑现：Stage 12 共享记忆是 scope 取值，Stage 14 轨迹是 adapter 投影，Stage 15 知识是 `MemoryType` 的一种。

---

## 9. 我的设计判断

最重的一条：**模型可以决定何时检索，不可以决定检索谁的。** 时机是能力，边界是安全。把租户放进 schema，等于把隔离交给 prompt。

第二条：**业务接入的稳定面是 `Tool`，不是某个 HTTP 客户端。** 订单系统从内存 Map 换成 REST，只应改 `execute` 或替换注册进去的实例。治理链（`GovernedToolExecutor`）按名字工作，不按实现类型工作。

第三条：**依赖方向是架构，不是口味。** Enterprise 不依赖 product，才能保证「领域 Profile 的 bug 不该先去改声明层」。需要 `HttpApiTool` 时，在更外的装配圈使用它。这和「组合优于修改」是同一纪律。

v1 检索是 keyword，没有向量、没有重排。接口形状（`search(tenantId, query, topK)`）故意保持可替换：换 retriever 实现时，`KnowledgeTool` 不用改绑定方式。

示例里的两个 mock 工具只为验收存在，不要把它们写成框架 API：`query_order` 读静态 `ORDERS`（8842=shipped，9917=refunded）；`refund_order` 用 `AtomicInteger` 数副作用次数。schema 都只有 `orderId`。换成真实订单服务时，改的是 `execute` 内部的 HTTP/SDK 调用，工具名和治理矩阵可以不变。这正是「Profile 不做新工具机制」的实践含义。

`KnowledgeEntry.of(title, content)` 是导入最小工厂；完整构造才带 `source` / `tags`。`ingest` 的第三参 `adminId` 进入 `MemoryProvenance.adminEdit`，不是装饰字段。`blankQueryReturnsAll` / `emptyIngestNoOp` / `blankTenantRejected` / `entryValidation` 把门面的失败形态锁死：空导入是 no-op，空 tenantId 直接拒绝，条目缺 title/content 在 record 校验期失败。接入层的 fail-closed 从写库那一刻就开始，不是等到模型调用才补。

`KnowledgeTool.NAME` 常量是 `"search_knowledge"`，与角色矩阵、示例 scripted `ToolCall`、审计 `toolName` 必须同一字符串。`getDescription` 要求模型在回答公司规则和产品事实之前先调此工具并引用标题——这是提示词契约，不是检索实现。`toolMetadata` 把 name/schema 钉死，避免有人改名后矩阵和剧本一起哑火。

`topKArgument` 覆盖模型传入的 `top_k`：合法整数才覆盖默认 3，否则回落 `DEFAULT_TOP_K`。`boundTenantVisibleForAudit` 只断言装配能读到 `boundTenantId()`，不把它放进 JSON 给模型。检索结果故意不含 source/tags——和 `fromMemoryEntry` 的诚实边界一致，模型引用的是 title/content，不是伪造出处。

---

## 10. 面试表达

> 「企业 Agent 接入业务系统不需要新的工具框架。知识走 `KnowledgeTool`，它 implements Tool，租户在 `forTenant` 时绑死，schema 没有 tenant 参数；存储复用 MemoryStore，type=KNOWLEDGE，scope=tenant:{id}。订单和退款也是普通 Tool，示例用 mock，真实 HTTP 用 Stage 13 的 HttpApiTool，在装配层注册，Enterprise 模块不依赖 product。治理链按工具名包裹，知识防读错租户，业务工具防写错权限。知识即记忆，不是再造一套 KnowledgeStore。」

---

## 11. 下一篇连接什么

工具已经能碰到知识和业务系统。下一篇问：谁被允许调用它们，敏感调用如何审批，整条链如何留下可按租户和用户查询的审计。权限、审批、审计是同一组治理接线，但审批仍然是两层。

→ [stage-15-article-4-permission-approval-audit.md](stage-15-article-4-permission-approval-audit.md)
