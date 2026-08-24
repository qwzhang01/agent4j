# Stage 18 架构设计：可观测性、评估、成本治理与发布

> 对应阶段：Stage 18 - 可观测性、评估、成本治理与发布（18 周规划**收官阶段**：指标 / 成本与预算 / 模型路由 / 评估回归 / 版本与发布）
> 状态：📐 规划定稿（2026-08-24），**与 Stage 17 实施并行推进**——依赖正交裁决见 §0；实施记录见 §14 起
> 模块：新增 `agent-observability` Maven 模块，依赖 `agent-core`（ModelClient/ToolExecutor/Tool 契约 + `ModelResponse.TokenUsage`）+ `agent-trace-export`（评估读失败轨迹：`TrajectoryReplayer`/`Trajectory`/`SamplingPolicy`）；`agent-model`（MockModelClient）test scope。**不依赖 channel / enterprise / product / sandbox / workflow / scheduler / memory / mcp / orchestrator / tavern / coding**（D5 装配层粘合 + 四处有意不复用）
> 前置：Stage 1-16 已完成（全仓 885 测试全绿）；Stage 17 实施中（M17.1 ✅ 全仓 921 全绿，M17.2-M17.5 进行中）——本阶段与其无代码依赖交叉，可交错实施
> 定位：18 周规划的最后一块。前 17 个阶段回答"Agent 能不能跑"（能不能决策 1-2 / 能不能扩展 3 / 能不能安全 4·9 / 能不能持久 5-8 / 能不能协作 10-12 / 能不能产品化 13 / 能不能学习 14 / 能不能进企业·演戏·写代码 15-17），Stage 18 回答"**Agent 敢不敢上线运营**"。收官同时兑现 M9 里程碑（面试表达：5 分钟讲架构、15 分钟深模块、30 分钟全链路）

---

## 0. 与 Stage 17 的并行裁决（本蓝图立项背景）

历史首次两阶段并行。裁决依据是依赖图正交，不是时间盒宽容：

```text
Stage 17 线（agent-coding）：core + security + sandbox（契约复用，全部只读）
Stage 18 线（agent-observability）：core + trace-export（装饰器契约 + 失败轨迹读取）

交集 = agent-core，且两条线都承诺零存量改动（不动 core 一行）：
  17 用 Tool 契约（ReadFileTool implements Tool）
  18 用 ModelClient/ToolExecutor 契约（ObservingXxx 装饰器 implements 同接口）
  ——同一条契约的两个消费方向，互不可见
```

| 并行维度 | 裁决 | 说明 |
|---|---|---|
| 规划 | ✅ 并行（本蓝图即产物） | 18 蓝图现在定稿，17 继续实施 |
| 模块依赖 | ✅ 正交 | agent-coding 不依赖 observability，反之亦然；构建图无环 |
| 父 POM | ✅ 无实质冲突 | 各注册 modules + dependencyManagement 两行；"零存量改动"口径本就豁免父 POM 注册（15/16/17 先例） |
| 收口文档 | ✅ 各写各节 | README 两个规划小节、两份笔记、变更记录各条目 |
| 实施节奏 | ⚠️ 交错而非同刻 | 一人开发：17 收口优先（距三 Profile 收官最近），18 的 M18.1/M18.4 与 17 无任何依赖，可在 17 里程碑间隙穿插；不建议同日双模块写代码（认知切换税 > 并行收益——13 一天五里程碑、14/15/16+17规划一天四役的串行冲刺已被证明更快） |
| 验收口径 | ✅ 互不污染 | 17 验收"第三次零存量改动"；18 若发现必须动 core（理论不会——装饰器路线），必须等 17 收口后再裁决 |

**并行策略一句话**：规划并行定稿、实施交错推进、17 收口让路——两线唯一共享的是 agent-core 的稳定契约，而契约稳定正是前 17 个阶段零存量改动的回报。

---

## 1. 核心命题：前 17 阶段造的是"能跑的 Runtime"，Stage 18 造的是"敢上线的 Runtime"

Stage 1-17 有一个从未被挑战的隐含共识：**跑起来、答对了，就算成功**。Demo 里确实如此。生产环境里这个共识全线破裂：

```text
Demo 的五个隐含假设，在生产环境全部破裂：
1. 跑完即忘假设 -- 假设 Run 结束就结束了（答案给出去，故事就完了）
   生产里"昨晚 3 点那次失败 Run 用的什么模型、调了哪些工具、花了多少
   token、卡在哪一步"必须可回答——没有指标就没有可调试的生产系统，
   值班工程师只能对着日志 grep 玄学

2. token 免费假设 -- 假设模型调用没有成本（Mock 是零成本的，Demo 跑一百次也免费）
   生产里 token 是真金白银：一个没被 [LIMIT] 拦住的修复死循环（17）、
   一个被注入攻击诱导的刷量循环（9）、一个 Ambient 定时任务的静默膨胀（12）——
   每一个都是真实账单事故。预算必须事前有闸，事后看账单已是事故复盘

3. 一维预算假设 -- 假设按用户限预算就够了（一个用户一份额度）
   频道级共享 Agent（12）意味着一个频道 50 人共同烧一份配额——每个成员
   都能说"我自己的额度没用完"；租户域（15）意味着一个客户能拖垮平台资源。
   预算是多维的：单次 Run / 用户 / 租户 / 频道 / Agent 服务身份，缺一维就是一个逃逸面

4. 改了就好假设 -- 假设修改只会变好（改一版 prompt，目标 case 立刻见效）
   生产里 prompt 是全局共享的：一次"优化"让目标 case 变好的同时，可能让
   另外 30% 的边角 case 悄悄变坏——没有回归测试集的修改是盲改，
   而 Agent 的非确定性让"手动回归"彻底不可行（同 prompt 两次跑都不一样）

5. 版本透明假设 -- 假设永远只有一版（代码就一份，跑的就是它）
   生产里 prompt 有版本（13 PromptManager）、模型有版本、工具有版本——
   "这次答错了"必须能回答"当时用的是哪个组合"，否则复盘无从谈起：
   版本记录不是 bookkeeping，是可复现性的前提
```

Stage 18 的答案：**指标在边界不在路径**（装饰器捕获，零埋点税）、**预算是事前闸不是事后账单**（多维 fail-closed，预警与阻断分离）、**路由是策略不是框架**（选谁走是可插拔决策，且决策必须可解释）、**失败样本即回归集**（修一个 bug = 数据集 +1 条用例）、**版本三元组是可复现性前提**（prompt/model/tool 进 RunRecord）。

一句话（接 Stage 15/16/17 的递进叙事）：

```text
Stage 15 让 Agent 能进企业 -- 第一个领域 Profile：归属层（谁在问 / 哪个租户 / 花谁的钱）
Stage 16 让 Agent 能演戏   -- 第二个领域 Profile：世界层（角色有灵魂 / 说话有后果 / 一局有历史）
Stage 17 让 Agent 能写代码 -- 第三个领域 Profile：变更层（输出即变更 / 验证即测试 / 循环即收敛）
Stage 18 让 Agent 能运营   -- 运营层（不是第四个 Profile，是全部 Profile 的上岗证）：
         每次执行看得见（指标在边界），
         每个 token 记得上账（预算是事前闸），
         每次修改经得起回归（失败样本即回归集），
         每个版本退得回去（版本三元组）
```

### 与相邻概念的四条边界（面试高频）

```text
Metrics（18）vs AuditEvent（9）vs Trajectory（14）—— 一次 Run 的三种投影：
  Trajectory 是训练格式（S-A-O-R-D，读者是 RL 训练系统）
  AuditEvent 是治理格式（谁批的/拒的/净化了什么，读者是审计员）
  Metrics 是运营格式（延迟/token/成本/成功率，读者是值班工程师）
  三者共享同一组装饰器边界（Model 调用边界 / Tool 执行边界），
  区别不是数据量，是读者——同一事件流的三种投影，零重复埋点

BudgetBook（18）vs CostLedger（15）—— 通用账本与企业域特化：
  CostLedger 先落地（TENANT/USER 两维，企业客服域）
  BudgetBook 是模式通用化（RUN/USER/TENANT/CHANNEL/AGENT 五维）
  为什么不直接扩展 CostLedger：依赖方向反了——enterprise 不该被通用观测层
  反向依赖；观测层是企业域的下层基础设施，下层不认识上层（D4/D5）

RoutingModelClient（18）vs FallbackModelClient（1）—— "选谁"与"挂了换谁"：
  Fallback 管可用性：主模型抛错才切备选（被动、事后、异常路径）
  Routing 管经济性：调用前就决定走贵的还是便宜的（主动、事前、常态路径）
  组合形态：Routing 外层选人、Fallback 内层兜底——便宜模型也会挂，
  两层互补不替代（对照 9 治理链 + 17 白名单的纵深防御同构）

EvalDataset（18）vs 单元测试（全仓）—— 确定性与非确定性：
  单元测试断言代码逻辑（Mock 可控、结果确定、毫秒级）
  评估集断言模型行为（真 LLM 非确定、判定有阈值、按 token 计费）
  两者不可互相替代：单测护住框架代码，评估集护住 prompt/模型/工具组合——
  "改 prompt"这件事只有评估集能护住（D7）
```

---

## 2. 复用清单：Stage 18 是第六次「组装阶段」（预检先行）

延续 Stage 12 教训、13-17 制度化的做法：**规划时就做复用预检**。本清单每行标注预检结论，含四处「有意不复用」与两处「钩子回收」。

| 能力需求 | 已有设施（阶段） | Stage 18 做什么 | 复用预检 |
|---|---|---|---|
| 模型调用指标 | `RecordingModelClient`（14：边界捕获 usage/finishReason/durationMs） | **模式复现**：`ObservingModelClient`——同一装饰器手法，但投影目标是运营指标（sink 接口化，不绑定轨迹 JSONL 格式） | ✅ 模式复现，蓝图显式记录 |
| 工具调用指标 | `RecordingToolExecutor`（14）+ `AuditLogger`/`AuditEvent`（9：APPROVED/DENIED/EXECUTED/FAILED/SANITIZED） | **模式复现**：`ObservingToolExecutor`（延迟/成功率/denied 计数）——审计管"该不该调"，指标管"快不快、成了没"，读者不同不合并 | ✅ 模式复现 |
| Run 级 Trace | `Trajectory`/`TrajectoryRecorder`/`ReplayView`（14：S-A-O-R-D + messages 双通道 + 走录回放） | **不另造 Trace 系统**：运营视角的"完整 Trace"= RunMetrics 汇总行 + 轨迹文件引用；三种投影共用同一 run 边界 | ✅ 直接兑现（D1） |
| Token 数据 | `ModelResponse.TokenUsage`（core，prompt/completion/total）+ `TrajectoryMetadata` 汇总（14） | CostMeter 从 usage 算钱；RunMetrics 聚合三项 token | ✅ 直接兑现 |
| 事前预算闸 | `CostLedger` + `BudgetExceededException`（15：TENANT/USER 两维 requireBudget fail-closed） | **模式通用化**：BudgetBook 五维（RUN/USER/TENANT/CHANNEL/AGENT）——CostLedger 保持企业域不动，不反向依赖（D4） | ✅ 模式复现（不 import enterprise） |
| 频道预算数据 | `ServiceAccount.monthlyTokenBudget` + `hasBudgetCap()`（12：占位 UNLIMITED=-1 **显式留 Stage 18**） | **钩子回收**：装配层读出预算数字构造 ChannelQuota——预算数字注入而非身份依赖（D5） | ✅ 装配层粘合（不 import channel） |
| 模型降级链 | `FallbackModelClient`（1：多级链式降级）+ Retry/Timeout（1） | RoutingModelClient 在其上加路由决策（事前选人）；Fallback 保留作内层兜底——组合形态 Routing(Fallback(premium, cheap)) | ✅ 模式扩展（组合不修改） |
| Prompt 版本/灰度/回滚 | `PromptManager` + `PromptVersion` + `PromptChannel`（stable/canary）+ 租户路由 + 指针回滚（13） | **不重做**：RunRecord 引用 PromptManager 的版本号；"发布"验收 = 13 已有的 canary 通道 + 18 新增的回归门禁（门禁不过不上 stable） | ✅ 直接兑现（D8） |
| 失败样本回收 | `TrajectoryReplayer.loadAll`（14：JSONL 加载坏行带行号）+ `SamplingPolicy`（状态集过滤）+ `RewardSource`（14：reward 阈值） | EvalDataset.importFailures：doneReason=ERROR/MAX_STEPS_EXCEEDED 或 reward 低于阈值的轨迹 → EvalCase（originRunId 可溯） | ✅ 直接兑现 |
| 判定非确定性对照 | `PreferenceAnnotationExample` 双 rollout（14） | 评估报告的失败明细借鉴 describeStep 走查渲染手法 | ✅ 手法复用 |
| 装饰器哲学 | `GovernedToolExecutor`（9）/ `RecordingModelClient`（14）/ Retry/Timeout/Fallback（1） | ObservingXxx 装饰器——组合优于修改的第 N 次兑现：不动 `ReActAgentLoop` 一行 | ✅ 直接兑现 |
| Mock 验收 | `MockModelClient` scripted `respondText`/`respondToolCalls`（1） | premium/cheap 双 Mock 实例演示路由切换；scripted 失败 run 产出失败轨迹 | ✅ 同 Stage 8-17 手法 |

### 存量改动清单（预检裁决：零）

**目标：延续零存量改动**（15 两处枚举加法 / 16 零 / 17 目标零——本阶段同样目标零）。指标捕获、预算闸、路由决策、评估判定、版本记录全部是 observability 模块内部概念；ModelClient/ToolExecutor 装饰器、装配层注入兑现既有占位（ServiceAccount 预算数字）——**"可观测、可治理"的规划总目标，用既有契约拼装即可兑现，一个存量类都不用改**。

若实现中发现需要动存量：先停下来核对是否真有必要（能靠组合解决的绝不改存量）；确需加法的，在本节回填并说明向后兼容性，且**必须避开 Stage 17 收口窗口**（并行裁决 §0）。

**依赖方向**：`agent-observability -> agent-core + agent-trace-export`（compile）；`agent-model`（test scope）。零新第三方依赖（Jackson 已由 core 传递；**OpenTelemetry 不引入**，见 D9）。

### 四处「有意不复用」

| # | 不复用什么 | 为什么 | 去向 |
|---|---|---|---|
| 1 | OpenTelemetry SDK | 零新第三方依赖纪律；OTel 价值在接入既有观测体系（Prometheus/Jaeger/Grafana），本阶段验收（Trace 查看/成本导出/仪表盘数据）MetricsSink + JSONL 即可兑现；先稳定自有接口，adapter 是薄壳 | D9；OTLP exporter 留 v2 |
| 2 | CostLedger 直接扩展 | 依赖方向：enterprise 不该被通用观测层反向依赖——观测层是企业域的下层基础设施，下层不认识上层 | D4；BudgetBook 内建五维 |
| 3 | ServiceAccount 身份绑定 | 预算数字注入而非身份依赖：observability 不 import agent-channel（对照 15 RequestContext 显式传递、16 executorFactory 注入先例） | D5；装配层粘合 |
| 4 | LLM-as-judge 评估 | 引入 LLM 判定 = 引入非确定性 + 评估自身成本——回归门禁必须可复现（同 dataset 同报告），先让确定性断言（Exact/Contains/预算/工具次数）护住发布线 | v2；判定器接口留槽 |

---

## 3. 核心抽象（26 个，五组）

### 第一组：指标（metrics 包，M18.1）

| 抽象 | 层 | 一句话 |
|---|---|---|
| `MetricsSink` | 核心 | 观测事件出口接口：onModelCall / onToolCall / onRun / onAlarm——实现方决定去哪（内存/日志/JSONL/未来 OTLP） |
| `ModelCallMetrics` | 数据 | record：model + promptTokens/completionTokens/totalTokens + latencyMs + finishReason + error——Model 调用边界的全部运营事实 |
| `ToolCallMetrics` | 数据 | record：toolName + latencyMs + success + denied + error——Tool 执行边界（denied 单列：被拒也是可观测信号，denied is intelligence 对齐 9 D6） |
| `RunMetrics` | 数据 | record：runId + agentName + status + doneReason + durationMs + modelCallCount + toolCallCount + tokenUsage + costMicros——run 级汇总行（值班工程师的一屏答案） |
| `ObservingModelClient` | 核心 | `implements ModelClient` 装饰器：测延迟、读 usage、记 finishReason；异常记完照抛（对照 14 RecordingModelClient"捕获后上抛不吞"） |
| `ObservingToolExecutor` | 核心 | `implements ToolExecutor` 装饰器：测延迟、记成败与 denied；指标是旁路，绝不改变执行语义 |
| `MetricsCollector` | 核心 | 边界事件 → run 级聚合：runStarted/runFinished 标记 + RunMetrics 物化 + 内存查询（按 run / 按 agent / 成功率统计） |

### 第二组：成本与预算（cost 包，M18.2）

| 抽象 | 层 | 一句话 |
|---|---|---|
| `PricingTable` | 数据 | model → 单价表（input/output 每百万 token，microUSD 整数——避免浮点；装配期可配） |
| `CostMeter` | 核心 | `TokenUsage` → microUSD 换算器（单价缺失 fail-loud：不算假账，IAE "no pricing for model"） |
| `BudgetDimension` | 数据 | enum：RUN / USER / TENANT / CHANNEL / AGENT——五个逃逸面五道闸 |
| `BudgetCheck` | 数据 | sealed 三态：OK / WARN（达预警线，不阻断）+ / DENIED（耗尽，fail-closed）——预警与阻断分离的类型层落地 |
| `BudgetBook` | 核心 | 多维预算账本：requireBudget(dimension, key, estTokens) 事前闸 + recordUsage 事后记账 + remainingOf 查询（供路由读余量）——CostLedger 模式的五维通用化 |
| `ChannelQuota` | 数据 | record：channelId + monthlyTokenBudget——**预算数字容器**（装配层从 ServiceAccount.hasBudgetCap()/monthlyTokenBudget() 读出构造；不 import channel） |

### 第三组：模型路由（routing 包，M18.3）

| 抽象 | 层 | 一句话 |
|---|---|---|
| `ModelRouter` | 核心 | 路由策略接口：route(request, budgetSnapshot) → RouteDecision——选谁走是策略不是框架，实现可插拔 |
| `RouteDecision` | 数据 | record：modelId + reason——**reason 必填**（"为什么这单走了 cheap"是成本对账的一部分，路由必须可解释） |
| `RoutingModelClient` | 核心 | `implements ModelClient` 装饰器：持 N 个 ModelClient + router，调用前问路由、转发选中者；与 Fallback（1）组合为 Routing(Fallback(…)) 纵深 |
| `BudgetAwareRouter` | 核心 | `implements ModelRouter` 默认策略：余量充足走 premium、余量低于阈值走 cheap、耗尽抛 BudgetExceededException——经济性与可用性的第一道交汇 |

### 第四组：评估回归（eval 包，M18.4）

| 抽象 | 层 | 一句话 |
|---|---|---|
| `EvalCase` | 数据 | record：caseId + prompt + expectation + originRunId——**来源可溯**（从哪次失败 Run 提取的，bug 与用例的谱系） |
| `Expectation` | 数据 | sealed 判定器：ExactMatch / Contains / MaxTokens / ToolCallCount——v1 确定性断言（可复现是门禁的生命线）；judge 槽位 v2 |
| `EvalDataset` | 核心 | case 集合：JSONL load/save + `importFailures(trajectories)`（doneReason=ERROR/MAX_STEPS 或低 reward 过滤）——失败样本回收的落点 |
| `EvaluationRunner` | 核心 | 批量重放：对每 case 跑 Agent → 判定 → 聚合——评估用的 Agent 由装配层注入（Mock 可测、真模型可评估） |
| `EvalReport` | 数据 | record：成功率 + 通过/失败明细 + 基线对比 + 门禁 verdict（PASS/FAIL/BASELINE_ABSENT）——基线缺失诚实标注，不假装对比 |

### 第五组：版本与发布（version 包，M18.5）

| 抽象 | 层 | 一句话 |
|---|---|---|
| `ComponentVersion` | 数据 | record：kind（PROMPT/MODEL/TOOL）+ name + version + channel——版本三元组的原子 |
| `RunRecord` | 数据 | record：runId + agentName + List&lt;ComponentVersion&gt; + RunMetrics 摘要 + costMicros——"这次 Run 用了什么组合"的 SSOT |
| `RunRegistry` | 核心 | append-only 注册表：record(run, versions) + byRunId / byAgent 查询——"昨晚那次答错用的什么版本"从此有答案 |
| `CostDashboard` | 核心 | 按维度拆分导出：tenant / channel / agent / user 四维聚合 → CSV/JSONL——成本仪表盘的数据出口（仪表盘本身是前端域，不进 v1） |

### 3.1 关键接口草图

```java
// ---- 指标（第一组）----
public interface MetricsSink {
    void onModelCall(ModelCallMetrics metrics);     // Model 边界
    void onToolCall(ToolCallMetrics metrics);       // Tool 边界
    void onRun(RunMetrics metrics);                 // run 级汇总
    void onAlarm(BudgetAlarmEvent alarm);           // 预算预警（WARN 级，非阻断）
}

public final class ObservingModelClient implements ModelClient {
    // 包装 delegate：sync/streaming 全透传；延迟在调用边界测、
    // usage 在响应边界读、异常记完照抛（指标是旁路，绝不吞错）
}

// ---- 成本与预算（第二组）----
public final class BudgetBook {
    public BudgetCheck requireBudget(BudgetDimension dim, String key, long estTokens);
        // OK / WARN(thresholdAt 0.8) / DENIED——WARN 不阻断只发事件，DENIED fail-closed
    public void recordUsage(BudgetDimension dim, String key, long actualTokens);
        // 事后记账：真实 usage 回填（估算与实际的差值诚实入账）
    public long remainingOf(BudgetDimension dim, String key);
        // 供 BudgetAwareRouter 读余量
}

public record ChannelQuota(String channelId, long monthlyTokenBudget) {
    // 装配层构造：ServiceAccount svc = ...;
    // if (svc.hasBudgetCap()) quota = new ChannelQuota(ch, svc.monthlyTokenBudget());
}

// ---- 模型路由（第三组）----
public interface ModelRouter {
    RouteDecision route(ModelRequest request, BudgetSnapshot budget);
        // reason 必填——路由决策可解释是对账的前提
}

public final class RoutingModelClient implements ModelClient {
    // Map<String, ModelClient> candidates + ModelRouter router
    // 调用前 route() → 转发选中 client；与 Fallback 组合：Routing(Fallback(premium, cheap))
}

// ---- 评估回归（第四组）----
public final class EvalDataset {
    public static EvalDataset load(Path jsonl);
    public int importFailures(List<Trajectory> trajectories, double minReward);
        // doneReason in {ERROR, MAX_STEPS_EXCEEDED} 或 reward < minReward 的轨迹
        // → 每条一个 EvalCase（originRunId = trajectory.runId）
}

public record EvalReport(double passRate, List<CaseResult> results,
                         EvalReport baseline, Verdict verdict) {
    public enum Verdict { PASS, FAIL, BASELINE_ABSENT }
        // 门禁语义：跌破阈值 = FAIL；无基线 = BASELINE_ABSENT（建基线，不假对比）
}

// ---- 版本与发布（第五组）----
public record ComponentVersion(Kind kind, String name, String version, String channel) {
    public enum Kind { PROMPT, MODEL, TOOL }
}

public final class RunRegistry {
    public void record(String runId, List<ComponentVersion> versions, RunMetrics metrics);
    public Optional<RunRecord> byRunId(String runId);   // "昨晚那次"的答案
    public List<RunRecord> byAgent(String agentName);
}
```

---

## 4. 关键设计决策（9 个）

### D1. 一次 Run 三种投影：不另造 Trace 系统

```text
现状盘点：一次 Run 已经有两个观测系统在采集——
  Stage 14 RecordingModelClient/RecordingToolExecutor → Trajectory（训练格式）
  Stage 9 AuditLogger → AuditEvent（治理格式）
Stage 18 若再造第三套采集，就是同一边界埋三次点的荒谬。

裁决：Metrics 是同一组边界的第三种投影，不是第三套采集。
  边界只有两个（Model 调用边界 / Tool 执行边界），投影按读者分：
    读者是 RL 训练系统 → S-A-O-R-D 轨迹（14）
    读者是审计员       → 审批与拒绝事件（9）
    读者是值班工程师   → 延迟/token/成本/成功率（18）
  三种投影零重复埋点：都在装饰器边界，各自独立订阅、互不干扰

面试表达：三者的区别不是数据量，是读者。同一事件流，
  训练系统读轨迹、审计员读事件、值班工程师读指标——
  一个成熟的 Runtime 不是把所有观测数据塞进一个大表，
  而是让每类读者拿到自己视角的投影
```

### D2. 指标在边界不在路径：装饰器第 N 次兑现

```text
延迟在哪里测：调用边界（ObservingModelClient.invoke 前后）——
  不是在 ReActAgentLoop 里埋点。为什么：
  1. loop 是路径，路径会分叉（workflow 节点里的 AgentNode、scheduler 恢复的
     run、channel 共享会话——埋在路径就要追着每条路径埋）
  2. 边界是收口：所有路径最终都过 ModelClient/ToolExecutor 两个边界
  3. 零存量改动：不动 loop 一行，指标能力对所有既有 Agent 即插即用

装饰器谱系（组合优于修改的编年史）：
  Stage 1  Retry/Timeout/Fallback   —— 可用性装饰器
  Stage 9  GovernedToolExecutor     —— 治理装饰器
  Stage 14 RecordingXxx             —— 训练数据装饰器
  Stage 18 ObservingXxx             —— 运营指标装饰器
  同一哲学第四次实体化：能力加在边界上，路径保持愚蠢
```

### D3. 预算是事前闸不是事后账单：预警与阻断分离

```text
预算闸的三段式（对照 Claude Tag / 企业 FinOps 的共同形态）：
  80%  预警（WARN）—— BudgetAlarmEvent 进 sink，run 继续
       预警是给人看的：让管理员在还能挽回时知道趋势
  阈值 降级（DEGRADE）—— BudgetAwareRouter 切便宜模型（M18.3）
       降级是自动的：不是拒绝服务，是降低单次成本密度
  100% 阻断（DENIED）—— requireBudget 抛 BudgetExceededException
       阻断是诚实的：fail-closed，宁可拒绝也不赊账

为什么预警不阻断：预警若阻断，阈值就形同虚设（管理员只会把预警线
  调到 99% 消除打扰）——预警的职责是"被看见"，阻断的职责是"守住底线"，
  两个职责两种机制，混在一起两个都做不好（对照 12 NoisePolicy 的
  频控/分级/预算/静默窗四道闸：噪音控制的敌人是打扰，预算控制的敌人是失控）

记账是事后的：requireBudget 用估算 token（request 大小近似），recordUsage
  回填真实 usage——估算闸够快，真实账够准，差值诚实入账不抹平
```

### D4. 预算是多维的：五个逃逸面五道闸

```text
RUN      —— 单次 run 防失控（死循环修复/注入刷量：17 的 [LIMIT] 是行为闸，
            RUN 预算是经济闸，同一条战壕的两个哨位）
USER     —— 月度个人配额（15 CostLedger 已有语义）
TENANT   —— 租户总额（15 已有；一个客户拖垮平台资源的防线）
CHANNEL  —— 频道级配额（12 ServiceAccount 占位的兑现：50 人共享一份配额时，
            每个成员"我自己的额度没用完"不再是有效辩解——账本按频道记）
AGENT    —— 服务身份级配额（一个销售 Agent 和一个工程 Agent 预算不同——
            身份隔离（12）的经济面）

为什么 BudgetBook 内建而不扩展 CostLedger（15）：
  依赖方向——enterprise 是企业域，observability 是它的下层基础设施；
  下层不认识上层。CostLedger 保持不动，是企业域的特化实现；
  BudgetBook 是通用账本，五维对称，企业装配层可以把两者桥接
  （同一个记账 sink，两本账对齐），但模块依赖绝不反向
```

### D5. 预算数字注入而非身份依赖：observability 不认识 channel

```text
反例设计（否决）：BudgetBook.requireBudget(ServiceAccount account, ...)
  —— observability import agent-channel，为读一个 long 字段拖进整个
  频道共享会话/身份解析/Ambient 依赖树。

裁决（对照 15 RequestContext 显式传递、16 executorFactory 注入先例）：
  ChannelQuota(channelId, monthlyTokenBudget) —— 纯数字容器
  装配层（examples / 未来 Factory）负责翻译：
    ServiceAccount.hasBudgetCap() ? new ChannelQuota(ch, monthlyTokenBudget()) : 无闸
  依赖最小化收益：observability 只依赖 core + trace-export，
  测试不需要构造任何 channel 域对象；channel 模块演进（预算模型升级）
  不触发 observability 重编译

模式名：装配层粘合（同 15 EnterpriseAgentFactory 把 memory/security/workflow
  粘起来而 enterprise 不依赖其组合根）——模块间传数字，装配层传语义
```

### D6. 路由是策略不是框架，且决策必须可解释

```text
RoutingModelClient 只做一件事：调用前问 router、转发选中者。
  谁被选中是 ModelRouter 实现的事——v1 给 BudgetAwareRouter（经济性），
  v2 可以有 TaskComplexityRouter（任务标记）、LatencyAwareRouter（SLA）、
  AvailabilityRouter（健康度）——路由策略是配置不是代码结构。

RouteDecision.reason 必填的三个理由：
  1. 成本对账：月底账单里 30% 流量走了便宜模型，"为什么"必须有答案——
     reason="budget remaining below 25%" 每一行可审计
  2. 事后归因：某批回答质量差，回查发现路由在那段时间切了 cheap——
     没有 reason，这就是一场不可复现的玄学事故
  3. 对齐 12 IdentityDecision / 9 AuditEvent 的裁决传统：
     决策必须留痕，denied/routed 都 is intelligence

与 Fallback（1）的关系：Fallback 管可用性（挂了换谁，被动事后），
  Routing 管经济性（一开始走谁，主动事前）。组合形态
  Routing(Fallback(premium, cheap))：外层按预算选人、内层挂了兜底——
  两层互补，谁也不替代谁（对照 9 治理链 + 17 白名单：纵深防御的又一次分形）
```

### D7. 失败样本即回归测试集：修一个 bug = 数据集 +1 条用例

```text
传统软件：修 bug 先写单测（回归防线），再修代码。
  Agent 的难题：bug 多数不在 Java 代码里，在 prompt/模型/工具组合里——
  单测断言不了"这次回答蠢不蠢"。

裁决：评估集就是 Agent 的回归测试集，失败轨迹就是用例来源。
  importFailures(Trajectory)：doneReason=ERROR/MAX_STEPS_EXCEEDED 的 run
    → 取首条 USER 消息为 prompt
    → 取当时的失败形态构造 expectation（如终答不应包含 X / 应包含 Y）
    → originRunId 指回原轨迹——用例与事故的谱系不断
  EvaluationRunner：改完 prompt/模型后重放全量 dataset → EvalReport
  门禁：passRate 跌破阈值 = FAIL → 阻止发布（与 13 canary 组合：
    门禁不过不上 stable 通道——发布流水线的最小闭环）

为什么判定器 v1 只做确定性断言（Exact/Contains/MaxTokens/ToolCallCount）：
  LLM-as-judge 本身非确定 + 有成本——门禁的生命线是可复现
  （同 dataset 同报告），先用确定性断言护住发布线，judge 槽位 v2 再开
```

### D8. 版本三元组是可复现性前提：RunRecord 回答"当时用的什么"

```text
复盘的标准问题："昨天那批答错的 run，用的是哪个 prompt 版本、哪个模型、
  哪套工具？"——没有版本记录，这个问题无解，一切归因都是猜。

ComponentVersion(kind, name, version, channel)：
  PROMPT —— 复用 13 PromptManager 的版本号与 stable/canary 通道
            （不重做：装配层从 PromptManager.resolve 拿到版本顺手 record）
  MODEL  —— 装配层声明（"gpt-4o" / "mock-premium"——版本随部署记录）
  TOOL   —— 装配层声明（工具集指纹：name@version 列表）

RunRecord = runId + 三元组 + RunMetrics 摘要 + costMicros：
  时间旅行查询：byRunId(runId) → "那次用的是 prompt v3 canary + gpt-4o-mini"
  这也是 14 TrajectoryMetadata（agentName + promptSha256 + 工具清单）的
  运营视角补全：轨迹存指纹（训练消费），RunRecord 存可读版本（人消费）
```

### D9. OpenTelemetry 诚实边界：先稳定接口，adapter 是薄壳

```text
技术基线写了 OpenTelemetry，本阶段裁决：v1 不引入 OTel SDK。
  理由 1（纪律）：零新第三方依赖——17 刚兑现的纪律在收官阶段破例
    是坏示范；OTel API+SDK 是一整棵依赖树
  理由 2（价值错位）：OTel 的价值在接入既有观测体系（Prometheus/
    Grafana/Jaeger）——本项目是库形态，没有"既有体系"可接；
    验收要求的 Trace 查看/成本导出/仪表盘数据，MetricsSink + JSONL 全额兑现
  理由 3（顺序）：先定义稳定的自有接口（MetricsSink 四事件），让语义
    收敛；OTLP exporter 是纯翻译层（sink 事件 → OTel span/metric），
    接口稳定后 adapter 一天写完；反过来先绑 OTel 数据模型，
    语义被外部格式绑架

v2 承诺的形态：otel-exporter 子模块（可选依赖），MetricsSink 的
  第三方实现——用户引了它才传递 OTel 依赖，不引则零成本
```

---

## 5. 分层架构图

```text
┌─────────────────────────────────────────────────────────────────────────┐
│ examples: ObservabilityExample（全链路验收剧本 T0-T7，零 LLM）            │
│   装配层粘合：ServiceAccount 预算数字→ChannelQuota · PromptManager 版本号  │
└──────────────────────────────────┬──────────────────────────────────────┘
                                   │
┌──────────────────────────────────▼──────────────────────────────────────┐
│ agent-observability（Stage 18 新增）                                     │
│                                                                         │
│  metrics/   MetricsSink / ModelCallMetrics / ToolCallMetrics /          │
│             RunMetrics / ObservingModelClient / ObservingToolExecutor   │
│             / MetricsCollector                                          │
│             —— D1/D2：指标在边界不在路径，三种投影零重复埋点              │
│  cost/      PricingTable / CostMeter / BudgetDimension / BudgetCheck    │
│             / BudgetBook / ChannelQuota                                 │
│             —— D3/D4：事前闸五维 fail-closed，预警与阻断分离            │
│  routing/   ModelRouter / RouteDecision / RoutingModelClient            │
│             / BudgetAwareRouter                                         │
│             —— D6：路由是策略，决策必须可解释（reason 必填）             │
│  eval/      EvalCase / Expectation / EvalDataset / EvaluationRunner     │
│             / EvalReport                                                │
│             —— D7：失败样本即回归集，门禁可复现                          │
│  version/   ComponentVersion / RunRecord / RunRegistry                  │
│             / CostDashboard                                             │
│             —— D8：版本三元组，"当时用的什么"从此有答案                  │
└────┬──────────────────────────────┬─────────────────────────────────────┘
     │ compile                      │ compile（评估读失败轨迹）
┌────▼───────────────┐   ┌──────────▼─────────────────────────────┐
│ agent-core         │   │ agent-trace-export（14）               │
│ ModelClient        │   │ Trajectory / TrajectoryReplayer        │
│ /ToolExecutor      │   │ / SamplingPolicy / RewardSource        │
│ /Tool              │   │ （失败样本回收的数据源；D1 轨迹投影）   │
│ /TokenUsage        │   └─────────────────────────────────────────┘
└────────────────────┘
  agent-model（MockModelClient premium/cheap 双实例）= test scope；
  channel / enterprise / product / sandbox / workflow / scheduler / memory /
  mcp / orchestrator / tavern / coding 一概不依赖（D5 装配层粘合 + §2 四处不复用）
```

数据流（一次被观测、被计费、被路由的 Run）：

```text
用户 ──prompt──▶ RoutingModelClient
                    │ route(request, budgetSnapshot)
                    ├── 余量充足 → RouteDecision(premium, "budget healthy")
                    └── 余量吃紧 → RouteDecision(cheap, "remaining 12% < 25%")
                    │
              ObservingModelClient（延迟/token/finishReason → sink）
                    │
              ReActAgentLoop（零改动，路径保持愚蠢）
                    │
              ObservingToolExecutor（工具延迟/成败/denied → sink）
                    │
        BudgetBook.recordUsage(USER/CHANNEL/TENANT/AGENT, actualTokens)
                    │                        ↑
        CostMeter: usage × PricingTable → costMicros ── CostDashboard 累计
                    │
              run 结束 → MetricsCollector 物化 RunMetrics
                    │
        RunRegistry.record(runId, [prompt@v3/canary, model=premium, tools@f1])
                    │
        三种投影同源对照（D1）：
          Trajectory JSONL（14，训练）· AuditEvent 流（9，治理）· RunMetrics 行（18，运营）
```

---

## 6. 完整时序：一个运营日的剧本

```text
T0: 装配（一次性）
    PricingTable：premium in $3/M out $15/M；cheap in $0.5/M out $1.5/M
    BudgetBook：
      USER     alice   月 10k tokens（warnAt 80%）
      CHANNEL  eng     月 50k tokens（从 ServiceAccount.hasBudgetCap() 读出）
      TENANT   acme    月 100k tokens
      RUN      每次上限 2k tokens（防单次失控）
    RoutingModelClient：
      candidates = {premium: MockA, cheap: MockB}
      router = BudgetAwareRouter(cheapBelowRemaining = 25%)
      组合形态：Routing(Fallback(premium, cheap))——路由选人，挂了兜底
    双装饰器：Observing(Routing(Fallback(...))) + ObservingToolExecutor
    sink = InMemory（测试断言）+ Console（人看）
    RunRegistry + PromptManager（13，prompt v3 stable）

T1: 正常 Run（看得见）
    alice："帮我总结这份报告"
    → 路由 premium（reason="budget healthy"，余量 100%）
    → 指标流：modelCall(120ms, 800+200 tokens) / toolCall(summarize, 45ms, ok)
    → RunMetrics 物化：SUCCEEDED / 900 tokens / 165ms / cost 5400 microUSD
    → BudgetBook 四维记账（USER alice +900 / CHANNEL eng +900 / TENANT acme +900 / AGENT +900）

T2: 阈值预警（预警不阻断）
    alice 继续跑批，USER 用量到 8.3k/10k = 83%
    → requireBudget 返回 WARN → BudgetAlarmEvent 进 sink（onAlarm）
    → run 正常继续完成——预警的职责是"被看见"，不是"被拦住"
    → 控制台告警：[BUDGET-WARN] user=alice 83% of 10k

T3: 预算降级路由（花得起——自动切换）
    alice 余量 1.8k/10k = 18% < 25%
    → BudgetAwareRouter 切 cheap：RouteDecision(cheap, "remaining 18% < 25%")
    → 这单便宜 10 倍——降级不是拒绝服务，是降低成本密度
    → 账单里每个 modelCall 都带 reason，月底对账行行可解释

T4: 预算阻断（守住底线）
    alice 余量耗尽，新 run 的 requireBudget → DENIED
    → BudgetExceededException（USER, used=10000, limit=10000）
    → 诚实失败：宁可拒绝也不赊账（对照 15 企业域同款语义）

T5: 失败样本回收（改得动——数据从哪来）
    scripted 一条失败 run（工具异常 → doneReason=ERROR）
    → RecordingAgent（14）产出 Trajectory JSONL
    → EvalDataset.importFailures(trajectories, minReward=-0.4)
    → EvalCase#case-0007：prompt=原文 / expectation=Contains("道歉") /
       originRunId=run-8842——用例与事故的谱系成立

T6: 回归评估与发布门禁（改得放心）
    PromptManager 发布 prompt v4（canary）修复 T5 的坑
    → EvaluationRunner 重放 dataset（含 case-0007）
    → EvalReport：passRate 8/8 = 100%，对比基线 7/8
    → verdict = PASS → v4 从 canary 提升到 stable（13 的指针回滚能力随时可退）
    （反例演示：若 v4 让 case-0003 变坏 → passRate 7/8 < 基线 → FAIL → 阻止提升）

T7: 收口（退得回 + 三投影对照）
    RunRegistry.byRunId("run-8842")
      → RunRecord[prompt=v4/canary, model=cheap(reason: budget), tools=core@f1]
      → "昨晚答错那单用的什么组合"——时间旅行查询成立
    CostDashboard.export(...)：
      tenant=acme 41,200 / channel=eng 41,200 / agent=assist 41,200 / user: alice 38k, bob 3.2k
      ——四维拆分，同一笔账不同视角，行行对得上总账

失败分支：
    F1 单 Run 预算闸：RUN 维度 2k 上限，长任务第 3 次模型调用被拦
       → 诚实失败 + 已耗成本照常入账（半途而废也是成本）
    F2 路由可解释：全部 RouteDecision.reason 非空；对账查询按 reason 分组统计
    F3 便宜模型也挂：cheap 调用异常 → Fallback 链兜底 → DENIED 后诚实失败
       ——路由与降级的纵深组合实证（D6）
    F4 基线缺失：首跑 dataset 无基线 → verdict=BASELINE_ABSENT（建立基线，不假对比）
    F5 门禁 FAIL：新版本跌破阈值 → 失败明细逐 case 列出（caseId + expectation 差异）
       → 阻止 stable 提升——发布流水线的最小闭环
    F6 单价缺失：PricingTable 没配的模型 → CostMeter IAE "no pricing for model: x"
       ——不算假账（fail-loud 优于静默 0 成本）
    F7 denied 也是信号：工具被治理链拒绝 → ToolCallMetrics.denied=true 计数
       ——denied 飙升是注入攻击或 prompt 退化的先导指标
```

---

## 7. 模块结构

```text
agent-observability/                               # 新增 Maven 模块（父 POM <modules> 增补）
└── src/main/java/io/github/qwzhang01/agent/observability/
    ├── metrics/                                   # 7 类（M18.1）
    │   ├── MetricsSink.java                       # 观测事件出口接口
    │   ├── ModelCallMetrics.java                  # record
    │   ├── ToolCallMetrics.java                   # record
    │   ├── RunMetrics.java                        # record（run 级汇总行）
    │   ├── ObservingModelClient.java              # 装饰器：Model 边界
    │   ├── ObservingToolExecutor.java             # 装饰器：Tool 边界
    │   └── MetricsCollector.java                  # 聚合 + 内存查询
    ├── cost/                                      # 6 类（M18.2）
    │   ├── PricingTable.java                      # 单价表（microUSD）
    │   ├── CostMeter.java                         # usage → 成本换算
    │   ├── BudgetDimension.java                   # enum 五维
    │   ├── BudgetCheck.java                       # sealed OK/WARN/DENIED
    │   ├── BudgetBook.java                        # 多维账本（事前闸+事后账）
    │   └── ChannelQuota.java                      # 预算数字容器（D5）
    ├── routing/                                   # 4 类（M18.3）
    │   ├── ModelRouter.java                       # 策略接口
    │   ├── RouteDecision.java                     # record（reason 必填）
    │   ├── RoutingModelClient.java                # 装饰器：路由转发
    │   └── BudgetAwareRouter.java                 # 默认策略：按余量
    ├── eval/                                      # 5 类（M18.4）
    │   ├── EvalCase.java                          # record（originRunId 可溯）
    │   ├── Expectation.java                       # sealed 判定器
    │   ├── EvalDataset.java                       # load/save/importFailures
    │   ├── EvaluationRunner.java                  # 批量重放
    │   └── EvalReport.java                        # 成功率+基线+门禁
    └── version/                                   # 4 类（M18.5）
        ├── ComponentVersion.java                  # record（kind/name/version/channel）
        ├── RunRecord.java                         # record（版本三元组+指标+成本）
        ├── RunRegistry.java                       # append + 查询
        └── CostDashboard.java                     # 四维拆分导出
```

```text
存量改动：零（§2 预检裁决——延续 15 两处加法之后 16/17 的零存量传统）
```

```text
examples/（新增 1 个）
└── ObservabilityExample.java   # 验收剧本：T0-T7 全景（指标→预警→降级路由→
                                #   阻断→失败回收→回归门禁→版本查询+四维账单）
                                #   + F1-F7 边界演示；装配层粘合示范：
                                #   ServiceAccount→ChannelQuota / PromptManager→ComponentVersion
```

不改动其他任何存量模块（agent-core / agent-trace-export 零 diff；examples pom 增补 agent-observability + agent-channel 装配依赖，同 Stage 15/16/17 先例）。

---

## 8. 实现里程碑（5 个，节奏对齐 Stage 13-17）

| # | 里程碑 | 交付 | 验证 |
|---|--------|------|------|
| M18.1 | 指标核心 | metrics 7 类 + 单测 | 双装饰器全语义透传（sync/stream/异常记完照抛）；延迟/usage/tool 指标捕获准确（Mock 可控断言）；RunMetrics 聚合（模型调用数/token 三项/工具数/成功率/时长）；denied 计数（治理链拒绝也进指标）；sink 多实现订阅互不干扰 |
| M18.2 | 成本与预算 | cost 6 类 + 单测 | 单价换算正确（microUSD 整数，input/output 分价）；单价缺失 fail-loud IAE；五维闸各自耗尽全拦 fail-closed；**WARN 不阻断**（80% 告警后 run 完成）与 **DENIED 阻断**分离；recordUsage 回填真实值；ChannelQuota 纯数字容器无 channel 依赖（编译级证明） |
| M18.3 | 模型路由 | routing 4 类 + 单测 | **至少 2 模型自动切换**（余量阈值切 cheap，验收原文）；RouteDecision.reason 必填非空；Routing+Fallback 组合（cheap 挂了链式兜底，1 的设施零改动复用）；路由零开销透传（选中者参数原样转发） |
| M18.4 | 评估回归 | eval 5 类 + 单测 | importFailures 过滤正确（ERROR/MAX_STEPS/低 reward 三源）；originRunId 谱系可溯；判定器四类确定性断言；**门禁三态**（PASS/FAIL/**BASELINE_ABSENT 诚实标注**）；同 dataset 同 Mock 同报告（可复现性是门禁生命线）；JSONL round-trip |
| M18.5 | 版本与收口 | version 4 类 + ObservabilityExample + README/笔记收口 | 示例实跑 T0-T7 全剧本（零 LLM：双 Mock + scripted 失败）；版本三元组查询（byRunId 时间旅行）；CostDashboard 四维拆分导出且各维合计=总账；三投影同源对照演示（同一 run：轨迹 JSONL + 审计流 + 指标行）；全仓存量零影响（零 diff 即证明） |

依赖：M18.2 ← M18.1（记账需 usage）；M18.3 ← M18.2（路由读余量）；M18.5 ← M18.1/M18.2（RunRecord 聚合）；**M18.4 独立**（只依赖 trace-export 读轨迹，不依赖 metrics/cost）。

**与 Stage 17 的交错**：M18.1-M18.5 全部与 M17.2-M17.5 无依赖交叉。建议节奏：17 收口优先 → 18 全速（或 17 里程碑间隙穿插 M18.1/M18.4 两个独立里程碑）。

---

## 9. 验收标准（对齐 18 周规划原文 9 条）

```text
规划原文：可观测性、评估、成本治理与发布
1. 一次 Run 的完整 Trace
   -> M18.1 RunMetrics（运营投影）+ 复用 14 Trajectory（训练投影）+ 9 AuditEvent
      （治理投影）——同一 run 三种投影对照演示（D1），不另造 Trace
2. 工具调用指标
   -> M18.1 ObservingToolExecutor：次数/延迟/成功率/denied（F7：denied 也是信号）
3. Token 和成本记录
   -> M18.1 TokenUsage 捕获 + M18.2 CostMeter 记账（microUSD，单价缺失 fail-loud）
4. 模型路由规则（至少 2 个模型自动切换）
   -> M18.3 BudgetAwareRouter：premium/cheap 按预算余量自动切换，
      RouteDecision.reason 可解释（D6）
5. 频道级 Token 配额和阈值预警
   -> M18.2 BudgetBook CHANNEL 维 + warnAt 预警不阻断；
      ChannelQuota 从 ServiceAccount.monthlyTokenBudget() 装配注入
      ——Stage 12 显式留给本阶段的占位钩子回收（D5）
6. 任务成功率统计
   -> M18.1 MetricsCollector 按 agent 聚合（SUCCEEDED/ERROR 比率）
      + M18.4 EvalReport 评估成功率（两个口径都交付：线上成功率与离线评估成功率）
7. 失败样本回归测试
   -> M18.4 importFailures → EvaluationRunner → 门禁 verdict（D7）
8. Prompt 和 Model 版本记录
   -> M18.5 RunRecord 版本三元组（Prompt 复用 13 PromptManager 版本号，
      Model/Tool 装配层声明；D8）
9. 成本仪表盘数据导出（按租户、频道、Agent、用户）
   -> M18.5 CostDashboard 四维拆分导出，各维合计=总账（F 系列对账断言）

学习内容四块对照：可观测性=M18.1 / 成本治理与模型路由=M18.2+M18.3 /
评估=M18.4 / 版本管理+发布=M18.5（发布=13 canary 通道 + 18 门禁组合，不重做）
```

---

## 10. 测试策略

- **装饰器透明性**：ObservingModelClient sync/stream 全语义透传（参数、响应、流事件序列逐项相等）；异常记完照抛（对照 14"捕获后上抛不吞"）；ObservingToolExecutor 不改变执行结果与异常语义——指标是旁路，旁路必须无感
- **指标准确性**：scripted 延迟与 usage（Mock 返回可控 TokenUsage）→ 断言捕获值精确相等；RunMetrics 聚合 = 边界事件之和（token 三项、调用计数、时长）
- **预算闸语义**：五维各自耗尽全拦（fail-closed）；**WARN 阈值行为**（80% 只发 BudgetAlarmEvent，run 完成，无异常）；**DENIED 行为**（耗尽抛 BudgetExceededException 携带 dimension/used/limit，对齐 15 语义）；recordUsage 回填后 remaining 精确
- **成本换算**：microUSD 整数运算（input/output 分价 × 对应 token 数）；**单价缺失 IAE fail-loud**（不算假账）；CostDashboard 四维拆分各行合计 == 总账（对账断言——账本的自洽性是成本治理的底线）
- **路由可解释与纵深**：切换场景 reason 非空且含余量数字；**Routing+Fallback 组合**（cheap 异常 → Fallback 兜底 → 全挂 DENIED 诚实失败）；路由零改动透传（选中 client 收到的 request 与原 request 相等）
- **评估可复现性**：同 dataset 同 Mock 两次跑 → 报告逐字段相等（门禁生命线）；importFailures 三源过滤（ERROR / MAX_STEPS_EXCEEDED / reward<阈值）各自正确；originRunId 指回原轨迹（load 回来能对上）
- **门禁语义**：基线缺失 BASELINE_ABSENT（不假对比）；跌破阈值 FAIL + 失败明细含 caseId 与 expectation 差异；通过 PASS 且报告存为下次基线
- **版本谱系**：RunRecord 三元组 round-trip；byRunId/byAgent 查询正确；Prompt 版本号与 13 PromptManager 实际发布版本一致（装配层翻译无漂移）
- **向后兼容**：零存量改动——全仓存量测试零 diff 即证明（Stage 17 收口后接续此基线）
- **Mock 验收**：全链零 LLM（premium/cheap 双 MockModelClient 实例 + scripted 失败 run 产轨迹，同 Stage 8-17 手法）

---

## 11. 文章规划（规划原文 11 篇全收）

| 文章（规划原文） | 写作时机 | 素材来源 |
|---|---|---|
| 《没有 Trace，就没有真正可调试的 Agent》 | M18.1 | §1 五假设 + D1 三种投影（读者不同不是数据量不同） |
| 《Agent 应该如何评估：答案、路径还是任务结果》 | M18.4 | D7 + 判定器四类 + 门禁三态（评估的是任务结果，可复现优先） |
| 《Agent 的 Token、延迟和工具调用指标》 | M18.1 | D2 边界不在路径 + 装饰器谱系编年史（1/9/14/18 四代） |
| 《模型路由：大模型、小模型和本地模型如何自动选择》 | M18.3 | D6 路由是策略 + reason 必填三理由 + Routing/Fallback 纵深 |
| 《Token 预算管理：单次 Run 和用户月度预算》 | M18.2 | D3 三段式（预警/降级/阻断）+ 估算闸与真实账 |
| 《频道级配额：Claude Tag 的频道 Token 上限怎么做》 | M18.2 | D4 五维逃逸面 + D5 数字注入 + 12 占位钩子回收（跨 6 阶段的伏笔回收叙事） |
| 《降级策略：预算耗尽时怎么办》 | M18.3 | D3/D6 交汇：降级不是拒绝服务，是降低成本密度；三段式的每一段为谁存在 |
| 《成本仪表盘：按租户、频道、Agent、用户拆分》 | M18.5 | 四维拆分 + 对账断言（各维合计=总账）+ microUSD 整数纪律 |
| 《Prompt、模型和工具的版本如何管理》 | M18.5 | D8 版本三元组 + 13 复用（不重做）+ 时间旅行查询 |
| 《Agent 失败样本如何进入回归测试集》 | M18.4 | D7 修 bug=数据集+1 + originRunId 谱系 + Agent 的 bug 多数不在 Java 代码里 |
| 《Java Agent Framework v1.0 架构复盘》 | M18.5 收口 | **收官总纲**：18 周全程复盘 + M9 面试叙事（5/15/30 分钟三层表达）+ 零存量改动证据链（15→16→17→18）+ 六次组装阶段的复用率演进 |

**系列衔接**：文章 1 开局即总纲（三投影是理解本阶段的钥匙）；文章 11 是整个系列的收官——从 Stage 1 的"为什么不从 AgentLoop 开始"讲到 v1.0 的"同一 Runtime 三 Profile + 运营层"，面试叙事完整闭环。

---

## 12. 本阶段不做（范围控制）

- **OpenTelemetry/OTLP 真实导出** —— D9：先稳定自有 MetricsSink 接口，otel-exporter 子模块留 v2（可选依赖形态）
- **LLM-as-judge 评估** —— v1 判定器只做确定性断言（可复现是门禁生命线）；judge 槽位在 Expectation 接口预留
- **时间序列存储与聚合引擎** —— Prometheus/Grafana 域；v1 内存聚合 + JSONL 导出
- **比例灰度（percentage canary）** —— 13 已声明需 sticky session 留 v2；本阶段门禁 + 双通道（stable/canary）+ 指针回滚已构成发布最小闭环
- **实时仪表盘 UI / 可视化前端** —— 只导出数据（CSV/JSONL），前端域外（对齐 13 DAG 只出描述标准的先例）
- **费率计费与账单系统** —— CostMeter 是治理估算（microUSD）不是财务口径；账单涉及税率/币种/合同价，域外
- **分布式追踪传播（traceparent/W3C）** —— 单体 Runtime 域外；跨服务传播在真实部署形态出现后再议
- **配额排队/削峰** —— 预算耗尽即拒绝（诚实失败），排队等待重置是产品决策不是框架语义，v2
- **model registry / model serving** —— 路由只从已装配的 ModelClient 集合里选，模型上线/下线是部署域
- **多币种/汇率** —— microUSD 单一单位，换算是展示层的事
- **agent-spring-boot-starter** —— 规划技术基线的可选模块，与 OTel 同理推迟：库形态先收敛语义，starter 是包装层

---

## 13. 与 18 周规划总目标的收官对照

```text
规划总目标最终交付清单（§一）逐项核对：
Core Runtime（1-2/5-6）✅ / Model Adapter（1）✅ / Tool System（2/9）✅
Plugin System（3）✅ / Sandbox（4/17 复验）✅ / Workflow Graph（5）✅
State/Memory/Checkpoint（6/8）✅ / Async Task Scheduler（7）✅
Security/Policy/Audit（9）✅ / Agent Identity（12）✅
Channel Agent & Ambient（12）✅ / Shared Memory Governance（8/12）✅
MCP & A2A（10/11）✅ / Product Layer（13）✅ / Trajectory Export（14）✅
Observability & Cost Governance（18）—— 本阶段交付
Enterprise/Tavern/Coding Profile（15/16/17）✅（17 实施中，并行收口）

里程碑 M1-M8 已全部达成；M9（面试表达）随文章 11 收官兑现：
  5 分钟：一张图讲清 Runtime 分层 + 三 Profile + 运营层
  15 分钟：深入任一模块（六次组装阶段的复用预检是最好的架构故事）
  30 分钟：一次完整执行链路（T0-T7 剧本即讲稿）
```

---

## 14. M18.1 实现记录（2026-08-25，指标核心）

### 交付

- 新增 `agent-observability` Maven 模块（父 POM `<modules>` + dependencyManagement 两处注册；compile 依赖**仅 `agent-core`**--「依赖随用随加」纪律：agent-trace-export 留 M18.4 评估读轨迹时再加；`agent-model` test scope 供端到端 MockModelClient）
- **零存量改动兑现**：除父 POM 两处注册外全仓存量模块零 diff--**全仓 1047 测试全绿**（17 线并行推进至 agent-coding 138 + 本模块 24 + 存量 885），双线并行（§0 裁决）首次落地互不干扰的实证
- **metrics 包 7 类**：
  - `MetricsSink`（运营投影出口：onModelCall / onToolCall / onRun 三事件；onAlarm 留 M18.2 以 default method 加入--接口演进不破坏实现者，见偏差 ①）
  - `ModelCallMetrics`（record：model + latencyMs + token 三项 + finishReason + error；usage 未报告诚实记 0--M18.2 定价表对缺行 fail-loud 拒猜，两级诚实分工）
  - `ToolCallMetrics`（record：toolName + latencyMs + success + denied + error）
  - `RunMetrics`（record：runId + agentName + status + lastError + durationMs + modelCallCount + modelCallErrors + toolCallCount + deniedToolCalls + tokenUsage 汇总 + costMicros 占位 0[诚实占位：无定价表=无成本计算，M18.2 接线]）
  - `ObservingModelClient`（装饰器第四代：1 Retry/Timeout/Fallback -> 9 Governed -> 14 Recording -> 18 Observing；chat 边界测延迟读 usage；**stream 在终止事件 Done/Error 恰发一次**（peek + AtomicBoolean，无终止事件的流零发射--lazy 语义保真）；**双向异常纪律**：delegate 异常记完照抛[对齐 14] / sink 异常吞+warn[对齐 12 listener 隔离]--指标是旁路，旁路失败不伤主流程）
  - `ObservingToolExecutor`（denied 检测=Stage 9 治理链文本契约 `[DENIED] `/`[RATE_LIMITED] ` 前缀；`[ERROR] ` 前缀（Stage 2 工具错误包装）明确不算 denied--工具跑了且失败是质量信号，治理拦截才是治理信号，javadoc 写明两种 prefix 的语义分界；**wiring 顺序契约**：必须在 Governed 外层否则 denial 全部静默丢失）
  - `MetricsCollector`（implements MetricsSink + run 生命周期：beginRun ThreadLocal 上下文 / endRun 物化 RunMetrics；**孤儿事件不丢弃**--无 run 上下文的边界事件计入全局 totalModelCalls/totalToolCalls[运营记账不挑 run：run 外烧的 token 也是 token]；查询 runMetrics/byAgent/agentStats[嵌套 record：成功率统计，DONE 才算成功、MAX_STEPS 算失败]；守卫：嵌套 run 拒[对齐 14 单 run 纪律] / runId 唯一性拒 / endRun 无 active run 拒 IAE）
- 测试 24 个：`ObservingModelClientTest` 9（chat 透传 assertSame 响应实例 + request 恒等 / 指标精确[tokens/finishReason/latency>=0] / usage null 诚实 0 / **异常记完照抛**[同类型同消息] / stream 事件序列逐项透传 + Done 恰一次含 usage / Error 终止失败指标 / **无终止流零发射** / **sink 抛异常不伤主流程** / 多 sink 转发广播同事件）+ `ObservingToolExecutorTest` 6（成功透传 / 异常 rethrow 非 denied / **[DENIED] 前缀 denied=true success=false 文本 verbatim** / [RATE_LIMITED] 同 / **[ERROR] 前缀不算 denied**[工具跑了的失败是质量信号] / sink 异常隔离）+ `MetricsCollectorTest` 9（聚合精确[2 model 含 1 error + 3 tool 含 1 denied -> 各字段 + token 累加 + costMicros==0 占位] / 失败 status+lastError 入行 / endRun 清 ThreadLocal[二次 endRun IAE] / beginRun 三守卫[blank/嵌套/runId 复用] / active run 查询 empty[诚实：未结束无汇总] / byAgent 过滤 / **agentStats 成功率[MAX_STEPS 算 failed]** / **孤儿事件全局计数** / **端到端：SimpleAgent + ReActAgentLoop + 双装饰器一行接线，scripted tool_calls->text 两轮 -> RunMetrics modelCallCount=2 toolCallCount=1 tokenUsage 精确 100/40/140 + agentStats 1.0**--「不动 loop 一行」的即插即用实证）

### 与蓝图的一致性（偏差 5 处诚实记录）

1. **MetricsSink.onAlarm 推迟 M18.2**：蓝图 3.1 草图含 `onAlarm(BudgetAlarmEvent)`，但参数类型属 cost 包（M18.2）--为不在 M18.1 引入占位类型，接口先三事件，M18.2 以 **default method** 加 onAlarm（接口演进零破坏实现者）；javadoc 已预告
2. **RunMetrics.doneReason -> status + lastError**：蓝图字段名 doneReason 来自 14 的 DoneReason 枚举（trace-export）；metrics 包为其 import trace-export 语义错位--改用 core 的 `AgentState.Status` + lastError 字符串承载失败原因（语义同源，依赖最小化）
3. **超蓝图字段两处**：modelCallErrors / deniedToolCalls（蓝图验收点名"denied 计数"与错误率，record 字段显式承载优于事后推导；同 M17.1 maxDepth 第 4 字段先例）
4. **AgentStats 嵌套 record**：蓝图 7 类清单外，作为 MetricsCollector 嵌套类型兑现"任务成功率统计"验收（不占顶层类名额，同 16 Snapshot.relationship 先例）
5. **sink 异常吞掉的旁路纪律**：蓝图测试策略写"指标是旁路"，实现明确**双向异常分界**（delegate 异常照抛[业务保真] vs sink 异常吞+warn[旁路不伤主流程]）并双双测试锁定--javadoc 契约化，同 14「捕获后上抛不吞」与 12 listener 隔离的两条纪律在同一装饰器内的精确组合

### 实现记录

- **零实现 bug**：24 测试一次通过（对照 M17.1/M17.2 各有 1 个实现侧 bug--装饰器层薄 + 契约先读后写的效果）
- **并行实证**：本里程碑开发期间 17 线同步推进（agent-coding 72 -> 138，M17.3/M17.4 落地），两线全仓合并构建一次通过--§0 裁决「交集仅 agent-core 且零存量」从纸面推断变成构建事实
- 依赖随用随加：pom 仅 agent-core（trace-export 推迟 M18.4，对照蓝图 §2 预检的落点）

---

## 15. M18.2 实现记录（2026-08-25，成本与预算）

### 交付

- **cost 包 7 类**（蓝图 6 类 + BudgetAlarmEvent 第 7 类，§3.1 草图点名的预警事件类型落位）：
  - `PricingTable`（model -> Price(input/output microUSD per 1M) 不可变表；builder 校验非正价 IAE；同模型重复定价后写覆盖）
  - `CostMeter`（TokenUsage -> microUSD，**纯整数运算 round-half-up**：`(tokens*price+500_000)/1_000_000`；溢出诚实边界 javadoc：价格 < ~4.3e9 microUSD/M（$4,300/M tokens）时 tokens*price 恒在 long 内；**单价缺失 IAE fail-loud 不算假账**，消息含模型名）
  - `BudgetDimension`（五维 enum，javadoc 逐维写明逃逸面语义：RUN 经济闸对照 17 [LIMIT] 行为闸"同一条战壕的两个哨位" / CHANNEL 兑现 12 占位 / AGENT 是 12 身份隔离的经济面）
  - `BudgetCheck`（sealed 三态 Ok/Warn/Denied，嵌套 record；**类型层落实 D3 预警阻断分离**：Warn 携带 percentUsed/used/limit 永不阻断，Denied fail-closed）
  - `BudgetAlarmEvent`（record：dimension/key/used/limit/percent，流向 MetricsSink.onAlarm）
  - `BudgetBook`（两阶段纪律：**requireBudget 事前闸**[DENIED 判 projected=used+est>limit，WARN 判 used>=warnAt%] + **recordUsage 事后诚实账**[估算与实际的差值保留不平滑]；未配置=不限[Ok + limitOf=-1 对齐 ServiceAccount.UNLIMITED 占位约定 + remainingOf=MAX_VALUE 路由算术安全]；alarm 每次越线都发 v1 无频控[javadoc 注明 NoisePolicy 教训属 sink 关切]；alarmSink 异常吞[旁路纪律]；builder 校验 limit>0/warnAt 1-99 装配期 fail-fast）
  - `ChannelQuota`（纯数字容器 record：channelId + monthlyTokenBudget；javadoc 写明装配模式 `if (svc.hasBudgetCap()) book.budget(CHANNEL, ch, svc.monthlyTokenBudget())`--**Stage 12 占位钩子兑现的代码级落点**）
- **M18.1 预告的两处接线兑现**：
  - `MetricsSink.onAlarm(BudgetAlarmEvent)` default method 落地（M18.1 前的实现零改动编译通过--接口演进承诺兑现，AlarmCollector 测试锁定）
  - `MetricsCollector(CostMeter)` 注入构造：RunAccumulator.addModelCall 边到边计价、endRun 的 costMicros 从占位 0 变为已计价精确和；**无参构造保持**（向后兼容）；**无定价模型 catch IAE -> warn + 0**（两纪律冲突的裁决：CostMeter 直接调用者享受 fail-loud，聚合路径选择旁路不炸 run--诚实记录见偏差 ②）
- 测试 +30（模块累计 54）：`PricingTableTest` 3 / `CostMeterTest` 7（**精确换算 800+200=4000 microUSD** / input-output 分价 / **round-half-up 0.5->1** / 零 token 诚实 0 / **缺价 IAE 含模型名** / ModelCallMetrics 重载 / 负数拒）/ `BudgetBookTest` 15（未配置不限 + 未配置也记账 / 健康 Ok 无告警 / **WARN 83% 精确 + alarm 事件六字段** / **DENIED projected 击穿** / **恰好用尽 == limit 放行**[拒绝透支不拒绝踩线] / 诚实账回填 / **五维闸各自独立** / 同维度不同 key 独立 / warnAt=50 提前告警 / 告警每次越线重发 / 无 sink 不炸 / **sink 抛异常不伤闸** / builder 三守卫 / gate 四守卫）/ `ChannelQuotaTest` 3 / `MetricsCollectorTest` +2（**接线精确和 2150** / **无定价 0 成本 run 存活**）
- **全仓 1077 全绿**（1047 + 30），存量零影响，零存量改动继续兑现

### 实现期坑 2 条（记入防复发）

1. **Java 17 sealed permits 嵌套类型简单名解析失败**（实现侧，编译器抓）：`sealed interface BudgetCheck permits Ok, Warn, Denied` 引用同文件嵌套 record 的简单名在 javac 17 (Tencent JDK 17.0.19) 下报"找不到符号"；修复=**省略 permits 子句**（JLS：子类同编译单元时自动 permit）。这是 Java 17 语法边界第 N 次踩（M16.2 record 解构模式 + switch 类型模式之后），防复发惯例升级："写 sealed 先问 17 还是 21，嵌套子类省 permits"
2. **测试期望值算式错误**（测试侧，M17.2 教训重演）：lastWriteWins 把 `1M tokens × 2M microUSD/M` 期望写成 4,000（正确是 4,000,000--每百万 token 单价 2M microUSD，1M tokens 恰好付单价 $2）。实现侧零 bug，4M 是对的。教训：**token 换算断言先写"单价 × 百万 token 数"的量纲检查再写数字**

### 与蓝图的一致性（偏差 5 处诚实记录）

1. **BudgetAlarmEvent 为第 7 类**：蓝图 26 类清单外、§3.1 草图 onAlarm(BudgetAlarmEvent) 点名--类型落位到 cost 包（预警是预算域的事件）
2. **CostMeter fail-loud 与 Collector 旁路的双纪律分界**：蓝图 F6 的 fail-loud 属于 CostMeter 直接调用者；MetricsCollector 聚合路径 catch IAE -> warn + 0（旁路失败不伤 run）。两纪律冲突时旁路优先，测试双双锁定（CostMeterTest.missingPricingFailsLoud vs MetricsCollectorTest.costWiringUnpricedSurvives）
3. **DENIED 边界语义细化**：蓝图只写"耗尽 fail-closed"，实现明确 projected（used+est）> limit 拒 / 恰好 == limit 放行（拒绝透支不拒绝踩线）+ WARN 按 used（已发生）判而非 projected（预测）--预算尾部保守浪费是永不透支的代价，javadoc 写明
4. **limitOf 未配置返回 -1**：对齐 Stage 12 ServiceAccount.UNLIMITED_BUDGET = -1 的占位约定（跨阶段同构语义）
5. **孤儿事件不算成本**：RunMetrics.costMicros 是 run 内已计价调用的和；run 外调用的成本走 BudgetBook 记账维度（run 汇总与账本职责分离，javadoc 注明）

---

## 16. M18.3 实现记录（2026-08-25，模型路由）

### 交付

- **routing 包 4 类 + cost 包第 8 类**（蓝图 26 类清单外新增 BudgetExhaustedException，见偏差 ①）：
  - `ModelRouter`（策略接口：`route(request, BudgetSnapshot) -> RouteDecision`；嵌套 record `BudgetSnapshot(remainingTokens, limitTokens)`--**D5 数字注入的代码级落点**：路由器只见数字不见 BudgetBook/ServiceAccount，测试可用纯数据构造；`of()` 工厂校验 limit>0 且 0<=remaining<=limit 装配期 fail-fast--路由器永远不见不可能的账本；`unlimited()` 工厂对应未配置预算，永不降级）
  - `RouteDecision`（record：modelId + reason；compact constructor **类型层强制 reason 非空**--D6"路由必须可解释"不是 javadoc 惯例是构造契约，javadoc 写明三理由：成本对账行行可审计 / 事后归因可复现 / 对齐 12 IdentityDecision + 9 AuditEvent 裁决留痕传统）
  - `RoutingModelClient`（implements ModelClient 装饰器：候选 Map + router + **每次调用前取一次快照的 Supplier**--同一 router 随账本消耗看到不同快照，中途耗尽翻转下一次决策；**零开销透传为测试锁定契约**：选中 client 收到原 request 实例[不重写 request.model()--候选按逻辑键编址，v1 javadoc 写明]、response/stream 实例原样返回；**路由是主路径不是旁路**：router 与 delegate 异常全部原样上抛不遮蔽--对照 M18.1 双向异常纪律，两代装饰器一个吞 sink 一个不吞业务，javadoc 互引；router 返回未知 modelId -> ISE 含 id+reason+候选集，fail-loud 是装配 bug 的正确死法；两构造器[无预算源=unlimited 快照 / 带预算源]）
  - `BudgetAwareRouter`（默认策略三档：健康>=阈值 -> premium / 0<余量<阈值 -> cheap / 余量==0 -> `BudgetExhaustedException` fail-closed--**降级不是拒绝服务，是降低剩余服务的成本密度；耗尽时切便宜也救不了[任何调用都透支]，诚实拒绝**；恰好踩线 == 阈值走 premium[严格小于比较，对齐 BudgetBook"拒绝透支不拒绝踩线"]；reason 携带余量百分数与阈值数字["remaining 18% < 25% threshold"]；percent 整数向下取整[javadoc 写明]；请求本身 v1 不参与决策--预算是唯一信号，复杂度路由是 v2 策略不是本类的参数）
  - `BudgetExhaustedException`（cost 包第 8 类：remaining + limit 字段；**非 ModelException 是有意裁决**--预算耗尽不是瞬态模型故障，外层 FallbackModelClient 不该"恢复"它[换供应商不回血预算]，javadoc 写明与 15 enterprise BudgetExceededException 的同语义不同名关系[D4 依赖方向 + 装配层桥接两账本时避免 import 歧义]）
- 测试 +24（模块累计 78）：`RouteDecisionTest` 3（构造/相等 + blank modelId 拒 + **null/blank reason 拒**[D6 类型契约]）/ `BudgetAwareRouterTest` 10（健康 premium reason 含"50%" / **低于阈值 cheap reason 同时含"18%"与"25%"**[对账材料] / **恰好踩线走 premium** / unlimited 走 premium + isUnlimited + percent 100 / **耗尽抛 BudgetExhaustedException 字段与消息数字全断言** / 微小余量 5/10000=0% 降级不抛[0% 向下取整仍在阈值下] / **percent 向下取整 2499/10000=24% -> cheap** / 构造守卫 4 / 快照 of() 校验 5 断言 / null 快照 NPE）/ `RoutingModelClientTest` 11（**chat 零开销透传**[assertSame request + assertSame response + 未选者 0 调用] / stream 同[assertSame 流实例 + router 每调用恰一次] / router 每调用都问[2 次 chat -> 2 次 route] / **验收剧本：双 Mock 同一 client 实例先 premium 后 cheap**[真 BudgetBook + 真 BudgetAwareRouter + supplier 读 remainingOf/limitOf，记账 8500 后 15%<25% 自动切换] / **预算中途耗尽下一调用 fail-closed** / **Routing(Fallback(…)) 组合**[空 scripted Mock 天然抛 ModelException -> 1 的链零改动兜底 -> backup 答案] / **全链耗尽" All fallback clients exhausted"原样穿透**[诚实失败] / **router 异常实例 assertSame 穿透**[主路径无遮蔽] / 未知 modelId ISE 三要素消息 / 构造守卫 4 / candidateIds）
- **全仓 1101 全绿**（1077 + 24），存量零影响，零存量改动继续兑现

### 实现期坑 1 条（记入防复发）

1. **record 静态工厂与实例谓词同签名冲突**（实现侧，编译器抓）：`BudgetSnapshot` 的静态工厂 `unlimited()`（返回快照）与实例谓词 `unlimited()`（返回 boolean）同名同参--Java 不允许 static 与实例方法同签名共存，javac 报"已在记录中定义了方法"。修复=谓词改名 `isUnlimited()`（bean 惯例）。防复发惯例：**record 的静态工厂命名先查实例方法命名空间**（对照 M18.2 坑 1 sealed permits--Java 17 语法边界踩坑第三期）

### 与蓝图的一致性（偏差 5 处诚实记录）

1. **BudgetExhaustedException 为第 8 类（cost 包）**：蓝图 §3 BudgetAwareRouter 行点名"耗尽抛 BudgetExceededException"，但该类型属 15 enterprise（D4 明文禁止 observability 反向依赖）--自有同语义类型落位 cost 包[预算域失败词汇，T4 的装配层闸门 DENIED->throw 也复用它]，命名避开 BudgetExceededException 防桥接装配时 import 歧义
2. **BudgetSnapshot 为 ModelRouter 嵌套 record**：蓝图 26 类清单无此类、§3.1 草图签名 `route(request, budgetSnapshot)` 点名了它--按 16 Snapshot.relationship / M18.1 AgentStats 嵌套先例落位（不占顶层类名额）；顺带把"路由器不见账本只见数字"固化为类型边界
3. **快照供应商注入 RoutingModelClient**：蓝图草图只写 candidates + router 两件套，未写预算视图从哪来--实现加第三构造参数 `Supplier<BudgetSnapshot>`（每次调用前取新快照），两参重载默认 unlimited；"同一 router 随账本消耗看到不同快照"由此成立，验收剧本的自动切换正是靠它
4. **恰好踩线走 premium（严格 `<` 比较）**：蓝图只写"余量低于阈值走 cheap"，实现明确 == 阈值不切--对齐 M18.2 偏差 ③"拒绝透支不拒绝踩线"的同款边界语义，两处 javadoc 互为印证
5. **不重写 request.model()**：路由选中候选后 request 原实例透传（蓝图验收"选中者参数原样转发"的字面兑现）--model 字段仍是调用方写的逻辑名，javadoc 写明 v1 候选按键编址、需要改写模型串的供应商在自己的 client 里改
