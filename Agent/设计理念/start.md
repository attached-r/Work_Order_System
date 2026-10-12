# 项目二 service-agent 技术设计方案（AI Agent 服务 · 起步篇）

> 本文给出「智能工单系统」**项目二：service-agent（AI Agent 服务）** 的完整技术设计方案。
> 写作目的：作为实现前的设计依据 + 作者本人复习 / 面试讲解材料，因此既写「怎么做」，也写**「为什么这么设计」与「我们没选什么」**。
> 文中出现的版本号均为**实测确认**（见 §三 的核对记录），项目一接口路径与类名与当前代码一致（`backend/WorkOrderSystem/src/main/java/com/rj/`），可对照源码阅读。
> **本文只做设计，不含实现代码。** 落地里程碑与验证方式见 §十一、§十二。
>
> 前置阅读：`backend/设计理念/权限解析.md`（板块一：认证鉴权 + RBAC）、`backend/设计理念/工单核心业务.md`（板块二：工单状态机）、`backend/阶段计划书/模块二~四.md`；以及项目一新增的 `/tool/**` 接口（`com.rj.controller.tool` 包）。

---

## 一、文档定位

### 1.1 范围

本文覆盖项目二的**第一个里程碑（M1）：骨架 + 只读问答**，即打通下面这一整条链路：

```
用户在浏览器/客户端用自然语言提问
        │  GET /api/agent/chat?sessionId=..&message=..   （带 Authorization 头）
        ▼
service-agent（本项目，端口 8082）
        │  ① ChatClient 把系统提示 + 用户消息 + 工具清单交给大模型
        │  ② 大模型判断：要不要调工具？调哪个？参数是什么？
        │  ③ 命中工具 → 在「请求线程」里取出 JWT → 调项目一 /tool/**（透传 Authorization）
        ▼
service-workorder（项目一，端口 10001）
        │  ④ /tool/** 复用现有 service + 数据范围，返回「裸 JSON」或「精简错误体」
        ▼
service-agent
        │  ⑤ 工具结果回喂大模型 → 大模型组织自然语言答案
        ▼
SSE 逐 token 流式返回给客户端
```

**M1 交付物**：

1. 一个**独立**的 Spring Boot 3.5.8 服务 `backend/service-agent/`（与 `backend/WorkOrderSystem/` 平级）。
2. **大模型封装层**（端口 / 适配器）：默认走通义千问，`provider=mock` 时无需 key 即可跑通全链路。
3. **两个只读工具**：`listWorkOrders`（列工单）、`queryWorkOrder`（查详情），内部走项目一 `/tool/**`。
4. **SSE 流式端点** `GET /api/agent/chat`。
5. **内存版会话记忆**（限 N 轮）。

### 1.2 边界（不在 M1 范围）

| 不做 | 放到 | 原因 |
|---|---|---|
| RAG 知识库（向量检索 / Milvus） | 里程碑 M3 | M1 先证明「LLM + 工具调用」这条主干；知识库是**叠加**在主干上的检索增强，先不引入向量库这一整套中间件 |
| 工具调用审计表 `agent_tool_call_log` | M2 | 审计是「可观测 / 可追责」，与主干正交，独立成表独立做 |
| 两层权限过滤（用 `/tool/user/info` 的 perms 收敛工具可见性） | M2 | M1 鉴权只需「透传 JWT」，项目一的 `LoginInterceptor` 已保证「调用者身份合法 + 数据范围正确」，够用 |
| 写操作（改状态、派单…） | M4 | 项目一侧写接口目前**只是可运行 demo**（`ToolWorkOrderService.changeStatus` 留了 TODO 扩展点），写链路要等状态机分发落地后再接 |
| Sentinel 降级 / 熔断 | M5 | M1 先保证「跑通」，不先做「扛住」 |
| Redis 会话记忆 | M5 | M1 用 Spring AI 内存版 `MessageWindowChatMemory` 即可，接口一致，后面换存储不动业务 |
| 多模型「同一进程并存」 | 视需要 | 见 §五：一个进程只引一个 provider starter，多提供方靠**适配器 + 配置**切换，而非同时引多个 starter |

### 1.3 一句话总览

> **把大模型关进一个 `LlmClient` 端口后面（默认通义千问、可换、可 mock），把项目一的 `/tool/**` 读接口包成 Spring AI 的 `@Tool`，用「透传用户 JWT」复用项目一现成的鉴权与数据范围，最后用 SSE 把模型回答逐字吐给客户端。**

一条纪律贯穿全文：**service-agent 不自己发明权限、不自己碰数据库。** 它是「自然语言 → 工具调用」的编排层，**所有业务事实与数据范围一律以项目一为准**——agent 能查到的、能做的，严格等于「这个用户带着自己的 token 直接调项目一」所能查到的、能做的。这条纪律同时决定了 §六 的鉴权方案。

---

## 二、为什么要独立成一个服务（而不是把 Agent 塞进项目一）

这是开工前最重要的一个决策，也是唯一一个「被文档定义、但实现代价很高」的决策。

### 2.1 硬约束：两个项目的 Spring Boot 大版本对不上

| | 项目一 service-workorder | 项目二 service-agent |
|---|---|---|
| Spring Boot | **4.1.1**（已锁死，见其 `pom.xml` 的 parent） | **3.5.8** |
| 原因 | 项目一已按 Boot 4 全家桶落地（`spring-boot-starter-webmvc`、`mybatis-plus-spring-boot4-starter`、knife4j 的 boot4 分支…） | Spring AI Alibaba `1.1.2.0` 的 `spring-ai-alibaba-starter-dashscope` **直接依赖 `spring-boot-starter:3.5.8`**（实测，见 §三） |

**这两个大版本无法共存在同一个 JVM 工程里**：Boot 4 对自动配置、`spring.factories` → `AutoConfiguration.imports`、Jackson 3 等都做了改动，而 SAA 1.1.2.0 是按 Boot 3.5 编译的。硬塞到一起，最典型的后果就是启动期 `ClassNotFoundException` / 自动配置不生效。

### 2.2 决策：拆成独立服务，各用各的版本

> **与其强行让 Agent 迁就项目一的 Boot 4，不如让 Agent 用它生态里最顺的 Boot 3.5 独立跑，通过 HTTP（`/tool/**`）与项目一通信。**

这样做还有三个顺带的好处：

1. **故障隔离**：Agent（会调外部大模型 API，可能慢、可能超时、可能被限流）出问题，**不会拖垮**稳如老狗的项目一。
2. **独立演进**：Agent 侧依赖（SAA、向量库、未来的 DeepSeek starter）升级频繁，独立仓库 / 独立 `pom.xml` 才不会牵连项目一。
3. **边界清晰**：跨进程调用**逼着**我们把接口契约写清楚（§六），而不是靠「反正同一个进程、直接注入 service」的隐性耦合。

### 2.3 我们没选什么

| 备选方案 | 为什么不选 |
|---|---|
| 把 Agent 代码并进项目一（同一个 Spring 工程） | Boot 4.1.1 与 SAA 所需的 Boot 3.5 **不兼容**，是本决策的根因 |
| 把项目一**降级**到 Boot 3.5 以容纳 Agent | 项目一已按 Boot 4 落地（含 MyBatis-Plus 的 boot4 starter、knife4j boot4 分支），降级等于把整个已完成的项目推倒重来，**收益成本完全不成比例** |
| 两个服务共用一个 parent / 多模块（Maven multi-module） | 同上，共 parent 就要求同一 Boot 版本；且两服务发布节奏、依赖树完全不同，multi-module 只会制造耦合 |

---

## 三、技术栈与版本（含实测核对记录）

### 3.1 版本矩阵

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | **17** | 与项目一一致；SAA 1.1.x 要求 17+ |
| Spring Boot | **3.5.8** | **由 SAA starter 反推**：`spring-ai-alibaba-starter-dashscope:1.1.2.0` 的 POM 直接依赖 `spring-boot-starter:3.5.8` |
| Spring AI Alibaba (SAA) | **1.1.2.0** | 四位版本号：前三段对齐 Spring AI（1.1.2），第四段是社区补丁 |
| Spring AI | **1.1.2**（由 SAA 传递） | `@Tool` / `@ToolParam` / `ChatClient` / `ToolContext` 均出自这里 |
| 模型 starter | `com.alibaba.cloud.ai:spring-ai-alibaba-starter-dashscope` | 通义千问（Qwen），支持 function/tool calling |
| 默认模型 | `qwen-plus` | 支持工具调用；`qwen-max` 亦可 |
| Web | `spring-boot-starter-web`（Spring MVC） | 返回 `Flux<ServerSentEvent<String>>` 做 SSE；用 `RestClient` 调项目一 |
| HTTP 客户端 | **Spring 6 `RestClient`**（`spring-web` 自带） | **不引 Spring Cloud / OpenFeign**，理由见 §3.3 |

### 3.2 Maven 坐标（实测确认可用）

```xml
<!-- parent -->
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>3.5.8</version>
</parent>

<!-- 管理 dashscope starter 版本（1.1.2.0 里已声明 spring-ai-alibaba-starter-dashscope:1.1.2.0） -->
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.alibaba.cloud.ai</groupId>
      <artifactId>spring-ai-alibaba-extensions-bom</artifactId>
      <version>1.1.2.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<!-- 依赖 -->
<dependency>
  <groupId>com.alibaba.cloud.ai</groupId>
  <artifactId>spring-ai-alibaba-starter-dashscope</artifactId>
</dependency>
```

**核对记录（本地 `.m2` 与联网实测，2026-10-12）**：
- `spring-ai-alibaba-extensions-bom:1.1.2.0` 的 `dependencyManagement` 里**明确管理了** `spring-ai-alibaba-starter-dashscope:1.1.2.0` 与 `...-autoconfigure-dashscope:1.1.2.0`，故引此 BOM 后 starter **无需再写 `<version>`**。
- 注意区分两个 BOM：`spring-ai-alibaba-bom`（管理 agent-framework / graph-core / studio 等）**不含** dashscope starter；**管理 dashscope 的是 `spring-ai-alibaba-extensions-bom`**。选错 BOM 会导致 starter 版本无法解析。
- `spring-boot-starter-parent:3.5.8` 本地未缓存，但 Maven Central 可达（实测 `HTTP 200`），首次构建会联网下载，属正常。

### 3.3 为什么用 `RestClient`，而**不引** Spring Cloud / OpenFeign

计划初稿把 OpenFeign 列为第一选择、`RestClient` 为退路。**实现前核对后，改为直接用 Spring 6 的 `RestClient`**，理由是它更贴合本项目的真实需求：

| 维度 | OpenFeign（+ Spring Cloud） | `RestClient`（Spring 6 原生） |
|---|---|---|
| 依赖体积 | 引入整条 Spring Cloud 依赖树，且要选与 Boot 3.5 对齐的 release train（**多一个版本对齐风险点**） | `spring-web` 自带，**零新增依赖** |
| 本项目是否需要它 | Feign 的核心价值是**服务发现 + 负载均衡 + 声明式接口**——本项目只调**一个固定 `base-url`** 的项目一，三者都用不上 | 直接 `RestClient.create(baseUrl)`，一个 `@Component` 搞定 |
| 声明式风格 | 通过 `@FeignClient` 接口 | 也可用 `@HttpExchange` + `HttpServiceProxyFactory` 拿到同样的声明式写法 |

> **一句话**：Feign 是「微服务全家桶」的一环，本项目是「两个服务直连」，用它是杀鸡用牛刀，还平白多一个版本对齐风险。`RestClient` 能满足全部需求，且将来如需换回 Feign，**改动只在 `client` 包一层**，`@Tool` 与控制器无感。

---

## 四、总体架构与目录结构

### 4.1 分层图

```
        ┌─────────────────────────────────────────────────────┐
        │  AgentChatController  (GET /api/agent/chat, SSE)     │  ← 唯一对外入口
        └───────────────┬─────────────────────────────────────┘
                        │ 只依赖端口,不碰 Spring AI / HTTP 细节
                        ▼
        ┌─────────────────────────────────────────────────────┐
        │  LlmClient  (端口 interface)                         │
        │    Flux<String> stream(sessionId, userMessage, token)│
        └───────┬───────────────────────────────┬─────────────┘
                │ provider=dashscope            │ provider=mock
                ▼                               ▼
        ┌────────────────────┐         ┌────────────────────┐
        │ SpringAiLlmClient  │         │ MockLlmClient      │
        │  ChatClient        │         │  不调模型,回显       │
        │  + .defaultTools() │         └────────────────────┘
        └─────────┬──────────┘
                  │ 模型决定调工具时,执行:
                  ▼
        ┌─────────────────────────────────────────────────────┐
        │  WorkOrderTools  (@Tool 方法)                        │
        │    listWorkOrders(...) / queryWorkOrder(...)         │
        │    从 ToolContext 取 token,作为显式参数往下传          │
        └───────────────┬─────────────────────────────────────┘
                        ▼
        ┌─────────────────────────────────────────────────────┐
        │  WorkOrderToolClient  (RestClient, 透传 Authorization)│
        └───────────────┬─────────────────────────────────────┘
                        ▼
              项目一 service-workorder :10001   /tool/**
```

### 4.2 目录结构

```
backend/service-agent/
  pom.xml
  src/main/java/com/rj/agent/
    AgentApplication.java
    config/
      LlmProperties.java          # 绑定 agent.llm.*(provider/model/base-url/api-key…)
      WorkOrderProperties.java     # 绑定 agent.workorder.*(base-url)
    llm/
      LlmClient.java              # 【端口】Flux<String> stream(sessionId, userMessage, token)
      SpringAiLlmClient.java      # 默认适配器:ChatClient + 工具;provider=dashscope
      MockLlmClient.java          # provider=mock:无需 key,回显式答复,用于先把链路跑通
    client/
      WorkOrderToolClient.java    # RestClient -> 项目一 /tool/workorder/**（透传 Authorization）
    tool/
      WorkOrderTools.java         # @Tool 方法,内部调 client（读:list / detail）
    controller/
      AgentChatController.java    # SSE 端点
  src/main/resources/
    application.yaml
```

> 说明：M1 **不建 `db/`、不建 `mapper/`**——agent 服务**没有自己的数据库**，一切业务数据都来自项目一。这是 §1.3 那条纪律在工程结构上的体现。

---

## 五、大模型封装 —— 端口 / 适配器（回应「不能局限在某一个模型」）

### 5.1 设计目标

需求原话是：**「大模型接口不能局限，用户会有自己想用的模型，或者有一个默认模型，最好是封装一下。」**

这条需求翻译成设计语言就是三条：

1. **有一个默认模型**（开箱即用，不用配置）。
2. **可替换提供方**（通义千问 → DeepSeek / OpenAI 兼容 / 用户自带 endpoint）。
3. **可被上层无感切换**（控制器与工具代码不因换模型而改动）。

### 5.2 方案：端口 + 适配器

```
                   ┌──────────────────────────┐
   控制器 ───────▶ │  LlmClient（端口/接口）    │
                   └────────────┬─────────────┘
                                │ agent.llm.provider 决定注入哪个实现
        ┌───────────────────────┼────────────────────────┐
        ▼                       ▼                        ▼
 SpringAiLlmClient        MockLlmClient          （未来）DeepSeekLlmClient
 provider=dashscope       provider=mock          provider=deepseek
 真调通义千问              不调模型,回显           换一个适配器即可
```

**端口定义（关键：只暴露业务语义，不泄漏任何 Spring AI 类型）**：

```java
public interface LlmClient {
    // sessionId 用于会话记忆;token 用于工具调用时透传鉴权
    Flux<String> stream(String sessionId, String userMessage, String authToken);
}
```

- **控制器只依赖 `LlmClient`**，看不到 `ChatClient`、`Flux` 之外的任何 Spring AI 概念。
- **默认适配器 `SpringAiLlmClient`**：用 SAA 的 `ChatClient.Builder` 造客户端，`.defaultSystem(系统提示)`，把工具 Bean 用 `.defaultTools(workOrderTools)` 注册；
- **`MockLlmClient`**（`provider=mock`）：**不调真模型**，直接回一段回显/固定文本。它的价值是——**没有 API key 时也能把「Feign→工具→SSE」整条链路跑通并验证**（见 §十二 验证步骤 2）。

### 5.3 换提供方的两种粒度

| 要换的是 | 怎么做 | 上层是否感知 |
|---|---|---|
| **模型名 / endpoint / key**（同一提供方） | 只改 `application.yaml`：`agent.llm.model` / `base-url` / `api-key` | 不感知 |
| **整个提供方**（如换 DeepSeek） | 新写一个 `LlmClient` 适配器（或换 SAA 对应的 model starter），用 `@ConditionalOnProperty(agent.llm.provider=...)` 选中 | 不感知——控制器与 `@Tool` 代码**一行不改** |

### 5.4 一个必须知道的坑：不要在同进程同时引两个 provider starter

> **同一进程内不要同时引入 dashscope 与 openai（或其它）两个 model starter**——那会产出多个 `ChatModel` Bean，注入时**歧义**。

所以「多提供方切换」这一需求，**在适配器层解决（实现第二个 `LlmClient` / 按需换 starter），而不是在同一进程里把多个 starter 都引进来**。M1 只引 dashscope；`mock` 不需要任何 starter。

---

## 六、与项目一的通信 + JWT 透传（最关键的正确性点）

### 6.1 契约：项目一 `/tool/**` 的既定约定

项目一侧的 Tool 接口已实现（`com.rj.controller.tool` 包），契约如下（**m1 只需遵守，不需修改**）：

| 约定 | 内容 | 对 agent 的含义 |
|---|---|---|
| **成功体** | **裸数据**（不套 `Result`） | agent 直接把 JSON 交给模型，无需拆 `data` 字段 |
| **失败体** | 精简 `{"code","message"}` | agent 把错误体一并交给模型解读（模型可据此告诉用户「没权限」等） |
| **HTTP 状态** | `/tool/**` **恒返回 200**（业务态在 body） | agent 侧不会因 4xx/5xx 抛异常，稳定拿到 body |
| **鉴权** | **透传用户 JWT**（复用 `LoginInterceptor`） | agent 必须把用户的 `Authorization` 带给项目一，否则 401 |
| **数据范围** | 由项目一按 JWT 里的身份自动收敛 | agent **不关心**数据范围逻辑，天然只看到该用户可见的数据 |
| **列表 size** | 项目一**静默封顶 50** | 防 LLM 上下文膨胀（见项目一 `/tool/` 契约决策） |

现有读接口：

| 接口 | 复用项目一 | 返回 |
|---|---|---|
| `GET /tool/workorder/list` | `IWorkOrderService.page`（size 封顶 50） | `PageResult<WorkOrderVO>`（裸） |
| `GET /tool/workorder/{id}` | `IWorkOrderService.detail` | `WorkOrderDetailVO`（裸） |
| `GET /tool/user/info` | `IUserService.me()`（roles + perms） | `UserVO`（裸） |

### 6.2 难点：工具执行可能不在「请求线程」上

流式 + 工具调用的执行线程，**不保证**是接收 HTTP 请求的那条线程。因此**不能用 `RequestContextHolder` 在 `@Tool` 方法里抓 `Authorization` 头**——那在多线程下取不到值（ThreadLocal 不跨线程）。

### 6.3 方案：用 Spring AI 的 `ToolContext` 显式传参

```
请求线程:  取 Authorization 头
            │
            ▼
   chatClient.prompt(...)
        .toolContext(Map.of("authorization", token))   ← 显式塞进工具上下文
        .stream() ...
            │
            ▼  （工具可能在任何线程上被调用）
   @Tool 方法签名接收 ToolContext ctx
        → token = (String) ctx.getContext().get("authorization")
        → 作为【显式参数】传给 Feign/RestClient 方法：
          client.list(... , token)  →  请求头 Authorization: Bearer <token>
```

**要点**：token 不作为 ThreadLocal 隐式传递，而是**沿着 `ToolContext → 方法参数 → HTTP 头`这条路显式传递**，彻底绕开跨线程问题。

> **实现时需验证**：SAA 1.1.2 的 `@Tool` 是否支持注入 `ToolContext` 参数。**若不支持**，退化为「服务层 ThreadLocal + `contextWrite`」，或「把本会话 token 与 sessionId 一起放进会话级的 `ConcurrentHashMap`」。这是计划中登记的**待确认风险 R3**。

### 6.4 客户端返回类型：收原始 `String`

`WorkOrderToolClient` 的方法**返回 `String`（原始响应体）**，而不是强类型 DTO。原因：

1. 项目一成功体是裸 JSON、失败体是 `{code,message}`，**结构不固定**，强类型映射会产生歧义；
2. 保留原始错误体，模型才有机会「读懂」失败原因并转述给用户；
3. 后续如要升级为强类型 DTO + 自定义 Decoder，改动**只在这一层**。

---

## 七、工具定义（M1 只做「读」）

### 7.1 两个工具

| 工具方法 | 描述 | 打到项目一 | 参数 |
|---|---|---|---|
| `listWorkOrders(status, orderType, keyword)` | 查询工单列表 | `GET /tool/workorder/list` | 状态码 / 工单类型 / 关键字 |
| `queryWorkOrder(id)` | 按 ID 查工单详情 | `GET /tool/workorder/{id}` | 工单 ID |

设计要点：

- 每个方法都加 `@Tool(description=...)` + `@ToolParam` 参数描述。**描述质量直接决定模型会不会在正确的时机、用正确的参数调用工具**——这是「工具调用」类项目里最容易被低估的一环。
- `listWorkOrders` 的参数**对齐项目一列表接口暴露的维度**（status + orderType + keyword），并提醒模型「列表已封顶 50 条」，避免模型幻想能一次拉全量。
- M1 **不挂权限过滤**（工具对谁可见）。项目一的 `LoginInterceptor` 已保证「调用者合法 + 数据范围正确」；「按用户 perms 决定工具可见性」是 M2（用 `/tool/user/info` 的 perms）。

### 7.2 为什么不把「写」也做了

项目一侧的写接口 **`PUT /tool/workorder/{id}/status` 目前只是可运行 demo**（不落库）——真实实现要按状态机把 `(当前态, 目标态)` 分发给 `review/dispatch/process/accept/withdraw`，并**按动作逐个判权**。这块在项目一侧尚未落地，agent 侧自然**无从对接**。因此写链路的对接属于 **M4**，而非 M1。

---

## 八、SSE 流式返回

- 用 **Spring MVC**（与项目一同栈，团队零学习成本），控制器返回 `Flux<ServerSentEvent<String>>`，`produces = MediaType.TEXT_EVENT_STREAM_VALUE`。
- 把 `LlmClient.stream(...)` 的**每个 token 映射成一个 SSE 事件**，客户端逐字渲染。
- MVC 用**独立异步线程**处理，工具里的**阻塞式 HTTP 调用（RestClient）不会卡事件循环**。
- 端点：`GET /api/agent/chat?sessionId=..&message=..`，**带 `Authorization` 头**。

> **实现时需验证**：Spring MVC 返回 `Flux<ServerSentEvent>` 的 SSE 装配（`reactor-core` 由 Spring AI 传递提供）；**若有波折，退化为 servlet 原生 `SseEmitter`**。登记为**待确认风险 R4**。

---

## 九、会话记忆（M1 从简）

- 用 Spring AI 的 **`MessageWindowChatMemory`（内存版，限 N 轮）**，`sessionId` 作为会话键。
- 选内存版是刻意的：M1 的目标是「跑通」，记忆只是让多轮对话成立，**没必要一上来就引 Redis**。
- 与文档 §3.4 模块 3（Redis 会话记忆）**对齐但分步做**：`MessageWindowChatMemory` 与将来的 Redis 版**接口一致**，将来替换**不动业务代码**。
- ⚠️ 内存版**重启即丢**、**多实例不共享**——这在 M1 可接受，M5 换 Redis 时一并解决。

---

## 十、配置（`application.yaml`）

```yaml
server:
  port: 8082

spring:
  application:
    name: service-agent
  ai:
    dashscope:
      api-key: ${AI_DASHSCOPE_API_KEY:}      # 无 key 时用 provider=mock
      chat:
        options:
          model: qwen-plus                    # qwen-plus / qwen-max 支持工具调用

agent:
  llm:
    provider: mock                            # dashscope | mock
    # 预留:model / base-url / api-key 等统一挂在 agent.llm.* 下,便于「同提供方调参」
  workorder:
    base-url: http://localhost:10001          # 项目一
```

设计要点：

- **`provider` 是唯一的切换开关**，默认 `mock`（**开箱即跑，不需要 key**），拿到 key 后改成 `dashscope` 即可。
- 项目一地址集中在 `agent.workorder.base-url`，便于本地 / 部署环境切换。

> **实现时需核对**：SAA 1.1.2 的 DashScope 配置前缀是否为 `spring.ai.dashscope.api-key`（不同大版本可能变为 `spring.ai.alibaba.dashscope.*`）。可用 `spring-boot-configuration-processor` 产物或解包 `spring-ai-alibaba-autoconfigure-dashscope` jar 核对。登记为**待确认风险 R1**。

---

## 十一、里程碑路线图（分层叠加）

| 里程碑 | 内容 | 对应文档 |
|---|---|---|
| **M1（本文）** | 骨架 + 大模型封装 + 只读工具（list/detail）+ SSE + 内存记忆 | §3 Agent 服务主干 |
| **M2** | 审计表 `agent_tool_call_log`；两层权限过滤（用 `/tool/user/info` 的 perms 收敛「工具可见性」） | §3.4 模块 2 |
| **M3** | RAG 知识库（Milvus / 向量检索），让模型「懂业务规则」而不只是「查数据」 | §3.6 幻觉与上下文 |
| **M4** | 写操作对接：项目一写接口真实落地（状态机分发 + 按动作判权）→ agent 侧接入 | 项目一 `/tool/**` 写扩展点 |
| **M5** | Sentinel 降级 / 熔断；Redis 会话记忆；SSE 心跳 / 错误事件规范 | §3.4 模块 3、§3 韧性 |

**分层原则**：每一层都**叠加在 M1 那条主干上**，且**不推翻上一层**——M2 加审计与过滤、M3 加检索增强、M4 加写、M5 加韧性与持久化。M1 的价值就是先把这条干主干**证明能通**。

---

## 十二、端到端验证（M1）

1. 起项目一（10001）；起 service-agent（8082）。
2. **先 `agent.llm.provider=mock` 冒烟，无需 key**：
   - 登录取 JWT：`POST http://localhost:10001/user/login`（admin/admin123）→ 取 body 的 `data`。
   - `curl -N -H "Authorization: Bearer $TOK" "http://localhost:8082/api/agent/chat?sessionId=s1&message=list+my+pending+workorders"` → **观察 SSE 逐条输出**；
   - 查 service-agent 日志，确认 RestClient **真的打到了**项目一 `/tool/workorder/list`（工具链路通）。
3. **有 key 后**切 `provider=dashscope`，配 `AI_DASHSCOPE_API_KEY`，同一命令应得到**模型结合真实工单数据的自然语言回答**。
4. **反向验证鉴权**：不带 `Authorization` 调 agent → 应被拒 / 工具调用失败（证明 JWT 透传确实生效）。

---

## 十三、风险 / 待实现时确认

| 编号 | 风险 | 应对 |
|---|---|---|
| **R1** | SAA 1.1.2 的 DashScope 配置前缀（`spring.ai.dashscope.api-key` vs `spring.ai.alibaba.dashscope.*`） | 解包 `spring-ai-alibaba-autoconfigure-dashscope` jar 核对；M1 默认 `mock`，不阻塞开工 |
| **R2** | Spring Boot 3.5.8 parent 本地未缓存 | 已实测 Maven Central 可达（HTTP 200），首次构建联网下载即可 |
| **R3** | `@Tool` 方法能否注入 `ToolContext`（决定 JWT 透传方案） | 若支持 → §6.3 显式传参；若不支持 → 服务层 ThreadLocal + `contextWrite`，或 session 级 token 缓存 |
| **R4** | Spring MVC 返回 `Flux<ServerSentEvent>` 的 SSE 装配 | 必要时退化为 servlet 原生 `SseEmitter` |
| **R5** | 无 API key | 全程 `provider=mock` 也能验证链路，**不阻塞开工** |

---

## 附：本设计与项目一既有成果的衔接

| 项目一已落地 | 项目二如何用 |
|---|---|
| `/tool/**` 读接口（`ToolWorkOrderController` / `ToolUserController`） | agent 的 `WorkOrderToolClient` 直连 |
| 精简错误体（`ToolExceptionHandler`：`{code,message}`） | agent 原样交给模型解读 |
| 列表 size 封顶 50 | agent 侧 `@Tool` 描述里提醒模型，避免上下文膨胀 |
| 透传 JWT 鉴权（`LoginInterceptor`） | agent 的 `ToolContext → 方法参数 → Authorization 头` 透传 |
| `PUT /tool/workorder/{id}/status`（demo + TODO 扩展点） | M4 对接写链路时启用 |
