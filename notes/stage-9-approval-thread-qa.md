# Stage 9 审批服务线程模型 Q&A（ConsoleApprovalService 三连问）

> 背景：读 `ConsoleApprovalService.console()` 时连环三问 -- ① 第 51 行线程会等待吗？② 阻塞是设计还是事故、影响别的 Agent 吗？③ 里面藏的 System.in bug 到底是什么？
> 涉及代码：`agent-security/ConsoleApprovalService.java` + `GovernedToolExecutor.java` + `TaskScheduler.java`

---

## Q1：`console()` 这一行线程会等待吗？

```java
// ConsoleApprovalService.java:50-52
public static ConsoleApprovalService console() {
    return new ConsoleApprovalService(ConsoleApprovalService::readFromConsole);
}
```

**不等待。** `ConsoleApprovalService::readFromConsole` 是方法引用（method reference），它在 JVM 层面就是一个实现了 `Function<ToolCall, Boolean>` 的对象，构造时只是被**存进 `decisionFunction` 字段**--相当于"登记了一个回调"，此处不执行任何 I/O、不阻塞。

真正会等待的是后续审批触发时，调用链一路同步走到 stdin：

```
ReActAgentLoop.execute()            （跑 Agent 循环的线程，比如 main）
  └─ GovernedToolExecutor.execute()          (line 50)
      └─ approvalService.request(tc, runId)  (line 68，工具是 REQUIRES_APPROVAL 时)
          └─ decisionFunction.apply(tc)      (ConsoleApprovalService line 63)
              └─ readFromConsole(tc)         (line 69)
                  └─ scanner.nextLine()      (line 75)  ← 唯一的阻塞点
```

`Scanner.nextLine()` 阻塞读标准输入，**卡住的就是调用 `agentLoop.execute()` 的那个线程**。整个 ReAct 循环、后续工具调用、模型调用全部暂停，直到用户敲下回车。

**一句话**：工厂方法登记回调（不阻塞）→ request() 触发回调（阻塞在 nextLine）。

---

## Q2：阻塞是正常的吗？影响别的 Agent 吗？

### 阻塞是设计，不是事故

"同步审批"的语义就是：**没拿到人的批准，一步都不能往下走**。而"不能往下走"在代码里唯一的表现形式，就是线程停在原地等。类比：银行柜员递单子让你签字，他必须停下手等你签完才继续办--不是偷懒，是流程规定。

三种工厂模式对应"要不要等、等谁"：

| 工厂 | 行为 | 适用场景 |
|------|------|---------|
| `autoApprove()` | 不等，直接放行 | 测试、全自动 |
| `console()` | **停在 stdin 等人敲 y/n** | 交互式 demo |
| `callback(fn)` | 停着等 webhook/API 返回 | 接外部审批系统 |

常见误解澄清：**阻塞等待不烧 CPU**。线程 park 在 `nextLine()` 上时，OS 不再分配时间片，CPU 占用为 0。代价是"线程工位被占"，不是性能损耗。这是 Stage 9 设计决策 D4（同步审批 v1，异步留 Stage 12）的有意取舍。

### 影响别的 Agent 吗？-- 线程是隔离边界

每个线程有独立调用栈，分三种场景：

```java
// 场景 A：各跑各的线程 —— 完全不受影响 ✅
new Thread(() -> agent1.execute()).start();  // 卡在审批
new Thread(() -> agent2.execute()).start();  // 照常干活

// 场景 B：同一个线程串行 —— 被连带排队 ⏳
agent1.execute();  // 卡在审批，一直不返回
agent2.execute();  // 排在后面，永远轮不到
```

**场景 C（真实风险）：scheduler 线程池只有 2 个线程**

```java
// TaskScheduler.java:57
this(runManager, Executors.newScheduledThreadPool(2, ...));
```

多个 run 挂在同一个 `TaskScheduler` 上由它自动恢复执行时：一个 run 卡死在 console 审批就占掉池里 1 个线程；**两个同时卡住，scheduler 的定时唤醒、事件恢复全部堵死**。console() 配 scheduler 定时任务是 worst combo（凌晨两点没人敲键盘，线程永远卡着）。

### 无人值守场景的正解（Stage 12 蓝图）

```
工具要审批 -> 抛 PauseException -> 存 Checkpoint（run 挂起）-> 线程立即释放
人在 2 小时后批准 -> 事件触发 -> TaskScheduler 恢复 run，从断点继续
```

基础设施已全部就绪：Stage 6 的 `PauseException + Checkpoint`、Stage 7 的 `TaskScheduler` 事件唤醒（"人批准就是事件"）。Stage 12 只差把审批服务接到这条链上。

---

## Q3：藏着的 System.in 关闭 bug 是什么？

### 问题代码

```java
// ConsoleApprovalService.java:69-78
private static boolean readFromConsole(ToolCall toolCall) {
    ...
    try (Scanner scanner = new Scanner(System.in)) {   // 每次审批都新建 Scanner
        String line = scanner.nextLine().trim().toLowerCase();
        return line.equals("y") || line.equals("yes");
    }   // ← try-with-resources 自动调用 scanner.close()
}
```

### 逐步拆解（四个知识点连起来）

1. **try-with-resources 的合约**：代码块结束时自动调用资源的 `close()`，无论正常返回还是抛异常。
2. **Scanner.close() 会关闭源流**：Scanner 实现了 `Closeable`，close 时会关闭构造时传入的流。
3. **这里传入的源流是 `System.in`**：它是 `static final` 的全局流，**整个 JVM 进程只有一份**，代表"标准输入"。关了就永远打不开。
4. **后果**：第一次审批结束后 `System.in` 被关闭；第二次审批时 `new Scanner(System.in)` 构造不报错（允许包装已关闭的流），但 `nextLine()` 一调用就抛 `NoSuchElementException`（Scanner 把底层"流已关闭"的 IOException 转成 NoSuchElementException）--**不是等待输入，而是直接崩溃**。

### 类比：总水管与水龙头

- `System.in` = 进户总水管（整栋楼就一根，总阀只有一个）
- `Scanner` = 你家装的水龙头
- try-with-resources = "用完自动拆水龙头"的合约——但这个水龙头拆的时候**顺手把总阀也关了**
- 第二次审批 = 再装个新水龙头拧开 → 没水，还爆管（抛异常）

### 异常怎么炸穿整个框架

```
nextLine() 抛 NoSuchElementException
  → readFromConsole 无 catch
    → request() 无 catch
      → GovernedToolExecutor.execute() 审批段无 catch
        （只有第 4 步 delegate.execute 有 try-catch，审批段不在保护范围内）
        → 一路抛到 AgentLoop → 整个 run 报 ERROR
```

**为什么 demo 没炸**：单次审批场景（如 McpExample 一次 run 只走一次 REQUIRES_APPROVAL）没问题。触发条件是**同一个进程里第二次 console 审批**--同一个 run 里多个危险工具，或多个 Agent 共用此服务。而且这个 bug 是**进程级**的：Agent A 审批完关了 System.in，Agent B 哪怕在别的线程再走 console 审批也直接崩--这才是真正"跨 Agent 影响"的点（bug，不是设计）。

### 最小复现

```java
try (Scanner s = new Scanner(System.in)) { s.nextLine(); }  // 第一次：正常
try (Scanner s = new Scanner(System.in)) { s.nextLine(); }  // 第二次：💥 NoSuchElementException
```

### 修法

**方案 1（推荐）：共享 static Scanner，永不关闭**

```java
private static final Scanner SCANNER = new Scanner(System.in);

private static boolean readFromConsole(ToolCall toolCall) {
    ...
    String line = SCANNER.nextLine().trim().toLowerCase();
    return line.equals("y") || line.equals("yes");
}
```

Scanner 的关闭职责属于创建 `System.in` 的一方（即 JVM 本身），进程退出时 OS 自动回收 stdin，业务代码不应显式关闭它。

**方案 2（防御性补充）：catch NoSuchElementException 按"无输入"处理，fail-closed 拒绝审批**（stdin 是 EOF/已关闭时视为 reject，符合 Stage 9 的 fail-closed 哲学）。

---

## 一句话总结

- 工厂方法登记回调不阻塞；**request() 触发时阻塞在 nextLine()，卡的是调用 Agent 循环的那个线程**。
- 阻塞是同步审批的设计本意（"没批准不许继续"），IO 阻塞不占 CPU；跨线程的其他 Agent 不受影响，除非共享 scheduler 2 线程池、或踩到 System.in 关闭 bug。
- `try-with-resources(new Scanner(System.in))` 每次 close 会焊死总阀门，第二次审批必炸，修法是共享 static Scanner。

## 面试速答

| 问 | 答 |
|----|----|
| 方法引用 `Class::method` 会执行方法吗 | 不会，只创建函数对象；调用 `apply()` 才执行 |
| `nextLine()` 阻塞时占 CPU 吗 | 不占，线程 park 等 I/O 事件，OS 不再分配时间片 |
| 同步审批卡住线程，别的线程受影响吗 | 不受，线程调用栈独立；除非共享线程池（TaskScheduler 默认 2 线程） |
| 为什么 try-with-resources 包 Scanner(System.in) 是坑 | Scanner.close() 会关闭 System.in，全局唯一且不可重开，第二次读取直接抛 NoSuchElementException |
| 无人值守场景怎么审批 | 异步审批：PauseException 挂起 + Checkpoint 存档 + 事件恢复（Stage 12） |
