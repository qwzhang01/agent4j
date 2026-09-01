# 决策 15：隔离和共享统一为 MemoryScope 字符串，而不是两套系统

> 对应《agent4j 架构立场》骨架的决策 15。
> 一句话：**隔离与共享是同一个维度的两端——隔离 = 查询列表里没有你，共享 = 查询列表里有你。**

## 一、决策在说什么

`MemoryScope` javadoc：

> "A memory namespace — the single mechanism for both isolation and sharing."

```text
user:u1      → 私有记忆（隔离）
channel:c1   → 频道共享记忆（共享）
tenant:acme  → 租户隔离
```

共享不是另一套系统，只是一个 scope 取值（P5）。

## 二、隔离怎么被强制

`InMemoryMemoryStore.query()`：

```java
.filter(e -> query.scopes().contains(e.scope()))
```

`MemoryQuery` 要求 scopes 非空。

```text
用户 A 查 [user:a1]        → 只看到 a1
用户 B 查 [user:b1]        → 只看到 b1
A/B 都查 [channel:c1]      → 都看到 c1（共享）
```

隔离 = scope 不在查询列表的自然结果；共享 = 两个列表都含同一个 scope。

## 三、为什么统一

1. 一套代码不是两套：不做 UserMemoryStore + ChannelMemoryStore，避免两路径两 bug。
2. 隔离是被动的不共享，不是另一功能；共享是主动。
3. 可扩展：加 tenant: 只加枚举值 + 工厂方法，不加新 store/表/查询。

## 四、代价（必须答）

1. 字符串运行时解析：kind() 用 Kind.valueOf(prefix.toUpperCase())，前缀写错查询时才炸；of() 只校验有无冒号。
2. 字符串前缀匹配脆弱：defaultStatusForScope 用 startsWith("channel:")，改前缀要全局搜。
3. 隔离靠契约不靠类型：新 store 忘了 scope 白名单过滤，隔离静默失效——最危险的一类 bug。

## 五、诚实指出风险

隔离没有类型保护，未来换 store 时"必须 scope 白名单过滤"是最容易漏的安全边界。主动承认"契约式隔离"比声称"强隔离"成熟。

## 六、什么场景会改

| 触发 | 改法 |
|---|---|
| 层级 scope（tenant→user→session） | 扁平字符串升级层级路径/树 |
| 跨 scope 可见性 | scope 关系图/继承规则 |
| 前缀拼写错频繁 | 强类型 + 编译期 Kind 校验 |
| 换持久化 store | 隔离下沉 SQL WHERE scope IN |

## 七、架构师洞察

多租户做对的标志：把隔离当成"共享的缺席"，而不是单独功能模块。一旦写"隔离模块"就走偏。

"命名空间即值"通用模式：一个字符串值承载"谁属于哪个空间"，隔离/共享退化成"值是否在集合里"。同思想见于 OAuth scope、K8s namespace、消息 topic。

## 八、面试表述

> 隔离和共享统一成 MemoryScope "kind:id"，不是两套系统。
> user:u1 隔离、channel:c1 共享，都走同一条 query；隔离 = scope 不在白名单，共享 = 两个列表都含它。
> 好处：一套代码、隔离是被动不共享、加命名空间只加枚举。
> 代价：字符串解析脆弱、隔离靠契约不靠类型——新 store 忘白名单过滤就静默失效，这是我最警惕的边界。
> 层级 scope 或跨 scope 可见性时升级图/层级。

## 关联

- 证据：`MemoryScope` record + `Kind` 枚举 + 工厂方法；`InMemoryMemoryStore.query()` scope 白名单；`MemoryQuery` scopes 非空；stage-8 D3/P5。
- 决策 14 的 scope 字段由本决策定义；决策 16/18 都以 scope 隔离为前提。
