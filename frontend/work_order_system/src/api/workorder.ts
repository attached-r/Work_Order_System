/**
 * 工单接口,对应后端 WorkOrderController。
 *
 * 设计约定(与后端一致):状态变更走「动作型」子资源路径(/review、/dispatch、/process、
 * /accept、/withdraw),而不是 PUT 改 status 字段 —— 这样后端能把状态机校验和权限码
 * 挂到每个动作上,前端也不能绕过流程把状态改坏。
 *
 * 所有动作接口的返回都是 Result<Void>,失败原因(状态机不允许、无权限、乐观锁冲突)
 * 由 request 层抛成 ApiError,页面直接展示 message 即可。
 */
import { request } from '@/utils/request'
import type {
  PageResult,
  WorkOrderDetailVO,
  WorkOrderQuery,
  WorkOrderStats,
  WorkOrderVO,
} from '@/types/domain'

/** 创建 / 重新提交工单的表单,对应后端 WorkOrderCreateDTO */
export interface WorkOrderSavePayload {
  title: string
  content?: string | null
  orderType: number
  priority: number
  /**
   * 超时时间,ISO-8601(`2026-09-14T20:49:24`);不传则后端默认 72 小时后。
   * ⚠️ 不能用空格分隔的 `2026-09-14 20:49:24`,后端会直接 400。
   */
  expireTime?: string | null
  resources?: WorkOrderResourcePayload[]
}

export interface WorkOrderResourcePayload {
  resourceType: string
  resourceName: string
  quantity: number
  unit?: string | null
  remark?: string | null
}

/** 工单分页列表;数据范围由后端按角色自动收敛 */
export function pageWorkOrders(query: WorkOrderQuery): Promise<PageResult<WorkOrderVO>> {
  return request<PageResult<WorkOrderVO>>('/workorder/page', {
    query: {
      current: query.current,
      size: query.size,
      status: query.status,
      orderType: query.orderType,
      keyword: query.keyword,
    },
  })
}

/**
 * 工单统计:总数与各状态数量,数据范围与列表一致。
 * 刻意不接受 status —— 状态筛选条各档的数量必须在切换状态时保持稳定。
 */
export function fetchWorkOrderStats(params?: {
  orderType?: number | null
  keyword?: string
}): Promise<WorkOrderStats> {
  return request<WorkOrderStats>('/workorder/stats', {
    query: { orderType: params?.orderType, keyword: params?.keyword },
  })
}

/** 工单详情,含资源明细与操作日志 */
export function getWorkOrder(id: number): Promise<WorkOrderDetailVO> {
  return request<WorkOrderDetailVO>(`/workorder/${id}`)
}

/** 创建工单,返回新工单ID */
export function createWorkOrder(payload: WorkOrderSavePayload): Promise<number> {
  return request<number>('/workorder', { method: 'POST', body: payload })
}

/** 重新提交:被驳回的工单修改后重投,回到「待审核」 */
export function resubmitWorkOrder(id: number, payload: WorkOrderSavePayload): Promise<void> {
  return request<void>(`/workorder/${id}/resubmit`, { method: 'POST', body: payload })
}

/** 审核:approved=true → 待派单;false → 已驳回 */
export function reviewWorkOrder(
  id: number,
  body: { approved: boolean; remark?: string | null },
): Promise<void> {
  return request<void>(`/workorder/${id}/review`, { method: 'POST', body })
}

/**
 * 派单 / 转派。
 * 同一入口按工单当前状态区分:待派单 → 派单(需 workorder:dispatch),
 * 处理中 → 转派(需 workorder:transfer)。判权在后端 Service 内完成。
 */
export function dispatchWorkOrder(
  id: number,
  body: { handlerId: number; remark?: string | null },
): Promise<void> {
  return request<void>(`/workorder/${id}/dispatch`, { method: 'POST', body })
}

/** 处理完成:处理中 → 待验收 */
export function processWorkOrder(
  id: number,
  body: { remark?: string | null },
): Promise<void> {
  return request<void>(`/workorder/${id}/process`, { method: 'POST', body })
}

/** 验收:approved=true → 已完成;false → 退回处理中 */
export function acceptWorkOrder(
  id: number,
  body: { approved: boolean; remark?: string | null },
): Promise<void> {
  return request<void>(`/workorder/${id}/accept`, { method: 'POST', body })
}

/** 撤回 / 取消(仅提单人本人) */
export function withdrawWorkOrder(
  id: number,
  body: { remark?: string | null },
): Promise<void> {
  return request<void>(`/workorder/${id}/withdraw`, { method: 'POST', body })
}
