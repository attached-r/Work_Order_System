/**
 * Agent(项目二)接口
 *
 * 为什么不复用 `utils/request.ts`:
 *   1. 项目一的接口约定是「HTTP 恒 200 + 业务 code 在 body 里」,
 *      而 Agent 的对话接口是真正的 `text/event-stream`,必须逐块读;
 *   2. 知识库接口成功时返回裸对象,失败时才是 HTTP 403/409/500 + `{code, message}`,
 *      与项目一的 `Result<T>` 包装不是同一套协议。
 *
 * 因此这里单独实现一个轻量 fetch 客户端,只复用 token 的键名与 Bearer 写法。
 *
 * M5 起对话流除了 token 之外还会推送:
 *   - `event: done`  正常结束(data 里带 traceId / durationMs);
 *   - `event: error` 错误结束(data 里带 code / message / retryable / traceId);
 *   - `:hb`          心跳注释,只用于保活,不产生文本。
 * 具体解析见下方「SSE 解析(M5 协议)」。
 */
import { TOKEN_KEY } from '@/constants/storage'

/** Agent 接口根地址:开发环境走 vite 代理(默认 /api/agent),生产可注入完整网关 */
const AGENT_BASE = (import.meta.env.VITE_AGENT_BASE_URL ?? '/api/agent').replace(/\/+$/, '')

/**
 * 用户问题长度上限。
 * 消息走 GET query string,过长会撞到 URL 长度限制,后端文档也建议 2000 字以内。
 */
export const MAX_MESSAGE_LENGTH = 2000

/**
 * 会话 id 的合法字符集,必须与后端 AgentChatController 的 SESSION_ID 正则一致:
 *   - 后端会用它拼 Redis 记忆 key,所以不允许空格/冒号/中文;
 *   - 前端先挡一道,避免拿到一个只有 "HTTP 400" 的模糊错误。
 */
const SESSION_ID_PATTERN = /^[A-Za-z0-9_-]{1,64}$/

/** 状态查询是轻量只读,15s 足够 */
const STATUS_TIMEOUT_MS = 15_000

/**
 * 重建索引要读语料、算 embedding、写向量库,可能持续数十秒,
 * 所以超时放宽到 120s(比接口文档建议的 60s 更保守一点)。
 */
const REINDEX_TIMEOUT_MS = 120_000

/** Agent 接口错误:code 可能是业务码、HTTP 状态码,网络/超时统一记为 -1 */
export class AgentApiError extends Error {
  readonly code: number
  /** HTTP 状态码;网络失败与超时为 0,SSE error 事件为 200(HTTP 本身成功) */
  readonly status: number
  /** 服务端建议是否值得重试;旧接口没有该字段时默认 true */
  readonly retryable: boolean
  /** 服务端 traceId,用于和 Agent 日志/审计对齐;可能为 null */
  readonly traceId: string | null

  constructor(
    code: number,
    message: string,
    status = 0,
    options: { retryable?: boolean; traceId?: string | null } = {},
  ) {
    super(message)
    this.name = 'AgentApiError'
    this.code = code
    this.status = status
    this.retryable = options.retryable ?? true
    this.traceId = options.traceId ?? null
  }
}

/**
 * 用户主动停止会被 fetch 包装成 AbortError,调用方据此区分「停止」与「失败」。
 * 不断言 `instanceof Error`:部分环境里中止抛的是 DOMException,
 * 与 Error 没有可靠的继承关系,只认 name 最稳。
 */
export function isAbortError(error: unknown): boolean {
  return (
    typeof error === 'object' &&
    error !== null &&
    (error as { name?: unknown }).name === 'AbortError'
  )
}

// ---------------------------------------------------------------------------
// 知识库类型(与后端 record 字段一一对应)
// ---------------------------------------------------------------------------

export interface KnowledgeStatus {
  enabled: boolean
  /** 向量库实现:milvus / simple */
  store: string
  /** 当前检索使用的索引版本;从未入库时为 null */
  activeVersion: string | null
  chunkCount: number
  documentCount: number
  embeddingProvider: string
  embeddingModel: string
  dimensions: number
  /** 上次入库时间(ISO-8601);从未入库时为 null */
  ingestedAt: string | null
  /** 是否有重建正在执行 */
  ingesting: boolean
}

export type IngestStatusValue = 'INGESTED' | 'SKIPPED'

export interface ReindexResult {
  status: IngestStatusValue
  activeVersion: string
  chunkCount: number
  documentCount: number
  corpusHash: string
  message: string
}

// ---------------------------------------------------------------------------
// 公共请求工具
// ---------------------------------------------------------------------------

/** 有 token 才带 Authorization;Agent 不传 token 也能对话,只是工具不可见 */
function buildAuthHeaders(accept: string): Record<string, string> {
  const headers: Record<string, string> = { Accept: accept }
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) headers.Authorization = `Bearer ${token}`
  return headers
}

/**
 * 读取错误响应的 `{code, message}`。
 * 不是所有失败都带 JSON(比如网关错误页),所以解析失败时回落到 HTTP 状态码。
 */
async function readErrorBody(
  response: Response,
): Promise<{ code: number; message: string | null }> {
  try {
    const text = await response.text()
    if (!text) return { code: response.status, message: null }

    const parsed = JSON.parse(text) as { code?: unknown; message?: unknown }
    return {
      code: typeof parsed.code === 'number' ? parsed.code : response.status,
      message: typeof parsed.message === 'string' ? parsed.message : null,
    }
  } catch {
    return { code: response.status, message: null }
  }
}

// ---------------------------------------------------------------------------
// SSE 解析(M5 协议)
// ---------------------------------------------------------------------------

/** 帧分隔符:兼容 LF / CRLF / 纯 CR 三种换行风格 */
const FRAME_BOUNDARY = /\r\n\r\n|\n\n|\r\r/

/**
 * 一帧 SSE 的解析结果。
 *
 * - `event` 默认是 `message`(无名事件),M5 新增的 `done` / `error` 会显式带 event 名;
 * - `data` 为 null 表示这一帧没有 data 字段(例如心跳注释 `:hb`),空串表示「空 token」,两者必须区分。
 */
interface SseFrame {
  event: string
  data: string | null
}

/**
 * 解析单帧。
 *
 * M5 的协议是:
 *   data:...            → 文本增量(无名 event,仍按 token 处理)
 *   event: done         → 正常结束,data 是 {traceId, durationMs}
 *   event: error        → 错误结束,data 是 {code, message, retryable, traceId}
 *   :hb                 → 心跳注释,直接跳过
 *
 * 注意:这里只解析,不执行 onDelta/onError;由 drainFrames 统一交给调用方。
 */
function parseFrame(frame: string): SseFrame {
  let event = 'message'
  let hasData = false
  const parts: string[] = []

  for (const line of frame.split(/\r\n|\n|\r/)) {
    // 空行与 `:` 开头的是注释(心跳),不产生事件
    if (!line || line.startsWith(':')) continue

    const colon = line.indexOf(':')
    const field = colon === -1 ? line : line.slice(0, colon)
    const value = colon === -1 ? '' : line.slice(colon + 1)

    // event 字段需要去掉冒号后的装饰性空格,否则 `event: done` 会变成 " done"
    if (field === 'event') {
      event = value.trim() || 'message'
      continue
    }

    if (field !== 'data') continue

    hasData = true
    // ⚠️ 刻意**不**剥掉冒号后的空格。
    //
    // SSE 规范允许 `data: value` 中有一个装饰性空格,通用客户端会把它删掉。
    // 但本项目的 Agent 用 Spring 的 ServerSentEvent 编码器,它写的是
    // `data:` + 原始 token,不额外加空格:模型输出里的空格 token 在线上就是
    // `data: \n\n`。若按通用规则剥掉,所有空格都会消失
    //(实测 mock 模式 `[mock] 收到问题` 会变成 `[mock]收到问题`)。
    // 因此这里保留冒号后的全部内容,只去掉 `data:` 前缀本身。
    parts.push(value)
  }

  return { event, data: hasData ? parts.join('\n') : null }
}

/**
 * 消费 buffer 中所有完整帧。
 *
 * - 心跳/注释帧没有 data 且 event=message,直接跳过;
 * - done/error 即使 data 为空也要交给调用方(至少能结束 loading);
 * - `flushTail=true` 时处理流末尾没有尾随空行的最后一帧。
 */
function drainFrames(
  buffer: string,
  onFrame: (frame: SseFrame) => void,
  flushTail = false,
): string {
  let rest = buffer

  // 步骤 1:逐个吃掉以空行结尾的完整帧
  while (true) {
    const match = FRAME_BOUNDARY.exec(rest)
    if (!match) break

    const rawFrame = rest.slice(0, match.index)
    rest = rest.slice(match.index + match[0].length)

    const frame = parseFrame(rawFrame)
    if (frame.data !== null || frame.event !== 'message') onFrame(frame)
  }

  // 步骤 2:流结束时,尾巴可能是一个没有空行收尾的完整帧
  if (flushTail && rest) {
    const frame = parseFrame(rest)
    if (frame.data !== null || frame.event !== 'message') onFrame(frame)
    rest = ''
  }

  return rest
}

/** done 事件里我们关心的元信息;旧后端可能没有 data,因此都允许 null */
interface DoneMeta {
  traceId: string | null
  durationMs: number | null
}

/** JSON.parse 之后先收窄成对象,避免直接对 any 做属性访问 */
function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null
    ? (value as Record<string, unknown>)
    : null
}

/** 解析 done 的 data;损坏/缺失都不应让已经成功的回答变成失败 */
function readDoneMeta(data: string | null): DoneMeta {
  if (!data) return { traceId: null, durationMs: null }

  try {
    const payload = asRecord(JSON.parse(data))
    return {
      traceId:
        payload && typeof payload.traceId === 'string' ? payload.traceId : null,
      durationMs:
        payload && typeof payload.durationMs === 'number' ? payload.durationMs : null,
    }
  } catch {
    return { traceId: null, durationMs: null }
  }
}

/** error 事件的 data 结构;解析失败也要给出一个可展示的兜底错误 */
interface ErrorEventPayload {
  code: number
  message: string
  retryable: boolean
  traceId: string | null
}

function readErrorEvent(
  data: string | null,
  fallbackTraceId: string | null,
): ErrorEventPayload {
  let payload: Record<string, unknown> | null = null
  if (data) {
    try {
      payload = asRecord(JSON.parse(data))
    } catch {
      payload = null
    }
  }

  const code = payload && typeof payload.code === 'number' ? payload.code : -1
  const message =
    payload && typeof payload.message === 'string' ? payload.message : ''
  const retryable =
    payload && typeof payload.retryable === 'boolean' ? payload.retryable : true
  const traceId =
    payload && typeof payload.traceId === 'string' ? payload.traceId : fallbackTraceId

  return {
    code,
    message: message || '智能助手返回了错误事件',
    retryable,
    traceId,
  }
}

// ---------------------------------------------------------------------------
// 流式对话
// ---------------------------------------------------------------------------

export interface StreamChatOptions {
  /** 会话 id,同时作为 Agent 记忆 key(后端会再拼 userId 前缀) */
  sessionId: string
  /** 用户问题 */
  message: string
  /** 停止生成用 */
  signal?: AbortSignal
  /** 每收到一段文本增量回调一次,调用方负责追加而不是覆盖 */
  onDelta: (delta: string) => void
}

/** done 事件返回的元信息;durationMs 可能缺失,只用于展示/排查 */
export interface StreamChatResult {
  /** done 事件里的 traceId;没有则回落到响应头 X-Trace-Id,仍可能为 null */
  traceId: string | null
  /** 模型本次生成耗时(毫秒) */
  durationMs: number | null
}

/**
 * 发起流式对话并持续解析 SSE(M5 协议)。
 *
 * 正常返回表示收到了 `event: done`;用户停止会抛出 AbortError;
 * 服务端 `event: error`、网络异常、以及「没有收到 done/error 就断流」都会抛 AgentApiError。
 * 调用方应保留已经通过 onDelta 回调出去的文本。
 */
export async function streamAgentChat(
  options: StreamChatOptions,
): Promise<StreamChatResult> {
  const { sessionId, message, signal, onDelta } = options

  // 步骤 1:本地先挡一道,避免把不合法的 sessionId / 空问题 / 超长问题发到服务端
  if (!SESSION_ID_PATTERN.test(sessionId)) {
    throw new AgentApiError(
      -1,
      '会话标识不合法(只允许字母、数字、下划线、连字符,长度 1-64)',
      0,
      { retryable: false },
    )
  }
  if (!message.trim()) {
    throw new AgentApiError(-1, '消息不能为空', 0, { retryable: false })
  }
  if (message.length > MAX_MESSAGE_LENGTH) {
    throw new AgentApiError(-1, `消息不能超过 ${MAX_MESSAGE_LENGTH} 字`, 0, {
      retryable: false,
    })
  }

  // 步骤 2:两个 query 参数都必须 encodeURIComponent,否则中文/&/# 会截断 URL
  const url =
    `${AGENT_BASE}/chat` +
    `?sessionId=${encodeURIComponent(sessionId)}` +
    `&message=${encodeURIComponent(message)}`

  let response: Response
  try {
    response = await fetch(url, {
      method: 'GET',
      // 浏览器原生 EventSource 不能加 Authorization,所以这里必须用 fetch 读流
      headers: buildAuthHeaders('text/event-stream'),
      signal,
    })
  } catch (error) {
    // 用户停止是正常路径,原样抛给上层识别
    if (isAbortError(error)) throw error
    throw new AgentApiError(-1, '无法连接智能助手服务,请确认 Agent 服务已启动')
  }

  // 步骤 3:非 2xx 先按 JSON 错误体解析;对话接口鉴权失败仍是 200,这里只兜真错误
  if (!response.ok) {
    const { code, message: serverMessage } = await readErrorBody(response)
    throw new AgentApiError(
      code,
      serverMessage ?? `智能助手请求失败(HTTP ${response.status})`,
      response.status,
      // 4xx 通常是参数/鉴权问题,重试也不会变;5xx 才值得给「重试」按钮
      { retryable: response.status >= 500 },
    )
  }

  if (!response.body) {
    throw new AgentApiError(-1, '智能助手没有返回可读取的数据流')
  }

  // 步骤 4:类型不对说明没走到 SSE(常见于网关返回了 JSON),及时报错而不是把 JSON 当文本
  const contentType = response.headers.get('content-type') ?? ''
  if (!contentType.includes('text/event-stream')) {
    const { code, message: serverMessage } = await readErrorBody(response)
    throw new AgentApiError(
      code,
      serverMessage ?? `智能助手返回了非预期内容(${contentType || '未知类型'})`,
      response.status,
    )
  }

  // 步骤 5:准备解析状态。
  //   - terminal 是「是否已收到终止帧」的标记:done 正常结束,error 错误结束;
  //   - traceId 优先用 done/error 的 data,其次用响应头,便于和 Agent 日志对齐;
  //   - durationMs 仅 done 有。
  let terminal: 'done' | 'error' | null = null
  let traceId: string | null = response.headers.get('x-trace-id')
  let durationMs: number | null = null

  /**
   * 处理一帧。
   *
   * 关键点:
   *   1. done/error 是终止帧,之后不再接收 token(防止半截数据污染);
   *   2. error 直接抛 AgentApiError,由 store 标记消息为 error;
   *   3. 无名事件与 `event: token` 都当作文本增量,兼容旧后端。
   */
  const handleFrame = (frame: SseFrame): void => {
    if (terminal !== null) return

    if (frame.event === 'done') {
      terminal = 'done'
      const meta = readDoneMeta(frame.data)
      traceId = meta.traceId ?? traceId
      durationMs = meta.durationMs
      return
    }

    if (frame.event === 'error') {
      terminal = 'error'
      const payload = readErrorEvent(frame.data, traceId)
      traceId = payload.traceId ?? traceId
      throw new AgentApiError(payload.code, payload.message, response.status, {
        retryable: payload.retryable,
        traceId,
      })
    }

    // 无名 / event: token:文本增量
    if (frame.data !== null) onDelta(frame.data)
  }

  // 步骤 6:逐块读取,按帧解析;decoder 的 stream 模式保证多字节汉字不被截断
  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  try {
    while (true) {
      const { value, done } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      buffer = drainFrames(buffer, handleFrame)
    }

    // 步骤 7:补上 decoder 里残余的半个字符,并处理没有尾随空行的最后一帧
    buffer += decoder.decode()
    drainFrames(buffer, handleFrame, true)
  } catch (error) {
    // 已经收到 done 之后再出现连接层小抖动,不应该把完整回答改成失败
    if (terminal === 'done') {
      return { traceId, durationMs }
    }
    throw error
  } finally {
    // 中止/异常时释放读锁,避免连接悬挂(取消失败也不该覆盖原始异常)
    try {
      reader.releaseLock()
    } catch {
      // ignore
    }
  }

  // 步骤 8:M5 后端正常结束一定发 done。只有 EOF 没有 done,多半是网关/网络中断;
  //          不能当成功,否则用户会把半截回答当成完整答案。
  if (terminal !== 'done') {
    throw new AgentApiError(-1, '连接意外结束,回答可能不完整,请重试', 0, {
      retryable: true,
      traceId,
    })
  }

  return { traceId, durationMs }
}

// ---------------------------------------------------------------------------
// 知识库管理
// ---------------------------------------------------------------------------

interface RequestJsonOptions {
  method?: 'GET' | 'POST'
  timeoutMs: number
  /** 超时时的专用文案,让调用方知道「任务可能还在后台跑」 */
  timeoutMessage: string
  signal?: AbortSignal
}

/**
 * 知识库接口共用的 JSON 请求。
 *
 * 与流式接口的差别:结果是一次性 JSON,但需要自己的超时控制
 * (项目一的 request.ts 没有超时参数,也不适合裸对象响应)。
 */
async function requestJson<T>(path: string, options: RequestJsonOptions): Promise<T> {
  const controller = new AbortController()
  const { timeoutMs, timeoutMessage, signal: externalSignal } = options

  // 步骤 1:超时与外部 signal 都汇到同一个 controller
  let timedOut = false
  const timer = setTimeout(() => {
    timedOut = true
    controller.abort()
  }, timeoutMs)

  const onExternalAbort = () => controller.abort()
  if (externalSignal) {
    if (externalSignal.aborted) controller.abort()
    else externalSignal.addEventListener('abort', onExternalAbort, { once: true })
  }

  try {
    // 步骤 2:发请求
    let response: Response
    try {
      response = await fetch(`${AGENT_BASE}${path}`, {
        method: options.method ?? 'GET',
        headers: buildAuthHeaders('application/json'),
        signal: controller.signal,
      })
    } catch (error) {
      if (timedOut) throw new AgentApiError(-1, timeoutMessage)
      if (isAbortError(error)) throw error
      throw new AgentApiError(-1, '无法连接智能助手服务,请确认 Agent 服务已启动')
    }

    // 步骤 3:Agent 管理接口的错误是 HTTP 403/409/500 + {code,message}
    if (!response.ok) {
      const { code, message } = await readErrorBody(response)
      throw new AgentApiError(
        code,
        message ?? `请求失败(HTTP ${response.status})`,
        response.status,
      )
    }

    // 步骤 4:成功响应是裸对象,直接解析
    try {
      return (await response.json()) as T
    } catch {
      throw new AgentApiError(-1, '智能助手返回了无法解析的内容', response.status)
    }
  } finally {
    clearTimeout(timer)
    externalSignal?.removeEventListener('abort', onExternalAbort)
  }
}

/** 查询知识库索引状态(需要 user:manage) */
export function getKnowledgeStatus(signal?: AbortSignal): Promise<KnowledgeStatus> {
  return requestJson<KnowledgeStatus>('/knowledge/status', {
    timeoutMs: STATUS_TIMEOUT_MS,
    timeoutMessage: '查询知识库状态超时,请稍后重试',
    signal,
  })
}

/** 重建知识库索引(需要 user:manage);force=true 忽略「语料未变化」判断 */
export function reindexKnowledge(
  force: boolean,
  signal?: AbortSignal,
): Promise<ReindexResult> {
  return requestJson<ReindexResult>(`/knowledge/reindex?force=${force}`, {
    method: 'POST',
    timeoutMs: REINDEX_TIMEOUT_MS,
    timeoutMessage: '重建请求超时,任务可能仍在后台执行,请稍后刷新状态',
    signal,
  })
}
