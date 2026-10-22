package com.rj.service.impl;

import com.rj.common.PageResult;
import com.rj.common.ResultCode;
import com.rj.common.UserContext;
import com.rj.exception.BusinessException;
import com.rj.mapper.WorkOrderMapper;
import com.rj.model.dto.AcceptDTO;
import com.rj.model.dto.DispatchDTO;
import com.rj.model.dto.ProcessDTO;
import com.rj.model.dto.ReviewDTO;
import com.rj.model.dto.ToolWorkOrderActionDTO;
import com.rj.model.dto.WithdrawDTO;
import com.rj.model.enums.ToolWorkOrderAction;
import com.rj.model.enums.WorkOrderStatus;
import com.rj.model.pojo.WorkOrder;
import com.rj.model.vo.ToolWorkOrderStatusVO;
import com.rj.model.vo.WorkOrderDetailVO;
import com.rj.model.vo.WorkOrderVO;
import com.rj.service.IToolWorkOrderService;
import com.rj.service.IWorkOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Agent Tool 工单服务实现:复用 {@link IWorkOrderService},只做入参整形、动作分发与判权。
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
     * 写路径专用:直接读主表拿「最新状态」。
     * <p>
     * 不能复用 {@code IWorkOrderService.detail()}:它走逻辑过期缓存,过期窗口内可能返回旧状态,
     * 而旧状态会把「派单」误路由成「转派」。这里只需要 id + status + orderNo,查主表最小也最新。
     */
    private final WorkOrderMapper workOrderMapper;

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
     * 执行工单动作(真实落库)。
     * <p>
     * 严格按「先判权、后查库」的顺序:无权限者不该通过 404/409/403 的差异探测工单是否存在。
     * 本方法<b>不挂</b> {@code @Transactional}:委托的五个方法各自是事务边界,返回时日志、
     * outbox、缓存失效都已提交,响应语义明确。
     */
    @Override
    public ToolWorkOrderStatusVO changeStatus(Long id, ToolWorkOrderActionDTO dto) {
        // 第 1 步:解析动作。未知动作直接 400,不触碰数据库
        ToolWorkOrderAction action = ToolWorkOrderAction.fromCode(dto.getAction());
        if (action == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "不支持的动作: " + dto.getAction()
                            + ",可选值 REVIEW/DISPATCH/TRANSFER/PROCESS/ACCEPT/WITHDRAW");
        }

        // 第 2 步:按动作判权。Tool 入口的 Controller 刻意没有权限注解,
        // review/process/accept/withdraw 的权限防线只剩这一处
        requirePermission(action);

        // 第 3 步:参数与存在性。绕开详情缓存读主表,拿到最新状态
        if (id == null || id <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "工单ID非法");
        }
        WorkOrder order = workOrderMapper.selectById(id);
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "工单不存在");
        }
        int currentStatus = order.getStatus();

        // 第 4 步:状态漂移校验。调用方确认时看到的状态必须仍是当前状态,
        // 否则本次「意图」已失效,拒绝执行而不重新解释动作
        if (!dto.getExpectedFromStatus().equals(currentStatus)) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "工单状态已变更(当前:" + WorkOrderStatus.fromCode(currentStatus).getDesc()
                            + "),请重新查询确认后再操作");
        }

        // 第 5 步:动作与该起点是否匹配。例如派单只接受待派单,不会被当成转派
        if (!action.allowsFrom(currentStatus)) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "当前状态[" + WorkOrderStatus.fromCode(currentStatus).getDesc()
                            + "]不允许执行[" + action.getDesc() + "]");
        }

        // 第 6、7 步:动作参数校验 + 委托既有 Service(数据范围、状态机、乐观锁都在其中)
        WorkOrderStatus targetStatus = execute(action, id, dto);

        // 第 8 步:组装响应。目标状态是确定的,不回读详情
        ToolWorkOrderStatusVO vo = new ToolWorkOrderStatusVO();
        vo.setId(id);
        vo.setOrderNo(order.getOrderNo());
        vo.setAction(action.name());
        vo.setFromStatus(currentStatus);
        vo.setFromStatusDesc(WorkOrderStatus.fromCode(currentStatus).getDesc());
        vo.setTargetStatus(targetStatus.getCode());
        vo.setTargetStatusDesc(targetStatus.getDesc());
        vo.setChanged(true);
        vo.setMessage(resolveMessage(action, dto));
        log.info("Tool 工单动作: orderId={}, action={}, {} -> {}, operator={}",
                id, action, currentStatus, targetStatus.getCode(), UserContext.getUserId());
        return vo;
    }

    /** 按动作判权:权限码原样取自项目一权限表,不做角色推导 */
    private static void requirePermission(ToolWorkOrderAction action) {
        if (!UserContext.hasPermission(action.getPermCode())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "缺少操作权限: " + action.getPermCode());
        }
    }

    /**
     * 把动作分派到既有写方法,并返回预期的目标状态。
     * <p>
     * 参数在委托前校验:宁可返回明确的 400,也不能让 {@code Boolean.TRUE.equals(null)}
     * 把「没传 approved」静默解释成「驳回」。
     */
    private WorkOrderStatus execute(ToolWorkOrderAction action, Long id, ToolWorkOrderActionDTO dto) {
        return switch (action) {
            case REVIEW -> {
                boolean approved = requireApproved(action, dto);
                ReviewDTO reviewDTO = new ReviewDTO();
                reviewDTO.setApproved(approved);
                reviewDTO.setRemark(dto.getRemark());
                workOrderService.review(id, reviewDTO);
                yield approved ? WorkOrderStatus.PENDING_DISPATCH : WorkOrderStatus.REJECTED;
            }
            case DISPATCH, TRANSFER -> {
                // 派单与转派入参一致,交给 dispatch 按当前状态做精确判权与范围校验
                if (dto.getHandlerId() == null) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, action.getDesc() + "必须指定处理人 handlerId");
                }
                DispatchDTO dispatchDTO = new DispatchDTO();
                dispatchDTO.setHandlerId(dto.getHandlerId());
                dispatchDTO.setRemark(dto.getRemark());
                workOrderService.dispatch(id, dispatchDTO);
                yield WorkOrderStatus.PROCESSING;
            }
            case PROCESS -> {
                ProcessDTO processDTO = new ProcessDTO();
                processDTO.setRemark(dto.getRemark());
                workOrderService.process(id, processDTO);
                yield WorkOrderStatus.PENDING_ACCEPT;
            }
            case ACCEPT -> {
                boolean approved = requireApproved(action, dto);
                AcceptDTO acceptDTO = new AcceptDTO();
                acceptDTO.setApproved(approved);
                acceptDTO.setRemark(dto.getRemark());
                workOrderService.accept(id, acceptDTO);
                yield approved ? WorkOrderStatus.COMPLETED : WorkOrderStatus.PROCESSING;
            }
            case WITHDRAW -> {
                WithdrawDTO withdrawDTO = new WithdrawDTO();
                withdrawDTO.setRemark(dto.getRemark());
                workOrderService.withdraw(id, withdrawDTO);
                yield WorkOrderStatus.CANCELED;
            }
        };
    }

    /** 审核/验收必须显式给出结论,缺失时 400 而不是默认按驳回处理 */
    private static boolean requireApproved(ToolWorkOrderAction action, ToolWorkOrderActionDTO dto) {
        if (dto.getApproved() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    action.getDesc() + "必须提供结论 approved(true 通过 / false 驳回或退回)");
        }
        return dto.getApproved();
    }

    /** 面向模型的人读结果说明 */
    private static String resolveMessage(ToolWorkOrderAction action, ToolWorkOrderActionDTO dto) {
        return switch (action) {
            case REVIEW -> Boolean.TRUE.equals(dto.getApproved())
                    ? "审核通过,工单已流转至待派单"
                    : "审核驳回,工单已流转至已驳回";
            case DISPATCH -> "派单成功,工单已流转至处理中";
            case TRANSFER -> "转派成功,处理人已更换";
            case PROCESS -> "处理完成,工单已流转至待验收";
            case ACCEPT -> Boolean.TRUE.equals(dto.getApproved())
                    ? "验收通过,工单已完成"
                    : "验收退回,工单已退回处理中";
            case WITHDRAW -> "工单已撤回/取消";
        };
    }
}
