/**
 * HTTP 客户端
 *
 * 只做四件事:拼 URL、带上 token、解包后端的 Result、把业务错误抛成异常。
 * 没有引入 axios —— 需要的恰恰只有这些,fetch 足够,也少一个依赖。
 *
 * 两个必须记住的后端约定:
 *   1. HTTP 状态码恒为 200,真正的成败在响应体的 `code` 字段里(见 GlobalExceptionHandler);
 *   2. 时间字段是 ISO-8601 的 `2026-09-11T20:49:23`,不带时区。
 *      往上传时也必须是这个写法 —— `2026-09-11 20:49:23`(空格)后端会直接 400。
 */
import { TOKEN_KEY, USER_KEY } from '@/constants/storage'
import type { Result } from '@/types/domain'

/** 开发环境走 vite 代理(见 vite.config.ts)绕开 CORS;生产用环境变量指到真实网关 */
const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api'

/** 业务成功码,对应后端 ResultCode.SUCCESS */
const CODE_SUCCESS = 200

/** 未登录/登录失效,对应后端 ResultCode.UNAUTHORIZED */
const CODE_UNAUTHORIZED = 401

/**
 * 接口错误。
 *
 * 优先展示后端给的 message —— 后端的校验提示(「超时时间必须晚于当前时间」
 * 之类)比前端兜底文案有用得多。
 */
export class ApiError extends Error {
  /** 业务状态码;网络层失败为 -1 */
  readonly code: number

  constructor(code: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
  }
}

type QueryValue = string | number | boolean | null | undefined

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  /** 请求体,会被 JSON 序列化 */
  body?: unknown
  /** 查询参数;null / undefined / 空串会被整个丢掉 */
  query?: Record<string, QueryValue>
  /** 跳过 401 自动跳登录(登录接口自己用,失败提示由页面处理) */
  skipAuthRedirect?: boolean
}

/**
 * 拼查询串。
 * 空值必须丢掉而不是拼成 `status=`:后端 @RequestParam Integer 收到空串会 400,
 * 而前端的"不限"恰恰就是 null。
 */
function buildQuery(params?: Record<string, QueryValue>): string {
  if (!params) return ''
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value === null || value === undefined || value === '') continue
    search.append(key, String(value))
  }
  const qs = search.toString()
  return qs ? `?${qs}` : ''
}

/** 清掉本地登录态 */
function clearAuth(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

/**
 * 登录失效时跳登录页。
 * 用 location 而不是 router:request 是被 store 依赖的最底层,
 * 反向 import router 会绕成循环依赖;而且会话过期时整页重载反而更干净。
 */
function redirectToLogin(): void {
  if (window.location.pathname === '/login') return
  const redirect = encodeURIComponent(window.location.pathname + window.location.search)
  window.location.href = `/login?redirect=${redirect}`
}

/**
 * 发起请求并返回解包后的 data。
 *
 * @throws {ApiError} 网络失败、HTTP 非 2xx、或业务 code 非 200
 */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, query, skipAuthRedirect = false } = options

  const headers: Record<string, string> = {}
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) headers.Authorization = `Bearer ${token}`
  if (body !== undefined) headers['Content-Type'] = 'application/json'

  let response: Response
  try {
    response = await fetch(`${BASE_URL}${path}${buildQuery(query)}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch {
    throw new ApiError(-1, '无法连接服务器,请确认后端已启动')
  }

  if (!response.ok) {
    throw new ApiError(response.status, `请求失败(HTTP ${response.status})`)
  }

  let result: Result<T>
  try {
    result = (await response.json()) as Result<T>
  } catch {
    throw new ApiError(-1, '服务器返回了无法解析的内容')
  }

  if (result.code === CODE_UNAUTHORIZED) {
    clearAuth()
    if (!skipAuthRedirect) redirectToLogin()
    throw new ApiError(CODE_UNAUTHORIZED, result.message || '登录已失效,请重新登录')
  }

  if (result.code !== CODE_SUCCESS) {
    throw new ApiError(result.code, result.message || '请求失败')
  }

  return result.data
}
