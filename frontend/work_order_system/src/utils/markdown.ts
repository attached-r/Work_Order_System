/**
 * Markdown 渲染
 *
 * 只建一个 MarkdownIt 实例(解析器本身无状态,复用比每次 new 更快)。
 *
 * 安全基线:`html: false` 让模型返回的原始 HTML 全部按文本转义,
 * 不引入 DOMPurify 也不会执行 `<script>` / `<img onerror>`。
 */
import MarkdownIt from 'markdown-it'
import { renderWorkOrderRefs } from '@/utils/workOrderRef'

/** 渲染开关;默认全部关闭,保证 Markdown 的默认行为不变 */
export interface RenderMarkdownOptions {
  /** 是否把回答里的工单引用包成可点击链接 */
  workOrderLinks?: boolean
}

/**
 * 渲染期间在 token 之间传递的上下文,目前只需要知道「是否位于一个链接内部」。
 * 必须带索引签名:markdown-it 的 render(src, env) 接受 `Record<string, any>`。
 */
interface RenderEnv {
  [key: string | symbol]: unknown
  workOrderLinks?: boolean
  inLink?: boolean
}

const md = new MarkdownIt({
  html: false,
  linkify: true,
  // 模型经常用单个换行分段,breaks 让渲染结果更接近它的本意
  breaks: true,
  typographer: false,
})

// ---------------------------------------------------------------------------
// 链接:禁止可执行协议,外链一律新窗口打开
// ---------------------------------------------------------------------------

const defaultValidateLink = md.validateLink.bind(md)

md.validateLink = (url: string): boolean => {
  // 明确拦掉脚本类协议;其余(相对路径、锚点、http/https/mailto)交给默认校验
  if (/^(javascript|vbscript|data|file):/i.test(url.trim())) return false
  return defaultValidateLink(url)
}

const defaultLinkOpen = md.renderer.rules.link_open

md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  // 标记「已经在一个链接里了」:text 渲染器据此跳过工单链接化,
  // 否则 markdown 自带的 http 链接、linkify 出来的裸 URL 会被再包一层 <a>
  if (env && typeof env === 'object') (env as RenderEnv).inLink = true

  const token = tokens[idx]
  // markdown-it v15 的 attrGet 可能返回 string | number | null,统一收敛成字符串
  const rawHref = token?.attrGet('href')
  const href = typeof rawHref === 'string' ? rawHref : ''

  // 只有真正的站外链接才加 target,站内锚点保持当前页跳转
  if (/^https?:\/\//i.test(href) && token) {
    token.attrSet('target', '_blank')
    token.attrSet('rel', 'noopener noreferrer')
  }

  return defaultLinkOpen
    ? defaultLinkOpen(tokens, idx, options, env, self)
    : self.renderToken(tokens, idx, options)
}

// 链接结束后恢复上下文;renderToken 负责输出 </a>,这里只补状态
const defaultLinkClose = md.renderer.rules.link_close

md.renderer.rules.link_close = (tokens, idx, options, env, self) => {
  if (env && typeof env === 'object') (env as RenderEnv).inLink = false
  return defaultLinkClose
    ? defaultLinkClose(tokens, idx, options, env, self)
    : self.renderToken(tokens, idx, options)
}

// ---------------------------------------------------------------------------
// 文本节点:可选地把工单引用变成链接
// ---------------------------------------------------------------------------

/**
 * Markdown 的 text token 覆盖了所有「非代码」的普通文字,
 * 而 code_inline / fence 是独立 token —— 因此在这里做替换,代码块天然免疫。
 *
 * 关闭开关时仍走默认的 escapeHtml,保证 `html: false` 的安全基线不被破坏。
 */
md.renderer.rules.text = (tokens, idx, options, env) => {
  const content = tokens[idx]?.content ?? ''
  const context = env as RenderEnv | null

  if (!context?.workOrderLinks || context.inLink) {
    return md.utils.escapeHtml(content)
  }
  return renderWorkOrderRefs(content)
}

// ---------------------------------------------------------------------------
// 代码块:加一条「语言 + 复制」工具条
// ---------------------------------------------------------------------------

md.renderer.rules.fence = (tokens, idx) => {
  const token = tokens[idx]
  if (!token) return ''

  // info 形如 "ts title=..." ;只取第一段并过滤成合法的 class 片段
  const info = token.info.trim().split(/\s+/)[0] ?? ''
  const lang = info.replace(/[^A-Za-z0-9_+-]/g, '')
  const label = lang || '代码'

  // 代码内容必须转义,否则模型输出里的 < > & 会破坏结构
  const code = md.utils.escapeHtml(token.content)
  const langClass = lang ? ` class="language-${lang}"` : ''

  // 复制按钮靠 AgentMarkdown 的事件委托处理(v-html 内容绑不了 Vue 事件)
  return (
    '<div class="md-code">' +
    '<div class="md-code__bar">' +
    `<span class="md-code__lang">${md.utils.escapeHtml(label)}</span>` +
    '<button type="button" class="md-code__copy" data-copy-code>复制</button>' +
    '</div>' +
    `<pre class="md-code__pre"><code${langClass}>${code}</code></pre>` +
    '</div>\n'
  )
}

/**
 * 渲染一段 Markdown 为 HTML。
 *
 * 每个调用传入独立的 env 对象:渲染器会往里面写 inLink,
 * 若复用同一个对象,上一段回答的链接状态可能漏到下一段。
 */
export function renderMarkdown(source: string, options: RenderMarkdownOptions = {}): string {
  const env: RenderEnv = {
    workOrderLinks: options.workOrderLinks === true,
    inLink: false,
  }
  return md.render(source, env)
}
