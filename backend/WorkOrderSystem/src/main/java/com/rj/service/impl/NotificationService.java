package com.rj.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rj.common.PageResult;
import com.rj.common.ResultCode;
import com.rj.common.UserContext;
import com.rj.exception.BusinessException;
import com.rj.mapper.NotificationMapper;
import com.rj.model.enums.NotifyChannel;
import com.rj.model.pojo.Notification;
import com.rj.model.vo.NotificationVO;
import com.rj.mq.NotifyMessage;
import com.rj.service.INotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.rj.common.RedisConstants.NOTIFY_UNREAD_EXPIRE_SECONDS;
import static com.rj.common.RedisConstants.NOTIFY_UNREAD_KEY;

/**
 * 站内信业务实现(模块三)。
 * <p>
 * 写侧只有一个入口 {@link #saveIfAbsent}:幂等做在存储层({@code uk_msg_user} 唯一键
 * + {@code ON DUPLICATE KEY UPDATE id = id}),不依赖应用层记住「这条处理过没有」——
 * 幂等做在存储层,就不会因为应用重启、并发、重试而失效。
 * <p>
 * 读侧的数据范围只有 {@code user_id = 当前登录用户} 一条,服务端强制注入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService implements INotificationService {

    /** 未读 */
    private static final int UNREAD = 0;
    /** 已读 */
    private static final int READ = 1;

    private final NotificationMapper notificationMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final NotifyDispatcher notifyDispatcher;

    /**
     * 幂等落库。
     * <p>
     * 逐个收件人插入而非批量:{@code ON DUPLICATE KEY UPDATE} 需要逐行的影响行数来判断
     * 「这条是不是重复消息」,批量插入会把「有几个是新的」这个信息搅在一起。
     * 收件人典型 1-3 人,逐行没有代价。
     *
     * @return 实际新增条数(0 表示整批都是重复消息)
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int saveIfAbsent(NotifyMessage message, List<Long> recipients) {
        if (recipients == null || recipients.isEmpty()) {
            // 「查出来了,结果是空的」是成功,不是失败:调用方会 ACK
            return 0;
        }
        NotifyDispatcher.NotifyText text = notifyDispatcher.renderText(message);
        int inserted = 0;
        for (Long userId : recipients) {
            Notification notification = Notification.builder()
                    .msgId(message.getMsgId())
                    .userId(userId)
                    .bizType(message.getBizType())
                    .bizId(String.valueOf(message.getOrderId()))
                    .orderNo(message.getOrderNo())
                    .notifyType(message.getOperateType())
                    .channel(NotifyChannel.IN_APP.getCode())
                    .title(text.title())
                    .content(text.content())
                    .build();
            if (notificationMapper.insertIgnoreDuplicate(notification) > 0) {
                inserted++;
            } else {
                log.debug("重复消息, 已跳过: msgId={}, userId={}", message.getMsgId(), userId);
            }
        }
        if (inserted > 0) {
            // 写失效而非写时增量:DEL 是幂等的,不可能算错;最坏只是下次读回源一次。
            // 顺序不能反——先写库再失效,反过来会造出更长的不一致窗口。
            recipients.forEach(this::evictUnreadCache);
        }
        return inserted;
    }

    @Override
    public PageResult<NotificationVO> page(long current, long size, Integer readFlag) {
        Long userId = UserContext.getUserId();
        LambdaQueryWrapper<Notification> wrapper = Wrappers.<Notification>lambdaQuery()
                .eq(Notification::getUserId, userId);
        if (readFlag != null) {
            wrapper.eq(Notification::getReadFlag, readFlag);
        }
        // 与 idx_user_read_time(user_id, read_flag, create_time) 的列序对齐:
        // 等值定位 user_id[, read_flag] 后按 create_time 倒序走索引,无 filesort
        wrapper.orderByDesc(Notification::getCreateTime);

        Page<Notification> page = notificationMapper.selectPage(new Page<>(current, size), wrapper);
        List<NotificationVO> records = page.getRecords().stream().map(this::toVO).toList();
        return new PageResult<>(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    @Override
    public long unreadCount() {
        Long userId = UserContext.getUserId();
        String key = NOTIFY_UNREAD_KEY + userId;
        String cached = stringRedisTemplate.opsForValue().get(key);
        if (cached != null) {
            try {
                return Long.parseLong(cached);
            } catch (NumberFormatException e) {
                // 值被外部写坏:当作未命中,删掉重建,而不是把 0 报给用户
                log.warn("未读缓存值非法, 已删除重建: key={}, value={}", key, cached);
                stringRedisTemplate.delete(key);
            }
        }
        Long count = notificationMapper.selectCount(Wrappers.<Notification>lambdaQuery()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getReadFlag, UNREAD));
        long unread = count == null ? 0L : count;
        stringRedisTemplate.opsForValue().set(key, String.valueOf(unread),
                NOTIFY_UNREAD_EXPIRE_SECONDS, TimeUnit.SECONDS);
        return unread;
    }

    /**
     * 标记单条已读。
     * <p>
     * 先按 {@code id + user_id} 查一次是否存在,再做「仅在未读时更新」的幂等 UPDATE。
     * 之所以要两次查询:单看 UPDATE 的影响行数 0 有两种含义——「不存在/不是你的」
     * 与「已经读过」,而这两者要返回不同的结果(404 vs 200)。<strong>两次查询换来的是语义清晰</strong>,
     * 对这个量级的接口是划算的。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markRead(Long id) {
        Long userId = UserContext.getUserId();
        Notification existing = notificationMapper.selectOne(Wrappers.<Notification>lambdaQuery()
                .eq(Notification::getId, id)
                .eq(Notification::getUserId, userId));
        if (existing == null) {
            // 刻意用 404 而不是 403:让「不存在」与「不是你的」在响应上不可区分
            throw new BusinessException(ResultCode.NOT_FOUND, "通知不存在");
        }
        if (existing.getReadFlag() != null && existing.getReadFlag() == READ) {
            return;
        }
        LambdaUpdateWrapper<Notification> wrapper = Wrappers.<Notification>lambdaUpdate()
                .eq(Notification::getId, id)
                .eq(Notification::getUserId, userId)
                .eq(Notification::getReadFlag, UNREAD)
                // IFNULL 保住首次已读时间:重复标记不会把时间一次次往后推
                .set(Notification::getReadFlag, READ)
                .setSql("read_time = IFNULL(read_time, NOW())");
        notificationMapper.update(null, wrapper);
        evictUnreadCache(userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int markAllRead() {
        Long userId = UserContext.getUserId();
        LambdaUpdateWrapper<Notification> wrapper = Wrappers.<Notification>lambdaUpdate()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getReadFlag, UNREAD)
                .set(Notification::getReadFlag, READ)
                .setSql("read_time = IFNULL(read_time, NOW())");
        int updated = notificationMapper.update(null, wrapper);
        if (updated > 0) {
            evictUnreadCache(userId);
        }
        return updated;
    }

    /** 删除未读缓存。缓存抖动不该让业务失败:TTL 300s 会兜底,最坏是几分钟的未读数滞后 */
    private void evictUnreadCache(Long userId) {
        try {
            stringRedisTemplate.delete(NOTIFY_UNREAD_KEY + userId);
        } catch (Exception e) {
            log.warn("未读缓存失效失败(等待 TTL 自愈): userId={}", userId, e);
        }
    }

    private NotificationVO toVO(Notification notification) {
        NotificationVO vo = new NotificationVO();
        vo.setId(notification.getId());
        vo.setNotifyType(notification.getNotifyType());
        vo.setNotifyTypeDesc(NotifyDispatcher.typeDesc(notification.getNotifyType()));
        vo.setChannel(notification.getChannel());
        vo.setTitle(notification.getTitle());
        vo.setContent(notification.getContent());
        vo.setBizType(notification.getBizType());
        vo.setBizId(notification.getBizId());
        vo.setOrderNo(notification.getOrderNo());
        vo.setReadFlag(notification.getReadFlag());
        vo.setReadTime(notification.getReadTime());
        vo.setCreateTime(notification.getCreateTime());
        return vo;
    }
}
