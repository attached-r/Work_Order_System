/**
 * 对话导出
 *
 * 不引入第三方库:把会话拼成 Markdown 字符串,再用 Blob + 临时 <a download> 下载。
 * 导出的是「当前快照」——正在生成的回答只包含已经收到的部分,这是刻意的:
 * 用户点导出时想拿到的就是屏幕上此刻的内容。
 */
import { formatTime } from '@/utils/datetime'
import type { Conversation } from '@/stores/agent'

/** 文件名里不能出现的字符;Windows 的限制最严格,按它来 */
const INVALID_FILENAME_CHARS = /[\\/:*?"<>|]/g

function pad(n: number): string {
  return n < 10 ? `0${n}` : String(n)
}

/** `20261019-1432`:文件名用紧凑格式,避免空格和冒号 */
function fileStamp(now: Date): string {
  return (
    `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}` +
    `-${pad(now.getHours())}${pad(now.getMinutes())}`
  )
}

/** 会话内容转 Markdown;空内容的消息跳过,状态异常补一行引用说明 */
export function buildConversationMarkdown(conversation: Conversation): string {
  const lines: string[] = [
    `# ${conversation.title}`,
    '',
    `> 导出时间：${formatTime(new Date().toISOString(), true)}`,
    `> 消息数：${conversation.messages.length}`,
    '',
  ]

  for (const message of conversation.messages) {
    const who = message.role === 'user' ? '我' : '工单 Agent'
    const time = formatTime(new Date(message.createdAt).toISOString(), true)

    lines.push(`## ${who} · ${time}`, '')

    if (message.content) lines.push(message.content, '')

    if (message.status === 'error') {
      lines.push(`> 生成失败：${message.error || '未知错误'}`, '')
    } else if (message.status === 'stopped') {
      lines.push('> 已停止生成', '')
    }
  }

  return lines.join('\n')
}

/** 生成并触发下载 */
export function exportConversationAsMarkdown(conversation: Conversation): void {
  const markdown = buildConversationMarkdown(conversation)

  // BOM 让 Windows 记事本也能正确识别 UTF-8,避免中文乱码
  const blob = new Blob([`﻿${markdown}`], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)

  // 按字符切,避免把 emoji 的代理对劈开
  const safeTitle =
    Array.from(conversation.title.trim().replace(INVALID_FILENAME_CHARS, '-'))
      .slice(0, 40)
      .join('') || '对话'
  const filename = `agent-chat-${safeTitle}-${fileStamp(new Date())}.md`

  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.style.display = 'none'
  document.body.appendChild(link)
  link.click()
  link.remove()

  // 下载触发后立即释放,避免 Blob 常驻内存
  URL.revokeObjectURL(url)
}
