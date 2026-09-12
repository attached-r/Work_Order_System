/**
 * 用户管理接口,对应后端 UserController。
 *
 * 权限分两档:
 *   - 管理类(改用户 / 分配角色 / 重置密码 / 启停用 / 删除 / 分页列表)要求 user:manage;
 *   - /user/directory 放宽到「登录即可」,它是给名称解析与派单候选人用的,
 *     见 listUserDirectory 的说明。
 * 登录 / 注册 / me / logout 见 auth.ts。
 */
import { request } from '@/utils/request'
import type { PageResult, UserBriefVO, UserQuery, UserVO } from '@/types/domain'

/**
 * /user/page 的原始返回:roles / perms 实际可能为 null
 * (列表场景不回填权限,只有 /user/me 才有)。
 */
type RawUserVO = Omit<UserVO, 'roles' | 'perms'> & {
  roles: string[] | null
  perms: string[] | null
}

/** 把可能为 null 的数组字段收敛成空数组,免得调用方到处判空 */
function normalizeUser(raw: RawUserVO): UserVO {
  return { ...raw, roles: raw.roles ?? [], perms: raw.perms ?? [] }
}

/** 用户分页列表,支持关键词 / 部门 / 状态 / 角色四个维度的组合筛选 */
export async function pageUsers(query: UserQuery): Promise<PageResult<UserVO>> {
  const page = await request<PageResult<RawUserVO>>('/user/page', {
    query: {
      current: query.current,
      size: query.size,
      keyword: query.keyword,
      departmentId: query.departmentId ?? undefined,
      status: query.status ?? undefined,
      roleCode: query.roleCode ?? undefined,
    },
  })
  return { ...page, records: page.records.map(normalizeUser) }
}

/**
 * 用户只读目录(id -> 姓名映射用)。
 *
 * 工单接口只回传 userId / handlerId,不回传姓名,所以凡是需要展示人名的页面
 * 都得自己备一份映射表。这个接口就是为此而设:
 *   - **登录即可访问**,不再要求 user:manage —— 否则只有 workorder:dispatch
 *     的派单人拿不到候选人列表,派单功能直接不可用;
 *   - **全查不分页**,不存在分页上限截断的问题;
 *   - 返回 UserBriefVO,**不含权限码**(见该类型的说明)。
 */
export function listUserDirectory(): Promise<UserBriefVO[]> {
  return request<UserBriefVO[]>('/user/directory')
}

export interface UpdateUserPayload {
  realName: string
  /**
   * 联系电话,三态语义:
   *   - 不传(undefined)/ null → 不修改;
   *   - `''`                   → **清空**(后端会 trim,纯空白同样按清空处理);
   *   - 有值                   → 覆盖。
   */
  phone?: string | null
  /** 部门ID;null / 不传 = 不调整。注意调这个字段清不掉部门(见 assignDepartment) */
  departmentId?: number | null
}

/** 修改用户基本信息(姓名/电话/部门) */
export function updateUser(userId: number, payload: UpdateUserPayload): Promise<void> {
  return request<void>(`/user/${userId}`, { method: 'PUT', body: payload })
}

/** 删除用户(逻辑删除) */
export function deleteUser(userId: number): Promise<void> {
  return request<void>(`/user/${userId}`, { method: 'DELETE' })
}

/** 启停用账号:0 禁用 1 启用 */
export function updateUserStatus(userId: number, status: number): Promise<void> {
  return request<void>(`/user/${userId}/status`, { method: 'PUT', body: { status } })
}

/** 覆盖式分配角色:以本次提交的列表为准,后端要求至少一个 */
export function assignRoles(userId: number, roleIds: number[]): Promise<void> {
  return request<void>(`/user/${userId}/roles`, { method: 'PUT', body: { roleIds } })
}

/** 重置密码(管理员操作,无需原密码);重置后该用户需重新登录 */
export function resetPassword(userId: number, newPassword: string): Promise<void> {
  return request<void>(`/user/${userId}/password`, { method: 'PUT', body: { newPassword } })
}

/** 调整所属部门,决定其工单的数据范围 */
export function assignDepartment(userId: number, departmentId: number | null): Promise<void> {
  return request<void>(`/user/${userId}/department`, { method: 'PUT', body: { departmentId } })
}
