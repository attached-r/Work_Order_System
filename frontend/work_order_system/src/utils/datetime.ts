/**
 * 时间格式化工具
 *
 * 后端 LocalDateTime 的序列化格式尚未最终确定(可能是 ISO 的 `2026-09-11T14:32:10`,
 * 也可能是 `2026-09-11 14:32:10`),所以这里统一做「宽容解析 + 统一输出」,
 * 前端各处只调这几个函数,等接口定下来后只改这一处。
 */

/** 把后端的各种时间写法解析成 Date;解析不了返回 null */
export function parseTime(value: string | null | undefined): Date | null {
  if (!value) return null
  // 兼容 "2026-09-11 14:32:10":部分浏览器对空格分隔的写法解析不一致
  const normalized = value.includes('T') ? value : value.replace(' ', 'T')
  const d = new Date(normalized)
  return Number.isNaN(d.getTime()) ? null : d
}

function pad(n: number): string {
  return n < 10 ? `0${n}` : String(n)
}

/** 统一格式化为 `2026-09-11 14:32` */
export function formatTime(value: string | null | undefined, withSeconds = false): string {
  const d = parseTime(value)
  if (!d) return '—'
  const base = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
  return withSeconds ? `${base}:${pad(d.getSeconds())}` : base
}

/** 只要日期部分 `2026-09-11` */
export function formatDate(value: string | null | undefined): string {
  const d = parseTime(value)
  if (!d) return '—'
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/** 只要时分 `14:32` */
export function formatClock(value: string | null | undefined): string {
  const d = parseTime(value)
  if (!d) return '—'
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/**
 * 相对时间:`3 分钟前` / `2 小时前` / `5 天前`,超过 30 天回落到绝对日期。
 * 列表里用它比绝对时间更容易扫读。
 */
export function formatRelative(value: string | null | undefined): string {
  const d = parseTime(value)
  if (!d) return '—'

  const diff = Date.now() - d.getTime()
  const min = 60_000
  const hour = 60 * min
  const day = 24 * hour

  if (diff < 0) return formatTime(value) // 未来时间(比如超时时间)直接显示绝对时间
  if (diff < min) return '刚刚'
  if (diff < hour) return `${Math.floor(diff / min)} 分钟前`
  if (diff < day) return `${Math.floor(diff / hour)} 小时前`
  if (diff < 30 * day) return `${Math.floor(diff / day)} 天前`
  return formatDate(value)
}

/**
 * 距超时时间的剩余时长描述。
 * 返回 tone 供组件决定配色:overdue 已超时 / urgent 24h 内 / normal 正常 / none 无期限。
 */
export function formatRemaining(expireTime: string | null | undefined): {
  text: string
  tone: 'overdue' | 'urgent' | 'normal' | 'none'
} {
  const d = parseTime(expireTime)
  if (!d) return { text: '不限', tone: 'none' }

  const diff = d.getTime() - Date.now()
  const hour = 3_600_000
  const day = 24 * hour

  if (diff < 0) {
    const over = -diff
    return {
      text: over < day ? `已超时 ${Math.floor(over / hour)} 小时` : `已超时 ${Math.floor(over / day)} 天`,
      tone: 'overdue',
    }
  }

  if (diff < hour) return { text: `${Math.floor(diff / 60_000)} 分钟后到期`, tone: 'urgent' }
  if (diff < day) return { text: `${Math.floor(diff / hour)} 小时后到期`, tone: 'urgent' }
  return { text: `${Math.floor(diff / day)} 天后到期`, tone: 'normal' }
}

/** 生成相对当前时间偏移若干小时的字符串,供 mock 数据构造使用 */
export function hoursAgo(hours: number): string {
  const d = new Date(Date.now() - hours * 3_600_000)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}
