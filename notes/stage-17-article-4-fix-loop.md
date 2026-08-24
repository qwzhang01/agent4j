# 测试失败后 Agent 如何进入修复循环

> 配套蓝图：[architecture-stage-17.md](architecture-stage-17.md) §1（验证/循环假设）+ D3/D4 · 对应实现：`agent-coding/exec/RunTestsTool.java`、`session/CodingSession.java`、`session/FixLoopPolicy.java`
> 上一篇：[stage-17-article-3-requirement-to-patch-state-machine.md](stage-17-article-3-requirement-to-patch-state-machine.md)
> 状态：✅ Stage 17 已完成

---

## 1. 我今天要解决什么问题

上一篇讲到 `DRAFT → VALIDATED` 的触发者是「测试通过」。但测试**失败**时呢？失败才是 Coding Agent 的常态。

这一篇回答两个连在一起的问题：

```text
1. 测试失败后，Agent 怎么"自然地"进入修复，而不是靠硬编码的 if-else？
2. 修复循环怎么保证"有界"——修不好还一直修 = 无限烧 token，谁来踩刹车？
```

答案分别是「节奏在模型」和「边界在引擎」——这八个字是修复循环的全部设计。

---

## 2. 为什么会有这个问题

先看一个关键认知：**Coding Agent 是三个场景里唯一自带客观裁判的。**

```text
企业 Agent：产出对不对，由用户/管理员判断
游戏 Agent：行为合不合理，由数值限幅间接约束
Coding Agent：改得对不对，跑一遍测试就知道 —— 裁判从人换成了工具
```

这个「唯一自带裁判」既是优势（奖励信号内生），也是难点：

- 优势：不需要人来判对错，编译过没过、测试绿没绿是客观的。
- 难点：**裁判的信号（失败）必须能驱动模型进入修复**，而不是死在那里；同时模型可能「修不好还一直修」，必须有人给它踩刹车。

于是有了两个设计问题：**怎么引导修复**（节奏），**怎么限制修复**（边界）。

---

## 3. 它解决了什么问题

- 「**节奏在模型**」解决「怎么引导修复」：失败的输出摘录（`outputExcerpt`）天然包含「预期 vs 实际」，模型读到它，ReAct 自然进入「读失败 → 改补丁 → 再测」的收敛流。修复不是硬编码的流程，是模型的自发行为。
- 「**边界在引擎**」解决「怎么限制修复」：`FixLoopPolicy` 计数**失败轮数**，超限后 `run_tests` 返回 `[LIMIT]`，直接拒绝再执行。踩刹车的不是「再试一次试试」，而是引擎级的硬拒绝。

---

## 4. 核心抽象和架构

### 4.1 测试即裁判：裁判不能由被裁判者指定（D3）

`RunTestsTool` 的关键设计：

```java
// 测试命令在装配期注入，schema 无参数 —— 模型不能自选裁判
new RunTestsTool(List.of("mvn", "test"), whitelist, runner);

// 为什么？反例：模型可以 run_command("mvn test -DskipTests") → "全绿" → 交付
// 被裁判者指定了裁判，验证完整性归零。测试命令是 Constitution，不是 choice。
```

测试实证（`RunTestsToolTest.argumentsIgnored`）：模型走私 `mvn test -DskipTests`，结果固定裁判照跑，走私无效。

### 4.2 修复环的边界：maxSteps 为什么不够（D4）

`FixLoopPolicy` 只数「测试失败后的修复轮数」：

```java
public record FixLoopPolicy(int maxFixIterations) {
    public static final FixLoopPolicy DEFAULT = new FixLoopPolicy(3);
    // 校验 >= 1：初始测试必须可跑（一个连测试都跑不了的会话没有裁判）
}
```

为什么不用 Stage 2 已有的 `maxSteps`？因为两者数的是不同的东西：

```text
maxSteps     数"所有步数"：读 5 个文件 + 改 2 次 + 测 1 次 = 8 步（健康）
             但它分不清上面这种健康探索，和"改-测-败 ×10"的失血循环

FixLoopPolicy 数"失败轮数"：只有 tested && failed 才计数
             健康探索不消耗预算，只有失败后的再修复消耗
```

### 4.3 两层分工：边界在引擎、节奏在模型

```text
引擎层（硬边界）—— CodingSession.LimitedTestsTool：
  execute 前检查 failedRuns >= maxFixIterations
    -> 超限返回 "[LIMIT] fix budget exhausted ... report honestly"
    -> 拒绝执行（veto），预算不再消耗
  每次 FAILED -> failedRuns++

模型层（自然节奏）—— ReAct 循环：
  读失败摘录 -> write_file 修复 -> 再测
  顺序完全由模型驱动，systemPrompt 行为契约引导（"先读后改、小步补丁"）
```

`LimitedTestsTool` 是 `run_tests` 的一个 **Tool 装饰器**（同样实现 Tool 接口，治理链和 Agent 无感知），它把「预算检查」塞在真正的 `RunTestsTool` 前面。

---

## 5. 一次完整数据流

以 `CodingAgentExample` 的真实剧本（`check.sh` 是真脚本，grep 真实工作区）：

```text
1. write_file(v1)           -> 暂存：divide 无除零守卫
2. run_tests                -> LimitedTestsTool.execute
                               -> 检查预算：failedRuns(0) < 3，放行
                               -> materialize 写盘（裁判要看到暂存变更！）
                               -> check.sh 跑，发现无 guard -> 红
                               -> failedRuns++  = 1
                               -> 返回 {passed:false, output_excerpt:"...Failures: 1..."}
3. 模型读 excerpt           -> 看到失败原因 -> 自然决定修（不是硬编码）
4. write_file(v2)           -> 同路径替换（修复环里 stage 是替换，不是叠加）
5. run_tests                -> 再 materialize -> check.sh 绿
                               -> passed，不计数（failedRuns 仍 = 1）
                               -> onVerdict: passed -> markValidated() -> Patch DRAFT->VALIDATED
```

如果一直修不好，走到 `failedRuns = 3`，第 4 次 `run_tests`：

```text
-> "[LIMIT] fix budget exhausted: 3 failed test run(s) (policy max 3).
    Stop fixing and report the failure honestly - the staged patch is kept for review."
-> 不再执行命令，failedRuns 不再增长
```

### 5.1 M17.5 才显形的关键机制：materialize

这里有一个蓝图时序图里藏着、装配时才暴露的问题：

```text
暂存纪律是"不落盘"，但测试命令跑在真实磁盘上。
不落盘 -> 裁判永远看不到修改 -> 修复环形同虚设。
```

解法（`PatchStore.materialize()`）：`run_tests` 执行前把暂存变更**物化写盘**（幂等），裁判就能看到；人拒绝时 `revert()` 恢复。这是下一篇沙箱检验的前置，也是修复环能真实运转的前提——**没有 materialize，就没有修复环**。

---

## 6. 最小代码或实验

修复环边界的最小实验（`CodingSessionTest`）：

```java
// 失败才计数，通过不计数
session = session(List.of("ls", "/definitely-not-there-xyz"), 3);
runTests.execute(null);  // 失败
runTests.execute(null);  // 失败
assertEquals(2, session.failedTestRuns());

// [LIMIT]：预算耗尽后拒绝执行，且不再消耗
session = session(failing, 1);
runTests.execute(null);  // 那一次允许的失败
String second = runTests.execute(null);
assertTrue(second.startsWith("[LIMIT]"));
assertEquals(1, session.failedTestRuns());  // 被 veto 的调用不计数

// 通过即出环：passed 后 Patch -> VALIDATED
```

还有一个值得亲手验证的反例实验：**走私裁判**。给 `run_tests` 传 `{"command":["mvn","test","-DskipTests"]}`，观察结果——固定裁判照跑，走私无效。

---

## 7. 常见误区

1. **「修复循环用 maxSteps 兜底就行」** —— 不行。maxSteps 数所有步数，分不清健康探索和失血循环；修复预算只数失败轮数。两者语义不同，各管各的。
2. **「测试失败后写个 while 循环自动重试」** —— 这正是「节奏在引擎」的反面。修复的顺序、改哪个文件、怎么改，必须由模型决策（ReAct 的自然行为），引擎只做边界（veto）。硬编码的 while 重试会变成「同一个错误反复试」。
3. **「[LIMIT] 后自动丢弃 Patch」** —— 本实现选择**不自动丢弃**：暂存内容（改了什么而失败）是复盘证据，自动销毁证据是反模式。丢弃是显式动作（`discardPatch()`）。
4. **「测试命令也应该让模型灵活指定」** —— 会让裁判失效。模型指定裁判 = 被裁判者自选裁判 = 验证完整性归零。

---

## 8. 和相邻概念的区别

三种「环」三种语义（本篇 + 前篇提到的边界，完整版）：

```text
maxSteps（2）        防"模型喋喋不休"     步是事故，数步数
图引擎环保护（5）    防"流程图成环死循环"  环是设计错误（DAG 拒绝成环）
修复环边界（17）     防"修不好还一直修"    环是方法，数失败轮数

前两者把环当敌人，本阶段把环当方法——但给它一个 token 预算意义上的刹车。
```

另一个对比：**TestResult 是 Stage 14 的天然接口**（v2 彩蛋）。`testPassed` 直接映射成 rule reward（+1.0），编码轨迹就能变成 RL 训练数据——这是 Coding Profile 和 AI Infra 主线的交叉点，留给未来。

---

## 9. 我的设计判断

最重的一条：**「节奏在模型、边界在引擎」这个分工，是修复循环的灵魂。**

很多人做 Coding Agent 会走向两个极端：要么硬编码「改→测→修」的 while 循环（剥夺模型决策权，同一错误反复试）；要么完全交给模型自生自灭（烧 token 到天荒地老）。正确姿势是**两层分工**：模型决定「怎么修」（节奏），引擎决定「最多修几次」（边界）。这个分工和 Stage 16 的限幅设计（引擎硬边界 + 模型自然行为）是同构的——它几乎成了这套 Runtime 处理「非确定性」的通用手法。

其次，`FixLoopPolicy` 校验收紧为 `>= 1` 这个细节（M17.4 实录）值得记住：0 看起来合法，但「一个连初始测试都跑不了的会话」没有裁判，流程根本不成立。**边界参数的极端值要跑一遍流程语义再定合法性**。

---

## 10. 面试表达

> 「修复循环我的设计是八个字：节奏在模型、边界在引擎。测试是 Coding Agent 唯一自带的客观裁判——但裁判不能由被裁判者指定，所以测试命令装配期注入、无参数，模型走私 `-DskipTests` 也无效。测试失败后，失败摘录天然引导模型进入修复，顺序由模型决定；引擎只做一件事——数失败轮数，超过预算就返回 `[LIMIT]` 拒绝再执行。为什么不用 maxSteps？因为 maxSteps 数所有步数，分不清健康探索和失血循环，修复预算只数失败轮数。还有一点：`run_tests` 前要先 materialize 把暂存变更写盘，否则裁判看不到修改，修复环是空转的。」

---

## 11. 下一篇连接什么

下一篇讲「可观测性和审批」：为什么 Coding Agent 的每一个工具调用都要留痕，审批档位为什么「跟着真副作用走」而不是「跟着工具名走」，以及审批疲劳这个真实的敌人。

→ [stage-17-article-5-observability-approval.md](stage-17-article-5-observability-approval.md)
