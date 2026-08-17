# 装饰器模式讨论与演进路线

> 时间：2026-08-13
> 来源：Stage 1-2 学习过程中的设计讨论
> 核心问题：装饰器手动套娃不够优雅，主流方案是什么？后续阶段如何演进？

---

## 问题描述

当前 Stage 1-2 的装饰器组装方式：

```java
var retry    = new RetryModelClient(base, 3, Duration.ofMillis(500), 2.0);
var timeout  = new TimeoutModelClient(retry, Duration.ofSeconds(30));
var fallback = new FallbackModelClient(timeout, mock);
var client   = new StructuredOutputModelClient(fallback);
```

4 个中间变量，从内到外手动套，不优雅。

---

## 装饰器工作原理

### 核心思想

> 装饰器 = 套娃：所有装饰器和真实 ModelClient 实现同一个接口，每个装饰器内部包着另一个 ModelClient，在调内层前后加点自己的逻辑。

### 最简模型

```java
// 不用装饰器：直接调模型
ModelClient client = new OpenAiModelClient(apiKey);
client.chat(request);  // 直接发 HTTP

// 加一层装饰器：包着 real，加重试
ModelClient real = new OpenAiModelClient(apiKey);
ModelClient client = new RetryModelClient(real);  // Retry 包着 real

client.chat(request);
// -> Retry 内部调 real.chat()，失败了重试
```

### RetryModelClient 内部结构

```java
public class RetryModelClient implements ModelClient {  // 同一个接口
    private final ModelClient delegate;  // 包着另一个

    public RetryModelClient(ModelClient delegate) {
        this.delegate = delegate;
    }

    public ModelResponse chat(ModelRequest request) {
        for (int attempt = 0; attempt <= 3; attempt++) {
            try {
                return delegate.chat(request);  // 让内层干
            } catch (ModelException e) {
                Thread.sleep(500);  // 失败了等一会再试
            }
        }
        throw lastException;
    }
}
```

### 四层套娃调用过程

```
你调 client.chat(request)
  ↓
第 4 层 Fallback："先试主模型，挂了切备用"
  -> 调 layer3.chat()
    ↓
  第 3 层 Timeout："设 30 秒闹钟"
    -> 调 layer2.chat()
      ↓
    第 2 层 Retry："失败了我重试"
      -> 调 layer1.chat()
        ↓
      第 1 层 OpenAi 真正发 HTTP 请求
        -> LLM 返回
      ← 返回给第 2 层
    ← 返回给第 3 层
  ← 返回给第 4 层
← 返回给你
```

每一层只管自己那一件事，不知道外面有什么，也不知道里面有什么。

### 装饰器的价值

> 加模型不用改装饰器，加装饰器不用改模型，自由组合。

```
加新模型？只写 1 个类：
  new AnthropicModelClient(apiKey)

加新能力？只写 1 个装饰器：
  new CacheModelClient(delegate)

组合自由：
  Retry(Anthropic)               ← Claude + 重试
  Retry(Timeout(OpenAI))          ← OpenAI + 超时 + 重试
  Fallback(Anthropic, OpenAI)     ← Claude 挂了切 GPT
```

### 四个装饰器各自职责

| 装饰器                  | 什么时候出手    | 做什么                               |
|----------------------|-----------|-----------------------------------|
| **Retry**            | 内层抛异常时    | 看错误码：超时/限流/网络错误 -> 重试；认证错误 -> 不重试 |
| **Timeout**          | 内层调用耗时过长时 | 30 秒还没返回 -> 杀线程，抛 TIMEOUT         |
| **Fallback**         | 内层抛异常时    | 主模型挂了 -> 切备用模型                    |
| **StructuredOutput** | 内层返回结果后   | 检查 JSON 合法性，不合法追加修正提示重试           |

Retry 和 Fallback 都在"内层失败时"出手，但策略不同：

```
Retry:    "同一个模型再试几次"  ← 适合瞬时故障
Fallback: "换一个模型"          ← 适合持续性故障
```

### 真实异常场景走一遍

假设 `Fallback(Timeout(Retry(OpenAI)), Mock)`：

```
1. 调 chat(request)

2. Fallback -> 让主模型 Timeout 去干

3. Timeout -> 开异步线程，设 30s 超时，调 Retry

4. Retry 第 1 次尝试 -> OpenAI 发 HTTP -> 500 错误

5. Retry 检查错误码：MODEL_ERROR -> 可重试，等 500ms

6. Retry 第 2 次尝试 -> 又 500

7. Retry 第 3 次尝试 -> 又 500，重试耗尽 -> 抛异常

8. Timeout 线程收到异常（非超时）-> 直接抛给上层

9. Fallback 收到异常："主模型挂了" -> 切到 Mock
   Mock.chat() -> 返回 "Fallback response"

10. 你收到：ModelResponse("Fallback response")
```

---

## 主流替代方案

### 方案 1：Builder（链式 API）-- Java 最常见

```java
ModelClient client = ModelClientBuilder.wrap(base)
    .retry(3, Duration.ofMillis(500), 2.0)
    .timeout(Duration.ofSeconds(30))
    .fallback(mock)
    .structuredOutput()
    .build();
```

**代表**：OkHttp `OkHttpClient.Builder()`、Spring `RestTemplate.Builder`

**优点**：可读、一行搞定
**缺点**：本质没变，语法糖，还是手动组装

---

### 方案 2：函数式组合 -- 现代 Java

```java
// 装饰器变成函数：ModelClient -> ModelClient
UnaryOperator<ModelClient> retry = c -> new RetryModelClient(c, 3, Duration.ofMillis(500), 2.0);
UnaryOperator<ModelClient> timeout = c -> new TimeoutModelClient(c, Duration.ofSeconds(30));
UnaryOperator<ModelClient> fallback = c -> new FallbackModelClient(c, mock);

// 组合
ModelClient client = retry.andThen(timeout).andThen(fallback).apply(base);

// 可以存起来复用
UnaryOperator<ModelClient> prodPolicy = retry.andThen(timeout).andThen(fallback);
ModelClient clientA = prodPolicy.apply(new OpenAiModelClient(keyA));
ModelClient clientB = prodPolicy.apply(new AnthropicModelClient(keyB));
```

**代表**：Java Stream 的 `map().filter().sorted()`

**优点**：装饰器链可以存变量、传参数、复用
**缺点**：需要适应函数式思维

---

### 方案 3：中间件 / Pipeline -- Node.js / Go 风格

```java
Pipeline pipeline = Pipeline.start(base)
    .add(new RetryMiddleware(3, 500ms))
    .add(new TimeoutMiddleware(30s))
    .add(new FallbackMiddleware(mock))
    .add(new StructuredOutputMiddleware());
```

中间件长这样（与装饰器不同的"链式处理"模式）：

```java
class RetryMiddleware implements Middleware {
    ModelResponse handle(ModelRequest req, Next next) {
        for (int i = 0; i < 3; i++) {
            try { return next.chat(req); }    // 调下一个
            catch (ModelException e) { ... }  // 失败了重来
        }
    }
}
```

**代表**：Express.js、Koa、Spring Cloud Gateway、Netty ChannelPipeline

**优点**：比装饰器更灵活，可以运行时动态增删中间件
**缺点**：架构变了，从"嵌套"变成"线性"，改造成本大

---

### 方案 4：注解驱动（Spring AOP）

```java
@Retryable(maxAttempts = 3, backoff = @Backoff(delay = 500))
@Timeout(duration = "30s")
@Fallback(fallbackBean = "mockClient")
public ModelResponse chat(ModelRequest request) { ... }
```

**代表**：Spring Retry、resilience4j、Hystrix

**优点**：最优雅，零样板代码
**缺点**：强依赖 Spring 容器和 AOP 代理，黑盒太重

---

### 方案 5：声明式配置（YAML）-- Stage 13

```yaml
model:
  provider: openai
  api-key: ${OPENAI_API_KEY}
  decorators:
    - retry:
        max-attempts: 3
        backoff: 500ms
        multiplier: 2.0
    - timeout: 30s
    - fallback:
        - provider: mock
    - structured-output: true
```

**代表**：Spring Boot application.yml、Kubernetes manifests

**优点**：不写代码，改配置就生效，运维也能改
**缺点**：需要解析器 + 校验 + 热加载机制

---

## 演进路线

```
Stage 1-2（现在）
  手动套娃 ── 故意的，显式 > 隐式，理解原理
     ↓
Stage 3（插件化）
  Builder + 函数式组合 ── 装饰器变成插件，运行时动态加载
     ↓
Stage 6（Runtime / Checkpoint）
  Runtime 自动织入 ── 框架自动加 Trace/Checkpoint 装饰器，用户不用管
     ↓
Stage 13（声明式产品层）
  YAML 配置 ── 用户写 YAML，框架解析后自动组装装饰器链
     ↓
Stage 18（可观测性）
  全自动 ── 装饰器链由运行时根据策略自动决定，用户完全不感知
```

### 当前阶段不动的原因

1. **Stage 1-2 的目标是理解原理**，手动套娃让每层细节可见，Builder 会把细节藏起来
2. **Stage 3 做插件化时会自然引入 Builder**，因为插件化需要动态组装，那时加 Builder 顺水推舟
3. **Stage 13 做声明式时会用 YAML 替代 Builder**，Builder 又变成内部实现细节
