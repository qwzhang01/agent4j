# 频道级配额：Claude Tag 的频道 Token 上限怎么做

> 配套蓝图：[architecture-stage-18.md](architecture-stage-18.md) D4（五维逃逸面）· D5（数字注入）· 对应实现：`ChannelQuota` + `BudgetBook` 的 `CHANNEL` 维；占位源头：`ServiceAccount.monthlyTokenBudget`
> 上一篇：[stage-18-article-5-token-budget.md](stage-18-article-5-token-budget.md)
> 状态：✅ Stage 18 已完成（M18.2 钩子回收 + M18.5 示例装配；全仓 1148 全绿）
> 这是 Stage 18 系列的第 6 篇：50 人共享一份配额时，「我自己没用完」不再是有效辩解。

---

## 1. 我今天要解决什么问题

上一篇把 `RUN` 和 `USER` 两道闸讲清楚了。频道场景立刻打穿一维预算（五假设之三）：

> 一个频道 50 人共用一个 Agent。每个人的个人额度都还没满，频道账单已经先炸了。

这不是假设题。Stage 12 做频道共享 Agent 时，就把这个问题写成了占位：`ServiceAccount.monthlyTokenBudget`，哨兵值 `UNLIMITED_BUDGET = -1`，javadoc 写明「reserved for Stage 18」。六个阶段之后，本篇回收这根钩子。

标题里的「Claude Tag」是产品类比，不是依赖。Claude 的 Team/频道用量上限、企业 IM 里一个机器人的月度封顶，形态都一样：**账本的 key 是频道，不是成员。** 框架里对应的类型是 `ChannelQuota` + `BudgetDimension.CHANNEL`。

---

## 2. 为什么会有这个认知冲突

一维预算的辩解在共享场景里全部成立、全部无效：

```text
成员 A：我这个月才用了 800 token，限额 10k，你凭什么拒我？
成员 B：同上。
……
频道账单：50 × 800 = 40k，频道上限 50k，再来几轮就穿。

按 USER 闸：每个人都 Ok。
按 CHANNEL 闸：频道已经该 Warn，再投影就 Denied。
```

冲突的另一面是模块边界。最直接的写法是 `BudgetBook.requireBudget(ServiceAccount account, ...)`——观测层 import `agent-channel`，为读一个 `long` 拖进共享会话、身份解析、Ambient 整棵依赖树。Stage 15 用 `RequestContext` 显式传值、Stage 16 用 `executorFactory` 注入，已经否决过这类「为读一个字段而绑一个域」。

所以本篇要同时解决两个问题：频道这笔账记在哪，以及观测层凭什么不认识 `ServiceAccount`。

---

## 3. 它解决了什么问题

五个逃逸面，五道闸。上一篇展开了 RUN / USER，这里把整表立完：

```text
RUN      单次 run 防失控（经济闸，对照 17 [LIMIT] 行为闸）
USER     月度个人配额（15 CostLedger 已有语义）
TENANT   租户总额（一个客户拖垮平台资源的防线）
CHANNEL  频道级配额（12 ServiceAccount 占位的兑现：
         「我自己的额度没用完」不再是有效辩解——账本按频道记）
AGENT    服务身份级配额（销售 Agent 和工程 Agent 预算不同，
         12 身份隔离的经济面）
```

`CHANNEL` 维的 key 是 `channelId`（示例用 `"eng"`），不是 `accountId`，也不是某个成员的 `userId`。`AGENT` 维的 key 在示例里是 `"assist"`，和 `AgentConfig` 的名字对齐。一次 Run 的真实 token 会同时记进多维——同一笔用量，alice 的 USER、eng 的 CHANNEL、acme 的 TENANT、assist 的 AGENT 各加一份。任一维投影击穿，那一维回 `Denied`。成员不能用「我个人还没满」打开已经被打满的频道闸。

`AGENT` 维单独存在，是因为同一个频道里可以有多个服务身份：工程 Bot 和值班 Bot 不该抢同一条经济命。Stage 12 用 `AgentIdentity` 把「以谁的身份干活」隔开，Stage 18 用 `AGENT` 维把「以谁的身份花钱」隔开。身份隔离的经济面，缺了就会出现「这个 Agent 被另一个 Agent 的账单连坐」。

`ChannelQuota` 本身不做闸。它是纯数字容器：`channelId + monthlyTokenBudget`。闸在 `BudgetBook.budget(CHANNEL, channelId, monthlyTokenBudget)`。容器的存在，是为了让装配层有一个不带 channel 类型的翻译产物。

---

## 4. 核心抽象和架构

### 4.1 占位与兑现，跨 6 个阶段

```text
Stage 12  写下 ServiceAccount.monthlyTokenBudget
          UNLIMITED_BUDGET = -1
          hasBudgetCap() = (monthlyTokenBudget != -1)
          注释：reserved for Stage 18，v1 不强制

Stage 15  Tenant.monthlyTokenBudget 沿用同一哨兵
          CostLedger 在企业域消费 TENANT/USER
          观测层当时还不存在

Stage 18  ChannelQuota(channelId, monthlyTokenBudget)
          装配层：
            if (svc.hasBudgetCap()) {
                ChannelQuota q = new ChannelQuota(ch, svc.monthlyTokenBudget());
                book.budget(CHANNEL, q.channelId(), q.monthlyTokenBudget());
            }
          agent-observability 的 import 面：没有 agent-channel
```

这是「规划时复用预检、实施时钩子回收」的完整闭环。12 的笔记原文就写着「预算占位留 Stage 18」；18 的示例 T0 把这句话跑成一行打印：`ServiceAccount.monthlyTokenBudget -> ChannelQuota(eng, 50000)`。

### 4.2 `ChannelQuota` 的类型纪律

```java
public record ChannelQuota(String channelId, long monthlyTokenBudget) {
    // channelId blank → IAE
    // monthlyTokenBudget <= 0 → IAE
    // "no cap = do not construct a quota"
}
```

注意和 `ServiceAccount` 的哨兵**不要混用**：

```text
ServiceAccount.UNLIMITED_BUDGET = -1     合法，表示不限
ChannelQuota 拒绝 0 和 -1                不限 = 不要构造这个 record
BudgetBook 未配置                        limitOf = -1，requireBudget → Ok
```

三种「不限」同构：身份侧用 -1 占位，数字容器侧拒绝非正数，账本侧用缺席表示无限。`ChannelQuotaTest` 把「-1 不能进容器」锁死，就是防止有人把 `UNLIMITED_BUDGET` 原样塞进配额对象。

### 4.3 为什么观测层不 import channel（D5）

否决设计：`requireBudget(ServiceAccount account, long est)`。

裁决：模块间传数字，装配层传语义。对照：

- Stage 15：`RequestContext` 显式传递，enterprise 不把调用方身份焊进下层
- Stage 16：`executorFactory` 注入，tavern 不依赖执行器实现树
- Stage 18：`ChannelQuota` 注入，observability 不依赖频道身份树

收益是具体的：观测层测试不用构造 `AgentIdentity` / `IdentityScope` / 有效期窗口；channel 模块以后改预算模型（比如从月度改成滑动窗口），只要装配层翻译变了，`agent-observability` 不用重编译。

`BudgetAwareRouter` 侧同一纪律：`BudgetSnapshot` 只带 `remainingTokens` / `limitTokens`。频道余量、用户余量，对路由器都是一对数字。选哪一维当路由信号，是装配层的 `Supplier` 写什么。

---

## 5. 一次完整数据流

示例 T0 + T1 的频道路径：

```text
T0 装配
  ServiceAccount engAccount = new ServiceAccount(
          "svc-eng-bot-01",
          new AgentIdentity("eng-bot", "Engineering Bot", "team-eng-leads"),
          IdentityScope.capabilities("git.read"),
          50_000L,   // monthlyTokenBudget，hasBudgetCap() == true
          null, null);

  check(engAccount.hasBudgetCap());
  ChannelQuota engQuota = new ChannelQuota("eng", engAccount.monthlyTokenBudget());
  book.budget(CHANNEL, engQuota.channelId(), engQuota.monthlyTokenBudget());
  // 同时：USER alice 10k / TENANT acme 100k / AGENT assist 200k

T1 正常 Run，真实用量记四维
  book.recordUsage(USER,    "alice",  tokens);
  book.recordUsage(TENANT,  "acme",   tokens);
  book.recordUsage(CHANNEL, "eng",    tokens);
  book.recordUsage(AGENT,   "assist", tokens);

  控制台：
    user=.../10000  tenant=.../100000
    channel=.../50000  agent=.../200000
```

之后任何一次 `requireBudget(CHANNEL, "eng", est)` 用的都是频道累计，与 alice 个人余量无关。频道先到 80% 先 Warn（`BudgetAlarmEvent.dimension=CHANNEL`）；投影击穿先 Denied。alice 的 USER 维可能仍是 Ok。

T7 的 `CostDashboard` 从另一侧验证同一笔钱：tenant / channel / agent / user 四角合计各自等于权威总额。频道不是「再记一次就翻倍」，是同一事件的又一个视角。分账 API 和总账 API 是分开的（`record` vs `recordCost`），否则四维归因会把总额乘以 4——实现期坑，测试按「四角=总额」锁，不按「四角之和=总额」。

这和 Claude Tag / 企业 IM 机器人的月度上限是同一形态，只是产品名叫法不同：房间有一个共享额度，成员在房间里说话烧的是房间的账。框架不实现「谁在频道里说话算谁的分摊报表」——那是展示层。框架实现的是：下一次调用的投影是否击穿 `CHANNEL` 这道闸。分摊报表可以事后从四维导出里做，闸必须事前。

---

## 6. 最小代码或实验

装配层粘合的最小诚实形式（javadoc 与示例一致）：

```java
if (svc.hasBudgetCap()) {
    ChannelQuota quota = new ChannelQuota(channelId, svc.monthlyTokenBudget());
    book.budget(BudgetDimension.CHANNEL, quota.channelId(), quota.monthlyTokenBudget());
}
// 无帽：不构造 ChannelQuota，也不调用 budget(CHANNEL, ...)
// 该维 limitOf 保持 -1，requireBudget 永远 Ok，recordUsage 仍可累计
```

`ChannelQuotaTest` 三条：

```text
pureNumberContainer     eng + 50_000 往返
blank channelId         null / 空白 → IAE
nonPositiveBudget       0 / -1 → IAE（对位 ServiceAccount -1 约定）
```

`BudgetBookTest` 的「五维闸各自独立」保证：把 CHANNEL 用尽，USER 仍放行；把 USER 用尽，CHANNEL 仍按自己的 limit 判。共享 Agent 的逃逸面必须是「维独立」，不能是「任一维耗尽就误伤其他维的查询」，也必须允许「一维耗尽就挡住这一维的新投影」。

和 Stage 12 身份闸的顺序也要写清。`IdentityResolver` 的 fail-closed 五条件（未知 Agent、账号未生效、过期、用户不在频道、权限交集为空）决定 **Run 能不能启动**。`CHANNEL` 预算闸决定 **启动之后还能不能继续烧 token**。先身份、后预算：没有身份的调用不该进账本，没有预算的调用不该进模型。两道闸都是 fail-closed，但读者不同——身份事件进 `IdentityDecision`，预算预警进 `BudgetAlarmEvent`。

编译级证明：`agent-observability` 的 pom compile 依赖只有 `agent-core` + `agent-trace-export`。`ServiceAccount` 出现在 `examples` 模块，和 Stage 15/16/17 一样——装配依赖，不是库依赖。

---

## 7. 常见误区

1. **「给每个成员加大 USER 限额就等于频道配额」** —— 成员数一变，总和跟着变；频道上限是共享资源的硬顶，与人数解耦。Claude Tag 类产品卡的是房间，不是房间里每个人。
2. **「把 ServiceAccount 传进 BudgetBook」** —— 为读一个 long 引入整棵 channel 依赖。数字容器 + 装配层翻译，是 15/16/18 三次重复的同一裁决。
3. **「ChannelQuota(-1) 表示不限」** —— 容器拒绝非正数。不限是「不要构造、不要配置」，不是「构造一个哨兵配额」。哨兵属于 `ServiceAccount`，不属于 `ChannelQuota`。
4. **「频道账和用户账只能记一处，以免重复」** —— 记的是视角，不是复制账单。同一事件写入多维，对账看各维是否等于权威总额，不看各维是否互斥。
5. **「有了 CHANNEL 就可以撤掉 USER」** —— 一个人在频道里刷爆自己的月度配额，仍然该被 USER 维拦住。五维是五个逃逸面，缺一维就留一个口。

---

## 8. 和相邻概念的区别

```text
ChannelQuota vs ServiceAccount
  前者：观测层的数字容器，正数预算，无身份字段
  后者：频道域的凭证+配置，-1 表示不限，含 scope 与有效期
  翻译发生在装配层，方向单向

CHANNEL 维 vs USER 维
  key=channelId vs key=userId
  共享资源硬顶 vs 个人月度
  两者同时记、同时闸，不互相替代

BudgetBook vs CostLedger
  仍是 D4：通用五维 vs 企业两维
  频道维只存在于 BudgetBook
  企业域若要频道账，装配层桥，不反向依赖

ChannelQuota vs IdentityScope
  配额是经济数字；scope 是能力交集
  12 的身份闸决定能不能启动 Run
  18 的频道闸决定还能不能继续烧 token
  先身份、后预算，两道 fail-closed
```

---

## 9. 我的设计判断

最重的一条：**跨阶段占位必须回收，否则占位就是谎言。**

`UNLIMITED_BUDGET = -1` 如果永远停在 12，就是一句「我们想到了成本」的空头支票。18 用 `ChannelQuota` 把数字读出来、用 `CHANNEL` 维把闸加上、用示例把翻译写给后人看——钩子才算兑现。规划原文 11 篇里这篇专门存在，就是为了把这根跨 6 阶段的线讲完。

第二条：共享资源的账本 key 必须是共享单元。按人记账再汇总，是报表，不是闸。闸要在投影击穿的那一刻挡住下一次调用，来不及等月底出汇总。

第三条：D5 看起来像洁癖，实际是编译隔离。观测层 125 个测试里没有一个要 new `ServiceAccount`。channel 模块演进预算模型时，炸的是装配层，不是账本。下层不认识上层——这句话在 15 的 CostLedger 边界上说了一次，在 18 的 ChannelQuota 上再说一次，才会变成习惯而不是例外。Claude Tag 类产品要的频道上限，用这三样就够：正数容器、CHANNEL 维、装配层翻译。闸有了，耗尽之后怎么降级，是下一篇。

---

## 10. 面试表达

> 「频道级配额要解决的是共享 Agent 的逃逸面：50 个人都能说自己额度没用完，但频道是一份账单。Stage 12 的 ServiceAccount.monthlyTokenBudget 用 -1 占位，Stage 18 用 ChannelQuota 把正数预算注入 BudgetBook 的 CHANNEL 维。观测层不 import channel——模块传数字，装配层传语义。同一笔 token 同时记 USER / CHANNEL / TENANT / AGENT，各维独立击穿、独立 Warn。不限不是构造一个 -1 的配额对象，而是不要构造、不要配置。」

---

## 11. 下一篇连接什么

闸会 Warn、会 Denied，路由会在余量低于阈值时切 cheap。还缺一篇把三段式收成一张作战图：**预算耗尽时怎么办**——降级、拒绝、已耗成本入账，每一段为谁存在，以及为什么「切最便宜继续赊」不是策略。

→ [stage-18-article-7-degradation.md](stage-18-article-7-degradation.md)
