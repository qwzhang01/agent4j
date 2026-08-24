# Coding Agent 的文件权限和命令权限设计

> 配套蓝图：[architecture-stage-17.md](architecture-stage-17.md) D1/D2 · 对应实现：`agent-coding/workspace/Workspace.java`、`WorkspacePolicy.java`、`exec/CommandWhitelist.java`、`CommandRunner.java`
> 上一篇：[stage-17-article-5-observability-approval.md](stage-17-article-5-observability-approval.md)
> 状态：✅ Stage 17 已完成

---

## 1. 我今天要解决什么问题

上一篇讲了「工具审批」（工具粒度）。这一篇把权限下沉到更细的两类「参数粒度」：

```text
文件权限：模型能读哪些文件、写哪些文件？（路径边界）
命令权限：模型能跑哪些命令？（命令边界）
```

这两个是 Coding Agent 安全设计的**最底层**——因为真正的攻击，不在「调了哪个工具」，而在「给了什么参数」：`read_file("../etc/passwd")`、`run_command("curl ...")`。

---

## 2. 为什么会有这个需求

Coding Agent 直接操作真实文件系统，攻击面有两个维度：

1. **文件维度**：路径逃逸（`../` 跳出工作区）、symlink 逃逸（链接指向根外）、读敏感文件（`.env`、`.git`、私钥）。
2. **命令维度**：命令注入（`; rm -rf /`）、外发数据（`curl`）、任意代码执行。

关键认知：**这两个维度的攻击，靠「工具审批」拦不住**——审批只问「能不能调这个工具」，问不了「这个参数安不安全」。所以必须在工具内部，再做一层参数粒度的防御。

---

## 3. 它解决了什么问题

- **文件权限**解决「读也是特权」：路径逃逸在词法层被拦死，敏感文件进 deny 列表，symlink 逃逸在真实路径层被拦。
- **命令权限**解决「命令面无限 vs 权限有限」：白名单 fail-closed，且**根本没有 shell**——注入语法无处生根。

---

## 4. 核心抽象和架构

### 4.1 文件权限：两层路径安全 + deny 列表

`Workspace` 用**两层**拦截路径逃逸：

```text
词法层（resolve）：normalize + startsWith 防 ../ 逃逸
  "../outside.txt"   -> 拒（逃逸）
  "/etc/passwd"      -> 拒（绝对路径）
  "a/../../.."       -> 拒（normalize 出根）
  " "（空白）         -> 拒

真实层（readFile）：toRealPath 防 symlink 逃逸
  根内 shortcut.txt 是 symlink，指向根外 secret -> 拒（"symbolic link escapes"）
```

为什么需要两层？因为**词法层拦不住 symlink**：`resolve("shortcut.txt")` 词法上在根内，但 `toRealPath()` 后它指向了根外。这是 M17.1 实现时补上的「超蓝图防御增强」。

`WorkspacePolicy` 的 deny 列表（默认六条）：

```text
.git, .git/**, .env*, **/.env*, *.key, **/*.key
```

`isDenied` 用**祖先传播**：deny 一个目录名，就等于 deny 它整棵子树——`.git` 命中，则 `.git/hooks/pre-commit` 免逐条匹配。

### 4.2 命令权限：无 shell 是第一道防御（D2）

`CommandRunner` 的核心设计，就一句话：

```java
// ProcessBuilder 直传 argv，不经 /bin/sh —— 注入语法无处生根
new ProcessBuilder(argv)   // 而不是 new ProcessBuilder("/bin/sh", "-c", commandLine)
```

这是「文件权限 vs 命令权限」里最漂亮的一笔：

```text
"mvn test; rm -rf /" 作为 argv[1]，只会被 maven 当成一个奇怪的 goal 名报错
—— 因为 ; | && $( ) ` > 在没有 shell 的世界里，只是普通字符串
```

然后才是白名单（`CommandWhitelist`）：argv 前缀匹配，fail-closed：

```text
规则 [mvn, test] -> 放行 "mvn test -q"，拒绝 "mvn clean"
规则 [mvn]      -> 放行一切 mvn 命令
不在表          -> 拒（curl / rm / sh 根本进不了白名单）
规则比 argv 长   -> 拒（授权的是完整命令，短 argv 是另一个命令）
```

### 4.3 纵深三道闸（D2）

```text
闸 1 治理链（Stage 9）：run_command 整体 REQUIRES_APPROVAL —— 人先批"要跑命令了"
闸 2 白名单（本阶段）：argv[0] 前缀匹配，fail-closed —— curl 进不了白名单
闸 3 执行器（本阶段）：cwd 锚定 workspace 根 + 超时 kill + 输出截断

任何单道闸失守都不是事故。
```

---

## 5. 一次完整数据流

三个拒绝演示（`CodingAgentExample` 实测输出）：

```text
F1 deny 读：
  read_file(".git/config")
  -> "path is denied by workspace policy: .git/config (reading is a privilege too)"

F3 白名单拒：
  run_command(["curl", "http://evil.example"])
  -> "[REJECTED] command not in whitelist: curl http://evil.example.
      Allowed command prefixes: ./check.sh | echo"

F7 注入免疫：
  run_command(["echo", "x; rm marker.txt"])
  -> stdout 原样打印 "x; rm marker.txt"，marker.txt 文件健在
```

第三个演示是「无 shell」的可执行证明：注入语法被 `echo` 原样打印出来——没有任何东西去解释它。

---

## 6. 最小代码或实验

四个最核心的安全测试，值得亲手跑：

```java
// 路径逃逸三态（WorkspaceTest.resolveEscapeRejected）
// ../ 链 / 绝对路径 / 编码空白 -> 全 IllegalArgumentException

// symlink 逃逸（WorkspaceTest.readFileSymlinkEscape）
// 根内 symlink 指向根外 secret -> 拒

// deny 默认集（WorkspacePolicyTest.defaultDenyGlobsMatch）
// .git/config / .env / config/server.key -> 全 denied

// 注入免疫（RunCommandToolTest.injectionIsInert）
// echo + "x; rm marker.txt" -> payload 原样打印 + marker 健在
```

---

## 7. 常见误区

1. **「用黑名单拦危险命令」** —— 军备竞赛。`rm --preserve-root`、base64 解码再执行、脚本套脚本，黑名单永远追不完。白名单 + 无 shell 让整类问题**默认不存在**：curl 进不了白名单，你不需要拦它。
2. **「路径白名单就够了，不用管 symlink」** —— symlink 是词法检查拦不住的真实逃逸向量：`shortcut.txt` 词法在根内，真实指向却在根外。必须 `toRealPath` 做第二层。
3. **「deny 列表逐文件匹配」** —— 祖先传播让 deny 目录名 = deny 整棵子树，否则 `.git` 下有 100 个文件你得写 100 条。
4. **「写和读用同一套权限」** —— 写比读严。写 symlink 路径会「写穿」symlink 到根外，所以 stage/apply 对 symlink 是直接拒绝，比 readFile 的「校验真实路径」更严。

---

## 8. 和相邻概念的区别

**两道闸、两个粒度**（本篇核心对比）：

```text
工具权限三档（Stage 9）  管"工具能不能调"    工具名粒度
  run_command 设 REQUIRES_APPROVAL

命令白名单（Stage 17）   管"参数里那条命令合不合法"  argv[0] 粒度
  mvn 可以、curl 不行
```

审批过后命令还要过白名单；白名单放行还要受超时/截断/锚定。**两道闸不同粒度，纵深防御。**

另一个对比：**读的权限 vs 写的权限**。读的副作用为零（AUTO 即可），写的副作用不可逆（必须走 Patch 暂存 + 人闸）。同一个文件系统，读和写的纪律完全不同——这就是「读也是特权，写更是特权」。

---

## 9. 我的设计判断

最重的一条：**无 shell 是命令权限的第一道防御，白名单只是第二道。**

很多人把「命令白名单」当成核心防御，但白名单解决的是「放行什么」，它对抗不了「放行之后参数里的注入」。真正的第一道防御是「根本没有 shell」——`ProcessBuilder` 直传 argv，注入语法在架构上就不成立。这个判断的价值在于：**凡是一类攻击手段，能通过架构让它根本不成立，就不要靠规则去拦截它**。拦截是军备竞赛，不存在是釜底抽薪。

其次是「读也是特权」这个反直觉的表述。很多人只防「写」，不防「读」——但 Coding Agent 能读到 `.env` 密钥、能读到 `.git` 里泄露的历史，本身就是泄露。所以 `Workspace` 的 deny 列表对读和写一视同仁。

---

## 10. 面试表达

> 「文件权限和命令权限，我各做了一层参数粒度的防御。文件层面是两层路径安全：词法层用 normalize+startsWith 拦 `../` 逃逸和绝对路径，真实层用 toRealPath 拦 symlink 逃逸——因为 symlink 是词法检查拦不住的。命令层面，第一道防御不是白名单，而是根本没有 shell：ProcessBuilder 直传 argv，`; rm -rf` 这种注入语法在没有 shell 的世界里只是普通字符串；第二道才是 argv 白名单，fail-closed，curl 根本进不了白名单。核心判断是：拦截是军备竞赛，不存在才是釜底抽薪。」

---

## 11. 下一篇连接什么

最后一篇，回到起点收个尾：**Coding Agent 与沙箱——阶段 4 的实战检验**。Stage 4 造的沙箱（ProcessSandbox），在 Coding Agent 这里到底能不能直接用？检验的结论是「哲学通用、隔离模型换壳」——两种威胁模型，两种正确答案。

→ [stage-17-article-7-sandbox-field-check.md](stage-17-article-7-sandbox-field-check.md)
