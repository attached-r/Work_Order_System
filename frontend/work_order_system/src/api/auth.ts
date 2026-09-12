/**
 * 认证:登录 / 注册 / 当前用户 / 登出
 * 对应后端 UserController 中不带 user:manage 权限的那几个接口。
 */
import { request } from '@/utils/request'
import type { UserVO } from '@/types/domain'

export interface LoginPayload {
  username: string
  password: string
}

export interface RegisterPayload {
  username: string
  password: string
  /** 后端要求与 password 一致,且是必填 */
  confirmPassword: string
  realName: string
  phone?: string | null
  departmentId?: number | null
}

/**
 * 登录:成功返回 JWT。
 *
 * skipAuthRedirect 让登录接口自己处理 401 —— 账号密码错就是普通的业务失败,
 * 不该触发"登录已失效"的整页跳转逻辑。
 */
export function login(payload: LoginPayload): Promise<string> {
  return request<string>('/user/login', {
    method: 'POST',
    body: payload,
    skipAuthRedirect: true,
  })
}

/** 注册:成功不返回 token,由前端引导用户去登录 */
export function register(payload: RegisterPayload): Promise<void> {
  return request<void>('/user/register', {
    method: 'POST',
    body: payload,
    skipAuthRedirect: true,
  })
}

/** 当前登录用户信息,含角色与权限码 —— 前端据此控制菜单与按钮显隐 */
export function fetchMe(): Promise<UserVO> {
  return request<UserVO>('/user/me')
}

/** 登出:让服务端 token 立即失效(不只是前端清缓存) */
export function logout(): Promise<void> {
  return request<void>('/user/logout', { method: 'POST' })
}
