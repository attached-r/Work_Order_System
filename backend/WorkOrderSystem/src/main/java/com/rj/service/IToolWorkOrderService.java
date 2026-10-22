package com.rj.service;

import com.rj.common.PageResult;
import com.rj.model.dto.ToolWorkOrderActionDTO;
import com.rj.model.vo.ToolWorkOrderStatusVO;
import com.rj.model.vo.WorkOrderDetailVO;
import com.rj.model.vo.WorkOrderVO;

/**
 * Agent Tool 接口的工单服务(项目二的调用入口)。
 * <p>
 * 定位:薄薄一层「工具契约」封装,复用既有的 {@link IWorkOrderService},负责
 * ① 对 Agent 友好的入参整形(如分页 size 封顶)、② 写动作的解析、判权与分发。
 * 业务规则、数据范围、状态机校验全部下沉在 {@link IWorkOrderService},本层不重复实现。
 */
public interface IToolWorkOrderService {

    /**
     * 工单列表(数据范围按当前登录用户自动收敛)。
     *
     * @param size 期望条数,会被钳制到上限,详见实现
     * @return 分页结果;响应里的 size 字段即实际生效值
     */
    PageResult<WorkOrderVO> list(long current, long size, Integer status, Integer orderType, String keyword);

    /**
     * 工单详情(内部会做归属/部门的数据范围校验,越权抛 403、不存在抛 404)。
     */
    WorkOrderDetailVO detail(Long id);

    /**
     * 执行一次工单动作(真实落库)。
     * <p>
     * 校验顺序:解析动作 → 按动作判权 → 读最新状态 → expectedFromStatus 比对 →
     * allowedFrom 校验 → 动作参数 → 委托既有 Service。任一环节失败都不会产生写副作用。
     *
     * @param id  工单ID
     * @param dto 动作与期望状态等参数
     * @return 已执行的动作与前后状态
     */
    ToolWorkOrderStatusVO changeStatus(Long id, ToolWorkOrderActionDTO dto);
}
