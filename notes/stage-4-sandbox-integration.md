# Stage 4 补充：沙箱集成链路--谁触发、谁调用、谁终止、结果谁用

> 时间：2026-08-16
> 对应阶段：Stage 4 沙箱与隔离执行（集成篇）
> 前置：stage-4-sandbox.md（实现篇）
> 端到端验证：`SandboxAgentExample`（模型驱动的完整闭环，两场景实测通过）

---

## 一、参与者总览

| 参与者                                     | 角色   | 在链路中的位置                                      |
|-----------------------------------------|------|----------------------------------------------|
| 模型（LLM）                                 | 决策者  | 决定"要执行代码"，发出 toolCall                        |
| `ReActAgentLoop`                        | 调度者  | 检测 toolCalls，分发执行，回收结果                       |
| `DefaultToolExecutor`                   | 执行分发 | 从 ToolRegistry 查找 `sandbox_execute` 对应的 Tool |
| `ToolRegistry`                          | 注册表  | 持有 SandboxTool 实例                            |
| `SandboxTool`                           | 适配器  | 实现 `Tool` 接口，把 Sandbox 适配为模型可调用的工具           |
| `ClassLoaderSandbox` / `ProcessSandbox` | 沙箱本体 | 编译、加载/子进程、执行、超时控制                            |
| `SandboxResult`                         | 结果载体 | record，屏蔽两种实现的差异                             |
| `AgentState.messages`                   | 上下文  | 工具结果最终落点，供模型下一轮读取                            |

两种使用方式对比：

```
方式 A：开发者直接调用（SandboxExample 演示）
  main() -> sandbox.execute(...)          -- 测试、调试用

方式 B：模型驱动（SandboxAgentExample 演示，生产路径）
  模型 -> toolCall -> ... -> sandbox.execute(...)
  -- Agent 运行时的真实链路，下文展开
```

---

## 二、触发与调用链（完整时序）

```
用户："帮我算 1-100 的和"
  |
  v
SimpleAgent.run()
  |
  v
ReActAgentLoop 第 N 轮循环
  |-- buildRequest: registry.getToolSchemas()
  |    -> 模型看到工具列表中有 sandbox_execute 的 schema
  |
  |-- modelClient.chat(request)
  |       |
  |       v
  |   模型决策："写一段 Java 代码来算"
  |   返回 ModelResponse:
  |     toolCalls = [ToolCall(name="sandbox_execute",
  |                args={class_name:"Generated",
  |                      code:"public class Generated {...}"})]
  |
  |-- response.hasToolCalls() == true
  |       |
  |       v
  |   DefaultToolExecutor.execute(toolCall)          <- 调用方
  |       |-- registry.getTool("sandbox_execute")
  |       |       -> 找到 SandboxTool 实例
  |       |
  |       v
  |   SandboxTool.execute(args)                       <- 唤起点
  |       |-- sandbox.execute("Generated", code)
  |       |
  |       v
  |   ClassLoaderSandbox / ProcessSandbox             <- 沙箱本体
  |       (内部流程见第三节)
  |       |
  |       v
  |   返回 SandboxResult
  |       |
  |       v
  |   SandboxTool 转 JSON 字符串：
  |   '{"success":true,"stdout":"Sum = 5050","timedOut":false}'
  |
  v
AgentLoop: state.addMessage(ChatMessage.tool(id, result))   <- 结果去向
  |
  v
第 N+1 轮循环：buildRequest 把含工具结果的 messages 发给模型
  |
  v
模型读到 "Sum = 5050" -> 生成最终回答 -> finishReason="stop" -> 循环结束
```

**触发者一句话**：触发是**模型**（它决定调 sandbox_execute）；调用者是 **AgentLoop 通过 ToolExecutor**；沙箱本身是被动的服务提供方，从不主动运行。

### 角色间的依赖关系

```
模型 不知道 SandboxTool 的存在，只知道工具 schema
AgentLoop 不知道 Sandbox 的存在，只跟 ToolExecutor / ToolRegistry 交互
SandboxTool 不知道 AgentLoop 的存在，只跟 Sandbox 接口交互
Sandbox 不知道调用者是谁

-> 全链路通过接口解耦，任何一层都可替换
```

---

## 三、沙箱内部的两种唤起方式

### 3.1 ClassLoaderSandbox（进程内）

```java
// execute(className, code, spec) 内部，五步：
1. InMemoryCompiler.compile()           // 编译：源码 -> Map<String,byte[]>
2. new SandboxClassLoader(parent, bytes, blockedList)  // 构造受限类加载器
3. System.setOut(重定向)                 // 捕获 stdout
4. executor.submit(() -> {              // 唤起：提交到独立沙箱线程
       sandboxLoader.loadClass(className)   // 加载（拦截检查在此发生）
       clazz.getMethod("run").invoke(null)  // 反射调用入口方法
   })
5. future.get(spec.getTimeout())        // 主线程限时等待
```

执行环境：**同一个 JVM 内的独立线程 + 独立 ClassLoader**。唤起动作是 `run.invoke(null)`。

### 3.2 ProcessSandbox（子进程）

```
1. Files.createTempDirectory("sandbox-")     // 沙箱工作目录
2. Files.writeString(dir/Generated.java)     // 源码落盘
3. ProcessBuilder("javac", ...).start()      // 唤起①：编译子进程
4. ProcessBuilder("java", "-cp", dir, "Generated").start()  // 唤起②：执行子进程
   ├── 独立线程读 stdout/stderr（防管道缓冲区满死锁）
   └── process.waitFor(timeout)              // 主线程限时等待
```

执行环境：**独立 OS 进程**。唤起动作是子进程加载并执行编译产物。

### 3.3 唤起时序对比

```
             ClassLoaderSandbox              ProcessSandbox
线程模型      同 JVM，沙箱线程（daemon）        独立 OS 进程
编译位置      进程内（Java Compiler API）       javac 子进程
执行位置      进程内反射调用                    java 子进程
约束生效点    loadClass 拦截检查                工作目录约束
典型耗时      ~100ms                           ~2s（两次 JVM 启动）
```

---

## 四、谁终止，四种情况

| 情况     | 触发条件            | ClassLoaderSandbox 的终止动作                                                                 | ProcessSandbox 的终止动作                                  |
|--------|-----------------|------------------------------------------------------------------------------------------|-------------------------------------------------------|
| 正常完成   | `run()` 返回      | future 正常完成，取返回值                                                                         | 子进程退出，waitFor 返回，exitCode=0                           |
| 业务异常   | 代码抛异常           | catch InvocationTargetException，解包 cause 转 `SandboxResult.error`                         | 子进程非零退出，stderr 含异常栈                                   |
| 访问控制拒绝 | 代码触碰拦截类         | 加载时抛 SecurityException -> `SandboxResult.blocked`                                        | （无此机制，靠工作目录约束）                                        |
| 超时     | 超过 spec.timeout | **主线程** `future.cancel(true)` 中断沙箱线程 + `executor.shutdownNow()`；死循环不响应中断时线程泄漏为 daemon 线程 | **主线程** `process.destroyForcibly()` 发送 SIGKILL，内核保证终止 |

**终止权设计**：

```
终止权始终在主线程（调用沙箱的一方）：
  future.get(timeout) / waitFor(timeout)  -- 限时等待
  cancel(true) / destroyForcibly()        -- 超时行使终止权

沙箱线程/子进程自身没有任何中止他人的能力。

finally 块保证无论哪种路径：
  - 恢复 System.out / System.err
  - executor.shutdownNow()
  - 清理临时目录（ProcessSandbox）
```

ClassLoader 版超时的已知局限：`cancel(true)` 只是中断信号，不响应中断的死循环代码所在线程无法真正停止，会以 daemon
线程形式存续（不阻止 JVM 退出，但占用 CPU）。这是进程内沙箱的固有缺陷，进程版通过 SIGKILL 无此问题。

---

## 五、返回结果的完整去向

```
SandboxResult（record：success/stdout/stderr/exitCode/timedOut/error）
  |
  | SandboxTool.execute() 转 JSON
  v
'{"success":true,"stdout":"Sum 1-100 = 5050"}'   (String)
  |
  | DefaultToolExecutor 原样返回
  | （异常已由沙箱内部消化，不会以异常形式抛出）
  v
ReActAgentLoop: state.addMessage(ChatMessage.tool(callId, json))
  |
  v
AgentState.messages（追加一条 role=TOOL 的消息）
  |
  | 下一轮 buildRequest 时随完整历史发送
  v
模型读到结果 --+- 信息足够 -> 生成最终回答，循环结束
              +- 需要再执行/修正代码 -> 再次返回 sandbox_execute toolCall（循环继续）
```

**结果唯一消费者是模型**。人类用户看到的最终回答，是模型基于这个工具结果生成的自然语言，不是 SandboxResult 本身。

关键点：**沙箱执行失败不会中断 Agent 循环**。无论编译失败、业务异常、拦截还是超时，SandboxResult 都以 `success=false` + error
描述的形式返回，模型读到失败信息后自行决策（换一种写法 / 向用户说明限制）。这与 DefaultToolExecutor
包装工具异常的设计一致--工具失败是业务信息，不是系统故障。

---

## 六、端到端验证（SandboxAgentExample）

示例位置：`examples/src/main/java/.../SandboxAgentExample.java`

### 场景 1：正常执行

```
用户："Compute the sum of 1 to 100"

Mock 模型第 1 轮：返回 toolCall sandbox_execute
  args = {class_name:"Generated",
          code:"public class Generated {
                  public static String run() {
                    int sum = 0;
                    for (int i = 1; i <= 100; i++) sum += i;
                    return \"Sum 1-100 = \" + sum; } }"}

AgentLoop -> ToolExecutor -> SandboxTool -> ClassLoaderSandbox
  编译 -> 加载 -> 反射调用 run() -> 返回

工具结果（role=TOOL 消息）：
  '{"success":true,"stdout":"Sum 1-100 = 5050","timedOut":false}'

Mock 模型第 2 轮：读到 5050，返回最终回答
  "The sum of 1 to 100 is 5050. I generated Java code and executed it in the sandbox."

实测：Completed in 2 steps
```

### 场景 2：访问拦截后模型自我修正

```
用户："Check if /etc/passwd exists"

Mock 模型第 1 轮：返回 toolCall，代码引用 java.io.File
AgentLoop -> ... -> ClassLoaderSandbox
  编译期拦截：java.io.File 在默认拦截列表 -> 编译失败

工具结果：
  '{"success":false,"error":"Compilation failed: ... java.io.File ..."}'

Mock 模型第 2 轮：读到失败，如实回答
  "I could not read the file: sandbox blocked access to java.io.File.
   File system access is not allowed."

实测：Completed in 2 steps
```

场景 2 展示了完整闭环的关键价值：**约束拒绝不是异常，是模型的输入**。模型拿到 blocked 信息后，向用户解释限制而不是崩溃--这正是
dsh "拒绝与基础设施故障正交分类"设计想达到的效果（我们当前以编译失败文本近似实现，拒绝归因体系见 stage-4 笔记后续路线）。

### 运行方式

```bash
mvn package -DskipTests
java -cp <各模块 jar + 依赖> \
  io.github.qwzhang01.agent.examples.SandboxAgentExample
```

---

## 七、与 Stage 3.5 自进化的衔接

沙箱链路打通后，自进化的下一级形态已经具备条件：

```
当前（Stage 3.5）：
  模型只能加载 SPI 预发现的插件
  plugin_load("search-tool")  -- 从已有集合中选择

具备条件（沙箱 + 插件 + Tool 已齐）：
  模型写代码 -> sandbox_execute 试运行验证
  -> 验证通过后动态注册为 Tool
  -- 对标 dsh 的 cordis_define + cordis_run

差距：
  - SandboxTool 执行的代码与 Tool 注册之间缺一座桥
    （试运行通过的 Generated 类需要包装为 Tool 实例注册进 Registry）
  - 需要人工确认环节（Stage 9 Tool Governance：审批 + 审计）
```

这座桥是 Stage 4 与 Stage 3.5 汇合的点，也是 agent4j 从"管理已有能力"迈向"创造新能力"的分界线。

---

## 八、文件清单

```
新增：
examples/src/main/java/io/github/qwzhang01/agent/examples/
└── SandboxAgentExample.java        # 模型驱动的端到端闭环（两场景）

已有（本笔记涉及）：
agent-sandbox/src/main/java/io/github/qwzhang01/agent/sandbox/
├── Sandbox.java / SandboxSpec.java / SandboxResult.java
├── classloader/ClassLoaderSandbox.java / InMemoryCompiler.java / SandboxClassLoader.java
├── process/ProcessSandbox.java
└── tools/SandboxTool.java
```

---

## 九、面试表达

> 我们的沙箱通过 SandboxTool 适配成模型可调用的工具接入 Agent 循环：模型返回 sandbox_execute 的 toolCall，AgentLoop 经
> ToolExecutor 从 ToolRegistry 分发到 SandboxTool，再委托给 Sandbox 接口的两个实现之一。ClassLoader 版在同 JVM
> 的独立线程中以受限类加载器执行，超时由主线程 Future.cancel 行使终止权；进程版在子进程中执行，超时由 destroyForcibly 发送
> SIGKILL。执行结果无论成败都封装为 SandboxResult 转成 JSON 工具消息写回
> AgentState，由模型在下一轮读取后自行决策--拦截和失败是模型的输入而不是系统异常。端到端两场景实测：正常计算 2
> 步完成；代码触碰文件系统时在编译期被拦截，模型如实向用户说明限制。
