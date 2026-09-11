/**
 * 领域模型类型定义
 *
 * ⚠️ 这些类型目前是「按接口文档手写的占位版本」,只服务于当前阶段的静态页面。
 * 后端联调时应当:
 *   1. 用 openapi 生成器(或手写)对齐字段;
 *   2. 把本文件替换为 src/types/generated.d.ts + 少量前端派生类型。
 * 页面组件只依赖这里的类型名,不直接依赖字段来源,替换时页面无需改动。
 */

/** 统一返回体 */
export interface Result<T> {
  code: number
  message: string
  data: T
  timestamp: number
}

/** 分页返回体 */
export interface PageResult<T> {
  records: T[]
  total: number
  current: number
  size: number
}

// ---------------------------------------------------------------------------
// 用户 / 角色 / 部门 / 权限
// ---------------------------------------------------------------------------

export interface UserVO {
  userId: number
  username: string
  realName: string
  departmentId: number | null
  departmentName: string | null
  status: number
  roles: string[]
  perms: string[]
}

export interface Role {
  id: number
  roleCode: string
  roleName: string
  remark: string | null
}

export interface Department {
  id: number
  deptCode: string
  deptName: string
  remark: string | null
}

export interface PermissionVO {
  id: number
  permCode: string
  permName: string
  children: PermissionVO[]
}

// ---------------------------------------------------------------------------
// 工单
// ---------------------------------------------------------------------------

/** 工单列表项,对应后端 WorkOrderVO */
export interface WorkOrderVO {
  id: number
  orderNo: string
  userId: number
  departmentId: number | null
  handlerId: number | null
  title: string
  orderType: number
  priority: number
  status: number
  statusDesc: string
  createTime: string
  updateTime: string
  expireTime: string | null
}

/** 工单资源明细,对应后端 WorkOrderResource */
export interface WorkOrderResource {
  id: number
  workOrderId: number
  resourceName: string
  resourceType: string | null
  quantity: number | null
  remark: string | null
}

/** 工单操作日志,对应后端 WorkOrderOperateLog */
export interface WorkOrderOperateLog {
  id: number
  workOrderId: number
  operatorId: number
  operateType: number
  fromStatus: number | null
  toStatus: number | null
  remark: string | null
  createTime: string
}

/** 工单详情,对应后端 WorkOrderDetailVO */
export interface WorkOrderDetailVO extends WorkOrderVO {
  content: string | null
  remark: string | null
  resources: WorkOrderResource[]
  logs: WorkOrderOperateLog[]
}

/** 工单列表查询参数 */
export interface WorkOrderQuery {
  current: number
  size: number
  status?: number | null
  orderType?: number | null
  keyword?: string
}

/** 创建 / 重新提交工单入参 */
export interface WorkOrderCreateForm {
  title: string
  content: string
  orderType: number | null
  priority: number | null
  expireTime: string | null
  resources: WorkOrderResourceForm[]
}

export interface WorkOrderResourceForm {
  resourceName: string
  resourceType: string | null
  /**
   * 表单里用 number 而不是 number | null:
   * el-input-number 的 modelValue 不接受 null,而且这个输入框有 min=1,
   * 空值没有业务含义。回填时用 `quantity ?? 1` 兜底。
   */
  quantity: number
  remark: string | null
}

/** 用户列表查询参数 */
export interface UserQuery {
  current: number
  size: number
  keyword?: string
}
