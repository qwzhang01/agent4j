# 成本仪表盘：按租户、频道、Agent、用户拆分

> 配套蓝图：[architecture-stage-18.md](architecture-stage-18.md) §3 第五组 / §4 D4 / 验收第 9 条 · 对应实现：`CostDashboard` / `CostMeter` / `PricingTable` · 全剧本：[ObservabilityExample.java](../examples/src/main/java/io/github/qwzhang01/agent/examples/ObservabilityExample.java) T7
> 上一篇：[stage-18-article-7-degrade-strategy.md](stage-18-article-7-degrade-strategy.md)
> 状态：✅ Stage 18 已完成（M18.5 版本与收口；全仓 1148 全绿）
> 这是 Stage 18 系列的第 8 篇。上一篇讲完预算吃紧时怎么降级，本篇回答降级之后的钱记在哪、怎么拆、如何对账。

---

## 1. 我今天要解决什么问题

`CostMeter` 已经能把一次模型调用换成 microUSD。值班和财务要的不是这一行，是四张视角不同的账单：

> 这个租户花了多少？这个频道花了多少？这个 Agent 花了多少？这个用户花了多少？四张表加起来，必须还是同一笔钱。

「拆开」很容易：每来一笔，往四个 key 各记一次。危险的是下一步——把四张表再加总，当成平台总账。同一笔钱被乘以 4。`CostDashboard` 的核心判断就是把这件事变成结构问题，而不是纪律问题：**总账与分账分离，各维合计等于总账，而不是四维之和等于总账。**

---

## 2. 为什么会有这个认知冲突

企业财务习惯一张总账、若干辅助核算。Agent 运行时却同时具备五个逃逸面（上一篇的 `BudgetDimension`）：一笔 token 既属于 alice，也属于 acme，也属于 eng 频道，也属于 assist 这个服务身份。四个标签同时为真。

如果仪表盘只有 `record(dimension, key, micros)` 一个入口，实现者几乎必然写出：

```text
totalCost() = Σ 所有分账行
```

`CostDashboardTest.totalIsNotTheSumOfAngles` 锁住的反例：一笔记 1000，同时记 TENANT 和 USER，错误实现会报 2000。初版就是这样写的，测试抓到后才改成总账 / 分账分离（蓝图 §18 坑 1）。

另一个冲突来自「仪表盘」这个词。规划原文要「成本仪表盘数据导出」。直觉反应是做一张前端图。v1 的裁决和 Stage 13 的 DAG 一样：框架给出数据出口（CSV / JSONL），UI 是前端域，不进库形态的 Runtime。谁要做成 Grafana 面板，读 JSONL 即可；谁要做成财务报表，自己接税率和合同价。框架若内置一张 HTML，反而把「数据对不对」和「图好不好看」绑死，验收会滑向截图。

第三个冲突是「按 Agent 拆」和「按模型拆」常被当成同一张表。模型名在 `ModelCallMetrics.model()`，已经进了指标行；仪表盘的 AGENT 维是服务身份（`assist` / `eng-bot`），不是 `premium` / `cheap`。降级之后同一 Agent 会先后走过两个模型，钱仍记在同一个 AGENT key 下，模型切换的解释在路由 reason 里。两张表能对上，是因为归因键稳定，不跟着路由跳。

---

## 3. 它解决了什么问题

`CostDashboard` 只做三件事：

```text
recordCost(micros)     —— 权威总额，每事件恰一次
record(dim, key, µ)    —— 某一视角的分账
exportCsv / exportJsonl —— 按维导出，插入序确定性
```

查询 API 与记账对称：`costOf(dim, key)` 读一格，没有记过的 key 回 0；`totalOf(dim)` 是这一维所有 key 之和，也就是「这一角看见的总账」；`keysOf(dim)` 给导出和测试提供插入序。零金额的 `record` 不物化 key，所以空用户不会在 CSV 里冒出一行 0——缺席比假零更适合对账。

对账纪律是结构性的：装配层把同一事件归因到四个维度时，

```text
totalOf(TENANT) == totalOf(CHANNEL) == totalOf(AGENT) == totalOf(USER) == totalCost()
```

`ObservabilityExample` T7 四条 `check` 就是这组等式。真实部署里，不相等就该告警——说明有事件漏归因或重复记了总账。

计价本身不在仪表盘里。`CostMeter` 用 `PricingTable` 做纯整数换算：`(tokens * price + 500_000) / 1_000_000`，round-half-up，不碰浮点。单价缺失对直接调用者 fail-loud（`IAE` 含模型名，不算假账）；`AttributionSink` 作为旁路，缺价 skip + warn，不炸 run。两纪律冲突时旁路优先，测试双双锁定。

---

## 4. 核心抽象和架构

```text
PricingTable  model → Price(input/output microUSD per 1M)
CostMeter     TokenUsage → microUSD（整数，缺价 IAE）
CostDashboard
  totalLedger                 权威总额
  breakdown[dimension][key]   四视角分账
  AttributionSink             一行接线：定价 + 固定归因上下文
```

四维复用 `BudgetDimension` 的词汇：`TENANT` / `CHANNEL` / `AGENT` / `USER`。`RUN` 维是防单次失控的闸，不进仪表盘拆分——单次 run 的钱已经在 `RunMetrics.costMicros` 里。

`AttributionSink` 是嵌套类，实现 `MetricsSink`。它假定「一个进程服务一组固定归因」（demo / 试点部署：tenant=acme、user=alice、channel=eng、agent=assist）。每次 `onModelCall`：

1. `meter.costMicros(metrics)` 算出这一笔
2. `dashboard.recordCost(cost)` 记总账一次
3. 对 attribution map 里每个 `(dimension, key)` 再 `record` 一次

工具调用 v1 不计费：token 成本活在模型调用上，`onToolCall` 空实现。这是诚实边界，不是疏忽。沙箱 CPU、审批等待、人工值班都不进这本账——`CostMeter` 是治理估算，不是财务口径。税率、币种、合同价蓝图 §12 明文域外。

导出契约：CSV 表头 `key,cost_micros`，一行一个 key；JSONL 每行 `{"dimension":"...","key":"...","cost_micros":...}`。插入序保序，测试有契约快照。空维导出只有表头 / 空文件，不编造 0 行。父目录不存在会 `createDirectories`，示例写到 `examples/target/`。

`RUN` 维为什么不进四角拆分：单次 run 的钱已经在 `RunMetrics.costMicros`，值班问的是「这一跑花了多少」。仪表盘问的是「这个月按租户 / 频道 / Agent / 用户怎么切」。五维闸和四维账单共用 `BudgetDimension` 词汇，职责不同——闸防逃逸，账单做归因。

---

## 5. 一次完整数据流

接上一篇 T1 / T3 的真实数字（`ObservabilityExample` 用 `AttributionSink` 自动入账）：

```text
T1 premium：800 input × $3/M + 200 output × $15/M = 5400 microUSD
T3 cheap：  500 input × $0.5/M + 150 output × $1.5/M = 475 microUSD
（中间还有 T5 投影 run 的 400 tokens 等，总账以 sink 实收为准）

T7 收口：
  dashboard.totalCost()
  == totalOf(TENANT)
  == totalOf(CHANNEL)
  == totalOf(AGENT)
  == totalOf(USER)
  导出 target/cost-dashboard-user.csv
       target/cost-dashboard-tenant.jsonl
```

蓝图 T7 草稿写过「tenant=acme 41,200 / alice 38k / bob 3.2k」。示例没有第二用户，对账断言改成「四角相等」，不编造 bob 的行。多用户拆分由 `record(USER, "bob", µ)` 自然出现——`keysOf(USER)` 按插入序给出 alice、bob。不要为了让演示「看起来像月报」而手工补一行：假行会让对账等式碰巧仍然成立，却把「谁真的花了钱」弄脏。

`CostDashboardTest.reconciliation` 用三笔 650 + 1500 + 4000 = 6150，四维各记一遍，断言四角都是 6150。这是仪表盘的单元级生命线，和示例 T7 同一条纪律。

---

## 6. 最小代码或实验

总账入口必须和分账入口分开用。漏掉 `recordCost`，`totalCost()` 会是 0，四角却有数——对账立刻红：

```java
CostDashboard dashboard = new CostDashboard();
dashboard.recordCost(1_000);                          // 权威总额，一次
dashboard.record(BudgetDimension.TENANT, "acme", 1_000);
dashboard.record(BudgetDimension.CHANNEL, "eng", 1_000);
dashboard.record(BudgetDimension.AGENT, "assist", 1_000);
dashboard.record(BudgetDimension.USER, "alice", 1_000);

assertEquals(1_000, dashboard.totalCost());           // 不是 4000
assertEquals(1_000, dashboard.totalOf(BudgetDimension.TENANT));
```

一行装配（示例 T0）：

```java
CostDashboard.AttributionSink dashboardSink = CostDashboard.attributionSink(
        meter,
        Map.of(BudgetDimension.TENANT, "acme",
                BudgetDimension.CHANNEL, "eng",
                BudgetDimension.AGENT, "assist",
                BudgetDimension.USER, "alice"));
```

空 attribution 拒：`attribution must not be empty - nothing would be booked`。负金额拒。0 金额不物化 key——缺席是诚实的，不冒充「这个用户花了 0」。

`CostMeter` 的量纲检查：`1M tokens × 2M microUSD/M = 2_000_000`，不是 4000。M18.2 测试侧踩过这个坑——断言先写「单价 × 百万 token 数」再写数字。

单价表本身也是装配期契约。`PricingTable.builder().price("premium", 3_000_000L, 15_000_000L)`：两个价格都是「每百万 token 的 microUSD」，非正价 IAE，同模型后写覆盖。示例 T0 的 premium 是 `$3 / $15 per M`，cheap 是 `$0.5 / $1.5 per M`，和 T1 的 5400、T3 的 475 对得上。

F6 把 fail-loud 写进验收剧本：`meter.costMicros(new ModelCallMetrics("mystery-model", ...))` 抛 `IAE`，消息含模型名。直接问价必须响；`AttributionSink` 碰上同一情况只 warn 并跳过——旁路不伤 run。两纪律并存，测试各锁一条：`CostMeterTest.missingPricingFailsLoud` 对 `MetricsCollectorTest.costWiringUnpricedSurvives`。

孤儿事件（没有 `beginRun` 的模型调用）进 `MetricsCollector` 的全局计数，但不进当次 `RunMetrics.costMicros`。run 汇总和账本职责分离：仪表盘按 sink 实收的模型调用入账，不挑 run。run 外烧的 token 也是 token。

---

## 7. 常见误区

1. **「四维加总 = 总账」** —— 多维归因的经典双计。同一事件会被记四次分账，总账只能记一次。
2. **「仪表盘是一张前端图」** —— v1 只导出 CSV/JSONL。对齐 Stage 13：DAG 只出描述标准，渲染器是产品域。
3. **「缺单价就算 0，别打断演示」** —— `CostMeter` 直接调用必须 fail-loud。静默 0 是假账。旁路（`AttributionSink` / `MetricsCollector`）才允许 skip。
4. **「用 double 算美元更直观」** —— microUSD 整数是纪律。浮点在累加百万次调用后漂移，对账等式先破。换算成美元是展示层的事。
5. **「CostDashboard 扩展 CostLedger」** —— Stage 15 的 `CostLedger` 是企业域特化（TENANT/USER）。观测层是它的下层，下层不认识上层。两本账可在装配层对齐，模块依赖绝不反向。
6. **「先做一张总览再拆」** —— 总览若用分账求和，拆之前就已经 ×4。顺序必须是：先有 `recordCost` 的权威总额，再谈拆。演示可以只导出 USER 一维，但测试必须锁四角相等。

---

## 8. 和相邻概念的区别

```text
CostDashboard（18）vs BudgetBook（18）
  BudgetBook 是事前闸 + 事后 token 账（还剩多少 token）
  CostDashboard 是钱的视角拆分（花了多少 microUSD）
  一个管能不能跑，一个管跑完怎么报

CostDashboard vs CostLedger（15）
  CostLedger：企业客服域，TENANT/USER，和 RequestContext 绑定
  CostDashboard：通用四维，不 import enterprise
  数字可对、类型不可并

CostDashboard vs RunMetrics.costMicros
  RunMetrics 是单次 run 的汇总行（值班一屏）
  CostDashboard 是跨 run 的维度累计（月底一张表）
  RunRecord 不单列 costMicros，避免和 metrics 行两处真相
```

和降级策略的衔接：T3 切 cheap 之后，仪表盘里能看见单价掉下来，reason 在路由日志里，钱在分账里。三份材料对同一事件，读者不同。

和 `BudgetBook` 也不要抢职责。账本记的是 token，仪表盘记的是 microUSD。同一笔 1000 token，premium 和 cheap 在账本里一样重，在仪表盘里差一个数量级。对账时先确认两本账的单位，再比数字。示例里 `bookUsage` 把 `RunMetrics.tokenUsage().totalTokens()` 记进四维 token 账，`AttributionSink` 并行把计价后的 microUSD 记进四维钱账——同一 run、两本账、两种量纲。

---

## 9. 我的设计判断

最重的一条：**聚合 API 先问「同一事件会被记几次」，再定 total 的语义。** 多维账本不是加几个 map 的事。初版 `totalCost()` 用分账求和，测试用「两视角一笔 1000」一击即溃。修成 `recordCost` 之后，对账从「实现者记得别加错」变成「不相等就是 bug」。

其次是单位。microUSD 让 `$3/M` 写成 `3_000_000`，所有乘除都在 long 里。蓝图写过溢出边界：现实单价远低于 `$4,300/M` 时，`tokens * price` 恒在 long 内。这不是过度设计，是「账本不允许近似」。

再次是「有意不做」。费率、税率、币种、合同价是财务域；Prometheus 时间序列是运维域；实时 UI 是前端域。v1 把数据出口做对，比做一张过期的图更值钱。多币种换算也是展示层：账本只认 microUSD，导出列只叫 `cost_micros`。谁要看成美元、人民币，自己除以 `1_000_000` 再乘汇率，不要倒灌进框架。

配额排队和削峰同样不做。预算耗尽即拒绝，是第 7 篇的诚实失败；「排到下个月再跑」是产品决策。仪表盘不因为有人在排队就把钱记成 0，也不预先占位。看见的每一分钱，都是已经发生的模型调用。

---

## 10. 面试表达

> 「成本仪表盘我没有做成前端，做成了四维对账出口。同一笔 token 同时属于租户、频道、Agent、用户，如果把四张分账再加总，总账会 ×4。所以 `recordCost` 是权威总额，每事件一次；`record` 是视角分账。对账断言是各维合计等于总账，不是四维之和等于总账。计价走 `CostMeter` 的 microUSD 整数，缺单价对直接调用者 fail-loud，不算假账。UI 留在前端域。」

---

## 11. 下一篇连接什么

账单能拆开，还回答不了「昨晚那批答错的 run，用的是哪一版 prompt、哪个模型、哪套工具」。下一篇拆版本三元组：`ComponentVersion` + `RunRegistry.byRunId` 的时间旅行，以及为什么 Prompt 灰度直接复用 Stage 13 的 `PromptManager`，不重做一套。

→ [stage-18-article-9-version-management.md](stage-18-article-9-version-management.md)
