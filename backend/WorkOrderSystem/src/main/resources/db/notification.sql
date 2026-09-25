-- =====================================================================
-- 模块三(异步通知)增量升级脚本 —— notification 站内信表
--
-- ⚠️ 已有数据的环境请执行本脚本,不要执行 schema.sql。
--    schema.sql 是「全库重建」脚本,顶部逐表 DROP TABLE IF EXISTS,
--    在已有工单数据的库上执行会清空工单及其资源、日志。
--    本脚本只处理 notification 一张表,对既有表零影响。
--
-- 幂等性:重复执行等价于重建空表。请勿在有通知数据的库上重复执行。
--
-- 执行方式:
--   mysql -uroot -p work_order_system < notification.sql
-- =====================================================================

USE `work_order_system`;

-- ---------------------------------------------------------------------
-- notification 站内信表(只增)
--   幂等的地基: uk_msg_user(msg_id, user_id)
--     —— 把「同一条消息重复投递给同一个人」变成数据库层的唯一性冲突,
--        而不是需要应用层记住的东西。消费端据此写
--        INSERT ... ON DUPLICATE KEY UPDATE id = id。
--   索引列序: idx_user_read_time(user_id, read_flag, create_time)
--     —— 收件箱列表 (user_id[, read_flag] 等值 + create_time 倒序) 与
--        未读数 (user_id + read_flag 最左前缀计数) 共用一条索引。
--        列序写成 (read_flag, user_id, create_time) 会让未读数退化成
--        对「全部已读用户」的范围扫描,不要改。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `notification`;
CREATE TABLE `notification`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `msg_id`      VARCHAR(64)  NOT NULL COMMENT '来源消息ID,= mq_message_reliability.msg_id',
    `user_id`     BIGINT       NOT NULL COMMENT '收件人ID',
    `biz_type`    VARCHAR(50)  NOT NULL DEFAULT 'workorder_notify' COMMENT '业务类型,与 outbox 一致',
    `biz_id`      VARCHAR(64)           DEFAULT NULL COMMENT '业务单据ID(工单ID)',
    `order_no`    VARCHAR(32)           DEFAULT NULL COMMENT '工单编号快照,收件箱免 join',
    `notify_type` TINYINT      NOT NULL COMMENT '通知类型,取值同 OperateType(1-10)',
    `channel`     TINYINT      NOT NULL DEFAULT 1 COMMENT '渠道:1站内信 2邮件 3短信(预留)',
    `title`       VARCHAR(200) NOT NULL COMMENT '通知标题(已渲染)',
    `content`     VARCHAR(500)          DEFAULT NULL COMMENT '通知正文(已渲染)',
    `read_flag`   TINYINT      NOT NULL DEFAULT 0 COMMENT '0未读 1已读(列名不能叫 read,MySQL 保留字)',
    `read_time`   DATETIME              DEFAULT NULL COMMENT '首次标记已读时间',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '产生时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_msg_user` (`msg_id`, `user_id`) COMMENT '消费幂等:同一消息对同一收件人只落一行',
    KEY `idx_user_read_time` (`user_id`, `read_flag`, `create_time`) COMMENT '列表+未读数一索引两用',
    KEY `idx_biz` (`biz_type`, `biz_id`) COMMENT '反查某工单产生了哪些通知'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='站内信通知表';
