package com.rj.service;

import com.rj.common.PageResult;
import com.rj.model.vo.NotificationVO;
import com.rj.mq.NotifyMessage;

import java.util.List;

/**
 * 站内信业务接口(模块三:异步通知)。
 * <p>
 * <strong>数据范围只有一条:{@code user_id = UserContext.getUserId()},服务端强制注入。</strong>
 * 通知是点对点私信,不是部门/角色的共享资源,所以刻意<em>不提供</em>
 * {@code departmentId}/{@code roleCode} 之类的过滤参数——不是没实现,是不提供。
 * 也因此读侧接口不挂 {@code @RequiresPermission},登录即可。
 */
public interface INotificationService {

    /**
     * 幂等落库:同一 (msgId, userId) 只落一行,重复消息静默跳过。
     * <p>
     * 由消费端调用。实际插入成功的收件人才会失效未读缓存——重复消息不该产生任何写行为。
     *
     * @param message    事件载荷(msgId 与工单快照的来源)
     * @param recipients 收件人ID列表,可为空(空集是合法结果,直接返回)
     * @return 本次实际新增的通知条数
     */
    int saveIfAbsent(NotifyMessage message, List<Long> recipients);

    /**
     * 分页查询当前登录人的收件箱。
     *
     * @param current  页码,从 1 开始
     * @param size     每页条数
     * @param readFlag 不传=全部;0=仅未读;1=仅已读
     * @return 分页结果
     */
    PageResult<NotificationVO> page(long current, long size, Integer readFlag);

    /**
     * 当前登录人的未读数。走 Redis 缓存(写失效 + TTL 兜底),未命中回源计数。
     *
     * @return 未读条数
     */
    long unreadCount();

    /**
     * 标记单条已读(幂等)。
     * <p>
     * 通知不存在<strong>或不属于当前用户</strong>时抛 404 而非 403:
     * 403 等于告诉调用方「这条通知存在,只是不归你」,那是存在性泄漏。
     *
     * @param id 通知ID
     */
    void markRead(Long id);

    /**
     * 全部标记已读,并失效未读缓存。
     *
     * @return 本次标记的条数
     */
    int markAllRead();
}
