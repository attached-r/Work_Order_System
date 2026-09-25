package com.rj.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rj.model.pojo.Notification;
import org.apache.ibatis.annotations.Insert;

/**
 * 站内信表 mapper 接口。读侧与已读标记走 MyBatis-Plus 包装器,
 * 只有「幂等插入」需要手写 SQL。
 */
public interface NotificationMapper extends BaseMapper<Notification> {

    /**
     * 幂等插入:撞 {@code uk_msg_user (msg_id, user_id)} 时不报错、不新增行,影响行数 0。
     * <p>
     * 为什么是 {@code ON DUPLICATE KEY UPDATE id = id} 而不是另外两种写法:
     * <ul>
     *   <li>{@code INSERT IGNORE} 会忽略<strong>所有</strong>错误,一条 NOT NULL 违规的记录
     *       会被静默丢弃,你永远不知道有通知没发出去;</li>
     *   <li>{@code catch (DuplicateKeyException)} 是异常式控制流,在 {@code @Transactional}
     *       环境下会把事务标记为 rollback-only,<strong>污染同批其他收件人</strong>的插入。</li>
     * </ul>
     * 返回值 1=新插入(需要失效未读缓存),0=重复消息(静默跳过,照常 ACK)。
     *
     * @param notification 待落库的站内信(read_flag/create_time 由 SQL 常量与 NOW() 决定)
     * @return 影响行数
     */
    @Insert("INSERT INTO notification (msg_id, user_id, biz_type, biz_id, order_no, notify_type, channel, title, content, read_flag, create_time) "
            + "VALUES (#{msgId}, #{userId}, #{bizType}, #{bizId}, #{orderNo}, #{notifyType}, #{channel}, #{title}, #{content}, 0, NOW()) "
            + "ON DUPLICATE KEY UPDATE id = id")
    int insertIgnoreDuplicate(Notification notification);
}
