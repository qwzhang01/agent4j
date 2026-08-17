# Stage 3 插件系统：概念与设计

> 时间：2026-08-14
> 对应阶段：Stage 3 - 插件化与热插拔系统
> 前置知识：Stage 1-2 的 ModelClient / ToolRegistry / AgentLoop

---

## 一、Stage 3 解决什么问题

### Stage 1-2 的痛点

Stage 1-2 中，工具注册是硬编码的：

```java
InMemoryToolRegistry registry = new InMemoryToolRegistry();
registry.register(new CurrentTimeTool());
registry.register(new EchoTool());
```

这带来三个问题：

1. **加工具必须改代码**。想加一个搜索工具，需要写 `new SearchTool()`，重新编译、重新部署。Agent 运行中无法增加新能力。

2. **工具初始化失败会拖垮整个 Agent**。如果工具构造函数中连接数据库失败，异常传播到 main 方法，整个 Agent 启动失败。

3. **工具不能卸载**。`register` 之后工具永远存在。发现工具有 bug，无法在运行时移除；想升级工具到新版本，只能停服改代码。

### Stage 3 的目标

让工具能够在**运行时动态加载和卸载**，且一个插件的故障不影响其他插件和正在执行的 Agent。

---

## 二、前置概念

### 2.1 SPI（Service Provider Interface）

SPI 是 Java 标准的插件发现机制，定义在 `java.util.ServiceLoader` 中。

**原理**：在 `META-INF/services/` 目录下放置一个文件，文件名是接口的全限定名，文件内容是实现类的全限定名。`ServiceLoader`
会扫描 classpath 上的这些文件，自动实例化声明的类。

**文件示例**：

```
文件路径：META-INF/services/com.seven.agent.plugin.ToolPlugin
文件内容：
  com.seven.agent.examples.plugins.SearchToolPlugin
  com.seven.agent.examples.plugins.CalculatorToolPlugin
```

**代码使用**：

```java
ServiceLoader<ToolPlugin> loader = ServiceLoader.load(ToolPlugin.class);
for (ToolPlugin plugin : loader) {
    // plugin 是 SearchToolPlugin 或 CalculatorToolPlugin 的实例
    // ServiceLoader 通过无参构造函数创建实例
}
```

**关键点**：调用方不需要 import 具体实现类，不需要 new，不需要知道实现类在哪里。ServiceLoader 负责发现和实例化。这就是"
零胶水代码"的含义。

**SPI vs API 的区别**：

- API（Application Programming Interface）：你调用别人提供的接口。
- SPI（Service Provider Interface）：别人实现你定义的接口，你来发现和调用。

在本项目中，`ToolPlugin` 是我们定义的 SPI 接口，`SearchToolPlugin`
是别人（或我们自己写的另一个模块）提供的实现。`ServiceLoader` 负责发现实现。

### 2.2 装饰器模式（Decorator Pattern）

Stage 1-2 已使用。核心思想：装饰器和被装饰对象实现同一接口，装饰器内部持有被装饰对象的引用，在调用前后增加自己的逻辑。

本阶段的插件系统不使用装饰器模式，但使用了类似的思想：插件和框架通过同一接口（`Plugin`）交互，插件在 `onLoad`
中向框架注册能力，在 `onUnload` 中撤销注册。

### 2.3 注册是可逆的（Reversible Registration）

这是 Stage 3 的核心设计原则，借鉴自 DeepSeek Harness 的 Cordis 框架。

传统注册是单向的：`register(tool)` 之后，工具永远存在，没有撤销机制。

可逆注册的含义：每次注册都对应一个撤销操作。`onLoad` 中注册，`onUnload` 中注销。两者必须对称：

```java
@Override
public void onLoad(PluginContext context) {
    context.getToolRegistry().register(new SearchTool());  // 注册
}

@Override
public void onUnload(PluginContext context) {
    context.getToolRegistry().unregister("search_web");     // 注销
}
```

可逆注册是实现热卸载的前提。如果注册不可逆，卸载后工具还在注册表里，就不是真正的"卸载"。

### 2.4 故障隔离（Failure Isolation）

故障隔离的含义：一个组件的异常不传播到其他组件。

在本阶段中，如果插件 A 的 `onLoad` 抛异常：

- 插件 A 标记为 `FAILED` 状态
- 异常不向上传播（不 throw）
- 插件 B 照常加载
- Agent 正常运行，只是少了插件 A 的工具

实现方式是 try-catch 包裹 `onLoad` 调用，捕获异常后记录状态，不重新抛出。

---

## 三、核心类设计

### 3.1 Plugin 接口

```java
public interface Plugin {
    PluginDescriptor descriptor();
    void onLoad(PluginContext context);
    void onUnload(PluginContext context);
}
```

三个方法的职责：

| 方法                  | 调用时机 | 插件做什么                           |
|---------------------|------|---------------------------------|
| `descriptor()`      | 任何时候 | 返回自己的元数据（名称、版本、描述）              |
| `onLoad(context)`   | 加载时  | 通过 context 拿到 ToolRegistry，注册工具 |
| `onUnload(context)` | 卸载时  | 通过 context 拿到 ToolRegistry，注销工具 |

`onLoad` 和 `onUnload` 必须对称：`onLoad` 注册了什么，`onUnload` 就注销什么。

### 3.2 PluginDescriptor

```java
public record PluginDescriptor(
    String name,         // 唯一标识，如 "search-tool"
    String version,      // 语义版本，如 "1.0.0"
    String description   // 人类可读描述
) {}
```

使用 Java `record`（Java 16 final），因为元数据是不可变值对象。record 自动生成构造函数、getter、equals、hashCode、toString。

`name` 必须唯一，用作 PluginRegistry 中的 key。两个插件同名会冲突。

### 3.3 PluginState

```java
public enum PluginState {
    DETECTED,   // ServiceLoader 发现了，但还没加载
    LOADED,     // onLoad 成功，工具已注册
    UNLOADED,   // onUnload 完成，工具已注销
    FAILED      // onLoad 抛异常，插件不可用
}
```

状态转移：

```
DETECTED --load()成功--> LOADED --unload()--> UNLOADED --load()--> LOADED
                           |
                   load()失败  --> FAILED
```

`FAILED` 是终态：加载失败的插件不会自动重试，需要手动调 `reload`。

### 3.4 PluginContext

```java
public interface PluginContext {
    ToolRegistry getToolRegistry();
}
```

插件通过 PluginContext 访问框架的注册表。目前只暴露 `ToolRegistry`，后续阶段会增加 Memory Store、Policy Engine 等。

**为什么不直接传 ToolRegistry？** 因为 PluginContext 是一个扩展点。现在只有 `getToolRegistry()`
，以后加 `getMemoryStore()`、`getPolicyEngine()` 时，只改 PluginContext 接口，不改 Plugin 接口的签名。如果直接传
ToolRegistry，以后加新能力就要改 Plugin 接口的方法签名，破坏向后兼容。

### 3.5 ToolPlugin

```java
public interface ToolPlugin extends Plugin {}
```

标记接口（Marker Interface），不增加任何方法。作用是让 SPI 按类型发现：`ServiceLoader.load(ToolPlugin.class)`
只找工具插件，不找其他类型插件。

**为什么有 Plugin 还要 ToolPlugin？** 因为后续阶段会有 MemoryPlugin、PolicyPlugin、ModelAdapterPlugin 等。它们都 extends
Plugin，但各自有标记接口。SPI 按标记接口分类发现。

### 3.6 PluginRegistry

PluginRegistry 是插件生命周期管理的核心。它维护一个 `Map<String, PluginEntry>`，key 是插件名，value 是插件条目（包含插件实例、状态、错误信息）。

**load 方法**：

```java
public void load(Plugin plugin) {
    String name = plugin.descriptor().name();
    PluginContext context = new SimplePluginContext(toolRegistry);

    PluginEntry entry = new PluginEntry(plugin, context, DETECTED, null);
    plugins.put(name, entry);

    try {
        plugin.onLoad(context);
        entry.state = LOADED;
    } catch (Exception e) {
        entry.state = FAILED;
        entry.error = e;
        // 不抛异常 -- 故障隔离
    }
}
```

关键点：`onLoad` 的异常被 catch，不向上传播。插件标记为 `FAILED`，其他插件不受影响。

**unload 方法**：

```java
public void unload(String name) {
    PluginEntry entry = plugins.get(name);

    try {
        entry.plugin.onUnload(entry.context);
    } catch (Exception e) {
        // 记日志，但不抛异常
    }

    entry.state = UNLOADED;
}
```

关键点：`onUnload` 的异常也被 catch。即使卸载失败，插件仍标记为 `UNLOADED`（best-effort 清理）。这样不会因为卸载失败卡住整个系统。

### 3.7 PluginManager

PluginManager 是 PluginRegistry 之上的门面（Facade），负责 SPI 发现和批量操作。

**discover 方法**：

```java
public int discover() {
    ServiceLoader<ToolPlugin> loader = ServiceLoader.load(ToolPlugin.class);
    for (ToolPlugin plugin : loader) {
        discovered.put(plugin.descriptor().name(), plugin);
    }
    return discovered.size();
}
```

调用 `ServiceLoader.load(ToolPlugin.class)` 扫描 classpath 上所有 `META-INF/services/com.seven.agent.plugin.ToolPlugin`
文件，实例化其中声明的类。

**loadAll 方法**：

```java
public int loadAll() {
    if (discovered.isEmpty()) {
        discover();  // 自动发现
    }
    for (Plugin plugin : discovered.values()) {
        registry.load(plugin);  // 委托给 PluginRegistry
    }
}
```

**为什么分 PluginRegistry 和 PluginManager 两层？**

- PluginRegistry 管生命周期（load/unload/reload），不管怎么发现插件。
- PluginManager 管 SPI 发现 + 批量操作，委托给 PluginRegistry。

这样如果要换发现机制（比如以后从远程仓库下载插件），只改 PluginManager，不改 PluginRegistry。

---

## 四、插件生命周期流程

### 4.1 完整流程

```
1. 启动
   创建 InMemoryToolRegistry
   创建 PluginManager(toolRegistry)

2. 发现（discover）
   PluginManager 调用 ServiceLoader.load(ToolPlugin.class)
   扫描 META-INF/services/ 文件
   找到 SearchToolPlugin 和 CalculatorToolPlugin
   状态 = DETECTED

3. 加载（loadAll）
   对每个发现的插件调用 PluginRegistry.load(plugin)
   PluginRegistry 创建 PluginContext（包含 ToolRegistry 引用）
   调用 plugin.onLoad(context)
     -> SearchToolPlugin.onLoad() 执行 registry.register(new SearchTool())
     -> CalculatorToolPlugin.onLoad() 执行 registry.register(new CalculatorTool())
   onLoad 成功 -> 状态 = LOADED
   onLoad 失败 -> 状态 = FAILED（异常被 catch，不传播）

4. 运行中
   Agent 的 ReActAgentLoop 每轮循环调用 registry.getToolSchemas()
   工具列表动态反映已加载插件注册的工具
   Agent 代码不感知插件系统的存在

5. 卸载（unload）
   PluginManager.unload("search-tool")
   -> PluginRegistry.unload("search-tool")
   -> 调用 plugin.onUnload(context)
   -> SearchToolPlugin.onUnload() 执行 registry.unregister("search_web")
   -> 状态 = UNLOADED
   -> Agent 下一轮循环就看不到 search_web 工具了

6. 重载（reload）
   PluginManager.reload("search-tool")
   -> PluginRegistry.reload("search-tool")
   -> 先 unload（注销工具）
   -> 再 load（重新注册工具）
   -> 状态 = LOADED
```

### 4.2 故障隔离流程

```
插件 A（正常）：
  onLoad() -> registry.register(toolA) -> 状态 = LOADED

插件 B（onLoad 抛异常）：
  onLoad() -> 抛 RuntimeException
  -> PluginRegistry catch 异常
  -> 状态 = FAILED
  -> error = 异常对象
  -> 不向上传播

结果：
  - 插件 A 的 toolA 在注册表中，可用
  - 插件 B 没有 toolB 在注册表中，不可用
  - 插件 B 的异常没有影响插件 A
  - 插件 B 的异常没有影响 PluginRegistry
  - 插件 B 的异常没有影响 Agent 运行
```

---

## 五、一个完整插件示例

```java
public class SearchToolPlugin implements ToolPlugin {

    // 1. 元数据
    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor(
            "search-tool",      // 唯一名称
            "1.0.0",            // 版本
            "Web search tool"   // 描述
        );
    }

    // 2. 加载时：注册工具
    @Override
    public void onLoad(PluginContext context) {
        context.getToolRegistry().register(new SearchTool());
    }

    // 3. 卸载时：注销工具
    @Override
    public void onUnload(PluginContext context) {
        context.getToolRegistry().unregister("search_web");
    }

    // 4. 工具实现（内部类）
    public static class SearchTool implements Tool {
        @Override
        public String getName() { return "search_web"; }

        @Override
        public String getDescription() { return "Search the web"; }

        @Override
        public String getParametersSchema() { return "{ ... }"; }

        @Override
        public String execute(JsonNode arguments) {
            String query = arguments.path("query").asText();
            return "Search results for '" + query + "'";
        }
    }
}
```

对应的 SPI 声明文件：

```
文件：META-INF/services/com.seven.agent.plugin.ToolPlugin
内容：com.seven.agent.examples.plugins.SearchToolPlugin
```

---

## 六、设计决策记录

### 为什么 onLoad 失败不抛异常？

如果 `onLoad` 失败抛异常到 PluginManager，PluginManager 的 `loadAll` 循环会中断，后续插件不会加载。一个坏插件拖垮所有插件，违反故障隔离原则。

选择：catch 异常，标记 FAILED，继续加载其他插件。

### 为什么 onUnload 失败也标记 UNLOADED？

`onUnload` 的职责是清理（注销工具）。如果清理失败，工具可能还残留在注册表中。但插件本身已经不可用了，标记为 UNLOADED 表示"
这个插件不再活跃"。

如果标记为 FAILED，管理员可能以为插件还在运行，造成混淆。

选择：best-effort 清理，无论如何标记 UNLOADED，记录错误日志。

### 为什么不做 DISABLED 状态？

学习规划中提到了"加载 -> 启用 -> 禁用 -> 卸载"四态。但 Stage 3 选择最小版，只有 LOADED / UNLOADED 两态。

DISABLED 的语义是"插件已加载但暂停使用"。这需要 ToolRegistry 支持"临时隐藏工具"，而不是"移除工具"。这增加了 ToolRegistry
的复杂度。

选择：先做两态。DISABLED 留到后续阶段，需要时再加。

### 为什么不做 ClassLoader 隔离？

学习规划中提到"自定义 ClassLoader、JPMS 模块化"。这涉及插件类加载隔离，防止插件 A 和插件 B 的依赖版本冲突。

但 ClassLoader 隔离的实现复杂度高（需要处理类加载委托模型、资源隔离、卸载时的类 GC），且 Stage 3 的最小版用 SPI + 同一
ClassLoader 就能跑通。

选择：同 ClassLoader，不做隔离。后续阶段如果遇到版本冲突问题再加。

---

## 七、与 Stage 1-2 的关系

Stage 3 没有修改 Stage 1-2 的任何接口。`ToolRegistry` 的 `register` 和 `unregister` 方法在 Stage 1-2 就已定义。Stage 3
新增的是 Plugin / PluginRegistry / PluginManager 这一层，它在 ToolRegistry 之上，通过调用 ToolRegistry 的方法实现动态注册/注销。

```
Stage 1-2:
  main() --手动 register--> ToolRegistry --> AgentLoop 读取

Stage 3:
  PluginManager --load--> PluginRegistry --onLoad--> Plugin
                                                |
                                                +--> ToolRegistry.register()
                                                |
  PluginManager --unload--> PluginRegistry --onUnload--> Plugin
                                                  |
                                                  +--> ToolRegistry.unregister()
                                                |
  AgentLoop 读取 ToolRegistry（不感知插件系统的存在）
```

Agent 代码、AgentLoop、ToolRegistry、ToolExecutor 全部不变。新增的插件系统是 ToolRegistry 之上的管理层。

---

## 八、文件清单

```
agent-plugin/src/main/java/com/seven/agent/plugin/
├── Plugin.java               # 插件接口：descriptor + onLoad + onUnload
├── PluginDescriptor.java      # 元数据 record：name + version + description
├── PluginState.java          # 状态枚举：DETECTED / LOADED / UNLOADED / FAILED
├── PluginException.java      # 插件异常（带 pluginName）
├── PluginContext.java         # 插件上下文：提供 ToolRegistry 访问
├── ToolPlugin.java           # Tool 插件标记接口（SPI 发现用）
├── PluginRegistry.java       # 生命周期管理：load / unload / reload + 故障隔离
└── PluginManager.java        # SPI 发现 + 批量操作

examples/src/main/java/com/seven/agent/examples/plugins/
├── SearchToolPlugin.java     # 示例：搜索工具插件
└── CalculatorToolPlugin.java # 示例：计算器工具插件

examples/src/main/resources/META-INF/services/
└── com.seven.agent.plugin.ToolPlugin  # SPI 声明文件

examples/src/main/java/com/seven/agent/examples/
└── PluginExample.java        # 演示：发现 -> 加载 -> 卸载 -> 重载

agent-plugin/src/test/java/com/seven/agent/plugin/
├── PluginLifecycleTest.java # 11 个测试：生命周期 + 故障隔离
└── PluginManagerTest.java   # 8 个测试：SPI 发现 + 批量操作
```

---

## 九、验证对照

| 学习规划验收要求                   | 状态 | 实现                                             |
|----------------------------|----|------------------------------------------------|
| 动态加载一个新 Tool 插件并立即可用       | ✅  | `PluginManager.load()`                         |
| 动态卸载一个插件，进行中的 Run 不受影响     | ✅  | `PluginManager.unload()`，Agent 只看 ToolRegistry |
| 一个插件抛出异常时，其他插件和 Agent 正常运行 | ✅  | PluginRegistry catch 异常，标记 FAILED              |
| 插件升级时旧版本和新版本能短暂共存          | ⬜  | 未实现（最小版不含版本管理）                                 |
