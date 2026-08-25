# Agent 架构师面试问答 20 题

> 时间：2026-08-25
> 来源：基于 agent4j 项目的真实代码设计决策，模拟面试场景
> 定位：不是背答案，而是每个问题对应一个真实的设计权衡——答案写清楚了"为什么这么选、有什么代价、怎么改进"

---

## 一、核心控制流（ReActAgentLoop）

### Q1. 并行 Tool Execution：顺序 vs 并行

**问题**：`ReActAgentLoop.execute()` 里，当模型返回 `hasToolCalls() == true` 时，顺序执行所有 tool calls，全部结果追加到 state 后才进入下一轮。但 OpenAI 的并行 tool calling 语义是"这些调用互不依赖，可以并行"。你选择顺序执行而不是并行，这是有意为之还是 v1 权衡？

**答案**：v1 有意为之，但确实有优化空间。

**为什么顺序**：
1. **AgentState 线程安全**：当前 `AgentState.messages` 是普通 `List<Message>`，没有并发保护。并行写会 data race。
2. **ToolExecutor 无状态保证**：`DefaultToolExecutor` 不保证线程安全，同一个 AgentState 被多个 tool 共享时可能有副作用。
3. **可观测性优先**：顺序执行 = 顺序日志，调试时 step by step 一目了然。并行执行 = 交错日志，v1 阶段调试成本太高。
4. **mini-swe-agent 的教训**：SWE-bench 团队用"唯一工具 = bash + 线性历史"做到极简，我们 v1 同样选择"线性执行"来降低心智负担。

**怎么改才能支持并行**：

```java
// AgentState 改造
public class AgentState {
    private final List<Message> messages = new CopyOnWriteArrayList<>();  // 并发安全

    public synchronized void appendToolResults(List<ToolResult> results) {
        // 原子性追加，避免结果交错
        results.forEach(r -> messages.add(Message.toolResult(r)));
    }
}

// ToolExecutor 加版本号
public interface ToolExecutor {
    // 返回 CompletableFuture，支持并行
    CompletableFuture<ToolResult> executeAsync(ToolCall call, AgentState state);
}

// ReActAgentLoop 改造
List<CompletableFuture<ToolResult>> futures = toolCalls.stream()
    .map(call -> executor.executeAsync(call, state))
    .toList();
List<ToolResult> results = futures.stream()
    .map(CompletableFuture::join)
    .toList();
state.appendToolResults(results);
```

**代价**：并行执行改变了工具之间的因果顺序——如果两个 tool 都写 state 的同一个 key，结果不可预测。需要加"tool 依赖声明"或"读写隔离"。

---

### Q2. buildRequest() 的浅拷贝与引用安全

**问题**：`buildRequest()` 里 `new ArrayList<>(state.getMessages())` 做了一次浅拷贝。如果不拷贝直接传 `state.getMessages()` 会出什么问题？ContextBuilder.build() 返回的 messages 和 state 里的 messages 是同一个引用还是不同对象？

**答案**：浅拷贝是必要的，但确实存在引用泄漏风险。

**不拷贝直接传会怎样**：
- `ModelClient.chat()` 拿到的 messages 列表是 state 的内部引用
- 如果 LLM 调用是异步的（或 ModelClient 内部缓存了引用），另一个线程同时修改 state.messages（比如追加 tool result），会导致 LLM 请求的 messages 列表在传输过程中被修改——ConcurrentModificationException 或数据错乱

**ContextBuilder.build() 返回的是新列表**：
```java
// MemoryContextBuilder.build() 伪逻辑
List<Message> context = new ArrayList<>();          // 新列表
context.add(systemMessage);
context.addAll(memoryEntries);                       // 从 store 查出来的
context.addAll(state.getMessages());                 // 浅拷贝历史消息
return context;
```

但这里有一个陷阱：`context.addAll(state.getMessages())` 只是把 state 里的 Message 对象引用加进了新列表——Message 对象本身是共享的。如果 `ContextCompressor.compact()` 对 history 做了 `messages.clear() + addAll(summarized)`：

```java
// 假设 compact 实现
List<Message> history = state.getMessages();
history.clear();                                    // 危险！
history.addAll(summarized);
```

这**不会**破坏 ContextBuilder 返回的新列表（因为新列表已经 copy 了引用），但**会**破坏任何仍持有 `state.getMessages()` 引用的代码。

**当前代码安全的原因**：`buildRequest()` 做了 `new ArrayList<>()` 拷贝，且 ContextBuilder.build() 也返回新列表——双重保护。但依赖"每层都记得拷贝"是脆弱的。

**更安全的做法**：
```java
// AgentState 返回不可修改视图
public List<Message> getMessages() {
    return List.copyOf(messages);  // 深度不可变，任何持有者都无法修改原列表
}
```

---

### Q3. EXECUTING_TOOL 状态泄漏

**问题**：工具执行抛异常时，state 的 status 刚被设为 `EXECUTING_TOOL` 就异常退出了。这是设计缺陷还是刻意为之？

**答案**：v1 的"刻意偷懒"，但确实是设计债务。

**当前行为**：
```java
state.setStatus(AgentStatus.EXECUTING_TOOL);    // 设了
for (ToolCall call : toolCalls) {
    var result = executor.execute(call, state);  // 抛异常
    // 下面的代码不执行
    state.appendToolResult(...);
}
state.setStatus(AgentStatus.RUNNING);            // 永远走不到
```

异常抛出后，state 的 status 永远停在 `EXECUTING_TOOL`，外层 catch 只知道"出了异常"，不知道"执行到哪一步"。

**这不是致命问题，因为**：
- Agent 的 `run()` 方法在 catch 里会设 `status = ERROR`
- 但如果是 `run()` 外部（比如 Channel）读 state，在异常被 catch 之前的窗口期，看到的是 `EXECUTING_TOOL`

**优雅的改法**：
```java
try {
    state.setStatus(AgentStatus.EXECUTING_TOOL);
    for (ToolCall call : toolCalls) {
        var result = executor.execute(call, state);
        state.appendToolResult(result);
    }
} catch (Exception e) {
    state.setLastError(e);                        // 记录异常
    throw e;                                      // 继续往上抛
} finally {
    state.setStatus(wasError ? AgentStatus.ERROR : AgentStatus.RUNNING);
}
```

用 try-finally 保证 status 不会泄漏。这也是 mini-swe-agent 用 `InterruptAgentFlow` 异常体系统一处理的中灵感来源——所有中断路径都有确定的终态。

---

## 二、装饰器谱系（ModelClient 四代装饰器）

### Q4. 装饰器组装顺序：Observing 必须在最外层

**问题**：推荐组装顺序是 `Observing(Routing(Fallback(...)))`。如果反过来，变成 `Fallback(Observing(Retry(OpenAI)))`，metrics 会出什么问题？

**答案**：组装顺序决定"一次用户请求被记成几次 model call"，顺序反了 metrics 会膨胀 2-3 倍。

**正确顺序 `Observing(Routing(Fallback(OpenAI)))` 的事件流**：

```
用户 1 次 chat() 请求
  → Observing 开始计时
    → Routing 选择模型
      → Fallback 尝试 OpenAI → 成功
    → 返回
  → Observing 记录：1 次 call，延迟 Xms，token Y
```

**错误顺序 `Fallback(Observing(Retry(OpenAI)))` 的事件流**：

```
用户 1 次 chat() 请求
  → Fallback 开始
    → 主链：Observing 开始计时
      → Retry 第 1 次：OpenAI → 500 错误
      → Retry 第 2 次：OpenAI → 500 错误
      → Retry 第 3 次：OpenAI → 500 错误，重试耗尽，抛异常
    → Observing 记录：1 次 call（但是是 3 次重试的总延迟！）
    → Fallback 切备用模型
      → 备用链：Observing 开始计时
        → Retry → Mock → 成功
      → Observing 记录：1 次 call
  → 用户收到结果

Dashboard 看到：2 次 model call，但用户只发了 1 次请求
延迟数据：第 1 次的延迟包含了 3 次重试的累积时间，严重失真
```

**核心规则**：
- **Observing 在最外层**：一次用户请求 = 一次 metrics 记录，不管内层重试/fallback 了几次
- **Retry 在最内层**：重试对 Observing 透明，不影响 call 计数
- **Fallback 在中间**：fallback 是"换模型重试"，对外应表现为 1 次 call

这也解释了为什么 ObservingModelClient 的 javadoc 特别强调推荐顺序——这不是建议，是语义正确性的前提。

---

### Q5. ObservingModelClient 的 Stream.peek() 陷阱

**问题**：`Stream.peek()` 是中间操作，如果调用方不消费 stream，metrics 永远不会发出。这在生产环境里是容易踩的坑吗？

**答案**：在 agent4j 的使用场景下不容易踩，但需要文档约束。

**为什么不容易踩**：
1. `ReActAgentLoop.execute()` 拿到 stream 后会立即消费——遍历 events 追加到 state
2. 调用方是框架内部代码，不是外部用户
3. 如果 stream 真的没被消费，LLM 的回复也就丢了——问题比 metrics 不发要严重得多

**但确实有坑的场景**：
```java
// 假设有人写了"预览"方法：只看 stream 有没有 Error，不消费全部
Stream<StreamEvent> stream = client.stream(request);
boolean hasError = stream.anyMatch(e -> e.type() == ERROR);  // 消费到第一个 error 就停
// 如果第一个 event 不是 Done，metrics 不会 emit
```

**更安全的实现**：
```java
// 用 onClose 回调代替 peek
@Override
public Stream<StreamEvent> stream(ModelRequest request) {
    AtomicLong start = new AtomicLong(System.currentTimeMillis());
    Stream<StreamEvent> stream = delegate.stream(request);
    return stream
        .onClose(() -> {  // 无论消费到哪，stream 关闭时都会触发
            emitMetrics(start.get(), "stream_closed");
        });
}
```

`onClose()` 在 try-with-resources 或显式 close() 时都会触发，比 peek 更可靠。

---

### Q6. RetryModelClient 的 streaming 重试困境

**问题**：stream 已经开始返回了，怎么重试？

**答案**：当前代码选择了"只重试连接，不重试 mid-stream 失败"——这是务实的选择。

**当前实现**：
```java
@Override
public Stream<StreamEvent> stream(ModelRequest request) {
    for (int attempt = 0; attempt <= maxRetries; attempt++) {
        try {
            Stream<StreamEvent> stream = delegate.stream(request);
            return stream;  // 拿到 stream 引用就返回
        } catch (ModelException e) {
            // 只捕获"建立 stream 时的异常"（如 429、连接超时）
            // stream 内部的异常不会走到这里
        }
    }
}
```

**为什么 mid-stream 不重试**：
1. **语义问题**：stream 已经吐了一半 token 给调用方，重试后从头开始，调用方会收到重复内容
2. **消费模型**：Java Stream 只能消费一次，不能"倒带"
3. **LLM 生成不可复现**：同样的 prompt，第二次调 LLM 的输出一定不同

**如果一定要处理 mid-stream 失败**：
```java
// 方案：buffer + fallback
public Stream<StreamEvent> streamWithFallback(ModelRequest request) {
    List<StreamEvent> buffer = new ArrayList<>();
    AtomicBoolean failed = new AtomicBoolean(false);

    Stream<StreamEvent> primary = delegate.stream(request)
        .peek(e -> {
            if (!failed.get()) buffer.add(e);
            if (e.type() == ERROR) failed.set(true);
        });

    // 如果 primary 失败了，启动 fallback 从头发
    // 调用方需要知道"stream 可能会重新开始"——复杂度爆炸
}
```

**结论**：v1 不处理 mid-stream 重试是正确的。真正的解决方案是上层（FallbackModelClient）感知到 stream 失败后，用备模型重新发起一个**新的** chat() 调用——代价是丢失已生成的 token，但语义清晰。

---

## 三、安全治理（GovernedToolExecutor + 沙箱）

### Q7. 审批（REQUIRES_APPROVAL）与限流（RateLimiter）的顺序

**问题**：审批在限流之前，被拒绝的审批消耗 rate limit 配额。如果修正，审批和限流的顺序应该怎么排？

**答案**：当前顺序是 `Permission → Approval → RateLimit → Execute → Sanitize → Audit`，应该改为 `Permission → RateLimit → Approval → Execute → Sanitize → Audit`。

**为什么当前顺序有问题**：

```
场景：高敏感工具（REQUIRES_APPROVAL）被恶意/误调 100 次/分钟

当前顺序：
  100 次 Permission check → 通过（有权限）
  100 次 Approval check → 全部拒绝（管理员没批）
  RateLimit → 0 次（审批被拒的请求没消耗 rate limit？不对）

问题：RateLimiter 在 Approval 之后，审批被拒的请求"穿透"到了 RateLimiter
→ 如果 RateLimiter 基于"请求到达"计数，被拒的请求也消耗配额
→ AUTO 权限的低敏感工具被限流
```

**正确顺序的理由**：

| 步骤 | 顺序 | 理由 |
|------|------|------|
| Permission | 1（最前） | 无权限 = 直接拒绝，不浪费后续任何资源 |
| **RateLimit** | **2** | **限流应该覆盖所有"合法到达"的请求，不管审批结果如何** |
| Approval | 3 | 只有限流放行的请求才进入审批，避免审批服务被打爆 |
| Execute | 4 | 审批通过才执行 |
| Sanitize | 5 | 执行后清洗结果 |
| Audit | 6（最后） | 记录全链路 |

**更细粒度的方案**：Approval 被拒不计入 RateLimit 配额（只对"实际执行"的调用计费），但 Approval 请求本身有独立的 RateLimit（防止审批服务被打爆）。这需要两层 RateLimit：

```java
// 两层限流
RateLimitResult approvalRL = approvalRateLimiter.check(toolName, userId);  // 轻量级
if (!approvalRL.allowed()) return ToolResult.rateLimited(approvalRL.retryAfter());

ApprovalDecision decision = approvalService.check(toolName, call);
if (decision == REJECTED) return ToolResult.rejected(decision.reason());

RateLimitResult executionRL = executionRateLimiter.check(toolName, userId);  // 重量级
if (!executionRL.allowed()) return ToolResult.rateLimited(executionRL.retryAfter());
```

---

### Q8. ClassLoaderSandbox 的反射逃逸漏洞

**问题**：`Class.forName("java.lang.reflect.Proxy")` 就能绕过类加载器黑名单。你知道这个漏洞吗？在 Agent 场景下如何选择沙箱？

**答案**：知道，这是 Java ClassLoader 沙箱的根本局限，不是 bug 而是 Java 的设计哲学。

**逃逸路径**：
```java
// SandboxClassLoader 黑名单只拦截 loadClass
// 但反射的入口不在黑名单里：
Class<?> proxyClass = Class.forName("java.lang.reflect.Proxy", true,
    Thread.currentThread().getContextClassLoader());  // 用父 ClassLoader 加载
Method invoke = proxyClass.getMethod("newProxyInstance", ...);
// → 成功逃逸
```

**根本原因**：Java 的 ClassLoader 双亲委派模型意味着子 ClassLoader 永远能看到父 ClassLoader 加载的类。除非用自定义 SecurityManager（已 deprecated）或禁用反射，否则无法在 JVM 内部实现真正的隔离。

**选择决策矩阵**：

| 场景 | 推荐沙箱 | 原因 |
|------|---------|------|
| Agent 执行 LLM 生成的代码 | ProcessSandbox | 代码不可信，需要 OS 级隔离 |
| Agent 执行可信插件 | ClassLoaderSandbox | 代码可信但需要类隔离，速度快 |
| 开发/测试环境 | ClassLoaderSandbox | 便利性 > 安全性 |
| 生产环境 + 不可信输入 | ProcessSandbox | 安全性 > 速度 |

**未来方向**：
- GraalVM polyglot isolate：JVM 内部的进程级隔离，兼顾安全和速度
- Java 版 "V8 isolate"：用 native image 运行不受信任的代码
- Container-based sandbox：Docker/Podman 隔离，适合云端部署

---

### Q9. System.setOut() 全局单例副作用

**问题**：如果外部也做了 `System.setOut()`，RoutingOutputStream 的 `ORIGINAL_OUT` 引用会指向旧的 stream，丢失输出。

**答案**：这是 ClassLoaderSandbox 的已知风险，v1 选择"静态初始化时锁定"是务实方案。

**当前行为**：
```java
static {
    ORIGINAL_OUT = System.out;    // 类加载时捕获一次
    System.setOut(new PrintStream(new RoutingOutputStream(LOCAL_OUT, ORIGINAL_OUT), true));
}
```

**外部 setOut 的影响**：
```java
// 日志框架（如 logback）可能在 ClassLoaderSandbox 初始化之后调用
System.setOut(new PrintStream(logAppender));
// → ORIGINAL_OUT 还是指向最初的 System.out
// → RoutingOutputStream 路由非 sandbox 线程的输出到 ORIGINAL_OUT
// → 但 ORIGINAL_OUT 已经不是"当前的 System.out"了
// → 日志框架的输出可能被"吞掉"或写到错误的地方
```

**修复方案**：
```java
// 方案 1：动态查找当前 System.out（每次 write 都查，性能差）
private OutputStream currentOut() {
    OutputStream bound = LOCAL_OUT.get();
    if (bound != null) return bound;
    // 不用缓存的 ORIGINAL_OUT，直接读当前 System.out
    // 但 System.out 此时就是我们的 RoutingOutputStream → 无限循环！
    // 这个方案不可行
}

// 方案 2：分层路由（推荐）
// RoutingOutputStream 只处理 sandbox 线程，非 sandbox 线程直接透传
// 不替换 System.out，而是让 sandbox 线程的 ThreadLocal 绑定一个"拦截层"
// 拦截层在 write 时检查：如果是 sandbox 代码的输出 → 捕获，否则 → 透传

// 方案 3：不碰 System.out，用自定义 PrintStream 注入
// 让 sandbox 代码通过 Context 注入的 PrintWriter 输出，而不是 System.out
// 代价：sandbox 代码必须用注入的 writer，不能用 System.out.println()
```

**v2 建议**：方案 3 最干净。sandbox 代码通过 `NodeContext.outputWriter()` 输出，框架保证路由正确，不需要全局 `System.setOut()`。

---

## 四、Workflow 暂停恢复（GraphRuntime + HumanApprovalNode）

### Q10. PauseException 死循环陷阱

**问题**：如果 `checkDecision()` 返回 null，又抛 `PauseException`——会导致无限暂停循环吗？

**答案**：当前代码**不会**无限循环，但会无限暂停——这同样是个问题。

**为什么不会循环但会暂停**：
```
第 1 次 execute()：
  ctx.isResuming() == false → requestApproval() → 抛 PauseException
  GraphRuntime catch → cursor = "humanApproval" → 返回 PAUSED

resume：
第 2 次 execute()：
  ctx.isResuming() == true → checkDecision() → null（审批超时/状态变了）
  → 抛 PauseException
  GraphRuntime catch → cursor = "humanApproval" → 返回 PAUSED

resume：
第 3 次 execute()：
  同上... → 返回 PAUSED
```

**每次 resume 都立即 PAUSED**，虽然不是"死循环"（不是 CPU 转圈），但外部的 resume 操作永远不会推进——"暂停黑洞"。

**防呆机制**：

```java
// 方案 1：审批超时 = 自动拒绝（最推荐）
if (decision == null) {
    Duration sinceRequest = Duration.between(requestedAt, Instant.now());
    if (sinceRequest.compareTo(approvalTimeout) > 0) {
        return NodeResult.failure("Approval timed out after " + approvalTimeout);
    }
    throw new PauseException("Approval pending");  // 还没超时，继续等
}

// 方案 2：GraphRuntime 加 resume 次数限制
if (run.getResumeCount() > MAX_RESUMES) {
    return ExecutionResult.failed("Max resumes exceeded - possible approval deadlock", state);
}

// 方案 3：HumanApprovalNode 记录 resume 次数
if (ctx.isResuming() && checkDecision() == null) {
    resumeAttempts++;
    if (resumeAttempts > 3) {
        return NodeResult.failure("Approval not resolved after " + resumeAttempts + " resumes");
    }
    throw new PauseException("Approval still pending (attempt " + resumeAttempts + ")");
}
```

方案 1 最优：审批超时 = 业务拒绝，语义最清晰。

---

### Q11. 条件边的"最多匹配一条"约束

**问题**：多个条件同时为真是常见场景，但你强制要求条件边最多匹配一条。如果要支持"多条件匹配"，Edge 模型需要怎么改？

**答案**：当前约束是为了"路由确定性"——同一状态走同一条路，没有歧义。但确实限制了表达能力。

**当前 Edge 模型**：
```java
record Edge(String from, String to, Predicate<WorkflowState> condition, String label)
```

**扩展方案 1：条件边加优先级**

```java
record Edge(String from, String to, Predicate<WorkflowState> condition,
            String label, int priority)  // 数字越小优先级越高

// 路由逻辑
List<Edge> matches = outgoing.stream()
    .filter(e -> e.condition() != null && e.condition().test(state))
    .sorted(Comparator.comparingInt(Edge::priority))
    .toList();
if (!matches.isEmpty()) {
    return matches.get(0).to();  // 取优先级最高的
}
```

优点：向后兼容，多条件匹配时不报错，按优先级选。
缺点：优先级是全局数字，维护成本高。

**扩展方案 2：并行分支**

```java
// Edge 从"一对一"变成"一对多"
record Edge(String from, List<String> targets, Predicate<WorkflowState> condition,
            EdgeType type)  // type = SERIAL | PARALLEL

// 路由逻辑
if (edge.type() == PARALLEL) {
    // 所有匹配的 targets 同时执行
    // 需要 GraphRuntime 支持并行节点执行
    // 这是最根本的架构变化
}
```

优点：真正的并行分支。
缺点：GraphRuntime 的"单 cursor"模型必须改成"多 cursor"——几乎重写。

**扩展方案 3：合成条件（当前推荐）**

不改 Edge 模型，用 Java 的 `&&` / `||` 合成：

```java
workflow.addEdge("check", "highRiskPath",
    state -> amountGt1000(state) && riskHigh(state), "high-risk");
workflow.addEdge("check", "normalPath",
    state -> !amountGt1000(state) || !riskHigh(state), "normal");
```

这就是当前的做法——条件边不多时完全够用。只有当分支超过 5-6 条时，才需要引入优先级或并行。

---

## 五、记忆系统（MemoryScope + 待审闸门）

### Q12. 隐式安全 vs 显式安全：查询方必须正确构造 scopes

**问题**：如果 `channelMemoryContext()` 忘了把 `channel:c1` 放进 scopes，整个频道的记忆就对所有人不可见，而且没有任何报错。这是"靠约定不靠强制"的设计。

**答案**：这是 agent4j 最有意的设计决策之一，也是最值得反思的。

**隐式安全的好处**：
```java
// 查询方只需一行
store.query(new MemoryQuery(scopes, ...));

// 对比显式安全（需要注册 + 校验）
registry.register("channel:c1", ScopeKind.CHANNEL, ownerId);
registry.validateAccess(scopes, requesterId);  // 每次查询都校验
store.query(new MemoryQuery(scopes, ...));
```

隐式安全 = 零运行时开销、零配置、零依赖。"隔离与共享统一为 scope 值"这个设计让整个 Memory 系统的核心逻辑只有一行 filter——这在 Stage 8 从零搭建时是巨大的优势。

**隐式安全的代价**：
1. **沉默失败**：忘加 scope = 查不到数据，但没报错，debug 时很难发现
2. **无法审计**：谁查了谁的 memory？没有校验层，无从记录
3. **无法撤销**：一旦 scopes 列表构造错了，没有"二次确认"机制

**折中方案：编译时校验 + 运行时警告**

```java
// 1. 编译时：用工厂方法代替裸字符串
public class ChannelMemoryContext {
    // 只能通过这个方法构造 scopes 列表，保证 channel scope 一定在里面
    public static List<String> channelMemoryContext(String channelId, String userId) {
        return List.of(
            MemoryScope.channel(channelId).value(),  // 一定有
            MemoryScope.user(userId).value(),         // 一定有
            MemoryScope.session(currentSessionId).value()
        );
    }
}

// 2. 运行时：Store 层加 warning 日志
public List<MemoryEntry> query(MemoryQuery query) {
    if (query.scopes().stream().noneMatch(s -> s.startsWith("channel:"))) {
        log.warn("Query without any channel scope - is this intentional?");
    }
    // ...
}
```

**结论**：v1 隐式安全是对的——极致简单。v2 需要在工厂方法层加"语义构造器"，让错误不可能发生，而不是靠运行时校验。

---

### Q13. MemoryPolicy 的 startsWith("channel:") 与 Kind 枚举解耦

**问题**：用字符串前缀判断而不是 `MemoryScope.of(scope).kind() == Kind.CHANNEL`，如何让治理规则和 scope 类型解耦？

**答案**：`startsWith("channel:")` 是 v1 的快捷路径，但确实违反了 DRY（Kind 枚举和字符串前缀是同一件事的两个表述）。

**为什么 v1 用字符串**：
1. `MemoryPolicy` 在 `agent-memory` 模块内，`MemoryScope.Kind` 也在同一模块——技术上可以用 `MemoryScope.of(scope).kind()`
2. 但 `MemoryPolicy` 的定位是"策略层"，不应该依赖 `MemoryScope` 的内部结构。策略说"channel 类型的 scope 要待审"，而不是"以 `channel:` 开头的字符串要待审"——后者是实现细节。

**解耦方案：Policy 用 Kind，但通过函数注入**

```java
public class MemoryPolicy {
    private final double importanceThreshold;
    private final Function<String, MemoryScope.Kind> scopeClassifier;  // 注入分类器

    public MemoryPolicy(double importanceThreshold) {
        this(importanceThreshold, MemoryScope::of);  // 默认用 MemoryScope 解析
    }

    public MemoryPolicy(double importanceThreshold,
                        Function<String, MemoryScope.Kind> scopeClassifier) {
        this.importanceThreshold = importanceThreshold;
        this.scopeClassifier = scopeClassifier;
    }

    public MemoryStatus defaultStatusForScope(String scope) {
        MemoryScope.Kind kind = scopeClassifier.apply(scope).kind();
        if (kind == MemoryScope.Kind.CHANNEL) return MemoryStatus.PENDING_REVIEW;
        if (kind == MemoryScope.Kind.TENANT) return MemoryStatus.PENDING_REVIEW;  // 新增！
        return MemoryStatus.ACTIVE;
    }
}
```

这样加了 `tenant:acme` 也不需要改 startsWith——只需在 Kind 枚举里加 `TENANT`（已经有了），Policy 里加一行判断。

**更极致的解耦**：Policy 不判断 Kind，而是由外部注册"哪些 Kind 要待审"：

```java
Set<MemoryScope.Kind> reviewRequiredKinds = EnumSet.of(Kind.CHANNEL, Kind.TENANT);
MemoryPolicy policy = new MemoryPolicy(0.5, reviewRequiredKinds);
```

策略完全由外部配置驱动，Policy 本身不知道哪些 Kind 存在。

---

### Q14. InMemoryMemoryStore 的惰性 TTL 清理

**问题**：过期条目只靠查询时 filter 跳过，不删除也不改状态，会无限堆积。

**答案**：v1 选择惰性策略的原因是"简单且安全"，但确实需要补充清理机制。

**惰性策略的优点**：
1. **零额外线程**：不需要后台线程，不需要调度器
2. **查询时才判断**：过期逻辑和业务逻辑同线程，没有并发问题
3. **对 v1 够用**：InMemoryMemoryStore 本来就不是为长期运行设计的

**惰性策略的代价**：
- 无查询的 scope = 过期条目永远在内存里
- `listByScope()` 不过滤过期条目——这个方法确实没做 TTL 检查

**改进方案**：

```java
// 方案 1：写时清理（推荐 v2）
@Override
public MemoryEntry write(MemoryEntry entry) {
    // 每次 write 时顺便清理该 scope 的过期条目
    String scope = entry.scope();
    entries.entrySet().removeIf(e ->
        e.getValue().scope().equals(scope) && e.getValue().isExpired(Instant.now()));
    // ... 正常写入
}

// 方案 2：查询时标记（而不是跳过）
@Override
public List<MemoryEntry> query(MemoryQuery query) {
    entries.values().stream()
        .filter(e -> e.isExpired(Instant.now()))
        .forEach(e -> entries.remove(e.id()));  // 查到就删
    // ... 正常查询
}

// 方案 3：后台清理线程（适合持久化 Store）
ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor();
cleaner.scheduleAtFixedRate(() -> {
    Instant now = Instant.now();
    entries.entrySet().removeIf(e -> e.getValue().isExpired(now));
}, 5, 5, TimeUnit.MINUTES);
```

方案 1 最适合 v2：写时清理保证了"活跃 scope 不会堆积"，且不引入后台线程。

---

## 六、Channel 多人协作（SharedAgentSession）

### Q15. synchronized speak() 的锁粒度

**问题**：`synchronized` 锁住整个 speak()，A 正在 speak 时 B 会被阻塞。v2 怎么拆锁？

**答案**：当前 `synchronized` 是 v1 的"粗但安全"选择，v2 需要更细的并发模型。

**当前锁保护了什么**：
1. `state.getMessages()` 的读 + 写（追加用户消息 + LLM 回复 + tool result）
2. `state.setStatus()` 的写
3. `notifyUI()` 的调用顺序

**CopyOnWriteArrayList 能解决吗？** 部分能，但不彻底：
```java
// CopyOnWriteArrayList 解决了"读不阻塞写"
// 但 Agent 的 speak() 是"先追加用户消息 → 调 LLM → 追加回复"——这是一个事务
// CopyOnWriteArrayList 不能保证"追加用户消息 + 追加回复"是原子的
// A 的用户消息和 B 的回复可能交错
```

**v2 方案：消息队列 + 单线程消费（Actor 模型）**

```java
public class SharedAgentSession {
    private final ExecutorService agentThread = Executors.newSingleThreadExecutor();
    private final BlockingQueue<ConversationTurn> inbox = new LinkedBlockingQueue<>();

    // speak 不再阻塞调用方，把任务扔进队列就返回
    public CompletableFuture<String> speak(String userId, String message) {
        CompletableFuture<String> future = new CompletableFuture<>();
        inbox.add(new ConversationTurn(userId, message, future));
        return future;
    }

    // Agent 单线程消费队列，保证消息处理顺序
    public void start() {
        agentThread.submit(() -> {
            while (!Thread.interrupted()) {
                ConversationTurn turn = inbox.take();
                String reply = agentLoop.run(turn.message());  // 串行执行
                turn.future().complete(reply);
                notifyUI(reply);
            }
        });
    }
}
```

优点：
- 调用方不阻塞（异步返回 CompletableFuture）
- Agent 处理仍然是串行的（避免并发写 state）
- 天然支持优先级队列（VIP 用户优先处理）

缺点：
- 异步模型增加了复杂度（调用方需要处理 CompletableFuture）
- Agent 处理慢时，队列会堆积

---

### Q16. Task Handoff 的 LLM 遵循率风险

**问题**：模型可能忽略 `[handoff]` system message，继续以旧 owner 身份对话。下游的 IdentityResolver 和 PermissionChecker 会不会出问题？

**答案**：会出问题，而且是安全级别的。

**攻击场景**：
```
1. 用户 A（admin）把任务 handoff 给用户 B（viewer）
2. LLM 忽略 [handoff] system message，继续以为自己是 A
3. LLM 调用 deleteAllUsers 工具
4. PermissionChecker 检查"当前 owner"——但 AgentState.owner 已经改成 B
5. B 没有 deleteAllUsers 权限 → 被拒 ✅

看似安全？但如果：
1. LLM 在 handoff 之前就发起了一个需要 admin 权限的 tool call
2. Tool call 还在执行中，handoff 同时发生
3. PermissionChecker 检查时 owner 是 A → 放行
4. 但意图上这个操作应该是 B 发起的 → 权限提升
```

**更强制的手段**：

```java
// 方案 1：Handoff 时 reset AgentState（彻底隔离）
public void handoff(String fromUser, String toUser) {
    // 保存当前 state 快照
    MemoryEntry snapshot = saveStateSnapshot(state);

    // 重置 state
    state.getMessages().clear();
    state.addMessage(Message.system("You are now assisting " + toUser));
    state.addMessage(Message.system("Previous context summary: " + summarize(snapshot)));
    state.setOwner(toUser);

    // 重新初始化 IdentityResolver
    identityResolver.refresh(state);
}

// 方案 2：Handoff 用独立的 session scope
public void handoff(String fromUser, String toUser) {
    // 不改 AgentState，而是切换 MemoryScope
    List<String> newScopes = List.of(
        MemoryScope.user(toUser).value(),
        MemoryScope.channel(currentChannel).value(),
        MemoryScope.session(newSessionId).value()  // 新 session
    );
    contextBuilder = new MemoryContextBuilder(retriever, newScopes, ...);
}
```

方案 2 更好：不改 state，用 scope 隔离上下文。LLM 看到的 context 自动变成新用户的，即使它忽略 handoff message 也无法访问旧用户的数据。

---

## 七、架构整体（跨模块）

### Q17. 扩展点预留：如果 AgentLoop 没留 ContextBuilder 钩子

**问题**：渐进式构建的纪律是"新 Stage 不改旧代码"。如果 Stage 1 没留钩子，Stage 8 怎么做？

**答案**：三种策略，按侵入性排序。

**策略 1：改接口（向后兼容的方式）**
```java
// Stage 1 的接口
interface AgentLoop {
    ExecutionResult run(AgentState state);
}

// Stage 8 加 default 方法——不破坏现有实现
interface AgentLoop {
    ExecutionResult run(AgentState state);

    // v2 钩子：默认行为 = 不做任何事
    default List<Message> buildContext(AgentState state) {
        return new ArrayList<>(state.getMessages());
    }
}

// ReActAgentLoop 覆写 buildContext
@Override
public List<Message> buildContext(AgentState state) {
    return contextBuilder != null
        ? contextBuilder.build(state)
        : new ArrayList<>(state.getMessages());
}
```

**策略 2：事件驱动**
```java
// Stage 1 不改，Stage 8 通过事件注入
eventBus.subscribe(BeforeModelCallEvent.class, e -> {
    List<Message> memoryMessages = memoryRetriever.query(e.getState());
    e.getState().prependMessages(memoryMessages);  // 在 LLM 调用前注入
});
```

优点：不改 Stage 1 任何代码。
缺点：事件顺序不可控，多个订阅者时注入时机不确定。

**策略 3：装饰器模式（终极方案）**
```java
// Stage 8 用装饰器包装 AgentLoop
class MemoryAwareAgentLoop implements AgentLoop {
    private final AgentLoop delegate;
    private final ContextBuilder contextBuilder;

    @Override
    public ExecutionResult run(AgentState state) {
        // 在 delegate 运行前注入 memory
        contextBuilder.build(state).forEach(state::prependMessage);
        return delegate.run(state);
    }
}
```

这是最符合 agent4j 哲学的方案——和 ModelClient 的装饰器谱系一脉相承。新能力通过装饰器叠加，不碰旧代码。

---

### Q18. MemoryScope.Kind 枚举的跨模块扩展困境

**问题**：tavern 场景需要"guild:xxx"公会记忆，但不能加新的 Kind 枚举。用自定义 scope 字符串但不加 Kind 的代价是什么？

**答案**：代价是 `MemoryPolicy.defaultStatusForScope()` 的 `startsWith("channel:")` 逻辑无法覆盖新 scope——除非改代码。

**用自定义字符串但不加 Kind**：
```java
// tavern 可以直接写
MemoryScope guild = new MemoryScope("guild:warriors");

// 查询正常工作
store.query(new MemoryQuery(List.of("guild:warriors"), ...));  // OK

// 但 MemoryPolicy 不知道 guild 需要待审
MemoryPolicy.defaultStatusForScope("guild:warriors");  // → ACTIVE（默认）
// 公会记忆直接激活，没有审批——可能不符合业务需求
```

**解决路径**：

```java
// 路径 1：MemoryPolicy 改为配置驱动（推荐）
public class MemoryPolicy {
    private final Set<String> reviewRequiredPrefixes;  // 注入

    public MemoryStatus defaultStatusForScope(String scope) {
        return reviewRequiredPrefixes.stream()
            .anyMatch(scope::startsWith)
            ? MemoryStatus.PENDING_REVIEW
            : MemoryStatus.ACTIVE;
    }
}

// 注册时
MemoryPolicy policy = new MemoryPolicy(0.5, Set.of("channel:", "guild:", "tenant:"));
```

路径 1 最好：Policy 不依赖 Kind 枚举，新 scope 类型通过配置注册，不改代码。

**如果坚持用 Kind 枚举**：需要把 `MemoryScope.Kind` 从 agent-memory 模块抽成独立的 `agent-scopes-api` 模块，让 tavern 和其他模块都能扩展。但这违反了"零新增 scope kind"的蓝图约束。

**结论**：配置驱动的 Policy 比枚举驱动更灵活。Kind 枚举适合"类型安全"的场景（如工厂方法），Policy 适合"治理规则"的场景——两者用不同的机制。

---

### Q19. ProcessSandbox 的 JVM 进程开销优化

**问题**：每次执行 fork 两个进程（javac + java），5 次代码执行 = 10 个进程。怎么优化？

**答案**：当前开销约 1-2 秒/次（JVM 启动 + 编译），SWE-bench 场景 5 次执行 = 5-10 秒纯开销。

**优化方案 1：常驻 JVM（nailgun 思路）**

```java
public class ResidentProcessSandbox implements Sandbox {
    private Process nailgunServer;  // 常驻 JVM 进程

    public ResidentProcessSandbox() {
        // 启动一次，常驻运行
        nailgunServer = new ProcessBuilder("java", "-jar", "nailgun-server.jar").start();
    }

    @Override
    public SandboxResult execute(String className, String code) {
        // 编译仍需一次调用
        SandboxResult compiled = runNailgunCommand("javac", code);
        // 执行通过常驻进程，不需要重新启动 JVM
        return runNailgunCommand("java", className);
    }
}
```

开销：1 次启动 + N 次轻量调用，约 100ms/次。

**优化方案 2：GraalVM polyglot Context**

```java
public class GraalVmSandbox implements Sandbox {
    private final Context context;  // GraalVM 隔离上下文

    public GraalVmSandbox() {
        context = Context.newBuilder("java")
            .sandboxed(true)           // 安全沙箱
            .option("java.ExecAccess", "true")
            .build();
    }

    @Override
    public SandboxResult execute(String className, String code) {
        try {
            context.eval("java", code);  // 在隔离上下文执行
            // ...
        } catch (PolyglotException e) {
            if (e.isExit()) return SandboxResult.success(...);
        }
    }
}
```

优点：JVM 内部隔离，不 fork 进程，<10ms/次。
缺点：需要 GraalVM runtime，且 polyglot API 仍在演进。

**Sandbox 接口需要的改动**：
```java
public interface Sandbox extends AutoCloseable {  // 加 AutoCloseable
    SandboxResult execute(String className, String code);
    SandboxResult execute(String className, String code, SandboxSpec spec);

    // 新增：预热/初始化（常驻沙箱需要）
    default void warmup() {}

    // 新增：释放资源（常驻进程需要）
    @Override
    default void close() {}
}
```

---

### Q20. 双暂停机制统一：GraphRuntime vs GovernedToolExecutor

**问题**：如果 workflow 节点内部调了 `REQUIRES_APPROVAL` 的工具，两种暂停机制会冲突吗？

**答案**：会冲突，而且冲突方式很微妙。

**当前行为**：
```
GraphRuntime 调 HumanApprovalNode.execute()
  → HumanApprovalNode 抛 PauseException
  → GraphRuntime catch → PAUSED

但如果 HumanApprovalNode 内部调用了 GovernedToolExecutor：
  → GovernedToolExecutor 的 REQUIRES_APPROVAL
  → approvalService.request() ——这是阻塞等待！
  → GraphRuntime 的超时机制还在倒计时
  → 如果审批耗时 10 分钟，GraphRuntime 的 nodeTimeout 或 runTimeout 可能先触发
  → 节点超时失败 → 走 onError 边 → 和审批状态不一致
```

**冲突本质**：
- `GraphRuntime.PauseException` = "暂停整个 Run，等外部 resume"
- `GovernedToolExecutor.approvalService.request()` = "阻塞当前线程，等审批结果"

前者是**异步暂停**（把控制权还给调用方），后者是**同步阻塞**（线程挂着等）。两者语义不同。

**统一方案**：

```java
// GovernedToolExecutor 的 REQUIRES_APPROVAL 也抛 PauseException
public class GovernedToolExecutor implements ToolExecutor {
    @Override
    public ToolResult execute(ToolCall call, AgentState state) {
        // ...
        if (decision == ApprovalDecision.REQUIRES_APPROVAL) {
            if (approvalService.isAsync()) {
                // 抛 PauseException，让 GraphRuntime 暂停整个 Run
                throw new PauseException("Tool '" + call.name() + "' requires approval");
            } else {
                // 同步阻塞等待（兼容旧用法）
                decision = approvalService.request(call).blockingGet();
            }
        }
        // ...
    }
}
```

**但这里有个架构问题**：`GovernedToolExecutor` 在 `agent-security` 模块，`PauseException` 在 `agent-workflow` 模块——让 security 依赖 workflow 违反了模块分层。

**更好的方案**：定义一个模块无关的 `SuspendableException`：

```java
// agent-core（最底层模块）
public class SuspendableException extends RuntimeException {
    private final String reason;
    public SuspendableException(String reason) { this.reason = reason; }
}

// agent-workflow 的 PauseException 继承它
public class PauseException extends SuspendableException { ... }

// agent-security 的 RequiresApprovalException 也继承它
public class RequiresApprovalException extends SuspendableException { ... }

// GraphRuntime 统一 catch SuspendableException
catch (SuspendableException e) {
    run.setCursor(cursor);
    run.setPendingInput(lastOutput);
    run.setStatus(RunState.PAUSED);
    return ExecutionResult.paused(...);
}
```

这样两种暂停机制在 GraphRuntime 层面统一为"暂停 Run"，在各自模块内保留独立语义。这是 v2 架构最重要的统一之一。

---

## 速查表：20 题核心考点

| # | 模块 | 核心权衡 | 一句话答案 |
|---|------|---------|-----------|
| Q1 | 控制流 | 顺序 vs 并行 | v1 有意顺序，AgentState 需 CopyOnWriteArrayList + CompletableFuture 才能并行 |
| Q2 | 控制流 | 浅拷贝 vs 深拷贝 | 浅拷贝必要但脆弱，应返回 `List.copyOf()` |
| Q3 | 控制流 | 状态泄漏 | try-finally 保证 status 不泄漏 |
| Q4 | 装饰器 | 组装顺序 | Observing 在外 = 1 次请求 1 次 metric；反了 = 膨胀 2-3 倍 |
| Q5 | 装饰器 | lazy stream | peek 不消费不触发，onClose 更安全 |
| Q6 | 装饰器 | streaming 重试 | mid-stream 不可重试，只能 fallback 从头发新 chat() |
| Q7 | 安全 | 审批 vs 限流顺序 | RateLimit 应在 Approval 前，被拒请求不应消耗执行配额 |
| Q8 | 安全 | ClassLoader 逃逸 | 反射绕过是 Java 根本局限，不可信代码必须用 ProcessSandbox |
| Q9 | 安全 | System.setOut 副作用 | v2 用注入的 PrintWriter 替代全局 System.out 替换 |
| Q10 | 工作流 | PauseException 死循环 | 不会循环但会暂停黑洞，审批超时 = 自动拒绝 |
| Q11 | 工作流 | 条件边扩展 | 加 priority 字段或用合成条件，并行分支需重写 cursor 模型 |
| Q12 | 记忆 | 隐式 vs 显式安全 | v1 隐式安全极致简单，v2 工厂方法让错误不可能发生 |
| Q13 | 记忆 | Policy 与 Kind 解耦 | 配置驱动 > 枚举驱动，Policy 不应依赖 Kind 内部结构 |
| Q14 | 记忆 | TTL 惰性清理 | 写时清理最优：活跃 scope 不堆积，不引入后台线程 |
| Q15 | Channel | synchronized 粒度 | Actor 模型：消息队列 + 单线程消费，调用方异步返回 |
| Q16 | Channel | handoff 遵循率 | LLM 可能忽略 handoff message，scope 隔离比 system message 更可靠 |
| Q17 | 架构 | 扩展点预留 | 装饰器模式最优——和 ModelClient 谱系一脉相承 |
| Q18 | 架构 | Kind 枚举扩展 | 配置驱动 Policy 比枚举更灵活，治理规则不应硬编码 |
| Q19 | 沙箱 | JVM 进程开销 | 常驻 JVM（nailgun）或 GraalVM polyglot，Sandbox 加 warmup/close |
| Q20 | 架构 | 双暂停统一 | SuspendableException 基类统一 workflow 暂停和 tool 审批 |
