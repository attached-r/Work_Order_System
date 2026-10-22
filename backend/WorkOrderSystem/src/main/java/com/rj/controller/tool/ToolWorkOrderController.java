package com.rj.controller.tool;

import com.rj.common.PageResult;
import com.rj.common.annotation.RequiresPermission;
import com.rj.model.dto.ToolWorkOrderActionDTO;
import com.rj.model.vo.ToolWorkOrderStatusVO;
import com.rj.model.vo.WorkOrderDetailVO;
import com.rj.model.vo.WorkOrderVO;
import com.rj.service.IToolWorkOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent Tool 工单接口(供项目二 OpenFeign 调用),对应设计文档 §2.5。
 * <p>
 * <b>鉴权:</b>与普通接口一致——Agent 透传用户 JWT,{@code LoginInterceptor} 校验后把鉴权快照写入
 * {@code UserContext},后续数据范围过滤照常生效。
 * <p>
 * <b>返回体:</b>成功<b>直接返回业务对象,不套统一响应体</b>(文档要求「去除多余包装」);失败由
 * {@link com.rj.exception.ToolExceptionHandler} 输出精简的 {@code {"code","message"}}。
 * 本包({@code com.rj.controller.tool})即是该 advice 的作用边界。
 */
@Tag(name = "Agent Tool - 工单")
@RequestMapping("/tool/workorder")
@RestController
@RequiredArgsConstructor
public class ToolWorkOrderController {

    private final IToolWorkOrderService toolWorkOrderService;

    /**
     * 工单列表,支持状态/类型/关键字过滤。
     * <p>
     * {@code size} 会被服务层<b>静默钳制</b>到上限(50)以防撑爆大模型上下文,响应里的 size 字段
     * 即实际生效值。数据范围按当前登录用户自动收敛。
     */
    @Operation(summary = "工单列表(Agent)")
    @RequiresPermission("workorder:query")
    @GetMapping("/list")
    public PageResult<WorkOrderVO> list(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer orderType,
            @RequestParam(required = false) String keyword) {
        return toolWorkOrderService.list(current, size, status, orderType, keyword);
    }

    /**
     * 工单详情,含资源明细与操作日志。沿用 Service 内归属/部门校验,越权 403、不存在 404。
     */
    @Operation(summary = "工单详情(Agent)")
    @RequiresPermission("workorder:query")
    @GetMapping("/{id}")
    public WorkOrderDetailVO detail(@PathVariable Long id) {
        return toolWorkOrderService.detail(id);
    }

    /**
     * 执行工单动作:审核、派单、转派、处理完成、验收、撤回/取消。
     * <p>
     * 请求体必须同时给出 {@code action} 与 {@code expectedFromStatus}:前者锁定动作语义,
     * 后者是调用方确认时看到的状态;与库中最新状态不一致即 409,绝不重新解释动作。
     * <p>
     * <b>刻意不挂 {@code @RequiresPermission}:</b>单个注解的语义是「同时满足所列所有权限码」,
     * 而此接口按动作分别需要 {@code workorder:review}/{@code dispatch}/{@code transfer}/
     * {@code process}/{@code accept}/{@code withdraw} 之一,无法用一条注解表达。
     * 判权在 {@link com.rj.service.impl.ToolWorkOrderService#changeStatus} 内按动作逐个执行,
     * 且先于查库,避免无权限者探测工单是否存在。
     */
    @Operation(summary = "执行工单动作(Agent)")
    @PutMapping("/{id}/status")
    public ToolWorkOrderStatusVO changeStatus(@PathVariable Long id,
                                              @Valid @RequestBody ToolWorkOrderActionDTO dto) {
        return toolWorkOrderService.changeStatus(id, dto);
    }
}
