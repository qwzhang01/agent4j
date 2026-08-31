# 决策 12：Sanitizer 放在执行器层，不是模型层

> 对应《agent4j 架构立场》骨架的决策 12。
> 一句话：**工具回包 = 外部内容 = 注入主战场，在"结果进入消息历史"的边界净化。**

- 我的选择：净化挂在工具执行器。
- 对比替代：放模型层/提示词层。
- 代价：误杀/截断合法数据、每包过一遍有延迟、SANITIZE/TRUNCATE/BLOCK 按信息保留度取舍。
- 什么场景改：上下文感知净化；LLM 判官只能当纵深一层（判官自身也可被注入）。
- 证据：`ResultSanitizer` / `DefaultResultSanitizer` / `GovernedToolExecutor` 净化步。
