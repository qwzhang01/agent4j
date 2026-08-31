# 决策 9：compaction 就地改写 state.getMessages()，而不是只压当次请求

> 对应《agent4j 架构立场》骨架的决策 9。
> 一句话：**有损变换必须作用于"真相"（state），否则 Checkpoint 存的就不是模型看到的东西——决策 8 的配套规则。**

## 实现

```java
// CompressingContextBuilder / MemoryContextBuilder
var result = compressor.compress(messages);
if (result.didCompress()) {
    messages.clear();                    // 就地改写 state
    messages.addAll(result.compressed());
    archive(result.archived(), config);  // 原文归档为 SUMMARY
}
```

`ContextCompressor` 保留 system + 最近 K 条，旧段总结成 `[Summary of earlier conversation]`，原文归档进 MemoryStore。

## 为什么必须改 state，不压请求副本

反证：只压请求副本不改 state——

```text
T1: state 100 条消息，请求副本压成 20 条发给模型
T2: 模型看到 20 条，state 还是 100 条
T3: Checkpoint 存 state（决策 8）→ 存了 100 条
T4: 恢复后模型看到 100 条，与 T2 的 20 条不一致
```

恢复后状态与模型见过的历史漂移。所以压缩必须写回 state，让"存什么 == 发什么"。

## 代价（必须答）

1. **有损且不可逆于 state**：老消息细节被总结替代。缓解：原文归档进 store（SUMMARY 条目）供回溯。
2. **读操作带写副作用**：build() 名义上组装上下文，实际改写 state——与决策 6 的"返回契约掩盖副作用"同一泄漏。
3. **摘要质量决定损失**：总结差就真丢信息（归档是原始文本堆，不参与推理）。

## 什么场景会改

- 要无损压缩（压缩只影响本次请求、state 保留完整历史）→ 放弃"存 state = 发 state"，改双源：state 存完整历史 + 另存"已发送视图"。这是 event sourcing vs snapshot 路线切换。
- 归档不够、需要结构化回溯（旧消息按主题检索）→ 归档策略升级。

## 架构师洞察

```text
决策 8：Checkpoint 存 state（真相）
决策 9：有损变换必须改写 state（保证真相 == 模型所见）
```

合起来一条铁律：**快照一致性——Checkpoint 存的必须等于实际发给模型的。**

这是 durable execution 的核心。很多人做 compaction 只压请求副本，结果恢复后上下文漂移还查不出来。

## 面试表述

> 我的 compaction 就地改写 state.getMessages()，不只压当次请求。
> 因为 Checkpoint 存 state，若压缩只发生在请求副本，恢复后 state 还是完整历史，模型所见与压缩前不一致。
> 代价是压缩有损且写回 state 不可逆，所以用"原文归档成 SUMMARY 条目"补回追溯。
> 这和"存 state 不存 request"是配套两条规则：有损变换必须作用于真相。

## 关联

- 证据：`CompressingContextBuilder.build()` / `MemoryContextBuilder.build()` 的 `messages.clear()+addAll`；`ContextCompressor.CompressionResult(compressed, archived, didCompress)`；归档 SUMMARY 条目。
- 决策 8 是前提；决策 6 的 build() 副作用在这里具象化。
