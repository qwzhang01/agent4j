# v1 边界与已知限制

版本：`0.1.4-SNAPSHOT`（开发中）。下面是**有意不做**或**尚未具备**的能力，避免按 `notes/` 或仓库体量误判为已交付。

## Maven Central

坐标是 `io.github.qwzhang01:seven-agent`（Central 最新 `0.1.3`）。发布走 `./mvnw -DskipTests deploy`，`examples` 不进 bundle。直接从 Central 引用即可；要跟踪仓库开发版，克隆仓库 → `./mvnw -B verify` 或 `mvn install`，再从本地仓库引用。

用法见 [getting-started.md](getting-started.md)。

## 运行时不依赖 Spring

`agent-core` / `agent-model` 等运行时模块不引入 Spring Framework。父 POM 是独立聚合工程，不是 `spring-boot-starter-parent`。

可选模块 `agent-spring-boot-starter` 是**唯一**依赖 Spring 的模块：读 `agent4j.*` 配置、创建 `ModelClient`、提供 `AgentFactory`。Core 仍然 Spring-free。没有 Actuator 集成。

## v1 明确不做

| 能力 | v1 实际有什么 |
|------|----------------|
| 插件多版本共存 / module layer 禁闭 | SPI 插件加载 / 卸载 / 重载；外部 JAR 加载（`PluginJarLoader`：SHA-256 checksum 门 + 每 jar 独立 URLClassLoader framework-first + SPI 注册域隔离 + `PluginManifest` 宿主侧权限声明，仅 tools 权限有可执行注册面）；SPI 插件进程内全 JVM 权限（框架只隔离注册面，不是代码执行沙箱） |
| Docker / WASM 沙箱 | `ClassLoaderSandbox` + `ProcessSandbox` |
| MCP Streamable-HTTP、完整 OAuth 客户端流、resources/prompts 能力 | MCP 客户端：stdio + HTTP/SSE transport（2024-11-05 SSE 方言）；server 信任三级（TRUSTED/RESTRICTED/UNTRUSTED，缺席即拒）+ allowlist + 宿主认证适配器（bearer/staticToken/refreshable）+ 入口 schema 结构校验；可连官方 filesystem server |
| A2A 卡片签名、外部第三方对端互操作 | A2A **HTTP** 双向：`HttpA2AClient`（`message/send` / `tasks/get` / 卡片发现 / `message/stream` SSE / webhook 推送 / input-required 续跑）+ `HttpA2AServer`（Agent 包成端点：入站净化 + 可选 bearer 门（恒时比对）+ HMAC-SHA256 签名 webhook 推送 + 5 分钟时间窗 + nonce 重放缓存）；任务存储走可插拔 `A2ATaskStore` 接口（默认内存实现，可换持久化 store），共享 store 跨 server 重启任务存活已测，lease 语义支撑跨实例认领；互操作验证 = 自家两端回环 + JDK HttpServer 模拟第三方言，无外部实现 |
| 真 Git | `agent-coding` 是工作区 + 补丁 + 命令白名单 + 有界修复环，不封装 Git |
| OpenTelemetry SDK 进核心 | `agent-otel-export` 薄壳模块（Stage 7.1）已提供 RunEvent/Metrics→OTel span 翻译（opentelemetry-api compile、SDK 仅 test scope 验证）；`agent-observability` 自管指标 / 预算 / 路由 / 评估 / 版本三元组不变，SDK 不进任何核心模块（D9）；Workflow/Memory/Sandbox/MCP/A2A span 与 Micrometer adapter 未做 |
| Mini VERL 训练 | `agent-trace-export` 导出轨迹 JSONL 与 DPO 偏好，训练环不在库内 |
| LLM-as-judge | 评估走规则 / 失败样本回归集，不用模型当裁判 |

这些不是「下一阶段漏做」，是 v1 非目标。

## Sandbox 安全边界（Stage 4）

v1 最高只实现到 PROCESS 层。不要把 `ProcessSandbox` 当作对抗恶意代码的安全沙箱：它防的是**意外与低强度攻击**，红队八条攻击全阻断，但边界是 guest 内 SecurityManager，理论上可被 JDK 内部机制绕过。

### 各层安全等级（从弱到强）

| Tier | 实现状态 | 防什么 | 防不了什么 |
|------|---------|--------|-----------|
| CLASSLOADER | ✅ `ClassLoaderSandbox` | 意外调用危险 API | 反射、`Unsafe`、JNI 逃逸 |
| PROCESS | ✅ `ProcessSandbox`（guest guard） | 上行全部 + guest 侧文件/网络/进程/输出截断 | guard 本身被 JDK 内部机制绕过；宿主 OS 用户身份未隔离 |
| DOCKER | ❌ 待 Linux CI | 上述全部 + namespace/cgroup 结构隔离 | 内核漏洞 |
| gVisor / MICROVMM | ❌ 未实现 | 上述全部 + 内核攻击面收敛 | 侧信道 |
| WASM | ❌ 未实现 | 上述全部 + 无系统调用 | WASM runtime bug |

### PROCESS 层强制了什么

- guest 内 `SandboxGuard`（SecurityManager 双阶段安装，guest 源码零感知）：workspace 外读写/删除全拒、`java.home` 只读、进程执行全拒、Socket/Net 权限全拒、exitVM/装第二个 SecurityManager 等按白名单放行
- 环境变量 allowlist：默认只继承 `PATH/TMPDIR/LANG/TZ/LC_ALL/LC_CTYPE`（不含 `HOME`），`ENV_INHERIT_ALL=["*"]` 显式 opt-in 恢复
- 输出每流默认 1MB 截断（`truncated by sandbox` 标记）；超时进程树整体击杀；临时目录 finally 清理
- `UNRESTRICTED` 模式不注入 guard，仅限 TRUSTED 调试，测试诚实记录 NOT-BLOCKED

### macOS 本地 vs Linux 生产

macOS 本地开发：guard 层 guest 强制即全部边界，进程以你的 OS 用户运行，无 UID/GID 隔离；Linux 生产：PROCESS 层同样只有 guard 边界，结构隔离（namespace/cgroup/UID）要等 DOCKER adapter，高风险生产路径在 adapter 落地前**不要**跑 ADVERSARIAL 代码。`TierLimits.forRisk(risk, multiTenant)` 查每档风险的实际限制表；`requiresApproval=true`（ADVERSARIAL）表示此路径需要人工审批或更强 sandbox。

## 数据保护边界（Stage 5）

敏感数据治理是**opt-in 而非默认**：治理能力（脱敏/审计/身份绑定）已全部就位，但默认装配路径不强制启用。宿主接入生产数据前必须显式接线。

### 已就位的治理件

- `SecretMasker`：regex 规则引擎（api-key/AWS key/JWT/bearer/邮箱/手机号/银行卡 7 条默认预设 + 租户自定义规则），占位符 `[REDACTED:<规则名>]`，`mask` 永不抛异常
- `RedactionPolicy`：四旗标（RAW/SUMMARY/HASH/MASKED）+ 四姿态工厂（rawOnly=旧行为 / maskedOnly=导出禁原文 / rawPlusMasked=Memory 账本姿态 / hashOnly=日志姿态）
- `MemoryGovernance`：RunContext 派生 scope 白名单（不可伪造）+ purpose 强制 + 读写审计（MemoryAccessAuditRecord）+ 删除传播可验证（DeletionPropagation）
- `TrajectoryCodec(masker)`：导出面脱敏参数化，默认 null=逐字节旧行为

### 诚实的边界

- 默认不脱敏：`SecretMasker`/`MemoryGovernance`/masked `TrajectoryCodec` 都是显式 opt-in；不接线就是旧行为（原文直通）。这是单租户本地运行的兼容选择，生产多租户必须接线
- 加密靠宿主：v1 无内置加密存储适配器，静态加密由宿主在 PG/磁盘层提供；框架保证的是离宿主边界的导出面无原文
- `metadata.last_error` 导出不脱敏：异常文本是结构性信息（类名+消息），脱敏会破坏排障，v1 记录为已知 gap
- retention 只有 hard delete + TTL（expireAt），无匿名化路径
- MemoryProvenance 无独立模型版本字段（经 actor 字符串承载）
- Trace/Trajectory 导出物的删除传播（Memory 之外）留 Stage 8

## 其他诚实边界

- **模型覆盖窄**：Mock、OpenAI-compatible、Anthropic。没有厂商全家桶 connector。
- **记忆持久化是裸 JDBC**：`PgMemoryStore` 用宿主自带 `DataSource`（无连接池、无 ORM、无框架托管迁移），schema 幂等自建；`InMemoryMemoryStore` 适合单机与测试。
- **Checkpoint 同理**：内存 / 文件 store，没有托管工作流后端。
- **测试基线**：全仓 22 模块全绿是回归契约（以 CI 为准）；`notes/` 里的阶段叙事、公众号文章**不是**用户合同。
- **学习项目**：通过造 Runtime 学架构。生产使用前先读本页和 [comparison.md](comparison.md)。

## 相关文档

- 上手：[getting-started.md](getting-started.md)
- 模块：[modules.md](modules.md)
- 和别的库：[comparison.md](comparison.md)
