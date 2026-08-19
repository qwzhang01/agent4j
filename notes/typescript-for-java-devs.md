# TypeScript for Java 开发者：怎么学、怎么用

> 时间：2026-08-18
> 读者：已经用 Java 把 Agent Runtime 从 ModelClient 做到 Scheduler 的人
> 目的：用 Java 心智模型快速进入 TS，能读、能改、能写当下主流 Agent 代码
> 不是：从零学前端，也不是把 Java Agent Framework 用 TS 重写一遍

---

## 0. 先回答两个问题

### 0.1 为什么 Agent 开发是 TS 的天下？

不是 TS 比 Java 更适合写 Agent 循环。ReAct、Tool、Checkpoint、Scheduler 这些抽象你们已经用 Java 做完了，语言不是瓶颈。

是 **生态先在 JS/TS 长出来**：

| 层 | 主流仓库 | 语言 |
|----|----------|------|
| 模型 SDK | OpenAI / Anthropic / Vercel AI SDK | TS 一等公民 |
| Agent 框架 | Mastra / LangGraph.js / OpenAI Agents SDK / Claude Agent SDK | TS |
| 协议 | MCP TypeScript SDK（官方参考实现） | TS |
| 产品壳 | Next.js / Vite / CLI（tsx / bun） | TS |
| 工具定义 | Zod schema → JSON Schema 自动生成 | TS |

Java 能做同样的东西（你们已经证明了）。但读论文外的真实代码、接 MCP、抄一个新 harness、给模型喂 tool schema，第一手材料几乎全是 TS。

**学 TS 的目标**：能拆开别人的 Agent 框架，而不是换语言重写自己的。

### 0.2 TS 到底是什么？（对 Java 人最重要的一句话）

```
Java  = 语言 + 运行时 + 类型系统，绑死在一起（javac + JVM）
TS   = 只是类型系统。运行时是 JavaScript（Node / Bun / 浏览器）
```

编译产物是 `.js`。类型在编译期被擦掉，运行时看不到 `ChatRole`、看不到 `interface Tool`。

这一个事实解释了后面所有「TS 为什么还要 Zod」「为什么 `any` 会毁项目」「为什么 JSON 进来必须再验一遍」。

---

## 1. 心智模型：用 Java 对照，不要用「前端」对照

先忘掉 React / CSS。Agent 开发用的 TS 更像：

```
Java 21 + record + sealed + CompletableFuture
        ↓ 换成
TypeScript 5 + type/interface + discriminated union + async/await
运行在 Node（≈ JVM），包管理是 pnpm（≈ Maven）
```

| Java | TypeScript | 别踩的坑 |
|------|------------|----------|
| `javac` + JVM | `tsc`（或 bun/tsx 直接跑）+ Node/Bun | TS 不跑代码，JS 才跑 |
| `pom.xml` / Maven 模块 | `package.json` + workspace | 没有「provided / compile」那种完整依赖作用域 |
| `import io.github...` | `import { X } from "./x.js"` | ESM 路径要带扩展名（看 tsconfig） |
| `public class` | `export class` / 更多时候是 `export function` | TS 项目函数式更重，不必每个东西都是类 |
| `interface`（名义类型） | `interface` / `type`（**结构类型**） | 长得像就算实现了，不用 `implements` 也行 |
| `record ChatMessage(...)` | `type ChatMessage = { ... }` | 没有自动 `equals/hashCode`，就是个对象字面量 |
| `enum ChatRole` | 字符串字面量联合 ` "system" \| "user" ` | 少用 TS `enum`（有运行时残留，生态不待见） |
| `sealed interface StreamEvent` | 可辨识联合（discriminated union） | `switch (event.type)` + `never` 做穷尽检查 |
| `List<T>` / `Map<K,V>` | `T[]` / `Map<K,V>` / `Record<string, V>` | 数组是可变的；`Record` 不是 Java record |
| `Optional<T>` | `T \| undefined` / `T \| null` | 两套空值，项目里要统一（建议只用 `undefined`） |
| `CompletableFuture<T>` | `Promise<T>` + `async/await` | 没有受检异常，失败靠 `reject` / `throw` |
| `Stream<StreamEvent>` | `AsyncIterable<StreamEvent>` | `for await (const ev of stream)` |
| Jackson `JsonNode` | `unknown` + Zod parse | **不要用 `any` 接模型输出** |
| 受检异常 | 没有 | 错误是值，或裸 `throw` |
| SPI `ServiceLoader` | 动态 `import()` / 约定式文件扫描 | 热插拔没有 ClassLoader 那套，靠进程/模块 |

---

## 2. 用你们已经会的概念，对照着写一遍

下面每段都是「Java Agent Framework 里的东西 → TS 怎么写」。先建立肌肉记忆，再谈学习路径。

### 2.1 ChatRole / ChatMessage：从 record 到 type

Java：

```java
public enum ChatRole { SYSTEM, USER, ASSISTANT, TOOL }

public record ChatMessage(
    ChatRole role,
    String content,
    List<ToolCall> toolCalls,
    String toolCallId,
    String name
) {
    public static ChatMessage user(String content) { ... }
}
```

TS：

```typescript
type ChatRole = "system" | "user" | "assistant" | "tool";

interface ToolCall {
  id: string;
  name: string;
  arguments: Record<string, unknown>; // 运行时再 Zod 验
}

interface ChatMessage {
  role: ChatRole;
  content: string | null;
  toolCalls?: ToolCall[];
  toolCallId?: string;
  name?: string;
}

const user = (content: string): ChatMessage => ({
  role: "user",
  content,
});
```

对照要点：

- `"system" | "user"` 就是枚举，但是 **类型级的**，编译完就是普通字符串。正好对接 OpenAI / Anthropic 的 JSON。
- `toolCalls?` 的 `?` = 字段可缺省，≈ `@JsonInclude(NON_NULL)` + `null`。
- 工厂函数是普通函数，不必挂在类上。

### 2.2 StreamEvent：sealed interface → 可辨识联合

你们 Stage 1 用 sealed 是为了「只有这四种事件」。TS 用一个字面量字段做标签：

```typescript
type StreamEvent =
  | { type: "content_delta"; delta: string }
  | { type: "tool_call"; toolCall: ToolCall }
  | { type: "done"; finalResponse: ModelResponse }
  | { type: "error"; message: string };

function handle(event: StreamEvent): string {
  switch (event.type) {
    case "content_delta":
      return event.delta;
    case "tool_call":
      return event.toolCall.name;
    case "done":
      return event.finalResponse.content ?? "";
    case "error":
      return event.message;
    default: {
      const _exhaustive: never = event;
      return _exhaustive;
    }
  }
}
```

`never` 那一行 = Java 的穷尽性检查。漏了一个分支，`tsc` 直接红。这是 TS 里最像 Java 21 pattern matching 的东西，**Agent 流式事件、工作流节点结果、审批状态，全部用这套**。

### 2.3 ModelClient：接口 + 装饰器，写法几乎能直接搬

```typescript
interface ModelRequest {
  messages: ChatMessage[];
  tools?: ToolSchema[];
  stream?: boolean;
}

interface ModelResponse {
  content: string | null;
  toolCalls: ToolCall[];
}

interface ModelClient {
  chat(request: ModelRequest): Promise<ModelResponse>;
  stream(request: ModelRequest): AsyncIterable<StreamEvent>;
}

class RetryModelClient implements ModelClient {
  constructor(
    private readonly delegate: ModelClient,
    private readonly maxRetries = 3,
  ) {}

  async chat(request: ModelRequest): Promise<ModelResponse> {
    let lastError: unknown;
    for (let attempt = 0; attempt <= this.maxRetries; attempt++) {
      try {
        return await this.delegate.chat(request);
      } catch (e) {
        lastError = e;
        await sleep(500 * 2 ** attempt);
      }
    }
    throw lastError;
  }

  stream(request: ModelRequest): AsyncIterable<StreamEvent> {
    return this.delegate.stream(request);
  }
}
```

和 `RetryModelClient.java` 是同一个套娃。差别只有：

- 返回值必须是 `Promise`（模型调用天生异步，Java 你们很多地方还是同步 `chat()`）
- `private readonly delegate` 写在构造器参数上 = 字段声明 + 赋值，少写半页样板

装饰器组装也一样：

```typescript
const client: ModelClient = new StructuredOutputModelClient(
  new FallbackModelClient(
    new TimeoutModelClient(
      new RetryModelClient(new OpenAiModelClient(apiKey)),
    ),
    mock,
  ),
);
```

TS 里还有更常见的写法：高阶函数套娃（`wrapRetry(wrapTimeout(base))`）。本质没变，只是不一定用 class。

### 2.4 Tool：接口 + Zod，比手写 JSON Schema 舒服

Java 里你们手写 `getParametersSchema()` 字符串。TS 生态的标准做法是 **Zod 定义一次，同时当类型和 schema**：

```typescript
import { z } from "zod";

const WeatherArgs = z.object({
  location: z.string().describe("City name"),
});

type WeatherArgs = z.infer<typeof WeatherArgs>;

interface Tool<TArgs> {
  name: string;
  description: string;
  parameters: z.ZodType<TArgs>;
  execute(args: TArgs): Promise<string>;
}

const getWeather: Tool<WeatherArgs> = {
  name: "get_weather",
  description: "Get current weather for a city",
  parameters: WeatherArgs,
  async execute(args) {
    return `Weather in ${args.location}: 26C`;
  },
};

// 模型吐出来的 arguments 是 unknown，这里才真正变成 WeatherArgs
async function invokeTool(tool: Tool<WeatherArgs>, raw: unknown): Promise<string> {
  const args = tool.parameters.parse(raw); // 失败抛 ZodError
  return tool.execute(args);
}
```

这是 TS Agent 开发的核心手感：

```
编译期类型（tsc）管「我们自己的代码」
Zod          管「模型 / HTTP / 文件」这些运行时入口
```

Java 两边都靠编译器 + Jackson。TS 必须拆成两层，否则 `arguments` 写成 `any`，工具层全裸奔。

### 2.5 AgentLoop：async 函数，不是线程

你们的设计原则原文：「the loop is a function, not a thread」。TS 把这句话写得更自然：

```typescript
interface AgentState {
  messages: ChatMessage[];
  currentStep: number;
  maxSteps: number;
  status: "running" | "done" | "error";
  lastError?: string;
}

async function reactLoop(
  client: ModelClient,
  tools: Map<string, Tool<unknown>>,
  state: AgentState,
): Promise<AgentState> {
  while (state.currentStep < state.maxSteps && state.status === "running") {
    state.currentStep += 1;
    const response = await client.chat({
      messages: state.messages,
      tools: [...tools.values()].map(toSchema),
    });

    if (response.toolCalls.length === 0) {
      state.messages.push({ role: "assistant", content: response.content });
      state.status = "done";
      break;
    }

    state.messages.push({
      role: "assistant",
      content: response.content,
      toolCalls: response.toolCalls,
    });

    for (const call of response.toolCalls) {
      const tool = tools.get(call.name);
      const result = tool
        ? await tool.execute(call.arguments)
        : `Unknown tool: ${call.name}`;
      state.messages.push({
        role: "tool",
        content: result,
        toolCallId: call.id,
        name: call.name,
      });
    }
  }
  return state;
}
```

对照 `ReActAgentLoop.execute`：控制流一模一样。`await` 替代阻塞调用，事件循环替你们调度，不必 `CompletableFuture` 链。

流式版把 `client.chat` 换成：

```typescript
for await (const event of client.stream(request)) {
  if (event.type === "content_delta") process.stdout.write(event.delta);
}
```

≈ 消费 `Stream<StreamEvent>`，但是异步的。

### 2.6 结构类型：Java 人最容易低估的差异

```typescript
interface ModelClient {
  chat(request: ModelRequest): Promise<ModelResponse>;
}

const fake = {
  async chat(_request: ModelRequest): Promise<ModelResponse> {
    return { content: "ok", toolCalls: [] };
  },
};

function run(client: ModelClient) {
  return client.chat({ messages: [] });
}

run(fake); // 合法。没写 implements，但形状对了
```

Java 是名义类型：必须 `implements ModelClient`。TS 是结构类型：鸭子类型 + 编译器检查。

好处：测试里随手捏一个 mock，不用 Mockito。
坏处：两个无关接口字段碰巧同名，会被当成同一个东西。**跨包的公共契约，字段名要起得足够具体。**

---

## 3. 怎么学（给 Java 工程师的路径，不是给前端新人的）

不要从「TS 官方 Handbook 从头到尾」开始，也不要从 React 教程开始。按你们学 Agent 的方式：概念 → 对照代码 → 做一个能跑的薄切片。

### 阶段 A（1 天）：把运行时摸清楚

只做三件事，手敲，不要看视频：

1. 装 Node 22 LTS 或 Bun。`node -v` / `bun -v`。
2. `pnpm init` + `pnpm add -D typescript tsx @types/node` + `pnpm exec tsc --init`。
3. 写一个 `src/hello.ts`，`pnpm exec tsx src/hello.ts` 跑起来。

此时建立的正确图景：

```
.ts  --tsx/tsc-->  .js  --Node-->  跑起来
类型只在左边存在
```

`tsx` ≈ 开发期的「不先编译直接跑」，类似 `jshell` 但针对文件。生产再 `tsc` 出 JS。

`tsconfig.json` 先抄这份，别纠结每个字段：

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "NodeNext",
    "moduleResolution": "NodeNext",
    "strict": true,
    "noUncheckedIndexedAccess": true,
    "exactOptionalPropertyTypes": true,
    "skipLibCheck": true,
    "outDir": "dist",
    "rootDir": "src"
  },
  "include": ["src"]
}
```

`strict: true` 是底线。关掉它学 TS，等于关掉泛型学 Java。

### 阶段 B（2~3 天）：类型系统只学会这 10 个

按这个顺序，每个写 10 行对照 Java 的小例子就够：

| # | 概念 | Java 锚点 | 学完能干什么 |
|---|------|-----------|--------------|
| 1 | `type` / `interface` | `record` / `interface` | 描述 `ChatMessage` |
| 2 | 联合 + 字面量 | `enum` | `ChatRole` |
| 3 | 可辨识联合 + `never` | `sealed` | `StreamEvent` |
| 4 | 泛型 | `List<T>` / `Optional<T>` | `Tool<TArgs>` |
| 5 | `keyof` / 索引访问 | 有点像反射，但是编译期 | 从对象抽出字段类型 |
| 6 | 条件类型（看见就行） | 有点像 overload 决议 | 读库的类型声明时不慌 |
| 7 | `unknown` vs `any` | `Object` vs 关掉检查 | 接模型 JSON |
| 8 | 类型守卫 `is` | `instanceof` | 把 `unknown` 收窄 |
| 9 | `Readonly` / `as const` | `final` / 不可变 | 配置对象、事件名 |
| 10 | 模块 `export` / `import` | 包 + 类可见性 | 拆文件 |

**刻意不先学**：装饰器实验特性、namespace、`enum`、class 继承树、前端 JSX。Agent 后端用不上，学了会脏手感。

推荐读法（都短，当手册查）：

1. [TS Handbook - Everyday Types](https://www.typescriptlang.org/docs/handbook/2/everyday-types.html)
2. [Narrowing](https://www.typescriptlang.org/docs/handbook/2/narrowing.html)
3. [Object Types](https://www.typescriptlang.org/docs/handbook/2/objects.html)
4. [Narrowing 里的 Discriminated Unions](https://www.typescriptlang.org/docs/handbook/2/narrowing.html#discriminated-unions)

读到能独立写出第 2 节的 `StreamEvent` + `handle`，阶段 B 毕业。

### 阶段 C（2 天）：异步 + 模块，补上 Java 直觉的缺口

Java 人写 TS，死得最多的不是类型，是这两处：

**异步。** Node 是单线程事件循环。`await client.chat()` 不占线程等 I/O；`fs.readFileSync` 才会卡死。模型调用、HTTP、读文件，默认全部 `async`。

```typescript
// 并行调两个工具 ≈ allOf + join
const [a, b] = await Promise.all([toolA.execute(argsA), toolB.execute(argsB)]);

// 超时 ≈ orTimeout
const result = await Promise.race([
  client.chat(req),
  sleep(30_000).then(() => {
    throw new Error("TIMEOUT");
  }),
]);
```

**模块。** 一个文件 = 一个模块。`export` 的才看得到。没有 Java 那种包级私有 + 同包可见。目录只是路径，不是可见性。

`package.json` 里记住四个字段就够开工：

```json
{
  "name": "agent-core",
  "type": "module",
  "main": "./dist/index.js",
  "types": "./dist/index.d.ts",
  "scripts": {
    "dev": "tsx src/index.ts",
    "build": "tsc",
    "test": "vitest"
  }
}
```

`"type": "module"` = 用 ESM（`import`），不要再写 `require`。

### 阶段 D（3~5 天）：对着一个真 Agent 框架读，不要对着教程读

选 **一个** 读到能画出和你们 Stage 1-2 对应的图。推荐顺序按「和你们抽象的接近程度」：

| 优先级 | 项目 | 为什么适合现在读 |
|--------|------|------------------|
| 1 | [Vercel AI SDK](https://github.com/vercel/ai) | `generateText` + `tools` + `streamText`，是目前最薄的 ModelClient 层 |
| 2 | [Mastra](https://github.com/mastra-ai/mastra) | Agent + Workflow + Memory，和你们 Stage 2/5/8 最像 |
| 3 | [MCP TS SDK](https://github.com/modelcontextprotocol/typescript-sdk) | 官方协议参考，Stage 10 要接的就是它 |
| 4 | [LangGraph.js](https://github.com/langchain-ai/langgraphjs) | 图运行时，对照你们 `GraphRuntime` |

阅读作业（对着你们自己的代码看）：

```
他们的 ModelClient / generateText  ≈  我们的 ModelClient
他们的 tool({ name, parameters, execute })  ≈  我们的 Tool
他们的 agent.generate() / graph.invoke()  ≈  我们的 AgentLoop / GraphRuntime
他们的 checkpoint / memory  ≈  我们的 CheckpointStore / 还没做的 Stage 8
```

读的时候只问三个问题（和你们笔记里一贯的问法一样）：

1. 状态存在哪？messages？graph state？session？
2. 工具 schema 怎么到模型手里？手写还是 Zod？
3. 失败 / 超时 / 审批卡在哪一层？

### 阶段 E（1 个周末）：用 TS 复刻 Stage 1-2 的最小切片

不要复刻整个框架。只做：

- `ChatMessage` / `ModelClient` / `Tool` / `reactLoop`
- 一个 `MockModelClient`（脚本模式，你们已经熟）
- 一个 `get_current_time` 工具
- vitest 测「调工具 → 回灌 → 最终回答」

验收：测试全绿，并且你能指出每一行对应 `ReActAgentLoop` 的哪一段。

做完这件事，TS 对你就不再是「门新语言」，是「同一套 Agent 抽象的另一种写法」。

---

## 4. 怎么用（日常开发手册）

### 4.1 工具链（对标 Maven 世界）

| 你要做的事 | Java | TypeScript |
|------------|------|------------|
| 装依赖 | `mvn install` | `pnpm add zod` / `pnpm add -D vitest` |
| 跑单个文件 | `mvn -q exec:java` | `pnpm exec tsx src/foo.ts` |
| 编译 | `mvn compile` | `pnpm exec tsc --noEmit`（只检查）或 `tsc` 出 JS |
| 测试 | JUnit 5 | Vitest（API 很像 JUnit + AssertJ） |
| 格式 / lint | Checkstyle / Spotless | Biome 或 oxlint + prettier |
| HTTP | Java HttpClient | `fetch`（Node 18+ 内置） |
| JSON | Jackson | `JSON.parse` + Zod |
| 日志 | slf4j | `pino` 或先 `console`（CLI 够用） |
| 多模块 | Maven reactor | pnpm workspace |

初始化一个 Agent 向的小包：

```bash
pnpm init
pnpm add zod
pnpm add -D typescript tsx vitest @types/node
pnpm exec tsc --init
```

目录习惯（对标你们现在的模块）：

```
src/
  model/chat-message.ts
  client/model-client.ts
  tool/tool.ts
  agent/react-loop.ts
  index.ts          # 对外 export，≈ Java 的包门面
test/
  react-loop.test.ts
```

文件名 kebab-case，类型 PascalCase，函数 camelCase。一个文件可以 export 多个东西，不必 `ChatMessage.java` 那种一类一文件，但公共类型还是建议独立文件。

### 4.2 每天真正在用的 6 个模式

**1. 对象当数据，函数当行为。**  
`ChatMessage` 用 `type`，`user()` 用函数。能不用 class 就不用。class 留给「有生命周期的东西」：`RetryModelClient`、`FileCheckpointStore`、`PluginManager`。

**2. 边界用 Zod，内部用类型。**  
HTTP body、LLM tool arguments、checkpoint 文件，入口 `schema.parse(unknown)`。parse 之后的代码只认推断出来的类型，不再出现 `any`。

**3. 异步默认，同步是例外。**  
`chat` / `execute` / `loadCheckpoint` 全部 `async`。CPU 密集（解析大 JSON、本地 embedding）再考虑 `worker_threads`，别一上来就碰。

**4. 错误当值，或按层抛。**  
没有受检异常。实践上和你们 Stage 6 一样分层：

- 可恢复（限流、超时）→ 装饰器里重试，或返回 `{ ok: false, code: "RATE_LIMITED" }`
- 不可恢复（schema 不对、未知工具）→ `throw`，loop 写成 `ERROR`

结果联合比抛异常更香：

```typescript
type Result<T, E = string> =
  | { ok: true; value: T }
  | { ok: false; error: E };
```

**5. 不可变更新状态。**  
Java 里 `AgentState` 是可变对象，`state.addMessage(...)`。TS / 图运行时习惯返回新对象：

```typescript
const next: AgentState = {
  ...state,
  messages: [...state.messages, user(input)],
  currentStep: state.currentStep + 1,
};
```

这和 LangGraph / 你们以后若做 event sourcing 更贴。自己的 loop 里可变也可以，但不要混用两种风格。

**6. 配置用 `as const` + 满足类型，不要到处 `new XxxConfig()`。**

```typescript
const agentConfig = {
  name: "support",
  maxSteps: 8,
  systemPrompt: "You are a support agent",
} as const;
```

### 4.3 读别人仓库时的导航顺序

打开一个 TS Agent 项目，按这个顺序找，不会迷路：

```
1. package.json        → 依赖和 scripts（相当于看 pom + 常用 goal）
2. tsconfig.json       → 严不严、用不用 ESM
3. src/index.ts        → 对外 API
4. 搜 type Tool / generateText / Agent    → 核心抽象
5. 搜 z.object / jsonSchema               → 工具怎么声明
6. test/                                  → 他们眼里的正确行为
```

类型文件 `*.d.ts` 是「只有类型没有实现」的声明，≈ 只有接口的 JAR sources。`node_modules` 里的 `.d.ts` 是你读第三方 API 的第一手资料，比官网例子准。

### 4.4 测试：Vitest 对照 JUnit

```typescript
import { describe, it, expect } from "vitest";

describe("reactLoop", () => {
  it("executes a tool then finishes", async () => {
    const state = await reactLoop(mockClient, tools, initialState);
    expect(state.status).toBe("done");
    expect(state.messages.at(-1)?.role).toBe("assistant");
  });
});
```

`describe` / `it` ≈ `@Nested` + `@Test`。`expect` ≈ AssertJ。异步测试直接 `async () =>`，不用 `CompletableFuture.get()`。

---

## 5. Java 工程师写 TS 的 10 个坑

1. **把 TS 当 Java 用 class 堆继承。** 装饰器可以留 class，数据不要。
2. **用 `enum`。** 改成字符串联合。JSON 往返零摩擦。
3. **用 `any` 接模型输出。** 用 `unknown` + Zod。`any` 会传染，一传就等于没类型。
4. **忘记 `await`。** 返回的是 Promise，不是值。`strict` 帮得了类型，帮不了你漏 await（ESLint 的 `no-floating-promises` 要开）。
5. **拿 `==` 做比较。** 永远 `===`。`undefined == null` 为 true，是坑。
6. **`this` 在回调里丢绑定。** class 方法当回调传出去会丢 `this`。箭头函数或高阶函数更省心。
7. **数组 / 对象是引用。** `const msgs = state.messages; msgs.push(x)` 会改到原状态。要隔离就拷贝。
8. **默认 `null` 和 `undefined` 混用。** 选定一个。和 JSON 互转时，缺字段是 `undefined`，显式空常用 `null`。
9. **以为类型能挡住坏 JSON。** 类型擦除。磁盘上的 checkpoint、模型返回的 tool call，必须运行时校验。
10. **去学 React 才觉得自己在学 TS。** Agent 运行时是 Node 程序。UI 以后真要做再学。

---

## 6. 和你们框架的能力对照（读 TS 生态时用这张表导航）

| 你们的阶段 / 抽象 | TS 生态对等物 | 读的时候看什么 |
|-------------------|---------------|----------------|
| Stage 1 `ModelClient` | AI SDK `generateText` / `streamText` | provider 怎么抽象、装饰器还是 middleware |
| Stage 1 `StreamEvent` | AI SDK `UIMessageChunk` / 各家 SSE 事件 | 可辨识联合怎么拆 |
| Stage 2 `Tool` / `ToolRegistry` | `tool()` + Zod / MCP tools | schema 从哪来 |
| Stage 2 `ReActAgentLoop` | `generateText({ tools, maxSteps })` / `Agent.generate` | 循环在库内还是你自己写 |
| Stage 3 Plugin / SPI | 动态 `import()`、MCP server 进程 | 没有 ClassLoader，隔离靠进程 |
| Stage 4 Sandbox | 独立进程、Deno permission、容器 | TS 自己几乎没有安全的进程内沙箱 |
| Stage 5 `Workflow` / `GraphRuntime` | Mastra Workflow / LangGraph.js | 图定义不可变吗、状态是黑板还是 reducer |
| Stage 6 Checkpoint / `runId` | LangGraph checkpointer / Mastra snapshot | snapshot vs event log（你们笔记里比过） |
| Stage 7 Scheduler | 各家 `sleep` / cron / queue 工具 | 是不是真的 Agent 自驱动恢复 |
| Stage 10 MCP | 官方 `@modelcontextprotocol/sdk` | 这是 TS 必须会的一块 |

结论不变：**抽象你们已经有了。TS 是把这些抽象接到主流轮子上的语言。**

---

## 7. 两周计划（可执行）

| 天 | 产出 | 完成标准 |
|----|------|----------|
| D1 | Node + pnpm + 严格 tsconfig 跑通 | `tsx` 跑起来，`tsc --noEmit` 零报错 |
| D2-D3 | 手写 `ChatMessage` / `StreamEvent` / `ModelClient` | 和第 2 节对得上，带 `never` 穷尽检查 |
| D4 | Zod 定义 2 个 Tool，写出 `invokeTool` | 坏 JSON 被 parse 挡住，测试覆盖 |
| D5 | `reactLoop` + MockModelClient | 复现你们 Stage 2 的「先调工具再回答」 |
| D6 | 读 AI SDK 的 `generateText` 实现或文档 | 画出和 `ModelClient` 的对应图 |
| D7 | 读 Mastra Agent 或 LangGraph 一个最小 example | 说出状态存在哪、loop 在哪 |
| D8-D10 | 接一个真模型（OpenAI 兼容 / 方舟）流式输出 | `for await` 打出 delta |
| D11-D12 | 写一个最小 MCP server（一个 tool） | 能被 Claude / Cursor 列到 |
| D13-D14 | 对照一篇 TS 框架源码，写半页笔记 | 用第 6 节的表填空，不写教程、只写差异 |

两周后不要求能设计 TS 框架。要求：打开 Mastra / AI SDK / MCP 源码不发虚，能改、能提问题、能把好设计搬回 Java。

---

## 8. 一句话收束

Java 教你的是 Agent 的结构：消息、模型、工具、循环、图、检查点、调度。  
TS 教你的是这个结构在 2026 年的交付面：Zod schema、async 流、MCP、以及所有人正在改的那些仓库。

先把第 2 节对照着敲一遍，再按第 7 节两周走。类型系统遇到再查 Handbook，不要先把 Handbook 当书读完。
