/**
 * 智能助手（项目二）M4 写操作常量
 *
 * M4 把「审核 / 派单 / 转派 / 处理完成 / 验收 / 撤回」六个写动作接进了 agent，
 * 但仍然只走 `GET /api/agent/chat` 这一条 SSE 文本流，没有独立的确认事件。
 * 因此前端能做的适配是「让用户看清、点得动」：
 *   - 按权限展示可用的写操作建议；
 *   - 识别助手回答里的预览，在对话内提供「确认执行」按钮；
 *   - 永远不自动发送确认，也不在前端判断能不能写。
 *
 * 真正的权限、数据范围、状态机与并发校验仍然全部由项目一裁决。
 */

/**
 * 对话内确认的触发词。
 *
 * 默认值与 agent 侧 `agent.write.mock-execute-keyword` 一致（mock 模式用它区分
 * 「闲聊里的确认」和「真正提交」）；真实模型路径下，它就是一条普通的用户消息，
 * 模型据此带令牌再次调用写工具。
 *
 * 不要改成「确认」：mock 会刻意忽略单独的「确认」，避免误写。
 */
export const WRITE_CONFIRM_KEYWORD = '确认执行'

/** M4 开放的六个写权限码（顺序与工单流转一致） */
export const WRITE_PERMISSIONS = [
  'workorder:review',
  'workorder:dispatch',
  'workorder:transfer',
  'workorder:process',
  'workorder:accept',
  'workorder:withdraw',
] as const

/** 写动作标识，与后端 ToolWorkOrderAction / agent 工具名一一对应 */
export type AgentWriteActionKey =
  | 'review'
  | 'dispatch'
  | 'transfer'
  | 'process'
  | 'accept'
  | 'withdraw'

export interface AgentWriteAction {
  key: AgentWriteActionKey
  /** 动作名（按钮/卡片标题） */
  label: string
  /** 所需权限码 */
  perm: string
  /** 前置状态，仅用于提示；最终由项目一判定 */
  precondition: string
  /** 卡片上的一句话说明 */
  hint: string
  /**
   * 点击后预填到输入框的内容。
   *
   * 刻意不带真实工单 ID：写操作必须由用户明确指定目标，
   * 预填一个示例数字容易让用户直接回车误改到别的工单。
   * 用户补全 ID 后才会发送，mock/模型解析不到 ID 时也只会要求补充。
   */
  prompt: string
}

/**
 * 六个写动作的展示元数据。
 *
 * 顺序即界面展示顺序：审核 → 派单 → 转派 → 处理完成 → 验收 → 撤回。
 * 与 `constants/workorder.ts` 的 availableActions 不同：这里不依赖具体工单状态，
 * 只按权限决定「助手能替我做什么」，具体某张单是否可执行仍由项目一裁决。
 */
export const AGENT_WRITE_ACTIONS: AgentWriteAction[] = [
  {
    key: 'review',
    label: '审核工单',
    perm: 'workorder:review',
    precondition: '待审核',
    hint: '通过或驳回，助手会先展示预览',
    prompt: '帮我审核通过工单（请补充工单 ID）：',
  },
  {
    key: 'dispatch',
    label: '派单',
    perm: 'workorder:dispatch',
    precondition: '待派单',
    hint: '指派处理人，需先确认候选人',
    // 必须出现「派单」：mock 靠这个词识别 DISPATCH；「派给」只用于提取处理人姓名
    prompt: '请帮我派单：把工单（请补充工单 ID）派给 ',
  },
  {
    key: 'transfer',
    label: '转派',
    perm: 'workorder:transfer',
    precondition: '处理中',
    hint: '更换处理人，工单状态不变',
    prompt: '把工单（请补充工单 ID）转派给 ',
  },
  {
    key: 'process',
    label: '处理完成',
    perm: 'workorder:process',
    precondition: '处理中',
    hint: '提交处理结果，流转到待验收',
    prompt: '工单（请补充工单 ID）已处理完成',
  },
  {
    key: 'accept',
    label: '验收工单',
    perm: 'workorder:accept',
    precondition: '待验收',
    hint: '通过或退回，助手会先展示预览',
    prompt: '帮我验收通过工单（请补充工单 ID）',
  },
  {
    key: 'withdraw',
    label: '撤回 / 取消',
    perm: 'workorder:withdraw',
    precondition: '待审核 / 待派单 / 处理中 / 已驳回',
    hint: '撤回后工单进入已取消',
    prompt: '帮我撤回工单（请补充工单 ID）',
  },
]

/** 当前权限下助手可执行的写动作（顺序保持不变） */
export function getWritableActions(perms: string[]): AgentWriteAction[] {
  return AGENT_WRITE_ACTIONS.filter((action) => perms.includes(action.perm))
}
