# Stage 3.5 Agent 自进化：插件管理即工具

> 时间：2026-08-14
> 对应阶段：Stage 3 扩展 -- Agent 自我管理能力
> 灵感来源：DeepSeek Harness 的 tool-cordis（自我引用的工具集）

---

## 一、为什么要做这个

### 问题：谁决定 Agent 用什么能力？

Stage 3 实现了插件系统，但加载和卸载是**人在代码里调**的：

```java
manager.loadAll();              // 启动时全部加载
manager.unload("search-tool");  // 人手动卸载
```

这不够。如果 Agent 在对话中发现"我需要一个搜索能力"，它应该能**自己加载**，而不是等人来改代码。

### DeepSeek Harness 的答案

dsh 的 `tool-cordis` 包注册了 5 个模型可调用的工具：

| 工具 | 作用 |
|------|------|
| `cordis_inspect` | 模型查看自己的运行时状态 |
| `cordis_define` | 模型写代码定义新插件 |
| `cordis_run` | 模型启动已定义的插件 |
| `cordis_stop` | 模型停止运行中的插件 |
| `cordis_undefine` | 模型删除插件定义 |

核心思想：**插件管理本身就是一组工具，模型在对话中按需调用。**

这不是运维层面的版本管理，而是 Agent 架构层面的自我进化：**Agent 自己决定什么时候需要什么能力，自己加载，自己卸载。**

### dsh 的完整自进化体系

dsh 不只有 tool-cordis，还有 4 层配合：

```
1. Skill Catalog（技能目录）
   模型每轮循环前看到"可用技能清单"
   模型决定加载哪个技能（技能 = prompt 级别的指令集，不是代码）

2. Goal（目标持久化）
   模型有跨轮次的目标状态
   "我的目标是搭建 Web 服务" -> 后续每轮围绕这个目标

3. Plan Mode（规划模式）
   复杂任务先规划步骤再执行

4. tool-cordis（自我修改）
   模型 inspect 自己的运行时
   模型 define + run 新插件
   模型 stop + undefine 不需要的插件

5. Feedback（反馈记录）
   用户反馈被记录，后续可以改进
```

本阶段实现的是第 4 层的最小版本（不含 define，因为模型还不能写代码定义新插件，需要 Stage 4 沙箱支持）。

---

## 二、前置概念

### 2.1 自引用（Self-Referential）

"自引用"指系统包含操作自身的能力。在 dsh 中，tool-cordis 是"自我引用的工具集"：模型通过工具调用来修改工具集本身。

传统系统中，"系统能力"和"管理系统能力"是分开的：

```
传统：
  代码定义了哪些工具可用（编译时决定）
  运维决定加载哪些插件（部署时决定）
  模型只能使用已加载的工具（运行时受限）

自引用：
  模型可以查看自己有哪些工具（inspect）
  模型可以加载新工具（load）
  模型可以卸载不需要的工具（unload）
  模型可以定义全新的工具（define，需要沙箱）
```

### 2.2 工具即能力（Tool as Capability）

在 Agent 架构中，"工具"就是 Agent 的"能力"。Agent 有 `search_web` 工具 = Agent 有搜索能力。

因此，"管理工具"= "管理能力"= "自我进化"。

### 2.3 对比 Stage 3 的区别

| 维度 | Stage 3 | Stage 3.5 |
|------|---------|-----------|
| 谁调 PluginManager | 人在代码里调 | 模型在对话中调 |
| 什么时候加载 | 启动时或人手动 | 模型自己判断需要时 |
| 模型知道插件系统吗 | 不知道 | 知道（通过 inspect/list 工具） |
| 触发方式 | 代码调用 | 模型决策 + 工具调用 |

---

## 三、设计

### 3.1 四个工具

把 PluginManager 的操作包装成 4 个 `Tool` 实现，模型通过 ReAct 循环调用：

| 工具 | 参数 | 返回 | 模型什么时候调 |
|------|------|------|--------------|
| `plugin_inspect` | 无 | 当前所有插件状态 JSON | "我有什么能力？" |
| `plugin_list` | 无 | 所有已发现插件 + 可加载数量 | "还有什么可用？" |
| `plugin_load` | name | success/error | "我需要这个能力" |
| `plugin_unload` | name | success/error | "不需要了" |

### 3.2 为什么是 4 个而不是更多

对应 PluginManager 的 4 个核心操作：

```
inspect  -> PluginManager.listPlugins()
list     -> PluginManager.getDiscoveredPlugins() + listPlugins()
load     -> PluginManager.load(name)
unload   -> PluginManager.unload(name)
```

dsh 还有 `cordis_define`（模型写代码定义新插件），这需要沙箱来安全执行模型写的代码。Stage 3.5 不做，留到 Stage 4（沙箱）之后。

### 3.3 工具放在哪里

4 个工具放在 `agent-plugin` 模块的 `tools/` 包下，不是 `examples`。因为它们是框架能力，不是示例：

```
agent-plugin/src/main/java/com/seven/agent/plugin/tools/
├── PluginInspectTool.java
├── PluginListTool.java
├── PluginLoadTool.java
└── PluginUnloadTool.java
```

### 3.4 怎么组装

```java
// 1. 创建 ToolRegistry 和 PluginManager
InMemoryToolRegistry registry = new InMemoryToolRegistry();
PluginManager pluginManager = new PluginManager(registry);

// 2. SPI 发现插件（不加载，让模型自己决定）
pluginManager.discover();

// 3. 注册 4 个管理工具到 ToolRegistry
registry.register(new PluginInspectTool(pluginManager));
registry.register(new PluginListTool(pluginManager));
registry.register(new PluginLoadTool(pluginManager));
registry.register(new PluginUnloadTool(pluginManager));

// 4. 创建 Agent，工具列表中现在有 4 个管理工具
//    模型可以调用它们来管理自己的能力
Agent agent = new SimpleAgent(new AgentConfig(..., registry, ...));
```

---

## 四、工具实现

### 4.1 PluginInspectTool

```java
public class PluginInspectTool implements Tool {

    private final PluginManager pluginManager;

    @Override
    public String execute(JsonNode arguments) {
        List<PluginRegistry.PluginInfo> plugins = pluginManager.listPlugins();

        // 构造 JSON 返回
        // [{name, version, description, state, error?}, ...]
        return jsonResult(plugins);
    }
}
```

无参数。返回当前所有插件的名称、版本、描述、状态。模型调用后就知道"我现在有哪些能力"。

### 4.2 PluginListTool

```java
public class PluginListTool implements Tool {

    @Override
    public String execute(JsonNode arguments) {
        // 返回所有已发现插件 + 可加载数量
        // available_to_load = DETECTED + UNLOADED + FAILED 的插件数
        return jsonResult(plugins, availableCount);
    }
}
```

和 inspect 的区别：inspect 显示当前状态，list 额外标注"哪些可以加载"。

### 4.3 PluginLoadTool

```java
public class PluginLoadTool implements Tool {

    @Override
    public String execute(JsonNode arguments) {
        String name = arguments.path("name").asText("");

        try {
            pluginManager.load(name);
            return jsonResult(success: true);
        } catch (Exception e) {
            return jsonResult(success: false, error: e.getMessage());
        }
    }
}
```

参数：`{name: "search-tool"}`。调用后该插件的工具会出现在 ToolRegistry 中，Agent 下一轮循环就能看到。

### 4.4 PluginUnloadTool

```java
public class PluginUnloadTool implements Tool {

    @Override
    public String execute(JsonNode arguments) {
        String name = arguments.path("name").asText("");

        try {
            pluginManager.unload(name);
            return jsonResult(success: true);
        } catch (Exception e) {
            return jsonResult(success: false, error: e.getMessage());
        }
    }
}
```

参数：`{name: "search-tool"}`。调用后该插件的工具从 ToolRegistry 移除。

---

## 五、自进化完整流程

### 5.1 示例场景

用户问："搜索北京天气"。但 Agent 启动时没有加载搜索工具。

Mock 模型被脚本化为执行 6 步自进化：

```
Step 1: plugin_inspect     -> 查看自己有什么
  返回：[{name: "search-tool", state: DETECTED}, {name: "calculator-tool", state: DETECTED}]

Step 2: plugin_list         -> 查看什么可以加载
  返回：{total: 2, available_to_load: 2}

Step 3: plugin_load         -> 加载 search-tool
  参数：{name: "search-tool"}
  返回：{success: true, message: "Plugin 'search-tool' loaded"}
  -> search_web 工具出现在 ToolRegistry 中

Step 4: search_web          -> 使用刚加载的搜索工具
  参数：{query: "Beijing weather"}
  返回："Search results for 'Beijing weather': ..."

Step 5: plugin_unload       -> 用完卸载
  参数：{name: "search-tool"}
  返回：{success: true}
  -> search_web 工具从 ToolRegistry 移除

Step 6: 最终回答
  "北京天气晴，25°C。我加载了 search-tool 插件来搜索，用完后卸载了。"
```

### 5.2 运行时日志

```
[self-evolving-agent] Executing tool: plugin_inspect
[self-evolving-agent] Executing tool: plugin_list
[self-evolving-agent] Executing tool: plugin_load
Loading plugin: search-tool v1.0.0
Plugin loaded: search-tool v1.0.0
[self-evolving-agent] Executing tool: search_web
[self-evolving-agent] Executing tool: plugin_unload
Unloading plugin: search-tool
Plugin unloaded: search-tool
[self-evolving-agent] Completed in 6 steps

Final Plugin States:
  search-tool [UNLOADED]

Tools in Registry:
  plugin_list
  plugin_load
  plugin_unload
  plugin_inspect
```

最终状态：search-tool 被卸载，ToolRegistry 只剩 4 个管理工具。Agent 用完即清，不残留不需要的能力。

---

## 六、与 dsh tool-cordis 的对比

| 维度 | dsh tool-cordis | 本框架 Stage 3.5 |
|------|----------------|-----------------|
| inspect | `cordis_inspect` 查看服务、插件、工具、API | `plugin_inspect` 查看插件状态 |
| define | `cordis_define` 模型写代码定义新插件 | 未实现（需要沙箱） |
| run | `cordis_run` 启动已定义的插件 | `plugin_load` 加载已发现的插件 |
| stop | `cordis_stop` 停止运行中的插件 | `plugin_unload` 卸载插件 |
| undefine | `cordis_undefine` 删除插件定义 | 未实现 |
| 发现机制 | 动态定义（模型写代码） | SPI 预发现（classpath 扫描） |
| 安全隔离 | VM 沙箱执行 | 无（插件是预编译的 Java 类） |

**关键差异**：dsh 的模型可以**写代码定义新插件**，本框架的模型只能**从预发现的插件中选择加载**。这是"预定义能力集"vs"动态创造能力"的区别。

要实现 dsh 那样的动态定义，需要 Stage 4（沙箱）来安全执行模型写的代码。

---

## 七、设计决策记录

### 为什么不实现 define（模型写代码定义插件）？

`cordis_define` 让模型写 JavaScript/TypeScript 代码，通过 VM 沙箱执行。这需要：

1. 代码执行沙箱（防止模型写的代码搞坏宿主进程）
2. 动态编译/加载机制（运行时把模型写的代码变成可执行的类）
3. 安全限制（模型写的代码不能无限访问文件系统、网络等）

这些是 Stage 4（沙箱与隔离执行）的工作。Stage 3.5 只做"管理已存在的插件"，不做"创造新插件"。

### 为什么工具放在 agent-plugin 而不是 examples？

这 4 个工具是框架级能力，不是演示代码。任何使用 agent-plugin 模块的 Agent 都应该能用它们来自我管理。

### 为什么 inspect 和 list 是两个工具？

inspect 回答"我现在有什么"。list 回答"我还能获得什么"。关注点不同，分开更清晰。

实际实现中 list 比 inspect 多了 `available_to_load` 字段，但两个工具的返回内容有重叠。后续可以合并成一个带 `detail` 参数的工具。

---

## 八、验证对照

| 能力 | 状态 | 实现 |
|------|------|------|
| 模型查看自己的能力 | ✅ | `plugin_inspect` |
| 模型发现可用能力 | ✅ | `plugin_list` |
| 模型加载能力 | ✅ | `plugin_load` |
| 模型卸载能力 | ✅ | `plugin_unload` |
| 模型定义新能力 | ⬜ | 需要 Stage 4 沙箱 |
| 模型写代码创建插件 | ⬜ | 需要 Stage 4 沙箱 |

---

## 九、文件清单

```
agent-plugin/src/main/java/com/seven/agent/plugin/tools/
├── PluginInspectTool.java     # 查看插件状态
├── PluginListTool.java         # 列出可用插件
├── PluginLoadTool.java         # 加载插件
└── PluginUnloadTool.java       # 卸载插件

examples/src/main/java/com/seven/agent/examples/
└── PluginSelfModificationExample.java  # 自进化闭环演示

agent-plugin/src/test/java/com/seven/agent/plugin/tools/
└── PluginToolTest.java         # 10 个测试
```

---

## 十、后续路线

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

完整自进化 = Stage 3.5（管理）+ Stage 4（创造）+ Stage 8（目标驱动）+ Stage 9（安全治理）+ Stage 14（学习改进）。
