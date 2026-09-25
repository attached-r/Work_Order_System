/**
 * 通知领域常量
 *
 * 通知的类型码**就是**工单操作事件码(后端落库时写的是 OperateType),所以这里
 * 不再抄一份中文表 —— 直接借 constants/workorder.ts 的 getOperateLabel,两边永远一致。
 * 本文件只补通知独有的两件事:标签查询的兜底策略、类型到配色的映射。
 */

import { getOperateLabel } from '@/constants/workorder'

/** 读取状态筛选值;顺带充当 el-select 的选项来源 */
export interface ReadFlagOption {
  /** null = 不限 */
  value: number | null
  label: string
}

export const READ_FLAG_OPTIONS: ReadFlagOption[] = [
  { value: null, label: '全部' },
  { value: 0, label: '未读' },
  { value: 1, label: '已读' },
]

/**
 * 类型 -> 中文名。
 *
 * 优先用后端渲染好的 notifyTypeDesc(它跟着后端枚举走,后端加了新类型前端不用改),
 * 只有后端回 null 时(遇到未知码,后端刻意容错)才回落到本地枚举表。
 */
export function getNotifyTypeLabel(code: number | null | undefined, desc?: string | null): string {
  if (desc) return desc
  return getOperateLabel(code)
}

/**
 * 类型 -> CSS 变量名后缀,取值仍是 tokens.scss 里的 --wo-st-{n}-*。
 *
 * 刻意不为通知另开一套颜色:同一批事件在工单详情的时间轴上已经是这些色,
 * 通知中心换个色反而会让人以为是两码事。绿的归"推进",红的归"退回",橙的归"时限"。
 */
const NOTIFY_TONE: Record<number, string> = {
  1: '1', // 提交      蓝
  2: '4', // 审核通过   绿
  3: '5', // 审核驳回   红
  4: '2', // 派单      紫
  5: '4', // 处理完成   绿
  6: '4', // 验收通过   绿
  7: '7', // 验收退回   橙
  8: '2', // 转派      紫
  9: '6', // 撤回/取消  灰
  10: '7', // 超时关闭  橙
}

/** 未知类型统一落到 6 号灰,不抛错也不留空白 */
export function getNotifyTone(code: number | null | undefined): string {
  if (code === null || code === undefined) return '6'
  return NOTIFY_TONE[code] ?? '6'
}
