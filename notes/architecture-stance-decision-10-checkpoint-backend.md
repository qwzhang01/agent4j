# 决策 10：Checkpoint 默认内存/文件，不做托管后端

> 对应《agent4j 架构立场》骨架的决策 10。
> 一句话：**把"存储能力"做成接口，把"存储选择"降级为配置——架构在接口里，不在实现里。**

## 一、决策在说什么

`agent-workflow` 定义 `CheckpointStore` 接口，v1 只给两个实现：

```text
CheckpointStore（接口）
├── InMemoryCheckpointStore  → ConcurrentHashMap，JVM 退出即丢
└── FileCheckpointStore      → 每个 runId 一个 JSON 文件，重启存活
```

接口四个方法，极其克制：

```java
String save(Checkpoint checkpoint);
Optional<Checkpoint> load(String runId);
void delete(String runId);
List<String> listRunIds();
```

## 二、两个实现的真实差异

```java
// InMemory：ConcurrentHashMap，一 run 一 checkpoint，覆盖写
public String save(Checkpoint checkpoint) {
    store.put(checkpoint.runId(), checkpoint);
    return checkpoint.checkpointId();
}
// javadoc：Checkpoints are lost when the JVM exits.
```

```java
// File：一个 runId 一个 JSON 文件
mapper.writerWithDefaultPrettyPrinter()
      .writeValue(fileFor(checkpoint.runId()).toFile(), Snapshot.from(checkpoint));
// javadoc：Survives process restart (unlike InMemoryCheckpointStore).
```

两个细节：

1. **Keyed by runId，只保留 latest**：覆盖写，snapshot 语义，不是 event sourcing。
2. **Snapshot 是序列化边界**：Checkpoint 是运行时 record，Snapshot 是 Jackson 友好 POJO——存储格式与运行时对象解耦。

## 三、为什么这么选

1. **范围控制**：v1 要验证"暂停→存→续"语义，不是生产存储。接 DB 会转移注意力到连接池/事务/迁移。
2. **零外部依赖**：核心哲学是 agent-core/model 不依赖 Spring、不依赖 DB。
3. **接口优先，实现是配置**：生产换 Postgres，GraphRuntime/RunManager 一行不改——插槽与卡。
4. **两实现各司其职**：内存给测试，文件给崩溃恢复 demo。

## 四、代价（必须答）

1. 内存 store 重启即丢，只适合测试/短命 run。
2. 文件 store 单机，无跨机恢复、无分布式锁、多实例互相覆盖。
3. 文件 store 的 blackboard 值必须 Jackson 可序列化，靠约定不靠类型强制。
4. **文件写入非原子**：writeValue 直写目标文件，save 中途崩溃会写坏 JSON。

## 五、诚实指出自己代码的坑（加分点）

```text
FileCheckpointStore.save() 直接 writeValue 到目标文件
→ 崩溃窗口：写一半进程死 → 文件损坏
→ 修复：写 tmp 文件 + Files.move(ATOMIC_MOVE) 原子替换
```

一个声称"崩溃恢复"的组件，自己的写入却不抗崩溃——这是架构师最该敏感的矛盾。

## 六、什么场景会改

| 触发 | 改法 |
|---|---|
| 多实例 / HA / 跨机 | 接 Postgres/Redis，实现接口，调用方不动 |
| 写入不损坏 | tmp + atomic rename |
| 保留历史版本 | append-only + 版本号（走向 event sourcing） |
| blackboard 存复杂对象 | 序列化升级 |

## 七、架构师洞察

与 agent-memory 的 MemoryStore 同一设计基因：

```text
MemoryStore      → InMemory v1，DB 后补，调用方不动
CheckpointStore  → InMemory/File v1，托管后端后补，调用方不动
```

"接口插槽 + 默认最简实现 + 生产后补"是框架贯彻最彻底的原则。

## 八、面试表述

> Checkpoint 抽象成 CheckpointStore 接口，v1 只给内存和文件实现，不做托管后端。
> 因为 v1 验证的是暂停→存→续语义，且核心零外部依赖。
> 代价：内存重启丢、文件单机、写入非原子会写坏 checkpoint。
> 生产要 HA 就实现接口接 Postgres/Redis，调用方一行不改。

## 关联

- 证据：`CheckpointStore` / `InMemoryCheckpointStore` / `FileCheckpointStore`；limitations.md「Checkpoint 同理：内存/文件 store」。
- 与 agent-memory 的 MemoryStore 同源接口插槽原则。
