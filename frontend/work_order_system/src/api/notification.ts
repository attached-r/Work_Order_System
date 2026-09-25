/**
 * 通知中心接口,对应后端 NotificationController。
 *
 * 三条与工单模块不同的约定,别顺手写错:
 *   1. 这四个接口后端**刻意不挂权限码**,登录即可 —— 收件箱只装自己的消息,
 *      数据范围由服务端按 token 里的 userId 收敛。所以这里既不传也不该传 userId,
 *      传了也会被忽略;
 *   2. 分页参数是 current / size(MyBatis-Plus 那套),不是 pageNum / pageSize;
 *   3. readFlag 可选:不传=全部,0=未读,1=已读。空值必须整个丢掉 ——
 *      拼成 `readFlag=` 后端 @RequestParam Integer 会 400(request 层已处理)。
 */

import { request } from '@/utils/request'
import type { NotificationQuery, NotificationVO, PageResult } from '@/types/domain'

/** 通知分页;数据范围由后端按当前登录用户收敛,前端无从指定 */
export function pageNotifications(query: NotificationQuery): Promise<PageResult<NotificationVO>> {
  return request<PageResult<NotificationVO>>('/notification/page', {
    query: {
      current: query.current,
      size: query.size,
      readFlag: query.readFlag,
    },
  })
}

/**
 * 未读数,顶栏红点用。
 * 后端由 Redis 计数直接作答(未命中才查库),可以放心轮询。
 */
export function fetchUnreadCount(): Promise<number> {
  return request<number>('/notification/unread-count')
}

/**
 * 标记单条已读。幂等:已读的再调一次不会报错,也不会刷新 readTime。
 * 通知不存在或不属于当前用户时后端回 404(刻意不区分,避免暴露他人消息是否存在)。
 */
export function markNotificationRead(id: number): Promise<void> {
  return request<void>(`/notification/${id}/read`, { method: 'POST' })
}

/**
 * 全部已读。
 * @returns 本次真正标记的条数 —— 本来就全已读时是 0,用来决定提示文案
 */
export function markAllNotificationsRead(): Promise<number> {
  return request<number>('/notification/read-all', { method: 'POST' })
}
