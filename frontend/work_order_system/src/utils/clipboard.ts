/**
 * 复制文本到剪贴板
 *
 * `navigator.clipboard` 只在安全上下文(localhost / https)可用,
 * 局域网 http 部署时会直接拒绝,所以保留一条 execCommand 的降级路径。
 */
export async function copyText(text: string): Promise<boolean> {
  if (!text) return false

  // 步骤 1:优先用现代 API
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    // 权限被拒 / 非安全上下文,继续走降级
  }

  // 步骤 2:隐藏 textarea + execCommand(兼容旧环境)
  try {
    const textarea = document.createElement('textarea')
    textarea.value = text
    textarea.setAttribute('readonly', '')
    // fixed + 屏幕外,避免复制时页面滚动或闪烁
    textarea.style.position = 'fixed'
    textarea.style.top = '-9999px'
    textarea.style.opacity = '0'
    document.body.appendChild(textarea)

    // 记下原选区,复制完恢复,不打断用户正在选择的内容
    const selection = document.getSelection()
    const previousRange =
      selection && selection.rangeCount > 0 ? selection.getRangeAt(0) : null

    textarea.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(textarea)

    if (previousRange && selection) {
      selection.removeAllRanges()
      selection.addRange(previousRange)
    }

    return ok
  } catch {
    return false
  }
}
