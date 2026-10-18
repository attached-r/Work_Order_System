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
 */
import { TOKEN_KEY } from '@/constants/storage'

/** Agent 接口根地址:开发环境走 vite 代理(默认 /api/agent),生产可注入完整网关 */
const AGENT_BASE = (import.meta.env.VITE_AGENT_BASE_URL ?? '/api/agent').replace(/\/+$/, '')

/**
 * 用户问题长度上限。
 * 消息走 GET query string,过长会撞到 URL 长度限制,后端文档也建议 2000 字以内。
 */
export const MAX_MESSAGE_LENGTH = 2000

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
  /** HTTP 状态码;网络失败与超时为 0 */
  readonly status: number

  constructor(code: number, message: string, status = 0) {
    super(message)
    this.name = 'AgentApiError'
    this.code = code
    this.status = status
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
// SSE 解析
// ---------------------------------------------------------------------------

/** 帧分隔符:兼容 LF / CRLF / 纯 CR 三种换行风格 */
const FRAME_BOUNDARY = /\r\n\r\n|\n\n|\r\r/

/**
 * 从一帧里取出 `data:` 内容。
 *
 * - 跳过空行与 `:` 开头的注释/心跳;
 * - 只认 `data` 字段,`event:` / `id:` / `retry:` 一律忽略;
 * - 多行 data 用 `\n` 连接(后端可能把含换行的 token 拆成多行);
 * - 返回 null 表示这一帧没有 data 字段;返回空串则表示「空 token」,两者必须区分。
 */
function extractData(frame: string): string | null {
  let hasData = false
  const parts: string[] = []

  for (const line of frame.split(/\r\n|\n|\r/)) {
    if (!line || line.startsWith(':')) continue

    const colon = line.indexOf(':')
    const field = colon === -1 ? line : line.slice(0, colon)
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
    parts.push(colon === -1 ? '' : line.slice(colon + 1))
  }

  return hasData ? parts.join('\n') : null
}

/**
 * 消费 buffer 中所有完整帧,返回尚未凑成完整帧的尾巴。
 *
 * @param flushTail 流结束时置 true:把没有尾随空行的最后一帧也处理掉。
 */
function drainFrames(
  buffer: string,
  onDelta: (delta: string) => void,
  flushTail = false,
): string {
  let rest = buffer

  // 步骤 1:逐个吃掉以空行结尾的完整帧
  while (true) {
    const match = FRAME_BOUNDARY.exec(rest)
    if (!match) break

    const frame = rest.slice(0, match.index)
    rest = rest.slice(match.index + match[0].length)

    const data = extractData(frame)
    if (data !== null) onDelta(data)
  }

  // 步骤 2:流结束时,尾巴可能是一个没有空行收尾的完整帧
  if (flushTail && rest) {
    const data = extractData(rest)
    if (data !== null) onDelta(data)
    rest = ''
  }

  return rest
}

// ---------------------------------------------------------------------------
// 流式对话
// ---------------------------------------------------------------------------

export interface StreamChatOptions {
  /** 会话 id,同时作为 Agent 进程内记忆的 key */
  sessionId: string
  /** 用户问题 */
  message: string
  /** 停止生成用 */
  signal?: AbortSignal
  /** 每收到一段文本增量回调一次,调用方负责追加而不是覆盖 */
  onDelta: (delta: string) => void
}

/**
 * 发起流式对话并持续解析 SSE。
 *
 * 正常返回表示流干净结束;用户停止会抛出 AbortError;
 * 网络/服务端异常抛 AgentApiError。调用方应保留已经回调出去的文本。
 */
export async function streamAgentChat(options: StreamChatOptions): Promise<void> {
  const { sessionId, message, signal, onDelta } = options

  // 步骤 1:本地先挡一道,避免把空问题或超长问题发到服务端
  if (!sessionId) throw new AgentApiError(-1, '会话标识缺失')
  if (!message.trim()) throw new AgentApiError(-1, '消息不能为空')
  if (message.length > MAX_MESSAGE_LENGTH) {
    throw new AgentApiError(-1, `消息不能超过 ${MAX_MESSAGE_LENGTH} 字`)
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

  // 步骤 5:逐块读取,按帧解析;decoder 的 stream 模式保证多字节汉字不被截断
  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  try {
    while (true) {
      const { value, done } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      buffer = drainFrames(buffer, onDelta)
    }

    // 步骤 6:补上 decoder 里残余的半个字符,并处理没有尾随空行的最后一帧
    buffer += decoder.decode()
    drainFrames(buffer, onDelta, true)
  } finally {
    // 中止/异常时释放读锁,避免连接悬挂(取消失败也不该覆盖原始异常)
    try {
      reader.releaseLock()
    } catch {
      // ignore
    }
  }
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
