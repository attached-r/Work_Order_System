-- =====================================================================
-- 智能工单系统 数据库初始化脚本 (MySQL 8.x / InnoDB / utf8mb4)
-- 库名与 application.yaml 中 spring.datasource.url 保持一致: work_order_system
--
-- 设计约定:
--   1. 不建物理外键,以「索引 + 业务保证」维护关联(配合 MyBatis-Plus);
--   2. 可变主数据(user/work_order)使用逻辑删除 deleted;记录型表只增不改;
--   3. 枚举统一存码值 + 列注释说明字典含义;
--   4. 字符集 utf8mb4,兼容 emoji。
--
-- 业务流程: 提单人SUBMITTER提交 → 审核人REVIEWER审核 → 派单人DISPATCHER派单
--            → 处理人HANDLER处理 → 提单人验收SUBMITTER → 完成;任一步可超时/取消
--
-- 工单状态机 status(0-7):
--   0 待审核  已提交,待本部门审核人审核
--   1 待派单  审核通过,待派单人分配处理人
--   2 处理中  已派单给处理人(转派/返工仍在 2)
--   3 待验收  处理人处理完成,待提单人验收
--   4 已完成  提单人验收通过(终态)
--   5 已驳回  审核不通过,退回提单人修改后可重新提交
--   6 已取消  提单人撤回/取消(终态)
--   7 已超时  到期未完成,系统自动关闭(终态)
-- 操作事件 operate_type:
--   1提交 2审核通过 3审核驳回 4派单 5处理完成 6验收通过 7退回处理 8转派 9撤回/取消 10超时关闭
-- 工单类型 order_type: 1故障报修 2资源申请 3需求变更
-- 优先级   priority:   1高 2中 3低
-- =====================================================================

CREATE DATABASE IF NOT EXISTS `work_order_system`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;
USE `work_order_system`;

-- ---------------------------------------------------------------------
-- 1. department 部门表
--    部门是工单数据隔离的基础: 审核人/派单人按本部门范围作业, 提单部门随工单快照。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `department`;
CREATE TABLE `department`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `dept_code`   VARCHAR(20)  NOT NULL COMMENT '部门编码(唯一,代码判断用)',
    `dept_name`   VARCHAR(50)  NOT NULL COMMENT '部门名称',
    `remark`      VARCHAR(200)          DEFAULT NULL COMMENT '备注',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dept_code` (`dept_code`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='部门表';

-- ---------------------------------------------------------------------
-- 2. user 用户表
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `user`;
CREATE TABLE `user`
(
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`      VARCHAR(50)  NOT NULL COMMENT '登录账号',
    `password`      VARCHAR(100) NOT NULL COMMENT 'BCrypt 加密密码(60 字符)',
    `real_name`     VARCHAR(50)  NOT NULL COMMENT '真实姓名',
    `department_id` BIGINT                DEFAULT NULL COMMENT '所属部门ID(department.id);NULL=无部门/全局(如 ADMIN)',
    `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '账号状态:0禁用 1启用',
    `phone`         VARCHAR(20)           DEFAULT NULL COMMENT '联系电话,可空',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除:0正常 1已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    KEY `idx_department_id` (`department_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户表';

-- ---------------------------------------------------------------------
-- 3. role 角色表
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `role`;
CREATE TABLE `role`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `role_code`   VARCHAR(20)  NOT NULL COMMENT '角色标识:SUBMITTER/REVIEWER/DISPATCHER/HANDLER/ADMIN(代码判断用)',
    `role_name`   VARCHAR(50)  NOT NULL COMMENT '角色名称:提单人/审核人/派单人/处理人/管理员(展示用)',
    `remark`      VARCHAR(200)          DEFAULT NULL COMMENT '职责说明',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_code` (`role_code`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='角色表';

-- ---------------------------------------------------------------------
-- 4. user_role 用户-角色关联表(多对多)
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `user_role`;
CREATE TABLE `user_role`
(
    `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT   NOT NULL COMMENT '用户ID',
    `role_id`     BIGINT   NOT NULL COMMENT '角色ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_role` (`user_id`, `role_id`),
    KEY `idx_role_id` (`role_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户-角色关联表';

-- ---------------------------------------------------------------------
-- 5. permission 权限表(权限字典,标识形如 workorder:create)
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `permission`;
CREATE TABLE `permission`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `perm_code`   VARCHAR(100) NOT NULL COMMENT '权限标识:如 workorder:review/workorder:dispatch',
    `perm_name`   VARCHAR(50)  NOT NULL COMMENT '权限名称:如 审核工单',
    `parent_id`   BIGINT                DEFAULT NULL COMMENT '父权限ID,支持权限树;一级权限为 NULL',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_perm_code` (`perm_code`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='权限表';

-- ---------------------------------------------------------------------
-- 6. role_permission 角色-权限关联表
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `role_permission`;
CREATE TABLE `role_permission`
(
    `id`            BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `role_id`       BIGINT   NOT NULL COMMENT '角色ID',
    `permission_id` BIGINT   NOT NULL COMMENT '权限ID',
    `create_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_perm` (`role_id`, `permission_id`),
    KEY `idx_permission_id` (`permission_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='角色-权限关联表';

-- ---------------------------------------------------------------------
-- 7. work_order 工单主表(逻辑删除 + 乐观锁)
--    索引说明: status/expire 由联合索引 idx_status_expire 覆盖,
--    不再单独建 idx_status、idx_expire_time,避免冗余。
--    department_id 存提单部门快照, 供审核/派单按部门隔离。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `work_order`;
CREATE TABLE `work_order`
(
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `order_no`      VARCHAR(32)  NOT NULL COMMENT '工单编号(业务生成,唯一)',
    `user_id`       BIGINT       NOT NULL COMMENT '提单人ID(user.id)',
    `department_id` BIGINT       NOT NULL COMMENT '归属部门ID(提单时部门快照)',
    `handler_id`    BIGINT                DEFAULT NULL COMMENT '处理人ID,派单前为空',
    `title`         VARCHAR(200) NOT NULL COMMENT '工单标题',
    `content`       TEXT                  DEFAULT NULL COMMENT '工单描述',
    `order_type`    TINYINT      NOT NULL DEFAULT 1 COMMENT '工单类型:1故障报修 2资源申请 3需求变更',
    `priority`      TINYINT      NOT NULL DEFAULT 2 COMMENT '优先级:1高 2中 3低',
    `status`        TINYINT      NOT NULL DEFAULT 0 COMMENT '状态:0待审核 1待派单 2处理中 3待验收 4已完成 5已驳回 6已取消 7已超时',
    `remark`        VARCHAR(500)          DEFAULT NULL COMMENT '备注/驳回原因/关闭说明',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `expire_time`   DATETIME              DEFAULT NULL COMMENT '超时时间,到期自动关闭(状态置 7)',
    `version`       INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号,防并发覆盖',
    `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除:0正常 1已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_department_id` (`department_id`),
    KEY `idx_handler_id` (`handler_id`),
    KEY `idx_create_time` (`create_time`),
    KEY `idx_status_expire` (`status`, `expire_time`) COMMENT '超时自动关闭 Job 扫描 + 按状态过滤'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='工单主表';

-- ---------------------------------------------------------------------
-- 8. work_order_resource 工单资源明细子表
--    资源随工单整体状态流转(逐项状态见主表 work_order.status),不再单独维护状态。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `work_order_resource`;
CREATE TABLE `work_order_resource`
(
    `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `order_id`      BIGINT        NOT NULL COMMENT '所属工单ID',
    `resource_type` VARCHAR(50)   NOT NULL COMMENT '资源类别:服务器/带宽/软件许可 等',
    `resource_name` VARCHAR(200)  NOT NULL COMMENT '资源名称/规格描述',
    `quantity`      DECIMAL(10,2) NOT NULL DEFAULT 1 COMMENT '数量',
    `unit`          VARCHAR(20)            DEFAULT NULL COMMENT '单位:台/MBps/个 等',
    `remark`        VARCHAR(500)           DEFAULT NULL COMMENT '申请说明,可空',
    `create_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_order_id` (`order_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='工单资源明细表';

-- ---------------------------------------------------------------------
-- 9. work_order_attachment 工单附件表
--    file_path 存对象存储 key(OSS/MinIO),不落本地绝对路径。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `work_order_attachment`;
CREATE TABLE `work_order_attachment`
(
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `order_id`     BIGINT       NOT NULL COMMENT '所属工单ID',
    `file_name`    VARCHAR(255) NOT NULL COMMENT '原始文件名',
    `file_path`    VARCHAR(500) NOT NULL COMMENT '存储 key(OSS/MinIO 对象 key)',
    `file_size`    BIGINT       NOT NULL DEFAULT 0 COMMENT '文件大小(字节)',
    `content_type` VARCHAR(100)          DEFAULT NULL COMMENT 'MIME 类型',
    `uploader_id`  BIGINT                DEFAULT NULL COMMENT '上传人ID',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    PRIMARY KEY (`id`),
    KEY `idx_order_id` (`order_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='工单附件表';

-- ---------------------------------------------------------------------
-- 10. work_order_operate_log 工单操作日志(只增不改,无 update/delete 字段)
--     每次状态流转记一行; 转派时 to_user_id 记目标处理人。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `work_order_operate_log`;
CREATE TABLE `work_order_operate_log`
(
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `order_id`      BIGINT       NOT NULL COMMENT '工单ID',
    `operator_id`   BIGINT       NOT NULL COMMENT '操作人ID(user.id)',
    `operate_type`  TINYINT      NOT NULL COMMENT '操作事件:1提交 2审核通过 3审核驳回 4派单 5处理完成 6验收通过 7退回处理 8转派 9撤回/取消 10超时关闭',
    `from_status`   TINYINT               DEFAULT NULL COMMENT '变更前状态(0-7),首次提交为 NULL',
    `to_status`     TINYINT      NOT NULL COMMENT '变更后状态(0-7)',
    `to_user_id`    BIGINT                DEFAULT NULL COMMENT '派单/转派目标处理人ID,可空',
    `remark`        VARCHAR(500)          DEFAULT NULL COMMENT '操作备注,可空',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    PRIMARY KEY (`id`),
    KEY `idx_order_operate` (`order_id`, `create_time`),
    KEY `idx_operator_id` (`operator_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='工单操作日志表';

-- ---------------------------------------------------------------------
-- 11. mq_message_reliability MQ 消息可靠性表(本地消息表/事务发消息,只增)
--     核心: msg_id 唯一 + idx_status_retry 给补偿 Job 扫描重发。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `mq_message_reliability`;
CREATE TABLE `mq_message_reliability`
(
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `msg_id`          VARCHAR(64)  NOT NULL COMMENT '消息唯一ID(业务侧生成)',
    `biz_type`        VARCHAR(50)  NOT NULL COMMENT '业务类型:如 workorder_notify',
    `biz_id`          VARCHAR(64)           DEFAULT NULL COMMENT '业务单据ID,可空',
    `exchange`        VARCHAR(128) NOT NULL COMMENT '交换机名称',
    `routing_key`     VARCHAR(128) NOT NULL COMMENT '路由键',
    `msg_body`        MEDIUMTEXT   NOT NULL COMMENT '消息体(JSON)',
    `status`          TINYINT      NOT NULL DEFAULT 0 COMMENT '发送状态:0待发送 1已投递待确认 2已确认 3失败',
    `retry_count`     INT          NOT NULL DEFAULT 0 COMMENT '已重试次数',
    `next_retry_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次重试时间',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `send_time`       DATETIME              DEFAULT NULL COMMENT '最近发送时间',
    `success_time`    DATETIME              DEFAULT NULL COMMENT '确认成功时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_msg_id` (`msg_id`),
    KEY `idx_status_retry` (`status`, `next_retry_time`) COMMENT '补偿 Job 扫描'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='MQ 消息可靠性表';
