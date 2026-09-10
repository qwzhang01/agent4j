# E3 · Prompt Caching 前缀稳定性实验笔记（2026-09-10）

> 四周学习计划（`learning-path-2026-09-knowledge-points.md`）的第 3 个实验：E3 = KP3（Prompt Caching）落定实验。
> 回答决策 26 的核心问题：**ContextBuilder 契约要不要写进前缀稳定性；决策 9（compaction 就地改写）打掉缓存的真实代价是多少。**

## 一、实验设计

同一 10 轮对话 × 五种上下文管理策略 × 双计价风格（确定性模拟，无随机）：

| 策略 | 机制 | 变量 |
|------|------|------|
| A.baseline | 追加式历史，不压缩 | 对照基线 |
| B.flapping | 第 5 轮起全量改写历史，summary 文本每轮变化 | 前缀不稳定 |
| B2.stable | 第 5 轮起全量改写历史，summary 文本冻结 | 前缀稳定（与 B 同体量） |
| C.tail-only | 第 5 轮起保留头部一半 + 压缩尾部 | 混合：部分保前缀 |
| D.cascade | E2 的级联升级（j2/j3/j5 失败 → premium 重发原请求） | E2 交叉点 |

B 与 B2 是本实验的核心设计：**同体量对照**，只差「改写后的 summary 文本是否稳定」，把「前缀稳定性」从「体量缩减」中分离出来——不加这组，两个效应混在一起，结论会错（见发现 1）。

计价（每 1M token，microUSD 整数纪律，无浮点记账）：

- Anthropic 风格（显式缓存）：读 0.1x、写 1.25x，基价 $3/M input、$15/M completion
- OpenAI 风格（隐式缓存）：读 0.5x、写免费

token 估算：消息长度/4 粗分词（同 E2）。全程确定性可复现。

`CacheSimulatingModelClient` 的缓存域按 model id 分域（同模型才比 LCP），`CachePricing` 的三段计价互斥不重复（cached 读折扣 / written 写惩罚替代基价 / base 全价）。

## 二、实验结果

```
=== E3 cache comparison (10-turn conversation, deterministic sim) ===
prices: Anthropic-style on $3/M input, $15/M completion; read 0.1x, write 1.25x
scenario          cost($)   prompt tok    cached read  hit rate no-cache($)
A.baseline       0.004413         1946           1575    0.8094    0.008388
B.flapping       0.003932          676            334    0.4941    0.004578
B2.stable        0.003702          666            390    0.5856    0.004548
C.tail-only      0.004300         1307            913    0.6985    0.006471

D.cascade: cheap domain hit 0.0641 (5/78), premium domain hit 0.0513 (2/39), escalated prompt tokens billed twice: 39
D.cascade crossover: premium's re-issued prompt hits the same prefix in ITS OWN domain (first escalation LCP=0); the clean re-issue adds no new prefix pollution (first escalation is a cold domain start)

--- OpenAI-style implicit caching (writes free, reads 0.5x) ---
A.baseline       0.004912         1946           1575    0.8094    0.008388
B.flapping       0.003051          676            334    0.4941    0.004524
B2.stable        0.003135         666            390    0.5856    0.004548
C.tail-only      0.003919         1307            913    0.6985    0.006471
```

## 三、发现与结论

### 发现 1：体量缩减可以压过缓存损失（反直觉，改写了 B 的预设结论）

预设断言是「全量改写历史 = 缓存死亡 = 更贵」。数据相反：**B.flapping（$0.003932）比 A.baseline（$0.004413）便宜**。全量改写把 prompt 从 1946 token 缩到 676，体量收益压过了命中率损失（0.49 vs 0.81）。

但缓存损失是真实存在的，只是要对照正确的基线：B.flapping 对自己的 no-cache 线（$0.004578）只省 $0.000646；B2.stable 对自己的 no-cache 线（$0.004548）省 $0.000846。**同体量下，前缀不稳定让缓存价值缩水 24%**——这是「稳定性」变量单独的代价，与体量无关。

教训（对实验设计本身）：不加 B2 同体量对照，「压缩是否伤害缓存」这个问题会被「压缩是否省钱」污染。分离变量是对照实验的命门。

### 发现 2：计价风格反转结论——前缀纪律是「供应商定价的」，不是普适真理

Anthropic 风格（写 1.25x）：B2.stable（$0.003702）< B.flapping（$0.003932），稳定前缀值得买。
OpenAI 风格（写免费）：B.flapping（$0.003051）< B2.stable（$0.003135），结论反转！

机制：OpenAI 写免费时，稳定 summary 多携带的体量（第 5 轮后每轮都带着 4 轮的 summary）是纯损失，缓存读折扣（0.5x）救不回来。**「前缀稳定性」值多少钱取决于供应商怎么给写计价**——写有惩罚才需要稳定纪律，写免费则压缩更自由。

工业界印证：Anthropic 官方文档把「稳定前缀 + cache_control 断点」当最佳实践反复强调，OpenAI 的隐式缓存文档只轻描淡写「prompt 前缀相同自动命中」——两家供应商的文档姿态差异，本质是计价结构差异的镜像。

### 发现 3：C.tail-only 是「既要又要」的正确姿势（在显式计价下）

命中率 0.6985（介于 A 的 0.81 与 B 的 0.49 之间），成本 $0.004300 高于 B/B2 但低于 A。尾部压缩保住了头部前缀的命中，代价是尾部新 prefix 的写入惩罚。在 Anthropic 风格下它是「预算紧张时的折中」；在 OpenAI 风格下（写免费）它反而不优（$0.003919 > B.flapping $0.003051）——又指向发现 2 的结论。

### 发现 4：级联的 clean re-issue 在缓存视角下是域隔离的冷启动，不是污染

D 场景验证了 E2 留下的交叉点：

- cheap 域命中率 0.0641（5/78），premium 域 0.0513（2/39），两域互不串
- premium 首次升级 LCP=0：它从未见过这个 prompt，冷启动是正确行为
- 升级的 prompt 在两域都计费（double-billed 39 token）——账目诚实
- **clean re-issue（重发原始请求）不把 cheap 的失败文本带进 premium 的前缀**——构造上就无污染，E2 的设计在缓存维度得到量化确认

注意：D 的绝对命中率低（0.06）是因为任务集是 6 个独立单轮任务（E2 任务集），不是长对话——这正常，D 只为验证域隔离语义，不做成本对比。

### 发现 5：`TokenUsage.cachedTokens` 的口径约定（E3 的契约产出）

全链路统一为一个计费形态：`promptTokens` = 完整计费 prompt（含缓存命中部分），`cachedTokens` = 命中部分（读折扣）。Anthropic 的 `cache_creation` 折叠进 promptTokens（它是真实支出，但按写价计），`cache_read` 计入 cachedTokens。`CachePricing.promptCostMicros(prompt, cached, written)` 三段互斥：cached×0.1x + written×1.25x + base×1x，无双重计费。

这个口径已落进：`TokenUsage`（core，record + 兼容构造器）、`ModelCallMetrics`/`RunMetrics`（observability）、`TrajectoryCodec`（trace-export，JSON 往返）、`OpenAiModelClient`（`prompt_tokens_details.cached_tokens`）、`AnthropicModelClient`（cache_read/cache_creation 折叠 + 流式 message_start 捕获）。

## 四、交付物清单

主代码（`agent-observability/src/main/java/.../routing/`）：

- `CachePricing.java` — 缓存计价 record：三段互斥计价（cached/written/base），Anthropic 与 OpenAI 两种风格工厂，整数 microUSD
- `CacheSimulatingModelClient.java` — 缓存模拟装饰器：按 model 分缓存域，LCP 前缀匹配算命中，cachedTokens 写回 usage；供应商已报值优先

核心扩展（agent-core）：

- `ModelResponse.TokenUsage` — 加 `cachedTokens` 第四字段（四参 record + 三参兼容构造器，normalize：负值归零、超 prompt 截断）

下游接线：

- `MetricsCollector`/`RunMetrics`/`ModelCallMetrics` — cached 聚合（obs 层 run 汇总可见）
- `RecordingSession`/`TrajectoryCodec` — trajectory 聚合与 JSON 往返（`cached_tokens` 字段，旧档读档兼容）
- `OpenAiModelClient`/`AnthropicModelClient` — 供应商侧真实 cache 字段解析（非流式 + 流式）

测试（`agent-observability/src/test/java/.../routing/`）：

- `CachePricingTest.java`（7 用例：乘数/三段计价/校验）
- `CacheSimulatingModelClientTest.java`（8 用例：冷启动/全命中/前缀命中/改写全失/域隔离/供应商优先/流式/归一化）
- `E3CacheExperimentTest.java` — 对照实验 harness（成本表 + 结构不变量断言）

模块测试：agent-observability 176/176（160 存量 + 16 新增），全仓 22 模块 BUILD SUCCESS（含 core 49、model 44、trace-export 73 的回归）。

## 五、与既有决策的关系

- 决策 9（compaction 就地改写历史）：张力落定——「就地改写」本身不是错，错的是「改写产物不稳定」。若 summary 每次生成都变，在写计价的供应商下持续付缓存死亡税；把 summary 固化（或增量追加），前缀就能继续命中。决策 26 细化而非推翻决策 9。
- 决策 25（路由两层）：D 场景验证了 clean re-issue 的缓存语义（域隔离、无污染），级联的双重计费在缓存口径下依旧诚实。
- 决策 5（装饰器优于继承）：`CacheSimulatingModelClient` 是它第四次验证——缓存观测天然属于请求序列属性，只能住 ModelClient 边界。
- KP2（Token Budgeting）：cachedTokens 进四本账的「历史」账，预算器读的是完整计费口径。

## 六、遗留与下一步

- 真实 API 计费验证（需要 OPENAI_API_KEY / ANTHROPIC_API_KEY 跑真实对照）
- Anthropic `cache_control` 显式断点机制（数量有限的最优断点摆放）——本轮只做了价格参数变体
- compaction 与压缩时机的「对话自然边界对齐」策略（KP3 文献主张，需结合真实压缩器实验）
- 全局 Token 预算器（KP2 主张，四本账超支截断顺序）——独立实验
