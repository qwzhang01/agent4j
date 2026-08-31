# 决策 11：权限三档 AUTO / REQUIRES_APPROVAL / DENY，而不是二元 allow/deny

> 对应《agent4j 架构立场》骨架的决策 11。
> 一句话：**权限模型是信任的分层；三档是"够用且便宜"的信任分层，RBAC 是"精确但昂贵"的访问控制。**

## 一、决策在说什么

`ToolPermission` 只有三值，javadoc 原话：

> "Three tiers — intentionally NOT fine-grained RBAC. RBAC is Stage 15."

两个否定：不是二元 allow/deny（太粗糙），也不是 RBAC（太重）。刻意停在中间。

## 二、三档各是什么

```java
AUTO               // get_time, echo, search
REQUIRES_APPROVAL  // delete_file, send_email, execute_command
DENY               // 调用到不了工具
```

`ToolPolicy.permissionFor(toolName)` = 显式注册 or `defaultPermission`。

两个细节：

1. defaultPermission：开发 AUTO，生产 REQUIRES_APPROVAL。
2. 运行时可变：setPermission() 事故后即时降级，不用重部署。

## 三、为什么三档不是两档

二元 allow/deny 把"安全且高频"和"危险且低频"混在一起：

- allow 一切 → delete_file 不用人批，事故。
- deny 一切 → get_time 也人批，审批疲劳，人盲签。

| 档 | 解决什么 | 意图 |
|---|---|---|
| AUTO | 安全工具注意力浪费 | 省审批预算 |
| REQUIRES_APPROVAL | 危险工具人把关 | 副作用大/不可撤销须确认 |
| DENY | 禁用工具硬阻断 | 连审批都不给 |

**AUTO 的本质是省注意力，不是"安全"。**

## 四、fail-closed 藏在审批步

```java
if (perm == REQUIRES_APPROVAL && approvalService == null) {
    return "[DENIED] ... no approval service configured";  // 不放行
}
```

宁可误拒，不可失守。

另：DENY 返回字符串而非抛异常，模型能见拒绝原因并自我纠正（沿用 Stage 1 约定）。

## 五、代价（答出不对称）

1. 三态比二元复杂，每个工具判定靠人（判据：副作用/可撤销/爆炸半径/毁灭性）。
2. **误判成本不对称**：漏升=多问一次（烦），漏降=事故（炸）。宁可多升不可漏降 → 生产默认 REQUIRES_APPROVAL。
3. 只有工具级粒度，参数级是 v2。
4. 无身份/角色维度，当前是"工具→档"静态映射。

## 六、诚实指出"缝"

`PermissionChecker` 是薄包装，javadoc 明说为了以后加 context-aware（user role X can call Y）而不改 ToolPolicy。当前 check(toolName) 只认工具名，不认"谁在调"——缝已留好，v1 是空的。

## 七、什么场景会改

- 需要"谁可以调什么" → PermissionChecker 加身份/角色（RBAC，Stage 15）。
- 参数级控制 → 治理下沉到参数层。
- 动态策略 → ToolPolicy 已支持运行时改，接外部配置源。
- fail-open → 低风险沙箱默认 AUTO（一般不推荐）。

## 八、架构师洞察

```text
权限模型 = 信任分层，不是访问控制完整实现。
三档 = 够用且便宜的信任分层；RBAC = 精确但昂贵的访问控制。
```

两个核心判断：

1. AUTO 的本质是省注意力预算，不是安全。
2. 误判成本不对称决定默认策略——漏降比漏升贵，生产默认 REQUIRES_APPROVAL。

## 九、面试表述

> 权限模型是 AUTO / REQUIRES_APPROVAL / DENY 三档，刻意不做 RBAC。
> 三档分别解决：省审批注意力、危险操作人把关、禁用硬阻断；二元会把安全高频和危险低频混一起。
> 实现 fail-closed：REQUIRES_APPROVAL 缺审批服务就 DENY；ToolPolicy 运行时可变，事故后即时降级。
> 代价：误判成本不对称（漏降=事故，漏升=多问），所以生产默认 REQUIRES_APPROVAL；粒度只在工具级，身份感知的缝留在 PermissionChecker。
> RBAC 和参数级是 Stage 15 / v2。

## 关联

- 证据：`ToolPermission` 枚举 + `ToolPolicy` + `PermissionChecker` + `GovernedToolExecutor.execute()` 的权限/审批步。
- 决策 7 的治理四件套之一；决策 12/13 是后续两步。
