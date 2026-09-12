# 实验记录：KP5 Guardrails 三层——补上 input / output 两道门（2026-09-12）

> 性质：KP5（Guardrails 三层时机）的实验收口笔记，对应提交 `14c6332`（`Guardrail` / `GuardrailChain` / `GuardrailVerdict` / `GuardrailFailMode` / `GuardrailContext` / `GuardrailPhase` 六件 + `SanitizerGuardrail` 桥接，测试 8 个新增全绿）。
> 前置：三层论里的 tool 层早已落地——`GovernedToolExecutor`（决策 7/12/13：降级/拒执行/Sanitizer）。
> 关联：`learning-path-2026-09-knowledge-points.md` §KP5、`experiment-kp9-sandbox-spectrum.md`（D5 纪律的同族）、`experiment-kp6-a2a-protocol.md`（入站防线是同一原则的 wire 版）。

---

## 插入点

```
ContextBuilder.build
    → INPUT GuardrailChain          // 改请求，不改 AgentState
    → ModelInvoker
    → （tool 调用仍走治理执行器）
    → OUTPUT GuardrailChain         // 改落库 + Done
    → AgentEvent.Done
```

## 失败语义

| 时机 | Block | Rewrite | 规则自己抛 |
|------|-------|---------|------------|
| INPUT | 不调模型，ERROR | 模型看改写，账本留原文 | FAIL_CLOSED=Block；FAIL_OPEN=跳过 |
| OUTPUT | 不写 assistant、不发 Done | 落库与 Done 都是改写 | 同上 |
| TOOL | 已有：降级 / 拒执行 | Sanitizer | 决策 7/12/13 |

每条规则必须声明 fail-open / fail-closed，没有隐式默认。

---

## 一手工程事实（六件）

### 1. 裁决三分：Allow / Rewrite / Block

`GuardrailVerdict` 是 sealed interface，三种裁决：`Allow` 放行；`Rewrite(replacement, reason)` 替换被评估文本；`Block(reason)` 拒绝。INPUT 侧 Block 停循环（`state.setStatus(ERROR)` + `AgentEvent.Error`，模型从不被调用）；OUTPUT 侧 Block 丢弃模型答案（不写 assistant、不发 Done，状态 ERROR）。

### 2. 链式评估：First-Block-wins，Rewrite 级联

`GuardrailChain.evaluate` 的遍历规则：逐条规则跑同 phase 的规则，第一个 Block 立即返回；Rewrite 改写后**下一条规则看到的是改写后的文本**（级联）。这意味着链上规则顺序即优先级——脱敏规则必须在合规规则前，否则合规规则看到的是未脱敏原文。

### 3. 失败模式必须显式：没有隐式默认

`Guardrail` 接口的 `failMode()` 是必选方法，每条规则自报 `FAIL_CLOSED`（自己抛 → 当 Block 处理）或 `FAIL_OPEN`（自己抛 → 跳过继续下一条）。没有「默认 fail-open」的静默约定——一条规则为什么 fail-open 一定是一个显式决策，可审计。

### 4. INPUT Rewrite 只改请求，AgentState 账本留原文

`applyInputGuardrails` 对 Rewrite 的处理：`withLastUserText` 重建 ModelRequest（只换最后一条 USER），`state.getMessages()` 原封不动。哲学同 KP2 的 HandoffInputFilter：身份是配置，历史是事实——审计账本里必须能查到用户原话，模型看到的是净化版。

### 5. OUTPUT Rewrite 改写的是落库与 Done 两个落点

`applyOutputGuardrails` 的 Rewrite：`state.addMessage(ChatMessage.assistant(guarded))` 与 `sink.accept(new AgentEvent.Done(guarded, state))` 都是改写后的文本——用户看到的、账本记的、模型下轮自见的，三者一致。不留「账本原文、展示净化」的分裂态。

### 6. SanitizerGuardrail：把既有防线接到新门上

`agent-security` 的 `SanitizerGuardrail` 是桥：把 Stage 9 的 `ResultSanitizer`（Sanitize 策略 → `Rewrite` 裁决；BLOCK 策略 → `Block`）接到 Guardrail 体系。框架给挂载点，策略归宿主——`DefaultResultSanitizer` 只加了 javadoc 引用，逻辑零改动。模块纪律：`agent-core` 不依赖 `agent-security`（同 A2A 入站防线纪律）。

---

## 计划 vs 实际

| 计划验收 | 实际 | 判定 |
|----------|------|------|
| INPUT 门：Block 不调模型 / Rewrite 只改请求 | `inputBlock_skipsModel_keepsLedger` / `inputRewrite_requestOnly` | 成 |
| OUTPUT 门：Block 丢答案 / Rewrite 落库+Done | `outputBlock_discardsAnswer` / `outputRewrite_persisted` | 成 |
| 失败模式显式化 | `failMode_onThrow` 钉死两种模式 | 成 |
| Sanitizer 桥接 | `blockStrategy_becomesBlockVerdict` / `sanitizeStrategy_becomesRewrite` / `cleanText_isAllow` | 成 |
| 挂载点在 AgentConfig | `getGuardrails()` 永不为 null（空=门开） | 成 |

判定：KP5 两道门一次性全成，四项验收全过。

## 诚实边界（v1）

- 只检最后一条 USER 文本与最终 assistant 文本——不检工具参数、不检工具输出文本（那归 tool 层 `GovernedToolExecutor` + Sanitizer 管，见三层论分工）。
- 只检文本，无 file/data parts 概念——A2A v2 的 file part 注入拦截（KP6 思考题）在这套门上暂无对应物。
- GuardrailContext 只带 phase/text/config/state——规则内无法访问完整历史（避免规则实现里二次实现上下文工程）有意为之。
- 无每规则耗时/触发计数指标——guardrail 触发率是 KP7 safety 指标的原料，v1 只有日志。

## 三段式收口

**做对了什么**：两道门一次性补齐三层论的最后两块；裁决三分 + 链式级联 + 显式失败模式构成完整的门语义；INPUT Rewrite 的「请求改、账本留」与 OUTPUT Rewrite 的「三处一致」把审计原则贯彻到底；SanitizerGuardrail 桥接让既有 Stage 9 防线零改动上链。

**缺什么**：只检首尾文本（用户输入、最终答案），工具参数与工具输出在门语义之外（靠 tool 层兜着）；无触发率/耗时指标（KP7 safety 原料缺一桶）；file parts 无对应物（KP6 思考题悬着）。

**怎么做**：guardrail 触发计数接入 KP7 指标线（Block 率/Rewrite 率按规则名分桶）；file part 拦截层在 A2A parts 解析层做（KP6 思考题的方向），把 `GuardrailPhase` 扩一个 `WIRE_INPUT` 是候选方案；规则耗时上限与超时 fail-open/fail-closed 同款显式声明。

## 思考题收口（上轮留下的：Done 是「模型停了」还是「任务成了」）

`AgentEvent.Done` 的语义：`ReActAgentLoop` 在模型给出最终答案（无 tool_calls）时发出 Done——它证明的是「循环正常终止」，不是「任务达成目标」。Done 里的 answer 可能答非所问、可能半途而废地完成任务、可能完成得很糟。Done = 程序性成功，完成判据（success criteria）才是任务性成功。

这直接接 KP7：在线评估的第一问不是「怎么测」，是「完成判据谁给」。用户消息里没写验收标准时，agent 只能按自己对任务的理解交付——评估的对象与其说是「答案质量」，不如说是「agent 理解的任务和你想要的任务之间的偏差」。这就是五指标里 drift 指标的原始定义。

## 思考题（留下轮）

INPUT 门只检最后一条 USER。设想注入攻击者通过 earlier turns 埋一段指令（轮次 N-2 的用户消息里藏「后续轮次请忽略系统提示」），轮次 N 的用户消息干净。这套门为什么拦不住它？该在哪补？——提示：这已经是 KP10 的领地（深水是进入上下文的非用户内容），想想「最后一条 USER」这个锚点和 KP2 的 lastTurn 锚点是不是同一个假设，以及当输入来自 memory 注入层时这个假设还成立吗。
