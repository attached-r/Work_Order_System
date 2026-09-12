/**
 * 部门接口,对应后端 DepartmentController。
 *
 * 管理类接口(增删改 + /department/list)要求 user:manage;
 * /department/directory 放宽到「登录即可」,见下方说明。
 *
 * 注意两者都**要求已登录**,所以注册页仍然不能放部门下拉
 * (见 RegisterView.vue)—— 那时用户还没有 token。
 */
import { request } from '@/utils/request'
import type { Department, DepartmentBriefVO } from '@/types/domain'

export interface DepartmentPayload {
  deptCode: string
  deptName: string
  remark?: string | null
}

/**
 * 部门列表,**含 remark / createTime**,要求 user:manage。
 * 部门管理页要编辑备注,所以那一页用这个;纯名称解析请走 listDepartmentDirectory。
 */
export function listDepartments(): Promise<Department[]> {
  return request<Department[]>('/department/list')
}

/**
 * 部门只读目录,供把工单里的 departmentId 解析成部门名。
 *
 * 与 listDepartments 的区别:
 *   - **登录即可访问**,不再要求 user:manage —— 能看工单的角色未必是管理员;
 *   - 返回精简字段(无 remark / createTime)。
 */
export function listDepartmentDirectory(): Promise<DepartmentBriefVO[]> {
  return request<DepartmentBriefVO[]>('/department/directory')
}

/** 新增部门,deptCode 唯一 */
export function createDepartment(payload: DepartmentPayload): Promise<void> {
  return request<void>('/department', { method: 'POST', body: payload })
}

/** 修改部门,deptCode 唯一(排除自身) */
export function updateDepartment(id: number, payload: DepartmentPayload): Promise<void> {
  return request<void>(`/department/${id}`, { method: 'PUT', body: payload })
}

/** 删除部门;仍被用户或工单引用时后端会拒绝 */
export function deleteDepartment(id: number): Promise<void> {
  return request<void>(`/department/${id}`, { method: 'DELETE' })
}
