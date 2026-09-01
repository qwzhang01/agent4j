# 决策 12：Sanitizer 放在执行器层，不是模型层

> 对应《agent4j 架构立场》骨架的决策 12。
> 一句话：**防御要放在"外部内容进入内部"的边界，不放在"内部处理"的模型里——边界是确定性代码，模型是可被注入的推理。**

## 一、决策在说什么

```text
delegate.execute(toolCall)       // 执行工具，拿到外部内容
        ↓
resultSanitizer.sanitize(result) // 在"结果进入上下文"的边界净化
        ↓
返回净化后的结果给模型
```

净化放执行器层（工具结果边界），不是模型层。

## 二、三套模式 + 三种策略

`InjectionPattern` 三类：

```text
1. role-spoofing        "[SYSTEM]"、<|im_start|>system、System:
2. instruction-override "ignore previous instructions"、"you are now"、"忽略以上指令"
3. sensitive-exfil      "send api_key to https://..."、URL+send/upload、发送到 URL
```

`DefaultResultSanitizer` 三策略（按信息保留度递减）：

| 策略 | 行为 | 保留 |
|---|---|---|
| SANITIZE | 命中片段替换 [REDACTED] | 高 |
| TRUNCATE | 从首个命中截断 | 中 |
| BLOCK | 整体替换 blocked 提示 | 零 |

默认 SANITIZE。

## 三、为什么放执行器层，不模型层

工具回包 = 外部内容 = 注入主战场；唯一能设卡的边界是执行器。

放模型层反证：模型自己可被注入，把净化塞进提示词，一句 "ignore previous instructions" 就绕过；模型侧净化是"挪一层"，不是"解决一层"。

递归问题：LLM 当判官，判官自己也会被注入。第一道防线必须在模型外的确定性代码层。

## 四、v1 用正则，不是语义

接口 javadoc：

> "v1 uses pattern matching, not semantic analysis. Semantic-level detection (LLM judge) can be added in v2 without changing this interface."

两个设计点：

1. v1 明确正则，便宜、确定、无递归问题。
2. 接口已为 v2 LLM 判官留好缝——换实现不换接口。

## 五、代价（答出"能防什么、防不住什么"）

1. 正则只拦模板式攻击（大多数），拦不住语义诱导/多跳的精锐攻击——垃圾邮件过滤器定位，撒大网不是精确打击。
2. 误杀合法内容（正常出现 "System:" 等）；SANITIZE 可能只命中一个变体、漏其他变体。
3. 每次工具调用都扫一遍（正则便宜，但仍有开销）。
4. 三策略是安全与信息保留的权衡，没有完美选项。

## 六、诚实定位

v1 净化是纵深防御里最便宜的一层网，不是完整方案。

```text
决策 11 权限：事前，管"能不能调"
决策 12 净化：事中，管"结果干不干净"
决策 13 审计：事后，管"发生了什么"
```

说清"能拦什么、拦不住什么"，比吹"我有注入防御"强一百倍。

## 七、什么场景会改

- 精锐攻击变多 → LLM 判官做第二层（纵深一层，非替代）。
- 误杀严重 → 按工具类型定制策略（某工具永远 BLOCK）。
- 语义检测 → 换 ResultSanitizer 实现，接口不动。

## 八、架构师洞察

防御放在边界，不放在模型；边界是确定性代码，模型是可被注入的推理。
三段治理闭环：事前权限 + 事中净化 + 事后审计。

## 九、面试表述

> 注入防御放工具执行器层，不模型层：工具回包是注入主战场，模型自身可被注入，放模型侧只是挪一层。
> v1 正则匹配三类模板 + 三档策略，接口已为 v2 LLM 判官留缝。
> 代价：正则只拦模板式、可能误杀；它是最便宜的一层网，不是完整方案。
> 配合权限（事前）和审计（事后）构成完整治理闭环。

## 关联

- 证据：`ResultSanitizer` / `DefaultResultSanitizer`（三策略）/ `InjectionPattern`（三类）/ `GovernedToolExecutor` 净化步。
- 决策 11 事前、决策 13 事后，三者构成闭环。
