package com.rj.service;

import com.rj.common.PageResult;
import com.rj.model.dto.AcceptDTO;
import com.rj.model.dto.DispatchDTO;
import com.rj.model.dto.ProcessDTO;
import com.rj.model.dto.ReviewDTO;
import com.rj.model.dto.WithdrawDTO;
import com.rj.model.dto.WorkOrderCreateDTO;
import com.rj.model.vo.WorkOrderDetailVO;
import com.rj.model.vo.WorkOrderVO;

/**
 * 工单业务接口(模块二:工单核心业务)
 * <p>
 * 覆盖完整 5 环节:提交 → 审核 → 派单 → 处理 → 验收,另有撤回与超时自动关闭。
 * 所有状态流转都经过状态机校验,且附带数据范围判断(本部门/本人)。
 */
public interface IWorkOrderService {

    /**
     * 创建(提交)工单:主表 + 资源明细在同一事务落库,初始状态为「0 待审核」。
     *
     * @param dto 创建参数
     * @return 新工单ID
     */
    Long create(WorkOrderCreateDTO dto);

    /**
     * 重新提交:被驳回(5)的工单,提单人本人修改后重投,回到「0 待审核」。
     * 沿用创建时的表单参数,可修改标题/描述/类型/优先级;resources 非空时覆盖式替换明细。
     *
     * @param id  工单ID
     * @param dto 重新提交的表单(与创建一致)
     */
    void resubmit(Long id, WorkOrderCreateDTO dto);

    /**
     * 分页查询工单,按当前登录人的角色自动施加数据范围。
     *
     * @param current   页码,从 1 开始
     * @param size      每页条数
     * @param status    状态过滤,可空
     * @param orderType 工单类型过滤,可空
     * @param keyword   标题/编号模糊搜索,可空
     * @return 分页结果
     */
    PageResult<WorkOrderVO> page(long current, long size, Integer status, Integer orderType, String keyword);

    /**
     * 工单详情(含资源明细与操作日志),带数据范围校验。
     *
     * @param id 工单ID
     * @return 详情视图
     */
    WorkOrderDetailVO detail(Long id);

    /** 审核:待审核(0) → 通过「1 待派单」/ 驳回「5 已驳回」 */
    void review(Long id, ReviewDTO dto);

    /**
     * 派单 / 转派:待派单(1) → 处理中(2) 为派单;处理中(2) → 处理中(2) 为转派。
     * 两动作按工单当前状态区分,权限码分别为 workorder:dispatch / workorder:transfer。
     */
    void dispatch(Long id, DispatchDTO dto);

    /** 处理完成:处理中(2) → 待验收(3) */
    void process(Long id, ProcessDTO dto);

    /** 验收:待验收(3) → 通过「4 已完成」/ 退回「2 处理中」 */
    void accept(Long id, AcceptDTO dto);

    /** 撤回 / 取消:待审核(0)/待派单(1)/处理中(2)/已驳回(5) → 已取消(6,终态) */
    void withdraw(Long id, WithdrawDTO dto);

    /**
     * 超时自动关闭:扫描已过期且未完结的工单,批量置为「7 已超时」。
     * 由定时任务调用,幂等依赖状态前置校验 + 乐观锁。
     *
     * @return 本轮实际关闭的工单数
     */
    int closeTimeout();
}
