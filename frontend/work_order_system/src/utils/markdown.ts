/**
 * Markdown 渲染
 *
 * 只建一个 MarkdownIt 实例(解析器本身无状态,复用比每次 new 更快)。
 *
 * 安全基线:`html: false` 让模型返回的原始 HTML 全部按文本转义,
 * 不引入 DOMPurify 也不会执行 `<script>` / `<img onerror>`。
 */
import MarkdownIt from 'markdown-it'

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

/** 渲染一段 Markdown 为 HTML */
export function renderMarkdown(source: string): string {
  return md.render(source)
}
