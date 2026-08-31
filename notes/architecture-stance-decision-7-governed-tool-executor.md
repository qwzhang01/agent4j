# 决策 7：治理用 GovernedToolExecutor 装饰 DefaultToolExecutor，不改 Tool 接口

> 对应《agent4j 架构立场》骨架的决策 7。
> 一句话：**工具定义"能做什么"，执行器定义"允许做什么"——治理和能力正交，用装饰器叠加。**

## 实现

```java
ToolExecutor governed = new GovernedToolExecutor(
        new DefaultToolExecutor(registry),   // 被装饰
        policy, approval, sanitizer, auditor); // 治理四件套
```

`Tool` 接口只有 getName / getDescription / getParametersSchema / execute，完全不感知治理。MCP 工具适配成 Tool 后自动走同一套。

## 为什么这么挂

1. **工具作者零负担**：权限/审批/审计是平台职责，不写进工具。
2. **治理统一收口**：本地 / SPI / MCP 全过同一个 GovernedToolExecutor，无旁路。
3. **向后兼容**：装饰 DefaultToolExecutor 不替换；治理组件 nullable，缺了退化回裸执行。
4. **与决策 5 同源**：装饰器叠加正交关注点。

## 代价（必须答）

1. **fail-closed 风险**：配 REQUIRES_APPROVAL 但没配审批服务 → 直接 DENY。宁可误拒不放行，是刻意选择但要能说清。
2. **前台变上帝对象**：execute 六步八出口（查权限→审批→限流→执行→净化→审计），单点复杂度高。
3. **每次调用过链**：所有工具调用多一层检查，有延迟/吞吐开销。
4. **粒度只在工具级**：参数级治理（shell 工具 ls 放行 / rm 拦截）是 v2。

## 什么场景会改

- 参数级权限 → 治理下沉到参数层。
- 上下文感知策略（A 用户可调 / B 不可调）→ 策略要读调用上下文，不是静态名单。

## 架构师洞察

"统一扩展语言"三件套的最完整案例：

```text
决策 5  ModelClient  装饰器   （重试/超时/降级）
决策 6  AgentLoop    挂载点   （ContextBuilder）
决策 7  ToolExecutor 装饰器   （权限/审批/净化/审计）
```

共同基因：接口插槽 + 可换实现 + 装饰器叠加 + null 退化。

更深原则：能力（Tool）与治理（Executor）分离，因为变更节奏不同——工具天天加，治理规则稳定；绑一起，加一个工具就得改一次安全代码。

## 面试表述

> 工具治理用 GovernedToolExecutor 装饰 DefaultToolExecutor，不改 Tool 接口。
> 好处：工具作者不感知治理、MCP 自动纳入、本地/插件/远程统一收口；治理四件套按执行时机分布。
> 代价：fail-closed、前台单点复杂、粒度只在工具级，参数级是 v2。
> 能力与治理分离，因为变更节奏不同。

## 关联

- 证据：`GovernedToolExecutor implements ToolExecutor` 装饰 `DefaultToolExecutor`；`Tool` 接口无治理字段；MCP `McpToolAdapter implements Tool`。
- 决策 5/6/7 同源扩展语言。
