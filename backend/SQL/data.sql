-- =====================================================================
-- 初始化数据(仅数据,建表请先执行 schema.sql)
--
-- 说明:
--   1. 主键 id 均为自增,不显式插入;下方 user_role/role_permission
--      按固定数字引用(空表下自增从 1 起,顺序即注释所示)。
--   2. 若表里已有数据,先 DELETE 全部子表再重跑,否则唯一键冲突、
--      id 顺序也会错位。
--   3. 账号统一口令 admin123,哈希已内联;如需更换,用
--      PasswordUtil.encode("新口令") 生成 60 位哈希替换下方
--      '<bcrypt-hash>' 即可(五条 user 保持一致)。
--   4. 如果执行出错 请按顺序一步步执行
-- =====================================================================

USE `work_order_system`;

-- ---------------------------------------------------------------------
-- 1. 部门(自增:RND=1, OPS=2)
-- ---------------------------------------------------------------------
INSERT INTO work_order_system.department  (`dept_code`, `dept_name`, `remark`)
VALUES ('RND', '研发部', NULL),
       ('OPS', '运维部', NULL);

-- ---------------------------------------------------------------------
-- 2. 角色(自增:SUBMITTER=1 REVIEWER=2 DISPATCHER=3 HANDLER=4 ADMIN=5)
-- ---------------------------------------------------------------------
INSERT INTO work_order_system.role  (`role_code`, `role_name`, `remark`)
VALUES ('SUBMITTER', '提单人', '创建/提交/撤回/取消工单,并对处理结果验收'),
       ('REVIEWER', '审核人', '审核本部门提单'),
       ('DISPATCHER', '派单人', '查看本部门待派单工单并分配处理人'),
       ('HANDLER', '处理人', '处理分配给自己的工单,支持完成与转派'),
       ('ADMIN', '管理员', '管理用户/角色/部门,并查看全部工单');

-- ---------------------------------------------------------------------
-- 3. 权限字典(自增:create=1 ~ user:manage=10)
-- ---------------------------------------------------------------------
INSERT INTO work_order_system.permission  (`perm_code`, `perm_name`)
VALUES ('workorder:create', '提交工单'),
       ('workorder:modify', '修改工单'),
       ('workorder:withdraw', '撤回/取消工单'),
       ('workorder:accept', '验收工单'),
       ('workorder:query', '查询工单'),
       ('workorder:review', '审核工单'),
       ('workorder:dispatch', '派单工单'),
       ('workorder:process', '处理工单'),
       ('workorder:transfer', '转派工单'),
       ('user:manage', '用户/角色/部门管理');

-- ---------------------------------------------------------------------
-- 4. 用户(自增:admin=1 submitter01=2 reviewer01=3 dispatcher01=4 handler01=5;
--        口令均 admin123;admin 无部门=全局,其余归研发部 RND=1)
-- ---------------------------------------------------------------------
INSERT INTO work_order_system.`user`  (`username`, `password`, `real_name`, `department_id`, `phone`, `status`)
VALUES ('admin', '$2a$10$xrwk/x2wqRpcqz1HTYKUx.CIokTeuDuSQ/RHJ4g5GC8LqnRoiz5yS',
        '超级管理员', NULL, '13800000001', 1),
       ('submitter01', '$2a$10$xrwk/x2wqRpcqz1HTYKUx.CIokTeuDuSQ/RHJ4g5GC8LqnRoiz5yS',
        '提单人小王', 1, '13800000002', 1),
       ('reviewer01', '$2a$10$xrwk/x2wqRpcqz1HTYKUx.CIokTeuDuSQ/RHJ4g5GC8LqnRoiz5yS',
        '审核人小赵', 1, '13800000003', 1),
       ('dispatcher01', '$2a$10$xrwk/x2wqRpcqz1HTYKUx.CIokTeuDuSQ/RHJ4g5GC8LqnRoiz5yS',
        '派单人小钱', 1, '13800000004', 1),
       ('handler01', '$2a$10$xrwk/x2wqRpcqz1HTYKUx.CIokTeuDuSQ/RHJ4g5GC8LqnRoiz5yS',
        '处理人小李', 1, '13800000005', 1);

-- ---------------------------------------------------------------------
-- 5. 用户-角色关联(admin=1→ADMIN=5;submitter01=2→SUBMITTER=1;
--                  reviewer01=3→REVIEWER=2;dispatcher01=4→DISPATCHER=3;
--                  handler01=5→HANDLER=4)
-- ---------------------------------------------------------------------
INSERT INTO work_order_system.user_role  (`user_id`, `role_id`)
VALUES (1, 5),
       (2, 1),
       (3, 2),
       (4, 3),
       (5, 4);

-- ---------------------------------------------------------------------
-- 6. 角色-权限关联(ADMIN=5 全部;其余 = 各自操作 + 查询)
--    SUBMITTER=1: create/modify/withdraw/accept/query
--    REVIEWER=2:  review/query; DISPATCHER=3: dispatch/query
--    HANDLER=4:   process/transfer/query
-- ---------------------------------------------------------------------
INSERT INTO work_order_system.role_permission  (`role_id`, `permission_id`)
VALUES (5, 1), (5, 2), (5, 3), (5, 4), (5, 5), (5, 6), (5, 7), (5, 8), (5, 9), (5, 10),
       (1, 1), (1, 2), (1, 3), (1, 4), (1, 5),
       (2, 6), (2, 5),
       (3, 7), (3, 5),
       (4, 8), (4, 9), (4, 5);
