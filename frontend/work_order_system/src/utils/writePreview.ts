/**
 * 写操作预览识别（M4）
 *
 * 背景：
 *   M4 的 agent 仍然只通过 SSE 输出「文本 token」，没有结构化的确认事件。
 *   mock 模式会把写工具返回的 PREVIEW JSON 原样拼进回答；真实模型模式则由
 *   模型用自己的话复述预览，并按系统提示询问「是否确认执行」。
 *
 * 因此这里做两件事：
 *   1. 优先从回答里提取 PREVIEW JSON（拿到工单号、标题、前后状态等，用于展示）；
 *   2. 提取不到 JSON 时，退化为「是否询问用户确认执行」的文本识别。
 *
 * 注意：这里只负责「让界面知道可以显示确认按钮」，不解析、不展示、不传输
 * confirmationToken —— 令牌仍由 agent 自己保管，用户点击确认时只发送一句
 * 「确认执行」，由 agent 用预览时保存的令牌完成提交。
 */

/** 从 PREVIEW JSON 中投影出的展示字段（全部可选，缺什么就不展示什么） */
export interface WritePreview {
  /** 工单内部 ID，用于「查看工单」入口 */
  orderId?: number
  /** 展示编号 WO + 14 位数字 */
  orderNo?: string
  /** 工单标题 */
  title?: string
  /** 预览时观察到的当前状态 */
  status?: number
  /** 当前状态中文名（后端预览里已给出，优先用它） */
  statusDesc?: string
  /** 动作：REVIEW / DISPATCH / TRANSFER / PROCESS / ACCEPT / WITHDRAW */
  action?: string
  /** 动作完成后的目标状态 */
  targetStatus?: number
  /** 目标状态中文名 */
  targetStatusDesc?: string
  /** 审核/验收结论：true 通过，false 驳回/退回 */
  approved?: boolean
  /** 派单/转派的处理人 ID */
  handlerId?: number
  /** 备注/说明 */
  remark?: string
  /** 令牌有效期（秒） */
  expiresInSeconds?: number
}

export interface WritePreviewInfo {
  /**
   * true = 命中了结构化 PREVIEW JSON（mock 模式）；
   * false = 仅通过自然语言判断「助手正在等待确认」（真实模型模式）。
   */
  structured: boolean
  data: WritePreview
}

/** 动作码 -> 中文名；只用于展示，找不到就原样返回动作码 */
const ACTION_LABEL: Record<string, string> = {
  REVIEW: '审核',
  DISPATCH: '派单',
  TRANSFER: '转派',
  PROCESS: '处理完成',
  ACCEPT: '验收',
  WITHDRAW: '撤回 / 取消',
}

export function getWriteActionLabel(action: string | null | undefined): string {
  if (!action) return '工单操作'
  return ACTION_LABEL[action] ?? action
}

/**
 * 从 `start`（必须指向 `{`）开始找与之配对的 `}`。
 *
 * 必须识别字符串与转义，否则标题里的 `}`、备注里的 `\"` 都会让括号计数错位。
 * 找不到配对返回 -1。
 */
function findMatchingBrace(text: string, start: number): number {
  let depth = 0
  let inString = false
  let escaped = false

  for (let i = start; i < text.length; i += 1) {
    const char = text[i]

    if (inString) {
      if (escaped) {
        escaped = false
      } else if (char === '\\') {
        escaped = true
      } else if (char === '"') {
        inString = false
      }
      continue
    }

    if (char === '"') {
      inString = true
    } else if (char === '{') {
      depth += 1
    } else if (char === '}') {
      depth -= 1
      if (depth === 0) return i
    }
  }

  return -1
}

/** 判断一个解析结果是不是写操作预览，而不是普通工单详情/错误体 */
function isPreviewObject(value: unknown): value is Record<string, unknown> {
  if (typeof value !== 'object' || value === null) return false
  const record = value as Record<string, unknown>

  // mock 的预览固定带 mode=PREVIEW；再要求一个能证明「已签发令牌」的字段，
  // 避免把模型自己编的 JSON 误当成可确认的预览。
  if (record.mode === 'PREVIEW' && typeof record.confirmationToken === 'string') {
    return true
  }

  // 兼容后端字段改名或模型精简输出的情况：同时有观察状态与目标状态即可。
  return typeof record.observedFromStatus === 'number' && typeof record.targetStatus === 'number'
}

function numberOrUndefined(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

function stringOrUndefined(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined
}

function booleanOrUndefined(value: unknown): boolean | undefined {
  return typeof value === 'boolean' ? value : undefined
}

/** 把 PREVIEW JSON 投影成界面需要的字段（token 刻意不取） */
function toPreviewData(record: Record<string, unknown>): WritePreview {
  const order =
    typeof record.order === 'object' && record.order !== null
      ? (record.order as Record<string, unknown>)
      : {}
  const intent =
    typeof record.intent === 'object' && record.intent !== null
      ? (record.intent as Record<string, unknown>)
      : {}

  return {
    orderId: numberOrUndefined(order.id),
    orderNo: stringOrUndefined(order.orderNo),
    title: stringOrUndefined(order.title),
    status: numberOrUndefined(order.status),
    statusDesc: stringOrUndefined(order.statusDesc),
    action: stringOrUndefined(record.action),
    targetStatus: numberOrUndefined(record.targetStatus),
    targetStatusDesc: stringOrUndefined(record.targetStatusDesc),
    approved: booleanOrUndefined(intent.approved),
    handlerId: numberOrUndefined(intent.handlerId),
    remark: stringOrUndefined(intent.remark),
    expiresInSeconds: numberOrUndefined(record.expiresInSeconds),
  }
}

/**
 * 尝试从文本里找出 PREVIEW JSON。
 *
 * 先定位特征字段（mode=PREVIEW 或 confirmationToken），再往前找最近的 `{`，
 * 避免对整段长文本的每个左括号都做一次括号扫描。
 */
function parseStructuredPreview(content: string): WritePreview | null {
  const markerIndexes = [
    content.search(/"mode"\s*:\s*"PREVIEW"/),
    content.search(/"confirmationToken"\s*:/),
  ].filter((index) => index >= 0)

  for (const markerIndex of markerIndexes) {
    // 从特征字段往前找左括号：预览对象是纯 JSON，起始括号不会离标记太远
    for (let start = markerIndex; start >= 0; start -= 1) {
      if (content[start] !== '{') continue

      const end = findMatchingBrace(content, start)
      if (end < markerIndex) continue

      try {
        const parsed: unknown = JSON.parse(content.slice(start, end + 1))
        if (isPreviewObject(parsed)) {
          return toPreviewData(parsed)
        }
      } catch {
        // 这个左括号不是 JSON 起点（例如正文里的花括号），继续往前找
      }
    }
  }

  return null
}

/**
 * 自然语言兜底：助手是否正在等用户确认。
 *
 * 只认系统提示词要求的确认话术（「是否确认执行」等），并额外要求出现
 * 工单引用与写动作词，尽量不把「写操作需要确认」这类规则解释误判成预览。
 */
function looksLikeConfirmationAsk(content: string): boolean {
  const asksConfirm =
    /是否确认执行|确认执行吗|请确认执行|回复[「“"]?确认执行|确认执行本次|确认执行该/.test(content)
  if (!asksConfirm) return false

  const mentionsOrder = /工单\s*(?:ID|id|编号)?|WO\d{8,}/.test(content)
  const mentionsAction =
    /审核|派单|转派|处理完成|处理完毕|验收|撤回|取消/.test(content)

  return mentionsOrder && mentionsAction
}

/**
 * 识别最后一条助手回答是否是一个「等待确认的写操作预览」。
 *
 * @returns null 表示不展示确认按钮；否则返回展示所需信息。
 */
export function extractWritePreview(content: string): WritePreviewInfo | null {
  if (!content) return null

  // 步骤 1：优先解析结构化预览（mock 模式）
  const structured = parseStructuredPreview(content)
  if (structured) {
    return { structured: true, data: structured }
  }

  // 步骤 2：真实模型模式只有自然语言，退化为话术识别
  if (looksLikeConfirmationAsk(content)) {
    return { structured: false, data: {} }
  }

  return null
}
