package com.rj.service;

import com.rj.common.PageResult;
import com.rj.model.dto.ToolWorkOrderStatusDTO;
import com.rj.model.vo.ToolWorkOrderStatusVO;
import com.rj.model.vo.WorkOrderDetailVO;
import com.rj.model.vo.WorkOrderVO;

/**
 * Agent Tool 接口的工单服务(项目二的调用入口)。
 * <p>
 * 定位:薄薄一层「工具契约」封装,复用既有的 {@link IWorkOrderService},负责
 * ① 对 Agent 友好的入参整形(如分页 size 封顶)、② 预留写接口的扩展点。
 * 业务规则、数据范围、状态机校验全部下沉在 {@link IWorkOrderService},本层不重复实现。
 * <p>
 * 之所以单独成层而不在 Controller 里直接调 {@code IWorkOrderService}:钳制与写接口扩展点属于
 * 「工具契约」而非「HTTP 关注点」,放这里与仓库「Controller 只做委托」的约定一致。
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
     * 变更工单状态。
     * <p>
     * <b>当前为演示实现</b>:只解析目标流转、不落库;真实的状态机分发留作扩展点。
     */
    ToolWorkOrderStatusVO changeStatus(Long id, ToolWorkOrderStatusDTO dto);
}
