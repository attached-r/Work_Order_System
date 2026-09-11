/**
 * 工单领域常量
 *
 * 这里集中定义「码值 -> 中文名 / 视觉样式」的映射,与后端枚举一一对应:
 *  - WorkOrderStatus  : 0 待审核 1 待派单 2 处理中 3 待验收 4 已完成 5 已驳回 6 已取消 7 已超时
 *  - OperateType      : 操作日志事件
 *  - orderType        : 1 故障报修 2 资源申请 3 需求变更
 *  - priority         : 1 高 2 中 3 低
 *
 * 注意 tsconfig 开了 noUncheckedIndexedAccess,所以查表一律走下面的 getXxx() 函数,
 * 不要直接用 map[code],否则类型是可能 undefined 的。
 */

export interface Option<T = number> {
  value: T
  label: string
}

/** 工单状态码 */
export const WorkOrderStatus = {
  PENDING_REVIEW: 0,
  PENDING_DISPATCH: 1,
  PROCESSING: 2,
  PENDING_ACCEPT: 3,
  COMPLETED: 4,
  REJECTED: 5,
  CANCELED: 6,
  TIMEOUT: 7,
} as const

export type WorkOrderStatusValue = (typeof WorkOrderStatus)[keyof typeof WorkOrderStatus]

/** 状态 -> 中文名 */
const STATUS_LABEL: Record<number, string> = {
  0: '待审核',
  1: '待派单',
  2: '处理中',
  3: '待验收',
  4: '已完成',
  5: '已驳回',
  6: '已取消',
  7: '已超时',
}

/**
 * 状态名查询。后端在 WorkOrderVO 里也回传了 statusDesc,
 * 但列表页的筛选器、图例等仍需前端自己渲染,所以留一份本地映射做兜底。
 */
export function getStatusLabel(code: number | null | undefined): string {
  if (code === null || code === undefined) return '—'
  return STATUS_LABEL[code] ?? '未知'
}

export const STATUS_OPTIONS: Option[] = [
  { value: 0, label: '待审核' },
  { value: 1, label: '待派单' },
  { value: 2, label: '处理中' },
  { value: 3, label: '待验收' },
  { value: 4, label: '已完成' },
  { value: 5, label: '已驳回' },
  { value: 6, label: '已取消' },
  { value: 7, label: '已超时' },
]

/** 工单类型 */
const ORDER_TYPE_LABEL: Record<number, string> = {
  1: '故障报修',
  2: '资源申请',
  3: '需求变更',
}

export function getOrderTypeLabel(code: number | null | undefined): string {
  if (code === null || code === undefined) return '—'
  return ORDER_TYPE_LABEL[code] ?? '未知'
}

export const ORDER_TYPE_OPTIONS: Option[] = [
  { value: 1, label: '故障报修' },
  { value: 2, label: '资源申请' },
  { value: 3, label: '需求变更' },
]

/** 优先级 */
const PRIORITY_LABEL: Record<number, string> = {
  1: '高',
  2: '中',
  3: '低',
}

export function getPriorityLabel(code: number | null | undefined): string {
  if (code === null || code === undefined) return '—'
  return PRIORITY_LABEL[code] ?? '未知'
}

export const PRIORITY_OPTIONS: Option[] = [
  { value: 1, label: '高' },
  { value: 2, label: '中' },
  { value: 3, label: '低' },
]

/** 操作日志事件 */
const OPERATE_LABEL: Record<number, string> = {
  1: '提交',
  2: '审核通过',
  3: '审核驳回',
  4: '派单',
  5: '处理完成',
  6: '验收通过',
  7: '验收退回',
  8: '转派',
  9: '撤回/取消',
  10: '超时关闭',
}

export function getOperateLabel(code: number | null | undefined): string {
  if (code === null || code === undefined) return '—'
  return OPERATE_LABEL[code] ?? '未知操作'
}

/**
 * 状态 -> CSS 变量名后缀
 *
 * 所有颜色都定义在 styles/tokens.scss 的 --wo-st-{n}-{fg|bg|dot} 里,
 * 组件只拿变量名,不碰具体色值。
 */
export function getStatusTone(code: number | null | undefined): string {
  const known = code !== null && code !== undefined && code in STATUS_LABEL
  return known ? String(code) : '6'
}

/** 优先级 -> CSS 变量名后缀 */
export function getPriorityTone(code: number | null | undefined): string {
  return code === 1 || code === 2 || code === 3 ? String(code) : '3'
}

/** 是否为终态(已完成 / 已取消 / 已超时),终态工单不再允许任何操作 */
export function isTerminal(code: number | null | undefined): boolean {
  return code === WorkOrderStatus.COMPLETED || code === WorkOrderStatus.CANCELED || code === WorkOrderStatus.TIMEOUT
}

/**
 * 状态机:当前状态下允许流转到的目标状态。
 * 与后端 WorkOrderStatus.TRANSITIONS 保持一致,前端只用来决定按钮显隐,
 * 真正的合法性校验仍在后端。
 */
const TRANSITIONS: Record<number, number[]> = {
  0: [1, 5, 6, 7],
  1: [2, 6, 7],
  2: [2, 3, 6, 7],
  3: [4, 2, 7],
  5: [0, 6],
  4: [],
  6: [],
  7: [],
}

export function canTransition(from: number | null | undefined, to: number): boolean {
  if (from === null || from === undefined) return false
  return (TRANSITIONS[from] ?? []).includes(to)
}

/**
 * 工单详情页的可用动作。
 *
 * 这里只做「状态 + 权限」的粗筛,决定按钮是否出现;
 * 更细的归属校验(比如是不是自己的单、是不是本部门的单)由后端兜底。
 */
export interface WorkOrderAction {
  key: 'review' | 'dispatch' | 'transfer' | 'process' | 'accept' | 'withdraw' | 'resubmit' | 'edit'
  label: string
  type: 'primary' | 'default' | 'danger' | 'success'
  perm: string | null
}

export function availableActions(status: number, perms: string[]): WorkOrderAction[] {
  const has = (p: string) => perms.includes(p)
  const actions: WorkOrderAction[] = []

  if (status === WorkOrderStatus.PENDING_REVIEW) {
    if (has('workorder:review')) {
      actions.push({ key: 'review', label: '审核', type: 'primary', perm: 'workorder:review' })
    }
    if (has('workorder:withdraw')) {
      actions.push({ key: 'withdraw', label: '撤回', type: 'default', perm: 'workorder:withdraw' })
    }
  }

  if (status === WorkOrderStatus.PENDING_DISPATCH && has('workorder:dispatch')) {
    actions.push({ key: 'dispatch', label: '派单', type: 'primary', perm: 'workorder:dispatch' })
  }

  if (status === WorkOrderStatus.PROCESSING) {
    if (has('workorder:process')) {
      actions.push({ key: 'process', label: '处理完成', type: 'primary', perm: 'workorder:process' })
    }
    if (has('workorder:transfer')) {
      actions.push({ key: 'transfer', label: '转派', type: 'default', perm: 'workorder:transfer' })
    }
  }

  if (status === WorkOrderStatus.PENDING_ACCEPT && has('workorder:accept')) {
    actions.push({ key: 'accept', label: '验收', type: 'primary', perm: 'workorder:accept' })
  }

  if (status === WorkOrderStatus.REJECTED) {
    if (has('workorder:modify')) {
      actions.push({ key: 'resubmit', label: '重新提交', type: 'primary', perm: 'workorder:modify' })
    }
    if (has('workorder:withdraw')) {
      actions.push({ key: 'withdraw', label: '取消', type: 'default', perm: 'workorder:withdraw' })
    }
  }

  // 待审核 / 已驳回时允许提单人改内容;撤回与取消共用 withdraw 接口
  if (status === WorkOrderStatus.PENDING_REVIEW && has('workorder:modify')) {
    actions.push({ key: 'edit', label: '修改', type: 'default', perm: 'workorder:modify' })
  }

  return actions
}
