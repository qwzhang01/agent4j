# E2 · 模型路由对照实验笔记（2026-09-10）

> 四周学习计划（`learning-path-2026-09-knowledge-points.md`）的第 2 个实验：E2 = KP4（Model Routing）+ KP2（Token Budgeting）交叉验证。
> 回答决策 25 的核心问题：**路由信号从哪拿——pre-call 还是 post-call，各值多少。**

## 一、实验设计

三配置 × 同一 14 任务集 × 校准价格（确定性模拟，无随机）：

| 配置 | 组装 | 机制 |
|------|------|------|
| A. all-premium | 直连 premium | 基线：全量高价 |
| B. pre-route | `RoutingModelClient(ComplexityRouter)` | pre-call 预判：消息数 ≥8 或命中任务标记 → premium |
| C. cascade | `CascadeModelClient(cheap, premium, RuleBasedQualityGate)` | post-call 验证：cheap 先答 → 质量门判定 → 不达标 premium 重发原请求 |

任务集构成（确定性）：

- 4 个短线程闲聊（s1-s4）：cheap 的主场
- 6 个结构化输出任务（j1-j6）：cheap 固定在 j2/j3/j5 上输出坏 JSON
- 4 个深线程任务（d1-d4，10 条消息）：ComplexityRouter 的 premium 触发条件

价格校准（每 1M token，USD，2026 gpt-4o 级价差）：

- premium：prompt $2.50 / completion $10.00
- cheap：prompt $0.15 / completion $0.60（约 1/16 价）

token 估算：消息长度/4 粗分词。全程确定性，可复现。

## 二、实验结果

```
=== E2 routing comparison (14 tasks, calibrated prices, deterministic sim) ===
config            cost($)  premium calls  cheap calls    defects
all-premium      0.002095             14            0          0
pre-route        0.001226              4           10          3
cascade          0.000409              3           14          0
cascade        escalations: 3 (cheap gate failures re-issued on premium)
```

| 结论 | 数据 |
|------|------|
| 级联成本最低 | cascade $0.000409 ≈ 基线的 1/5，比预判路由便宜 3 倍 |
| 级联零缺陷 | 3 个坏 JSON 全被质量门拦下并升级，交付 0 缺陷 |
| 预判路由最便宜的理论被推翻 | pre-route $0.001226 > cascade：4 个深线程盲升 premium（4 次全价调用），比「cheap 先试 + 3 次有据升级」更贵 |
| 预判路由盲区 | pre-call 看不见响应质量：3 个坏 JSON 原样送达用户 |

## 三、发现与结论

### 发现 1：验证比预判便宜（反直觉，本实验最重要）

按直觉预判路由（B）应该最便宜——它省掉了失败后的双重付费。数据相反：**盲预升级比事后验证贵**。原因：预判路由对 4 个深线程全部盲升 premium（全价 prompt + completion），而这些任务 cheap 大多能答好；级联只在「可证明的缺陷」（3 个坏 JSON）上付费升级。省下的不是「升级费」，是「不必要的 premium prompt 费」。

工业界印证：Anthropic 的模型路由、OpenAI 的 GPT-5 auto 模式本质都是级联/验证路线，纯预判分类器路由（LLM-as-router）是少数派。

### 发现 2：级联的质量门信号必须客观可判

v1 质量门三信号（结构化解析失败、finishReason 异常、内容为空）全客观可判，无打分模型。这决定了级联的可信度上限：门只拦得住「可证明的坏」，拦不住「流畅但错误」。v2 方向（打分门）需要标注数据，且打分本身有成本，会侵蚀级联的成本优势——留 E3/后续实验。

### 发现 3：决策 25 的架构答案落定

- **pre-call 经济信号（预算水位）→ ModelRouter 策略**（BudgetAwareRouter 已有）
- **pre-call 内容信号（任务复杂度标记）→ ModelRouter 策略**（ComplexityRouter 新增，证明接口零改动可承载）
- **post-call 质量信号（解析/截断/空响应）→ CascadeModelClient 装饰器**（本次新增，ModelRouter 接口天然看不见响应，必须在路由层之上）

两层互补不合并：路由决定「先试谁」，质量门决定「要不要再试贵的」。合成组装：`Observing(Cascade(Routing(Fallback(...))))`。

### 发现 4：级联的双重付费是特性不是缺陷

升级时 premium 收到的是原始请求（无 cheap 失败响应污染），双方 usage 都记账（merged TokenUsage）。双重付费换来两个保证：premium 从零作答不继承 cheap 幻觉；成本账目诚实——cheap 的失败也是真实支出。

### 发现 5（边界）：流式路径的门只能后判

stream 路径需将 cheap 流消费到 Done 才能判定，意味着用户在判定期间看不到增量输出（首 token 延迟 = cheap 全程）。这是级联在流式场景的固有代价，与 KP1 Handoff 的状态转移设计呼应。缓解方案（先流 cheap 给用户、门失败再切换）会引入 UX 抖动，v2 再权衡。

## 四、交付物清单

主代码（`agent-observability/src/main/java/.../routing/`）：

- `QualityGate.java` — 质量门接口 + `Verdict`（pass/fail + 强制 reason，同 RouteDecision 审计纪律）
- `RuleBasedQualityGate.java` — v1 三信号实现（结构化解析/finishReason/空内容），无外部 JSON 依赖
- `CascadeModelClient.java` — 级联装饰器：cheap 先答 → 门判定 → premium 原请求重发；usage 合并；流路径缓冲重放
- `ComplexityRouter.java` — 预判路由：消息数阈值 + 任务标记（中英文），零改动插入现有 RoutingModelClient

测试（`agent-observability/src/test/java/.../routing/`）：

- `RuleBasedQualityGateTest.java`（15 用例）
- `CascadeModelClientTest.java`（11 用例，含 Fallback 组合）
- `ComplexityRouterTest.java`（9 用例）
- `E2RoutingComparisonExperimentTest.java` — 对照实验 harness（打印成本表 + 断言结构性不变量）

模块测试 160/160 通过。

## 五、与既有决策的关系

- 决策 25（路由策略放哪层）：落定为「pre-call 归 ModelRouter / post-call 归级联装饰器」，接口边界经实验验证成立。
- KP4（Model Routing）：v1 策略族补齐（Budget 经济 / Complexity 内容 / Cascade 质量），装饰器形态验证可承载。
- KP2（Token Budgeting）：级联的 merged usage 是预算四本账的消费侧新入口，BudgetBook 对级联双记账天然兼容。
- KV cache 交叉点（KP3，E3 接手）：级联升级重发原请求时 prefix 不变，理论上 cheap→premium 切换不破坏 premium 侧缓存前缀稳定性；但 cheap 尝试与 premium 重试的 prompt 前缀重叠度对缓存命中率的影响，需要 E3 的真实计费数据量化。

## 六、遗留与下一步

- v2 质量门（打分模型）需要标注数据，暂缓
- 真实 API 计费验证（需要 OPENAI_API_KEY 环境跑一遍 harness 替换 Mock）
- E3：Prompt Caching 前缀稳定性实验
