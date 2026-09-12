/**
 * 角色接口,对应后端 RoleController,全部要求 user:manage 权限。
 *
 * 读写成对:listRolePermissions 读回已分配的权限 ID,assignPermissions 覆盖式写入。
 * **先读后写**是这里的正确用法 —— 写接口是覆盖语义,不先读回原值就提交,
 * 会把该角色未勾选的权限一并抹掉。
 */
import { request } from '@/utils/request'
import type { Role } from '@/types/domain'

/** 角色列表 */
export function listRoles(): Promise<Role[]> {
  return request<Role[]>('/role/list')
}

/**
 * 角色已分配的权限 ID 列表,用于权限抽屉回显勾选状态。
 * 只回 ID:权限名称由调用方用 fetchPermissionTree() 的字典自行映射。
 */
export function listRolePermissions(roleId: number): Promise<number[]> {
  return request<number[]>(`/role/${roleId}/permissions`)
}

/**
 * 覆盖式分配角色权限:以本次提交的列表为准,**空列表表示回收全部权限**。
 * 是覆盖而非追加,调用前务必确认勾选内容完整。
 */
export function assignPermissions(roleId: number, permissionIds: number[]): Promise<void> {
  return request<void>(`/role/${roleId}/permissions`, {
    method: 'PUT',
    body: { permissionIds },
  })
}
