# 插件系统设计：概念汇总

> 时间：2026-08-14
> 对应阶段：Stage 3（插件系统）+ Stage 3.5（Agent 自进化）
> 目标：把插件系统涉及的所有概念、设计模式、Java 基础知识整理成一篇完整笔记

---

## 一、插件系统解决什么问题

### 1.1 硬编码的局限

Stage 1-2 中，工具注册是硬编码的：

```java
InMemoryToolRegistry registry = new InMemoryToolRegistry();
registry.register(new CurrentTimeTool());
registry.register(new EchoTool());
```

这种方式的限制：

1. **编译时绑定**：工具类在编译时确定，运行时不能增减。要加工具必须改代码、重新编译、重新部署。
2. **初始化耦合**：如果 `new DatabaseTool(dataSource)` 在构造时连接数据库失败，异常传播到 main，整个 Agent 启动失败。
3. **不可卸载**：`register` 之后工具永远存在，无法在运行时移除有 bug 的工具。

### 1.2 插件系统的目标

让能力（工具）能够在运行时动态加载和卸载，且一个插件的故障不影响其他插件和正在执行的 Agent。

进一步的目标（Stage 3.5）：让 Agent 自己在对话过程中决定加载和卸载什么能力，而不是由人在代码中决定。

---

## 二、Java 基础概念

### 2.1 SPI（Service Provider Interface）

SPI 是 Java 标准的插件发现机制，定义在 `java.util.ServiceLoader` 中（Java 6 引入）。

#### 原理

在 `META-INF/services/` 目录下放置一个文件：
- 文件名 = 接口的全限定名
- 文件内容 = 实现类的全限定名（每行一个）

`ServiceLoader` 会扫描 classpath 上所有 JAR 中的这些文件，通过无参构造函数实例化声明的类。

#### 文件示例

```
文件路径：META-INF/services/com.seven.agent.plugin.ToolPlugin
文件内容：
  com.seven.agent.examples.plugins.SearchToolPlugin
  com.seven.agent.examples.plugins.CalculatorToolPlugin
```

#### 代码使用

```java
ServiceLoader<ToolPlugin> loader = ServiceLoader.load(ToolPlugin.class);
for (ToolPlugin plugin : loader) {
    // plugin 是 SearchToolPlugin 或 CalculatorToolPlugin 的实例
    // ServiceLoader 通过无参构造函数创建
}
```

#### SPI vs API

- **API（Application Programming Interface）**：你调用别人提供的接口。调用方知道接口，提供方实现接口。
- **SPI（Service Provider Interface）**：你定义接口，别人实现，你来发现和调用。调用方不需要 import 具体实现类。

在本项目中：`ToolPlugin` 是我们定义的 SPI 接口，`SearchToolPlugin` 是实现。`ServiceLoader` 负责发现实现，调用方不需要知道实现类在哪里。

#### SPI 的局限

1. 实现类必须有无参构造函数。
2. 无法传参给构造函数（如果插件需要配置，必须在 `onLoad` 中通过 `PluginContext` 获取）。
3. 所有实现共享同一个 ClassLoader（不做类隔离时）。
4. 发现顺序不保证（`ServiceLoader` 按 JAR 在 classpath 上的顺序扫描）。

### 2.2 接口（Interface）与标记接口（Marker Interface）

#### 普通接口

```java
public interface Plugin {
    PluginDescriptor descriptor();
    void onLoad(PluginContext context);
    void onUnload(PluginContext context);
}
```

定义了契约：实现类必须提供这些方法。

#### 标记接口

```java
public interface ToolPlugin extends Plugin {}
```

不增加任何方法。作用是**类型标记**：`ServiceLoader.load(ToolPlugin.class)` 只找标记了 `ToolPlugin` 的类，不找其他 `Plugin` 实现。

Java 标准库中的例子：`Serializable` 和 `Cloneable` 是标记接口，不定义方法，只标记"这个类可以被序列化/克隆"。

#### 为什么用标记接口而不是注解？

注解（如 `@ToolPlugin`）也能做标记，但 SPI 基于 `ServiceLoader` 只能按接口类型发现，不支持按注解发现。所以这里必须用接口。

### 2.3 record（Java 16 final）

```java
public record PluginDescriptor(
    String name,
    String version,
    String description
) {}
```

`record` 是 Java 16 正式引入的语法，用于定义不可变值对象。编译器自动生成：
- 全参构造函数
- getter（`name()` 而非 `getName()`）
- `equals()` / `hashCode()` / `toString()`

适用于元数据、DTO、配置等纯数据场景。

### 2.4 enum（枚举）

```java
public enum PluginState {
    DETECTED, LOADED, UNLOADED, FAILED
}
```

Java 枚举是完整的类，可以有字段、方法、构造函数。本项目中用于表示插件的生命周期状态，配合状态机使用。

### 2.5 Optional（Java 8）

```java
public Optional<PluginState> getState(String name) {
    PluginEntry entry = plugins.get(name);
    return entry != null ? Optional.of(entry.state) : Optional.empty();
}
```

`Optional` 是容器对象，可能包含值也可能为空。比直接返回 `null` 更明确，强制调用方处理"不存在"的情况。

---

## 三、设计模式

### 3.1 门面模式（Facade）

**PluginManager 是 PluginRegistry 的门面。**

PluginRegistry 负责生命周期管理（load/unload/reload），PluginRegistry 只管"怎么加载和卸载"。PluginManager 在其之上增加 SPI 发现和批量操作，对外提供简化的 API。

```java
// PluginManager（门面）
public class PluginManager {
    private final PluginRegistry registry;  // 委托给 Registry

    public int loadAll() {
        if (discovered.isEmpty()) discover();  // 门面负责发现
        for (Plugin plugin : discovered.values()) {
            registry.load(plugin);             // 委托给 Registry 加载
        }
    }
}
```

**为什么分两层？** 如果要换发现机制（如从远程仓库下载插件），只改 PluginManager，不改 PluginRegistry。单一职责。

### 3.2 标记接口模式（Marker Interface）

`ToolPlugin` 是标记接口，不增加方法，只用于 SPI 按类型发现。详见 2.2 节。

### 3.3 状态机模式（State Machine）

插件的生命周期用状态枚举和状态转移规则管理：

```
DETECTED --load()成功--> LOADED --unload()--> UNLOADED --load()--> LOADED
                           |
                   load()失败  --> FAILED
```

状态转移规则在 PluginRegistry 的 `load` 和 `unload` 方法中实现：
- `load` 成功 -> `LOADED`
- `load` 失败 -> `FAILED`
- `unload` 无论成功失败 -> `UNLOADED`

状态机保证插件不会处于不一致的状态（如"已加载但 onLoad 没调过"）。

### 3.4 策略模式（Strategy）的雏形

`Plugin.onLoad(PluginContext context)` 让每个插件自己决定怎么注册。框架只调 `onLoad`，不关心插件内部注册了什么工具。插件是策略的具体实现，框架是策略的调用方。

### 3.5 自引用模式（Self-Referential）

Stage 3.5 的核心设计。插件管理操作（inspect/list/load/unload）本身被包装成 `Tool`，模型通过 ReAct 循环调用这些 Tool 来管理插件系统。

```
ToolRegistry 中有：
  ├── plugin_inspect（查看插件状态）
  ├── plugin_list（列出可用插件）
  ├── plugin_load（加载插件）
  ├── plugin_unload（卸载插件）
  └── search_web（由 plugin_load 动态注册）
```

模型调用 `plugin_load("search-tool")` 后，`search_web` 出现在 ToolRegistry 中。模型下一轮循环就能调用 `search_web`。

**自引用**：系统包含操作自身的能力。模型的工具集不是固定的，而是模型自己可以修改的。

---

## 四、核心设计原则

### 4.1 注册是可逆的（Reversible Registration）

借鉴自 DeepSeek Harness 的 Cordis 框架。传统注册是单向的，可逆注册要求 `onLoad` 和 `onUnload` 对称：

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

可逆注册是实现热卸载的前提。如果注册不可逆，卸载后工具还在注册表里，就不是真正的卸载。

### 4.2 故障隔离（Failure Isolation）

一个插件失败不影响其他。通过 try-catch 包裹 `onLoad` 和 `onUnload` 调用，不重新抛出异常实现：

```java
try {
    plugin.onLoad(context);
    entry.state = LOADED;
} catch (Exception e) {
    entry.state = FAILED;
    entry.error = e;
    // 不 rethrow -- 隔离失败
}
```

#### 故障隔离的两个层次

| 层次 | 实现方式 | 保护范围 |
|------|---------|---------|
| 异常隔离（已实现） | try-catch 包裹 onLoad | 一个插件抛异常不传播到其他插件 |
| 类隔离（未实现） | 自定义 ClassLoader | 插件 A 和 B 的依赖版本冲突不影响彼此 |

本阶段只做了异常隔离。类隔离需要自定义 ClassLoader，留到后续阶段。

### 4.3 对称设计（Symmetric Design）

`onLoad` 和 `onUnload` 是对称的：前者注册，后者注销。插件开发者必须保证两者对称，否则卸载后工具残留或注销不存在的工具。

### 4.4 显式优于隐式（Explicit over Implicit）

`PluginContext` 显式传入 `ToolRegistry`，而不是通过全局变量或静态方法获取。这使插件依赖清晰可测，也便于未来扩展（加 `getMemoryStore()` 等方法时只改接口不改签名）。

### 4.5 工具即能力（Tool as Capability）

在 Agent 架构中，Tool 就是 Agent 的能力。有 `search_web` 工具 = 有搜索能力。因此"管理工具"= "管理能力"= "自我进化"。

---

## 五、核心类详解

### 5.1 类关系图

```
Plugin（接口）
  ├── descriptor() -> PluginDescriptor
  ├── onLoad(PluginContext)
  └── onUnload(PluginContext)

ToolPlugin（标记接口，extends Plugin）
  └── SearchToolPlugin（实现）
  └── CalculatorToolPlugin（实现）

PluginContext（接口）
  └── getToolRegistry() -> ToolRegistry

PluginDescriptor（record）
  ├── name: String
  ├── version: String
  └── description: String

PluginState（enum）
  └── DETECTED / LOADED / UNLOADED / FAILED

PluginRegistry（生命周期管理）
  ├── load(Plugin) -> 调 onLoad
  ├── unload(String) -> 调 onUnload
  ├── reload(String) -> unload + load
  ├── getState(String) -> Optional<PluginState>
  ├── getLoadedPlugins() -> List<Plugin>
  └── listPlugins() -> List<PluginInfo>

PluginManager（门面，SPI 发现 + 批量操作）
  ├── discover() -> ServiceLoader 扫描
  ├── loadAll() / unloadAll()
  ├── load(String) / unload(String) / reload(String)
  └── listPlugins()
```

### 5.2 职责划分

| 类 | 职责 | 不做什么 |
|----|------|---------|
| `Plugin` | 定义插件契约 | 不关心怎么发现、怎么加载 |
| `PluginDescriptor` | 存元数据 | 不含逻辑 |
| `PluginState` | 标记状态 | 不含转移逻辑（转移在 Registry 里） |
| `PluginContext` | 提供注册表访问 | 不管理生命周期 |
| `ToolPlugin` | SPI 类型标记 | 不增加方法 |
| `PluginRegistry` | 执行生命周期 | 不做 SPI 发现 |
| `PluginManager` | SPI 发现 + 批量操作 | 不直接调 onLoad/onUnload |

### 5.3 为什么 PluginRegistry 和 PluginManager 分两层

- **PluginRegistry**：管生命周期（load/unload/reload）。不管插件怎么来的。
- **PluginManager**：管 SPI 发现 + 批量操作。委托给 PluginRegistry。

换发现机制（如远程仓库下载插件）时，只改 PluginManager，不改 PluginRegistry。换生命周期策略（如加 DISABLED 状态）时，只改 PluginRegistry，不改 PluginManager。单一职责，互不影响。

---

## 六、插件生命周期

### 6.1 状态转移

```
                 load() 成功
  DETECTED ──────────────────> LOADED
     │                              │
     │ load() 失败             unload()
     v                              v
   FAILED <─── (终态)          UNLOADED
                                  │
                              load()
                                  v
                              LOADED
```

### 6.2 加载流程

```
PluginManager.load(plugin)
  |
  v
PluginRegistry.load(plugin)
  |
  ├── 检查是否已加载（重复加载抛异常）
  ├── 创建 PluginContext（包含 ToolRegistry 引用）
  ├── 创建 PluginEntry（state = DETECTED）
  |
  ├── try:
  │     plugin.onLoad(context)
  │       └── context.getToolRegistry().register(new SearchTool())
  │     state = LOADED
  |
  └── catch:
        state = FAILED
        error = 异常对象
        不抛异常（故障隔离）
```

### 6.3 卸载流程

```
PluginManager.unload(name)
  |
  v
PluginRegistry.unload(name)
  |
  ├── 查找 PluginEntry
  ├── 检查 state == LOADED（否则抛异常）
  |
  ├── try:
  │     plugin.onUnload(context)
  │       └── context.getToolRegistry().unregister("search_web")
  |
  └── catch:
        记录日志
        不管异常，继续

  state = UNLOADED（best-effort）
```

### 6.4 故障隔离演示

```
插件 A（正常）：
  onLoad() -> register(toolA) -> state = LOADED

插件 B（onLoad 抛异常）：
  onLoad() -> 抛 RuntimeException
  -> catch -> state = FAILED -> 不传播

结果：
  - 插件 A 的 toolA 在注册表中，可用
  - 插件 B 没有 toolB，不可用
  - 插件 B 的异常没有影响插件 A
  - 插件 B 的异常没有影响 PluginRegistry
  - 插件 B 的异常没有影响 Agent 运行
```

---

## 七、Agent 自进化

### 7.1 核心思想

把插件管理操作包装成 4 个 `Tool`，模型通过 ReAct 循环调用，实现自我管理能力。

### 7.2 四个工具

| 工具 | 参数 | 返回 | 模型什么时候调 |
|------|------|------|--------------|
| `plugin_inspect` | 无 | 当前所有插件状态 JSON | "我有什么能力？" |
| `plugin_list` | 无 | 所有已发现插件 + 可加载数量 | "还有什么可用？" |
| `plugin_load` | `{name}` | `{success, message/error}` | "我需要这个能力" |
| `plugin_unload` | `{name}` | `{success, message/error}` | "不需要了/有 bug" |

### 7.3 自进化完整流程

```
用户："搜索北京天气"
  |
  v
Step 1: 模型调 plugin_inspect
  -> 返回：search-tool [DETECTED], calculator-tool [DETECTED]
  -> 模型知道：我有两个插件可用但都没加载

Step 2: 模型调 plugin_list
  -> 返回：available_to_load: 2
  -> 模型确认：可以加载

Step 3: 模型调 plugin_load("search-tool")
  -> onLoad 执行 -> register(search_web)
  -> 返回：{success: true}
  -> search_web 工具现在在 ToolRegistry 中

Step 4: 模型调 search_web("Beijing weather")
  -> 工具执行
  -> 返回："Search results for 'Beijing weather': ..."

Step 5: 模型调 plugin_unload("search-tool")
  -> onUnload 执行 -> unregister("search_web")
  -> 返回：{success: true}
  -> search_web 工具从 ToolRegistry 移除

Step 6: 模型生成最终回答
  "北京天气晴，25°C。我加载了 search-tool 来搜索，用完卸载了。"
```

### 7.4 与 DeepSeek Harness 的对比

| 维度 | dsh tool-cordis | 本框架 |
|------|----------------|---------|
| inspect | 查看服务、插件、工具、API | 查看插件状态 |
| define | 模型写代码定义新插件 | 未实现（需要沙箱） |
| run | 启动已定义的插件 | `plugin_load` 加载已发现的插件 |
| stop | 停止运行中的插件 | `plugin_unload` 卸载插件 |
| 发现机制 | 动态定义（模型写代码） | SPI 预发现（classpath 扫描） |
| 安全隔离 | VM 沙箱执行 | 无（插件是预编译的 Java 类） |

关键差异：dsh 的模型可以**写代码定义新插件**，本框架的模型只能**从预发现的插件中选择加载**。要实现动态定义需要 Stage 4（沙箱）。

---

## 八、完整自进化的后续路线

本阶段实现了"管理已有插件"的最小闭环。完整自进化需要更多阶段配合：

```
Stage 3.5（本阶段）
  模型管理预发现的插件（load/unload/inspect/list）
     ↓
Stage 4（沙箱）
  安全执行模型写的代码
  -> 模型可以 define 新插件（写代码）
  -> 沙箱隔离执行环境
     ↓
Stage 8（Memory）
  Goal 持久化 -> 模型有跨轮次目标
  Skill Catalog -> 模型看到可用技能清单
     ↓
Stage 9（安全审计）
  插件加载/卸载需要权限审批
  模型的自我修改行为被审计记录
     ↓
Stage 14（RL 轨迹）
  自进化的决策过程被记录为轨迹
  -> 哪些自修改决策有效？哪些无效？
  -> 用于训练更好的自进化策略
```

完整自进化 = 管理（3.5）+ 创造（4）+ 目标驱动（8）+ 安全治理（9）+ 学习改进（14）。

---

## 九、文件清单

```
agent-plugin/src/main/java/com/seven/agent/plugin/
├── Plugin.java                   # 插件接口
├── PluginDescriptor.java         # 元数据 record
├── PluginState.java              # 状态枚举
├── PluginException.java         # 插件异常
├── PluginContext.java            # 插件上下文接口
├── ToolPlugin.java               # Tool 插件标记接口
├── PluginRegistry.java           # 生命周期管理 + 故障隔离
├── PluginManager.java            # SPI 发现 + 批量操作
└── tools/
    ├── PluginInspectTool.java    # 模型查看自己的能力
    ├── PluginListTool.java       # 模型列出可用插件
    ├── PluginLoadTool.java       # 模型加载插件
    └── PluginUnloadTool.java     # 模型卸载插件

examples/src/main/java/com/seven/agent/examples/
├── PluginExample.java            # 插件系统基础演示
├── PluginSelfModificationExample.java  # 自进化闭环演示
└── plugins/
    ├── SearchToolPlugin.java     # 示例插件
    └── CalculatorToolPlugin.java # 示例插件

agent-plugin/src/test/java/com/seven/agent/plugin/
├── PluginLifecycleTest.java     # 11 个生命周期测试
├── PluginManagerTest.java       # 8 个管理器测试
└── tools/
    └── PluginToolTest.java       # 10 个工具测试
```

---

## 十、面试表达

> 我的 Agent 框架用 Java SPI 实现了插件发现，插件通过 ServiceLoader 从 classpath 自动扫描。插件生命周期由 PluginRegistry 管理，采用可逆注册设计：onLoad 注册工具，onUnload 注销，两者对称。故障隔离通过 try-catch 实现：一个插件 onLoad 失败只标记 FAILED，不传播异常，其他插件照常加载。
>
> 在此之上，我把插件管理操作包装成 4 个模型可调用的 Tool（inspect/list/load/unload），让 Agent 在对话过程中自己决定加载和卸载什么能力。这是"Agent 自进化"的最小闭环：模型发现需要搜索能力 -> 加载 search-tool 插件 -> 使用 -> 用完卸载。
>
> 对标 DeepSeek Harness 的 tool-cordis，dsh 更进一步：模型可以写代码动态定义新插件（cordis_define），在 VM 沙箱中执行。我的框架目前只能从预发现的插件中选择，动态定义需要 Stage 4 沙箱支持。
