package com.rj.service.impl;

import com.rj.common.PageResult;
import com.rj.model.dto.ToolWorkOrderStatusDTO;
import com.rj.model.enums.WorkOrderStatus;
import com.rj.model.vo.ToolWorkOrderStatusVO;
import com.rj.model.vo.WorkOrderDetailVO;
import com.rj.model.vo.WorkOrderVO;
import com.rj.service.IToolWorkOrderService;
import com.rj.service.IWorkOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Agent Tool 工单服务实现:复用 {@link IWorkOrderService},只做入参整形与演示写接口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolWorkOrderService implements IToolWorkOrderService {

    /**
     * 工具接口单页上限。列表结果会进入大模型上下文,不封顶会有把几十上百条工单
     * 塞进 tool_output 撑爆上下文的风险(设计文档 §3.6 明确要防上下文膨胀)。
     */
    private static final long TOOL_MAX_PAGE_SIZE = 50L;

    private final IWorkOrderService workOrderService;

    /**
     * 工单列表:把分页参数钳制到安全区间后委托给既有分页查询。
     * <p>
     * 只钳 <b>下界</b>(current≥1)与 size∈[1,50],<b>不封 current 上界</b>——深翻页对 Agent 是
     * 合法行为,单页成本已被 size 上限兜住。size 采用<b>静默钳制</b>(不报错),响应里的 size 字段
     * 会告知实际生效值。数据范围过滤由 {@code IWorkOrderService.page} 内部按 UserContext 完成。
     */
    @Override
    public PageResult<WorkOrderVO> list(long current, long size, Integer status, Integer orderType, String keyword) {
        long safeCurrent = Math.max(current, 1L);
        long safeSize = Math.min(Math.max(size, 1L), TOOL_MAX_PAGE_SIZE);
        return workOrderService.page(safeCurrent, safeSize, status, orderType, keyword);
    }

    /**
     * 工单详情:直接委托,越权(403)/不存在(404)由既有 Service 抛出,经工具专用 advice 转成精简体。
     */
    @Override
    public WorkOrderDetailVO detail(Long id) {
        return workOrderService.detail(id);
    }

    /**
     * 变更工单状态(<b>演示实现</b>)。
     * <p>
     * 复用 {@code detail(id)} 取当前状态——顺带白拿它内部的数据范围校验:越权会抛 403、
     * 工单不存在会抛 404,因此本演示是「真的走了鉴权/范围路径」,而非永远成功的空壳。
     * 然后仅用状态机做<b>只读</b>的合法性判断并回显解析结果,<b>不改库</b>。
     */
    @Override
    public ToolWorkOrderStatusVO changeStatus(Long id, ToolWorkOrderStatusDTO dto) {
        WorkOrderDetailVO current = workOrderService.detail(id);
        int from = current.getStatus();
        int target = dto.getTargetStatus();

        WorkOrderStatus fromStatus = WorkOrderStatus.fromCode(from);
        WorkOrderStatus targetStatus = WorkOrderStatus.fromCode(target);
        // 只读合法性:不执行任何迁移,仅复用状态机的迁移表判断「这一步结构上是否允许」
        boolean legal = fromStatus.canTransitionTo(targetStatus);
        String action = resolveAction(from, target);

        // ============================================================================
        // TODO(状态机扩展点):真实流转分发落在此处。当前为演示,只解析不落库。
        // 真实实现应按 (当前状态, targetStatus) 派发到既有方法,并由各自的状态机 + 乐观锁保证:
        //   0 待审核 + 目标1(approved=true) -> workOrderService.review(id, new ReviewDTO(true,  remark))
        //   0 待审核 + 目标5(approved=false)-> workOrderService.review(id, new ReviewDTO(false, remark))
        //   1 待派单 + 目标2                 -> workOrderService.dispatch(id, new DispatchDTO(handlerId, remark))
        //   2 处理中 + 目标2(转派)          -> workOrderService.dispatch(id, new DispatchDTO(handlerId, remark))
        //   2 处理中 + 目标3                 -> workOrderService.process(id, new ProcessDTO(remark))
        //   3 待验收 + 目标4(approved=true) -> workOrderService.accept(id, new AcceptDTO(true,  remark))
        //   3 待验收 + 目标2(approved=false)-> workOrderService.accept(id, new AcceptDTO(false, remark))
        //   可取消态 + 目标6                 -> workOrderService.withdraw(id, new WithdrawDTO(remark))
        // 注意:每个动作需要不同的权限码(review/dispatch/process/accept/withdraw),单一
        // @RequiresPermission 无法表达「其一」,故真实实现应在 Service 内按动作逐个判权
        // (与 WorkOrderController#dispatch 同样的先例),而非在 Controller 上挂注解。
        // ============================================================================

        ToolWorkOrderStatusVO vo = new ToolWorkOrderStatusVO();
        vo.setId(id);
        vo.setFromStatus(from);
        vo.setFromStatusDesc(fromStatus.getDesc());
        vo.setTargetStatus(target);
        vo.setTargetStatusDesc(targetStatus.getDesc());
        vo.setAction(action);
        vo.setTransitionLegal(legal);
        vo.setChanged(false);
        vo.setMessage("演示接口:已解析目标流转[" + action + "]"
                + (legal ? ",状态机判定合法" : ",状态机判定非法")
                + ";未执行真实写操作");
        log.info("Tool 状态变更(演示): orderId={}, from={}, target={}, action={}, legal={}",
                id, from, target, action, legal);
        return vo;
    }

    /**
     * 目标状态 → 动作名(仅用于演示响应的可读描述)。
     * <p>
     * 既要看目标,也要看起点:目标态 2「处理中」可由三种来路进入——派单(1→2)、
     * 转派(2→2 自环)、验收退回(3→2),单看 target 无法区分。
     */
    private String resolveAction(int from, int target) {
        return switch (target) {
            case 1 -> "review";     // 0 待审核 → 1 待派单:审核通过
            case 5 -> "review";     // 0 待审核 → 5 已驳回:审核驳回
            case 3 -> "process";    // 2 处理中 → 3 待验收:处理完成
            case 4 -> "accept";     // 3 待验收 → 4 已完成:验收通过
            case 6 -> "withdraw";   // 可取消态 → 6 已取消:撤回/取消
            case 2 -> switch (from) {
                case 1 -> "dispatch";   // 1 待派单 → 2:派单
                case 2 -> "transfer";   // 2 → 2:转派(自环换处理人)
                case 3 -> "accept";     // 3 待验收 → 2:验收退回
                default -> "unknown";
            };
            default -> "unknown";
        };
    }
}
