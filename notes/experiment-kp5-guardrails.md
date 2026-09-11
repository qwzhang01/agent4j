# 实验记录：KP5 Guardrails 三层——补上 input / output 两道门（2026-09-12）

> 中间层（tool）已是 `GovernedToolExecutor`。本轮只补进 loop 前、见用户前。

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

## 和 Sanitizer 的关系

`SanitizerGuardrail` 把 Stage 9 `ResultSanitizer` 接到门上：BLOCK 策略 → `Block` 裁决；SANITIZE/TRUNCATE → `Rewrite`。框架给挂钩，策略仍归宿主。
