/**
 * 领域模型类型定义
 *
 * 已按后端实际返回(而非接口文档示例)对齐过。几处文档与实现不一致、容易踩的地方:
 *   - 后端时间字段是 ISO-8601 的 `2026-09-11T20:49:23`,不带时区;
 *   - 子表(资源明细 / 操作日志)的外键字段名是 `orderId`,不是 `workOrderId`;
 *   - `/user/page` 不回填 perms(为 null),由 src/api/user.ts 收敛成空数组;
 *   - 资源明细的 `quantity` 是后端 BigDecimal,可能是小数;
 *   - `phone` 清空后是空字符串 `''` 而非 `null`,判"未填写"要覆盖 '' / null / undefined。
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
  /**
   * 联系电话。后端写入侧用「传空串=清空,不传=不修改」区分两种意图,
   * 清空后库里是 `''` 而不是 NULL,所以这里可能同时出现 null 与 ''。
   */
  phone: string | null
  status: number
  roles: string[]
  perms: string[]
}

/**
 * 用户只读目录项,对应后端 UserBriefVO(`GET /user/directory`)。
 *
 * 与 UserVO 的唯一区别是**不含 perms** —— 该接口登录即可访问,
 * 若把权限码也带出来,等于任何普通账号都能枚举全量用户的权限清单。
 * roles 必须保留:派单 / 转派的下拉靠它筛出 HANDLER 候选人。
 */
export interface UserBriefVO {
  userId: number
  username: string
  realName: string
  departmentId: number | null
  departmentName: string | null
  status: number
  roles: string[]
}

export interface Role {
  id: number
  roleCode: string
  roleName: string
  remark: string | null
  createTime?: string
}

export interface Department {
  id: number
  deptCode: string
  deptName: string
  remark: string | null
  createTime?: string
}

/**
 * 部门只读目录项,对应后端 DepartmentBriefVO(`GET /department/directory`)。
 * 不含 remark / createTime —— 名称解析用不着,少一个字段少一分暴露面。
 * 注意:部门管理页要编辑 remark,所以那一页仍走 GET /department/list。
 */
export interface DepartmentBriefVO {
  id: number
  deptCode: string
  deptName: string
}

export interface PermissionVO {
  id: number
  permCode: string
  permName: string
  /** 父权限ID;后端当前种子数据全为 null(即扁平结构,没有分组节点) */
  parentId: number | null
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
  /** 所属工单ID;后端该字段叫 orderId */
  orderId: number
  resourceName: string
  resourceType: string | null
  quantity: number | null
  unit: string | null
  remark: string | null
  createTime?: string
}

/** 工单操作日志,对应后端 WorkOrderOperateLog */
export interface WorkOrderOperateLog {
  id: number
  /** 工单ID;后端该字段叫 orderId */
  orderId: number
  operatorId: number
  operateType: number
  fromStatus: number | null
  toStatus: number | null
  /** 派单 / 转派的目标处理人ID,其它操作为 null */
  toUserId: number | null
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

/** 单个状态的计数,对应后端 WorkOrderStatsVO.StatusCount */
export interface WorkOrderStatusCount {
  status: number
  statusDesc: string
  count: number
}

/** 工单统计,对应后端 WorkOrderStatsVO */
export interface WorkOrderStats {
  total: number
  /** 后端无条件返回全部 8 个状态(含数量为 0 的) */
  statusCounts: WorkOrderStatusCount[]
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
  unit: string | null
  remark: string | null
}

/** 用户列表查询参数 */
export interface UserQuery {
  current: number
  size: number
  keyword?: string
  /** 按所属部门筛选 */
  departmentId?: number | null
  /** 按状态筛选:1启用 0停用 */
  status?: number | null
  /** 按角色标识筛选,如 HANDLER */
  roleCode?: string | null
}
