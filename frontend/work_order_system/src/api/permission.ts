/**
 * 权限接口,对应后端 PermissionController,要求 user:manage 权限。
 */
import { request } from '@/utils/request'
import type { PermissionVO } from '@/types/domain'

/**
 * 权限树。
 *
 * ⚠️ 后端当前的种子数据里所有权限的 parent_id 都是 null,返回的其实是一个
 * 扁平的 10 元素列表,没有「工单管理 / 系统管理」这样的分组节点。
 * 分组由前端按 permCode 的命名空间补出来,见 RoleListView.vue 的 groupedTree。
 */
export function fetchPermissionTree(): Promise<PermissionVO[]> {
  return request<PermissionVO[]>('/permission/tree')
}
