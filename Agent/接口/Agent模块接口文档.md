# 智能工单 Agent 模块接口文档

> 面向前端改造：把现有前端接入 Agent 对话与知识库管理。
> 更新日期：2026-10-18
> Agent 服务端口：**10002**（项目一 service-workorder 仍为 **10001**）

---

## 1. 概览

### 1.1 服务地址

| 环境 | Base URL |
|---|---|
| 本地开发 | `http://localhost:10002` |

所有接口均为 UTF-8；JSON 响应为 `application/json;charset=UTF-8`；对话接口为 `text/event-stream`。

### 1.2 接口总表

| 方法 | 路径 | 用途 | 是否需要登录 |
|---|---|---|---|
| GET | `/api/agent/chat` | SSE 流式对话（主入口） | 建议携带；不带也能请求，但工具不可见 |
| GET | `/api/agent/hello` | 大模型连通性自测（调试用，不要做成产品功能） | 否 |
| POST | `/api/agent/knowledge/reindex` | 重建知识库索引 | 需要管理员 `user:manage` |
| GET | `/api/agent/knowledge/status` | 查询知识库索引状态 | 需要管理员 `user:manage` |
| GET | `/actuator/health` | 服务健康检查 | 否 |
| GET | `/swagger-ui/index.html` | Swagger 在线文档 | 否 |

### 1.3 认证方式

与项目一保持同一套 JWT，前端**原样透传**项目一的 `Authorization` 头：

```http
Authorization: Bearer <JWT>
```

Agent 会把该头透传给项目一 `/tool/user/info` 获取权限，再决定当前用户能看见哪些工具。

| 能力 | 所需权限码 |
|---|---|
| 对话中的工单查询、知识库检索 | `workorder:query` |
| 知识库重建 / 状态查询 | `user:manage`（通常仅 ADMIN） |

无 token、token 失效、项目一不可达时，Agent 按 **fail-closed** 处理：工具不可见，对话仍可继续，但模型无法查询任何业务数据。

### 1.4 前置依赖

- 项目一 `http://localhost:10001` 已启动（权限与工单数据来源）；
- Agent 的 `agent.llm.provider=openai` 时需配置真实大模型 Key；
- 知识库检索需要 embedding 服务与 Milvus 可用（当前配置为硅基流动 `BAAI/bge-m3` + 本地 Milvus `19530`）。

---

## 2. 流式对话（核心接口）

### 2.1 请求

```http
GET /api/agent/chat?sessionId={sessionId}&message={message}
Authorization: Bearer <JWT>
Accept: text/event-stream
```

| 参数 | 位置 | 必填 | 说明 |
|---|---|---|---|
| `sessionId` | query | 是 | 会话 ID。同一会话传同一个值；建议前端生成 UUID 并持久化 |
| `message` | query | 是 | 用户问题，**必须 `encodeURIComponent`** |
| `Authorization` | header | 否 | 项目一 JWT；不传时工具不可见 |

注意：
- `message` 在 query string 中，受 URL 长度限制；超长文本建议前端限制长度（如 2000 字以内）。
- 会话记忆保存在 Agent 进程内，**每个 sessionId 保留最近 20 条消息**；Agent 重启即丢失，前端不要在刷新页面后依赖服务端历史。

### 2.2 响应

HTTP 200，`Content-Type: text/event-stream`。服务端把模型输出按 token 逐段推送，每个 SSE 事件只有一个 `data` 字段：

```text
data:工单从提交到完成

data:一般会经过

data:待审核、待派单、处理中、待验收、已完成

```

前端处理规则：
1. 按标准 SSE 解析：事件之间以空行（`\n\n` 或 `\r\n\r\n`）分隔；
2. 取每帧中以 `data:` 开头的行，去掉 `data:` 和一个可选空格，多行用 `\n` 连接；
3. 把每帧文本**追加**到气泡内容中，不要覆盖；
4. 流结束（`done`）后停止 loading。

### 2.3 前端实现示例（fetch + SSE）

> 浏览器原生 `EventSource` **不能设置 Authorization 头**，因此这里必须用 `fetch` 读取流；也可以使用 `@microsoft/fetch-event-source`。

```ts
export async function streamChat(
  baseUrl: string,
  token: string,
  sessionId: string,
  message: string,
  onDelta: (text: string) => void,
  signal?: AbortSignal,
): Promise<void> {
  const url =
    `${baseUrl}/api/agent/chat` +
    `?sessionId=${encodeURIComponent(sessionId)}` +
    `&message=${encodeURIComponent(message)}`;

  const res = await fetch(url, {
    method: 'GET',
    headers: {
      Authorization: token.startsWith('Bearer ') ? token : `Bearer ${token}`,
      Accept: 'text/event-stream',
    },
    signal,
  });

  if (!res.ok || !res.body) {
    throw new Error(`Agent 请求失败：HTTP ${res.status}`);
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';

  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    // 逐个消费完整 SSE 帧
    let match: RegExpExecArray | null;
    while ((match = /\r?\n\r?\n/.exec(buffer)) !== null) {
      const frame = buffer.slice(0, match.index);
      buffer = buffer.slice(match.index + match[0].length);

      const data = frame
        .split(/\r?\n/)
        .filter((line) => line.startsWith('data:'))
        .map((line) => line.slice(5).replace(/^ /, ''))
        .join('\n');

      if (data) onDelta(data);
    }
  }

  // 处理最后一个没有以空行结尾的帧
  const tail = buffer
    .split(/\r?\n/)
    .filter((line) => line.startsWith('data:'))
    .map((line) => line.slice(5).replace(/^ /, ''))
    .join('\n');
  if (tail) onDelta(tail);
}
```

调用示例：

```ts
const controller = new AbortController();

await streamChat(
  'http://localhost:10002',
  jwt,
  sessionId,
  '审核驳回后怎么重新提交',
  (delta) => setAnswer((prev) => prev + delta),
  controller.signal,
);
```

离开页面或点击“停止生成”时调用 `controller.abort()`。

### 2.4 行为说明

- 模型会自行决定是否调用 `searchKnowledge`（规则类问题）或 `listWorkOrders / queryWorkOrder`（实时数据类问题）。
- 当前 SSE **只推送最终回答的文本增量**，不包含 `tool_call`、引用来源、检索分数等结构化事件。前端暂时无法单独渲染“正在检索知识库”这类状态；如需要，需后端扩展事件协议。
- 回答中若引用了知识库，模型会以文本形式注明（如“依据《工单操作 SOP》> 审核驳回后怎么处理”）。
- 工单列表最多返回 50 条，模型会在回答中说明这一限制。
- 用户无权限时，接口仍返回 HTTP 200，模型会以自然语言说明“未登录或无权”；**不要用 HTTP 状态码判断鉴权失败**。
- 大模型服务异常时，SSE 流会中断；前端应捕获 `reader.read()` 抛出的异常并提示重试。

---

## 3. 连通性自测（调试用）

```http
GET /api/agent/hello?context={prompt}
```

| 参数 | 必填 | 说明 |
|---|---|---|
| `context` | 是 | 任意一段测试文本 |

响应：`text/plain`，阻塞收集完整回答后一次性返回。

说明：
- 不携带 JWT，也不调用任何工具，只验证大模型 Key / base-url / model 是否配通；
- `agent.llm.provider=mock` 时只会返回 mock 文本，说明不了真实模型是否可用；
- **仅用于联调排障，不建议暴露给终端用户或做成产品按钮。**

---

## 4. 知识库管理

以下两个接口仅 `user:manage` 权限可用（通常是管理员）。

### 4.1 重建索引

```http
POST /api/agent/knowledge/reindex?force=false
Authorization: Bearer <ADMIN_JWT>
```

| 参数 | 位置 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `force` | query | 否 | `false` | `true` 表示忽略“语料未变化”判断，强制重建 |

成功响应 `200`：

```json
{
  "status": "INGESTED",
  "activeVersion": "20261018T134305Z-80150b42",
  "chunkCount": 35,
  "documentCount": 5,
  "corpusHash": "sha256:80150b4292075135ae00528dd6dd859e60a735cbb15cdc60022c7ff350682543",
  "message": "重建完成"
}
```

若语料与 embedding 配置均未变化且 `force=false`：

```json
{
  "status": "SKIPPED",
  "activeVersion": "20261018T134305Z-80150b42",
  "chunkCount": 35,
  "documentCount": 5,
  "corpusHash": "sha256:...",
  "message": "语料与 embedding 配置未变化"
}
```

错误响应：

| HTTP | body | 场景 |
|---|---|---|
| 403 | `{"code":403,"message":"需要管理员权限（user:manage）"}` | 无 token / 非管理员 / 项目一不可达 |
| 409 | `{"code":409,"message":"知识库重建正在进行中"}` | 已有重建任务在跑（单飞锁） |
| 500 | `{"code":500,"message":"知识库重建失败：..."}` | embedding / Milvus / 语料读取失败 |

前端建议：
- 重建可能持续数秒到数十秒，请求超时设置到 60s 以上；
- 提交后禁用按钮，避免重复触发 409；
- 重建期间检索仍使用旧索引，对话不会中断。

### 4.2 查询索引状态

```http
GET /api/agent/knowledge/status
Authorization: Bearer <ADMIN_JWT>
```

成功响应 `200`：

```json
{
  "enabled": true,
  "store": "milvus",
  "activeVersion": "20261018T134305Z-80150b42",
  "chunkCount": 35,
  "documentCount": 5,
  "embeddingProvider": "openai",
  "embeddingModel": "BAAI/bge-m3",
  "dimensions": 1024,
  "ingestedAt": "2026-10-18T13:43:06.544Z",
  "ingesting": false
}
```

| 字段 | 说明 |
|---|---|
| `enabled` | 知识库总开关 |
| `store` | `milvus` / `simple` |
| `activeVersion` | 当前检索使用的索引版本；从未入库时为 `null` |
| `chunkCount` / `documentCount` | 当前版本切片数 / 源文档数 |
| `embeddingProvider` / `embeddingModel` / `dimensions` | 当前 embedding 配置 |
| `ingestedAt` | 上次入库时间（ISO-8601） |
| `ingesting` | 是否有重建正在执行 |

错误响应：同样为 `403 {"code":403,"message":"需要管理员权限（user:manage）"}`。

---

## 5. 在线文档与健康检查

### 5.1 Swagger

- UI：`http://localhost:10002/swagger-ui/index.html`
- OpenAPI JSON：`http://localhost:10002/v3/api-docs`
- 现有分组：
  - `DefaultAll`（controller 包下全部接口）
  - `AgentChat`（`/api/agent/**`，当前也会包含知识库管理接口）
  - `knowledgeAdmin`（`/api/agent/knowledge/**`）

> 注意：三个分组存在重叠，同一接口会在多个分组中重复出现；前端对接以本文档为准。

### 5.2 健康检查

```http
GET /actuator/health
```

响应示例：

```json
{ "status": "UP" }
```

该接口默认只暴露 health。若 Redis / Milvus / 数据库异常，健康检查可能返回 `DOWN` 及对应组件信息。

---

## 6. TypeScript 类型汇总

```ts
export interface ApiError {
  code: number;
  message: string;
}

export type IngestStatusValue = 'INGESTED' | 'SKIPPED';

export interface IngestResult {
  status: IngestStatusValue;
  activeVersion: string;
  chunkCount: number;
  documentCount: number;
  corpusHash: string;
  message: string;
}

export interface KnowledgeStatus {
  enabled: boolean;
  store: 'simple' | 'milvus' | string;
  activeVersion: string | null;
  chunkCount: number;
  documentCount: number;
  embeddingProvider: string;
  embeddingModel: string;
  dimensions: number;
  ingestedAt: string | null;
  ingesting: boolean;
}
```

---

## 7. CORS 与本地联调

Agent 服务目前**没有配置 CORS**。如果前端页面与 Agent 不同源（例如前端跑在 5173/8080），浏览器会拦截带 `Authorization` 的跨域请求。

推荐做法（任选其一）：

### 7.1 前端开发代理（推荐）

注意：已有前端通常把 `/api` 代理到项目一（10001），必须把更具体的 `/api/agent` 放在前面：

```ts
// vite.config.ts
export default defineConfig({
  server: {
    proxy: {
      // 必须先匹配 /api/agent，否则会被下面的 /api 规则转发到项目一
      '/api/agent': {
        target: 'http://localhost:10002',
        changeOrigin: true,
      },
      '/api': {
        target: 'http://localhost:10001',
        changeOrigin: true,
      },
    },
  },
});
```

### 7.2 生产环境反向代理

```nginx
location /api/agent/ {
    proxy_pass http://agent:10002;
    proxy_http_version 1.1;
    proxy_set_header Connection '';
    proxy_buffering off;          # SSE 必须关闭缓冲
    proxy_read_timeout 3600s;
}
```

SSE 经过网关时务必关闭响应缓冲，否则前端会“卡住很久后一次性收到全部内容”。

---

## 8. 前端改造检查清单

- [ ] 对话请求改用 `fetch` + `ReadableStream` 解析 SSE，并携带 `Authorization`；
- [ ] 生成并持久化 `sessionId`（同一会话不变）；
- [ ] 支持“停止生成”（`AbortController`）；
- [ ] 流式追加文本，支持 Markdown 渲染；
- [ ] 处理流中断：提示重试，不要清空已输出内容；
- [ ] `message` 做 `encodeURIComponent`，并限制长度；
- [ ] 未登录时仍允许进入对话页，但要提示“未登录，无法查询工单”；
- [ ] 管理员页面接入知识库 `status` / `reindex`，处理 403、409；
- [ ] 配置开发代理 `/api/agent` → 10002（放在 `/api` 规则之前）；
- [ ] 生产网关对 SSE 关闭缓冲并放宽读超时。

---

## 9. 已知限制

1. **SSE 只有文本增量**：没有工具调用事件、引用结构、token 用量等元数据；如需“检索中…”状态指示，需要后端扩展。
2. **会话记忆在进程内**：每个 sessionId 保留最近 20 条消息，Agent 重启即丢失；前端如需历史会话，应自行保存展示记录。
3. **对话接口是 GET**：`message` 走 query string，超长问题会受 URL 长度限制；后续可考虑新增 POST 版本。
4. **鉴权失败不是 HTTP 错误**：对话接口始终 200，由模型用自然语言说明无权；管理端点才返回 403。
5. **mock 模式不要上线**：`agent.llm.provider=mock` 时回答形如 `[mock] ...`，仅用于联调。
6. **接口文档分组重叠**：Swagger 的 `AgentChat` 分组当前包含知识库接口，属于配置问题，不影响实际调用。
