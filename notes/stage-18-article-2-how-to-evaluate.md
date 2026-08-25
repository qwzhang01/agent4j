# Agent 应该如何评估：答案、路径还是任务结果

> 配套蓝图：[architecture-stage-18.md](architecture-stage-18.md) D7（失败样本即回归集）· 对应实现：`agent-observability/eval/`（`EvalCase` / `Expectation` / `EvalReport`）
> 上一篇：[stage-18-article-1-no-trace-no-debug.md](stage-18-article-1-no-trace-no-debug.md)
> 状态：✅ Stage 18 已完成（M18.4，eval 包 5 类；模块累计至收口 125 测试，全仓 1148 全绿）
> 这是 Stage 18 系列的第 2 篇：评估评的是任务结果，可复现优先于「看起来更聪明」。

---

## 1. 我今天要解决什么问题

上一篇把一次 Run 拆成三种投影。值班工程师现在看得见了。下一个问题立刻跟上：

> 看得见之后，怎么判断这次 Run——以及这次改 prompt / 换模型——到底是变好了还是变坏了？

传统软件的答案是单元测试。Agent 的难题是：bug 多数不在 Java 代码里，在 prompt、模型、工具组合里。单测断言不了「这次回答蠢不蠢」。于是行业里出现三种评估直觉，彼此经常被混成一句「我们做了 eval」：

```text
评答案  —— 终答文本对不对（Exact / Contains）
评路径  —— 中间走没走对（调了几次工具、有没有绕远）
评结果  —— 任务是否在约束内完成（token 天花板、工具次数、门禁能不能发版）
```

这篇文章的结论先说：**v1 评的是任务结果，用确定性断言护住发布线；答案和路径都只是结果的投影，不是评估的主语。**

---

## 2. 为什么会有这个认知冲突

「改了就好」假设（上一篇五假设之四）在评估场景里裂得最彻底：

```text
传统软件：修 bug 先写单测，再改代码。同输入同输出，毫秒级，Mock 可控。
Agent：   同 prompt 跑两次都不一样。改一版 system prompt，目标 case
          立刻见效，另外 30% 边角 case 可能悄悄变坏。
```

冲突来自三个错位：

1. **对象错位**。单元测试断言代码逻辑；评估集断言模型行为。两者不可互相替代（蓝图 §1 边界）。「改 prompt」这件事只有评估集能护住。
2. **判定器错位**。用 LLM-as-judge 评 LLM，等于给门禁引入第二份非确定性，外加一份评估自身的 token 账单。门禁的生命线是「同 dataset 同报告」。
3. **口径错位**。线上成功率（`MetricsCollector.agentStats`：`DONE` 才算成功）和离线评估成功率（`EvalReport.passRate`）是两个口径。混用会让「任务成功率」变成一句空话。

所以问题不是「要不要评估」，是「评估的主语是什么，判定能不能复现」。

---

## 3. 它解决了什么问题

Stage 18 的评估包回答三句话：

```text
用例从哪来   -> EvalCase：prompt + 断言 + 可选的 originRunId（事故谱系）
怎么判定     -> Expectation：四类确定性断言，评的是 Outcome（文本/token/工具数）
能不能发版   -> EvalReport：passRate + 基线对比 + 门禁三态
```

「任务结果」在代码里有精确定义。被判定的不是一句孤立的终答，而是 `Expectation.Outcome`：

```text
Outcome(finalText, totalTokens, toolCallCount)
```

- `ExactMatch` / `Contains` 读 `finalText`——看起来像「评答案」
- `MaxTokens` 读 `totalTokens`——跑出答案但烧穿预算，任务仍失败
- `ToolCallCount` 读 `toolCallCount`——看起来像「评路径」，实际评的是路径的**结果形状**（修了 15 次还没收敛，就是失败形态）

四类断言共享同一个 Outcome，主语是「这次任务交出来的结果」，不是「模型在中间想了什么」。过程奖励、逐步打分、LLM-as-judge 的槽位留 v2，javadoc 写明：judge 自带非确定性 + 成本，不是这四个类型上的一个 flag。

---

## 4. 核心抽象和架构

eval 包 5 个类，M18.4 独立于 metrics/cost（只依赖 `agent-trace-export` 读轨迹）：

```text
eval/
  Expectation        sealed：ExactMatch / Contains / MaxTokens / ToolCallCount
                     + 嵌套 Outcome(finalText, totalTokens, toolCallCount)
  EvalCase           record：caseId + prompt + expectation + originRunId
  EvalDataset        case 集合：add / JSONL load-save / importFailures
  EvaluationRunner   批量重放：对每 case 跑 Subject → 判定 → 聚合
  EvalReport         record：passRate + results + baseline + verdict
```

`EvalCase` 的谱系契约：从失败 Run 挖出来的用例带 `originRunId`；手写用例走 `EvalCase.of(...)`，`originRunId=null`。缺席是诚实，伪造谱系才是错（对齐 14 的 metadata 纪律）。

`EvalDataset.importFailures` 的真实签名是三参，不是蓝图草图的两参：

```java
int importFailures(List<Trajectory> trajectories, double minReward,
                   Function<Trajectory, Expectation> expectationFor)
```

过滤三源：`DoneReason.ERROR` / `MAX_STEPS_EXCEEDED`，或 `reward != null && reward < minReward`。健康的 `DONE`、以及 `DONE` 但还没有 reward 的轨迹，都不会被挖进来——没打分不等于失败，缺席是诚实。prompt 取 `messages` 里第一条非空 USER。无 USER 的轨迹诚实跳过，返回值只计实际导入数。`expectationFor` 是人注入的翻译函数——「终答应含道歉」还是「工具至多调两次」，框架通译要么伪造断言要么空洞断言，两者都比问人一次更糟。

失败样本如何系统化进回归集，系列后段有专篇。本篇只要记住三件事：过滤条件以 `DoneReason` 和 reward 为准、谱系字段是 `originRunId`、断言不能由框架替你编。

`EvaluationRunner.Subject` 是 `@FunctionalInterface`：`Outcome run(String prompt)`。Runner 不建 Agent。Mock lambda 可测、真模型可评，装配层自己把 M18.1 装饰器桥到 Outcome 的 tokens / tool 计数。M18.4 刻意不 import metrics/cost，就是为了保住这条里程碑独立线——评估能在指标模块之前单独测绿。

四类断言还有两条和预算同构的踩线约定，写错就会误伤门禁：

- `MaxTokens`：`totalTokens <= max`，恰好用尽放行。烧过上限才失败。这和 `BudgetBook`「拒绝透支不拒绝踩线」是同一条边界语义。
- `ToolCallCount`：精确相等，不是「至多 N 次」。javadoc 写明脆性是确定性断言的代价——你要「至多两次」，就写 `expected=0/1/2` 三条，或等 v2 的不等式判定器。v1 不假装有模糊匹配。

`EvalCase.of(caseId, prompt, expectation)` 是手写入口，`originRunId` 显式为 null。从 `importFailures` 走出的用例走四参构造，谱系指回 `trajectory.runId()`。caseId 生成 `case-%04d`，撞上手写占用号就继续往下找空号，不覆盖、不复用。重复 `caseId` 的 `add` 直接 IAE——门禁按 id 寻址，撞号等于两个事故抢一个座位。

---

## 5. 一次完整数据流

`ObservabilityExample` T5 → T6 是评估的最小闭环：

```text
T5 失败样本回收
  scripted 空 Mock → chat 抛错 → ReActAgentLoop 收成 Status.ERROR
  → Trajectory.doneReason = ERROR
  → dataset.add(手写 Contains("answer"))
  → dataset.importFailures([failed], -0.4, t -> Contains("summary"))
  → 导入 1 条，originRunId == failed.runId()
  —— 修一个 bug = 数据集 +1 条用例，谱系不断

T6 回归门禁
  PromptManager.publish(..., "canary")  // 13 的通道，不重做
  EvaluationRunner.evaluate(dataset, fixedSubject, null, 1.0)
    → 首跑 verdict = BASELINE_ABSENT（建基线，不假对比）
  再跑同一 subject + 上一份报告当 baseline
    → verdict = PASS，canary 可提升
  反例：一案变坏，passRate=0.50 >= 地板 0.50，但 < 基线 1.00
    → verdict = FAIL，提升被阻断
```

门禁三态的判定顺序以代码为准，不是「无基线优先」：

```text
EvalReport.verdictOf 三级瀑布：
  1. passRate < minPassRate        → FAIL      （地板，首跑也生效）
  2. baseline == null              → BASELINE_ABSENT
  3. passRate < baseline.passRate  → FAIL      （相对回归）
  4. 否则                          → PASS
```

实现期第一版曾把「无基线」写成一票先决，结果首跑 0% 也不 FAIL——门禁变成装饰。测试三红之后改成地板优先。每个状态为谁存在：地板护绝对质量，基线护相对回归，`BASELINE_ABSENT` 只负责诚实标注。

`minPassRate` 踩线约定与 `BudgetBook` 同构：`passRate < minPassRate` 才 FAIL，恰好相等放行。

---

## 6. 最小代码或实验

可复现性是结构性保证，不是纪律承诺。`EvalReport` 无时间戳、无环境态；同 dataset + 同确定性 Subject，两次 `evaluate` 的 report `equals`。这条被 `EvaluationRunnerTest` 锁死，是门禁的生命线。

```java
EvalDataset dataset = EvalDataset.of(
        EvalCase.of("case-hand-1", "what is the answer?",
                new Expectation.Contains("answer")));

EvaluationRunner runner = new EvaluationRunner();
EvaluationRunner.Subject subject =
        prompt -> new Expectation.Outcome("the answer, in summary form", 400, 1);

EvalReport first = runner.evaluate(dataset, subject, null, 1.0);
// first.verdict() == BASELINE_ABSENT

EvalReport second = runner.evaluate(dataset, subject, first, 1.0);
// second.verdict() == PASS，且 second.equals(再跑一次的结果)
```

失败明细走 `failureDetails()`：每条 `CaseResult` 带 `caseId` + `detail`（期望 `describe()` + 实际摘要，文本截断 80 字符，复现拿全文）。subject 崩溃不中止评估——第 1 个 case 炸了，其余 7 个照判；明细写 `subject threw Xxx`。评估不重试：重试会掩盖被评估对象的真实行为，这和 `FallbackModelClient`「换供应商再试」刻意不同。

`EvalReport.of` 拒空结果列表：「空评估不是评估」。`minPassRate` 必须落在 `(0.0, 1.0]`。基线链会带着走——第二次报告的 `baseline` 字段就是第一份 report，第三次可以继续往下比。比较粒度是 `passRate` 级，case 级差异（「哪几条相对基线新挂了」）留 v2，v1 的 `failureDetails()` 只列本次失败，足够挡住提升。

JSONL 契约按 14 `TrajectoryCodec` 纪律手建树：`api_version` / `kind` 信封、snake_case、坏行带行号 fail-loud。改字段名是版本号升级，不是编辑。

```text
{"api_version":"v1","kind":"EvalCase","case_id":"case-0001",
 "prompt":"...","origin_run_id":"run-8842",
 "expectation":{"type":"contains","fragment":"道歉"}}
```

四类期望的 type 字面量：`exact_match` / `contains` / `max_tokens` / `tool_call_count`。未知 type、缺字段、重复 `case_id` 全部 fail-loud。

---

## 7. 常见误区

1. **「评估 = 看终答像不像」** —— `Contains` / `ExactMatch` 只是 Outcome 的文本面。一个修 bug 死循环调了 15 次工具、烧了 50k token 的 Run，终答可能仍「看起来对」。`MaxTokens` 和 `ToolCallCount` 存在，就是为了把这种失败形状收进任务结果。
2. **「上 LLM-as-judge 才专业」** —— v1 故意不做。judge 非确定 + 有成本，门禁会 flake 或永远 PASS。槽位在 `Expectation` 接口预留，不是这四个 record 上的开关。
3. **「首跑没有基线就放行」** —— 地板每次运行都生效。首跑 0% 也是 FAIL。`BASELINE_ABSENT` 只标注「高于地板但无可比对象」，接不接受首跑提升是装配层策略，不是门禁语义。
4. **「评估挂了就重试到绿」** —— `EvaluationRunner` 把 subject 异常记成该 case 失败，评估继续。重试是 Fallback 的可用性语义，用在评估上等于篡改样本。

---

## 8. 和相邻概念的区别

```text
EvalDataset（18）vs 全仓单元测试
  单测：Mock 可控、结果确定、毫秒级，护框架代码
  评估：真 LLM 非确定、判定有阈值、按 token 计费，护 prompt/模型/工具组合
  不可互相替代。"改 prompt"只有评估集能护住

EvalReport.passRate vs MetricsCollector.AgentStats.successRate
  前者：离线、对 dataset、门禁输入
  后者：线上、按 agent 聚合、DONE 才算成功
  验收原文要的「任务成功率」是两个口径都交付，不是选一个

评答案 / 评路径 / 评任务结果
  答案：终答文本（ExactMatch / Contains）
  路径：逐步是否最优（v1 不做过程奖励）
  结果：Outcome 三字段同时成立 —— v1 的主语
```

失败样本如何从轨迹变成用例、`originRunId` 怎样支撑「修一个 bug = +1 条」，系列后段还有专篇。本篇只把评估的主语和门禁三态钉死。

---

## 9. 我的设计判断

最重的一条：**门禁的生命线是可复现，不是看起来更聪明。**

评估一旦带上环境时钟、随机 judge、网络重试，报告就不再能 `equals`，发布线就失去了「同输入同输出」这种最便宜的信任。所以 `EvalReport` 把时间戳和环境态从类型里拿掉——可复现是 record 相等，不是 README 承诺。

第二条：失败形状到断言的翻译是领域知识。蓝图 D7 自己举例「终答不应包含 X / 应包含 Y」，恰恰证明翻译者是人。`importFailures` 多出来的 `expectationFor` 不是接口膨胀，是拒绝伪造断言。

第三条：回归和地板是 FAIL 的两个来源。修好 case-0007、弄坏 case-0003，正是这套门禁存在的理由。只看绝对阈值，会放掉「比昨天差」；只看相对基线，会放掉「整体已经不可用」。

---

## 10. 面试表达

> 「Agent 的评估对象不是一句终答，也不是中间想了什么，而是任务结果：文本、token、工具次数装在同一个 Outcome 里，用四类确定性断言判定。单元测试护框架代码，评估集护 prompt/模型/工具组合。门禁三态是 FAIL / PASS / BASELINE_ABSENT，判定顺序是地板优先、再标无基线、再比回归。同 dataset 同 Mock 两次跑，报告字段级相等——可复现是门禁的生命线。LLM-as-judge 留 v2，因为 judge 自己就是一份非确定性和一份账单。」

---

## 11. 下一篇连接什么

评估要能判 `totalTokens` 和 `toolCallCount`，先得有人在边界把它们采下来。下一篇回到运营投影本身：**Token、延迟、工具调用指标从哪来、为什么不埋在 loop 里、denied 为什么单列。**

→ [stage-18-article-3-token-latency-tool-metrics.md](stage-18-article-3-token-latency-tool-metrics.md)
