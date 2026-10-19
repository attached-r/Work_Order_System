/**
 * 工单引用识别
 *
 * 助手回答里的工单有两种写法:
 *   1. 展示编号 `WO20261018000001`(WO + 8 位日期 + 6 位序号);
 *   2. 内部数字 ID,通常写成 `#123` 或「工单 ID 123」。
 *
 * 识别刻意保守 —— 回答里出现「工单有 3 种状态」时不能把 `3` 当成工单 ID,
 * 所以只认「明确标识」的形式,宁可漏掉也不误伤。
 *
 * 产出的 `<a>` 交给 Markdown 的 text 渲染器使用;左键点击由 AgentMarkdown
 * 的事件委托接管(SPA 跳转),href 只作为中键/新窗口打开的兜底。
 */

/** 点击一条工单引用时交给页面层的数据;id 与 orderNo 二选一 */
export interface WorkOrderRef {
  id?: number
  orderNo?: string
}

/**
 * 一段文本里所有可识别的工单引用。
 *
 * 用「一个」带命名分组的全局正则,而不是叠加多条全局正则:
 * 全局正则的 lastIndex 是跨调用共享的,混用 `test` / `replace` 很容易漏匹配。
 *
 * 分组含义:
 *   - orderNo: `WO` + 14 位数字,置信度最高;
 *   - hashId: `#123`,前后不能是字母/数字/#,排除 `C#12`、`SN#123` 这类写法;
 *   - labelId: 「工单 ID/编号 123」里的数字。
 *
 * 所有数字分支都要求后面不再跟字母/数字,避免把 14 位编号截成前 9 位。
 */
const REF_RE =
  /(?<orderNo>\bWO\d{14}\b)|(?<![\p{L}\p{N}#])#(?<hashId>\d{1,9})(?![\p{L}\p{N}])|(?<label>工单\s*(?:ID|id|编号)\s*[:：]?\s*)(?<labelId>\d{1,9})(?![\p{L}\p{N}])/gu

/**
 * Markdown 文本节点转义。
 *
 * 这里不 import markdown.ts 的 md 实例,否则两个模块会循环依赖;
 * text 渲染器只要求转义 `& < > "` 这四个会破坏 HTML 结构的字符。
 */
function escapeHtml(text: string): string {
  return text.replace(/[&<>"]/g, (char) => {
    switch (char) {
      case '&':
        return '&amp;'
      case '<':
        return '&lt;'
      case '>':
        return '&gt;'
      default:
        return '&quot;'
    }
  })
}

/** 生成一个可点击的工单链接;id 与 orderNo 都是数字/固定格式,仍统一转义 */
function buildLink(options: {
  text: string
  orderNo?: string
  id?: string
}): string {
  const { text, orderNo, id } = options
  if (orderNo) {
    return (
      `<a class="md-workorder" data-work-order-no="${escapeHtml(orderNo)}"` +
      ` href="/workorder?keyword=${encodeURIComponent(orderNo)}">${escapeHtml(text)}</a>`
    )
  }
  return (
    `<a class="md-workorder" data-work-order-id="${escapeHtml(id ?? '')}"` +
    ` href="/workorder/${escapeHtml(id ?? '')}">${escapeHtml(text)}</a>`
  )
}

/**
 * 把一段纯文本转成 HTML:普通文字原样转义,工单引用包成 `<a>`。
 *
 * 只处理 Markdown 的 text token;代码块 / 行内代码走的是别的 token,
 * 所以代码里的 `#123` 不会被误链接。
 */
export function renderWorkOrderRefs(text: string): string {
  if (!text) return ''

  let html = ''
  let cursor = 0

  // matchAll 会基于原正则克隆一个新实例,不会污染 REF_RE.lastIndex
  for (const match of text.matchAll(REF_RE)) {
    const index = match.index ?? 0
    // 步骤 1:先补上两个引用之间的普通文字
    html += escapeHtml(text.slice(cursor, index))

    // 步骤 2:按命中的分组生成对应的链接
    const groups = match.groups ?? {}
    if (groups.orderNo) {
      html += buildLink({ text: match[0], orderNo: groups.orderNo })
    } else if (groups.hashId) {
      html += buildLink({ text: match[0], id: groups.hashId })
    } else if (groups.labelId) {
      // 「工单 ID 123」整段作为链接文字,比只链接数字更好点
      html += buildLink({ text: match[0], id: groups.labelId })
    } else {
      // 理论上不会走到这里;真出现异常命中就按普通文本处理
      html += escapeHtml(match[0])
    }

    cursor = index + match[0].length
  }

  // 步骤 3:补上最后一段普通文字
  return html + escapeHtml(text.slice(cursor))
}
