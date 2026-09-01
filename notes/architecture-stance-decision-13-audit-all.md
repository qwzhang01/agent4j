# 决策 13：审计全留痕（允许/拒绝/执行/失败/净化），而不是只记执行

> 对应《agent4j 架构立场》骨架的决策 13。
> 一句话：**审计记"意图"，不只记"行为"——DENIED 是未成行的意图史，安全情报密度最高。**

## 一、决策在说什么

`AuditEvent` javadoc：

> "One event is produced per tool call attempt, regardless of outcome. Failed/denied calls are also audited — 'who tried to do what but was blocked' is itself a security event."

每次工具调用尝试，无论结果，都产出一条审计事件。

## 二、五种状态 + 九字段

```text
APPROVED   审批通过（REQUIRES_APPROVAL 执行前事件）
DENIED     权限拒绝或审批驳回——未执行
EXECUTED   执行成功
FAILED     执行抛异常
SANITIZED  结果被净化
```

九字段：eventId / runId / toolName / args / result / status / timestamp / durationMs / reason。

两个关键设计：

1. args/result truncate 到 500 字符——是存储上限，不是隐私脱敏。
2. runId 是关联键，贯穿 Stage 6/7/9。

## 三、为什么失败和拒绝也必须记

1. **DENIED = 未成行的意图史，安全情报密度最高**：只记 EXECUTED 等于丢掉最危险的信息。
2. **失败要记**：攻击未遂 / 重试危险操作 / 踩点检测。
3. **APPROVED 与 EXECUTED 分开**：决定凭证与动作凭证互相印证；有 EXECUTED 无 APPROVED = 审计链断裂信号。

## 四、三种查询 = 三种分析场景

| 查询 | 场景 |
|---|---|
| getByRun | 事故调查：一次 run 发生了什么 |
| getByTool | 模式分析：某工具被谁反复调、失败率 |
| getAll + 筛选 | 盲签检测：APPROVED/EXECUTED 异常比例 |

InMemoryAuditLogger 用 CopyOnWriteArrayList + byRun/byTool 二级索引，避免全表扫描。

## 五、代价（诚实说 v1 的坑）

1. 存储增长：每次调用都记，500 字符截断只压存储、丢尾部信息。
2. 截断不是脱敏：args/result 可能含 key/token/password，truncate 不打码，审计日志成了新敏感数据源。
3. v1 三个硬伤：InMemory 重启丢、进程内可篡改、无保留策略/哈希链。

## 六、诚实定位

v1 审计是"能查到发生了什么"的内存账本，不是"可作法律证据"的审计系统。

生产三步升级：

```text
1. 持久化：DB / file / SIEM（重启不丢）
2. 防篡改：哈希链 / append-only / 签名
3. 合规：保留策略 + 脱敏 + 访问控制
```

## 七、什么场景会改

- 生产/合规 → 持久后端（接口已留，Stage 18）。
- 防篡改 → 哈希链 / append-only / 签名。
- 敏感泄露 → args/result 脱敏，不只 truncate。
- 保留策略 → TTL + 归档。

## 八、架构师洞察

治理的本质是"结构化留痕 + 可回溯"——从 memory 的 provenance 到 tool 的 audit，同一设计基因。

三段闭环收口：

```text
决策 11 权限：事前——能不能调
决策 12 净化：事中——结果干不干净
决策 13 审计：事后——发生了什么（包括没发生的）
```

## 九、面试表述

> 审计每次工具调用尝试都留痕，五态全记：APPROVED/DENIED/EXECUTED/FAILED/SANITIZED。
> DENIED 是未成行意图史，安全情报密度最高；失败要记（攻击未遂/重试/踩点）；APPROVED 与 EXECUTED 分开互证。
> 代价：存储增长、args/result 只截断不脱敏；v1 内存账本，重启丢、可篡改、无保留策略。
> 生产要持久化 + 哈希链防篡改 + 脱敏 + 保留策略。
> 与 memory 的 provenance 同一设计基因：治理 = 结构化留痕 + 可回溯。

## 关联

- 证据：`AuditEvent`（五态 + 九字段 + 工厂方法）/ `AuditLogger` / `InMemoryAuditLogger` / `GovernedToolExecutor` 审计步。
- 决策 11 事前、决策 12 事中，三者闭环。
