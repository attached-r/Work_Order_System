/**
 * 未读通知与顶栏轮询
 *
 * 后端没有 WebSocket / SSE(消息走 RabbitMQ 落库,只保证"送达",不保证"推给浏览器"),
 * 所以前端只能用轮询。轮的是 /notification/unread-count —— 它由 Redis 计数直接回答,
 * 未命中才查库,60s 一次的开销可以忽略。
 *
 * 这里只放「跨组件共享」的那点状态:未读数 + 最近几条未读(顶栏悬浮层要用)。
 * 收件箱的整页列表由页面自己管,不进 store —— 那是页面级的一次性数据,
 * 塞进来只会多一份要同步的副本。
 */

import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import {
  fetchUnreadCount,
  markAllNotificationsRead,
  markNotificationRead,
  pageNotifications,
} from '@/api'
import type { NotificationVO } from '@/types/domain'

/** 轮询间隔。后端计数带 300s 的 Redis 缓存,再密也拿不到更新鲜的数字 */
const POLL_INTERVAL_MS = 60_000

/** 顶栏悬浮层一次列几条 */
const POPOVER_SIZE = 5

export const useNotificationStore = defineStore('notification', () => {
  const unreadCount = ref(0)
  const recent = ref<NotificationVO[]>([])
  const loadingRecent = ref(false)

  let timer: ReturnType<typeof setInterval> | null = null

  const hasUnread = computed(() => unreadCount.value > 0)

  /**
   * 只拉未读数。失败静默 —— 轮询不该因为一次网络抖动就弹提示打断用户,
   * 保留上一次的数字,下一轮再试。
   */
  async function refreshUnread(): Promise<void> {
    try {
      unreadCount.value = await fetchUnreadCount()
    } catch {
      // 故意吞掉:见上方注释
    }
  }

  /**
   * 拉最近几条未读。readFlag=0 时返回的 total 就是未读总数,
   * 所以这一次请求顺手把红点数字也校准了,不用再调 unread-count。
   */
  async function loadRecent(): Promise<void> {
    loadingRecent.value = true
    try {
      const res = await pageNotifications({ current: 1, size: POPOVER_SIZE, readFlag: 0 })
      recent.value = res.records
      unreadCount.value = res.total
    } catch {
      // 悬浮层自己会显示空态,不额外提示
    } finally {
      loadingRecent.value = false
    }
  }

  /**
   * 标记单条已读,并把本地状态一起改掉 —— 一次点击不该为了红点再跑一趟接口。
   *
   * @param wasUnread 该条当前是否未读。必须由调用方给(它手上就有 readFlag):
   *        后端对已读的再标记是幂等的,未读数不会变,这里若减了就永远少一个。
   */
  async function markRead(id: number, wasUnread: boolean): Promise<void> {
    await markNotificationRead(id)
    if (!wasUnread) return
    recent.value = recent.value.filter((n) => n.id !== id)
    unreadCount.value = Math.max(0, unreadCount.value - 1)
  }

  /**
   * 全部已读。
   * @returns 后端真正标记的条数;本来就是 0 未读时返回 0,由调用方决定提示文案
   */
  async function markAllRead(): Promise<number> {
    const marked = await markAllNotificationsRead()
    // 本地的未读集合已整体失效,直接清空比逐条推算可靠
    unreadCount.value = 0
    recent.value = []
    return marked
  }

  /** 开始轮询;重复调用无副作用(顶栏挂载时调一次即可) */
  function startPolling(): void {
    if (timer !== null) return
    void refreshUnread()
    timer = setInterval(() => void refreshUnread(), POLL_INTERVAL_MS)
  }

  function stopPolling(): void {
    if (timer === null) return
    clearInterval(timer)
    timer = null
  }

  /** 退出登录时清干净,免得上一个账号的未读数留在下一个账号的红点上 */
  function reset(): void {
    stopPolling()
    unreadCount.value = 0
    recent.value = []
  }

  return {
    unreadCount,
    recent,
    loadingRecent,
    hasUnread,
    refreshUnread,
    loadRecent,
    markRead,
    markAllRead,
    startPolling,
    stopPolling,
    reset,
  }
})
