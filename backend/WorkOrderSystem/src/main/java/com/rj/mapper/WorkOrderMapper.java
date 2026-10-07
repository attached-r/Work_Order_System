package com.rj.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rj.model.pojo.WorkOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 工单主表mapper接口 使用mybatis-plus简化手写sql
 * */
public interface WorkOrderMapper extends BaseMapper<WorkOrder> {

    /**
     * 统计「同部门 + 同类资源」进行中的资源申请工单数量(模块四:并发资源申请互斥校验)。
     * <p>
     * 「进行中」= 状态属于非终态 {@code {0 待审核, 1 待派单, 2 处理中, 3 待验收, 5 已驳回}}
     * (4 已完成 / 6 已取消 / 7 已超时是终态,可再次申请)。锁内调用本方法:
     * 返回 {@code > 0} 即说明已有进行中的同类申请,应拒绝重复提交。
     * <p>
     * <b>为什么不是唯一键?</b> 静态唯一键只能表达「同一时刻只能有一行」,而这里的规则是
     * 「进行中状态的<b>子集</b>里只能有一行」——状态会流转(终态后要允许再次申请),
     * 唯一键无法跟随状态变化。能用锁表达的动态约束,不该硬塞进静态唯一键。
     * <p>
     * 原生 SQL 不走 MyBatis-Plus 逻辑删除拦截,故<b>必须手写 {@code w.deleted = 0}</b>。
     *
     * @param departmentId 部门ID
     * @param resourceType 资源类别
     * @param resourceName 资源名称
     * @return 进行中的同类资源申请工单数
     */
    @Select("""
            SELECT COUNT(*)
            FROM work_order w
            JOIN work_order_resource r ON r.order_id = w.id
            WHERE w.department_id = #{departmentId}
              AND w.order_type = 2
              AND w.status IN (0, 1, 2, 3, 5)
              AND w.deleted = 0
              AND r.resource_type = #{resourceType}
              AND r.resource_name = #{resourceName}
            """)
    long countActiveResourceApplications(@Param("departmentId") Long departmentId,
                                         @Param("resourceType") String resourceType,
                                         @Param("resourceName") String resourceName);
}
