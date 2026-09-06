# 内部工单资源管理平台与业务辅助智能Agent服务详细设计文档

# 内部工单资源管理平台 \+ 业务辅助智能 Agent 服务

详细设计文档

> 文档用途：指导项目开发、GitHub 文档、面试复盘；适配大三实习简历项目；两套系统可打通，Agent 通过 Tool‑Calling 调用工单平台 HTTP 接口。
> 技术栈版本约定
> 
> 

- JDK 17

- SpringBoot 3\.2\.x

- MyBatis‑Plus 3\.5\.x

- Spring‑AI‑Alibaba 1\.0\.x

- Mysql 8\.0

- Redis 7\.x

- RabbitMQ 3\.9\+

- Milvus 向量数据库 2\.4\.x

## 文档目录

1. 总体架构说明

2. 项目一：内部工单资源管理平台详细设计
2\.1 业务概述
2\.2 整体架构
2\.3 数据库表设计
2\.4 核心模块功能设计
2\.5 关键技术方案（缓存、MQ、分布式锁、并发控制）
2\.6 对外 Tool 接口（供 Agent 调用）
2\.7 项目难点 \& 踩坑记录（面试素材）

3. 项目二：业务辅助智能 Agent 服务详细设计
3\.1 业务概述
3\.2 整体架构
3\.3 数据库表设计
3\.4 核心模块：RAG 知识库、Tool‑Calling、会话记忆、审计、权限、异常降级
3\.5 与工单系统交互流程
3\.6 关键问题解决方案（幻觉、tool 调用失败、上下文膨胀）
3\.7 项目难点 \& 踩坑记录（面试素材）

4. 部署与演示方案

5. GitHub 文档组织建议

6. 简历项目描述模板

---

# 1\. 总体架构说明

两套独立 SpringBoot 服务：

1. **工单服务（service‑workorder，端口 8081）**：传统业务后端，提供完整工单业务能力，对外暴露标准化 HTTP 工具接口；RBAC 权限；数据持久化 Mysql；Redis 缓存；RabbitMQ 异步通知。

2. **Agent 服务（service‑agent，端口 8082）**：AI 应用服务；Spring‑AI‑Alibaba；Milvus 向量库；Redis 存储会话记忆；通过 OpenFeign 远程调用工单服务 Tool 接口；完成 RAG 检索、Agent 工具调用、审计日志、权限校验；对外提供 SSE 流式问答接口。

**系统交互流程**
用户输入自然语言问题 → Agent 服务接收 → 1\.RAG 检索知识库；2\. 判断是否需要调用业务工具；3\.Tool‑Calling 通过 Feign 调用工单服务 HTTP 接口；4\. 拿到业务结果结合大模型整理回答；5\. 记录完整审计日志；SSE 流式返回给前端。

> 设计目标：Agent 不直接操作工单库，全部业务变更走工单服务对外 API；权限校验统一由工单服务完成。
> 
> 

---

# 2\. 项目一：内部工单资源管理平台详细设计

## 2\.1 业务概述

面向企业内部的资源申请、问题处理工单后台。员工可以提交工单，处理人流转处理、驳回、撤销、关闭；系统异步消息通知；支持并发修改控制；对外提供标准化 HTTP 工具接口，供 Agent 智能服务调用实现自然语言操作工单。

角色：普通员工、处理人、管理员。

业务状态流转：`待提交 → 待处理 → 处理中 → 已驳回 → 已完成 → 已撤销 → 超时关闭`

> 状态机约束：不允许非法状态跳转，例如已完成工单不能再次驳回。
> 
> 

## 2\.2 整体架构分层

- controller 层：普通业务接口 \+ Tool 专用接口（给 Agent 调用）

- service 层：业务逻辑、状态机校验、事务控制

- mapper 层：MyBatis‑Plus 操作 Mysql

- common 模块：统一返回封装、全局异常、参数校验、工具类

- 中间件：Redis（缓存、分布式锁）；RabbitMQ（异步通知）

## 2\.3 数据库表设计

### user 用户表

|字段|说明|
|---|---|
|id|主键 bigint|
|username|账号|
|password|加密存储 BCrypt|
|real\_name|真实姓名|
|status|0 禁用 1 启用|

### role 角色表

|字段|说明|
|---|---|
|id|主键|
|role\_name|角色名称：员工 / 处理人 / 管理员|

### user\_role 用户角色关联表：用户‑角色多对多

### permission 权限表：权限标识（用于 Agent 调用鉴权）

> 权限标识示例：`workorder:create`、`workorder:query`、`workorder:modify`
> 
> 

### work\_order 工单主表

|字段|说明|索引|
|---|---|---|
|id|主键 bigint|主键|
|order\_no|工单编号唯一|唯一索引|
|user\_id|提交人 ID|普通索引|
|handler\_id|处理人 ID|普通索引|
|title|工单标题||
|content|工单描述||
|status|工单状态 0‑6|普通索引|
|apply\_resource|申请资源内容||
|create\_time|创建时间|普通索引|
|update\_time|更新时间||
|expire\_time|超时时间，到期自动关闭|索引|
|version|乐观锁版本号，用于并发修改||

> version 乐观锁：防止多人同时修改同一个工单产生覆盖。
> 
> 

### work\_order\_operate\_log 工单操作日志表

记录每一次状态变更：操作人、操作类型、变更前后状态、操作备注。

### mq\_message\_reliability MQ 消息可靠性表

消息落库，实现消息发送确认，防止消息丢失。

## 2\.4 核心模块功能设计

### 模块 1：认证鉴权 RBAC

1. 用户登录，JWT 生成 token；

2. 接口鉴权，判断用户角色与权限；

3. Agent 调用 Tool 接口时也携带 token，复用同一套权限体系。

### 模块 2：工单核心业务

1. 创建工单；

2. 分配处理人；

3. 处理、驳回、撤销；

4. 状态机校验：非法状态变更抛出业务异常；

5. 超时定时任务：expire\_time 到期自动关闭工单（Spring scheduled）；

6. 乐观锁 version 控制并发修改；

### 模块 3：异步通知（RabbitMQ）

场景：工单创建、状态变更，发送站内通知。
可靠性方案：

1. 消息落库 `mq_message_reliability`；

2. 生产者确认；

3. 消费端手动 ACK；

4. 重复消费幂等处理；

5. 失败消息重试、死信队列。

### 模块 4：缓存与分布式锁

1. Redis 缓存：热点工单详情、权限列表；设置 TTL；处理缓存击穿；

2. 分布式锁：创建工单编号生成、并发资源申请场景使用 Redisson 分布式锁；

3. 缓存穿透、击穿简单防护。

### 模块 5：全局能力

- 统一返回体 `Result<T>`；全局异常处理器；参数校验 @Valid；

- 拦截器：登录 token 校验；

- 请求限流：基于 Redis 实现简单接口限流。

## 2\.5 对外 Tool 接口（专门供 Agent Feign 调用）

> 这一组接口是两个项目打通关键，权限校验和普通接口一致。
> 返回格式标准化，方便大模型解析。
> 
> 

|接口|功能|
|---|---|
|GET /tool/workorder/list|查询我的工单列表（支持状态过滤）|
|GET /tool/workorder/\{id\}|查询工单详情|
|POST /tool/workorder/create|创建工单|
|PUT /tool/workorder/\{id\}/status|修改工单状态（处理 / 驳回 / 完成）|
|GET /tool/user/info|获取当前用户信息与权限|

> Tool 接口约束：返回简单干净 JSON，去除多余包装；参数校验严格；全部操作记录操作日志。
> 
> 

## 2\.6 项目难点 \& 面试踩坑素材（写进 github 文档）

1. **事务失效踩坑**：内部方法调用 @Transactional 不生效；自己复现并且理解 AOP 代理原理。

2. **并发修改工单**：不使用乐观锁会出现数据覆盖；引入 version 版本号解决。

3. **MQ 消息丢失、重复消费问题**：消息表 \+ 生产者确认 \+ 手动 ACK \+ 幂等。

4. **缓存问题**：热点工单缓存，缓存击穿处理；缓存和数据库双写一致性权衡。

5. **状态机非法流转**：直接代码写 if‑else 维护状态流转，新增状态需要修改代码；可以思考后续可扩展状态机方案。

6. **定时任务超时关闭**：服务重启任务丢失风险，生产环境应该把定时任务迁移到 xxl‑job。

## 2\.7 可扩展优化点（简历可以写）

> 不需要实现，作为思考点写进文档：
> 
> 

1. 引入 XXL‑Job 替代 Spring 内置定时任务；

2. 使用状态机框架简化工单状态流转；

3. MQ 消息监控告警。

---

# 3\. 项目二：业务辅助智能 Agent 服务详细设计

## 3\.1 业务概述

智能 Agent 服务，用户使用自然语言完成工单系统操作。
能力：

1. RAG 知识库检索：内部运维文档、系统使用文档；

2. Tool‑Calling：调用工单系统 Tool 接口，完成查询、创建、修改工单；

3. Redis 维护会话记忆，多轮对话；

4. 完整调用审计日志；

5. Tool 调用权限校验；

6. 超时、熔断、异常降级；

7. SSE 流式输出问答。

> 本项目不造 Agent 内核，基于 Spring‑AI‑Alibaba；重点做**业务工程化、异常处理、权限、审计，而不是只跑通 demo happy path**。
> 
> 

## 3\.2 整体架构分层

- controller：SSE 对话接口；会话管理接口

- agent 层：Spring‑AI‑Alibaba Agent、Tool 定义、RAG 编排

- feign 客户端：调用工单服务 Tool 接口

- service：会话管理、审计日志、权限校验、异常降级逻辑

- mapper：审计日志持久化

- 中间件：Redis（会话记忆）；Milvus（向量库 RAG）；对接大模型 API（DeepSeek / 通义千问）

## 3\.3 数据库表设计

### agent\_chat\_session Agent 会话表

|字段|说明|
|---|---|
|session\_id|会话唯一 ID|
|user\_id|对应用户 ID|
|create\_time|创建时间|
|expire\_time|会话过期时间|

> 完整会话上下文存储到 Redis，Mysql 只存会话元信息。
> 
> 

### agent\_tool\_call\_log Agent 工具调用审计日志表【非常重要】

每一次 Tool 调用全部落库

|字段|说明|
|---|---|
|id|主键|
|session\_id|会话 ID|
|user\_id|用户 ID|
|tool\_name|工具名称 \(createWorkOrder /queryWorkOrder\)|
|tool\_input|工具入参 JSON|
|tool\_output|工具返回结果 JSON|
|status|调用状态：成功 / 失败 / 超时 / 权限拒绝|
|error\_msg|异常信息|
|cost\_time|耗时 ms|
|create\_time|调用时间|

## 3\.4 核心模块详细设计

### 模块 1：RAG 知识库

1. 文档来源：工单系统使用手册、内部业务文档；

2. 文档切片：文本分割；向量化存入 Milvus 向量库；

3. 用户提问：做向量检索召回 Top‑N 文档片段；把文档作为上下文喂给大模型；

4. 简单召回过滤策略：相似度阈值过滤，过滤低相关性结果。

> 问题：RAG 只做基础版本；简历写明：后续优化方向：重排序 Rerank。
> 
> 

### 模块 2：Tool‑Calling 工具定义

使用 Spring‑AI‑Alibaba 声明 Tool，工具底层内部使用 OpenFeign 调用工单服务 Tool 接口。
工具列表：

1. queryWorkOrder：查询工单详情

2. listWorkOrder：查询工单列表

3. createWorkOrder：创建工单

4. modifyWorkOrderStatus：修改工单状态

5. getCurrentUserInfo：获取用户信息权限

> ⚠️关键点：**Agent 不直接访问 Mysql，全部业务操作走远程 Feign 调用工单服务接口；权限由工单服务鉴权。**
> 
> 

### 模块 3：会话记忆 Redis

1. key：`agent:session:{sessionId}`

2. value：存储对话历史消息；设置 TTL 自动过期；

3. 问题处理：会话上下文无限膨胀；简单策略：最多保留 N 轮对话，超出自动裁剪旧消息。

### 模块 4：权限校验逻辑

两层鉴权：

1. 请求携带用户 token；

2. Agent 发起 Tool 调用前，先获取用户权限；过滤掉用户没有权限的工具；禁止调用无权限 Tool；直接返回拒绝。

> 防止大模型幻觉诱导调用无权限工具。
> 
> 

### 模块 5：调用审计

**每一次 Tool 调用，无论成功失败全部写入 agent\_tool\_call\_log；记录入参、返回、异常、耗时。**
用于排查 Agent 行为，面试重点亮点。

### 模块 6：异常降级处理（Demo 最容易缺失部分，你的加分点）

1. **大模型 API 超时**：设置 http 超时时间；超时降级返回 “大模型服务暂时不可用，请稍后重试”；

2. **Feign 调用工单服务超时、熔断**：Sentinel 对 Feign 接口做熔断降级；Tool 调用失败写入审计日志；给大模型返回失败信息，Agent 可以选择重试 1 次；超过重试次数终止；

3. Tool 返回业务错误码（权限不足、工单不存在），把错误信息交给大模型整理为自然语言告诉用户；

4. Milvus 向量库不可用：RAG 降级关闭知识库检索，只使用大模型固有知识回答。

### 模块 7：SSE 流式输出

对话接口采用 SSE，把大模型返回流式推送给前端；异常中断处理。

## 3\.5 完整交互流程

1. 用户携带 token \+ sessionId \+ 用户问题请求 Agent 服务 SSE 接口。

2. 根据 sessionId 从 Redis 读取历史会话。

3. 用户 query 做 Milvus 向量检索，获取 RAG 参考文档。

4. Agent 组装 prompt，包含 RAG 文档、历史会话；大模型判断是否需要调用 Tool。

5. 如果需要调用工具：

    - Agent 层校验用户是否拥有该 Tool 权限；无权限直接拒绝。

    - Feign 远程调用工单 Tool 接口。

    - **无论成功失败写入审计日志表**。

    - 将工具返回结果送入大模型继续推理。

6. 大模型生成最终回答，SSE 流式返回前端。

7. 更新 Redis 会话上下文。

## 3\.6 关键问题解决方案（面试高频）

1. **大模型幻觉**

    - RAG 提供参考文档；Tool 返回真实业务数据；

    - Prompt 约束：工具返回结果为事实依据，不要编造工单信息；

2. **Tool‑Calling 调用失败**

    - Feign 超时、熔断；有限次数重试；失败记录审计日志；将错误信息反馈给大模型。

3. **会话上下文膨胀**

    - Redis 存储会话，限制最大对话轮数，裁剪早期消息；

4. Tool 参数格式错误：大模型输出不符合工具入参；Spring‑AI 会做校验；增加异常捕获。

## 3\.7 项目难点 \& 踩坑记录（github 文档）

1. Tool‑Calling 大模型输出参数格式错误，导致调用失败；如何捕获处理。

2. Feign 调用下游工单服务超时，没有降级策略会导致整个 Agent 接口卡死。

3. 会话上下文无限累积，prompt 越来越长；做对话轮数裁剪。

4. RAG 召回无关文档，带来误导；增加相似度阈值过滤。

5. 大模型幻觉试图调用用户没有权限的工具；增加前置权限校验拦截。

6. SSE 长连接断开，异常处理。

## 3\.8 待优化方向（写进文档，不用实现）

1. RAG 引入 Rerank 重排序优化检索质量；

2. Agent 多步任务执行持久化；支持任务中断恢复；

3. 工具调用结果缓存；

---

# 4\. 部署与演示方案

1. 本地开发：全部服务本地运行；Mysql/Redis/RabbitMQ/Milvus 使用 Docker Compose 一键拉起。

2. 演示：简单前端页面（极简 html 即可，不需要复杂 Vue），实现 SSE 对话；

3. GitHub 仓库建议：两个项目分开或者单仓库多 module；附带 docker‑compose\.yml 用于一键启动依赖环境。

# 5\. GitHub 文档组织建议

```Plain Text
/workspace
├── service‑workorder      # 工单系统
├── service‑agent          # Agent服务
├── docs
│   ├── design.md          # 这份详细设计文档
│   ├── agent‑os‑idea.md   # 你之前AgentOS原型构想文档（记录想法、难点、原型实现）
│   └── pitfall.md         # 踩坑记录
├── docker‑compose.yml     # 启动mysql redis rabbitmq milvus
└── README.md
```

> README 写明项目简介、架构图、启动步骤、演示说明；同时简单提及：前期探索 AgentOS 多 Agent 编排原型，识别较高复杂度，所以落地本业务 Agent 应用。
> 
> 

# 6\. 简历项目描述模板

## 项目一：内部工单资源管理平台

**技术栈**：SpringBoot3、MyBatis‑Plus、Mysql8、Redis、RabbitMQ
负责实现企业内部工单管理后台，支持工单提交、流转处理、驳回撤销、超时自动关闭；基于 RBAC 实现权限控制；使用乐观锁解决工单并发修改；通过 RabbitMQ 实现异步消息通知，完成消息可靠性保障；Redis 实现热点数据缓存与分布式锁；对外提供标准化 HTTP Tool 接口，供 Agent 智能服务调用。
**工作内容：**

1. 设计数据库表结构，建立合理索引，使用乐观锁 version 处理并发更新；

2. 实现工单状态机流转校验，拦截非法状态变更；

3. 完成 RabbitMQ 消息可靠性方案：消息落库、生产者确认、手动 ACK、死信队列处理；

4. 封装对外 Tool 接口，统一权限校验，供 Agent 服务远程调用；

5. 处理事务失效、缓存击穿、MQ 重复消费等问题。

## 项目二：业务辅助智能 Agent 服务（亮点项目）

**技术栈**：SpringBoot3、Spring‑AI‑Alibaba、Milvus、Redis、OpenFeign
基于 Spring‑AI‑Alibaba 构建业务 Agent 应用，对接工单系统实现自然语言操作工单；集成 RAG 知识库检索内部业务文档；通过 Tool‑Calling 调用工单系统对外接口完成业务操作；Redis 维护多轮会话记忆；实现完整工具调用审计日志、工具调用权限校验、全链路异常降级熔断；SSE 流式输出问答结果。
**工作内容：**

1. 实现 RAG 知识库，文档切片向量化存入 Milvus，相似度阈值过滤召回结果；

2. 封装 Agent 工具，Feign 远程调用工单服务接口，实现查询、创建、修改工单；

3. Redis 存储对话会话，限制最大轮数防止上下文膨胀；

4. 实现 Tool 调用审计日志落库，权限校验拦截越权工具调用；

5. 针对大模型超时、下游服务熔断、向量库不可用做降级策略，缓解大模型幻觉问题。

---

如果你需要，我可以下一步输出：

1. docker‑compose\.yml 完整配置；

2. AgentOS 原型构想文档（docs/agent‑os‑\[idea\.md\]\(idea\.md\)）模板；

3. 或者生成简易架构 mermaid 图文本。

> （注：部分内容可能由 AI 生成）
