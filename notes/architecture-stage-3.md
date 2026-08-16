# Stage 3 架构图与执行流程

> 插件系统架构图 + 执行流程图
> 对应阶段：Stage 3 - 插件化与热插拔系统

---

## 1. 整体分层架构

```mermaid
graph TB
    subgraph Examples["examples 模块"]
        PluginExample["PluginExample<br/>演示程序"]
        SearchPlugin["SearchToolPlugin<br/>implements ToolPlugin"]
        CalcPlugin["CalculatorToolPlugin<br/>implements ToolPlugin"]
        SPI["META-INF/services/<br/>com.seven.agent.plugin.ToolPlugin"]
    end

    subgraph PluginModule["agent-plugin 模块（新增）"]
        PluginManager["PluginManager<br/>━━━━━━━━━━━━━━━━<br/>SPI 发现 + 批量操作<br/>discover / loadAll / unloadAll"]
        PluginRegistry["PluginRegistry<br/>━━━━━━━━━━━━━━━━<br/>生命周期管理 + 故障隔离<br/>load / unload / reload"]
        Plugin["Plugin &lt;i&gt;interface&lt;/i&gt;<br/>━━━━━━━━━━━━━━━━<br/>descriptor()<br/>onLoad(context)<br/>onUnload(context)"]
        ToolPlugin["ToolPlugin &lt;i&gt;marker&lt;/i&gt;<br/>━━━━━━━━━━━━━━━━<br/>extends Plugin<br/>SPI 发现标记"]
        PluginContext["PluginContext &lt;i&gt;interface&lt;/i&gt;<br/>━━━━━━━━━━━━━━━━<br/>getToolRegistry()"]
        PluginDescriptor["PluginDescriptor &lt;record&gt;<br/>━━━━━━━━━━━━━━━━<br/>name + version + description"]
        PluginState["PluginState &lt;enum&gt;<br/>━━━━━━━━━━━━━━━━<br/>DETECTED / LOADED<br/>UNLOADED / FAILED"]
        PluginException["PluginException<br/>带 pluginName"]
    end

    subgraph CoreModule["agent-core 模块（未修改）"]
        ToolRegistry["ToolRegistry &lt;i&gt;interface&lt;/i&gt;<br/>register / unregister"]
        InMemoryRegistry["InMemoryToolRegistry"]
        Tool["Tool &lt;i&gt;interface&lt;/i&gt;"]
        AgentLoop["ReActAgentLoop<br/>读 getToolSchemas()"]
    end

    %% 调用关系
    PluginExample --> PluginManager
    PluginManager --> PluginRegistry
    PluginRegistry --> PluginContext
    PluginRegistry --> PluginState
    PluginRegistry --> PluginException

    PluginManager -->|ServiceLoader.load| SPI
    SPI --> SearchPlugin
    SPI --> CalcPlugin

    SearchPlugin -.->|implements| ToolPlugin
    CalcPlugin -.->|implements| ToolPlugin
    ToolPlugin -.->|extends| Plugin

    SearchPlugin --> PluginDescriptor
    SearchPlugin -->|onLoad| PluginContext
    PluginContext -->|getToolRegistry| ToolRegistry

    InMemoryRegistry -.->|implements| ToolRegistry
    AgentLoop -->|每轮循环读| ToolRegistry

    classDef new fill:#e8f5e9,stroke:#388e3c,stroke-width:2px
    classDef existing fill:#e3f2fd,stroke:#1565c0,stroke-width:2px
    classDef example fill:#fff9c4,stroke:#f57f17,stroke-width:2px
    classDef data fill:#fff3e0,stroke:#ef6c00,stroke-width:2px

    class PluginManager,PluginRegistry,Plugin,ToolPlugin,PluginContext,PluginException new
    class PluginDescriptor,PluginState data
    class ToolRegistry,InMemoryRegistry,Tool,AgentLoop existing
    class PluginExample,SearchPlugin,CalcPlugin,SPI example
```

---

## 2. 模块依赖关系

```mermaid
graph LR
    subgraph Maven["Maven 多模块（Stage 3 后）"]
        Pom["pom.xml<br/>父 POM"]
        Core["agent-core<br/>零外部依赖"]
        Model["agent-model<br/>依赖 agent-core"]
        Plugin["agent-plugin<br/>依赖 agent-core<br/>（Stage 3 新增）"]
        Examples["examples<br/>依赖 agent-core<br/>+ agent-model<br/>+ agent-plugin"]
    end

    Pom --> Core
    Pom --> Model
    Pom --> Plugin
    Pom --> Examples
    Model --> Core
    Plugin --> Core
    Examples --> Core
    Examples --> Model
    Examples --> Plugin

    classDef parent fill:#fce4ec,stroke:#c62828,stroke-width:2px
    classDef core fill:#e3f2fd,stroke:#1565c0,stroke-width:2px
    classDef new fill:#e8f5e9,stroke:#388e3c,stroke-width:3px
    classDef module fill:#f1f8e9,stroke:#33691e,stroke-width:2px

    class Pom parent
    class Core core
    class Plugin new
    class Model,Examples module
```

---

## 3. SPI 发现机制

```mermaid
flowchart TD
    Start["PluginManager.discover()"]

    Load["ServiceLoader.load(ToolPlugin.class)<br/>━━━━━━━━━━━━━━━━<br/>扫描 classpath"]

    Scan["扫描所有 JAR 中的<br/>META-INF/services/<br/>com.seven.agent.plugin.ToolPlugin 文件"]

    ReadFile["读取文件内容：<br/>com.seven.agent.examples.plugins.SearchToolPlugin<br/>com.seven.agent.examples.plugins.CalculatorToolPlugin"]

    Instantiate["ServiceLoader 通过无参构造函数<br/>实例化每个类"]

    Store["存入 discovered Map<br/>key = plugin.descriptor().name()<br/>value = plugin 实例"]

    Done["返回发现数量"]

    Start --> Load --> Scan --> ReadFile --> Instantiate --> Store --> Done

    classDef start fill:#fff9c4,stroke:#f57f17,stroke-width:2px
    classDef process fill:#e3f2fd,stroke:#1565c0
    classDef io fill:#fff3e0,stroke:#ef6c00
    classDef done fill:#c8e6c9,stroke:#388e3c,stroke-width:2px

    class Start start
    class Load,Scan,Instantiate,Store process
    class ReadFile io
    class Done done
```

---

## 4. 插件生命周期状态机

```mermaid
stateDiagram-v2
    [*] --> DETECTED: ServiceLoader 发现

    DETECTED --> LOADED: load() 成功
    DETECTED --> FAILED: load() onLoad 抛异常

    LOADED --> UNLOADED: unload()
    LOADED --> UNLOADED: unloadAll()

    UNLOADED --> LOADED: load() 再次加载
    UNLOADED --> LOADED: reload()

    FAILED --> LOADED: reload() 修复后重试

    note right of DETECTED
        发现了但还没加载
        工具不在注册表
    end note

    note right of LOADED
        onLoad 成功
        工具已注册到 ToolRegistry
        Agent 可以调用这些工具
    end note

    note right of UNLOADED
        onUnload 完成
        工具已从 ToolRegistry 移除
        Agent 看不到这些工具了
    end note

    note right of FAILED
        onLoad 抛异常
        异常被 catch 不传播
        其他插件不受影响
        工具未注册
    end note
```

---

## 5. 加载流程（load）

```mermaid
flowchart TD
    Entry["PluginManager.loadAll() 或 load(name)"]

    CheckDiscovered{"discovered 为空?"}
    AutoDiscover["自动调 discover()<br/>SPI 扫描"]

    LoopStart["遍历 discovered 中的每个 Plugin"]

    CheckDup{"plugins.containsKey(name)?<br/>且 state == LOADED?"}
    DupError["抛 PluginException<br/>'Plugin already loaded'"]

    CreateContext["创建 PluginContext<br/>new SimplePluginContext(toolRegistry)"]

    CreateEntry["创建 PluginEntry<br/>state = DETECTED<br/>存入 plugins Map"]

    TryLoad["try {<br/>  plugin.onLoad(context)<br/>}"]

    OnLoad["plugin.onLoad(context) 执行<br/>━━━━━━━━━━━━━━━━<br/>context.getToolRegistry()<br/>  .register(new SearchTool())<br/>工具进入 ToolRegistry"]

    LoadSuccess["state = LOADED<br/>log: 'Plugin loaded'"]

    Catch["catch (Exception e) {<br/>  state = FAILED<br/>  error = e<br/>  log: 'Plugin failed to load'<br/>  不抛异常<br/>}"]

    LoadFailed["state = FAILED<br/>━━━━━━━━━━━━━━━━<br/>工具未注册<br/>异常被隔离<br/>不影响其他插件"]

    NextPlugin["继续加载下一个插件"]

    Entry --> CheckDiscovered
    CheckDiscovered -->|Yes| AutoDiscover --> LoopStart
    CheckDiscovered -->|No| LoopStart
    LoopStart --> CheckDup
    CheckDup -->|Yes| DupError
    CheckDup -->|No| CreateContext --> CreateEntry --> TryLoad
    TryLoad --> OnLoad
    OnLoad --> LoadSuccess
    TryLoad -->|抛异常| Catch
    Catch --> LoadFailed
    LoadSuccess --> NextPlugin
    LoadFailed --> NextPlugin
    NextPlugin -->|有更多| LoopStart
    NextPlugin -->|遍历完| Done["返回 loaded 计数"]

    classDef entry fill:#fff9c4,stroke:#f57f17,stroke-width:2px
    classDef decision fill:#e8eaf6,stroke:#3f51b5,stroke-width:2px
    classDef process fill:#e3f2fd,stroke:#1565c0
    classDef success fill:#c8e6c9,stroke:#388e3c,stroke-width:2px
    classDef error fill:#ffcdd2,stroke:#c62828,stroke-width:2px

    class Entry entry
    class CheckDiscovered,CheckDup decision
    class AutoDiscover,LoopStart,CreateContext,CreateEntry,TryLoad,OnLoad,NextPlugin process
    class LoadSuccess,Done success
    class DupError,Catch,LoadFailed error
```

---

## 6. 卸载流程（unload）

```mermaid
flowchart TD
    Entry["PluginManager.unload(name)"]

    FindEntry["PluginRegistry 查找 plugins.get(name)"]

    CheckExists{"entry == null?"}
    NotFound["抛 PluginException<br/>'Plugin not found'"]

    CheckLoaded{"state != LOADED?"}
    NotLoaded["抛 PluginException<br/>'Plugin is not loaded'"]

    TryUnload["try {<br/>  plugin.onUnload(context)<br/>}"]

    OnUnload["plugin.onUnload(context) 执行<br/>━━━━━━━━━━━━━━━━<br/>context.getToolRegistry()<br/>  .unregister('search_web')<br/>工具从 ToolRegistry 移除"]

    UnloadSuccess["log: 'Plugin unloaded'"]

    Catch["catch (Exception e) {<br/>  log: 'Plugin failed to unload'<br/>  不抛异常（best-effort）<br/>}"]

    SetUnloaded["state = UNLOADED<br/>━━━━━━━━━━━━━━━━<br/>工具已移除<br/>Agent 下轮循环看不到"]

    Entry --> FindEntry --> CheckExists
    CheckExists -->|Yes| NotFound
    CheckExists -->|No| CheckLoaded
    CheckLoaded -->|Yes| NotLoaded
    CheckLoaded -->|No| TryUnload
    TryUnload --> OnUnload
    OnUnload --> UnloadSuccess
    TryUnload -->|抛异常| Catch
    UnloadSuccess --> SetUnloaded
    Catch --> SetUnloaded

    classDef entry fill:#fff9c4,stroke:#f57f17,stroke-width:2px
    classDef decision fill:#e8eaf6,stroke:#3f51b5,stroke-width:2px
    classDef process fill:#e3f2fd,stroke:#1565c0
    classDef success fill:#c8e6c9,stroke:#388e3c,stroke-width:2px
    classDef error fill:#ffcdd2,stroke:#c62828,stroke-width:2px

    class Entry entry
    class CheckExists,CheckLoaded decision
    class FindEntry,TryUnload,OnUnload,UnloadSuccess process
    class SetUnloaded success
    class NotFound,NotLoaded,Catch error
```

---

## 7. 故障隔离流程

```mermaid
flowchart TD
    Start["loadAll() 加载 3 个插件"]

    PluginA["插件 A：SearchToolPlugin<br/>onLoad 正常"]
    PluginB["插件 B：FailingPlugin<br/>onLoad 抛 RuntimeException"]
    PluginC["插件 C：CalculatorToolPlugin<br/>onLoad 正常"]

    TryA["try { pluginA.onLoad() }"]
    OnLoadA["registry.register(SearchTool)<br/>成功"]
    SuccessA["state = LOADED<br/>工具在注册表中"]

    TryB["try { pluginB.onLoad() }"]
    OnLoadB["抛 RuntimeException<br/>'Intentional failure'"]
    CatchB["catch (Exception e) {<br/>  state = FAILED<br/>  error = e<br/>  不抛异常<br/>}"]
    FailedB["state = FAILED<br/>━━━━━━━━━━━━━━━━<br/>工具未注册<br/>异常被隔离"]

    TryC["try { pluginC.onLoad() }"]
    OnLoadC["registry.register(CalculatorTool)<br/>成功"]
    SuccessC["state = LOADED<br/>工具在注册表中"]

    Result["最终结果：<br/>━━━━━━━━━━━━━━━━<br/>ToolRegistry 中有 [search_web, calculate]<br/>插件 A 和 C 正常工作<br/>插件 B FAILED 但不影响 A 和 C<br/>Agent 正常运行，只是少了 B 的工具"]

    Start --> PluginA
    PluginA --> TryA --> OnLoadA --> SuccessA
    SuccessA --> PluginB
    PluginB --> TryB --> OnLoadB
    OnLoadB --> CatchB --> FailedB
    FailedB --> PluginC
    PluginC --> TryC --> OnLoadC --> SuccessC
    SuccessC --> Result

    classDef start fill:#fff9c4,stroke:#f57f17,stroke-width:2px
    classDef plugin fill:#e3f2fd,stroke:#1565c0,stroke-width:2px
    classDef try fill:#f3e5f5,stroke:#7b1fa2
    classDef success fill:#c8e6c9,stroke:#388e3c,stroke-width:2px
    classDef error fill:#ffcdd2,stroke:#c62828,stroke-width:2px
    classDef result fill:#fff3e0,stroke:#ef6c00,stroke-width:2px

    class Start start
    class PluginA,PluginB,PluginC plugin
    class TryA,OnLoadA,TryB,OnLoadB,TryC,OnLoadC try
    class SuccessA,SuccessC success
    class CatchB,FailedB error
    class Result result
```

---

## 8. 完整执行时序图

```mermaid
sequenceDiagram
    autonumber
    participant Main as PluginExample<br/>main()
    participant Mgr as PluginManager
    participant Reg as PluginRegistry
    participant SL as ServiceLoader
    participant Plugin as ToolPlugin<br/>(SearchToolPlugin)
    participant ToolReg as InMemoryToolRegistry
    participant Agent as ReActAgentLoop<br/>(示意)

    Note over Main: ━━ 1. 发现阶段 ━━

    Main->>Mgr: new PluginManager(toolRegistry)
    Main->>Mgr: discover()
    Mgr->>SL: ServiceLoader.load(ToolPlugin.class)

    SL->>SL: 扫描 META-INF/services/<br/>com.seven.agent.plugin.ToolPlugin
    SL->>SL: 读取文件内容
    SL->>SL: 实例化 SearchToolPlugin
    SL->>SL: 实例化 CalculatorToolPlugin

    SL-->>Mgr: 返回 2 个 Plugin 实例
    Mgr->>Mgr: 存入 discovered Map<br/>状态 = DETECTED
    Mgr-->>Main: 返回 discovered 数量 = 2

    Note over Main: ━━ 2. 批量加载阶段 ━━

    Main->>Mgr: loadAll()
    Mgr->>Reg: load(SearchToolPlugin)

    Reg->>Reg: 创建 PluginContext(toolRegistry)<br/>创建 PluginEntry, state=DETECTED

    Reg->>Plugin: onLoad(context)
    Plugin->>ToolReg: register(new SearchTool())
    Note over ToolReg: tools["search_web"] = SearchTool

    Plugin-->>Reg: onLoad 成功
    Reg->>Reg: state = LOADED
    Reg-->>Mgr: load 成功

    Mgr->>Reg: load(CalculatorToolPlugin)
    Reg->>Plugin: onLoad(context)
    Plugin->>ToolReg: register(new CalculatorTool())
    Note over ToolReg: tools["calculate"] = CalculatorTool

    Plugin-->>Reg: onLoad 成功
    Reg->>Reg: state = LOADED
    Reg-->>Mgr: load 成功
    Mgr-->>Main: loaded = 2

    Note over Main: ━━ 3. Agent 运行阶段 ━━

    Note over Agent: Agent 不感知插件系统<br/>只调 ToolRegistry.getToolSchemas()

    Agent->>ToolReg: getToolSchemas()
    ToolReg-->>Agent: [search_web schema, calculate schema]

    Note over Agent: 模型看到两个工具<br/>可以决定调用它们

    Note over Main: ━━ 4. 运行中卸载 ━━

    Main->>Mgr: unload("search-tool")
    Mgr->>Reg: unload("search-tool")

    Reg->>Reg: 查找 entry, state = LOADED ✓
    Reg->>Plugin: onUnload(context)
    Plugin->>ToolReg: unregister("search_web")
    Note over ToolReg: tools 中移除 search_web

    Plugin-->>Reg: onUnload 成功
    Reg->>Reg: state = UNLOADED
    Reg-->>Mgr: unload 成功
    Mgr-->>Main: done

    Note over Agent: Agent 下轮循环
    Agent->>ToolReg: getToolSchemas()
    ToolReg-->>Agent: [calculate schema only]

    Note over Agent: 模型只看到 calculate 工具<br/>search_web 已消失

    Note over Main: ━━ 5. 运行中重载 ━━

    Main->>Mgr: load("search-tool")
    Mgr->>Reg: load(SearchToolPlugin)
    Reg->>Plugin: onLoad(context)
    Plugin->>ToolReg: register(new SearchTool())
    Reg->>Reg: state = LOADED

    Agent->>ToolReg: getToolSchemas()
    ToolReg-->>Agent: [search_web schema, calculate schema]

    Note over Agent: search_web 工具回来了
```

---

## 9. 插件与 Agent 的关系

```mermaid
graph TB
    subgraph PluginSystem["插件系统（Stage 3 新增）"]
        PluginMgr["PluginManager"]
        PluginReg["PluginRegistry"]
        PluginA["SearchToolPlugin"]
        PluginB["CalculatorToolPlugin"]
    end

    subgraph Stage12["Agent 系统（Stage 1-2，未修改）"]
        ToolReg["ToolRegistry<br/>(InMemoryToolRegistry)"]
        AgentLoop["ReActAgentLoop"]
        ModelClient["ModelClient"]
        LLM["LLM Provider"]
    end

    subgraph Dynamic["动态变化"]
        ToolsNote["工具列表动态变化<br/>随插件加载/卸载改变"]
    end

    PluginMgr --> PluginReg
    PluginReg --> PluginA
    PluginReg --> PluginB

    PluginA -->|onLoad: register| ToolReg
    PluginA -->|onUnload: unregister| ToolReg
    PluginB -->|onLoad: register| ToolReg
    PluginB -->|onUnload: unregister| ToolReg

    ToolReg --> ToolsNote
    AgentLoop -->|每轮读 getToolSchemas| ToolReg
    AgentLoop -->|调 chat| ModelClient
    ModelClient -->|HTTP| LLM

    ToolsNote -.->|Agent 不感知<br/>插件系统存在| AgentLoop

    classDef plugin fill:#e8f5e9,stroke:#388e3c,stroke-width:2px
    classDef existing fill:#e3f2fd,stroke:#1565c0,stroke-width:2px
    classDef dynamic fill:#fff3e0,stroke:#ef6c00,stroke-width:2px

    class PluginMgr,PluginReg,PluginA,PluginB plugin
    class ToolReg,AgentLoop,ModelClient,LLM existing
    class ToolsNote dynamic
```

---

## 10. PluginManager API 调用关系

```mermaid
classDiagram
    class PluginManager {
        -PluginRegistry registry
        -Map~String, Plugin~ discovered
        +discover() int
        +loadAll() int
        +unloadAll() int
        +load(String name) void
        +unload(String name) void
        +reload(String name) void
        +listPlugins() List~PluginInfo~
        +getDiscoveredPlugins() List~Plugin~
        +getRegistry() PluginRegistry
    }

    class PluginRegistry {
        -ToolRegistry toolRegistry
        -Map~String, PluginEntry~ plugins
        +load(Plugin plugin) void
        +unload(String name) void
        +reload(String name) void
        +getState(String name) Optional~PluginState~
        +getLoadedPlugins() List~Plugin~
        +listPlugins() List~PluginInfo~
    }

    class Plugin {
        <<interface>>
        +descriptor() PluginDescriptor
        +onLoad(PluginContext) void
        +onUnload(PluginContext) void
    }

    class ToolPlugin {
        <<marker interface>>
    }

    class PluginContext {
        <<interface>>
        +getToolRegistry() ToolRegistry
    }

    class PluginDescriptor {
        +String name
        +String version
        +String description
    }

    class PluginState {
        <<enumeration>>
        DETECTED
        LOADED
        UNLOADED
        FAILED
    }

    class PluginEntry {
        -Plugin plugin
        -PluginContext context
        -PluginState state
        -Throwable error
    }

    class PluginInfo {
        +PluginDescriptor descriptor
        +PluginState state
        +String error
    }

    PluginManager --> PluginRegistry : 委托生命周期操作
    PluginRegistry --> PluginEntry : 管理
    PluginRegistry ..> PluginInfo : 创建快照
    PluginEntry --> Plugin
    PluginEntry --> PluginContext
    PluginEntry --> PluginState
    ToolPlugin --|> Plugin
    Plugin --> PluginDescriptor : 返回元数据
    PluginContext ..> ToolRegistry : 暴露访问
```

---

## 11. 与 Stage 1-2 的集成全景

```mermaid
graph TB
    subgraph Entry["入口"]
        Main["PluginExample.main()"]
    end

    subgraph Stage3["Stage 3: 插件系统"]
        Discover["1. discover()<br/>SPI 扫描"]
        LoadAll["2. loadAll()<br/>批量加载"]
        UnloadOne["3. unload(name)<br/>单个卸载"]
        Reload["4. load(name)<br/>重新加载"]
    end

    subgraph Stage12_Agent["Stage 1-2: Agent 层"]
        SimpleAgent["SimpleAgent"]
        AgentLoop["ReActAgentLoop"]
        AgentState["AgentState"]
    end

    subgraph Stage12_Tool["Stage 1-2: 工具层"]
        ToolRegistry["ToolRegistry<br/>(InMemoryToolRegistry)"]
        ToolExecutor["DefaultToolExecutor"]
    end

    subgraph Stage12_Model["Stage 1-2: 模型层"]
        ModelClient["ModelClient<br/>(Mock / OpenAI / Anthropic)"]
        Decorators["装饰器链<br/>Retry -> Timeout -> Fallback -> Structured"]
    end

    subgraph Plugins["插件实例"]
        SearchP["SearchToolPlugin"]
        CalcP["CalculatorToolPlugin"]
    end

    Main --> Discover
    Discover --> LoadAll
    LoadAll --> SearchP
    LoadAll --> CalcP

    SearchP -->|onLoad: register| ToolRegistry
    CalcP -->|onLoad: register| ToolRegistry
    SearchP -->|onUnload: unregister| ToolRegistry
    CalcP -->|onUnload: unregister| ToolRegistry

    UnloadOne -->|onUnload| ToolRegistry
    Reload -->|onLoad| ToolRegistry

    Main --> SimpleAgent
    SimpleAgent --> AgentLoop
    AgentLoop -->|每轮读 toolSchemas| ToolRegistry
    AgentLoop -->|调 chat| ModelClient
    AgentLoop -->|有 toolCalls| ToolExecutor
    ToolExecutor -->|getTool + execute| ToolRegistry

    ModelClient --> Decorators

    subgraph Unchanged["未修改的代码"]
        SimpleAgent2["SimpleAgent"]
        AgentLoop2["ReActAgentLoop"]
        ToolExecutor2["DefaultToolExecutor"]
        ModelClient2["ModelClient"]
        Decorators2["装饰器链"]
    end

    classDef stage3 fill:#e8f5e9,stroke:#388e3c,stroke-width:2px
    classDef stage12 fill:#e3f2fd,stroke:#1565c0,stroke-width:2px
    classDef plugin fill:#fff9c4,stroke:#f57f17,stroke-width:2px
    classDef unchanged fill:#fafafa,stroke:#999,stroke-dasharray: 5 5

    class Discover,LoadAll,UnloadOne,Reload stage3
    class ToolRegistry stage12
    class SearchP,CalcP plugin
    class SimpleAgent2,AgentLoop2,ToolExecutor2,ModelClient2,Decorators2 unchanged
```

---

## 12. 文件清单

```
agent-plugin/                                      # Stage 3 新增模块
├── pom.xml                                        # Maven 配置
├── src/main/java/com/seven/agent/plugin/
│   ├── Plugin.java                               # 插件接口
│   ├── PluginDescriptor.java                     # 元数据 record
│   ├── PluginState.java                          # 状态枚举
│   ├── PluginException.java                      # 插件异常
│   ├── PluginContext.java                        # 插件上下文接口
│   ├── ToolPlugin.java                           # Tool 插件标记接口
│   ├── PluginRegistry.java                       # 生命周期管理
│   └── PluginManager.java                        # SPI 发现 + 批量操作
└── src/test/java/com/seven/agent/plugin/
    ├── PluginLifecycleTest.java                  # 11 个生命周期测试
    └── PluginManagerTest.java                    # 8 个管理器测试

examples/                                          # Stage 3 新增示例
├── src/main/java/com/seven/agent/examples/
│   ├── PluginExample.java                        # 演示程序
│   └── plugins/
│       ├── SearchToolPlugin.java                 # 示例插件 1
│       └── CalculatorToolPlugin.java             # 示例插件 2
└── src/main/resources/META-INF/services/
    └── com.seven.agent.plugin.ToolPlugin         # SPI 声明文件

修改的文件
├── pom.xml                                        # 父 POM：加 agent-plugin 模块
└── examples/pom.xml                              # 加 agent-plugin 依赖
```
